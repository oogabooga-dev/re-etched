package gg.moonflower.etched.client.radio.history;

import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RadioStationHistoryTest {

    private static final String CONTEXT = "mp:" + "a".repeat(64);

    @Test
    void storesNormalizedStationsNewestFirstAndPromotesDuplicates() {
        RadioStationHistory history = history();

        assertTrue(history.record(CONTEXT, "  https://radio.example/first  "));
        assertTrue(history.record(CONTEXT, "https://radio.example/second"));
        assertTrue(history.record(CONTEXT, "https://radio.example/first"));
        assertFalse(history.record(CONTEXT, "https://radio.example/first"));

        assertEquals(List.of("https://radio.example/first", "https://radio.example/second"),
                history.entries(CONTEXT));
    }

    @Test
    void rejectsInvalidValuesAndContextKeys() {
        RadioStationHistory history = history();

        assertFalse(history.record(CONTEXT, ""));
        assertFalse(history.record(CONTEXT, "not a url"));
        assertFalse(history.record("server.example", "https://radio.example/live"));
        assertTrue(history.entries(CONTEXT).isEmpty());
    }

    @Test
    void retainsOnlyTwentyStationsAndKeepsContextsIndependent() {
        RadioStationHistory history = history();
        String otherContext = "sp:" + "b".repeat(64);

        for (int i = 0; i < 21; i++) {
            assertTrue(history.record(CONTEXT, "https://radio.example/" + i));
        }
        assertTrue(history.record(otherContext, "https://other.example/live"));

        assertEquals(20, history.entries(CONTEXT).size());
        assertEquals("https://radio.example/20", history.entries(CONTEXT).get(0));
        assertFalse(history.entries(CONTEXT).contains("https://radio.example/0"));
        assertEquals(List.of("https://other.example/live"), history.entries(otherContext));
    }

    @Test
    void clearsOneContextAndExposesImmutableEntries() {
        RadioStationHistory history = history();
        String otherContext = "sp:" + "b".repeat(64);
        history.record(CONTEXT, "https://radio.example/live");
        history.record(otherContext, "https://other.example/live");

        assertThrows(UnsupportedOperationException.class,
                () -> history.entries(CONTEXT).add("https://radio.example/other"));
        assertTrue(history.clear(CONTEXT));
        assertFalse(history.clear(CONTEXT));
        assertTrue(history.entries(CONTEXT).isEmpty());
        assertEquals(1, history.entries(otherContext).size());
    }

    private static RadioStationHistory history() {
        return new RadioStationHistory(Clock.fixed(Instant.ofEpochMilli(100), ZoneOffset.UTC));
    }
}
