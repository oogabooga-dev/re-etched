package gg.moonflower.etched.client.radio.history;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;

/** Loads and atomically persists client radio history. */
public final class RadioHistoryStorage {

    private static final Logger LOGGER = LogManager.getLogger();
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final int SCHEMA_VERSION = 1;
    private static final long MAX_FILE_SIZE = 8L * 1024L * 1024L;

    private final RadioStationHistory history;
    private final Path file;
    private final Path temporaryFile;
    private final Path backupFile;
    private final Executor executor;
    private CompletableFuture<Void> pendingWrite = CompletableFuture.completedFuture(null);
    private long requestedGeneration;
    private long savedGeneration;
    private long backupReplacementGeneration;
    private boolean primaryValid;
    private boolean readOnly;

    public RadioHistoryStorage(RadioStationHistory history, Path file, Executor executor) {
        this.history = Objects.requireNonNull(history, "history");
        this.file = Objects.requireNonNull(file, "file");
        this.temporaryFile = file.resolveSibling(file.getFileName() + ".tmp");
        this.backupFile = file.resolveSibling(file.getFileName() + ".bak");
        this.executor = Objects.requireNonNull(executor, "executor");
    }

    public void load() {
        LoadResult primary = this.read(this.file);
        if (primary.status == LoadStatus.SUCCESS) {
            this.history.restore(primary.snapshot);
            this.primaryValid = true;
            return;
        }
        if (primary.status == LoadStatus.FUTURE_VERSION) {
            this.readOnly = true;
            LOGGER.warn("Radio history uses a newer schema; leaving it untouched");
            return;
        }

        LoadResult backup = this.read(this.backupFile);
        if (backup.status == LoadStatus.SUCCESS) {
            this.history.restore(backup.snapshot);
            LOGGER.warn("Recovered radio history from backup");
        } else if (primary.status == LoadStatus.INVALID) {
            LOGGER.warn("Ignoring invalid radio history data");
        }
    }

    public synchronized void requestSave() {
        this.requestSave(false);
    }

    public synchronized void requestSaveAndReplaceBackup() {
        this.requestSave(true);
    }

    private void requestSave(boolean replaceBackup) {
        if (this.readOnly) {
            return;
        }
        long generation = ++this.requestedGeneration;
        if (replaceBackup) {
            this.backupReplacementGeneration = generation;
        }
        String json = this.serializeWithinLimit();
        this.enqueueWrite(generation, json, this.backupReplacementGeneration > this.savedGeneration);
    }

    public void flush() {
        CompletableFuture<Void> write;
        synchronized (this) {
            write = this.pendingWrite;
        }
        write.join();

        synchronized (this) {
            if (this.savedGeneration < this.requestedGeneration) {
                long generation = this.requestedGeneration;
                this.enqueueWrite(generation, this.serializeWithinLimit(),
                        this.backupReplacementGeneration > this.savedGeneration);
                write = this.pendingWrite;
            } else {
                return;
            }
        }
        write.join();
        synchronized (this) {
            if (this.savedGeneration < this.requestedGeneration) {
                LOGGER.warn("Radio history remains unsaved after retry");
            }
        }
    }

    private void enqueueWrite(long generation, String json, boolean replaceBackup) {
        this.pendingWrite = this.pendingWrite.handle((unused, throwable) -> null)
                .thenRunAsync(() -> {
                    if (this.write(json, replaceBackup)) {
                        synchronized (this) {
                            this.savedGeneration = Math.max(this.savedGeneration, generation);
                        }
                    }
                }, this.executor);
    }

    private String serializeWithinLimit() {
        String json = serialize(this.history.snapshot());
        while (json.getBytes(StandardCharsets.UTF_8).length > MAX_FILE_SIZE
                && this.history.removeOldestContext()) {
            json = serialize(this.history.snapshot());
        }
        return json;
    }

