package gg.moonflower.etched.client.radio;

import gg.moonflower.etched.client.radio.net.RadioTransportException;
import gg.moonflower.etched.client.radio.stream.PlaybackAudioStream;
import gg.moonflower.etched.common.audio.AudioProgram;
import gg.moonflower.etched.common.audio.AudioTrack;
import gg.moonflower.etched.common.audio.PlaybackState;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.Level;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.RejectedExecutionException;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AudioPlaybackManagerTest {

    private static final RadioReconnectPolicy NO_JITTER = new RadioReconnectPolicy(
            new long[]{1_000L, 2_000L, 5_000L, 10_000L, 20_000L, 30_000L},
            30_000L, 0.0D, () -> 0.5D);

    static {
        MinecraftTestBootstrap.bootStrap();
    }

    private static final ResourceKey<Level> FIRST_DIMENSION = dimension("first");
    private static final ResourceKey<Level> SECOND_DIMENSION = dimension("second");
    private static final String LIVE_SOURCE = "https://radio.example/live";
    private static final PlaybackState ENABLED = liveState(1L, LIVE_SOURCE, true);

    @Test
    void deduplicatesStateAndAppliesMeaningfulChanges() {
        RecordingDriver driver = new RecordingDriver();
        AudioPlaybackManager manager = new AudioPlaybackManager(driver);
        PlaybackOwnerKey key = PlaybackOwnerKey.block(FIRST_DIMENSION, new BlockPos(1, 2, 3));

        assertTrue(manager.update(key, ENABLED));
        assertFalse(manager.update(key, ENABLED));
        PlaybackState stopped = liveState(2L, LIVE_SOURCE, false);
        assertTrue(manager.update(key, stopped));

        assertEquals(2, driver.applied.size());
        assertEquals(stopped, manager.getPlaybackState(key).orElseThrow());
        assertEquals(RadioPlaybackState.STOPPED,
                manager.getSessionSnapshot(key).orElseThrow().state());
    }

    @Test
    void rejectsStaleAndConflictingRevisions() {
        RecordingDriver driver = new RecordingDriver();
        AudioPlaybackManager manager = new AudioPlaybackManager(driver);
        PlaybackOwnerKey key = PlaybackOwnerKey.block(FIRST_DIMENSION, BlockPos.ZERO);
        PlaybackState current = liveState(10L, "https://radio.example/current", true);

        assertTrue(manager.update(key, current));
        assertFalse(manager.update(key, liveState(9L, "https://radio.example/stale", true)));
        assertFalse(manager.update(key, liveState(10L, "https://radio.example/conflict", true)));

        assertEquals(current, manager.getPlaybackState(key).orElseThrow());
        assertEquals(1, driver.applied.size());
    }

    @Test
    void acceptsRevisionAfterLongWrap() {
        AudioPlaybackManager manager = new AudioPlaybackManager(new RecordingDriver());
        PlaybackOwnerKey key = PlaybackOwnerKey.block(FIRST_DIMENSION, BlockPos.ZERO);

        assertTrue(manager.update(key, liveState(
                Long.MAX_VALUE, "https://radio.example/before-wrap", true)));
        assertTrue(manager.update(key, liveState(
                Long.MIN_VALUE, "https://radio.example/after-wrap", true)));

        assertEquals(Long.MIN_VALUE, manager.getPlaybackState(key).orElseThrow().revision());
    }

    @Test
    void removesPlaybackIdempotently() {
        RecordingDriver driver = new RecordingDriver();
        AudioPlaybackManager manager = new AudioPlaybackManager(driver);
        PlaybackOwnerKey key = PlaybackOwnerKey.block(FIRST_DIMENSION, BlockPos.ZERO);
        manager.update(key, ENABLED);

        assertTrue(manager.remove(key));
        assertFalse(manager.remove(key));

        assertEquals(List.of(key), driver.stopped);
        assertTrue(manager.getPlaybackState(key).isEmpty());
    }

    @Test
    void keepsEqualPositionsInDifferentDimensionsIndependent() {
        RecordingDriver driver = new RecordingDriver();
        AudioPlaybackManager manager = new AudioPlaybackManager(driver);
        PlaybackOwnerKey first = PlaybackOwnerKey.block(FIRST_DIMENSION, BlockPos.ZERO);
        PlaybackOwnerKey second = PlaybackOwnerKey.block(SECOND_DIMENSION, BlockPos.ZERO);

        manager.update(first, ENABLED);
        manager.update(second, ENABLED);
        manager.remove(first);

        assertTrue(manager.getPlaybackState(first).isEmpty());
        assertEquals(ENABLED, manager.getPlaybackState(second).orElseThrow());
    }

    @Test
    void keepsBlockAndEntityOwnersIndependent() {
        RecordingDriver driver = new RecordingDriver();
        AudioPlaybackManager manager = new AudioPlaybackManager(driver);
        PlaybackOwnerKey block = PlaybackOwnerKey.block(FIRST_DIMENSION, BlockPos.ZERO);
        PlaybackOwnerKey entity = PlaybackOwnerKey.entity(FIRST_DIMENSION,
                UUID.fromString("d860f8b6-c8e7-4c4d-9d5e-cf7503845df4"));

        assertTrue(manager.update(block, ENABLED));
        assertTrue(manager.update(entity, ENABLED));
        assertTrue(manager.remove(block));

        assertTrue(manager.getPlaybackState(block).isEmpty());
        assertEquals(ENABLED, manager.getPlaybackState(entity).orElseThrow());
        assertEquals(List.of(block), driver.stopped);
        assertTrue(manager.isPlaying(entity));
    }

    @Test
    void supportedEntityOwnerReceivesSessionEffectsAndStop() {
        RecordingSessionDriver sessions = new RecordingSessionDriver();
        RecordingEffects effects = new RecordingEffects();
        AudioPlaybackManager manager = new AudioPlaybackManager(new RecordingDriver(), sessions, effects);
        PlaybackOwnerKey entity = PlaybackOwnerKey.entity(FIRST_DIMENSION,
                UUID.fromString("d860f8b6-c8e7-4c4d-9d5e-cf7503845df4"));

        assertTrue(manager.update(entity, ENABLED));
        StartedSession started = sessions.started.get(0);
        assertEquals(entity, effects.updated.get(0).key());
        assertEquals(RadioPlaybackState.RESOLVING, effects.updated.get(0).snapshot().state());

        assertTrue(started.session().advance(started.attempt(), RadioPlaybackState.CONNECTING, 0L));
        manager.tick(entity, ENABLED);
        assertEquals(RadioPlaybackState.CONNECTING,
                effects.updated.get(effects.updated.size() - 1).snapshot().state());

        assertTrue(manager.remove(entity));
        assertEquals(List.of(entity), effects.stopped);
        assertTrue(started.attempt().cancellation().isCancelled());
    }

    @Test
    void minecraftRadioEffectsIgnoreEntityOwner() {
        MinecraftRadioPlaybackEffects effects = new MinecraftRadioPlaybackEffects();
        PlaybackOwnerKey entity = PlaybackOwnerKey.entity(FIRST_DIMENSION,
                UUID.fromString("d860f8b6-c8e7-4c4d-9d5e-cf7503845df4"));
        PlaybackSession session = new PlaybackSession();
        session.start(LIVE_SOURCE);

        effects.update(entity, session.snapshot());
        effects.stop(entity);
    }

    @Test
    void tickSelfHealsMissedUpdatesWithoutRestartingKnownPlayback() {
        RecordingDriver driver = new RecordingDriver();
        AudioPlaybackManager manager = new AudioPlaybackManager(driver);
        PlaybackOwnerKey key = PlaybackOwnerKey.block(FIRST_DIMENSION, BlockPos.ZERO);

        manager.tick(key, ENABLED);
        manager.tick(key, ENABLED);

        assertEquals(1, driver.applied.size());
        assertEquals(List.of(key, key), driver.ticked);
    }

    @Test
    void fallbackTickUsesAcceptedStateAfterStaleUpdate() {
        RecordingDriver driver = new RecordingDriver();
        AudioPlaybackManager manager = new AudioPlaybackManager(driver);
        PlaybackOwnerKey key = PlaybackOwnerKey.block(FIRST_DIMENSION, BlockPos.ZERO);
        PlaybackState current = liveState(2L, "https://radio.example/current", true);

        manager.update(key, current);
        manager.tick(key, liveState(1L, "https://radio.example/stale", true));

        assertEquals(current, driver.tickedStates.get(0).state());
    }

    @Test
    void reportsActualDriverStateOnlyForTrackedPlayback() {
        RecordingDriver driver = new RecordingDriver();
        AudioPlaybackManager manager = new AudioPlaybackManager(driver);
        PlaybackOwnerKey tracked = PlaybackOwnerKey.block(FIRST_DIMENSION, BlockPos.ZERO);
        PlaybackOwnerKey missing = PlaybackOwnerKey.block(FIRST_DIMENSION, BlockPos.ZERO.above());
        manager.update(tracked, ENABLED);

        assertTrue(manager.isPlaying(tracked));
        driver.playing.add(missing);
        assertFalse(manager.isPlaying(missing));
    }

    @Test
    void clearStopsEveryPlaybackAndIsIdempotent() {
        RecordingDriver driver = new RecordingDriver();
        AudioPlaybackManager manager = new AudioPlaybackManager(driver);
        PlaybackOwnerKey first = PlaybackOwnerKey.block(FIRST_DIMENSION, BlockPos.ZERO);
        PlaybackOwnerKey second = PlaybackOwnerKey.block(FIRST_DIMENSION, BlockPos.ZERO.above());
        manager.update(first, ENABLED);
        manager.update(second, ENABLED);

        manager.clearAll();
        manager.clearAll();

        assertEquals(Set.of(first, second), new HashSet<>(driver.stopped));
        assertEquals(2, driver.stopped.size());
        assertTrue(manager.getPlaybackState(first).isEmpty());
        assertTrue(manager.getPlaybackState(second).isEmpty());
    }

    @Test
    void replacementAndDisableCancelThePreviousGeneration() {
        RecordingDriver playback = new RecordingDriver();
        RecordingSessionDriver sessions = new RecordingSessionDriver();
        AudioPlaybackManager manager = new AudioPlaybackManager(playback, sessions);
        PlaybackOwnerKey key = PlaybackOwnerKey.block(FIRST_DIMENSION, BlockPos.ZERO);

        manager.update(key, ENABLED);
        StartedSession first = sessions.started.get(0);
        assertFalse(manager.update(key, ENABLED));
        assertFalse(first.attempt().cancellation().isCancelled());
        manager.update(key, liveState(2L, "https://radio.example/new", true));
        StartedSession second = sessions.started.get(1);

        assertTrue(first.attempt().cancellation().isCancelled());
        assertFalse(first.session().advance(first.attempt().generation(), RadioPlaybackState.CONNECTING));
        assertFalse(second.attempt().cancellation().isCancelled());

        manager.update(key, liveState(3L, source(second.state()), false));

        assertTrue(second.attempt().cancellation().isCancelled());
        assertEquals(2, sessions.started.size());
        assertEquals(RadioPlaybackState.STOPPED,
                manager.getSessionSnapshot(key).orElseThrow().state());
    }

    @Test
    void repeatedSourceAndEnabledStateChurnNeverOverlapsBackendOwnership() {
        RecordingSessionDriver sessions = new RecordingSessionDriver();
        RadioReconnectController reconnects = new RadioReconnectController(
                NO_JITTER, () -> 0L, Runnable::run, new ManualRetryScheduler());
        RadioConnectionScheduler connections = new RadioConnectionScheduler(1, 4, Runnable::run);
        AudioPlaybackManager manager = new AudioPlaybackManager(
                new RecordingDriver(), sessions, new RecordingEffects(), reconnects, connections);
        PlaybackOwnerKey key = PlaybackOwnerKey.block(FIRST_DIMENSION, BlockPos.ZERO);

        manager.update(key, ENABLED);
        long revision = ENABLED.revision();
        for (int i = 0; i < 100; i++) {
            String source = "https://radio.example/live/" + i;
            StartedSession previous = sessions.started.get(sessions.started.size() - 1);
            manager.update(key, liveState(++revision, source, true));
            assertTrue(previous.attempt().cancellation().isCancelled());
            StartedSession started = sessions.started.get(sessions.started.size() - 1);
            manager.update(key, liveState(++revision, source, false));
            assertTrue(started.attempt().cancellation().isCancelled());
            manager.update(key, liveState(++revision, source, true));
        }
        manager.remove(key);

        assertEquals(201, sessions.started.size());
        assertEquals(1, sessions.maximumOpen);
        assertTrue(sessions.open.isEmpty());
        assertTrue(sessions.openSessions.isEmpty());
        assertEquals(0, connections.activeCount());
        assertEquals(0, connections.queuedCount());
    }

    @Test
    void replacingQueuedPlaybackStartsOnlyItsLatestState() {
        RecordingSessionDriver sessions = new RecordingSessionDriver();
        RadioReconnectController reconnects = new RadioReconnectController(
                NO_JITTER, () -> 0L, Runnable::run, new ManualRetryScheduler());
        RadioConnectionScheduler connections = new RadioConnectionScheduler(1, 1, Runnable::run);
        AudioPlaybackManager manager = new AudioPlaybackManager(
                new RecordingDriver(), sessions, new RecordingEffects(), reconnects, connections);
        PlaybackOwnerKey incumbent = PlaybackOwnerKey.block(FIRST_DIMENSION, BlockPos.ZERO);
        PlaybackOwnerKey queued = PlaybackOwnerKey.block(FIRST_DIMENSION, BlockPos.ZERO.above());
        manager.update(incumbent, ENABLED);

        for (int i = 0; i < 50; i++) {
            manager.update(queued, liveState(
                    i + 1L, "https://radio.example/queued/" + i, true));
            assertEquals(1, connections.queuedCount());
        }
        manager.remove(incumbent);

        assertEquals(2, sessions.started.size());
        assertEquals("https://radio.example/queued/49", sessions.started.get(1).attempt().source());
        assertEquals(1, connections.activeCount());
        assertEquals(0, connections.queuedCount());
        assertEquals(1, sessions.maximumOpen);
    }

    @Test
    void startsOnlyWhenAuthoritativeStateHasAnEnabledLiveProgram() {
        RecordingSessionDriver sessions = new RecordingSessionDriver();
        AudioPlaybackManager manager = new AudioPlaybackManager(new RecordingDriver(), sessions);
        PlaybackOwnerKey key = PlaybackOwnerKey.block(FIRST_DIMENSION, BlockPos.ZERO);

        manager.update(key, new PlaybackState(1L, Optional.empty(), false));
        manager.update(key, liveState(2L, "https://radio.example/live", true));

        assertEquals(1, sessions.started.size());
        assertEquals("https://radio.example/live", sessions.started.get(0).attempt().source());
    }

    @Test
    void tracksEnabledFiniteStateWithoutSendingItToTheLiveBackend() {
        RecordingSessionDriver sessions = new RecordingSessionDriver();
        AudioPlaybackManager manager = new AudioPlaybackManager(new RecordingDriver(), sessions);
        PlaybackOwnerKey key = PlaybackOwnerKey.block(FIRST_DIMENSION, BlockPos.ZERO);
        PlaybackState finite = finiteState(1L, true);

        assertTrue(manager.update(key, finite));

        assertEquals(finite, manager.getPlaybackState(key).orElseThrow());
        assertEquals(RadioPlaybackState.STOPPED,
                manager.getSessionSnapshot(key).orElseThrow().state());
        assertTrue(sessions.started.isEmpty());
    }

    @Test
    void supportedFiniteProgramAdvancesAndCompletesWithoutReconnect() {
        RecordingSessionDriver sessions = new RecordingSessionDriver();
        sessions.supportsFinite = true;
        ManualRetryScheduler scheduler = new ManualRetryScheduler();
        RadioReconnectController reconnects = new RadioReconnectController(
                NO_JITTER, () -> 0L, Runnable::run, scheduler);
        RadioConnectionScheduler connections = new RadioConnectionScheduler(1, 0, Runnable::run);
        AudioPlaybackManager manager = new AudioPlaybackManager(
                new RecordingDriver(), sessions, new RecordingEffects(), reconnects, connections);
        PlaybackOwnerKey key = PlaybackOwnerKey.block(FIRST_DIMENSION, BlockPos.ZERO);
        PlaybackState finite = finiteRemoteState(1L, "https://audio.example/one", "https://audio.example/two");

        assertTrue(manager.update(key, finite));
        StartedSession started = sessions.started.get(0);
        assertEquals("https://audio.example/one", started.attempt().source());
        started.events().progress(RadioPlaybackState.CONNECTING);
        started.events().progress(RadioPlaybackState.BUFFERING);
        started.events().progress(RadioPlaybackState.PLAYING);
        AtomicLong openedNext = new AtomicLong();
        started.events().sequenceAdvance(openedNext::incrementAndGet);
        assertEquals(1L, openedNext.get());
        assertEquals(RadioPlaybackState.CONNECTING, started.session().snapshot().state());
        assertEquals(started.attempt().generation(), started.session().snapshot().generation());
        started.events().progress(RadioPlaybackState.BUFFERING);
        started.events().progress(RadioPlaybackState.PLAYING);

        started.events().soundEngineStopped();
        assertEquals(1, scheduler.tasks.size());
        started.events().completion();
        scheduler.fire();
        started.events().completion();

        assertEquals(RadioPlaybackState.STOPPED, started.session().snapshot().state());
        assertEquals(List.of(key), sessions.aborted);
        assertTrue(started.attempt().cancellation().isCancelled());
        assertEquals(0, connections.activeCount());
        assertTrue(scheduler.tasks.get(0).cancelled);
        assertFalse(manager.retry(key));
        assertEquals(1, sessions.started.size());
    }

    @Test
    void finiteFailureStopsWithoutSchedulingLiveBackoff() {
        RecordingSessionDriver sessions = new RecordingSessionDriver();
        sessions.supportsFinite = true;
        ManualRetryScheduler scheduler = new ManualRetryScheduler();
        RadioReconnectController reconnects = new RadioReconnectController(
                NO_JITTER, () -> 0L, Runnable::run, scheduler);
        AudioPlaybackManager manager = new AudioPlaybackManager(
                new RecordingDriver(), sessions, new RecordingEffects(), reconnects);
        PlaybackOwnerKey key = PlaybackOwnerKey.block(FIRST_DIMENSION, BlockPos.ZERO);
        manager.update(key, finiteRemoteState(1L, "https://audio.example/track"));
        StartedSession started = sessions.started.get(0);

        started.events().failure(new RadioTransportException(
                RadioFailure.Code.READ_TIMEOUT, true, "Timed out", null));

        PlaybackSession.Snapshot failed = manager.getSessionSnapshot(key).orElseThrow();
        assertEquals(RadioPlaybackState.FAILED, failed.state());
        assertEquals(RadioFailure.Code.READ_TIMEOUT, failed.failure().code());
        assertFalse(failed.failure().recoverable());
        assertTrue(started.attempt().cancellation().isCancelled());
        assertEquals(List.of(key), sessions.aborted);
        assertTrue(scheduler.tasks.isEmpty());
        assertFalse(manager.retry(key));
    }

    @Test
    void finitePartialStartFailureAbortsAndReleasesAdmissionWithoutReconnect() {
        RecordingSessionDriver sessions = new RecordingSessionDriver();
        sessions.supportsFinite = true;
        sessions.throwOnStart = true;
        sessions.openBeforeThrow = true;
        ManualRetryScheduler scheduler = new ManualRetryScheduler();
        RadioReconnectController reconnects = new RadioReconnectController(
                NO_JITTER, () -> 0L, Runnable::run, scheduler);
        RadioConnectionScheduler connections = new RadioConnectionScheduler(1, 0, Runnable::run);
        AudioPlaybackManager manager = new AudioPlaybackManager(
                new RecordingDriver(), sessions, new RecordingEffects(), reconnects, connections);
        PlaybackOwnerKey key = PlaybackOwnerKey.block(FIRST_DIMENSION, BlockPos.ZERO);

        assertTrue(manager.update(key, finiteRemoteState(1L, "https://audio.example/track")));

        assertEquals(RadioPlaybackState.FAILED, manager.getSessionSnapshot(key).orElseThrow().state());
        assertEquals(List.of(key), sessions.aborted);
        assertTrue(sessions.open.isEmpty());
        assertEquals(0, connections.activeCount());
        assertTrue(scheduler.tasks.isEmpty());
    }

    @Test
    void staleFiniteOutcomeCannotAffectReplacementSession() {
        RecordingSessionDriver sessions = new RecordingSessionDriver();
        sessions.supportsFinite = true;
        ManualRetryScheduler scheduler = new ManualRetryScheduler();
        RadioReconnectController reconnects = new RadioReconnectController(
                NO_JITTER, () -> 0L, Runnable::run, scheduler);
        AudioPlaybackManager manager = new AudioPlaybackManager(
                new RecordingDriver(), sessions, new RecordingEffects(), reconnects);
        PlaybackOwnerKey key = PlaybackOwnerKey.block(FIRST_DIMENSION, BlockPos.ZERO);
        manager.update(key, finiteRemoteState(1L, "https://audio.example/old"));
        StartedSession old = sessions.started.get(0);

        manager.update(key, finiteRemoteState(2L, "https://audio.example/new"));
        StartedSession replacement = sessions.started.get(1);
        old.events().failure(new RadioTransportException(
                RadioFailure.Code.READ_TIMEOUT, true, "Late timeout", null));
        old.events().completion();
        old.events().soundEngineStopped();

        assertTrue(old.attempt().cancellation().isCancelled());
        assertEquals(RadioPlaybackState.RESOLVING, replacement.session().snapshot().state());
        assertFalse(replacement.attempt().cancellation().isCancelled());
        assertTrue(scheduler.tasks.isEmpty());
        assertEquals(2, sessions.started.size());
    }

    @Test
    void replacingFiniteWithLivePreservesLiveReconnectPolicy() {
        RecordingSessionDriver sessions = new RecordingSessionDriver();
        sessions.supportsFinite = true;
        ManualRetryScheduler scheduler = new ManualRetryScheduler();
        RadioReconnectController reconnects = new RadioReconnectController(
                NO_JITTER, () -> 0L, Runnable::run, scheduler);
        AudioPlaybackManager manager = new AudioPlaybackManager(
                new RecordingDriver(), sessions, new RecordingEffects(), reconnects);
        PlaybackOwnerKey key = PlaybackOwnerKey.block(FIRST_DIMENSION, BlockPos.ZERO);
        manager.update(key, finiteRemoteState(1L, "https://audio.example/track"));
        StartedSession finite = sessions.started.get(0);

        manager.update(key, liveState(2L, LIVE_SOURCE, true));
        StartedSession live = sessions.started.get(1);
        live.events().failure(new RadioTransportException(
                RadioFailure.Code.READ_TIMEOUT, true, "Timed out", null));

        assertTrue(finite.attempt().cancellation().isCancelled());
        assertEquals(List.of(key), sessions.stopped);
        assertEquals(RadioPlaybackState.RECONNECT_WAIT, live.session().snapshot().state());
        assertEquals(1, scheduler.tasks.size());
    }

    @Test
    void routingPinsLiveRetriesAndFiniteTerminalCleanupToTheirBackend() {
        RecordingSessionDriver live = new RecordingSessionDriver();
        RecordingSessionDriver finite = new RecordingSessionDriver();
        finite.supportsLive = false;
        finite.supportsFinite = true;
        RoutingPlaybackBackend routed = new RoutingPlaybackBackend(List.of(live, finite));
        ManualRetryScheduler scheduler = new ManualRetryScheduler();
        RadioReconnectController reconnects = new RadioReconnectController(
                NO_JITTER, () -> 0L, Runnable::run, scheduler);
        AudioPlaybackManager manager = new AudioPlaybackManager(
                new RecordingDriver(), routed, new RecordingEffects(), reconnects);
        PlaybackOwnerKey key = PlaybackOwnerKey.block(FIRST_DIMENSION, BlockPos.ZERO);
        manager.update(key, ENABLED);
        StartedSession first = live.started.get(0);

        first.events().failure(new RadioTransportException(
                RadioFailure.Code.CONNECT_TIMEOUT, true, "Timed out", null));
        scheduler.fire();

        assertEquals(2, live.started.size());
        assertTrue(finite.started.isEmpty());
        assertEquals(List.of(key), live.aborted);

        manager.update(key, finiteRemoteState(2L, "https://audio.example/track"));
        StartedSession finiteStart = finite.started.get(0);
        finiteStart.events().failure(new RadioTransportException(
                RadioFailure.Code.READ_TIMEOUT, true, "Timed out", null));
        assertEquals(RadioPlaybackState.FAILED, finiteStart.session().snapshot().state());
        assertEquals(List.of(key), finite.aborted);
        manager.remove(key);
        assertEquals(List.of(key), live.stopped);
        assertEquals(List.of(key), finite.stopped);
        manager.shutdown();
        assertTrue(live.shutdown);
        assertTrue(finite.shutdown);
    }

    @Test
    void routedBackendAbortsPartialFiniteStartBeforeReleasingTheSlot() {
        RecordingSessionDriver live = new RecordingSessionDriver();
        RecordingSessionDriver finite = new RecordingSessionDriver();
        finite.supportsLive = false;
        finite.supportsFinite = true;
        finite.throwOnStart = true;
        finite.openBeforeThrow = true;
        RadioConnectionScheduler connections = new RadioConnectionScheduler(1, 0, Runnable::run);
        AudioPlaybackManager manager = new AudioPlaybackManager(new RecordingDriver(),
                new RoutingPlaybackBackend(List.of(live, finite)), new RecordingEffects(),
                RadioReconnectController.createDefault(Runnable::run), connections);
        PlaybackOwnerKey key = PlaybackOwnerKey.block(FIRST_DIMENSION, BlockPos.ZERO);

        manager.update(key, finiteRemoteState(1L, "https://audio.example/track"));

        assertEquals(List.of(key), finite.aborted);
        assertTrue(finite.openSessions.isEmpty());
        assertEquals(0, connections.activeCount());
        manager.remove(key);
        assertEquals(List.of(key), finite.stopped);
        assertTrue(live.stopped.isEmpty());
        manager.shutdown();
    }

    @Test
    void finiteAdmissionLimitFailsWithoutLiveReconnect() {
        RecordingSessionDriver sessions = new RecordingSessionDriver();
        sessions.supportsFinite = true;
        ManualRetryScheduler scheduler = new ManualRetryScheduler();
        RadioReconnectController reconnects = new RadioReconnectController(
                NO_JITTER, () -> 0L, Runnable::run, scheduler);
        RadioConnectionScheduler connections = new RadioConnectionScheduler(1, 0, Runnable::run);
        AudioPlaybackManager manager = new AudioPlaybackManager(
                new RecordingDriver(), sessions, new RecordingEffects(), reconnects, connections);
        PlaybackOwnerKey first = PlaybackOwnerKey.block(FIRST_DIMENSION, BlockPos.ZERO);
        PlaybackOwnerKey second = PlaybackOwnerKey.block(FIRST_DIMENSION, BlockPos.ZERO.above());
        manager.update(first, finiteRemoteState(1L, "https://audio.example/first"));

        manager.update(second, finiteRemoteState(1L, "https://audio.example/second"));

        PlaybackSession.Snapshot rejected = manager.getSessionSnapshot(second).orElseThrow();
        assertEquals(RadioPlaybackState.FAILED, rejected.state());
        assertEquals(RadioFailure.Code.RESOURCE_LIMIT, rejected.failure().code());
        assertFalse(rejected.failure().recoverable());
        assertEquals(List.of(first), sessions.started.stream().map(StartedSession::key).toList());
        assertEquals(1, connections.activeCount());
        assertTrue(scheduler.tasks.isEmpty());
    }

    @Test
    void finiteBackendDoesNotClaimSoundEventTracks() {
        RecordingSessionDriver sessions = new RecordingSessionDriver();
        sessions.supportsFinite = true;
        AudioPlaybackManager manager = new AudioPlaybackManager(new RecordingDriver(), sessions);
        PlaybackOwnerKey key = PlaybackOwnerKey.block(FIRST_DIMENSION, BlockPos.ZERO);

        assertTrue(manager.update(key, finiteState(1L, true)));

        PlaybackState mixed = new PlaybackState(2L, Optional.of(new AudioProgram(AudioProgram.Kind.FINITE,
                List.of(new AudioTrack(AudioTrack.SourceType.REMOTE, "https://audio.example/track", "", ""),
                        new AudioTrack(AudioTrack.SourceType.SOUND_EVENT, "minecraft:music_disc.13", "", "")))),
                true);
        assertTrue(manager.update(key, mixed));

        assertTrue(sessions.started.isEmpty());
        assertEquals(RadioPlaybackState.STOPPED,
                manager.getSessionSnapshot(key).orElseThrow().state());
    }

    @Test
    void replacingLivePlaybackWithUnsupportedStateReleasesItsBackendAndEffects() {
        RecordingSessionDriver sessions = new RecordingSessionDriver();
        RecordingEffects effects = new RecordingEffects();
        AudioPlaybackManager manager = new AudioPlaybackManager(new RecordingDriver(), sessions, effects);
        PlaybackOwnerKey.BlockOwner key = PlaybackOwnerKey.block(FIRST_DIMENSION, BlockPos.ZERO);

        manager.update(key, ENABLED);
        StartedSession live = sessions.started.get(0);
        PlaybackState finite = finiteState(2L, true);
        manager.update(key, finite);

        assertTrue(live.attempt().cancellation().isCancelled());
        assertEquals(List.of(key), sessions.stopped);
        assertEquals(List.of(key), effects.stopped);
        assertEquals(finite, manager.getPlaybackState(key).orElseThrow());
        assertEquals(1, sessions.started.size());
    }

    @Test
    void removeAndClearCancelEveryOwnedSession() {
        RecordingSessionDriver sessions = new RecordingSessionDriver();
        AudioPlaybackManager manager = new AudioPlaybackManager(new RecordingDriver(), sessions);
        PlaybackOwnerKey firstKey = PlaybackOwnerKey.block(FIRST_DIMENSION, BlockPos.ZERO);
        PlaybackOwnerKey secondKey = PlaybackOwnerKey.block(FIRST_DIMENSION, BlockPos.ZERO.above());
        manager.update(firstKey, ENABLED);
        manager.update(secondKey, ENABLED);
        StartedSession first = sessions.started.get(0);
        StartedSession second = sessions.started.get(1);

        manager.remove(firstKey);
        manager.clearAll();

        assertTrue(first.attempt().cancellation().isCancelled());
        assertTrue(second.attempt().cancellation().isCancelled());
        assertEquals(Set.of(firstKey, secondKey), new HashSet<>(sessions.stopped));
    }

    @Test
    void sessionBackendIsExclusiveAndReportsOnlyPlayingState() {
        RecordingDriver playback = new RecordingDriver();
        RecordingSessionDriver sessions = new RecordingSessionDriver();
        RecordingEffects effects = new RecordingEffects();
        AudioPlaybackManager manager = new AudioPlaybackManager(playback, sessions, effects);
        PlaybackOwnerKey key = PlaybackOwnerKey.block(FIRST_DIMENSION, BlockPos.ZERO);

        manager.update(key, ENABLED);
        StartedSession started = sessions.started.get(0);

        assertTrue(playback.applied.isEmpty());
        assertFalse(manager.isPlaying(key));
        assertTrue(started.session().advance(started.attempt().generation(), RadioPlaybackState.CONNECTING));
        assertFalse(manager.isPlaying(key));
        assertTrue(started.session().advance(started.attempt().generation(), RadioPlaybackState.BUFFERING));
        assertFalse(manager.isPlaying(key));
        assertTrue(started.session().advance(started.attempt().generation(), RadioPlaybackState.PLAYING));
        assertTrue(manager.isPlaying(key));

        manager.tick(key, ENABLED);
        assertEquals(RadioPlaybackState.PLAYING, effects.updated.get(effects.updated.size() - 1).snapshot().state());
        manager.remove(key);

        assertTrue(playback.stopped.isEmpty());
        assertEquals(List.of(key), effects.stopped);
        assertFalse(manager.isPlaying(key));
    }

    @Test
    void drainsMetadataToEffectsAndRejectsDetachedSessionUpdates() {
        RecordingSessionDriver sessions = new RecordingSessionDriver();
        RecordingEffects effects = new RecordingEffects();
        AudioPlaybackManager manager = new AudioPlaybackManager(new RecordingDriver(), sessions, effects);
        PlaybackOwnerKey key = PlaybackOwnerKey.block(FIRST_DIMENSION, BlockPos.ZERO);

        manager.update(key, ENABLED);
        StartedSession first = sessions.started.get(0);
        assertTrue(first.session().advance(first.attempt().generation(), RadioPlaybackState.CONNECTING));
        assertTrue(first.session().advance(first.attempt().generation(), RadioPlaybackState.BUFFERING));
        assertTrue(first.session().offerStreamTitle(first.attempt(), "Current title"));

        manager.tick(key, ENABLED);
        EffectUpdate buffered = effects.updated.get(effects.updated.size() - 1);
        assertEquals(RadioPlaybackState.BUFFERING, buffered.snapshot().state());
        assertEquals("Current title", buffered.snapshot().streamTitle());

        assertTrue(first.session().advance(first.attempt().generation(), RadioPlaybackState.PLAYING));
        manager.tick(key, ENABLED);
        EffectUpdate playing = effects.updated.get(effects.updated.size() - 1);
        assertEquals(RadioPlaybackState.PLAYING, playing.snapshot().state());
        assertEquals("Current title", playing.snapshot().streamTitle());

        manager.update(key, liveState(2L, "https://radio.example/replacement", true));
        StartedSession second = sessions.started.get(1);
        assertEquals(first.attempt().generation(), second.attempt().generation());
        assertFalse(first.session().offerStreamTitle(first.attempt(), "Stale title"));
        assertFalse(second.session().offerStreamTitle(first.attempt(), "Stale title"));
        manager.tick(key, second.state());
        assertNull(manager.getSessionSnapshot(key).orElseThrow().streamTitle());
    }

    @Test
    void initialStartFailureUsesReconnectClassificationAndCanBeRetried() {
        RecordingSessionDriver sessions = new RecordingSessionDriver();
        sessions.throwOnStart = true;
        RecordingEffects effects = new RecordingEffects();
        AudioPlaybackManager manager = new AudioPlaybackManager(new RecordingDriver(), sessions, effects);
        PlaybackOwnerKey key = PlaybackOwnerKey.block(FIRST_DIMENSION, BlockPos.ZERO);

        assertTrue(manager.update(key, ENABLED));

        assertEquals(RadioPlaybackState.FAILED,
                manager.getSessionSnapshot(key).orElseThrow().state());
        assertEquals(ENABLED, manager.getPlaybackState(key).orElseThrow());
        assertEquals(List.of(key), sessions.aborted);
        sessions.throwOnStart = false;
        assertTrue(manager.retry(key));
        assertEquals(1, sessions.started.size());
    }

    @Test
    void stopFailureCannotOrphanEffectsOrOtherPlaybackDuringClear() {
        RecordingSessionDriver sessions = new RecordingSessionDriver();
        RecordingEffects effects = new RecordingEffects();
        AudioPlaybackManager manager = new AudioPlaybackManager(new RecordingDriver(), sessions, effects);
        PlaybackOwnerKey first = PlaybackOwnerKey.block(FIRST_DIMENSION, BlockPos.ZERO);
        PlaybackOwnerKey second = PlaybackOwnerKey.block(FIRST_DIMENSION, BlockPos.ZERO.above());
        manager.update(first, ENABLED);
        manager.update(second, ENABLED);
        sessions.throwOnStop = true;

        assertThrows(IllegalStateException.class, manager::clearAll);

        assertEquals(Set.of(first, second), new HashSet<>(effects.stopped));
        assertTrue(manager.getPlaybackState(first).isEmpty());
        assertTrue(manager.getPlaybackState(second).isEmpty());
    }

    @Test
    void recoverableSessionFailureStartsAnAutomaticRetry() {
        RecordingSessionDriver sessions = new RecordingSessionDriver();
        RecordingEffects effects = new RecordingEffects();
        ManualRetryScheduler scheduler = new ManualRetryScheduler();
        AtomicLong clock = new AtomicLong(10_000L);
        RadioReconnectController reconnects = new RadioReconnectController(
                NO_JITTER, clock::get, Runnable::run, scheduler);
        RadioConnectionScheduler connections = new RadioConnectionScheduler(1, 1, Runnable::run);
        AudioPlaybackManager manager = new AudioPlaybackManager(
                new RecordingDriver(), sessions, effects, reconnects, connections);
        PlaybackOwnerKey key = PlaybackOwnerKey.block(FIRST_DIMENSION, BlockPos.ZERO);
        manager.update(key, ENABLED);
        StartedSession first = sessions.started.get(0);

        first.events().failure(new RadioTransportException(
                RadioFailure.Code.CONNECT_TIMEOUT, true, "Timed out", null));

        assertEquals(RadioPlaybackState.RECONNECT_WAIT,
                manager.getSessionSnapshot(key).orElseThrow().state());
        assertEquals(RadioPlaybackState.RECONNECT_WAIT,
                effects.updated.get(effects.updated.size() - 1).snapshot().state());
        scheduler.fire();

        assertEquals(2, sessions.started.size());
        assertEquals(2, manager.getSessionSnapshot(key).orElseThrow().attemptNumber());
        assertEquals(RadioPlaybackState.RESOLVING,
                manager.getSessionSnapshot(key).orElseThrow().state());
        first.events().failure(new RadioTransportException(
                RadioFailure.Code.READ_TIMEOUT, true, "Stale", null));
        assertEquals(1, scheduler.tasks.size());
        assertEquals(1, connections.activeCount());
    }

    @Test
    void explicitRetryRestartsTheSameFailedStateAndResetsBackoff() {
        RecordingSessionDriver sessions = new RecordingSessionDriver();
        ManualRetryScheduler scheduler = new ManualRetryScheduler();
        RadioReconnectController reconnects = new RadioReconnectController(
                NO_JITTER, () -> 0L, Runnable::run, scheduler);
        AudioPlaybackManager manager = new AudioPlaybackManager(
                new RecordingDriver(), sessions, new RecordingEffects(), reconnects);
        PlaybackOwnerKey key = PlaybackOwnerKey.block(FIRST_DIMENSION, BlockPos.ZERO);
        manager.update(key, ENABLED);
        StartedSession first = sessions.started.get(0);
        first.events().failure(new java.io.IOException("Invalid audio"));

        assertEquals(RadioPlaybackState.FAILED,
                manager.getSessionSnapshot(key).orElseThrow().state());
        assertTrue(manager.retry(key));

        assertEquals(2, sessions.started.size());
        assertEquals(first.state(), sessions.started.get(1).state());
        assertEquals(1, manager.getSessionSnapshot(key).orElseThrow().attemptNumber());
        assertFalse(manager.retry(key));
    }

    @Test
    void managerAppliesConnectionAdmissionToInitialAttemptsAndRetries() {
        RecordingSessionDriver sessions = new RecordingSessionDriver();
        ManualRetryScheduler retryScheduler = new ManualRetryScheduler();
        RadioReconnectController reconnects = new RadioReconnectController(
                NO_JITTER, () -> 0L, Runnable::run, retryScheduler);
        RadioConnectionScheduler connections = new RadioConnectionScheduler(1, 0, Runnable::run);
        AudioPlaybackManager manager = new AudioPlaybackManager(
                new RecordingDriver(), sessions, new RecordingEffects(), reconnects, connections);
        PlaybackOwnerKey first = PlaybackOwnerKey.block(FIRST_DIMENSION, BlockPos.ZERO);
        PlaybackOwnerKey second = PlaybackOwnerKey.block(FIRST_DIMENSION, BlockPos.ZERO.above());

        manager.update(first, ENABLED);
        manager.update(second, ENABLED);

        assertEquals(1, sessions.started.size());
        assertEquals(RadioPlaybackState.RECONNECT_WAIT,
                manager.getSessionSnapshot(second).orElseThrow().state());
        assertEquals(RadioFailure.Code.RESOURCE_LIMIT,
                manager.getSessionSnapshot(second).orElseThrow().failure().code());

        manager.remove(first);
        retryScheduler.fire();

        assertEquals(2, sessions.started.size());
        assertEquals(second, sessions.started.get(1).key());
        assertEquals(1, connections.activeCount());
    }

    @Test
    void detachedSessionCallbacksCannotAffectReplacementWithSameGeneration() {
        RecordingSessionDriver sessions = new RecordingSessionDriver();
        RecordingEffects effects = new RecordingEffects();
        ManualRetryScheduler scheduler = new ManualRetryScheduler();
        RadioReconnectController reconnects = new RadioReconnectController(
                NO_JITTER, () -> 0L, Runnable::run, scheduler);
        AudioPlaybackManager manager = new AudioPlaybackManager(
                new RecordingDriver(), sessions, effects, reconnects);
        PlaybackOwnerKey key = PlaybackOwnerKey.block(FIRST_DIMENSION, BlockPos.ZERO);
        manager.update(key, ENABLED);
        StartedSession detached = sessions.started.get(0);
        PlaybackState replacement = liveState(2L, "https://radio.example/new", true);
        manager.update(key, replacement);
        StartedSession current = sessions.started.get(1);
        int updates = effects.updated.size();

        detached.events().progress(RadioPlaybackState.CONNECTING);
        detached.events().failure(new RadioTransportException(
                RadioFailure.Code.CONNECT_TIMEOUT, true, "Late", null));
        detached.events().termination(new PlaybackAudioStream.Termination(
                PlaybackAudioStream.TerminalState.EOF, null));
        detached.events().soundEngineStopped();
        detached.events().completion();

        assertEquals(detached.attempt().generation(), current.attempt().generation());
        assertEquals(RadioPlaybackState.RESOLVING,
                manager.getSessionSnapshot(key).orElseThrow().state());
        assertEquals(updates, effects.updated.size());
        assertTrue(scheduler.tasks.isEmpty());
    }

    @Test
    void shutdownCancelsPendingRetriesAndClosesTheirScheduler() {
        RecordingSessionDriver sessions = new RecordingSessionDriver();
        ManualRetryScheduler scheduler = new ManualRetryScheduler();
        RadioReconnectController reconnects = new RadioReconnectController(
                NO_JITTER, () -> 0L, Runnable::run, scheduler);
        AudioPlaybackManager manager = new AudioPlaybackManager(
                new RecordingDriver(), sessions, new RecordingEffects(), reconnects);
        PlaybackOwnerKey key = PlaybackOwnerKey.block(FIRST_DIMENSION, BlockPos.ZERO);
        manager.update(key, ENABLED);
        sessions.started.get(0).events().failure(new RadioTransportException(
                RadioFailure.Code.CONNECT_TIMEOUT, true, "Timed out", null));

        manager.shutdown();

        assertTrue(scheduler.closed);
        assertTrue(scheduler.tasks.get(0).cancelled);
        assertTrue(manager.getPlaybackState(key).isEmpty());
        assertTrue(sessions.shutdown);
    }

    @Test
    void finiteCompletionClosesBackendBeforeReusingAdmissionLease() {
        RecordingSessionDriver sessions = new RecordingSessionDriver();
        RadioReconnectController reconnects = new RadioReconnectController(
                NO_JITTER, () -> 0L, Runnable::run, new ManualRetryScheduler());
        RadioConnectionScheduler connections = new RadioConnectionScheduler(1, 1, Runnable::run);
        AudioPlaybackManager manager = new AudioPlaybackManager(
                new RecordingDriver(), sessions, new RecordingEffects(), reconnects, connections);
        PlaybackOwnerKey first = PlaybackOwnerKey.block(FIRST_DIMENSION, BlockPos.ZERO);
        PlaybackOwnerKey second = PlaybackOwnerKey.block(FIRST_DIMENSION, BlockPos.ZERO.above());
        manager.update(first, ENABLED);
        manager.update(second, ENABLED);
        StartedSession started = sessions.started.get(0);
        started.events().progress(RadioPlaybackState.CONNECTING);
        started.events().progress(RadioPlaybackState.BUFFERING);
        started.events().progress(RadioPlaybackState.PLAYING);

        started.events().completion();

        assertEquals(RadioPlaybackState.STOPPED,
                manager.getSessionSnapshot(first).orElseThrow().state());
        assertEquals(List.of("start:" + first, "abort:" + first, "start:" + second),
                sessions.lifecycle);
        assertEquals(1, sessions.maximumOpen);
        assertEquals(1, connections.activeCount());

        started.events().completion();
        assertEquals(1, sessions.aborted.size());
    }

    @Test
    void shutdownRejectsLateUpdatesAndTicks() {
        RecordingDriver playback = new RecordingDriver();
        AudioPlaybackManager manager = new AudioPlaybackManager(playback);
        PlaybackOwnerKey key = PlaybackOwnerKey.block(FIRST_DIMENSION, BlockPos.ZERO);

        manager.shutdown();

        assertFalse(manager.update(key, ENABLED));
        manager.tick(key, ENABLED);
        assertTrue(playback.applied.isEmpty());
        assertTrue(playback.ticked.isEmpty());
        assertTrue(manager.getPlaybackState(key).isEmpty());
    }

    @Test
    void backendStopsBeforeItsConnectionSlotIsReused() {
        RecordingSessionDriver sessions = new RecordingSessionDriver();
        RadioReconnectController reconnects = new RadioReconnectController(
                NO_JITTER, () -> 0L, Runnable::run, new ManualRetryScheduler());
        RadioConnectionScheduler connections = new RadioConnectionScheduler(1, 1, Runnable::run);
        AudioPlaybackManager manager = new AudioPlaybackManager(
                new RecordingDriver(), sessions, new RecordingEffects(), reconnects, connections);
        PlaybackOwnerKey first = PlaybackOwnerKey.block(FIRST_DIMENSION, BlockPos.ZERO);
        PlaybackOwnerKey second = PlaybackOwnerKey.block(FIRST_DIMENSION, BlockPos.ZERO.above());
        manager.update(first, ENABLED);
        manager.update(second, ENABLED);

        manager.remove(first);

        assertEquals(List.of("start:" + first, "stop:" + first, "start:" + second), sessions.lifecycle);
        assertEquals(1, sessions.maximumOpen);
    }

    @Test
    void partialStartIsAbortedBeforeAdmissionSlotIsReleased() {
        RecordingSessionDriver sessions = new RecordingSessionDriver();
        sessions.throwOnStart = true;
        sessions.openBeforeThrow = true;
        RadioReconnectController reconnects = new RadioReconnectController(
                NO_JITTER, () -> 0L, Runnable::run, new ManualRetryScheduler());
        RadioConnectionScheduler connections = new RadioConnectionScheduler(1, 0, Runnable::run);
        AudioPlaybackManager manager = new AudioPlaybackManager(
                new RecordingDriver(), sessions, new RecordingEffects(), reconnects, connections);
        PlaybackOwnerKey first = PlaybackOwnerKey.block(FIRST_DIMENSION, BlockPos.ZERO);
        PlaybackOwnerKey second = PlaybackOwnerKey.block(FIRST_DIMENSION, BlockPos.ZERO.above());

        manager.update(first, ENABLED);
        sessions.throwOnStart = false;
        manager.update(second, ENABLED);

        assertTrue(sessions.open.contains(second));
        assertFalse(sessions.open.contains(first));
        assertEquals(1, sessions.maximumOpen);
        assertEquals(List.of(first), sessions.aborted);
    }

    @Test
    void initialWorkerRejectionUsesRecoverableBackoff() {
        RecordingSessionDriver sessions = new RecordingSessionDriver();
        sessions.throwOnStart = true;
        sessions.startFailure = new RejectedExecutionException("busy");
        ManualRetryScheduler scheduler = new ManualRetryScheduler();
        RadioReconnectController reconnects = new RadioReconnectController(
                NO_JITTER, () -> 0L, Runnable::run, scheduler);
        AudioPlaybackManager manager = new AudioPlaybackManager(
                new RecordingDriver(), sessions, new RecordingEffects(), reconnects);
        PlaybackOwnerKey key = PlaybackOwnerKey.block(FIRST_DIMENSION, BlockPos.ZERO);

        manager.update(key, ENABLED);

        assertEquals(RadioPlaybackState.RECONNECT_WAIT,
                manager.getSessionSnapshot(key).orElseThrow().state());
        assertEquals(RadioFailure.Code.RESOURCE_LIMIT,
                manager.getSessionSnapshot(key).orElseThrow().failure().code());
        assertEquals(1, scheduler.tasks.size());
        sessions.throwOnStart = false;
        scheduler.fire();
        assertEquals(1, sessions.started.size());
    }

    @Test
    void ownerDispatchFailureCannotOrphanQueuedSession() {
        RecordingSessionDriver sessions = new RecordingSessionDriver();
        RadioReconnectController reconnects = new RadioReconnectController(
                NO_JITTER, () -> 0L, Runnable::run, new ManualRetryScheduler());
        AtomicIntegerExecutor owner = new AtomicIntegerExecutor();
        RadioConnectionScheduler connections = new RadioConnectionScheduler(1, 1, owner);
        AudioPlaybackManager manager = new AudioPlaybackManager(
                new RecordingDriver(), sessions, new RecordingEffects(), reconnects, connections);
        PlaybackOwnerKey first = PlaybackOwnerKey.block(FIRST_DIMENSION, BlockPos.ZERO);
        PlaybackOwnerKey second = PlaybackOwnerKey.block(FIRST_DIMENSION, BlockPos.ZERO.above());
        manager.update(first, ENABLED);
        manager.update(second, ENABLED);
        owner.reject = true;

        manager.remove(first);

        assertEquals(RadioPlaybackState.FAILED,
                manager.getSessionSnapshot(second).orElseThrow().state());
        assertEquals(RadioFailure.Code.RESOURCE_LIMIT,
                manager.getSessionSnapshot(second).orElseThrow().failure().code());
    }

    @Test
    void sessionOwnerFailureClosesBackendAndReleasesAdmissionLease() {
        RecordingSessionDriver sessions = new RecordingSessionDriver();
        RadioReconnectController reconnects = new RadioReconnectController(
                NO_JITTER, () -> 0L, Runnable::run, new ManualRetryScheduler());
        RadioConnectionScheduler connections = new RadioConnectionScheduler(1, 0, Runnable::run);
        AudioPlaybackManager manager = new AudioPlaybackManager(
                new RecordingDriver(), sessions, new RecordingEffects(), reconnects, connections);
        PlaybackOwnerKey key = PlaybackOwnerKey.block(FIRST_DIMENSION, BlockPos.ZERO);
        manager.update(key, ENABLED);

        sessions.started.get(0).events().ownerUnavailable(
                new RejectedExecutionException("client executor stopped"));

        assertEquals(RadioPlaybackState.FAILED,
                manager.getSessionSnapshot(key).orElseThrow().state());
        assertEquals(RadioFailure.Code.RESOURCE_LIMIT,
                manager.getSessionSnapshot(key).orElseThrow().failure().code());
        assertEquals(List.of(key), sessions.aborted);
        assertEquals(0, connections.activeCount());
    }

    @Test
    void abortFailureCannotPreventScheduledReconnect() {
        RecordingSessionDriver sessions = new RecordingSessionDriver();
        ManualRetryScheduler scheduler = new ManualRetryScheduler();
        RadioReconnectController reconnects = new RadioReconnectController(
                NO_JITTER, () -> 0L, Runnable::run, scheduler);
        RadioConnectionScheduler connections = new RadioConnectionScheduler(1, 0, Runnable::run);
        AudioPlaybackManager manager = new AudioPlaybackManager(
                new RecordingDriver(), sessions, new RecordingEffects(), reconnects, connections);
        PlaybackOwnerKey key = PlaybackOwnerKey.block(FIRST_DIMENSION, BlockPos.ZERO);
        manager.update(key, ENABLED);
        sessions.throwOnAbort = true;

        assertThrows(IllegalStateException.class, () -> sessions.started.get(0).events().failure(
                new RadioTransportException(RadioFailure.Code.READ_TIMEOUT, true, "Timed out", null)));

        assertEquals(RadioPlaybackState.RECONNECT_WAIT,
                manager.getSessionSnapshot(key).orElseThrow().state());
        assertEquals(1, scheduler.tasks.size());
        assertEquals(0, connections.activeCount());
        sessions.throwOnAbort = false;
        scheduler.fire();
        assertEquals(2, sessions.started.size());
    }

    private static ResourceKey<Level> dimension(String path) {
        return ResourceKey.create(Registries.DIMENSION, ResourceLocation.fromNamespaceAndPath("etched_test", path));
    }

    private static PlaybackState liveState(long revision, String source, boolean enabled) {
        return state(revision, AudioProgram.Kind.LIVE,
                new AudioTrack(AudioTrack.SourceType.REMOTE, source, "", ""), enabled);
    }

    private static PlaybackState finiteState(long revision, boolean enabled) {
        return state(revision, AudioProgram.Kind.FINITE,
                new AudioTrack(AudioTrack.SourceType.SOUND_EVENT,
                        "minecraft:music_disc.13", "", ""), enabled);
    }

    private static PlaybackState finiteRemoteState(long revision, String... sources) {
        return new PlaybackState(revision, Optional.of(new AudioProgram(AudioProgram.Kind.FINITE,
                Arrays.stream(sources).map(source ->
                        new AudioTrack(AudioTrack.SourceType.REMOTE, source, "", "")).toList())), true);
    }

    private static PlaybackState state(long revision, AudioProgram.Kind kind,
                                       AudioTrack track, boolean enabled) {
        return new PlaybackState(revision,
                Optional.of(new AudioProgram(kind, List.of(track))), enabled);
    }

    private static String source(PlaybackState state) {
        return state.program().orElseThrow().tracks().get(0).source();
    }

    private static final class RecordingDriver implements AudioPlaybackManager.PlaybackDriver {

        private final List<AppliedState> applied = new ArrayList<>();
        private final List<PlaybackOwnerKey> stopped = new ArrayList<>();
        private final List<PlaybackOwnerKey> ticked = new ArrayList<>();
        private final List<AppliedState> tickedStates = new ArrayList<>();
        private final Set<PlaybackOwnerKey> playing = new HashSet<>();

        @Override
        public void apply(PlaybackOwnerKey key, PlaybackState state) {
            this.applied.add(new AppliedState(key, state));
            if (state.enabled()) {
                this.playing.add(key);
            } else {
                this.playing.remove(key);
            }
        }

        @Override
        public void stop(PlaybackOwnerKey key) {
            this.stopped.add(key);
            this.playing.remove(key);
        }

        @Override
        public void tick(PlaybackOwnerKey key, PlaybackState state) {
            this.ticked.add(key);
            this.tickedStates.add(new AppliedState(key, state));
        }

        @Override
        public boolean isPlaying(PlaybackOwnerKey key) {
            return this.playing.contains(key);
        }
    }

    private static final class RecordingSessionDriver implements PlaybackBackend {

        private final List<StartedSession> started = new ArrayList<>();
        private final List<PlaybackOwnerKey> stopped = new ArrayList<>();
        private final List<PlaybackOwnerKey> aborted = new ArrayList<>();
        private final List<String> lifecycle = new ArrayList<>();
        private final Set<PlaybackOwnerKey> open = new HashSet<>();
        private final Set<PlaybackSession> openSessions = new HashSet<>();
        private boolean throwOnStart;
        private boolean openBeforeThrow;
        private boolean throwOnStop;
        private boolean throwOnAbort;
        private boolean shutdown;
        private boolean supportsFinite;
        private boolean supportsLive = true;
        private int maximumOpen;
        private RuntimeException startFailure;

        @Override
        public boolean supports(PlaybackOwnerKey key, PlaybackState state) {
            return state.program().filter(program -> this.supportsLive && program.kind() == AudioProgram.Kind.LIVE
                    || this.supportsFinite && program.kind() == AudioProgram.Kind.FINITE).isPresent();
        }

        @Override
        public void start(PlaybackOwnerKey key, PlaybackState state, PlaybackSession session,
                          PlaybackSession.Attempt attempt, PlaybackBackend.Events events) {
            if (this.throwOnStart) {
                if (this.openBeforeThrow) {
                    this.open.add(key);
                    this.openSessions.add(session);
                    this.maximumOpen = Math.max(this.maximumOpen, this.openSessions.size());
                }
                throw this.startFailure == null
                        ? new IllegalStateException("start failed") : this.startFailure;
            }
            this.started.add(new StartedSession(key, state, session, attempt, events));
            this.open.add(key);
            this.openSessions.add(session);
            this.maximumOpen = Math.max(this.maximumOpen, this.openSessions.size());
            this.lifecycle.add("start:" + key);
        }

        @Override
        public void stop(PlaybackOwnerKey key, PlaybackSession session) {
            StartedSession startedSession = this.started.stream()
                    .filter(started -> started.session() == session)
                    .findFirst()
                    .orElse(null);
            if (startedSession != null) {
                assertTrue(startedSession.attempt().cancellation().isCancelled());
            }
            this.stopped.add(key);
            this.open.remove(key);
            this.openSessions.remove(session);
            this.lifecycle.add("stop:" + key);
            if (this.throwOnStop) {
                throw new IllegalStateException("stop failed");
            }
        }

        @Override
        public void abort(PlaybackOwnerKey key, PlaybackSession session, PlaybackSession.Attempt attempt) {
            this.aborted.add(key);
            this.open.remove(key);
            this.openSessions.remove(session);
            this.lifecycle.add("abort:" + key);
            if (this.throwOnAbort) {
                throw new IllegalStateException("abort failed");
            }
        }

        @Override
        public void shutdown() {
            this.shutdown = true;
        }
    }

    private static final class RecordingEffects implements PlaybackEffects {

        private final List<EffectUpdate> updated = new ArrayList<>();
        private final List<PlaybackOwnerKey> stopped = new ArrayList<>();

        @Override
        public void update(PlaybackOwnerKey key, PlaybackSession.Snapshot snapshot) {
            this.updated.add(new EffectUpdate(key, snapshot));
        }

        @Override
        public void stop(PlaybackOwnerKey key) {
            this.stopped.add(key);
        }
    }

    private static final class ManualRetryScheduler implements RadioReconnectController.RetryScheduler {

        private final List<ScheduledRetry> tasks = new ArrayList<>();
        private boolean closed;

        @Override
        public RadioReconnectController.Cancellable schedule(Runnable task, long delayMillis) {
            ScheduledRetry retry = new ScheduledRetry(task, delayMillis);
            this.tasks.add(retry);
            return () -> retry.cancelled = true;
        }

        private void fire() {
            ScheduledRetry retry = this.tasks.get(this.tasks.size() - 1);
            if (!retry.cancelled) {
                retry.task.run();
            }
        }

        @Override
        public void close() {
            this.closed = true;
        }
    }

    private static final class ScheduledRetry {

        private final Runnable task;
        private final long delayMillis;
        private boolean cancelled;

        private ScheduledRetry(Runnable task, long delayMillis) {
            this.task = task;
            this.delayMillis = delayMillis;
        }
    }

    private static final class AtomicIntegerExecutor implements java.util.concurrent.Executor {

        private boolean reject;

        @Override
        public void execute(Runnable command) {
            if (this.reject) {
                throw new RejectedExecutionException("closed");
            }
            command.run();
        }
    }

    private record AppliedState(PlaybackOwnerKey key, PlaybackState state) {
    }

    private record StartedSession(PlaybackOwnerKey key, PlaybackState state, PlaybackSession session,
                                  PlaybackSession.Attempt attempt, PlaybackBackend.Events events) {
    }

    private record EffectUpdate(PlaybackOwnerKey key, PlaybackSession.Snapshot snapshot) {
    }
}
