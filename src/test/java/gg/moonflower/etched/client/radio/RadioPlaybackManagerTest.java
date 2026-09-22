package gg.moonflower.etched.client.radio;

import gg.moonflower.etched.common.radio.RadioConfiguration;
import gg.moonflower.etched.client.radio.net.RadioTransportException;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.Level;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.RejectedExecutionException;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RadioPlaybackManagerTest {

    private static final RadioReconnectPolicy NO_JITTER = new RadioReconnectPolicy(
            new long[]{1_000L, 2_000L, 5_000L, 10_000L, 20_000L, 30_000L},
            30_000L, 0.0D, () -> 0.5D);

    static {
        MinecraftTestBootstrap.bootStrap();
    }

    private static final ResourceKey<Level> FIRST_DIMENSION = dimension("first");
    private static final ResourceKey<Level> SECOND_DIMENSION = dimension("second");
    private static final RadioConfiguration ENABLED =
            new RadioConfiguration("https://radio.example/live", false);

    @Test
    void deduplicatesConfigurationAndAppliesMeaningfulChanges() {
        RecordingDriver driver = new RecordingDriver();
        RadioPlaybackManager manager = new RadioPlaybackManager(driver);
        RadioKey key = new RadioKey(FIRST_DIMENSION, new BlockPos(1, 2, 3));

        assertTrue(manager.update(key, ENABLED));
        assertFalse(manager.update(key, ENABLED));
        assertTrue(manager.update(key, new RadioConfiguration(ENABLED.url(), true)));

        assertEquals(2, driver.applied.size());
        assertEquals(new RadioConfiguration(ENABLED.url(), true), manager.getConfiguration(key).orElseThrow());
        assertEquals(RadioPlaybackState.STOPPED,
                manager.getSessionSnapshot(key).orElseThrow().state());
    }

    @Test
    void removesRadiosIdempotently() {
        RecordingDriver driver = new RecordingDriver();
        RadioPlaybackManager manager = new RadioPlaybackManager(driver);
        RadioKey key = new RadioKey(FIRST_DIMENSION, BlockPos.ZERO);
        manager.update(key, ENABLED);

        assertTrue(manager.remove(key));
        assertFalse(manager.remove(key));

        assertEquals(List.of(key), driver.stopped);
        assertTrue(manager.getConfiguration(key).isEmpty());
    }

    @Test
    void keepsEqualPositionsInDifferentDimensionsIndependent() {
        RecordingDriver driver = new RecordingDriver();
        RadioPlaybackManager manager = new RadioPlaybackManager(driver);
        RadioKey first = new RadioKey(FIRST_DIMENSION, BlockPos.ZERO);
        RadioKey second = new RadioKey(SECOND_DIMENSION, BlockPos.ZERO);

        manager.update(first, ENABLED);
        manager.update(second, ENABLED);
        manager.remove(first);

        assertTrue(manager.getConfiguration(first).isEmpty());
        assertEquals(ENABLED, manager.getConfiguration(second).orElseThrow());
    }

    @Test
    void tickSelfHealsMissedUpdatesWithoutRestartingKnownRadio() {
        RecordingDriver driver = new RecordingDriver();
        RadioPlaybackManager manager = new RadioPlaybackManager(driver);
        RadioKey key = new RadioKey(FIRST_DIMENSION, BlockPos.ZERO);

        manager.tick(key, ENABLED);
        manager.tick(key, ENABLED);

        assertEquals(1, driver.applied.size());
        assertEquals(List.of(key, key), driver.ticked);
    }

    @Test
    void reportsActualDriverStateOnlyForTrackedRadios() {
        RecordingDriver driver = new RecordingDriver();
        RadioPlaybackManager manager = new RadioPlaybackManager(driver);
        RadioKey tracked = new RadioKey(FIRST_DIMENSION, BlockPos.ZERO);
        RadioKey missing = new RadioKey(FIRST_DIMENSION, BlockPos.ZERO.above());
        manager.update(tracked, ENABLED);

        assertTrue(manager.isPlaying(tracked));
        driver.playing.add(missing);
        assertFalse(manager.isPlaying(missing));
    }

    @Test
    void clearStopsEveryRadioAndIsIdempotent() {
        RecordingDriver driver = new RecordingDriver();
        RadioPlaybackManager manager = new RadioPlaybackManager(driver);
        RadioKey first = new RadioKey(FIRST_DIMENSION, BlockPos.ZERO);
        RadioKey second = new RadioKey(FIRST_DIMENSION, BlockPos.ZERO.above());
        manager.update(first, ENABLED);
        manager.update(second, ENABLED);

        manager.clearAll();
        manager.clearAll();

        assertEquals(Set.of(first, second), new HashSet<>(driver.stopped));
        assertEquals(2, driver.stopped.size());
        assertTrue(manager.getConfiguration(first).isEmpty());
        assertTrue(manager.getConfiguration(second).isEmpty());
    }

    @Test
    void keyCopiesMutablePosition() {
        BlockPos.MutableBlockPos mutable = new BlockPos.MutableBlockPos(1, 2, 3);
        RadioKey key = new RadioKey(FIRST_DIMENSION, mutable);

        mutable.set(9, 8, 7);

        assertEquals(new BlockPos(1, 2, 3), key.pos());
    }

    @Test
    void replacementAndRedstoneCancelThePreviousGeneration() {
        RecordingDriver playback = new RecordingDriver();
        RecordingSessionDriver sessions = new RecordingSessionDriver();
        RadioPlaybackManager manager = new RadioPlaybackManager(playback, sessions);
        RadioKey key = new RadioKey(FIRST_DIMENSION, BlockPos.ZERO);

        manager.update(key, ENABLED);
        StartedSession first = sessions.started.get(0);
        assertFalse(manager.update(key, ENABLED));
        assertFalse(first.attempt().cancellation().isCancelled());
        manager.update(key, new RadioConfiguration("https://radio.example/new", false));
        StartedSession second = sessions.started.get(1);

        assertTrue(first.attempt().cancellation().isCancelled());
        assertFalse(first.session().advance(first.attempt().generation(), RadioPlaybackState.CONNECTING));
        assertFalse(second.attempt().cancellation().isCancelled());

        manager.update(key, new RadioConfiguration(second.configuration().url(), true));

        assertTrue(second.attempt().cancellation().isCancelled());
        assertEquals(2, sessions.started.size());
        assertEquals(RadioPlaybackState.STOPPED,
                manager.getSessionSnapshot(key).orElseThrow().state());
    }

    @Test
    void repeatedUrlAndRedstoneChurnNeverOverlapsBackendOwnership() {
        RecordingSessionDriver sessions = new RecordingSessionDriver();
        RadioReconnectController reconnects = new RadioReconnectController(
                NO_JITTER, () -> 0L, Runnable::run, new ManualRetryScheduler());
        RadioConnectionScheduler connections = new RadioConnectionScheduler(1, 4, Runnable::run);
        RadioPlaybackManager manager = new RadioPlaybackManager(
                new RecordingDriver(), sessions, new RecordingEffects(), reconnects, connections);
        RadioKey key = new RadioKey(FIRST_DIMENSION, BlockPos.ZERO);

        manager.update(key, ENABLED);
        for (int i = 0; i < 100; i++) {
            String url = "https://radio.example/live/" + i;
            StartedSession previous = sessions.started.get(sessions.started.size() - 1);
            manager.update(key, new RadioConfiguration(url, false));
            assertTrue(previous.attempt().cancellation().isCancelled());
            StartedSession started = sessions.started.get(sessions.started.size() - 1);
            manager.update(key, new RadioConfiguration(url, true));
            assertTrue(started.attempt().cancellation().isCancelled());
            manager.update(key, new RadioConfiguration(url, false));
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
    void replacingQueuedRadioStartsOnlyItsLatestConfiguration() {
        RecordingSessionDriver sessions = new RecordingSessionDriver();
        RadioReconnectController reconnects = new RadioReconnectController(
                NO_JITTER, () -> 0L, Runnable::run, new ManualRetryScheduler());
        RadioConnectionScheduler connections = new RadioConnectionScheduler(1, 1, Runnable::run);
        RadioPlaybackManager manager = new RadioPlaybackManager(
                new RecordingDriver(), sessions, new RecordingEffects(), reconnects, connections);
        RadioKey incumbent = new RadioKey(FIRST_DIMENSION, BlockPos.ZERO);
        RadioKey queued = new RadioKey(FIRST_DIMENSION, BlockPos.ZERO.above());
        manager.update(incumbent, ENABLED);

        for (int i = 0; i < 50; i++) {
            manager.update(queued, new RadioConfiguration("https://radio.example/queued/" + i, false));
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
    void normalizesSurroundingUrlWhitespaceBeforeStartingASession() {
        RecordingSessionDriver sessions = new RecordingSessionDriver();
        RadioPlaybackManager manager = new RadioPlaybackManager(new RecordingDriver(), sessions);
        RadioKey key = new RadioKey(FIRST_DIMENSION, BlockPos.ZERO);

        manager.update(key, new RadioConfiguration("   ", false));
        manager.update(key, new RadioConfiguration("  https://radio.example/live  ", false));

        assertEquals(1, sessions.started.size());
        assertEquals("https://radio.example/live", sessions.started.get(0).attempt().source());
    }

    @Test
    void removeAndClearCancelEveryOwnedSession() {
        RecordingSessionDriver sessions = new RecordingSessionDriver();
        RadioPlaybackManager manager = new RadioPlaybackManager(new RecordingDriver(), sessions);
        RadioKey firstKey = new RadioKey(FIRST_DIMENSION, BlockPos.ZERO);
        RadioKey secondKey = new RadioKey(FIRST_DIMENSION, BlockPos.ZERO.above());
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
        RadioPlaybackManager manager = new RadioPlaybackManager(playback, sessions, effects);
        RadioKey key = new RadioKey(FIRST_DIMENSION, BlockPos.ZERO);

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
        RadioPlaybackManager manager = new RadioPlaybackManager(new RecordingDriver(), sessions, effects);
        RadioKey key = new RadioKey(FIRST_DIMENSION, BlockPos.ZERO);

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

        manager.update(key, new RadioConfiguration("https://radio.example/replacement", false));
        StartedSession second = sessions.started.get(1);
        assertEquals(first.attempt().generation(), second.attempt().generation());
        assertFalse(first.session().offerStreamTitle(first.attempt(), "Stale title"));
        assertFalse(second.session().offerStreamTitle(first.attempt(), "Stale title"));
        manager.tick(key, second.configuration());
        assertNull(manager.getSessionSnapshot(key).orElseThrow().streamTitle());
    }

    @Test
    void initialStartFailureUsesReconnectClassificationAndCanBeRetried() {
        RecordingSessionDriver sessions = new RecordingSessionDriver();
        sessions.throwOnStart = true;
        RecordingEffects effects = new RecordingEffects();
        RadioPlaybackManager manager = new RadioPlaybackManager(new RecordingDriver(), sessions, effects);
        RadioKey key = new RadioKey(FIRST_DIMENSION, BlockPos.ZERO);

        assertTrue(manager.update(key, ENABLED));

        assertEquals(RadioPlaybackState.FAILED,
                manager.getSessionSnapshot(key).orElseThrow().state());
        assertEquals(ENABLED, manager.getConfiguration(key).orElseThrow());
        assertEquals(List.of(key), sessions.aborted);
        sessions.throwOnStart = false;
        assertTrue(manager.retry(key));
        assertEquals(1, sessions.started.size());
    }

    @Test
    void stopFailureCannotOrphanEffectsOrOtherRadiosDuringClear() {
        RecordingSessionDriver sessions = new RecordingSessionDriver();
        RecordingEffects effects = new RecordingEffects();
        RadioPlaybackManager manager = new RadioPlaybackManager(new RecordingDriver(), sessions, effects);
        RadioKey first = new RadioKey(FIRST_DIMENSION, BlockPos.ZERO);
        RadioKey second = new RadioKey(FIRST_DIMENSION, BlockPos.ZERO.above());
        manager.update(first, ENABLED);
        manager.update(second, ENABLED);
        sessions.throwOnStop = true;

        assertThrows(IllegalStateException.class, manager::clearAll);

        assertEquals(Set.of(first, second), new HashSet<>(effects.stopped));
        assertTrue(manager.getConfiguration(first).isEmpty());
        assertTrue(manager.getConfiguration(second).isEmpty());
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
        RadioPlaybackManager manager = new RadioPlaybackManager(
                new RecordingDriver(), sessions, effects, reconnects, connections);
        RadioKey key = new RadioKey(FIRST_DIMENSION, BlockPos.ZERO);
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
    void explicitRetryRestartsTheSameFailedConfigurationAndResetsBackoff() {
        RecordingSessionDriver sessions = new RecordingSessionDriver();
        ManualRetryScheduler scheduler = new ManualRetryScheduler();
        RadioReconnectController reconnects = new RadioReconnectController(
                NO_JITTER, () -> 0L, Runnable::run, scheduler);
        RadioPlaybackManager manager = new RadioPlaybackManager(
                new RecordingDriver(), sessions, new RecordingEffects(), reconnects);
        RadioKey key = new RadioKey(FIRST_DIMENSION, BlockPos.ZERO);
        manager.update(key, ENABLED);
        StartedSession first = sessions.started.get(0);
        first.events().failure(new java.io.IOException("Invalid audio"));

        assertEquals(RadioPlaybackState.FAILED,
                manager.getSessionSnapshot(key).orElseThrow().state());
        assertTrue(manager.retry(key));

        assertEquals(2, sessions.started.size());
        assertEquals(first.configuration(), sessions.started.get(1).configuration());
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
        RadioPlaybackManager manager = new RadioPlaybackManager(
                new RecordingDriver(), sessions, new RecordingEffects(), reconnects, connections);
        RadioKey first = new RadioKey(FIRST_DIMENSION, BlockPos.ZERO);
        RadioKey second = new RadioKey(FIRST_DIMENSION, BlockPos.ZERO.above());

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
        RadioPlaybackManager manager = new RadioPlaybackManager(
                new RecordingDriver(), sessions, effects, reconnects);
        RadioKey key = new RadioKey(FIRST_DIMENSION, BlockPos.ZERO);
        manager.update(key, ENABLED);
        StartedSession detached = sessions.started.get(0);
        RadioConfiguration replacement = new RadioConfiguration("https://radio.example/new", false);
        manager.update(key, replacement);
        StartedSession current = sessions.started.get(1);
        int updates = effects.updated.size();

        detached.events().progress(RadioPlaybackState.CONNECTING);
        detached.events().failure(new RadioTransportException(
                RadioFailure.Code.CONNECT_TIMEOUT, true, "Late", null));
        detached.events().termination(new gg.moonflower.etched.client.radio.stream.RadioAudioStream.Termination(
                gg.moonflower.etched.client.radio.stream.RadioAudioStream.TerminalState.EOF, null));
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
        RadioPlaybackManager manager = new RadioPlaybackManager(
                new RecordingDriver(), sessions, new RecordingEffects(), reconnects);
        RadioKey key = new RadioKey(FIRST_DIMENSION, BlockPos.ZERO);
        manager.update(key, ENABLED);
        sessions.started.get(0).events().failure(new RadioTransportException(
                RadioFailure.Code.CONNECT_TIMEOUT, true, "Timed out", null));

        manager.shutdown();

        assertTrue(scheduler.closed);
        assertTrue(scheduler.tasks.get(0).cancelled);
        assertTrue(manager.getConfiguration(key).isEmpty());
        assertTrue(sessions.shutdown);
    }

    @Test
    void finiteCompletionClosesBackendBeforeReusingAdmissionLease() {
        RecordingSessionDriver sessions = new RecordingSessionDriver();
        RadioReconnectController reconnects = new RadioReconnectController(
                NO_JITTER, () -> 0L, Runnable::run, new ManualRetryScheduler());
        RadioConnectionScheduler connections = new RadioConnectionScheduler(1, 1, Runnable::run);
        RadioPlaybackManager manager = new RadioPlaybackManager(
                new RecordingDriver(), sessions, new RecordingEffects(), reconnects, connections);
        RadioKey first = new RadioKey(FIRST_DIMENSION, BlockPos.ZERO);
        RadioKey second = new RadioKey(FIRST_DIMENSION, BlockPos.ZERO.above());
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
    void shutdownRejectsLateLegacyUpdatesAndTicks() {
        RecordingDriver playback = new RecordingDriver();
        RadioPlaybackManager manager = new RadioPlaybackManager(playback);
        RadioKey key = new RadioKey(FIRST_DIMENSION, BlockPos.ZERO);

        manager.shutdown();

        assertFalse(manager.update(key, ENABLED));
        manager.tick(key, ENABLED);
        assertTrue(playback.applied.isEmpty());
        assertTrue(playback.ticked.isEmpty());
        assertTrue(manager.getConfiguration(key).isEmpty());
    }

    @Test
    void backendStopsBeforeItsConnectionSlotIsReused() {
        RecordingSessionDriver sessions = new RecordingSessionDriver();
        RadioReconnectController reconnects = new RadioReconnectController(
                NO_JITTER, () -> 0L, Runnable::run, new ManualRetryScheduler());
        RadioConnectionScheduler connections = new RadioConnectionScheduler(1, 1, Runnable::run);
        RadioPlaybackManager manager = new RadioPlaybackManager(
                new RecordingDriver(), sessions, new RecordingEffects(), reconnects, connections);
        RadioKey first = new RadioKey(FIRST_DIMENSION, BlockPos.ZERO);
        RadioKey second = new RadioKey(FIRST_DIMENSION, BlockPos.ZERO.above());
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
        RadioPlaybackManager manager = new RadioPlaybackManager(
                new RecordingDriver(), sessions, new RecordingEffects(), reconnects, connections);
        RadioKey first = new RadioKey(FIRST_DIMENSION, BlockPos.ZERO);
        RadioKey second = new RadioKey(FIRST_DIMENSION, BlockPos.ZERO.above());

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
        RadioPlaybackManager manager = new RadioPlaybackManager(
                new RecordingDriver(), sessions, new RecordingEffects(), reconnects);
        RadioKey key = new RadioKey(FIRST_DIMENSION, BlockPos.ZERO);

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
        RadioPlaybackManager manager = new RadioPlaybackManager(
                new RecordingDriver(), sessions, new RecordingEffects(), reconnects, connections);
        RadioKey first = new RadioKey(FIRST_DIMENSION, BlockPos.ZERO);
        RadioKey second = new RadioKey(FIRST_DIMENSION, BlockPos.ZERO.above());
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
        RadioPlaybackManager manager = new RadioPlaybackManager(
                new RecordingDriver(), sessions, new RecordingEffects(), reconnects, connections);
        RadioKey key = new RadioKey(FIRST_DIMENSION, BlockPos.ZERO);
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
        RadioPlaybackManager manager = new RadioPlaybackManager(
                new RecordingDriver(), sessions, new RecordingEffects(), reconnects, connections);
        RadioKey key = new RadioKey(FIRST_DIMENSION, BlockPos.ZERO);
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

    private static final class RecordingDriver implements RadioPlaybackManager.PlaybackDriver {

        private final List<AppliedConfiguration> applied = new ArrayList<>();
        private final List<RadioKey> stopped = new ArrayList<>();
        private final List<RadioKey> ticked = new ArrayList<>();
        private final Set<RadioKey> playing = new HashSet<>();

        @Override
        public void apply(RadioKey key, RadioConfiguration configuration) {
            this.applied.add(new AppliedConfiguration(key, configuration));
            if (configuration.isEnabled()) {
                this.playing.add(key);
            } else {
                this.playing.remove(key);
            }
        }

        @Override
        public void stop(RadioKey key) {
            this.stopped.add(key);
            this.playing.remove(key);
        }

        @Override
        public void tick(RadioKey key, RadioConfiguration configuration) {
            this.ticked.add(key);
        }

        @Override
        public boolean isPlaying(RadioKey key) {
            return this.playing.contains(key);
        }
    }

    private static final class RecordingSessionDriver implements RadioPlaybackManager.SessionDriver {

        private final List<StartedSession> started = new ArrayList<>();
        private final List<RadioKey> stopped = new ArrayList<>();
        private final List<RadioKey> aborted = new ArrayList<>();
        private final List<String> lifecycle = new ArrayList<>();
        private final Set<RadioKey> open = new HashSet<>();
        private final Set<RadioSession> openSessions = new HashSet<>();
        private boolean throwOnStart;
        private boolean openBeforeThrow;
        private boolean throwOnStop;
        private boolean throwOnAbort;
        private boolean shutdown;
        private int maximumOpen;
        private RuntimeException startFailure;

        @Override
        public void start(RadioKey key, RadioConfiguration configuration, RadioSession session,
                          RadioSession.Attempt attempt, RadioPlaybackManager.SessionEvents events) {
            if (this.throwOnStart) {
                if (this.openBeforeThrow) {
                    this.open.add(key);
                    this.openSessions.add(session);
                    this.maximumOpen = Math.max(this.maximumOpen, this.openSessions.size());
                }
                throw this.startFailure == null
                        ? new IllegalStateException("start failed") : this.startFailure;
            }
            this.started.add(new StartedSession(key, configuration, session, attempt, events));
            this.open.add(key);
            this.openSessions.add(session);
            this.maximumOpen = Math.max(this.maximumOpen, this.openSessions.size());
            this.lifecycle.add("start:" + key);
        }

        @Override
        public void stop(RadioKey key, RadioSession session) {
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
        public void abort(RadioKey key, RadioSession session, RadioSession.Attempt attempt) {
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

    private static final class RecordingEffects implements RadioPlaybackEffects {

        private final List<EffectUpdate> updated = new ArrayList<>();
        private final List<RadioKey> stopped = new ArrayList<>();

        @Override
        public void update(RadioKey key, RadioSession.Snapshot snapshot) {
            this.updated.add(new EffectUpdate(key, snapshot));
        }

        @Override
        public void stop(RadioKey key) {
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

    private record AppliedConfiguration(RadioKey key, RadioConfiguration configuration) {
    }

    private record StartedSession(RadioKey key, RadioConfiguration configuration, RadioSession session,
                                  RadioSession.Attempt attempt, RadioPlaybackManager.SessionEvents events) {
    }

    private record EffectUpdate(RadioKey key, RadioSession.Snapshot snapshot) {
    }
}