    private boolean write(String json, boolean replaceBackup) {
        try {
            Path parent = this.file.getParent();
            if (parent != null) {
                Files.createDirectories(parent);
            }
            if (!replaceBackup && this.primaryValid && Files.exists(this.file)) {
                Files.copy(this.file, this.backupFile, StandardCopyOption.REPLACE_EXISTING);
            }
            try (Writer writer = Files.newBufferedWriter(this.temporaryFile, StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING)) {
                writer.write(json);
            }
            try {
                Files.move(this.temporaryFile, this.file, StandardCopyOption.ATOMIC_MOVE,
                        StandardCopyOption.REPLACE_EXISTING);
            } catch (AtomicMoveNotSupportedException exception) {
                Files.move(this.temporaryFile, this.file, StandardCopyOption.REPLACE_EXISTING);
            }
            this.primaryValid = true;
            if (replaceBackup) {
                Files.copy(this.file, this.backupFile, StandardCopyOption.REPLACE_EXISTING);
            }
            return true;
        } catch (Exception exception) {
            LOGGER.error("Failed to write radio history", exception);
            return false;
        } finally {
            try {
                Files.deleteIfExists(this.temporaryFile);
            } catch (IOException exception) {
                LOGGER.debug("Failed to remove temporary radio history file", exception);
            }
        }
    }

    private LoadResult read(Path source) {
        if (!Files.exists(source)) {
            return LoadResult.missing();
        }
        try {
            if (Files.size(source) > MAX_FILE_SIZE) {
                return LoadResult.invalid();
            }
            try (Reader reader = Files.newBufferedReader(source, StandardCharsets.UTF_8)) {
                JsonElement rootElement = JsonParser.parseReader(reader);
                if (!rootElement.isJsonObject()) {
                    return LoadResult.invalid();
                }
                JsonObject root = rootElement.getAsJsonObject();
                if (!root.has("version") || !root.get("version").isJsonPrimitive()
                        || !root.getAsJsonPrimitive("version").isNumber()) {
                    return LoadResult.invalid();
                }
                int version = root.get("version").getAsInt();
                if (version > SCHEMA_VERSION) {
                    return LoadResult.futureVersion();
                }
                if (version != SCHEMA_VERSION || !root.has("contexts")
                        || !root.get("contexts").isJsonArray()) {
                    return LoadResult.invalid();
                }
                List<RadioStationHistory.ContextSnapshot> contexts = new ArrayList<>();
                for (JsonElement contextElement : root.getAsJsonArray("contexts")) {
                    if (!contextElement.isJsonObject()) {
                        continue;
                    }
                    JsonObject context = contextElement.getAsJsonObject();
                    if (!context.has("key") || !context.get("key").isJsonPrimitive()
                            || !context.has("stations") || !context.get("stations").isJsonArray()) {
                        continue;
                    }
                    String key = context.get("key").getAsString();
                    long modified = context.has("modified") && context.get("modified").isJsonPrimitive()
                            && context.getAsJsonPrimitive("modified").isNumber()
                            ? context.get("modified").getAsLong() : 0L;
                    List<String> stations = new ArrayList<>();
                    for (JsonElement station : context.getAsJsonArray("stations")) {
                        if (station.isJsonPrimitive() && station.getAsJsonPrimitive().isString()) {
                            stations.add(station.getAsString());
                        }
                    }
                    contexts.add(new RadioStationHistory.ContextSnapshot(key, modified, stations));
                }
                return LoadResult.success(new RadioStationHistory.Snapshot(contexts));
            }
        } catch (Exception exception) {
            LOGGER.debug("Failed to read radio history metadata", exception);
            return LoadResult.invalid();
        }
    }

    private static String serialize(RadioStationHistory.Snapshot snapshot) {
        JsonObject root = new JsonObject();
        root.addProperty("version", SCHEMA_VERSION);
        JsonArray contexts = new JsonArray();
        for (RadioStationHistory.ContextSnapshot context : snapshot.contexts()) {
            JsonObject contextJson = new JsonObject();
            contextJson.addProperty("key", context.key());
            contextJson.addProperty("modified", context.modified());
            JsonArray stations = new JsonArray();
            context.stations().forEach(stations::add);
            contextJson.add("stations", stations);
            contexts.add(contextJson);
        }
        root.add("contexts", contexts);
        return GSON.toJson(root);
    }

    private enum LoadStatus {
        SUCCESS,
        MISSING,
        INVALID,
        FUTURE_VERSION
    }

    private record LoadResult(LoadStatus status, RadioStationHistory.Snapshot snapshot) {

        private static LoadResult success(RadioStationHistory.Snapshot snapshot) {
            return new LoadResult(LoadStatus.SUCCESS, snapshot);
        }

        private static LoadResult missing() {
            return new LoadResult(LoadStatus.MISSING, null);
        }

        private static LoadResult invalid() {
            return new LoadResult(LoadStatus.INVALID, null);
        }

        private static LoadResult futureVersion() {
            return new LoadResult(LoadStatus.FUTURE_VERSION, null);
        }
    }
}
