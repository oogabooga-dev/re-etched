package gg.moonflower.etched.client.radio;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import gg.moonflower.etched.client.radio.net.RadioHttpTransportImpl;
import gg.moonflower.etched.client.radio.net.RadioNetworkPolicy;
import gg.moonflower.etched.client.radio.sound.RadioSoundInstance;
import gg.moonflower.etched.client.radio.source.DirectRadioSourceResolver;
import gg.moonflower.etched.client.radio.source.RadioResolveContext;
import gg.moonflower.etched.client.radio.source.RadioResolveLimits;
import gg.moonflower.etched.client.radio.source.RadioSourceException;
import gg.moonflower.etched.client.radio.source.RadioSourceProgram;
import gg.moonflower.etched.client.radio.source.RadioSourceProgramResolver;
import gg.moonflower.etched.client.radio.stream.RadioAudioStream;
import gg.moonflower.etched.common.audio.AudioProgram;
import gg.moonflower.etched.common.audio.AudioTrack;
import gg.moonflower.etched.common.audio.PlaybackState;
import net.minecraft.client.resources.sounds.SoundInstance;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.Level;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.Proxy;
import java.net.URI;
import java.nio.ByteBuffer;
import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BooleanSupplier;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;

class ProductionRadioSessionDriverTest {

    static {
        MinecraftTestBootstrap.bootStrap();
    }

    private static final ResourceKey<Level> DIMENSION = ResourceKey.create(Registries.DIMENSION,
            ResourceLocation.fromNamespaceAndPath("etched_test", "driver"));
    private static final PlaybackOwnerKey.BlockOwner KEY = PlaybackOwnerKey.block(DIMENSION, BlockPos.ZERO);

    private final List<String> requests = new CopyOnWriteArrayList<>();
    private ExecutorService resolvers;
    private ExecutorService producers;
    private ExecutorService decoders;
    private HttpServer server;
    private byte[] mp3;
    private byte[] longMp3;
    private URI baseUri;
    private AtomicInteger retryRequests;

    @BeforeEach
    void setUp() throws Exception {
        try (var fixture = ProductionRadioSessionDriverTest.class.getResourceAsStream(
                "/gg/moonflower/etched/client/radio/audio/mono.mp3")) {
            if (fixture == null) {
                throw new IllegalStateException("Missing MP3 fixture");
            }
            this.mp3 = fixture.readAllBytes();
        }
        try (var fixture = ProductionRadioSessionDriverTest.class.getResourceAsStream(
                "/gg/moonflower/etched/client/radio/audio/stereo-long.mp3")) {
            if (fixture == null) {
                throw new IllegalStateException("Missing long MP3 fixture");
            }
            this.longMp3 = fixture.readAllBytes();
        }
        this.server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        this.server.createContext("/one", exchange -> this.serve(exchange, "one"));
        this.server.createContext("/two", exchange -> this.serve(exchange, "two"));
        this.retryRequests = new AtomicInteger();
        this.server.createContext("/retry", exchange -> {
            this.requests.add("retry");
            if (this.retryRequests.incrementAndGet() == 1) {
                exchange.sendResponseHeaders(503, -1);
                exchange.close();
            } else {
                exchange.getResponseHeaders().set("Content-Type", "audio/mpeg");
                exchange.sendResponseHeaders(200, this.mp3.length);
                try (exchange; var output = exchange.getResponseBody()) {
                    output.write(this.mp3);
                }
            }
        });
        this.server.start();
        this.baseUri = URI.create("http://127.0.0.1:" + this.server.getAddress().getPort());
        this.resolvers = Executors.newSingleThreadExecutor();
        this.producers = Executors.newSingleThreadExecutor();
        this.decoders = Executors.newSingleThreadExecutor();
    }

    @AfterEach
    void tearDown() {
        this.server.stop(0);
        this.resolvers.shutdownNow();
        this.producers.shutdownNow();
        this.decoders.shutdownNow();
    }

