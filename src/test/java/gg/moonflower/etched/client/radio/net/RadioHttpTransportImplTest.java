package gg.moonflower.etched.client.radio.net;

import com.sun.net.httpserver.Headers;
import gg.moonflower.etched.client.radio.RadioFailure;
import gg.moonflower.etched.client.radio.RadioSession;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.Authenticator;
import java.net.CookieHandler;
import java.net.PasswordAuthentication;
import java.net.Proxy;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RadioHttpTransportImplTest {

    private static final Duration TEST_TIMEOUT = Duration.ofSeconds(2);
    private static final RadioNetworkPolicy ALLOW_TEST_SERVER = uri -> {
    };

    @Test
    void sendsControlledAudioHeadersAndPerformsOneGet() throws Exception {
        AtomicInteger requests = new AtomicInteger();
        AtomicReference<Headers> requestHeaders = new AtomicReference<>();
        AtomicReference<String> requestMethod = new AtomicReference<>();
        byte[] audio = "test-audio".getBytes(StandardCharsets.UTF_8);
        try (TestHttpServer server = new TestHttpServer()) {
            server.handle("/stream", exchange -> {
                requests.incrementAndGet();
                requestHeaders.set(exchange.getRequestHeaders());
                requestMethod.set(exchange.getRequestMethod());
                exchange.getResponseHeaders().add("X-Radio-Test", "present");
                exchange.sendResponseHeaders(200, audio.length);
                exchange.getResponseBody().write(audio);
                exchange.close();
            });

            RadioSession.Attempt attempt = attempt();
            try (RadioHttpResponse response = transport().execute(
                    RadioHttpRequest.audio(server.uri("/stream#ignored")), attempt.cancellation())) {
                assertEquals(200, response.statusCode());
                assertEquals(server.uri("/stream"), response.uri());
                assertEquals(audio.length, response.contentLength().orElseThrow());
                assertEquals("present", response.firstHeader("X-RADIO-TEST").orElseThrow());
                assertArrayEquals(audio, response.body().readAllBytes());
            }

            Headers headers = requestHeaders.get();
            assertEquals(1, requests.get());
            assertEquals("GET", requestMethod.get());
            assertEquals("Re-Etched/4.1", RadioHttpTransportImpl.USER_AGENT);
            assertEquals(RadioHttpTransportImpl.USER_AGENT, headers.getFirst("User-Agent"));
            assertEquals(RadioHttpTransportImpl.ACCEPT, headers.getFirst("Accept"));
            assertEquals("1", headers.getFirst("Icy-MetaData"));
            assertFalse(headers.containsKey("X-Minecraft-Username"));
            assertFalse(headers.containsKey("X-Minecraft-UUID"));
        }
    }

    @Test
    void resourceRequestDoesNotAskForIcyMetadata() throws Exception {
        AtomicReference<Headers> requestHeaders = new AtomicReference<>();
        try (TestHttpServer server = new TestHttpServer()) {
            server.handle("/playlist", exchange -> {
                requestHeaders.set(exchange.getRequestHeaders());
                exchange.sendResponseHeaders(200, -1);
                exchange.close();
            });

            try (RadioHttpResponse ignored = transport().execute(
                    RadioHttpRequest.resource(server.uri("/playlist")), attempt().cancellation())) {
                assertFalse(requestHeaders.get().containsKey("Icy-MetaData"));
            }
        }
    }

    @Test
    void doesNotForwardGlobalJvmCookies() throws Exception {
        CookieHandler previous = CookieHandler.getDefault();
        CookieHandler.setDefault(new CookieHandler() {
            @Override
            public Map<String, List<String>> get(URI uri, Map<String, List<String>> requestHeaders) {
                return Map.of("Cookie", List.of("identity=leaked"));
            }

            @Override
            public void put(URI uri, Map<String, List<String>> responseHeaders) {
            }
        });
        AtomicInteger requests = new AtomicInteger();
        try (TestHttpServer server = new TestHttpServer()) {
            server.handle("/resource", exchange -> {
                requests.incrementAndGet();
                exchange.sendResponseHeaders(200, -1);
                exchange.close();
            });

            RadioTransportException exception = assertThrows(RadioTransportException.class,
                    () -> transport().execute(RadioHttpRequest.resource(server.uri("/resource")),
                            attempt().cancellation()));

            assertEquals(RadioFailure.Code.UNSAFE_HTTP_STATE, exception.code());
            assertEquals(0, requests.get());
        } finally {
            CookieHandler.setDefault(previous);
        }
    }

    @Test
    void rechecksGlobalCookiesBeforeFollowingRedirects() throws Exception {
        CookieHandler previous = CookieHandler.getDefault();
        AtomicInteger finalRequests = new AtomicInteger();
        CookieHandler injected = new CookieHandler() {
            @Override
            public Map<String, List<String>> get(URI uri, Map<String, List<String>> requestHeaders) {
                return Map.of("Cookie", List.of("identity=leaked"));
            }

            @Override
            public void put(URI uri, Map<String, List<String>> responseHeaders) {
            }
        };
        try (TestHttpServer server = new TestHttpServer()) {
            server.handle("/redirect", exchange -> {
                CookieHandler.setDefault(injected);
                redirect(exchange, "/final");
            });
            server.handle("/final", exchange -> {
                finalRequests.incrementAndGet();
                exchange.sendResponseHeaders(200, -1);
                exchange.close();
            });

            RadioTransportException exception = assertThrows(RadioTransportException.class,
                    () -> transport().execute(RadioHttpRequest.resource(server.uri("/redirect")),
                            attempt().cancellation()));

            assertEquals(RadioFailure.Code.UNSAFE_HTTP_STATE, exception.code());
            assertEquals(0, finalRequests.get());
        } finally {
            CookieHandler.setDefault(previous);
        }
    }

    @Test
    void doesNotUseGlobalAuthenticatorForOriginCredentials() throws Exception {
        Authenticator previous = Authenticator.getDefault();
        AtomicInteger authentications = new AtomicInteger();
        Authenticator.setDefault(new Authenticator() {
            @Override
            protected PasswordAuthentication getPasswordAuthentication() {
                authentications.incrementAndGet();
                return new PasswordAuthentication("identity", "secret".toCharArray());
            }
        });
        AtomicInteger requests = new AtomicInteger();
        try (TestHttpServer server = new TestHttpServer()) {
            server.handle("/protected", exchange -> {
                requests.incrementAndGet();
                exchange.getResponseHeaders().add("WWW-Authenticate", "Basic realm=\"radio\"");
                exchange.sendResponseHeaders(401, -1);
                exchange.close();
            });

            try (RadioHttpResponse response = transport().execute(
                    RadioHttpRequest.resource(server.uri("/protected")), attempt().cancellation())) {
                assertEquals(401, response.statusCode());
            }

            assertEquals(1, requests.get());
            assertEquals(0, authentications.get());
        } finally {
            Authenticator.setDefault(previous);
        }
    }

    @Test
    void delegatesAuthenticationOnlyForTheConfiguredProxy() throws Exception {
        Authenticator previous = Authenticator.getDefault();
        AtomicInteger proxyAuthentications = new AtomicInteger();
        AtomicInteger originAuthentications = new AtomicInteger();
        Authenticator.setDefault(new Authenticator() {
            @Override
            protected PasswordAuthentication getPasswordAuthentication() {
                if (this.getRequestorType() == Authenticator.RequestorType.PROXY) {
                    proxyAuthentications.incrementAndGet();
                    return new PasswordAuthentication("proxy-user", "proxy-password".toCharArray());
                }
                originAuthentications.incrementAndGet();
                return new PasswordAuthentication("origin-user", "origin-password".toCharArray());
            }
        });
        AtomicInteger requests = new AtomicInteger();
        try (TestHttpServer proxyServer = new TestHttpServer()) {
            proxyServer.handle("/", exchange -> {
                requests.incrementAndGet();
                if (exchange.getRequestHeaders().getFirst("Proxy-Authorization") == null) {
                    exchange.getResponseHeaders().add("Proxy-Authenticate", "Basic realm=\"radio-proxy\"");
                    exchange.sendResponseHeaders(407, -1);
                } else {
                    exchange.sendResponseHeaders(200, -1);
                }
                exchange.close();
            });
            Proxy proxy = new Proxy(Proxy.Type.HTTP, proxyServer.address());
            RadioHttpTransportImpl transport = new RadioHttpTransportImpl(
                    proxy, ALLOW_TEST_SERVER, TEST_TIMEOUT, TEST_TIMEOUT, 0);

            try (RadioHttpResponse response = transport.execute(
                    RadioHttpRequest.resource(URI.create("http://radio.example/live")),
                    attempt().cancellation())) {
                assertEquals(200, response.statusCode());
            }

            assertEquals(2, requests.get());
            assertEquals(1, proxyAuthentications.get());
            assertEquals(0, originAuthentications.get());
        } finally {
            Authenticator.setDefault(previous);
        }
    }

    @Test
    void followsRelativeRedirectAndChecksEveryDestination() throws Exception {
        AtomicInteger startRequests = new AtomicInteger();
        AtomicInteger streamRequests = new AtomicInteger();
        List<URI> checked = new java.util.concurrent.CopyOnWriteArrayList<>();
        try (TestHttpServer server = new TestHttpServer()) {
            server.handle("/start", exchange -> {
                startRequests.incrementAndGet();
                exchange.getResponseHeaders().add("Location", "/stream");
                exchange.sendResponseHeaders(302, -1);
                exchange.close();
            });
            server.handle("/stream", exchange -> {
                streamRequests.incrementAndGet();
                exchange.sendResponseHeaders(200, 0);
                exchange.close();
            });
            RadioHttpTransportImpl transport = new RadioHttpTransportImpl(
                    Proxy.NO_PROXY, checked::add, TEST_TIMEOUT, TEST_TIMEOUT, 5);

            try (RadioHttpResponse response = transport.execute(
                    RadioHttpRequest.audio(server.uri("/start")), attempt().cancellation())) {
                assertEquals(server.uri("/stream"), response.uri());
            }

            assertEquals(List.of(server.uri("/start"), server.uri("/stream")), checked);
            assertEquals(1, startRequests.get());
            assertEquals(1, streamRequests.get());
        }
    }

    @Test
    void queryOnlyRedirectRetainsTheCurrentPath() throws Exception {
        AtomicInteger requests = new AtomicInteger();
        try (TestHttpServer server = new TestHttpServer()) {
            server.handle("/station/live", exchange -> {
                requests.incrementAndGet();
                if (exchange.getRequestURI().getRawQuery() == null) {
                    redirect(exchange, "?quality=high");
                } else {
                    exchange.sendResponseHeaders(200, -1);
                    exchange.close();
                }
            });

            try (RadioHttpResponse response = transport().execute(
                    RadioHttpRequest.audio(server.uri("/station/live")), attempt().cancellation())) {
                assertEquals(server.uri("/station/live?quality=high"), response.uri());
            }
            assertEquals(2, requests.get());
        }
    }

    @Test
    void rejectsRedirectLoopsAndRedirectsPastTheLimit() throws Exception {
        try (TestHttpServer server = new TestHttpServer()) {
            server.handle("/loop-a", exchange -> redirect(exchange, "/loop-b"));
            server.handle("/loop-b", exchange -> redirect(exchange, "/loop-a"));
            server.handle("/one", exchange -> redirect(exchange, "/two"));
            server.handle("/two", exchange -> redirect(exchange, "/three"));
            server.handle("/three", exchange -> {
                exchange.sendResponseHeaders(200, -1);
                exchange.close();
            });

            RadioTransportException loop = assertThrows(RadioTransportException.class,
                    () -> transport().execute(RadioHttpRequest.audio(server.uri("/loop-a")),
                            attempt().cancellation()));
            RadioHttpTransportImpl oneRedirect = new RadioHttpTransportImpl(
                    Proxy.NO_PROXY, ALLOW_TEST_SERVER, TEST_TIMEOUT, TEST_TIMEOUT, 1);
            RadioTransportException limit = assertThrows(RadioTransportException.class,
                    () -> oneRedirect.execute(RadioHttpRequest.audio(server.uri("/one")),
                            attempt().cancellation()));

            assertEquals(RadioFailure.Code.TOO_MANY_REDIRECTS, loop.code());
            assertEquals(RadioFailure.Code.TOO_MANY_REDIRECTS, limit.code());
            assertFalse(loop.recoverable());
            assertFalse(limit.recoverable());
        }
    }

    @Test
    void checksPolicyBeforeConnectingToRedirectedMetadataAddress() throws Exception {
        try (TestHttpServer server = new TestHttpServer()) {
            server.handle("/redirect", exchange ->
                    redirect(exchange, "http://169.254.169.254/latest/meta-data"));
            RadioHttpTransportImpl transport = new RadioHttpTransportImpl(
                    Proxy.NO_PROXY, new DefaultRadioNetworkPolicy(() -> true),
                    TEST_TIMEOUT, TEST_TIMEOUT, 5);

            RadioTransportException exception = assertThrows(RadioTransportException.class,
                    () -> transport.execute(RadioHttpRequest.audio(server.uri("/redirect")),
                            attempt().cancellation()));

            assertEquals(RadioFailure.Code.BLOCKED_ADDRESS, exception.code());
        }
    }

    @Test
    void exposesErrorStatusesWithoutAnotherRequest() throws Exception {
        AtomicInteger requests = new AtomicInteger();
        try (TestHttpServer server = new TestHttpServer()) {
            for (int status : List.of(404, 429, 500, 503)) {
                server.handle("/status/" + status, exchange -> {
                    requests.incrementAndGet();
                    byte[] body = ("status-" + status).getBytes(StandardCharsets.UTF_8);
                    exchange.sendResponseHeaders(status, body.length);
                    exchange.getResponseBody().write(body);
                    exchange.close();
                });
            }

            for (int status : List.of(404, 429, 500, 503)) {
                try (RadioHttpResponse response = transport().execute(
                        RadioHttpRequest.resource(server.uri("/status/" + status)), attempt().cancellation())) {
                    assertEquals(status, response.statusCode());
                    assertEquals("status-" + status,
                            new String(response.body().readAllBytes(), StandardCharsets.UTF_8));
                }
            }
            assertEquals(4, requests.get());
        }
    }

    @Test
    void distinguishesFixedAndChunkedContentLength() throws Exception {
        byte[] body = "body".getBytes(StandardCharsets.UTF_8);
        try (TestHttpServer server = new TestHttpServer()) {
            server.handle("/fixed", exchange -> {
                exchange.sendResponseHeaders(200, body.length);
                exchange.getResponseBody().write(body);
                exchange.close();
            });
            server.handle("/chunked", exchange -> {
                exchange.sendResponseHeaders(200, 0);
                exchange.getResponseBody().write(body);
                exchange.close();
            });

            try (RadioHttpResponse fixed = transport().execute(
                    RadioHttpRequest.audio(server.uri("/fixed")), attempt().cancellation());
                 RadioHttpResponse chunked = transport().execute(
                         RadioHttpRequest.audio(server.uri("/chunked")), attempt().cancellation())) {
                assertEquals(body.length, fixed.contentLength().orElseThrow());
                assertTrue(chunked.contentLength().isEmpty());
            }
        }
    }

    @Test
    void classifiesSlowHeadersAndSlowBodyAsReadTimeouts() throws Exception {
        CountDownLatch headerRequest = new CountDownLatch(1);
        CountDownLatch releaseHeaders = new CountDownLatch(1);
        CountDownLatch bodyRequest = new CountDownLatch(1);
        CountDownLatch releaseBody = new CountDownLatch(1);
        try (TestHttpServer server = new TestHttpServer()) {
            server.handle("/slow-headers", exchange -> {
                headerRequest.countDown();
                await(releaseHeaders);
                exchange.sendResponseHeaders(200, -1);
                exchange.close();
            });
            server.handle("/slow-body", exchange -> {
                exchange.sendResponseHeaders(200, 0);
                exchange.getResponseBody().flush();
                bodyRequest.countDown();
                await(releaseBody);
                exchange.close();
            });
            RadioHttpTransportImpl transport = new RadioHttpTransportImpl(
                    Proxy.NO_PROXY, ALLOW_TEST_SERVER, TEST_TIMEOUT, Duration.ofMillis(100), 5);

            try {
                RadioTransportException headers = assertThrows(RadioTransportException.class,
                        () -> transport.execute(RadioHttpRequest.audio(server.uri("/slow-headers")),
                                attempt().cancellation()));
                assertTrue(headerRequest.await(1, TimeUnit.SECONDS));
                assertEquals(RadioFailure.Code.READ_TIMEOUT, headers.code());

                try (RadioHttpResponse response = transport.execute(
                        RadioHttpRequest.audio(server.uri("/slow-body")), attempt().cancellation())) {
                    assertTrue(bodyRequest.await(1, TimeUnit.SECONDS));
                    RadioTransportException bodyTimeout = assertThrows(RadioTransportException.class,
                            () -> response.body().read());
                    assertEquals(RadioFailure.Code.READ_TIMEOUT, bodyTimeout.code());
                }
            } finally {
                releaseHeaders.countDown();
                releaseBody.countDown();
            }
        }
    }

    @Test
    void cancellationDisconnectsARequestBlockedOnHeaders() throws Exception {
        CountDownLatch requestStarted = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        try (TestHttpServer server = new TestHttpServer()) {
            server.handle("/blocked", exchange -> {
                requestStarted.countDown();
                await(release);
                exchange.sendResponseHeaders(200, -1);
                exchange.close();
            });
            RadioSession session = new RadioSession();
            RadioSession.Attempt attempt = session.start("http://radio.example/live");
            CompletableFuture<RadioHttpResponse> response = CompletableFuture.supplyAsync(() -> {
                try {
                    return transport().execute(RadioHttpRequest.audio(server.uri("/blocked")),
                            attempt.cancellation());
                } catch (RadioTransportException exception) {
                    throw new java.util.concurrent.CompletionException(exception);
                }
            });

            try {
                assertTrue(requestStarted.await(1, TimeUnit.SECONDS));
                session.stop();
                ExecutionException exception = assertThrows(ExecutionException.class,
                        () -> response.get(2, TimeUnit.SECONDS));
                assertInstanceOf(java.util.concurrent.CancellationException.class, exception.getCause());
            } finally {
                release.countDown();
            }
        }
    }

    @Test
    void cancellationDuringPolicyPreventsConnectionCreation() throws Exception {
        CountDownLatch policyStarted = new CountDownLatch(1);
        CountDownLatch releasePolicy = new CountDownLatch(1);
        AtomicInteger connections = new AtomicInteger();
        RadioNetworkPolicy policy = uri -> {
            policyStarted.countDown();
            await(releasePolicy);
        };
        RadioHttpTransportImpl transport = new RadioHttpTransportImpl(
                Proxy.NO_PROXY, policy, TEST_TIMEOUT, TEST_TIMEOUT, 0,
                (uri, proxy) -> {
                    connections.incrementAndGet();
                    return new TrackingConnection();
                });
        RadioSession session = new RadioSession();
        RadioSession.Attempt attempt = session.start("http://radio.example/live");
        CompletableFuture<RadioHttpResponse> response = CompletableFuture.supplyAsync(() -> {
            try {
                return transport.execute(RadioHttpRequest.audio(URI.create("http://radio.example/live")),
                        attempt.cancellation());
            } catch (RadioTransportException exception) {
                throw new java.util.concurrent.CompletionException(exception);
            }
        });

        assertTrue(policyStarted.await(1, TimeUnit.SECONDS));
        session.stop();
        releasePolicy.countDown();

        ExecutionException exception = assertThrows(ExecutionException.class,
                () -> response.get(2, TimeUnit.SECONDS));
        assertInstanceOf(CancellationException.class, exception.getCause());
        assertEquals(0, connections.get());
    }

    @Test
    void cancellationDuringConnectCancelsAndDisconnectsTheConnection() throws Exception {
        CountDownLatch connectStarted = new CountDownLatch(1);
        CountDownLatch disconnected = new CountDownLatch(1);
        TrackingConnection connection = new TrackingConnection() {
            @Override
            public void connect() throws IOException {
                connectStarted.countDown();
                try {
                    disconnected.await(5, TimeUnit.SECONDS);
                } catch (InterruptedException exception) {
                    Thread.currentThread().interrupt();
                    throw new IOException("connect interrupted", exception);
                }
            }

            @Override
            public void disconnect() {
                super.disconnect();
                disconnected.countDown();
            }
        };
        RadioHttpTransportImpl transport = new RadioHttpTransportImpl(
                Proxy.NO_PROXY, ALLOW_TEST_SERVER, TEST_TIMEOUT, TEST_TIMEOUT, 0,
                (uri, proxy) -> connection);
        RadioSession session = new RadioSession();
        RadioSession.Attempt attempt = session.start("http://radio.example/live");
        CompletableFuture<RadioHttpResponse> response = CompletableFuture.supplyAsync(() -> {
            try {
                return transport.execute(RadioHttpRequest.audio(URI.create("http://radio.example/live")),
                        attempt.cancellation());
            } catch (RadioTransportException exception) {
                throw new java.util.concurrent.CompletionException(exception);
            }
        });

        assertTrue(connectStarted.await(1, TimeUnit.SECONDS));
        session.stop();

        ExecutionException exception = assertThrows(ExecutionException.class,
                () -> response.get(2, TimeUnit.SECONDS));
        assertInstanceOf(CancellationException.class, exception.getCause());
        assertTrue(connection.disconnected);
    }

    @Test
    void cancellationDoesNotWaitForBlockingDisconnect() throws Exception {
        CountDownLatch disconnectStarted = new CountDownLatch(1);
        CountDownLatch releaseDisconnect = new CountDownLatch(1);
        TrackingConnection connection = new TrackingConnection() {
            @Override
            public void disconnect() {
                disconnectStarted.countDown();
                await(releaseDisconnect);
                super.disconnect();
            }
        };
        RadioHttpTransportImpl transport = new RadioHttpTransportImpl(
                Proxy.NO_PROXY, ALLOW_TEST_SERVER, TEST_TIMEOUT, TEST_TIMEOUT, 0,
                (uri, proxy) -> connection);
        RadioSession session = new RadioSession();
        RadioSession.Attempt attempt = session.start("http://radio.example/live");
        RadioHttpResponse response = transport.execute(
                RadioHttpRequest.audio(URI.create(attempt.source())), attempt.cancellation());

        ExecutorService clientThread = Executors.newSingleThreadExecutor();
        Future<Boolean> stopped = clientThread.submit(session::stop);
        try {
            assertTrue(stopped.get(5, TimeUnit.SECONDS));
            assertTrue(disconnectStarted.await(5, TimeUnit.SECONDS));
        } finally {
            releaseDisconnect.countDown();
            response.close();
            clientThread.shutdownNow();
        }
        await(() -> connection.disconnected);
    }

    @Test
    void cancellationDoesNotWaitForBlockingResponseClose() throws Exception {
        CountDownLatch closeStarted = new CountDownLatch(1);
        CountDownLatch releaseClose = new CountDownLatch(1);
        AtomicReference<Boolean> bodyClosed = new AtomicReference<>(false);
        TrackingConnection connection = new TrackingConnection() {
            @Override
            public InputStream getInputStream() {
                return new ByteArrayInputStream(new byte[0]) {
                    @Override
                    public void close() throws IOException {
                        closeStarted.countDown();
                        await(releaseClose);
                        bodyClosed.set(true);
                        super.close();
                    }
                };
            }
        };
        RadioHttpTransportImpl transport = new RadioHttpTransportImpl(
                Proxy.NO_PROXY, ALLOW_TEST_SERVER, TEST_TIMEOUT, TEST_TIMEOUT, 0,
                (uri, proxy) -> connection);
        RadioSession session = new RadioSession();
        RadioSession.Attempt attempt = session.start("http://radio.example/live");
        RadioHttpResponse response = transport.execute(
                RadioHttpRequest.audio(URI.create(attempt.source())), attempt.cancellation());

        ExecutorService clientThread = Executors.newSingleThreadExecutor();
        Future<Boolean> stopped = clientThread.submit(session::stop);
        try {
            assertTrue(stopped.get(5, TimeUnit.SECONDS));
            assertTrue(closeStarted.await(5, TimeUnit.SECONDS));
        } finally {
            releaseClose.countDown();
            response.close();
            clientThread.shutdownNow();
        }
        await(bodyClosed::get);
    }

    @Test
    void cancellationClosesResponseBodyWhenDisconnectFails() throws Exception {
        AtomicReference<Boolean> bodyClosed = new AtomicReference<>(false);
        TrackingConnection connection = new TrackingConnection() {
            @Override
            public void disconnect() {
                throw new IllegalStateException("disconnect failed");
            }

            @Override
            public InputStream getInputStream() {
                return new ByteArrayInputStream(new byte[0]) {
                    @Override
                    public void close() throws IOException {
                        bodyClosed.set(true);
                        super.close();
                    }
                };
            }
        };
        RadioHttpTransportImpl transport = new RadioHttpTransportImpl(
                Proxy.NO_PROXY, ALLOW_TEST_SERVER, TEST_TIMEOUT, TEST_TIMEOUT, 0,
                (uri, proxy) -> connection);
        RadioSession session = new RadioSession();
        RadioSession.Attempt attempt = session.start("http://radio.example/live");
        RadioHttpResponse response = transport.execute(
                RadioHttpRequest.audio(URI.create(attempt.source())), attempt.cancellation());

        session.stop();

        await(bodyClosed::get);
        response.close();
    }

    @Test
    void classifiesMidBodyDisconnectAsRecoverableTransportFailure() throws Exception {
        TrackingConnection connection = new TrackingConnection() {
            @Override
            public InputStream getInputStream() {
                return new InputStream() {
                    @Override
                    public int read() throws IOException {
                        throw new IOException("connection reset");
                    }
                };
            }
        };
        RadioHttpTransportImpl transport = new RadioHttpTransportImpl(
                Proxy.NO_PROXY, ALLOW_TEST_SERVER, TEST_TIMEOUT, TEST_TIMEOUT, 0,
                (uri, proxy) -> connection);

        try (RadioHttpResponse response = transport.execute(
                RadioHttpRequest.audio(URI.create("http://radio.example/live")), attempt().cancellation())) {
            RadioTransportException exception = assertThrows(RadioTransportException.class,
                    () -> response.body().read());
            assertEquals(RadioFailure.Code.UNKNOWN, exception.code());
            assertTrue(exception.recoverable());
        }
    }

    @Test
    void cancellationWinsOverUncheckedDisconnectFailureDuringBodyRead() throws Exception {
        CountDownLatch readStarted = new CountDownLatch(1);
        CountDownLatch disconnected = new CountDownLatch(1);
        TrackingConnection connection = new TrackingConnection() {
            @Override
            public InputStream getInputStream() {
                return new InputStream() {
                    @Override
                    public int read() {
                        readStarted.countDown();
                        await(disconnected);
                        throw new IllegalStateException("connection was disconnected");
                    }
                };
            }

            @Override
            public void disconnect() {
                super.disconnect();
                disconnected.countDown();
            }
        };
        RadioHttpTransportImpl transport = new RadioHttpTransportImpl(
                Proxy.NO_PROXY, ALLOW_TEST_SERVER, TEST_TIMEOUT, TEST_TIMEOUT, 0,
                (uri, proxy) -> connection);
        RadioSession session = new RadioSession();
        RadioSession.Attempt attempt = session.start("http://radio.example/live");
        try (RadioHttpResponse response = transport.execute(
                RadioHttpRequest.audio(URI.create("http://radio.example/live")),
                attempt.cancellation())) {
            CompletableFuture<Integer> read = CompletableFuture.supplyAsync(() -> {
                try {
                    return response.body().read();
                } catch (IOException exception) {
                    throw new java.util.concurrent.CompletionException(exception);
                }
            });

            assertTrue(readStarted.await(1, TimeUnit.SECONDS));
            session.stop();

            ExecutionException exception = assertThrows(ExecutionException.class,
                    () -> read.get(2, TimeUnit.SECONDS));
            assertInstanceOf(CancellationException.class, exception.getCause());
        }
    }

    @Test
    void cancellationWinsAfterBlockedBodyReadReturns() throws Exception {
        CountDownLatch bodyStarted = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        try (TestHttpServer server = new TestHttpServer()) {
            server.handle("/blocked-body", exchange -> {
                exchange.sendResponseHeaders(200, 0);
                exchange.getResponseBody().flush();
                bodyStarted.countDown();
                await(release);
                exchange.close();
            });
            RadioSession session = new RadioSession();
            RadioSession.Attempt attempt = session.start("http://radio.example/live");
            try (RadioHttpResponse response = transport().execute(
                    RadioHttpRequest.audio(server.uri("/blocked-body")), attempt.cancellation())) {
                assertTrue(bodyStarted.await(1, TimeUnit.SECONDS));
                CompletableFuture<Integer> read = CompletableFuture.supplyAsync(() -> {
                    try {
                        return response.body().read();
                    } catch (IOException exception) {
                        throw new java.util.concurrent.CompletionException(exception);
                    }
                });

                session.stop();
                release.countDown();

                ExecutionException exception = assertThrows(ExecutionException.class,
                        () -> read.get(2, TimeUnit.SECONDS));
                assertInstanceOf(CancellationException.class, exception.getCause());
            } finally {
                release.countDown();
            }
        }
    }

    @Test
    void usesInjectedProxyAndClosesReturnedConnectionOnCancellation() throws Exception {
        Proxy proxy = new Proxy(Proxy.Type.HTTP, new java.net.InetSocketAddress("127.0.0.1", 3128));
        AtomicReference<Proxy> usedProxy = new AtomicReference<>();
        AtomicReference<URI> openedUri = new AtomicReference<>();
        TrackingConnection connection = new TrackingConnection();
        RadioHttpTransportImpl transport = new RadioHttpTransportImpl(
                proxy, ALLOW_TEST_SERVER, TEST_TIMEOUT, TEST_TIMEOUT, 0,
                (uri, suppliedProxy) -> {
                    openedUri.set(uri);
                    usedProxy.set(suppliedProxy);
                    return connection;
                });
        RadioSession session = new RadioSession();
        RadioSession.Attempt attempt = session.start("http://radio.example/live");

        URI requested = URI.create("http://radio.example/a%20b%23c?q=x%20y%23z#ignored");
        RadioHttpResponse response = transport.execute(RadioHttpRequest.audio(requested), attempt.cancellation());
        session.stop();

        assertEquals(proxy, usedProxy.get());
        assertEquals(URI.create("http://radio.example/a%20b%23c?q=x%20y%23z"), openedUri.get());
        assertFalse(connection.getInstanceFollowRedirects());
        assertEquals("GET", connection.getRequestMethod());
        assertEquals((int) TEST_TIMEOUT.toMillis(), connection.getConnectTimeout());
        assertEquals((int) TEST_TIMEOUT.toMillis(), connection.getReadTimeout());
        await(() -> connection.disconnected && connection.body.closed);
        response.close();
    }

    @Test
    void classifiesConnectTimeoutSeparatelyFromReadTimeout() throws Exception {
        TrackingConnection connection = new TrackingConnection() {
            @Override
            public void connect() throws java.net.SocketTimeoutException {
                throw new java.net.SocketTimeoutException("connect timeout");
            }
        };
        RadioHttpTransportImpl transport = new RadioHttpTransportImpl(
                Proxy.NO_PROXY, ALLOW_TEST_SERVER, TEST_TIMEOUT, TEST_TIMEOUT, 0,
                (uri, proxy) -> connection);

        RadioTransportException exception = assertThrows(RadioTransportException.class,
                () -> transport.execute(RadioHttpRequest.audio(URI.create("http://radio.example/live")),
                        attempt().cancellation()));

        assertEquals(RadioFailure.Code.CONNECT_TIMEOUT, exception.code());
        assertTrue(exception.recoverable());
    }

    @Test
    void validatesTimeoutAndRedirectConfiguration() {
        assertThrows(IllegalArgumentException.class, () -> new RadioHttpTransportImpl(
                Proxy.NO_PROXY, ALLOW_TEST_SERVER, Duration.ZERO, TEST_TIMEOUT, 5));
        assertThrows(IllegalArgumentException.class, () -> new RadioHttpTransportImpl(
                Proxy.NO_PROXY, ALLOW_TEST_SERVER, TEST_TIMEOUT, Duration.ofNanos(1), 5));
        assertThrows(IllegalArgumentException.class, () -> new RadioHttpTransportImpl(
                Proxy.NO_PROXY, ALLOW_TEST_SERVER, TEST_TIMEOUT, TEST_TIMEOUT, -1));
    }

    private static RadioHttpTransportImpl transport() {
        return new RadioHttpTransportImpl(
                Proxy.NO_PROXY, ALLOW_TEST_SERVER, TEST_TIMEOUT, TEST_TIMEOUT, 5);
    }

    private static RadioSession.Attempt attempt() {
        return new RadioSession().start("http://radio.example/live");
    }

    private static void redirect(com.sun.net.httpserver.HttpExchange exchange, String location) throws IOException {
        exchange.getResponseHeaders().add("Location", location);
        exchange.sendResponseHeaders(302, -1);
        exchange.close();
    }

    private static void await(CountDownLatch latch) {
        try {
            latch.await(5, TimeUnit.SECONDS);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
        }
    }

    private static void await(java.util.function.BooleanSupplier condition) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (!condition.getAsBoolean()) {
            if (System.nanoTime() >= deadline) {
                throw new AssertionError("Timed out waiting for asynchronous radio cleanup");
            }
            Thread.sleep(10L);
        }
    }

    private static class TrackingConnection extends HttpURLConnection {

        private final TrackingInputStream body = new TrackingInputStream();
        private volatile boolean disconnected;

        private TrackingConnection() throws IOException {
            super(URI.create("http://radio.example/live").toURL());
        }

        @Override
        public void disconnect() {
            this.disconnected = true;
        }

        @Override
        public boolean usingProxy() {
            return false;
        }

        @Override
        public void connect() throws IOException {
            this.connected = true;
        }

        @Override
        public int getResponseCode() {
            return 200;
        }

        @Override
        public void setAuthenticator(Authenticator authenticator) {
        }

        @Override
        public InputStream getInputStream() {
            return this.body;
        }

        @Override
        public Map<String, List<String>> getHeaderFields() {
            return Map.of("Content-Length", List.of("0"));
        }
    }

    private static final class TrackingInputStream extends ByteArrayInputStream {

        private volatile boolean closed;

        private TrackingInputStream() {
            super(new byte[0]);
        }

        @Override
        public void close() throws IOException {
            this.closed = true;
            super.close();
        }
    }
}
