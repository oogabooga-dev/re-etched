package gg.moonflower.etched.client.radio.history;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RadioHistoryStorageTest {

    private static final String CONTEXT = "mp:" + "a".repeat(64);

    @TempDir
    Path temporaryDirectory;

    @Test
    void roundTripsHistory() {
        Path file = this.temporaryDirectory.resolve("radio-history.json");
        RadioStationHistory written = new RadioStationHistory();
        written.record(CONTEXT, "https://radio.example/first");
        written.record(CONTEXT, "https://radio.example/second");
        RadioHistoryStorage writer = storage(written, file);

        writer.requestSave();
        writer.flush();

        RadioStationHistory loaded = new RadioStationHistory();
        storage(loaded, file).load();
        assertEquals(List.of("https://radio.example/second", "https://radio.example/first"),
                loaded.entries(CONTEXT));
    }

    @Test
    void filtersInvalidPersistedEntries() throws IOException {
        Path file = this.temporaryDirectory.resolve("radio-history.json");
        Files.writeString(file, """
                {
                  "version": 1,
                  "contexts": [{
                    "key": "%s",
                    "modified": 10,
                    "stations": ["broken", " https://radio.example/live ", "https://radio.example/live"]
                  }]
                }
                """.formatted(CONTEXT), StandardCharsets.UTF_8);
        RadioStationHistory history = new RadioStationHistory();

        storage(history, file).load();

        assertEquals(List.of("https://radio.example/live"), history.entries(CONTEXT));
    }

    @Test
    void recoversFromBackupWhenPrimaryIsCorrupt() throws IOException {
        Path file = this.temporaryDirectory.resolve("radio-history.json");
        Path backup = this.temporaryDirectory.resolve("radio-history.json.bak");
        Files.writeString(file, "{broken", StandardCharsets.UTF_8);
        Files.writeString(backup, """
                {"version":1,"contexts":[{"key":"%s","modified":1,
                "stations":["https://radio.example/live"]}]}
                """.formatted(CONTEXT), StandardCharsets.UTF_8);
        RadioStationHistory history = new RadioStationHistory();

        storage(history, file).load();

        assertEquals(List.of("https://radio.example/live"), history.entries(CONTEXT));
    }

    @Test
    void leavesUnknownFutureSchemaUntouched() throws IOException {
        Path file = this.temporaryDirectory.resolve("radio-history.json");
        String future = "{\"version\":2,\"contexts\":[]}";
        Files.writeString(file, future, StandardCharsets.UTF_8);
        RadioStationHistory history = new RadioStationHistory();
        RadioHistoryStorage storage = storage(history, file);

        storage.load();
        assertTrue(history.record(CONTEXT, "https://radio.example/live"));
        storage.requestSave();
        storage.flush();

        assertEquals(future, Files.readString(file, StandardCharsets.UTF_8));
        assertFalse(Files.exists(this.temporaryDirectory.resolve("radio-history.json.tmp")));
    }

    @Test
    void clearReplacementCannotBeRecoveredFromBackup() throws IOException {
        Path file = this.temporaryDirectory.resolve("radio-history.json");
        RadioStationHistory history = new RadioStationHistory();
        RadioHistoryStorage storage = storage(history, file);
        history.record(CONTEXT, "https://radio.example/private?token=secret");
        storage.requestSave();
        storage.flush();
        history.record(CONTEXT, "https://radio.example/second");
        storage.requestSave();
        storage.flush();

        assertTrue(history.clear(CONTEXT));
        storage.requestSaveAndReplaceBackup();
        storage.flush();
        Files.writeString(file, "{broken", StandardCharsets.UTF_8);

        RadioStationHistory recovered = new RadioStationHistory();
        storage(recovered, file).load();
        assertTrue(recovered.entries(CONTEXT).isEmpty());
        assertFalse(Files.readString(file.resolveSibling("radio-history.json.bak"))
                .contains("token=secret"));
    }

    @Test
    void flushRetriesTransientWriteFailure() throws IOException {
        Path blocker = this.temporaryDirectory.resolve("blocked");
        Files.writeString(blocker, "not a directory", StandardCharsets.UTF_8);
        Path file = blocker.resolve("radio-history.json");
        RadioStationHistory history = new RadioStationHistory();
        history.record(CONTEXT, "https://radio.example/live");
        RadioHistoryStorage storage = storage(history, file);

        storage.requestSave();
        Files.delete(blocker);
        storage.flush();

        RadioStationHistory loaded = new RadioStationHistory();
        storage(loaded, file).load();
        assertEquals(List.of("https://radio.example/live"), loaded.entries(CONTEXT));
    }

    private static RadioHistoryStorage storage(RadioStationHistory history, Path file) {
        return new RadioHistoryStorage(history, file, Runnable::run);
    }
}
