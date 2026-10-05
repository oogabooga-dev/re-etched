package gg.moonflower.etched.common.menu;

import gg.moonflower.etched.common.audio.AudioCancellation;
import gg.moonflower.etched.common.audio.AudioContentProbe;
import gg.moonflower.etched.common.audio.TestAudioContent;
import gg.moonflower.etched.common.audio.RadioFailure;
import gg.moonflower.etched.common.audio.net.AudioNetworkPolicy;
import gg.moonflower.etched.common.audio.net.RadioHttpTransportImpl;
import gg.moonflower.etched.common.audio.net.RadioTransportException;
import gg.moonflower.etched.common.audio.net.TestHttpServer;
import gg.moonflower.etched.common.audio.net.TestAudioHttpResponse;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.Proxy;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Arrays;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class EtchingUrlValidatorTest {

    @Test
    void usesGetAndAcceptsMpegContent() throws Exception {
        AtomicReference<String> method = new AtomicReference<>();
        try (TestHttpServer server = new TestHttpServer()) {
            server.handle("/track", exchange -> {
                method.set(exchange.getRequestMethod());
                exchange.getResponseHeaders().add("Content-Type", "Audio/Mpeg; charset=binary");
                exchange.sendResponseHeaders(200, 4);
                exchange.getResponseBody().write(TestAudioContent.mpeg());
                exchange.close();
            });
            validator(uri -> {}).check(server.uri("/track").toString(), new AudioCancellation());
            assertEquals("GET", method.get());
        }
    }

    @Test
    void rejectsEmptyBodiesAndFailedStatuses() throws Exception {
        try (TestHttpServer server = new TestHttpServer()) {
            server.handle("/missing", exchange -> {
                exchange.sendResponseHeaders(200, -1);
                exchange.close();
            });
            server.handle("/failed", exchange -> {
                exchange.sendResponseHeaders(404, -1);
                exchange.close();
            });
            assertThrows(IOException.class, () -> validator(uri -> {}).check(
                    server.uri("/missing").toString(), new AudioCancellation()));
            assertThrows(IOException.class, () -> validator(uri -> {}).check(
                    server.uri("/failed").toString(), new AudioCancellation()));
        }
    }

    @Test
    void acceptsRealAudioRegardlessOfMimeAndSuffix() throws Exception {
        try (TestHttpServer server = new TestHttpServer()) {
            for (String name : new String[]{"mono.mp3", "stereo-vbr-id3.mp3", "stereo.ogg"}) {
                byte[] body = TestAudioContent.fixture(name);
                server.handle("/" + name + ".html", exchange -> {
                    exchange.getResponseHeaders().add("Content-Type", "text/html");
                    exchange.sendResponseHeaders(200, body.length);
                    exchange.getResponseBody().write(body);
                    exchange.close();
                });
                validator(uri -> {}).check(server.uri("/" + name + ".html").toString(), new AudioCancellation());
            }
            server.handle("/no-mime", exchange -> {
                exchange.sendResponseHeaders(200, 4);
                exchange.getResponseBody().write(TestAudioContent.mpeg());
                exchange.close();
            });
            validator(uri -> {}).check(server.uri("/no-mime").toString(), new AudioCancellation());
        }
    }

    @Test
    void mimeAndExtensionCannotMakeUnsupportedContentIntoAudio() throws Exception {
        AtomicInteger playlistTargets = new AtomicInteger();
        try (TestHttpServer server = new TestHttpServer()) {
            server.handle("/target", exchange -> {
                playlistTargets.incrementAndGet();
                exchange.sendResponseHeaders(200, -1);
                exchange.close();
            });
            byte[][] bodies = {
                    "<html>not audio</html>".getBytes(StandardCharsets.UTF_8),
                    "ID3-audio".getBytes(StandardCharsets.UTF_8),
                    "OggS-not-vorbis".getBytes(StandardCharsets.UTF_8),
                    ("#EXTM3U\n" + server.uri("/target")).getBytes(StandardCharsets.UTF_8),
                    "RIFF....WAVE".getBytes(StandardCharsets.UTF_8),
                    new byte[]{(byte) 0xFF, (byte) 0xF1, 0, 0},
                    new byte[]{(byte) 0xFF, (byte) 0xFB, 0, 0}
            };
            for (int i = 0; i < bodies.length; i++) {
                byte[] body = bodies[i];
                String path = "/fake" + i + ".mp3";
                server.handle(path, exchange -> {
                    exchange.getResponseHeaders().add("Content-Type", "audio/mpeg");
                    exchange.sendResponseHeaders(200, body.length);
                    exchange.getResponseBody().write(body);
                    exchange.close();
                });
                assertThrows(IOException.class, () -> validator(uri -> {}).check(
                        server.uri(path).toString(), new AudioCancellation()));
            }
            assertEquals(0, playlistTargets.get());
        }
    }

    @Test
    void liveAudioAndOversizedId3HeadersDoNotWaitForEof() throws Exception {
        byte[] oversized = Arrays.copyOf(TestAudioContent.taggedMpeg(
                AudioContentProbe.DEFAULT_MAX_ID3_PREFIX_BYTES), 10);
        for (byte[] body : new byte[][]{TestAudioContent.mpeg(), TestAudioContent.taggedMpeg(16 * 1024), oversized}) {
            CountDownLatch release = new CountDownLatch(1);
            try (TestHttpServer server = new TestHttpServer()) {
                server.handle("/live", exchange -> {
                    exchange.sendResponseHeaders(200, 0);
                    exchange.getResponseBody().write(body);
                    exchange.getResponseBody().flush();
                    try {
                        release.await(5, TimeUnit.SECONDS);
                    } catch (InterruptedException exception) {
                        Thread.currentThread().interrupt();
                    } finally {
                        exchange.close();
                    }
                });
                CompletableFuture<Void> request = CompletableFuture.runAsync(() -> {
                    try {
                        validator(uri -> {}).check(server.uri("/live").toString(), new AudioCancellation());
                    } catch (IOException exception) {
                        throw new CompletionException(exception);
                    }
                });
                try {
                    if (body == oversized) {
                        ExecutionException error = assertThrows(ExecutionException.class,
                                () -> request.get(2, TimeUnit.SECONDS));
                        assertInstanceOf(IOException.class, error.getCause());
                    } else {
                        request.get(2, TimeUnit.SECONDS);
                    }
                } finally {
                    release.countDown();
                }
            }
        }
    }

    @Test
    void closesResponsesOnAcceptanceRejectionAndReadFailure() throws Exception {
        for (int scenario = 0; scenario < 3; scenario++) {
            AtomicBoolean closed = new AtomicBoolean();
            int current = scenario;
            InputStream body = new InputStream() {
                private final InputStream delegate = new ByteArrayInputStream(
                        current == 0 ? TestAudioContent.mpeg() : new byte[0]);

                @Override
                public int read() throws IOException {
                    return this.delegate.read();
                }

                @Override
                public int read(byte[] bytes, int offset, int length) throws IOException {
                    if (current == 2) {
                        throw new IOException("Fixture read failure");
                    }
                    return this.delegate.read(bytes, offset, length);
                }

                @Override
                public void close() {
                    closed.set(true);
                }
            };
            EtchingUrlValidator validator = new EtchingUrlValidator((request, cancellation) ->
                    TestAudioHttpResponse.owned(body, cancellation));
            if (current == 0) {
                validator.check("https://audio.example/track", new AudioCancellation());
            } else {
                assertThrows(IOException.class, () -> validator.check(
                        "https://audio.example/track", new AudioCancellation()));
            }
            assertTrue(closed.get(), "Response leaked in scenario " + current);
        }
    }

    @Test
    void cancellationRetiresAnExchangeWaitingForTheId3Body() throws Exception {
        CountDownLatch started = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        AudioCancellation cancellation = new AudioCancellation();
        try (TestHttpServer server = new TestHttpServer()) {
            server.handle("/slow-tag", exchange -> {
                exchange.sendResponseHeaders(200, 0);
                exchange.getResponseBody().write(Arrays.copyOf(TestAudioContent.taggedMpeg(100), 10));
                exchange.getResponseBody().flush();
                started.countDown();
                try {
                    release.await(5, TimeUnit.SECONDS);
                } catch (InterruptedException exception) {
                    Thread.currentThread().interrupt();
                } finally {
                    exchange.close();
                }
            });
            CompletableFuture<Void> request = CompletableFuture.runAsync(() -> {
                try {
                    validator(uri -> {}).check(server.uri("/slow-tag").toString(), cancellation);
                } catch (IOException exception) {
                    throw new CompletionException(exception);
                }
            });
            try {
                assertTrue(started.await(2, TimeUnit.SECONDS));
                cancellation.cancel();
                ExecutionException error = assertThrows(ExecutionException.class,
                        () -> request.get(2, TimeUnit.SECONDS));
                assertInstanceOf(CancellationException.class, error.getCause());
            } finally {
                release.countDown();
            }
        }
    }

    @Test
    void validatesRedirectDestinationsBeforeOpeningThem() throws Exception {
        AtomicInteger blockedRequests = new AtomicInteger();
        try (TestHttpServer server = new TestHttpServer()) {
            server.handle("/start", exchange -> {
                exchange.getResponseHeaders().add("Location", "/blocked");
                exchange.sendResponseHeaders(302, -1);
                exchange.close();
            });
            server.handle("/blocked", exchange -> {
                blockedRequests.incrementAndGet();
                exchange.sendResponseHeaders(200, -1);
                exchange.close();
            });
            EtchingUrlValidator validator = validator(uri -> {
                if (uri.getPath().equals("/blocked")) {
                    throw new RadioTransportException(RadioFailure.Code.BLOCKED_ADDRESS, false, "blocked", null);
                }
            });
            RadioTransportException error = assertThrows(RadioTransportException.class,
                    () -> validator.check(server.uri("/start").toString(), new AudioCancellation()));
            assertEquals(RadioFailure.Code.BLOCKED_ADDRESS, error.code());
            assertEquals(0, blockedRequests.get());
        }
    }

    @Test
    void productionPolicyRejectsLoopbackAndNonHttpInput() {
        EtchingUrlValidator validator = new EtchingUrlValidator(Proxy.NO_PROXY);
        RadioTransportException error = assertThrows(RadioTransportException.class,
                () -> validator.check("http://127.0.0.1:1/track", new AudioCancellation()));
        assertEquals(RadioFailure.Code.BLOCKED_ADDRESS, error.code());
        assertThrows(RadioTransportException.class,
                () -> validator.check("file:///etc/passwd", new AudioCancellation()));
    }

    @Test
    void preCancelledValidationDoesNotStartAnExchange() {
        AtomicInteger requests = new AtomicInteger();
        EtchingUrlValidator validator = validator(uri -> requests.incrementAndGet());
        AudioCancellation cancellation = new AudioCancellation();
        cancellation.cancel();
        assertThrows(CancellationException.class,
                () -> validator.check("https://audio.example/track", cancellation));
        assertEquals(0, requests.get());
    }

    @Test
    void cancellationRetiresAnExchangeWaitingForResponseHeaders() throws Exception {
        CountDownLatch started = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        AudioCancellation cancellation = new AudioCancellation();
        try (TestHttpServer server = new TestHttpServer()) {
            server.handle("/slow", exchange -> {
                started.countDown();
                try {
                    release.await(5, TimeUnit.SECONDS);
                } catch (InterruptedException exception) {
                    Thread.currentThread().interrupt();
                } finally {
                    exchange.close();
                }
            });
            CompletableFuture<Void> request = CompletableFuture.runAsync(() -> {
                try {
                    validator(uri -> {}).check(server.uri("/slow").toString(), cancellation);
                } catch (IOException exception) {
                    throw new CompletionException(exception);
                }
            });
            try {
                assertTrue(started.await(2, TimeUnit.SECONDS));
                cancellation.cancel();
                ExecutionException error = assertThrows(ExecutionException.class,
                        () -> request.get(2, TimeUnit.SECONDS));
                assertInstanceOf(CancellationException.class, error.getCause());
            } finally {
                release.countDown();
            }
        }
    }

    private static EtchingUrlValidator validator(AudioNetworkPolicy policy) {
        return new EtchingUrlValidator(new RadioHttpTransportImpl(Proxy.NO_PROXY, policy,
                Duration.ofSeconds(1), Duration.ofSeconds(1), 2));
    }
}
