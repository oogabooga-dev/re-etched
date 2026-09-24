package gg.moonflower.etched.client.radio;

import gg.moonflower.etched.client.radio.history.RadioHistoryStorage;
import gg.moonflower.etched.client.radio.history.RadioStationHistory;
import gg.moonflower.etched.common.audio.PlaybackState;
import gg.moonflower.etched.common.radio.RadioConfiguration;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.Level;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RadioClientRuntimeTest {

    private static final String CONTEXT = "mp:" + "a".repeat(64);

    static {
        MinecraftTestBootstrap.bootStrap();
    }

    private static final ResourceKey<Level> DIMENSION = Level.OVERWORLD;
    private static final BlockPos POSITION = new BlockPos(1, 2, 3);

    @TempDir
    Path temporaryDirectory;

    @Test
    void commitsOnlyMatchingAuthoritativeUpdates() {
        TestPlayback playback = new TestPlayback();
        RadioStationHistory history = new RadioStationHistory();
        RadioClientRuntime runtime = this.runtime(playback.manager, history);

        runtime.expectStation(DIMENSION, POSITION, "https://radio.example/live");
        runtime.tick(DIMENSION, POSITION, configuration(1L, "https://radio.example/live", false));
        assertTrue(history.entries(CONTEXT).isEmpty());

        runtime.update(DIMENSION, POSITION, configuration(2L, "https://radio.example/other", false));
        assertTrue(history.entries(CONTEXT).isEmpty());

        runtime.update(DIMENSION, POSITION, configuration(3L, "https://radio.example/live", false));
        assertEquals(1, history.entries(CONTEXT).size());
        assertEquals(List.of(1L, 2L, 3L), playback.applied.stream()
                .map(applied -> applied.state().revision()).toList());
        assertEquals(1, playback.ticked.size());
    }

    @Test
    void poweredUpdateStillConfirmsAndStopCancelsPendingStation() {
        TestPlayback playback = new TestPlayback();
        RadioStationHistory history = new RadioStationHistory();
        RadioClientRuntime runtime = this.runtime(playback.manager, history);

        runtime.expectStation(DIMENSION, POSITION, "https://radio.example/powered");
        runtime.update(DIMENSION, POSITION, configuration(1L, "https://radio.example/powered", true));
        assertEquals(1, history.entries(CONTEXT).size());
        assertFalse(playback.applied.get(0).state().enabled());
        assertTrue(playback.applied.get(0).state().program().isPresent());

        runtime.expectStation(DIMENSION, POSITION, "https://radio.example/cancelled");
        runtime.cancelExpectedStation(DIMENSION, POSITION);
        runtime.update(DIMENSION, POSITION, configuration(2L, "https://radio.example/cancelled", false));
        assertEquals(1, history.entries(CONTEXT).size());
    }

    @Test
    void retainedManuallyStoppedStationDoesNotConfirmPendingPlay() {
        TestPlayback playback = new TestPlayback();
        RadioStationHistory history = new RadioStationHistory();
        RadioClientRuntime runtime = this.runtime(playback.manager, history);

        runtime.expectStation(DIMENSION, POSITION, "https://radio.example/stopped");
        runtime.update(DIMENSION, POSITION, RadioConfiguration.forStation(
                1L, "https://radio.example/stopped", false, false));

        assertTrue(history.entries(CONTEXT).isEmpty());
        assertFalse(playback.applied.get(0).state().enabled());
        assertTrue(playback.applied.get(0).state().program().isPresent());
    }

    @Test
    void staleAndConflictingUpdatesDoNotConfirmPendingStation() {
        TestPlayback playback = new TestPlayback();
        RadioStationHistory history = new RadioStationHistory();
        RadioClientRuntime runtime = this.runtime(playback.manager, history);

        runtime.expectStation(DIMENSION, POSITION, "https://radio.example/pending");
        runtime.tick(DIMENSION, POSITION, configuration(5L, "https://radio.example/current", false));
        runtime.update(DIMENSION, POSITION, configuration(4L, "https://radio.example/pending", false));
        runtime.update(DIMENSION, POSITION, configuration(5L, "https://radio.example/pending", false));

        assertTrue(history.entries(CONTEXT).isEmpty());
        runtime.update(DIMENSION, POSITION, configuration(6L, "https://radio.example/pending", false));
        assertEquals(List.of("https://radio.example/pending"), history.entries(CONTEXT));
    }

    @Test
    void exactDuplicateUpdateCanConfirmStateFirstObservedByTick() {
        TestPlayback playback = new TestPlayback();
        RadioStationHistory history = new RadioStationHistory();
        RadioClientRuntime runtime = this.runtime(playback.manager, history);

        runtime.expectStation(DIMENSION, POSITION, "https://radio.example/live");
        RadioConfiguration state = configuration(1L, "https://radio.example/live", false);
        runtime.tick(DIMENSION, POSITION, state);
        runtime.update(DIMENSION, POSITION, state);

        assertEquals(List.of("https://radio.example/live"), history.entries(CONTEXT));
    }

    @Test
    void lossySameRevisionControlConflictDoesNotConfirmPendingStation() {
        TestPlayback playback = new TestPlayback();
        RadioStationHistory history = new RadioStationHistory();
        RadioClientRuntime runtime = this.runtime(playback.manager, history);
        String url = "https://radio.example/live";

        runtime.expectStation(DIMENSION, POSITION, url);
        runtime.tick(DIMENSION, POSITION, RadioConfiguration.forStation(1L, url, false, false));
        runtime.update(DIMENSION, POSITION, RadioConfiguration.forStation(1L, url, true, true));

        assertTrue(history.entries(CONTEXT).isEmpty());
        runtime.update(DIMENSION, POSITION, RadioConfiguration.forStation(2L, url, true, true));
        assertEquals(List.of(url), history.entries(CONTEXT));
    }

    @Test
    void removalCancelsPendingStationAndDelegates() {
        TestPlayback playback = new TestPlayback();
        RadioStationHistory history = new RadioStationHistory();
        RadioClientRuntime runtime = this.runtime(playback.manager, history);
        PlaybackOwnerKey key = PlaybackOwnerKey.block(DIMENSION, POSITION);
        playback.manager.update(key, configuration(0L, "https://radio.example/initial", false).toPlaybackState());

        runtime.expectStation(DIMENSION, POSITION, "https://radio.example/live");
        runtime.remove(DIMENSION, POSITION);
        runtime.update(DIMENSION, POSITION, configuration(1L, "https://radio.example/live", false));

        assertTrue(history.entries(CONTEXT).isEmpty());
        assertEquals(List.of(key), playback.stopped);
    }

    @Test
    void lifecycleMethodsTargetTheInjectedManager() {
        TestPlayback playback = new TestPlayback();
        RadioClientRuntime runtime = this.runtime(playback.manager, new RadioStationHistory());
        PlaybackOwnerKey key = PlaybackOwnerKey.block(DIMENSION, POSITION);

        runtime.update(DIMENSION, POSITION, configuration(1L, "https://radio.example/live", false));
        runtime.clearAll();
        assertTrue(playback.manager.getPlaybackState(key).isEmpty());

        runtime.update(DIMENSION, POSITION, configuration(2L, "https://radio.example/live", false));
        runtime.shutdown();
        assertFalse(playback.manager.update(key,
                configuration(3L, "https://radio.example/live", false).toPlaybackState()));
    }

    private RadioClientRuntime runtime(AudioPlaybackManager playback, RadioStationHistory history) {
        RadioHistoryStorage storage = new RadioHistoryStorage(history,
                this.temporaryDirectory.resolve("radio-history.json"), Runnable::run);
        return new RadioClientRuntime(playback, history, storage, () -> Optional.of(CONTEXT));
    }

    private static RadioConfiguration configuration(long revision, String url, boolean powered) {
        return RadioConfiguration.forStation(revision, url, true, powered);
    }

    private static final class TestPlayback implements AudioPlaybackManager.PlaybackDriver {

        private final AudioPlaybackManager manager = new AudioPlaybackManager(this);
        private final List<AppliedState> applied = new ArrayList<>();
        private final List<PlaybackOwnerKey> stopped = new ArrayList<>();
        private final List<AppliedState> ticked = new ArrayList<>();

        @Override
        public void apply(PlaybackOwnerKey key, PlaybackState state) {
            this.applied.add(new AppliedState(key, state));
        }

        @Override
        public void stop(PlaybackOwnerKey key) {
            this.stopped.add(key);
        }

        @Override
        public void tick(PlaybackOwnerKey key, PlaybackState state) {
            this.ticked.add(new AppliedState(key, state));
        }

        @Override
        public boolean isPlaying(PlaybackOwnerKey key) {
            return false;
        }
    }

    private record AppliedState(PlaybackOwnerKey key, PlaybackState state) {
    }
}
