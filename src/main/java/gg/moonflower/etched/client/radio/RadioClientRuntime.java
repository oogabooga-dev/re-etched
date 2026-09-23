package gg.moonflower.etched.client.radio;

import gg.moonflower.etched.client.radio.history.RadioHistoryContext;
import gg.moonflower.etched.client.radio.history.RadioHistoryStorage;
import gg.moonflower.etched.client.radio.history.RadioStationHistory;
import gg.moonflower.etched.common.radio.RadioClientBridge;
import gg.moonflower.etched.common.radio.RadioConfiguration;
import net.minecraft.Util;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.Level;

import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Supplier;
import java.util.concurrent.TimeUnit;

/** Coordinates playback and confirmed client-local station history. */
public final class RadioClientRuntime implements RadioClientBridge.Listener {

    private static final long PENDING_TIMEOUT_NANOS = TimeUnit.SECONDS.toNanos(30);

    private final RadioClientBridge.Listener playback;
    private final RadioStationHistory history;
    private final RadioHistoryStorage storage;
    private final Supplier<Optional<String>> contextSupplier;
    private final Map<RadioKey, PendingStation> pendingStations = new HashMap<>();
    private boolean initialized;
    private Optional<String> currentContext;

    RadioClientRuntime(RadioClientBridge.Listener playback, RadioStationHistory history,
                       RadioHistoryStorage storage, Supplier<Optional<String>> contextSupplier) {
        this.playback = Objects.requireNonNull(playback, "playback");
        this.history = Objects.requireNonNull(history, "history");
        this.storage = Objects.requireNonNull(storage, "storage");
        this.contextSupplier = Objects.requireNonNull(contextSupplier, "contextSupplier");
    }

    public static RadioClientRuntime getInstance() {
        return Holder.INSTANCE;
    }

    public synchronized void initialize() {
        if (!this.initialized) {
            this.storage.load();
            this.initialized = true;
        }
    }

    public synchronized Optional<String> currentContextKey() {
        if (this.currentContext == null) {
            this.currentContext = this.contextSupplier.get();
        }
        return this.currentContext;
    }

    public List<String> currentEntries() {
        return this.currentContextKey().map(this.history::entries).orElseGet(List::of);
    }

    public boolean clearCurrentHistory() {
        boolean changed = this.currentContextKey().map(this.history::clear).orElse(false);
        if (changed) {
            this.storage.requestSaveAndReplaceBackup();
        }
        return changed;
    }

    public synchronized void expectStation(ResourceKey<Level> dimension, BlockPos pos, String url) {
        this.currentContextKey().ifPresent(context -> this.pendingStations.put(
                new RadioKey(dimension, pos), new PendingStation(context, url,
                        System.nanoTime() + PENDING_TIMEOUT_NANOS)));
    }

    public synchronized void cancelExpectedStation(ResourceKey<Level> dimension, BlockPos pos) {
        this.pendingStations.remove(new RadioKey(dimension, pos));
    }

    @Override
    public void update(ResourceKey<Level> dimension, BlockPos pos, RadioConfiguration configuration) {
        RadioKey key = new RadioKey(dimension, pos);
        PendingStation confirmed = null;
        synchronized (this) {
            PendingStation pending = this.pendingStations.get(key);
            if (pending != null && pending.expired()) {
                this.pendingStations.remove(key);
            } else if (pending != null && configuration.manuallyEnabled()
                    && pending.url.equals(configuration.url())) {
                this.pendingStations.remove(key);
                confirmed = pending;
            }
        }
        if (confirmed != null && this.history.record(confirmed.contextKey, confirmed.url)) {
            this.storage.requestSave();
        }
        this.playback.update(dimension, pos, configuration);
    }

    @Override
    public void remove(ResourceKey<Level> dimension, BlockPos pos) {
        synchronized (this) {
            this.pendingStations.remove(new RadioKey(dimension, pos));
        }
        this.playback.remove(dimension, pos);
    }

    @Override
    public void tick(ResourceKey<Level> dimension, BlockPos pos, RadioConfiguration configuration) {
        synchronized (this) {
            PendingStation pending = this.pendingStations.get(new RadioKey(dimension, pos));
            if (pending != null && pending.expired()) {
                this.pendingStations.remove(new RadioKey(dimension, pos));
            }
        }
        this.playback.tick(dimension, pos, configuration);
    }

    @Override
    public boolean isPlaying(ResourceKey<Level> dimension, BlockPos pos) {
        return this.playback.isPlaying(dimension, pos);
    }

    public synchronized void clearPendingStations() {
        this.pendingStations.clear();
    }

    public void clearAll() {
        this.clearPendingStations();
        RadioPlaybackManager.getInstance().clearAll();
    }

    public void logout() {
        try {
            this.clearAll();
        } finally {
            try {
                this.storage.flush();
            } finally {
                synchronized (this) {
                    this.currentContext = null;
                }
            }
        }
    }

    public void shutdown() {
        this.clearPendingStations();
        this.storage.flush();
        RadioPlaybackManager.getInstance().shutdown();
    }

    private static RadioClientRuntime createDefault() {
        Minecraft minecraft = Minecraft.getInstance();
        RadioStationHistory history = new RadioStationHistory();
        Path file = minecraft.gameDirectory.toPath().resolve("re-etched").resolve("radio-history.json");
        RadioHistoryStorage storage = new RadioHistoryStorage(history, file, Util.ioPool());
        return new RadioClientRuntime(RadioPlaybackManager.getInstance(), history, storage,
                () -> RadioHistoryContext.resolve(Minecraft.getInstance()));
    }

    private static final class Holder {

        private static final RadioClientRuntime INSTANCE = createDefault();
    }

    private record PendingStation(String contextKey, String url, long deadlineNanos) {

        private PendingStation {
            Objects.requireNonNull(contextKey, "contextKey");
            Objects.requireNonNull(url, "url");
        }

        private boolean expired() {
            return System.nanoTime() - this.deadlineNanos >= 0;
        }
    }
}
