package gg.moonflower.etched.client.radio.net;

import gg.moonflower.etched.client.radio.AudioCancellation;
import gg.moonflower.etched.client.radio.RadioFailure;
import gg.moonflower.etched.client.radio.RadioResourceDisposer;

import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.Authenticator;
import java.net.CookieHandler;
import java.net.PasswordAuthentication;
import java.net.ProtocolException;
import java.net.Proxy;
import java.net.SocketTimeoutException;
import java.net.URI;
import java.net.URLConnection;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.CancellationException;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.FutureTask;

/**
 * A single-request HTTP transport with explicit redirects and connection ownership.
 */
public final class RadioHttpTransportImpl implements AudioHttpTransport {

    public static final Duration DEFAULT_CONNECT_TIMEOUT = Duration.ofSeconds(10);
    public static final Duration DEFAULT_READ_TIMEOUT = Duration.ofSeconds(15);
    public static final int DEFAULT_MAX_REDIRECTS = 5;
    public static final String USER_AGENT = "Re-Etched/4.1";
    public static final String ACCEPT = "audio/mpeg, audio/ogg, application/ogg, audio/x-mpegurl, "
            + "audio/mpegurl, application/x-mpegurl, audio/x-scpls, application/vnd.apple.mpegurl;q=0.5, "
            + "*/*;q=0.1";

    private final Proxy proxy;
    private final RadioNetworkPolicy networkPolicy;
    private final int connectTimeoutMillis;
    private final int readTimeoutMillis;
    private final int maxRedirects;
    private final ConnectionFactory connectionFactory;
    private final Authenticator proxyAuthenticator;

    public RadioHttpTransportImpl(Proxy proxy, RadioNetworkPolicy networkPolicy,
                                               Duration connectTimeout, Duration readTimeout,
                                               int maxRedirects) {
        this(proxy, networkPolicy, connectTimeout, readTimeout, maxRedirects,
                (uri, configuredProxy) -> {
                    URLConnection connection = uri.toURL().openConnection(configuredProxy);
                    if (!(connection instanceof HttpURLConnection httpConnection)) {
                        throw new ProtocolException("Radio URL did not create an HTTP connection");
                    }
                    return httpConnection;
                });
    }

    RadioHttpTransportImpl(Proxy proxy, RadioNetworkPolicy networkPolicy,
                                        Duration connectTimeout, Duration readTimeout,
                                        int maxRedirects, ConnectionFactory connectionFactory) {
        this.proxy = Objects.requireNonNull(proxy, "proxy");
        this.networkPolicy = Objects.requireNonNull(networkPolicy, "networkPolicy");
        this.connectTimeoutMillis = timeoutMillis(connectTimeout, "connectTimeout");
        this.readTimeoutMillis = timeoutMillis(readTimeout, "readTimeout");
        if (maxRedirects < 0) {
            throw new IllegalArgumentException("maxRedirects must not be negative");
        }
        this.maxRedirects = maxRedirects;
        this.connectionFactory = Objects.requireNonNull(connectionFactory, "connectionFactory");
        this.proxyAuthenticator = Authenticator.getDefault();
    }

