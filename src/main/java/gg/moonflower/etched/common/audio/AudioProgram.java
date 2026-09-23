package gg.moonflower.etched.common.audio;

import java.util.List;
import java.util.Objects;

/** An ordered finite program or one live station. */
public record AudioProgram(Kind kind, List<AudioTrack> tracks) {

    public static final int MAX_TRACKS = 100;
    public static final int MAX_TOTAL_TEXT_LENGTH = 65_536;

    public AudioProgram {
        Objects.requireNonNull(kind, "kind");
        tracks = List.copyOf(Objects.requireNonNull(tracks, "tracks"));
        if (tracks.isEmpty() || tracks.size() > MAX_TRACKS) {
            throw new IllegalArgumentException("Audio program must contain between 1 and "
                    + MAX_TRACKS + " tracks");
        }
        if (kind == Kind.LIVE
                && (tracks.size() != 1 || tracks.get(0).sourceType() != AudioTrack.SourceType.REMOTE)) {
            throw new IllegalArgumentException("A live program must contain exactly one remote track");
        }

        long textLength = 0;
        for (AudioTrack track : tracks) {
            textLength += track.textLength();
        }
        if (textLength > MAX_TOTAL_TEXT_LENGTH) {
            throw new IllegalArgumentException("Audio program text exceeds "
                    + MAX_TOTAL_TEXT_LENGTH + " characters");
        }
    }

    int textLength() {
        int textLength = 0;
        for (AudioTrack track : this.tracks) {
            textLength += track.textLength();
        }
        return textLength;
    }

    public enum Kind {
        FINITE("finite"),
        LIVE("live");

        private final String serializedName;

        Kind(String serializedName) {
            this.serializedName = serializedName;
        }

        public String serializedName() {
            return this.serializedName;
        }

        static Kind bySerializedName(String name) {
            for (Kind value : values()) {
                if (value.serializedName.equals(name)) {
                    return value;
                }
            }
            return null;
        }
    }
}