    @Test
    void supportsOnlyLiveProgramsOwnedByBlocks() {
        RadioSourceProgram program = this.program(RadioSourceProgram.Kind.STATION,
                List.of(this.track("one")));
        ProductionRadioSessionDriver driver = this.driver(fixed(program), new FakeSoundOutput(false));
        PlaybackState live = state("https://radio.example/live");
        PlaybackState finite = new PlaybackState(0L, Optional.of(new AudioProgram(
                AudioProgram.Kind.FINITE, List.of(new AudioTrack(AudioTrack.SourceType.SOUND_EVENT,
                "minecraft:music_disc.13", "", "")))), true);
        PlaybackOwnerKey entity = PlaybackOwnerKey.entity(DIMENSION, new UUID(0L, 1L));

        assertTrue(driver.supports(KEY, live));
        assertFalse(driver.supports(entity, live));
        assertFalse(driver.supports(KEY, finite));
        driver.shutdown();
    }

    @Test
    void playsFiniteAlbumInOrderAndCompletesWithoutRepeating() throws Exception {
        RadioSourceProgram program = this.program(RadioSourceProgram.Kind.SERVICE_TRACKS,
                List.of(this.track("one"), this.track("two")));
        FakeSoundOutput sounds = new FakeSoundOutput(false);
        ProductionRadioSessionDriver driver = this.driver(fixed(program), sounds);
        RadioSession session = new RadioSession();
        RadioSession.Attempt attempt = session.start(this.baseUri.resolve("/album").toString());
        RecordingEvents events = new RecordingEvents(session, attempt);

        driver.start(KEY, state(attempt.source()), session, attempt, events);
        await(() -> sounds.audio.size() == 1);
        assertEquals(4.0F, sounds.played.get(0).getVolume());
        assertEquals(SoundInstance.Attenuation.LINEAR, sounds.played.get(0).getAttenuation());
        drain(sounds.audio.get(0));
        assertEquals(1, sounds.audio.size());
        sounds.played.get(0).onStop();
        await(() -> sounds.audio.size() == 2);

        // SoundEngine commonly reports the retired sound after the next track is already opening.
        sounds.played.get(0).onStop();
        assertTrue(events.soundStops.isEmpty());
        assertEquals(RadioPlaybackState.PLAYING, session.snapshot().state());

        drain(sounds.audio.get(1));
        assertEquals(RadioPlaybackState.PLAYING, session.snapshot().state());
        sounds.played.get(1).onStop();
        await(() -> session.snapshot().state() == RadioPlaybackState.STOPPED);

        assertEquals(List.of("one", "two"), this.requests);
        assertEquals(List.of(RadioPlaybackState.CONNECTING, RadioPlaybackState.BUFFERING,
                        RadioPlaybackState.PLAYING, RadioPlaybackState.CONNECTING,
                        RadioPlaybackState.BUFFERING, RadioPlaybackState.PLAYING),
                events.progress);
        assertEquals(1, events.completions.get());
        assertTrue(events.terminations.isEmpty());
        assertTrue(events.failures.isEmpty());
        assertTrue(attempt.cancellation().isCancelled());
        driver.shutdown();
    }

    @Test
    void stationEofUsesTheExistingTerminationPath() throws Exception {
        RadioSourceProgram program = this.program(RadioSourceProgram.Kind.STATION,
                List.of(this.track("one")));
        FakeSoundOutput sounds = new FakeSoundOutput(false);
        ProductionRadioSessionDriver driver = this.driver(fixed(program), sounds);
        RadioSession session = new RadioSession();
        RadioSession.Attempt attempt = session.start(this.baseUri.resolve("/station").toString());
        RecordingEvents events = new RecordingEvents(session, attempt);

        driver.start(KEY, state(attempt.source()), session, attempt, events);
        await(() -> sounds.audio.size() == 1);
        drain(sounds.audio.get(0));
        await(() -> events.terminations.size() == 1);

        assertEquals(RadioAudioStream.TerminalState.EOF, events.terminations.get(0).state());
        assertEquals(0, events.completions.get());
        assertEquals(RadioPlaybackState.PLAYING, session.snapshot().state());
        session.stop();
        driver.stop(KEY, session);
        driver.shutdown();
    }

