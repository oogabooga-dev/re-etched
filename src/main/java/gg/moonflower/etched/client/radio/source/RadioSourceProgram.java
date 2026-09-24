package gg.moonflower.etched.client.radio.source;

import org.jetbrains.annotations.Nullable;

import java.net.URI;
import java.util.List;
import java.util.Objects;

/** Immutable station or ordered service-track description. */
public final class RadioSourceProgram {

    private final Kind kind;
    private final URI source;
    private final List<Track> tracks;

    public RadioSourceProgram(Kind kind, URI source, List<Track> tracks) {
        this.kind = Objects.requireNonNull(kind, "kind");
        this.source = Objects.requireNonNull(source, "source");
        this.tracks = List.copyOf(tracks);
        if (this.tracks.isEmpty()) {
            throw new IllegalArgumentException("A radio source program must contain at least one track");
        }
        if (kind == Kind.STATION && this.tracks.size() != 1) {
            throw new IllegalArgumentException("A station program must contain exactly one track");
        }
    }

    public Kind kind() {
        return this.kind;
    }

    public URI source() {
        return this.source;
    }

    public List<Track> tracks() {
        return this.tracks;
    }

    public RadioResolvedSource openTrack(int index, AudioResolveContext context)
            throws RadioSourceException {
        return this.tracks.get(index).open(context);
    }

    public enum Kind {
        STATION,
        SERVICE_TRACKS
    }

    public static final class Track {

        private final URI source;
        private final String title;
        private final Opener opener;

        public Track(URI source, @Nullable String title, Opener opener) {
            this.source = Objects.requireNonNull(source, "source");
            this.title = title;
            this.opener = Objects.requireNonNull(opener, "opener");
        }

        public URI source() {
            return this.source;
        }

        public @Nullable String title() {
            return this.title;
        }

        private RadioResolvedSource open(AudioResolveContext context) throws RadioSourceException {
            return this.opener.open(context);
        }
    }

    @FunctionalInterface
    public interface Opener {

        RadioResolvedSource open(AudioResolveContext context) throws RadioSourceException;
    }
}
