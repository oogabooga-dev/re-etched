package gg.moonflower.etched.common.radio;

import gg.moonflower.etched.common.audio.AudioProgram;
import gg.moonflower.etched.common.audio.AudioTrack;

import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * Immutable radio state synchronized by the block entity.
 */
public record RadioConfiguration(long revision, Optional<AudioProgram> station,
                                 boolean manuallyEnabled, boolean powered) {

    public RadioConfiguration {
        station = Objects.requireNonNull(station, "station");
        if (station.isPresent() && station.get().kind() != AudioProgram.Kind.LIVE) {
            throw new IllegalArgumentException("A radio station must be a live audio program");
        }
        if (manuallyEnabled && station.isEmpty()) {
            throw new IllegalArgumentException("An enabled radio must have a station");
        }
    }

    public static RadioConfiguration forStation(long revision, String url,
                                                 boolean manuallyEnabled, boolean powered) {
        AudioProgram station = new AudioProgram(AudioProgram.Kind.LIVE, List.of(
                new AudioTrack(AudioTrack.SourceType.REMOTE, url, "", "")));
        return new RadioConfiguration(revision, Optional.of(station), manuallyEnabled, powered);
    }

    public static RadioConfiguration empty(long revision, boolean powered) {
        return new RadioConfiguration(revision, Optional.empty(), false, powered);
    }

    public String url() {
        return this.station.map(program -> program.tracks().get(0).source()).orElse("");
    }

    public boolean isConfigured() {
        return this.station.isPresent();
    }

    public boolean isEnabled() {
        return this.manuallyEnabled && !this.powered;
    }
}
