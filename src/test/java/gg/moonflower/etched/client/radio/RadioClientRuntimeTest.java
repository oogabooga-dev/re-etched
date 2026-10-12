package gg.moonflower.etched.client.radio;

import gg.moonflower.etched.client.radio.history.RadioHistoryStorage;
import gg.moonflower.etched.client.radio.history.RadioStationHistory;
import gg.moonflower.etched.common.radio.RadioClientBridge;
import gg.moonflower.etched.common.radio.RadioConfiguration;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.Level;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.nio.file.Files;
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
        TestListener playback = new TestListener();
        RadioStationHistory history = new RadioStationHistory();
        RadioClientRuntime runtime = this.runtime(playback, history);

        runtime.expectStation(DIMENSION, POSITION, "https://radio.example/live");
        runtime.tick(DIMENSION, POSITION, new RadioConfiguration("https://radio.example/live", false));
        assertTrue(history.entries(CONTEXT).isEmpty());

        runtime.update(DIMENSION, POSITION, new RadioConfiguration("https://radio.example/other", false));
        assertTrue(history.entries(CONTEXT).isEmpty());

        runtime.update(DIMENSION, POSITION, new RadioConfiguration("https://radio.example/live", false));
        assertEquals(1, history.entries(CONTEXT).size());
        assertEquals(2, playback.updates);
        assertEquals(1, playback.ticks);
    }

    @Test
    void poweredUpdateStillConfirmsAndStopCancelsPendingStation() {
        RadioStationHistory history = new RadioStationHistory();
        RadioClientRuntime runtime = this.runtime(new TestListener(), history);

        runtime.expectStation(DIMENSION, POSITION, "https://radio.example/powered");
        runtime.update(DIMENSION, POSITION, new RadioConfiguration("https://radio.example/powered", true));
        assertEquals(1, history.entries(CONTEXT).size());

        runtime.expectStation(DIMENSION, POSITION, "https://radio.example/cancelled");
        runtime.cancelExpectedStation(DIMENSION, POSITION);
        runtime.update(DIMENSION, POSITION, new RadioConfiguration("https://radio.example/cancelled", false));
        assertEquals(1, history.entries(CONTEXT).size());
    }

    @Test
    void removalCancelsPendingStationAndDelegates() {
        TestListener playback = new TestListener();
        RadioStationHistory history = new RadioStationHistory();
        RadioClientRuntime runtime = this.runtime(playback, history);

        runtime.expectStation(DIMENSION, POSITION, "https://radio.example/live");
        runtime.remove(DIMENSION, POSITION);
        runtime.update(DIMENSION, POSITION, new RadioConfiguration("https://radio.example/live", false));

        assertTrue(history.entries(CONTEXT).isEmpty());
        assertEquals(1, playback.removals);
    }

    @Test
    void deletingOneStationPersistsWithoutChangingPlaybackOrOtherContexts() throws Exception {
        TestListener playback = new TestListener();
        RadioStationHistory history = new RadioStationHistory();
        RadioClientRuntime runtime = this.runtime(playback, history);
        String otherContext = "mp:" + "b".repeat(64);
        String removed = "https://radio.example/remove";
        String retained = "https://radio.example/retain";
        history.record(CONTEXT, removed);
        history.record(CONTEXT, retained);
        history.record(otherContext, removed);

        assertTrue(runtime.removeCurrentHistoryEntry(removed));
        assertFalse(runtime.removeCurrentHistoryEntry(removed));
        assertEquals(List.of(retained), runtime.currentEntries());
        assertEquals(List.of(removed), history.entries(otherContext));
        assertEquals(0, playback.updates + playback.removals + playback.ticks);

        Path file = this.temporaryDirectory.resolve("radio-history.json");
        RadioStationHistory restored = new RadioStationHistory();
        new RadioHistoryStorage(restored, file, Runnable::run).load();
        assertEquals(List.of(retained), restored.entries(CONTEXT));
        assertEquals(List.of(removed), restored.entries(otherContext));

        // Recovery from backup must not resurrect an explicitly deleted station.
        Files.writeString(file, "invalid json");
        new RadioHistoryStorage(restored, file, Runnable::run).load();
        assertEquals(List.of(retained), restored.entries(CONTEXT));
        assertTrue(runtime.removeCurrentHistoryEntry(retained));
        assertTrue(runtime.currentEntries().isEmpty());
    }

    private RadioClientRuntime runtime(TestListener playback, RadioStationHistory history) {
        RadioHistoryStorage storage = new RadioHistoryStorage(history,
                this.temporaryDirectory.resolve("radio-history.json"), Runnable::run);
        return new RadioClientRuntime(playback, history, storage, () -> Optional.of(CONTEXT));
    }

    private static final class TestListener implements RadioClientBridge.Listener {

        private int updates;
        private int removals;
        private int ticks;

        @Override
        public void update(ResourceKey<Level> dimension, BlockPos pos, RadioConfiguration configuration) {
            this.updates++;
        }

        @Override
        public void remove(ResourceKey<Level> dimension, BlockPos pos) {
            this.removals++;
        }

        @Override
        public void tick(ResourceKey<Level> dimension, BlockPos pos, RadioConfiguration configuration) {
            this.ticks++;
        }

        @Override
        public boolean isPlaying(ResourceKey<Level> dimension, BlockPos pos) {
            return false;
        }
    }
}