    @Test
    void stationDecoderTerminationCanSupersedeAnEarlierSoundStop() throws Exception {
        RadioSourceProgram program = this.program(RadioSourceProgram.Kind.STATION,
                List.of(this.track("one")));
        FakeSoundOutput sounds = new FakeSoundOutput(false);
        ProductionRadioSessionDriver driver = this.driver(fixed(program), sounds);
        RadioSession session = new RadioSession();
        RadioSession.Attempt attempt = session.start(this.baseUri.resolve("/station-race").toString());
        RecordingEvents events = new RecordingEvents(session, attempt);

        driver.start(KEY, state(attempt.source()), session, attempt, events);
        await(() -> sounds.audio.size() == 1);
        sounds.played.get(0).onStop();
        assertEquals(1, events.soundStops.size());
        drain(sounds.audio.get(0));
        await(() -> events.terminations.size() == 1);

        assertEquals(RadioAudioStream.TerminalState.EOF, events.terminations.get(0).state());
        session.stop();
        driver.stop(KEY, session);
        driver.shutdown();
    }

    @Test
    void serviceTrackAdvancesWhenSoundStopArrivesBeforeDecoderEof() throws Exception {
        RadioSourceProgram program = this.program(RadioSourceProgram.Kind.SERVICE_TRACKS,
                List.of(this.track("one"), this.track("two")));
        FakeSoundOutput sounds = new FakeSoundOutput(false);
        ProductionRadioSessionDriver driver = this.driver(fixed(program), sounds);
        RadioSession session = new RadioSession();
        RadioSession.Attempt attempt = session.start(this.baseUri.resolve("/album-race").toString());
        RecordingEvents events = new RecordingEvents(session, attempt);

        driver.start(KEY, state(attempt.source()), session, attempt, events);
        await(() -> sounds.audio.size() == 1);
        sounds.played.get(0).onStop();
        drain(sounds.audio.get(0));
        await(() -> sounds.audio.size() == 2);

        assertEquals(List.of("one", "two"), this.requests);
        assertEquals(1, events.soundStops.size());
        session.stop();
        driver.stop(KEY, session);
        driver.shutdown();
    }

    @Test
    void cancellationSuppressesAResolverThatFinishesLate() throws Exception {
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        RadioSourceProgram program = this.program(RadioSourceProgram.Kind.STATION,
                List.of(this.track("one")));
        RadioSourceProgramResolver blocking = new RadioSourceProgramResolver() {
            @Override
            public boolean supports(URI input) {
                return true;
            }

            @Override
            public RadioSourceProgram resolveProgram(URI input, RadioResolveContext context)
                    throws RadioSourceException {
                entered.countDown();
                try {
                    release.await();
                } catch (InterruptedException exception) {
                    Thread.currentThread().interrupt();
                }
                context.cancellation().throwIfCancelled();
                return program;
            }
        };
        FakeSoundOutput sounds = new FakeSoundOutput(false);
        ProductionRadioSessionDriver driver = this.driver(blocking, sounds);
        RadioSession session = new RadioSession();
        RadioSession.Attempt attempt = session.start(this.baseUri.resolve("/blocked").toString());
        RecordingEvents events = new RecordingEvents(session, attempt);
        driver.start(KEY, state(attempt.source()), session, attempt, events);
        assertTrue(entered.await(5, TimeUnit.SECONDS));

        session.stop();
        driver.stop(KEY, session);
        release.countDown();
        Thread.sleep(50L);

        assertTrue(sounds.played.isEmpty());
        assertTrue(events.failures.isEmpty());
        assertTrue(events.progress.isEmpty());
        driver.shutdown();
    }