    @Override
    public RadioHttpResponse execute(RadioHttpRequest request, AudioCancellation cancellation)
            throws RadioTransportException {
        Objects.requireNonNull(request, "request");
        Objects.requireNonNull(cancellation, "cancellation");
        ActiveExchange exchange = new ActiveExchange();
        cancellation.onCancel(exchange::cancelTerminal);
        URI current = removeFragment(request.uri());
        Set<URI> visited = new HashSet<>();
        int redirects = 0;
        boolean transferred = false;

        try {
            while (true) {
                cancellation.throwIfCancelled();
                this.networkPolicy.check(current, cancellation);
                cancellation.throwIfCancelled();
                if (!visited.add(current)) {
                    throw failure(RadioFailure.Code.TOO_MANY_REDIRECTS, false,
                            "Radio redirect loop detected", null);
                }

                ensureSafeGlobalState();
                HttpURLConnection connection = this.open(current);
                if (!exchange.installConnection(connection)) {
                    throw new CancellationException("Radio request was cancelled");
                }
                ensureSafeGlobalState();
                this.configure(connection, request.purpose());
                cancellation.throwIfCancelled();
                this.connect(exchange, connection, cancellation);
                cancellation.throwIfCancelled();
                int statusCode = this.responseCode(connection, cancellation);
                if (statusCode < 100 || statusCode > 599) {
                    throw failure(RadioFailure.Code.UNKNOWN, true,
                            "The radio host returned an invalid HTTP response", null);
                }
                if (isRedirect(statusCode)) {
                    if (redirects >= Math.min(this.maxRedirects, request.maxRedirects())) {
                        throw failure(RadioFailure.Code.TOO_MANY_REDIRECTS, false,
                                "Radio request exceeded the redirect limit", null);
                    }
                    current = resolveRedirect(current, connection.getHeaderField("Location"));
                    redirects++;
                    exchange.closeCurrent();
                    continue;
                }

                Map<String, List<String>> headers = normalizeHeaders(connection.getHeaderFields());
                InputStream body = this.openBody(connection, statusCode, cancellation);
                if (!exchange.installBody(connection, body)) {
                    throw new CancellationException("Radio request was cancelled");
                }
                RadioHttpResponse response = new RadioHttpResponse(
                        current, statusCode, headers, body, redirects, cancellation, exchange);
                cancellation.throwIfCancelled();
                transferred = true;
                return response;
            }
        } catch (RadioTransportException exception) {
            throw exception.withRedirectCount(redirects);
        } catch (RuntimeException exception) {
            cancellation.throwIfCancelled();
            throw exception;
        } finally {
            if (!transferred) {
                exchange.closeTerminal();
            }
        }
    }

    private HttpURLConnection open(URI uri) throws RadioTransportException {
        try {
            return this.connectionFactory.open(uri, this.proxy);
        } catch (IOException | IllegalArgumentException exception) {
            throw failure(RadioFailure.Code.INVALID_URL, false,
                    "Could not open the radio URL", exception);
        }
    }

    private void configure(HttpURLConnection connection, RadioHttpRequest.Purpose purpose)
            throws RadioTransportException {
        try {
            connection.setInstanceFollowRedirects(false);
            connection.setRequestMethod("GET");
            connection.setDoInput(true);
            connection.setUseCaches(false);
            connection.setConnectTimeout(this.connectTimeoutMillis);
            connection.setReadTimeout(this.readTimeoutMillis);
            connection.setRequestProperty("User-Agent", USER_AGENT);
            connection.setRequestProperty("Accept", ACCEPT);
            connection.setAuthenticator(new ProxyOnlyAuthenticator(this.proxyAuthenticator));
            if (purpose == RadioHttpRequest.Purpose.AUDIO) {
                connection.setRequestProperty("Icy-MetaData", "1");
            }
        } catch (IllegalStateException | ProtocolException exception) {
            throw failure(RadioFailure.Code.INVALID_URL, false,
                    "Could not configure the radio request", exception);
        }
    }

    private void connect(ActiveExchange exchange, HttpURLConnection connection,
                         AudioCancellation cancellation)
            throws RadioTransportException {
        try {
            exchange.connect(connection);
        } catch (SocketTimeoutException exception) {
            cancellation.throwIfCancelled();
            throw failure(RadioFailure.Code.CONNECT_TIMEOUT, true,
                    "Timed out while connecting to the radio host", exception);
        } catch (IOException exception) {
            cancellation.throwIfCancelled();
            throw failure(RadioFailure.Code.UNKNOWN, true,
                    "Could not connect to the radio host", exception);
        }
    }

    private static void ensureSafeGlobalState() throws RadioTransportException {
        if (CookieHandler.getDefault() != null) {
            throw failure(RadioFailure.Code.UNSAFE_HTTP_STATE, false,
                    "Global HTTP cookies are not allowed for radio requests", null);
        }
    }

