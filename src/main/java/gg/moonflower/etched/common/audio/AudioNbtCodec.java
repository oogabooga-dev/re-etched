package gg.moonflower.etched.common.audio;

import com.mojang.serialization.DataResult;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/** Versioned NBT encoding for the side-neutral audio model. */
public final class AudioNbtCodec {

    public static final int SCHEMA_VERSION = 1;
    public static final int MAX_DISCRIMINATOR_LENGTH = 32;

    private static final String SCHEMA_VERSION_KEY = "SchemaVersion";
    private static final String PROGRAM_KEY = "Program";
    private static final String KIND_KEY = "Kind";
    private static final String TRACKS_KEY = "Tracks";
    private static final String ALBUM_KEY = "AlbumMetadata";
    private static final String SOURCE_TYPE_KEY = "SourceType";
    private static final String SOURCE_KEY = "Source";
    private static final String ARTIST_KEY = "Artist";
    private static final String TITLE_KEY = "Title";

    private AudioNbtCodec() {
    }

    public static CompoundTag write(AudioProgram program) {
        CompoundTag root = new CompoundTag();
        root.putInt(SCHEMA_VERSION_KEY, SCHEMA_VERSION);
        root.put(PROGRAM_KEY, writeProgram(program));
        return root;
    }

    public static CompoundTag write(RecordContent content) {
        CompoundTag root = write(content.program());
        content.album().ifPresent(album -> root.put(ALBUM_KEY, writeAlbum(album)));
        return root;
    }

    public static DataResult<AudioProgram> readProgram(CompoundTag root) {
        DataResult<CompoundTag> envelope = readEnvelope(root);
        Optional<CompoundTag> programTag = envelope.result();
        if (programTag.isEmpty()) {
            return error(envelope.error().orElseThrow().message());
        }
        return readProgramBody(programTag.get());
    }

    public static DataResult<RecordContent> readRecordContent(CompoundTag root) {
        DataResult<AudioProgram> programResult = readProgram(root);
        Optional<AudioProgram> program = programResult.result();
        if (program.isEmpty()) {
            return error(programResult.error().orElseThrow().message());
        }

        if (!root.contains(ALBUM_KEY)) {
            return createRecordContent(program.get(), Optional.empty());
        }
        if (!(root.get(ALBUM_KEY) instanceof CompoundTag albumTag)) {
            return error("AlbumMetadata must be a compound");
        }
        DataResult<RecordContent.AlbumMetadata> albumResult = readAlbum(albumTag);
        Optional<RecordContent.AlbumMetadata> album = albumResult.result();
        if (album.isEmpty()) {
            return error(albumResult.error().orElseThrow().message());
        }
        return createRecordContent(program.get(), album);
    }

    private static CompoundTag writeProgram(AudioProgram program) {
        CompoundTag tag = new CompoundTag();
        tag.putString(KIND_KEY, program.kind().serializedName());
        ListTag tracks = new ListTag();
        for (AudioTrack track : program.tracks()) {
            tracks.add(writeTrack(track));
        }
        tag.put(TRACKS_KEY, tracks);
        return tag;
    }

    private static CompoundTag writeTrack(AudioTrack track) {
        return writeMetadata(track.sourceType(), track.source(), track.artist(), track.title());
    }

    private static CompoundTag writeAlbum(RecordContent.AlbumMetadata album) {
        return writeMetadata(album.sourceType(), album.source(), album.artist(), album.title());
    }

    private static CompoundTag writeMetadata(AudioTrack.SourceType sourceType, String source,
                                             String artist, String title) {
        CompoundTag tag = new CompoundTag();
        tag.putString(SOURCE_TYPE_KEY, sourceType.serializedName());
        tag.putString(SOURCE_KEY, source);
        if (!artist.isEmpty()) {
            tag.putString(ARTIST_KEY, artist);
        }
        if (!title.isEmpty()) {
            tag.putString(TITLE_KEY, title);
        }
        return tag;
    }

    private static DataResult<CompoundTag> readEnvelope(CompoundTag root) {
        if (!root.contains(SCHEMA_VERSION_KEY, Tag.TAG_INT)) {
            return error("Missing integer SchemaVersion");
        }
        int version = root.getInt(SCHEMA_VERSION_KEY);
        if (version != SCHEMA_VERSION) {
            return error(version > SCHEMA_VERSION
                    ? "Unsupported future audio schema version: " + version
                    : "Unsupported audio schema version: " + version);
        }
        if (!(root.get(PROGRAM_KEY) instanceof CompoundTag program)) {
            return error("Program must be a compound");
        }
        return DataResult.success(program);
    }

