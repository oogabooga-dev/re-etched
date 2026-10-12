package gg.moonflower.etched.client.radio;

import gg.moonflower.etched.client.radio.net.RadioHttpTransportImpl;
import gg.moonflower.etched.client.radio.net.RadioNetworkPolicy;
import gg.moonflower.etched.client.radio.net.TestHttpServer;
import gg.moonflower.etched.client.radio.source.*;
import gg.moonflower.etched.client.radio.stream.RadioAudioStream;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.net.Proxy;
import java.net.URI;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

class RecordStreamLoaderTest {
    private final ExecutorService resolvers = Executors.newFixedThreadPool(2);
    private final ExecutorService producers = Executors.newFixedThreadPool(8);
    private final ExecutorService decoders = Executors.newFixedThreadPool(2);
    private TestHttpServer server;
    private byte[] mp3;
    private final AtomicInteger requests = new AtomicInteger();

    @BeforeEach
    void setup() throws Exception {
        this.server = new TestHttpServer();
        try (var input = getClass().getResourceAsStream("/gg/moonflower/etched/client/radio/audio/stereo-long.mp3")) {
            this.mp3 = input.readAllBytes();
        }
        this.server.handle("/disc.mp3", exchange -> {
            this.requests.incrementAndGet();
            exchange.getResponseHeaders().set("Content-Type", "audio/mpeg");
            exchange.sendResponseHeaders(200, this.mp3.length);
            try (exchange; var output = exchange.getResponseBody()) {
                output.write(this.mp3);
            }
        });
    }

    @AfterEach
    void cleanup() {
        this.server.close();
        this.resolvers.shutdownNow();
        this.producers.shutdownNow();
        this.decoders.shutdownNow();
    }

    private RecordStreamLoader loader(RadioSourceProgramResolver resolver) {
        RadioNetworkPolicy allowFixture = ignored -> { };
        return new RecordStreamLoader(resolver, cancellation -> new RadioResolveContext(
                new RadioHttpTransportImpl(Proxy.NO_PROXY, allowFixture, Duration.ofSeconds(2),
                        Duration.ofSeconds(2), 2), allowFixture, cancellation, RadioResolveLimits.DEFAULT),
                this.resolvers, this.producers, this.decoders);
    }

    @Test
    void twoDevicesDecodeTheWholeSameTrackIndependently() throws Exception {
        RecordStreamLoader loader = this.loader(new DirectRadioSourceResolver(true));
        String url = this.server.uri("/disc.mp3").toString();
        try (var first = loader.open(url, true); var second = loader.open(url, true)) {
            RadioAudioStream a = first.stream().get(5, TimeUnit.SECONDS);
            RadioAudioStream b = second.stream().get(5, TimeUnit.SECONDS);
            assertNotSame(a, b);
            assertTrue(first.transfer(a));
            assertTrue(second.transfer(b));
            assertFalse(first.transfer(a));
            try (a; b) {
                byte[] expected = drain(a);
                assertTrue(expected.length > 100_000);
                first.close();
                assertArrayEquals(expected, drain(b));
                assertEquals(RadioAudioStream.TerminalState.EOF, b.termination().toCompletableFuture().join().state());
            }
        }
        assertEquals(2, this.requests.get());
    }

    @Test
    void stopBeforeHandoffClosesPreparationAndRejectsTransfer() throws Exception {
        RecordStreamLoader loader = this.loader(new DirectRadioSourceResolver());
        var load = loader.open(this.server.uri("/disc.mp3").toString(), false);
        RadioAudioStream audio = load.stream().get(5, TimeUnit.SECONDS);
        load.close();
        assertFalse(load.transfer(audio));
        assertEquals(RadioAudioStream.TerminalState.CLOSED,
                audio.termination().toCompletableFuture().get(5, TimeUnit.SECONDS).state());
    }

    @Test
    void cancelledLateResolutionCannotOpenMedia() throws Exception {
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        AtomicInteger opens = new AtomicInteger();
        RadioSourceProgramResolver resolver = new RadioSourceProgramResolver() {
            public boolean supports(URI input) { return true; }

            public RadioSourceProgram resolveProgram(URI input, RadioResolveContext context) {
                entered.countDown();
                try {
                    release.await();
                } catch (InterruptedException ignored) {
                    // Deliberately emulate a provider that returns a result after cancellation.
                }
                return new RadioSourceProgram(RadioSourceProgram.Kind.SERVICE_TRACKS, input, List.of(
                        new RadioSourceProgram.Track(input, "Track", next -> {
                            next.cancellation().throwIfCancelled();
                            opens.incrementAndGet();
                            return new DirectRadioSourceResolver().resolve(input, next);
                        })));
            }
        };
        var load = this.loader(resolver).open(this.server.uri("/disc.mp3").toString(), true);
        assertTrue(entered.await(5, TimeUnit.SECONDS));
        load.close();
        release.countDown();
        this.resolvers.shutdown();
        assertTrue(this.resolvers.awaitTermination(5, TimeUnit.SECONDS));
        assertTrue(load.stream().isCancelled());
        assertEquals(0, opens.get());
    }

    @Test
    void activeDiscLimitIsReleasedByStop() throws Exception {
        RecordStreamLoader loader = this.loader(new DirectRadioSourceResolver());
        String url = this.server.uri("/disc.mp3").toString();
        var loads = new java.util.ArrayList<RecordStreamLoader.Load>();
        try {
            for (int i = 0; i < 8; i++) {
                var load = loader.open(url, true);
                loads.add(load);
                load.stream().get(5, TimeUnit.SECONDS);
            }
            try (var rejected = loader.open(url, true)) {
                assertInstanceOf(RejectedExecutionException.class,
                        assertThrows(ExecutionException.class, () -> rejected.stream().get()).getCause());
            }
            loads.get(0).close();
            try (var replacement = loader.open(url, true)) {
                assertNotNull(replacement.stream().get(5, TimeUnit.SECONDS));
            }
        } finally {
            loads.forEach(RecordStreamLoader.Load::close);
        }
    }

    @Test
    void failedSourcesDoNotConsumeAllPlaybackSlots() throws Exception {
        RecordStreamLoader loader = this.loader(new DirectRadioSourceResolver());
        for (int i = 0; i < 12; i++) {
            try (var load = loader.open(this.server.uri("/missing.mp3").toString(), true)) {
                assertThrows(ExecutionException.class, () -> load.stream().get(5, TimeUnit.SECONDS));
            }
        }
        try (var load = loader.open(this.server.uri("/disc.mp3").toString(), true)) {
            assertNotNull(load.stream().get(5, TimeUnit.SECONDS));
        }
    }

    private static byte[] drain(RadioAudioStream audio) throws Exception {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        while (true) {
            var pcm = audio.read(64 * 1024);
            if (!pcm.hasRemaining()) {
                return output.toByteArray();
            }
            byte[] bytes = new byte[pcm.remaining()];
            pcm.get(bytes);
            output.write(bytes);
        }
    }
}