    @Test
    void playFailureClosesPreparedResourcesAndShutdownClosesEveryExecutor() throws Exception {
        RadioSourceProgram program = this.program(RadioSourceProgram.Kind.STATION,
                List.of(this.track("one")));
        FakeSoundOutput sounds = new FakeSoundOutput(true);
        ProductionRadioSessionDriver driver = this.driver(fixed(program), sounds);
        RadioSession session = new RadioSession();
        RadioSession.Attempt attempt = session.start(this.baseUri.resolve("/failure").toString());
        RecordingEvents events = new RecordingEvents(session, attempt);

        driver.start(KEY, state(attempt.source()), session, attempt, events);
        await(() -> events.failures.size() == 1);
        session.stop();
        driver.abort(KEY, session, attempt);
        driver.abort(KEY, session, attempt);
        driver.shutdown();
        driver.shutdown();

        assertEquals(1, sounds.stops.get());
        assertTrue(this.resolvers.isShutdown());
        assertTrue(this.producers.isShutdown());
        assertTrue(this.decoders.isShutdown());
    }

    @Test
    void silentSoundManagerRejectionBecomesATerminalFailure() throws Exception {
        RadioSourceProgram program = this.program(RadioSourceProgram.Kind.STATION,
                List.of(this.track("one")));
        FakeSoundOutput sounds = new FakeSoundOutput(false, false);
        ProductionRadioSessionDriver driver = this.driver(fixed(program), sounds);
        RadioSession session = new RadioSession();
        RadioSession.Attempt attempt = session.start(this.baseUri.resolve("/silent").toString());
        RecordingEvents events = new RecordingEvents(session, attempt);

        driver.start(KEY, state(attempt.source()), session, attempt, events);
        await(() -> events.failures.size() == 1);

        assertTrue(sounds.audio.isEmpty());
        session.stop();
        driver.abort(KEY, session, attempt);
        assertEquals(1, sounds.stops.get());
        driver.shutdown();
    }

    @Test
    void unexpectedClosedStreamReportsSoundEngineStop() throws Exception {
        RadioSourceProgram program = this.program(RadioSourceProgram.Kind.STATION,
                List.of(this.track("one")));
        FakeSoundOutput sounds = new FakeSoundOutput(false);
        ProductionRadioSessionDriver driver = this.driver(fixed(program), sounds);
        RadioSession session = new RadioSession();
        RadioSession.Attempt attempt = session.start(this.baseUri.resolve("/closed").toString());
        RecordingEvents events = new RecordingEvents(session, attempt);

        driver.start(KEY, state(attempt.source()), session, attempt, events);
        await(() -> sounds.audio.size() == 1);
        sounds.audio.get(0).close();
        await(() -> events.soundStops.size() == 1);

        assertTrue(events.terminations.isEmpty());
        session.stop();
        driver.abort(KEY, session, attempt);
        driver.shutdown();
    }

    @Test
    void normalStopLeavesTransferredAudioOwnedBySoundEngine() throws Exception {
        RadioSourceProgram program = this.program(RadioSourceProgram.Kind.STATION,
                List.of(this.track("one")));
        FakeSoundOutput sounds = new FakeSoundOutput(false);
        ProductionRadioSessionDriver driver = this.driver(fixed(program), sounds);
        RadioSession session = new RadioSession();
        RadioSession.Attempt attempt = session.start(this.baseUri.resolve("/stop-ownership").toString());
        RecordingEvents events = new RecordingEvents(session, attempt);
        driver.start(KEY, state(attempt.source()), session, attempt, events);
        await(() -> sounds.audio.size() == 1);
        RadioAudioStream audio = sounds.audio.get(0);

        session.stop();
        driver.stop(KEY, session);

        assertEquals(1, sounds.stops.get());
        assertFalse(audio.termination().toCompletableFuture().isDone());
        audio.close();
        driver.shutdown();
    }