    private static DataResult<AudioProgram> readProgramBody(CompoundTag tag) {
        DataResult<String> kindName = readRequiredString(tag, KIND_KEY, MAX_DISCRIMINATOR_LENGTH);
        Optional<String> serializedKind = kindName.result();
        if (serializedKind.isEmpty()) {
            return error(kindName.error().orElseThrow().message());
        }
        AudioProgram.Kind kind = AudioProgram.Kind.bySerializedName(serializedKind.get());
        if (kind == null) {
            return error("Unknown audio program kind");
        }

        if (!(tag.get(TRACKS_KEY) instanceof ListTag tracks) || tracks.getElementType() != Tag.TAG_COMPOUND) {
            return error("Tracks must be a compound list");
        }
        if (tracks.isEmpty() || tracks.size() > AudioProgram.MAX_TRACKS) {
            return error("Tracks must contain between 1 and " + AudioProgram.MAX_TRACKS + " entries");
        }

        List<AudioTrack> decodedTracks = new ArrayList<>(tracks.size());
        for (int i = 0; i < tracks.size(); i++) {
            DataResult<AudioTrack> trackResult = readTrack(tracks.getCompound(i));
            Optional<AudioTrack> track = trackResult.result();
            if (track.isEmpty()) {
                return error("Invalid track " + i + ": " + trackResult.error().orElseThrow().message());
            }
            decodedTracks.add(track.get());
        }

        try {
            return DataResult.success(new AudioProgram(kind, decodedTracks));
        } catch (IllegalArgumentException exception) {
            return error(exception.getMessage());
        }
    }

    private static DataResult<AudioTrack> readTrack(CompoundTag tag) {
        DataResult<DecodedMetadata> metadata = readMetadata(tag);
        Optional<DecodedMetadata> value = metadata.result();
        if (value.isEmpty()) {
            return error(metadata.error().orElseThrow().message());
        }
        try {
            DecodedMetadata decoded = value.get();
            return DataResult.success(new AudioTrack(decoded.sourceType(), decoded.source(),
                    decoded.artist(), decoded.title()));
        } catch (IllegalArgumentException exception) {
            return error(exception.getMessage());
        }
    }

    private static DataResult<RecordContent.AlbumMetadata> readAlbum(CompoundTag tag) {
        DataResult<DecodedMetadata> metadata = readMetadata(tag);
        Optional<DecodedMetadata> value = metadata.result();
        if (value.isEmpty()) {
            return error(metadata.error().orElseThrow().message());
        }
        try {
            DecodedMetadata decoded = value.get();
            return DataResult.success(new RecordContent.AlbumMetadata(decoded.sourceType(), decoded.source(),
                    decoded.artist(), decoded.title()));
        } catch (IllegalArgumentException exception) {
            return error(exception.getMessage());
        }
    }

    private static DataResult<DecodedMetadata> readMetadata(CompoundTag tag) {
        DataResult<String> sourceTypeName = readRequiredString(
                tag, SOURCE_TYPE_KEY, MAX_DISCRIMINATOR_LENGTH);
        Optional<String> serializedSourceType = sourceTypeName.result();
        if (serializedSourceType.isEmpty()) {
            return error(sourceTypeName.error().orElseThrow().message());
        }
        AudioTrack.SourceType sourceType = AudioTrack.SourceType.bySerializedName(serializedSourceType.get());
        if (sourceType == null) {
            return error("Unknown audio source type");
        }

        DataResult<String> source = readRequiredString(tag, SOURCE_KEY, AudioTrack.MAX_REMOTE_SOURCE_LENGTH);
        if (source.result().isEmpty()) {
            return error(source.error().orElseThrow().message());
        }
        DataResult<String> artist = readOptionalString(tag, ARTIST_KEY);
        if (artist.result().isEmpty()) {
            return error(artist.error().orElseThrow().message());
        }
        DataResult<String> title = readOptionalString(tag, TITLE_KEY);
        if (title.result().isEmpty()) {
            return error(title.error().orElseThrow().message());
        }
        return DataResult.success(new DecodedMetadata(sourceType, source.result().orElseThrow(),
                artist.result().orElseThrow(), title.result().orElseThrow()));
    }

    private static DataResult<RecordContent> createRecordContent(AudioProgram program,
                                                                  Optional<RecordContent.AlbumMetadata> album) {
        try {
            return DataResult.success(new RecordContent(program, album));
        } catch (IllegalArgumentException exception) {
            return error(exception.getMessage());
        }
    }

    private static DataResult<String> readRequiredString(CompoundTag tag, String key, int maximumLength) {
        if (!tag.contains(key, Tag.TAG_STRING)) {
            return error("Missing string " + key);
        }
        String value = tag.getString(key);
        return value.length() <= maximumLength
                ? DataResult.success(value)
                : error(key + " exceeds " + maximumLength + " characters");
    }

    private static DataResult<String> readOptionalString(CompoundTag tag, String key) {
        if (!tag.contains(key)) {
            return DataResult.success("");
        }
        if (!tag.contains(key, Tag.TAG_STRING)) {
            return error(key + " must be a string");
        }
        String value = tag.getString(key);
        return value.length() <= AudioTrack.MAX_METADATA_LENGTH
                ? DataResult.success(value)
                : error(key + " exceeds " + AudioTrack.MAX_METADATA_LENGTH + " characters");
    }

    private static <T> DataResult<T> error(String message) {
        return DataResult.error(() -> message);
    }

    private record DecodedMetadata(AudioTrack.SourceType sourceType, String source, String artist, String title) {
    }
}
