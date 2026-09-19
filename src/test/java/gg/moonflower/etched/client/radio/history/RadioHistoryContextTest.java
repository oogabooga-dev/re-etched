package gg.moonflower.etched.client.radio.history;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RadioHistoryContextTest {

    @TempDir
    Path temporaryDirectory;

    @Test
    void canonicalizesMultiplayerHostsAndDefaultPorts() {
        String first = RadioHistoryContext.multiplayerKey("EXAMPLE.com.").orElseThrow();
        String second = RadioHistoryContext.multiplayerKey("example.com:25565").orElseThrow();

        assertEquals(first, second);
        assertTrue(RadioHistoryContext.isValidKey(first));
        assertFalse(first.contains("example.com"));
    }

    @Test
    void separatesDifferentPorts() {
        assertNotEquals(RadioHistoryContext.multiplayerKey("example.com:25565"),
                RadioHistoryContext.multiplayerKey("example.com:25566"));
    }

    @Test
    void separatesSingleplayerFoldersWithoutExposingTheirNames() {
        Path saves = this.temporaryDirectory.resolve("saves");
        String first = RadioHistoryContext.singleplayerKey(saves.resolve("private-world"), saves);
        String second = RadioHistoryContext.singleplayerKey(saves.resolve("other-world"), saves);

        assertNotEquals(first, second);
        assertTrue(RadioHistoryContext.isValidKey(first));
        assertFalse(first.contains("private-world"));
    }
}