    @Test
    void normalStopCancelsUpstreamForTransferredAudio() throws Exception {
        CountDownLatch bodySent = new CountDownLatch(1);
        CountDownLatch releaseBody = new CountDownLatch(1);
        this.server.createContext("/stalled", exchange -> {
            exchange.getResponseHeaders().set("Content-Type", "audio/mpeg");
            exchange.sendResponseHeaders(200, 0);
            try (exchange; var output = exchange.getResponseBody()) {
                output.write(this.longMp3);
                output.flush();
                bodySent.countDown();
                try {
                    releaseBody.await();
                } catch (InterruptedException exception) {
                    Thread.currentThread().interrupt();
                }
            }
        });
        RadioSourceProgram program = this.program(RadioSourceProgram.Kind.STATION,
                List.of(this.track("stalled")));
        FakeSoundOutput sounds = new FakeSoundOutput(false);
        ProductionRadioSessionDriver driver = this.driver(fixed(program), sounds);
        RadioSession session = new RadioSession();
        RadioSession.Attempt attempt = session.start(this.baseUri.resolve("/stalled").toString());
        RecordingEvents events = new RecordingEvents(session, attempt);
        ExecutorService soundExecutor = Executors.newSingleThreadExecutor();

        try {
            driver.start(KEY, state(attempt.source()), session, attempt, events);
            await(() -> sounds.audio.size() == 1);
            assertTrue(bodySent.await(5, TimeUnit.SECONDS));
            RadioAudioStream audio = sounds.audio.get(0);
            Future<Throwable> read = soundExecutor.submit(() -> {
                try {
                    while (true) {
                        audio.read(64 * 1024);
                    }
                } catch (Throwable failure) {
                    return failure;
                }
            });
            Thread.sleep(100L);
            assertFalse(read.isDone());

            session.stop();
            driver.stop(KEY, session);

            assertInstanceOf(IOException.class, read.get(2, TimeUnit.SECONDS));
            assertEquals(RadioAudioStream.TerminalState.CANCELLED,
                    audio.termination().toCompletableFuture().get(2, TimeUnit.SECONDS).state());
            assertEquals(1, sounds.stops.get());
        } finally {
            releaseBody.countDown();
            soundExecutor.shutdownNow();
            session.stop();
            driver.stop(KEY, session);
            driver.shutdown();
        }
    }

    @Test
    void soundStopFailureClosesTransferredAudioDirectly() throws Exception {
        RadioSourceProgram program = this.program(RadioSourceProgram.Kind.STATION,
                List.of(this.track("one")));
        FakeSoundOutput sounds = new FakeSoundOutput(false);
        ProductionRadioSessionDriver driver = this.driver(fixed(program), sounds);
        RadioSession session = new RadioSession();
        RadioSession.Attempt attempt = session.start(this.baseUri.resolve("/stop-failure").toString());
        RecordingEvents events = new RecordingEvents(session, attempt);
        driver.start(KEY, state(attempt.source()), session, attempt, events);
        await(() -> sounds.audio.size() == 1);
        RadioAudioStream audio = sounds.audio.get(0);
        sounds.failStop = true;

        session.stop();
        driver.stop(KEY, session);

        assertEquals(RadioAudioStream.TerminalState.CLOSED,
                audio.termination().toCompletableFuture().get(2, TimeUnit.SECONDS).state());
        driver.shutdown();
    }

