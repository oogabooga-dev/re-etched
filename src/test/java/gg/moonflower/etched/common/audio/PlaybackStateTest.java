package gg.moonflower.etched.common.audio;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PlaybackStateTest {

    private static final AudioProgram PROGRAM = new AudioProgram(AudioProgram.Kind.FINITE, List.of(
            new AudioTrack(AudioTrack.SourceType.SOUND_EVENT, "minecraft:music_disc.13", "", "")));

    @Test
    void representsEmptyStoppedAndEnabledPlayback() {
        PlaybackState empty = new PlaybackState(Long.MIN_VALUE, Optional.empty(), false);
        PlaybackState stopped = new PlaybackState(2L, Optional.of(PROGRAM), false);
        PlaybackState enabled = new PlaybackState(Long.MAX_VALUE, Optional.of(PROGRAM), true);

        assertEquals(Long.MIN_VALUE, empty.revision());
        assertTrue(empty.program().isEmpty());
        assertFalse(stopped.enabled());
        assertTrue(enabled.enabled());
    }

    @Test
    void rejectsEnabledPlaybackWithoutAProgram() {
        assertThrows(IllegalArgumentException.class,
                () -> new PlaybackState(1L, Optional.empty(), true));
        assertThrows(NullPointerException.class,
                () -> new PlaybackState(1L, null, false));
    }
}
