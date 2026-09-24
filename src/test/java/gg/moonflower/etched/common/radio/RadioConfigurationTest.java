package gg.moonflower.etched.common.radio;

import gg.moonflower.etched.common.audio.AudioProgram;
import gg.moonflower.etched.common.audio.AudioTrack;
import gg.moonflower.etched.common.audio.PlaybackState;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RadioConfigurationTest {

    @Test
    void exposesStationUrlAndExplicitPlaybackState() {
        RadioConfiguration enabled = RadioConfiguration.forStation(
                12L, "https://radio.example/live", true, false);
        RadioConfiguration manuallyStopped = RadioConfiguration.forStation(
                13L, "https://radio.example/live", false, false);
        RadioConfiguration powered = RadioConfiguration.forStation(
                14L, "https://radio.example/live", true, true);
        RadioConfiguration empty = RadioConfiguration.empty(15L, false);

        assertEquals(12L, enabled.revision());
        assertEquals("https://radio.example/live", enabled.url());
        assertTrue(enabled.isConfigured());
        assertTrue(enabled.isEnabled());
        assertFalse(manuallyStopped.isEnabled());
        assertFalse(powered.isEnabled());
        assertFalse(empty.isConfigured());
        assertEquals("", empty.url());

        assertEquals(new PlaybackState(12L, enabled.station(), true), enabled.toPlaybackState());
        assertEquals(new PlaybackState(13L, manuallyStopped.station(), false),
                manuallyStopped.toPlaybackState());
        assertEquals(new PlaybackState(14L, powered.station(), false), powered.toPlaybackState());
        assertEquals(new PlaybackState(15L, Optional.empty(), false), empty.toPlaybackState());
    }

    @Test
    void rejectsInvalidRadioPrograms() {
        AudioProgram finite = new AudioProgram(AudioProgram.Kind.FINITE, List.of(
                new AudioTrack(AudioTrack.SourceType.REMOTE,
                        "https://audio.example/track", "", "")));

        assertThrows(IllegalArgumentException.class,
                () -> new RadioConfiguration(1L, Optional.of(finite), true, false));
        assertThrows(IllegalArgumentException.class,
                () -> new RadioConfiguration(1L, Optional.empty(), true, false));
    }
}