    @Test
    void malformedSourceIsReportedAsInvalidUrl() throws Exception {
        AtomicInteger resolverCalls = new AtomicInteger();
        RadioSourceProgramResolver resolver = new RadioSourceProgramResolver() {
            @Override
            public boolean supports(URI input) {
                return true;
            }

            @Override
            public RadioSourceProgram resolveProgram(URI input, RadioResolveContext context) {
                resolverCalls.incrementAndGet();
                throw new AssertionError("Malformed URI reached resolver");
            }
        };
        ProductionRadioSessionDriver driver = this.driver(resolver, new FakeSoundOutput(false));
        RadioSession session = new RadioSession();
        RadioSession.Attempt attempt = session.start("https://bad host/");
        RecordingEvents events = new RecordingEvents(session, attempt);

        driver.start(KEY, state("https://radio.example/valid"), session, attempt, events);
        await(() -> events.failures.size() == 1);

        RadioSourceException failure = assertInstanceOf(
                RadioSourceException.class, events.failures.get(0));
        assertEquals(RadioFailure.Code.INVALID_URL, failure.code());
        assertEquals(0, resolverCalls.get());
        session.stop();
        driver.abort(KEY, session, attempt);
        driver.shutdown();
    }

    @Test
    void automaticRetryResumesTheFailedServiceTrack() throws Exception {
        RadioSourceProgram program = this.program(RadioSourceProgram.Kind.SERVICE_TRACKS,
                List.of(this.track("one"), this.track("retry")));
        FakeSoundOutput sounds = new FakeSoundOutput(false);
        ProductionRadioSessionDriver driver = this.driver(fixed(program), sounds);
        RadioSession session = new RadioSession();
        RadioSession.Attempt first = session.start(this.baseUri.resolve("/album-retry").toString());
        RecordingEvents firstEvents = new RecordingEvents(session, first);
        driver.start(KEY, state(first.source()), session, first, firstEvents);
        await(() -> sounds.audio.size() == 1);
        drain(sounds.audio.get(0));
        sounds.played.get(0).onStop();
        await(() -> firstEvents.failures.size() == 1);

        RadioSourceException sourceFailure = assertInstanceOf(
                RadioSourceException.class, firstEvents.failures.get(0));
        RadioReconnectPolicy policy = new RadioReconnectPolicy(
                new long[]{0L}, 30_000L, 0.0D, () -> 0.5D);
        RadioSession.ReconnectWait wait = session.scheduleReconnect(
                first, sourceFailure.toFailure(), 0L, policy).orElseThrow();
        RadioSession.Attempt retry = session.retry(wait).orElseThrow();
        RecordingEvents retryEvents = new RecordingEvents(session, retry);

        driver.start(KEY, state(retry.source()), session, retry, retryEvents);
        await(() -> sounds.audio.size() == 2);

        assertEquals(List.of("one", "retry", "retry"), this.requests);
        session.stop();
        driver.stop(KEY, session);
        driver.shutdown();
    }

    @Test
    void ownerExecutorRejectionUsesTheDirectTerminalFallback() throws Exception {
        RadioSourceProgram program = this.program(RadioSourceProgram.Kind.STATION,
                List.of(this.track("one")));
        FakeSoundOutput sounds = new FakeSoundOutput(false);
        RadioNetworkPolicy allowTestServer = ignored -> {
        };
        ProductionRadioSessionDriver driver = new ProductionRadioSessionDriver(
                fixed(program), cancellation -> new RadioResolveContext(
                new RadioHttpTransportImpl(Proxy.NO_PROXY, allowTestServer,
                        Duration.ofSeconds(2), Duration.ofSeconds(2), 2), allowTestServer,
                cancellation, RadioResolveLimits.DEFAULT), this.resolvers, this.producers,
                this.decoders, command -> {
                    throw new java.util.concurrent.RejectedExecutionException("owner stopped");
                }, sounds, () -> true);
        RadioSession session = new RadioSession();
        RadioSession.Attempt attempt = session.start(this.baseUri.resolve("/owner").toString());
        RecordingEvents events = new RecordingEvents(session, attempt);

        driver.start(KEY, state(attempt.source()), session, attempt, events);
        await(() -> events.unavailableOwners.get() == 1);

        assertTrue(sounds.played.isEmpty());
        driver.shutdown();
    }

