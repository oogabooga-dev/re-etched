package gg.moonflower.etched.client.radio.net;

import gg.moonflower.etched.client.radio.AudioCancellation;
import gg.moonflower.etched.client.radio.RadioFailure;

import java.io.IOException;
import java.io.InputStream;
import java.net.SocketTimeoutException;
import java.net.URI;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.OptionalLong;

/**
 * Owns the final response stream and its underlying connection.
 */
public final class RadioHttpResponse implements AutoCloseable {

    private final URI uri;
    private final int statusCode;
    private final Map<String, List<String>> headers;
    private final OptionalLong contentLength;
    private final int redirectCount;
    private final InputStream body;
    private final RadioHttpTransportImpl.ActiveExchange exchange;

    RadioHttpResponse(URI uri, int statusCode, Map<String, List<String>> headers, InputStream rawBody,
                      int redirectCount, AudioCancellation cancellation,
                      RadioHttpTransportImpl.ActiveExchange exchange) {
        this.uri = Objects.requireNonNull(uri, "uri");
        this.statusCode = statusCode;
        this.headers = Objects.requireNonNull(headers, "headers");
        this.contentLength = parseContentLength(headers.get("content-length"));
        this.redirectCount = redirectCount;
        this.exchange = Objects.requireNonNull(exchange, "exchange");
        this.body = new ManagedBody(Objects.requireNonNull(rawBody, "rawBody"), cancellation);
    }

    public URI uri() {
        return this.uri;
    }

    public int statusCode() {
        return this.statusCode;
    }

    public Map<String, List<String>> headers() {
        return this.headers;
    }

    public Optional<String> firstHeader(String name) {
        List<String> values = this.headers.get(name.toLowerCase(Locale.ROOT));
        return values == null || values.isEmpty() ? Optional.empty() : Optional.of(values.get(0));
    }

    public OptionalLong contentLength() {
        return this.contentLength;
    }

    public int redirectCount() {
        return this.redirectCount;
    }

    public InputStream body() {
        return this.body;
    }

    @Override
    public void close() {
        this.exchange.closeTerminal();
    }

    private static OptionalLong parseContentLength(List<String> values) {
        if (values == null || values.isEmpty()) {
            return OptionalLong.empty();
        }

        Long parsed = null;
        for (String value : values) {
            for (String part : value.split(",")) {
                long current;
                try {
                    current = Long.parseLong(part.trim());
                } catch (NumberFormatException exception) {
                    return OptionalLong.empty();
                }
                if (current < 0 || parsed != null && parsed != current) {
                    return OptionalLong.empty();
                }
                parsed = current;
            }
        }
        return parsed == null ? OptionalLong.empty() : OptionalLong.of(parsed);
    }

    private final class ManagedBody extends InputStream {

        private final InputStream delegate;
        private final AudioCancellation cancellation;

        private ManagedBody(InputStream delegate, AudioCancellation cancellation) {
            this.delegate = delegate;
            this.cancellation = cancellation;
        }

        @Override
        public int read() throws IOException {
            this.cancellation.throwIfCancelled();
            try {
                int value = this.delegate.read();
                this.cancellation.throwIfCancelled();
                return value;
            } catch (SocketTimeoutException exception) {
                this.cancellation.throwIfCancelled();
                throw new RadioTransportException(RadioFailure.Code.READ_TIMEOUT, true,
                        "Timed out while reading the radio response", exception);
            } catch (IOException exception) {
                this.cancellation.throwIfCancelled();
                throw interruptedResponse(exception);
            } catch (RuntimeException exception) {
                this.cancellation.throwIfCancelled();
                throw exception;
            }
        }

        @Override
        public int read(byte[] bytes, int offset, int length) throws IOException {
            this.cancellation.throwIfCancelled();
            try {
                int read = this.delegate.read(bytes, offset, length);
                this.cancellation.throwIfCancelled();
                return read;
            } catch (SocketTimeoutException exception) {
                this.cancellation.throwIfCancelled();
                throw new RadioTransportException(RadioFailure.Code.READ_TIMEOUT, true,
                        "Timed out while reading the radio response", exception);
            } catch (IOException exception) {
                this.cancellation.throwIfCancelled();
                throw interruptedResponse(exception);
            } catch (RuntimeException exception) {
                this.cancellation.throwIfCancelled();
                throw exception;
            }
        }

        @Override
        public long skip(long count) throws IOException {
            this.cancellation.throwIfCancelled();
            try {
                long skipped = this.delegate.skip(count);
                this.cancellation.throwIfCancelled();
                return skipped;
            } catch (SocketTimeoutException exception) {
                this.cancellation.throwIfCancelled();
                throw new RadioTransportException(RadioFailure.Code.READ_TIMEOUT, true,
                        "Timed out while reading the radio response", exception);
            } catch (IOException exception) {
                this.cancellation.throwIfCancelled();
                throw interruptedResponse(exception);
            } catch (RuntimeException exception) {
                this.cancellation.throwIfCancelled();
                throw exception;
            }
        }

        @Override
        public int available() throws IOException {
            this.cancellation.throwIfCancelled();
            try {
                return this.delegate.available();
            } catch (IOException exception) {
                this.cancellation.throwIfCancelled();
                throw interruptedResponse(exception);
            } catch (RuntimeException exception) {
                this.cancellation.throwIfCancelled();
                throw exception;
            }
        }

        @Override
        public void close() {
            RadioHttpResponse.this.close();
        }

        private static RadioTransportException interruptedResponse(IOException cause) {
            return new RadioTransportException(RadioFailure.Code.UNKNOWN, true,
                    "The radio response ended unexpectedly", cause);
        }
    }
}