    private int responseCode(HttpURLConnection connection, AudioCancellation cancellation)
            throws RadioTransportException {
        try {
            return connection.getResponseCode();
        } catch (SocketTimeoutException exception) {
            cancellation.throwIfCancelled();
            throw failure(RadioFailure.Code.READ_TIMEOUT, true,
                    "Timed out while waiting for radio response headers", exception);
        } catch (IOException exception) {
            cancellation.throwIfCancelled();
            throw failure(RadioFailure.Code.UNKNOWN, true,
                    "Could not read the radio response", exception);
        }
    }

    private InputStream openBody(HttpURLConnection connection, int statusCode,
                                 AudioCancellation cancellation) throws RadioTransportException {
        try {
            if (statusCode >= 400) {
                InputStream error = connection.getErrorStream();
                return error != null ? error : InputStream.nullInputStream();
            }
            return connection.getInputStream();
        } catch (SocketTimeoutException exception) {
            cancellation.throwIfCancelled();
            throw failure(RadioFailure.Code.READ_TIMEOUT, true,
                    "Timed out while opening the radio response", exception);
        } catch (IOException exception) {
            cancellation.throwIfCancelled();
            throw failure(RadioFailure.Code.UNKNOWN, true,
                    "Could not open the radio response", exception);
        }
    }

    private static URI resolveRedirect(URI current, String location) throws RadioTransportException {
        if (location == null || location.isBlank()) {
            throw failure(RadioFailure.Code.INVALID_URL, false,
                    "Radio redirect did not include a destination", null);
        }
        try {
            String reference = location.trim();
            if (reference.startsWith("?")) {
                String base = current.toString();
                int query = base.indexOf('?');
                return removeFragment(URI.create((query >= 0 ? base.substring(0, query) : base) + reference));
            }
            return removeFragment(current.resolve(reference));
        } catch (IllegalArgumentException exception) {
            throw failure(RadioFailure.Code.INVALID_URL, false,
                    "Radio redirect included an invalid destination", exception);
        }
    }

    private static URI removeFragment(URI uri) throws RadioTransportException {
        if (uri.getRawFragment() == null) {
            return uri;
        }
        try {
            String raw = uri.toString();
            return URI.create(raw.substring(0, raw.indexOf('#')));
        } catch (Exception exception) {
            throw failure(RadioFailure.Code.INVALID_URL, false, "Radio URL is invalid", exception);
        }
    }

    private static boolean isRedirect(int statusCode) {
        return statusCode == HttpURLConnection.HTTP_MOVED_PERM
                || statusCode == HttpURLConnection.HTTP_MOVED_TEMP
                || statusCode == HttpURLConnection.HTTP_SEE_OTHER
                || statusCode == 307
                || statusCode == 308;
    }

    private static Map<String, List<String>> normalizeHeaders(Map<String, List<String>> rawHeaders) {
        Map<String, List<String>> normalized = new HashMap<>();
        rawHeaders.forEach((name, values) -> {
            if (name == null || values == null) {
                return;
            }
            String normalizedName = name.toLowerCase(Locale.ROOT);
            List<String> destination = normalized.computeIfAbsent(normalizedName, ignored -> new ArrayList<>());
            for (String value : values) {
                if (value != null) {
                    destination.add(value);
                }
            }
        });
        normalized.replaceAll((name, values) -> List.copyOf(values));
        return Map.copyOf(normalized);
    }

    private static int timeoutMillis(Duration timeout, String name) {
        Objects.requireNonNull(timeout, name);
        long millis = timeout.toMillis();
        if (timeout.isNegative() || timeout.isZero() || millis <= 0 || millis > Integer.MAX_VALUE) {
            throw new IllegalArgumentException(name + " must be between 1ms and " + Integer.MAX_VALUE + "ms");
        }
        return (int) millis;
    }

    private static RadioTransportException failure(RadioFailure.Code code, boolean recoverable,
                                                   String message, Throwable cause) {
        return new RadioTransportException(code, recoverable, message, cause);
    }

    @FunctionalInterface
    interface ConnectionFactory {