    @Test
    void ownerRejectionAfterHandoffDoesNotStopSoundOffThread() throws Exception {
        RadioSourceProgram program = this.program(RadioSourceProgram.Kind.STATION,
                List.of(this.track("one")));
        FakeSoundOutput sounds = new FakeSoundOutput(false);
        AtomicInteger ownerDispatches = new AtomicInteger();
        RadioNetworkPolicy allowTestServer = ignored -> {
        };
        ProductionRadioSessionDriver driver = new ProductionRadioSessionDriver(
                fixed(program), cancellation -> new RadioResolveContext(
                new RadioHttpTransportImpl(Proxy.NO_PROXY, allowTestServer,
                        Duration.ofSeconds(2), Duration.ofSeconds(2), 2), allowTestServer,
                cancellation, RadioResolveLimits.DEFAULT), this.resolvers, this.producers,
                this.decoders, command -> {
                    if (ownerDispatches.incrementAndGet() > 2) {
                        throw new java.util.concurrent.RejectedExecutionException("owner stopped");
                    }
                    command.run();
                }, sounds, () -> true);
        RadioSession session = new RadioSession();
        RadioSession.Attempt attempt = session.start(this.baseUri.resolve("/owner-handoff").toString());
        RecordingEvents events = new RecordingEvents(session, attempt);

        driver.start(KEY, state(attempt.source()), session, attempt, events);
        await(() -> sounds.audio.size() == 1);
        sounds.played.get(0).onStop();
        await(() -> events.unavailableOwners.get() == 1);

        assertEquals(0, sounds.stops.get());
        assertEquals(RadioAudioStream.TerminalState.CLOSED,
                sounds.audio.get(0).termination().toCompletableFuture().get(2, TimeUnit.SECONDS).state());
        driver.shutdown();
    }

    private ProductionRadioSessionDriver driver(RadioSourceProgramResolver resolver,
                                                FakeSoundOutput sounds) {
        RadioNetworkPolicy allowTestServer = ignored -> {
        };
        return new ProductionRadioSessionDriver(resolver, cancellation ->
                new RadioResolveContext(new RadioHttpTransportImpl(Proxy.NO_PROXY, allowTestServer,
                        Duration.ofSeconds(2), Duration.ofSeconds(2), 2), allowTestServer,
                        cancellation, RadioResolveLimits.DEFAULT), this.resolvers, this.producers,
                this.decoders, Runnable::run, sounds, () -> true);
    }

    private RadioSourceProgram program(RadioSourceProgram.Kind kind,
                                       List<RadioSourceProgram.Track> tracks) {
        return new RadioSourceProgram(kind, this.baseUri.resolve("/program"), tracks);
    }

    private RadioSourceProgram.Track track(String path) {
        URI uri = this.baseUri.resolve("/" + path);
        return new RadioSourceProgram.Track(uri, path,
                context -> new DirectRadioSourceResolver().resolve(uri, context));
    }

    private static RadioSourceProgramResolver fixed(RadioSourceProgram program) {
        return new RadioSourceProgramResolver() {
            @Override
            public boolean supports(URI input) {
                return true;
            }

            @Override
            public RadioSourceProgram resolveProgram(URI input, RadioResolveContext context) {
                return program;
            }
        };
    }

    private static PlaybackState state(String source) {
        AudioTrack track = new AudioTrack(AudioTrack.SourceType.REMOTE, source, "", "");
        return new PlaybackState(0L,
                Optional.of(new AudioProgram(AudioProgram.Kind.LIVE, List.of(track))), true);
    }

    private void serve(HttpExchange exchange, String name) throws IOException {
        this.requests.add(name);
        exchange.getResponseHeaders().set("Content-Type", "audio/mpeg");
        exchange.sendResponseHeaders(200, this.mp3.length);
        try (exchange; var output = exchange.getResponseBody()) {
            output.write(this.mp3);
        }
    }

