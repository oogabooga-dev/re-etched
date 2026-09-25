package gg.moonflower.etched.client.radio.source;

import gg.moonflower.etched.client.radio.AudioCancellation;
import gg.moonflower.etched.client.cache.BoundedMediaCache;
import gg.moonflower.etched.client.radio.net.AudioHttpResponse;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.PushbackInputStream;
import java.io.SequenceInputStream;
import java.net.URI;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.OptionalLong;
import org.jetbrains.annotations.Nullable;

/**
 * Owns one independently opened station response.
 */
public final class RadioResolvedSource implements AutoCloseable {

    private final URI uri;
    private final Format format;
    private final Map<String, List<String>> headers;
    private final OptionalLong contentLength;
    private final List<URI> stationEndpoints;
    private final @Nullable AudioHttpResponse response;
    private final InputStream body;

    RadioResolvedSource(AudioHttpResponse response, Format format, byte[] prefix,
                        List<URI> stationEndpoints, AudioCancellation cancellation) {
        this.response = Objects.requireNonNull(response, "response");
        this.uri = response.uri();
        this.format = Objects.requireNonNull(format, "format");
        this.headers = response.headers();
        this.contentLength = response.contentLength();
        this.stationEndpoints = List.copyOf(stationEndpoints);
        this.body = new ReplayBody(prefix, response, cancellation);
    }

    /** Each cached source owns a separate pinned file stream, never a shared response. */
    public static RadioResolvedSource cached(URI uri, BoundedMediaCache.Lease lease,
                                             AudioCancellation cancellation) throws IOException {
        PushbackInputStream input = new PushbackInputStream(lease.body(), 4);
        try {
            byte[] prefix = input.readNBytes(4);
            if (prefix.length != 4) {
                throw new IOException("Incomplete cached audio header");
            }
            input.unread(prefix);
            Format format = prefix[0] == 'O' && prefix[1] == 'g' && prefix[2] == 'g'
                    && prefix[3] == 'S' ? Format.OGG : Format.MP3;
            return new RadioResolvedSource(uri, format, input, lease, cancellation);
        } catch (IOException | RuntimeException exception) {
            lease.close();
            throw exception;
        }
    }

    private RadioResolvedSource(URI uri, Format format, InputStream input,
                                BoundedMediaCache.Lease lease, AudioCancellation cancellation) {
        this.response = null;
        this.uri = uri;
        this.format = format;
        this.headers = Map.of();
        this.contentLength = OptionalLong.empty();
        this.stationEndpoints = List.of(uri);
        this.body = new InputStream() {
            @Override
            public int read() throws IOException {
                cancellation.throwIfCancelled();
                return input.read();
            }

            @Override
            public int read(byte[] bytes, int offset, int length) throws IOException {
                cancellation.throwIfCancelled();
                return input.read(bytes, offset, length);
            }

            @Override
            public void close() throws IOException {
                lease.close();
            }
        };
        cancellation.onCancel(() -> {
            try {
                lease.close();
            } catch (IOException ignored) {
            }
        });
    }

    public URI uri() {
        return this.uri;
    }

    public Format format() {
        return this.format;
    }

    public Map<String, List<String>> headers() {
        return this.headers;
    }

    public OptionalLong contentLength() {
        return this.contentLength;
    }

    public List<URI> stationEndpoints() {
        return this.stationEndpoints;
    }

    public InputStream body() {
        return this.body;
    }

    @Override
    public void close() {
        try {
            this.body.close();
        } catch (IOException ignored) {
            if (this.response != null) {
                this.response.close();
            }
        }
    }

    public enum Format {
        MP3,
        OGG
    }

    private static final class ReplayBody extends InputStream {

        private final SequenceInputStream delegate;
        private final AudioHttpResponse response;
        private final AudioCancellation cancellation;
        private volatile boolean closed;

        private ReplayBody(byte[] prefix, AudioHttpResponse response, AudioCancellation cancellation) {
            this.delegate = new SequenceInputStream(new ByteArrayInputStream(prefix), response.body());
            this.response = response;
            this.cancellation = cancellation;
        }

        @Override
        public int read() throws IOException {
            this.requireOpen();
            return this.delegate.read();
        }

        @Override
        public int read(byte[] bytes, int offset, int length) throws IOException {
            this.requireOpen();
            return this.delegate.read(bytes, offset, length);
        }

        @Override
        public long skip(long count) throws IOException {
            this.requireOpen();
            return this.delegate.skip(count);
        }

        @Override
        public int available() throws IOException {
            this.requireOpen();
            return this.delegate.available();
        }

        @Override
        public void close() throws IOException {
            if (this.closed) {
                return;
            }
            this.closed = true;
            try {
                this.delegate.close();
            } finally {
                this.response.close();
            }
        }

        private void requireOpen() throws IOException {
            this.cancellation.throwIfCancelled();
            if (this.closed) {
                throw new IOException("Radio source is closed");
            }
        }
    }
}
