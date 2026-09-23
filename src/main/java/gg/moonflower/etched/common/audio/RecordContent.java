package gg.moonflower.etched.common.audio;

import java.util.Objects;
import java.util.Optional;

/** A finite record program and optional album-level metadata. */
public record RecordContent(AudioProgram program, Optional<AlbumMetadata> album) {

    public RecordContent {
        Objects.requireNonNull(program, "program");
        album = Objects.requireNonNull(album, "album");
        if (program.kind() != AudioProgram.Kind.FINITE) {
            throw new IllegalArgumentException("Record content must contain a finite program");
        }
        if (album.map(AlbumMetadata::textLength).orElse(0) + program.textLength()
                > AudioProgram.MAX_TOTAL_TEXT_LENGTH) {
            throw new IllegalArgumentException("Record content text exceeds "
                    + AudioProgram.MAX_TOTAL_TEXT_LENGTH + " characters");
        }
    }

    public RecordContent(AudioProgram program) {
        this(program, Optional.empty());
    }

    public record AlbumMetadata(AudioTrack.SourceType sourceType, String source, String artist, String title) {

        public AlbumMetadata {
            Objects.requireNonNull(sourceType, "sourceType");
            Objects.requireNonNull(source, "source");
            Objects.requireNonNull(artist, "artist");
            Objects.requireNonNull(title, "title");

            source = AudioTrack.normalizeSource(sourceType, source);
            AudioTrack.requireLength(artist, AudioTrack.MAX_METADATA_LENGTH, "album artist");
            AudioTrack.requireLength(title, AudioTrack.MAX_METADATA_LENGTH, "album title");
        }

        int textLength() {
            return this.source.length() + this.artist.length() + this.title.length();
        }
    }
}
