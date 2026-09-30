package gg.moonflower.etched.common.menu;

import gg.moonflower.etched.common.audio.AudioCancellation;
import gg.moonflower.etched.common.audio.RadioFailure;
import gg.moonflower.etched.common.audio.net.AudioNetworkPolicy;
import gg.moonflower.etched.common.audio.net.RadioHttpTransportImpl;
import gg.moonflower.etched.common.audio.net.RadioTransportException;
import gg.moonflower.etched.common.audio.net.TestHttpServer;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.Proxy;
import java.time.Duration;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class EtchingUrlValidatorTest {

    @Test
    void usesGetAndAcceptsTheExistingMimeForms() throws Exception {
        AtomicReference<String> method = new AtomicReference<>();
        try (TestHttpServer server = new TestHttpServer()) {
            server.handle("/track", exchange -> {
                method.set(exchange.getRequestMethod());
                exchange.getResponseHeaders().add("Content-Type", "Audio/Mpeg; charset=binary");
                exchange.sendResponseHeaders(200, 1);
                exchange.getResponseBody().write(42);
                exchange.close();
            });
            validator(uri -> {}).check(server.uri("/track").toString(), new AudioCancellation());
            assertEquals("GET", method.get());
        }
    }

    @Test
    void rejectsMissingMimeAndFailedStatuses() throws Exception {
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
