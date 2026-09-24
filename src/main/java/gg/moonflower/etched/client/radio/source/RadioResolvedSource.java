package gg.moonflower.etched.client.radio.source;

import gg.moonflower.etched.client.radio.AudioCancellation;
import gg.moonflower.etched.client.radio.net.RadioHttpResponse;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.SequenceInputStream;
import java.net.URI;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.OptionalLong;

/**
 * Owns one independently opened station response.
 */
public final class RadioResolvedSource implements AutoCloseable {

    private final URI uri;
    private final Format format;
    private final Map<String, List<String>> headers;
    private final OptionalLong contentLength;
    private final List<URI> stationEndpoints;
    private final RadioHttpResponse response;
    private final InputStream body;

    RadioResolvedSource(RadioHttpResponse response, Format format, byte[] prefix,
                        List<URI> stationEndpoints, AudioCancellation cancellation) {
        this.response = Objects.requireNonNull(response, "response");
        this.uri = response.uri();
        this.format = Objects.requireNonNull(format, "format");
        this.headers = response.headers();
        this.contentLength = response.contentLength();
        this.stationEndpoints = List.copyOf(stationEndpoints);
        this.body = new ReplayBody(prefix, response, cancellation);
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
            this.response.close();
        }
    }

    public enum Format {
        MP3,
        OGG
    }

    private static final class ReplayBody extends InputStream {

        private final SequenceInputStream delegate;
        private final RadioHttpResponse response;
        private final AudioCancellation cancellation;
        private volatile boolean closed;

        private ReplayBody(byte[] prefix, RadioHttpResponse response, AudioCancellation cancellation) {
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
