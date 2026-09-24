package gg.moonflower.etched.client.radio.stream;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import gg.moonflower.etched.client.radio.PlaybackSession;
import gg.moonflower.etched.client.radio.net.AudioNetworkPolicy;
import gg.moonflower.etched.client.radio.net.RadioHttpTransportImpl;
import gg.moonflower.etched.client.radio.source.AudioResolveContext;
import gg.moonflower.etched.client.radio.source.DirectRadioSourceResolver;
import gg.moonflower.etched.client.radio.source.RadioResolveLimits;
import gg.moonflower.etched.client.radio.source.RadioResolvedSource;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.Proxy;
import java.net.URI;
import java.nio.ByteBuffer;
import java.time.Duration;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RadioStreamPipelineTest {

    private final ExecutorService producers = Executors.newFixedThreadPool(2);
    private final ExecutorService decoders = Executors.newFixedThreadPool(2);
    private HttpServer server;
    private URI uri;
    private byte[] mp3;
    private final AtomicInteger requests = new AtomicInteger();

    @BeforeEach
    void setUp() throws Exception {
        try (var fixture = RadioStreamPipelineTest.class.getResourceAsStream(
                "/gg/moonflower/etched/client/radio/audio/mono.mp3")) {
            if (fixture == null) {
                throw new IllegalStateException("Missing MP3 fixture");
            }
            this.mp3 = fixture.readAllBytes();
        }
        this.server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        this.server.createContext("/radio", this::serveIcyMp3);
        this.server.start();
        this.uri = URI.create("http://127.0.0.1:" + this.server.getAddress().getPort() + "/radio");
    }

    @AfterEach
    void tearDown() {
        this.server.stop(0);
        this.producers.shutdownNow();
        this.decoders.shutdownNow();
    }

    @Test
    void resolvesBuffersStripsIcyAndDecodesUsingOneGet() throws Exception {
        PlaybackSession session = new PlaybackSession();
        PlaybackSession.Attempt attempt = session.start(this.uri.toString());
        RadioResolvedSource source = this.resolve(attempt);
        RadioStreamPipeline.Preparation preparation = RadioStreamPipeline.prepare(source,
                attempt.cancellation(), this.producers, this.decoders, true,
                title -> session.offerStreamTitle(attempt, title));
        try (preparation) {
            RadioAudioStream audio = preparation.stream().toCompletableFuture().get(5, TimeUnit.SECONDS);
            try (audio) {
                assertEquals(1, audio.getFormat().getChannels());
                assertEquals(22_050.0F, audio.getFormat().getSampleRate());
                assertTrue(drain(audio) > 4_000);
            }
        }
        assertEquals(1, this.requests.get());
        assertTrue(session.applyPendingStreamTitle());
        assertEquals("T", session.snapshot().streamTitle());
    }

    @Test
    void identicalUrlsProduceIndependentBuffersAndDecoders() throws Exception {
        PlaybackSession.Attempt firstAttempt = new PlaybackSession().start(this.uri.toString());
        PlaybackSession.Attempt secondAttempt = new PlaybackSession().start(this.uri.toString());
        RadioStreamPipeline.Preparation first = RadioStreamPipeline.prepare(
                this.resolve(firstAttempt), firstAttempt.cancellation(), this.producers, this.decoders, true);
        RadioStreamPipeline.Preparation second = RadioStreamPipeline.prepare(
                this.resolve(secondAttempt), secondAttempt.cancellation(), this.producers, this.decoders, true);
        try (first; second) {
            RadioAudioStream firstAudio = first.stream().toCompletableFuture().get(5, TimeUnit.SECONDS);
            RadioAudioStream secondAudio = second.stream().toCompletableFuture().get(5, TimeUnit.SECONDS);
            assertNotSame(firstAudio, secondAudio);
            try (firstAudio; secondAudio) {
                assertTrue(firstAudio.read(512).hasRemaining());
                assertTrue(secondAudio.read(512).hasRemaining());
            }
        }
        assertEquals(2, this.requests.get());
    }

    @Test
    void transferredDecoderOutlivesPreparationLease() throws Exception {
        PlaybackSession.Attempt attempt = new PlaybackSession().start(this.uri.toString());
        RadioStreamPipeline.Preparation preparation = RadioStreamPipeline.prepare(
                this.resolve(attempt), attempt.cancellation(), this.producers, this.decoders, true);
        RadioAudioStream audio = preparation.stream().toCompletableFuture().get(5, TimeUnit.SECONDS);

        assertTrue(preparation.transfer(audio));
        preparation.close();

        try (audio) {
            assertTrue(audio.read(512).hasRemaining());
        }
    }

    @Test
    void rejectsSharedProducerAndDecoderExecutor() throws Exception {
        PlaybackSession.Attempt attempt = new PlaybackSession().start(this.uri.toString());
        RadioResolvedSource source = this.resolve(attempt);
        try (source) {
            org.junit.jupiter.api.Assertions.assertThrows(IllegalArgumentException.class,
                    () -> RadioStreamPipeline.prepare(source, attempt.cancellation(),
                            this.producers, this.producers, true));
        }
    }

    @Test
    void decoderExecutorRejectionClosesTheBuffer() throws Exception {
        ExecutorService rejecting = Executors.newSingleThreadExecutor();
        rejecting.shutdownNow();
        PlaybackSession.Attempt attempt = new PlaybackSession().start(this.uri.toString());
        RadioStreamPipeline.Preparation preparation = RadioStreamPipeline.prepare(
                this.resolve(attempt), attempt.cancellation(), this.producers, rejecting, true);
        try (preparation) {
            org.junit.jupiter.api.Assertions.assertThrows(ExecutionException.class,
                    () -> preparation.stream().toCompletableFuture().get(5, TimeUnit.SECONDS));
            assertEquals(RadioBufferedInputStream.State.CANCELLED, preparation.bufferState());
        }
    }

    @Test
    void closeRemovesQueuedDecoderTask() throws Exception {
        ThreadPoolExecutor decoder = new ThreadPoolExecutor(1, 1, 0L, TimeUnit.MILLISECONDS,
                new ArrayBlockingQueue<>(1));
        CountDownLatch occupied = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        decoder.execute(() -> {
            occupied.countDown();
            try {
                release.await();
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
            }
        });
        assertTrue(occupied.await(2, TimeUnit.SECONDS));
        PlaybackSession.Attempt attempt = new PlaybackSession().start(this.uri.toString());
        RadioStreamPipeline.Preparation preparation = RadioStreamPipeline.prepare(
                this.resolve(attempt), attempt.cancellation(), this.producers, decoder, true);
        try {
            await(() -> decoder.getQueue().size() == 1);

            preparation.close();

            assertTrue(decoder.getQueue().isEmpty());
        } finally {
            release.countDown();
            decoder.shutdownNow();
        }
    }

    private RadioResolvedSource resolve(PlaybackSession.Attempt attempt) throws Exception {
        AudioNetworkPolicy allowTestServer = ignored -> {
        };
        RadioHttpTransportImpl transport = new RadioHttpTransportImpl(Proxy.NO_PROXY,
                allowTestServer, Duration.ofSeconds(2), Duration.ofSeconds(2), 2);
        return new DirectRadioSourceResolver().resolve(this.uri,
                new AudioResolveContext(transport, allowTestServer, attempt.cancellation(),
                        RadioResolveLimits.DEFAULT));
    }

    private void serveIcyMp3(HttpExchange exchange) throws IOException {
        this.requests.incrementAndGet();
        exchange.getResponseHeaders().set("Content-Type", "audio/mpeg");
        exchange.getResponseHeaders().set("icy-metaint", "128");
        byte[] encoded = withIcyMetadata(this.mp3, 128);
        exchange.sendResponseHeaders(200, 0);
        try (exchange; var output = exchange.getResponseBody()) {
            for (int offset = 0; offset < encoded.length; offset += 73) {
                int length = Math.min(73, encoded.length - offset);
                output.write(encoded, offset, length);
                output.flush();
            }
        }
    }

    private static byte[] withIcyMetadata(byte[] audio, int interval) throws IOException {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        int offset = 0;
        while (audio.length - offset >= interval) {
            output.write(audio, offset, interval);
            output.write(1);
            output.write("StreamTitle='T';".getBytes(java.nio.charset.StandardCharsets.ISO_8859_1));
            offset += interval;
        }
        output.write(audio, offset, audio.length - offset);
        return output.toByteArray();
    }

    private static int drain(RadioAudioStream stream) throws IOException {
        int bytes = 0;
        while (true) {
            ByteBuffer output = stream.read(1024);
            if (!output.hasRemaining()) {
                return bytes;
            }
            bytes += output.remaining();
        }
    }

    private static void await(java.util.function.BooleanSupplier condition) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (!condition.getAsBoolean()) {
            if (System.nanoTime() >= deadline) {
                throw new AssertionError("Timed out waiting for asynchronous radio work");
            }
            Thread.sleep(10L);
        }
    }
}