        HttpURLConnection open(URI uri, Proxy proxy) throws IOException;
    }

    static final class ActiveExchange {

        private HttpURLConnection connection;
        private InputStream body;
        private FutureTask<Void> connectTask;
        private boolean terminal;

        boolean installConnection(HttpURLConnection connection) {
            boolean installed;
            synchronized (this) {
                installed = !this.terminal;
                if (installed) {
                    this.connection = connection;
                    this.body = null;
                }
            }
            if (!installed) {
                close(connection, null);
            }
            return installed;
        }

        void connect(HttpURLConnection connection) throws IOException {
            FutureTask<Void> task = new FutureTask<>(() -> {
                connection.connect();
                return null;
            });
            synchronized (this) {
                if (this.terminal || this.connection != connection) {
                    task.cancel(false);
                    throw new CancellationException("Radio request was cancelled");
                }
                this.connectTask = task;
            }

            task.run();
            try {
                task.get();
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
                throw new CancellationException("Radio request was interrupted");
            } catch (ExecutionException exception) {
                Throwable cause = exception.getCause();
                if (cause instanceof IOException ioException) {
                    throw ioException;
                }
                if (cause instanceof RuntimeException runtimeException) {
                    throw runtimeException;
                }
                throw new IOException("Radio connection failed", cause);
            } finally {
                synchronized (this) {
                    if (this.connectTask == task) {
                        this.connectTask = null;
                    }
                }
            }
        }

        boolean installBody(HttpURLConnection connection, InputStream body) {
            boolean installed;
            synchronized (this) {
                installed = !this.terminal && this.connection == connection;
                if (installed) {
                    this.body = body;
                }
            }
            if (!installed) {
                close(connection, body);
            }
            return installed;
        }

        void closeCurrent() {
            HttpURLConnection connection;
            InputStream body;
            synchronized (this) {
                connection = this.connection;
                body = this.body;
                if (this.connectTask != null) {
                    this.connectTask.cancel(false);
                    this.connectTask = null;
                }
                this.connection = null;
                this.body = null;
            }
            close(connection, body);
        }

        void closeTerminal() {
            CloseState state = this.detachTerminal();
            if (state != null) {
                close(state.connection(), state.body());
            }
        }

        void cancelTerminal() {
            CloseState state = this.detachTerminal();
            if (state != null) {
                RadioResourceDisposer.dispose(() -> close(state.connection(), state.body()));
            }
        }

        private CloseState detachTerminal() {
            HttpURLConnection connection;
            InputStream body;
            synchronized (this) {
                if (this.terminal) {
                    return null;
                }
                this.terminal = true;
                connection = this.connection;
                body = this.body;
                if (this.connectTask != null) {
                    this.connectTask.cancel(false);
                    this.connectTask = null;
                }
                this.connection = null;
                this.body = null;
            }
            return new CloseState(connection, body);
        }

        private static void close(HttpURLConnection connection, InputStream body) {
            try {
                if (connection != null) {
                    connection.disconnect();
                }
            } finally {
                closeBody(body);
            }
        }

        private static void closeBody(InputStream body) {
            if (body != null) {
                try {
                    body.close();
                } catch (IOException ignored) {
                }
            }
        }

        private record CloseState(HttpURLConnection connection, InputStream body) {
        }
    }

    private static final class ProxyOnlyAuthenticator extends Authenticator {

        private final Authenticator delegate;

        private ProxyOnlyAuthenticator(Authenticator delegate) {
            this.delegate = delegate;
        }

        @Override
        protected PasswordAuthentication getPasswordAuthentication() {
            if (this.delegate == null || this.getRequestorType() != RequestorType.PROXY) {
                return null;
            }
            return Authenticator.requestPasswordAuthentication(
                    this.delegate,
                    this.getRequestingHost(),
                    this.getRequestingSite(),
                    this.getRequestingPort(),
                    this.getRequestingProtocol(),
                    this.getRequestingPrompt(),
                    this.getRequestingScheme(),
                    this.getRequestingURL(),
                    this.getRequestorType());
        }
    }
}