    private static void drain(RadioAudioStream stream) throws IOException {
        while (true) {
            ByteBuffer bytes = stream.read(4096);
            if (!bytes.hasRemaining()) {
                return;
            }
        }
    }

    private static void await(BooleanSupplier condition) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (!condition.getAsBoolean()) {
            if (System.nanoTime() >= deadline) {
                throw new AssertionError("Timed out waiting for asynchronous radio work");
            }
            Thread.sleep(10L);
        }
    }

    private static final class FakeSoundOutput implements ProductionRadioSessionDriver.SoundOutput {
        private final List<RadioSoundInstance> played = new CopyOnWriteArrayList<>();
        private final List<RadioAudioStream> audio = new CopyOnWriteArrayList<>();
        private final AtomicInteger stops = new AtomicInteger();
        private final boolean failPlay;
        private final boolean acceptPlay;
        private volatile boolean failStop;

        private FakeSoundOutput(boolean failPlay) {
            this(failPlay, true);
        }

        private FakeSoundOutput(boolean failPlay, boolean acceptPlay) {
            this.failPlay = failPlay;
            this.acceptPlay = acceptPlay;
        }

        @Override
        public boolean play(RadioSoundInstance sound) {
            this.played.add(sound);
            if (this.failPlay) {
                throw new IllegalStateException("SoundManager rejected playback");
            }
            if (!this.acceptPlay) {
                return false;
            }
            sound.resolve(null);
            try {
                this.audio.add((RadioAudioStream) sound.getStream(null, sound.getSound(), false).get());
            } catch (Exception exception) {
                throw new IllegalStateException(exception);
            }
            return true;
        }

        @Override
        public void stop(RadioSoundInstance sound) {
            this.stops.incrementAndGet();
            if (this.failStop) {
                throw new IllegalStateException("SoundManager failed to stop playback");
            }
            sound.onStop();
        }
    }

    private static final class RecordingEvents implements AudioPlaybackManager.SessionEvents {
        private final RadioSession session;
        private final RadioSession.Attempt attempt;
        private final List<RadioPlaybackState> progress = new CopyOnWriteArrayList<>();
        private final List<Throwable> failures = new CopyOnWriteArrayList<>();
        private final List<RadioAudioStream.Termination> terminations = new CopyOnWriteArrayList<>();
        private final List<Boolean> soundStops = new CopyOnWriteArrayList<>();
        private final AtomicInteger completions = new AtomicInteger();
        private final AtomicInteger unavailableOwners = new AtomicInteger();
        private final AtomicInteger clock = new AtomicInteger();

        private RecordingEvents(RadioSession session, RadioSession.Attempt attempt) {
            this.session = session;
            this.attempt = attempt;
        }

        @Override
        public void progress(RadioPlaybackState state) {
            if (this.session.advance(this.attempt, state, this.clock.incrementAndGet())) {
                this.progress.add(state);
            }
        }

        @Override
        public void sequenceAdvance(Runnable continuation) {
            if (this.session.advanceToNextTrack(this.attempt)) {
                this.progress.add(RadioPlaybackState.CONNECTING);
                continuation.run();
            }
        }

        @Override
        public void completion() {
            if (this.session.complete(this.attempt)) {
                this.completions.incrementAndGet();
            }
        }

        @Override
        public void failure(Throwable failure) {
            this.failures.add(failure);
        }

        @Override
        public void termination(RadioAudioStream.Termination termination) {
            this.terminations.add(termination);
        }

        @Override
        public void soundEngineStopped() {
            this.soundStops.add(Boolean.TRUE);
        }

        @Override
        public void ownerUnavailable(Throwable failure) {
            this.unavailableOwners.incrementAndGet();
        }
    }
}
