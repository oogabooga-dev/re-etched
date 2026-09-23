package gg.moonflower.etched.common.blockentity;

import gg.moonflower.etched.common.audio.AudioNbtCodec;
import gg.moonflower.etched.common.audio.AudioProgram;
import gg.moonflower.etched.common.audio.AudioTrack;
import gg.moonflower.etched.common.radio.RadioConfiguration;
import net.minecraft.nbt.CompoundTag;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RadioControlStateTest {

    @Test
    void ignoresLegacyLayouts() {
        CompoundTag legacy = new CompoundTag();
        legacy.putString("Url", "https://radio.example/live");
        legacy.putString("StoredUrl", "https://radio.example/stored");
        RadioControlState state = new RadioControlState();

        assertTrue(state.load(legacy));

        assertNull(state.storedUrl());
        assertTrue(state.station().isEmpty());
        assertFalse(state.enabled());
        assertEquals(0L, state.revision());
    }

    @Test
    void stoppedStationRoundTripsWithExplicitStateAndRevision() {
        RadioControlState state = new RadioControlState();
        assertTrue(state.apply("https://radio.example/live"));
        assertTrue(state.apply(""));
        CompoundTag saved = new CompoundTag();

        state.save(saved);

        assertTrue(saved.contains("Station"));
        assertFalse(saved.getBoolean("Enabled"));
        assertEquals(2L, saved.getLong("PlaybackRevision"));
        assertFalse(saved.contains("Url"));
        assertFalse(saved.contains("StoredUrl"));

        RadioControlState loaded = new RadioControlState();
        assertTrue(loaded.load(saved));
        assertEquals("https://radio.example/live", loaded.storedUrl());
        assertFalse(loaded.enabled());
        assertEquals(2L, loaded.revision());
        assertEquals(AudioProgram.Kind.LIVE, loaded.station().orElseThrow().kind());
    }

    @Test
    void playResumesStoredStationAndCanReplaceIt() {
        RadioControlState state = new RadioControlState();
        state.apply("https://radio.example/first");
        state.apply("");

        assertTrue(state.apply("https://radio.example/first"));
        assertFalse(state.apply("https://radio.example/first"));
        assertEquals(3L, state.revision());
        assertTrue(state.apply("https://radio.example/second"));
        assertEquals("https://radio.example/second", state.storedUrl());
        assertEquals(4L, state.revision());
    }

    @Test
    void clearRemovesStationAndAdvancesRevision() {
        RadioControlState state = new RadioControlState();
        state.apply("https://radio.example/live");

        assertTrue(state.clear());
        assertFalse(state.clear());
        assertNull(state.storedUrl());
        assertTrue(state.station().isEmpty());
        assertEquals(2L, state.revision());

        CompoundTag saved = new CompoundTag();
        state.save(saved);
        assertFalse(saved.contains("Station"));
        assertFalse(saved.getBoolean("Enabled"));
        assertEquals(2L, saved.getLong("PlaybackRevision"));
    }

    @Test
    void rejectsMalformedAndFiniteStations() {
        CompoundTag malformed = new CompoundTag();
        malformed.putString("Station", "invalid");
        malformed.putBoolean("Enabled", true);
        malformed.putLong("PlaybackRevision", 3L);

        CompoundTag finite = new CompoundTag();
        finite.put("Station", AudioNbtCodec.write(new AudioProgram(AudioProgram.Kind.FINITE,
                List.of(new AudioTrack(AudioTrack.SourceType.REMOTE,
                        "https://audio.example/track", "", "")))));
        finite.putBoolean("Enabled", true);
        finite.putLong("PlaybackRevision", 4L);

        RadioControlState malformedState = new RadioControlState();
        RadioControlState finiteState = new RadioControlState();
        assertTrue(malformedState.load(malformed));
        assertTrue(finiteState.load(finite));
        assertTrue(malformedState.station().isEmpty());
        assertTrue(finiteState.station().isEmpty());
        assertFalse(malformedState.enabled());
        assertFalse(finiteState.enabled());
    }

    @Test
    void ignoresDuplicateAndStaleSnapshotsIncludingAcrossWrap() {
        RadioControlState state = new RadioControlState();
        assertTrue(state.load(snapshot(Long.MAX_VALUE, "https://radio.example/first", true)));
        assertFalse(state.load(snapshot(Long.MAX_VALUE, "https://radio.example/conflict", true)));
        assertFalse(state.load(snapshot(Long.MAX_VALUE - 1, "https://radio.example/stale", true)));
        assertTrue(state.load(snapshot(Long.MIN_VALUE, "https://radio.example/wrapped", true)));

        assertEquals("https://radio.example/wrapped", state.storedUrl());
        assertEquals(Long.MIN_VALUE, state.revision());
    }

    @Test
    void manualAndPoweredStopsRemainDistinct() {
        RadioControlState state = new RadioControlState();
        state.apply("https://radio.example/live");
        state.apply("");

        assertFalse(configuration(state, false).isEnabled());
        assertFalse(configuration(state, true).isEnabled());

        state.apply("https://radio.example/live");
        assertFalse(configuration(state, true).isEnabled());
        assertTrue(configuration(state, false).isEnabled());
    }

    private static RadioConfiguration configuration(RadioControlState state, boolean powered) {
        return new RadioConfiguration(state.revision(), state.station(), state.enabled(), powered);
    }

    private static CompoundTag snapshot(long revision, String url, boolean enabled) {
        RadioControlState state = new RadioControlState();
        state.apply(url);
        if (!enabled) {
            state.apply("");
        }
        CompoundTag tag = new CompoundTag();
        state.save(tag);
        tag.putLong("PlaybackRevision", revision);
        return tag;
    }
}
