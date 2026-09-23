package gg.moonflower.etched.common.audio;

import java.util.Objects;
import java.util.Optional;

/** Server-authoritative audio program and playback intent for one owner. */
public record PlaybackState(long revision, Optional<AudioProgram> program, boolean enabled) {

    public PlaybackState {
        program = Objects.requireNonNull(program, "program");
        if (enabled && program.isEmpty()) {
            throw new IllegalArgumentException("Enabled playback must contain an audio program");
        }
    }
}
