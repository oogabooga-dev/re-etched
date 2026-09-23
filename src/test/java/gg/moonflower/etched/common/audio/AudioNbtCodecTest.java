package gg.moonflower.etched.common.audio;

import com.mojang.serialization.DataResult;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AudioNbtCodecTest {

    @Test
    void roundTripsFiniteRecordContent() {
        AudioTrack local = new AudioTrack(AudioTrack.SourceType.SOUND_EVENT,
                "minecraft:music_disc.13", "Minecraft", "C418 - 13");
        AudioTrack remote = new AudioTrack(AudioTrack.SourceType.REMOTE,
                "https://audio.example/track.mp3", "Artist", "Track");
        RecordContent expected = new RecordContent(
                new AudioProgram(AudioProgram.Kind.FINITE, List.of(local, remote)),
                Optional.of(new RecordContent.AlbumMetadata(AudioTrack.SourceType.REMOTE,
                        "https://audio.example/album", "Artist", "Album")));

        RecordContent actual = success(AudioNbtCodec.readRecordContent(AudioNbtCodec.write(expected)));

        assertEquals(expected, actual);
    }

    @Test
    void roundTripsLiveProgram() {
        AudioProgram expected = new AudioProgram(AudioProgram.Kind.LIVE, List.of(
                new AudioTrack(AudioTrack.SourceType.REMOTE,
                        "https://radio.example/live", "", "")));

        AudioProgram actual = success(AudioNbtCodec.readProgram(AudioNbtCodec.write(expected)));

        assertEquals(expected, actual);
    }

    @Test
    void roundTripsCanonicalSoundEventAtTheLengthLimit() {
        int maximumPathLength = AudioTrack.MAX_SOUND_EVENT_LENGTH - "minecraft:".length();
        AudioProgram expected = new AudioProgram(AudioProgram.Kind.FINITE, List.of(
                new AudioTrack(AudioTrack.SourceType.SOUND_EVENT,
                        "a".repeat(maximumPathLength), "", "")));

        AudioProgram actual = success(AudioNbtCodec.readProgram(AudioNbtCodec.write(expected)));

        assertEquals(expected, actual);
        assertEquals(AudioTrack.MAX_SOUND_EVENT_LENGTH, actual.tracks().get(0).source().length());
    }

    @Test
    void writesExplicitVersionAndOmitsEmptyMetadata() {
        AudioProgram program = new AudioProgram(AudioProgram.Kind.FINITE, List.of(
                new AudioTrack(AudioTrack.SourceType.REMOTE,
                        "https://audio.example/track", "", "")));

        CompoundTag root = AudioNbtCodec.write(new RecordContent(program));
        CompoundTag programTag = root.getCompound("Program");
        CompoundTag track = programTag.getList("Tracks", CompoundTag.TAG_COMPOUND).getCompound(0);

        assertEquals(AudioNbtCodec.SCHEMA_VERSION, root.getInt("SchemaVersion"));
        assertEquals("finite", programTag.getString("Kind"));
        assertEquals("remote", track.getString("SourceType"));
        assertFalse(track.contains("Artist"));
        assertFalse(track.contains("Title"));
        assertFalse(root.contains("AlbumMetadata"));
    }

    @Test
    void rejectsMissingWrongAndFutureVersions() {
        CompoundTag missing = new CompoundTag();
        CompoundTag wrongType = validProgramTag();
        wrongType.putString("SchemaVersion", "1");
        CompoundTag old = validProgramTag();
        old.putInt("SchemaVersion", 0);
        CompoundTag future = validProgramTag();
        future.putInt("SchemaVersion", AudioNbtCodec.SCHEMA_VERSION + 1);

        assertError(AudioNbtCodec.readProgram(missing));
        assertError(AudioNbtCodec.readProgram(wrongType));
        assertError(AudioNbtCodec.readProgram(old));
        assertTrue(error(AudioNbtCodec.readProgram(future)).contains("future"));
    }

    @Test
    void rejectsMalformedProgramsWithoutSkippingTracks() {
        CompoundTag unknownKind = validProgramTag();
        unknownKind.getCompound("Program").putString("Kind", "playlist");

        CompoundTag wrongTracksType = validProgramTag();
        wrongTracksType.getCompound("Program").putString("Tracks", "not a list");

        AudioTrack track = new AudioTrack(AudioTrack.SourceType.REMOTE,
                "https://audio.example/track", "", "");
        CompoundTag malformedMiddle = AudioNbtCodec.write(new AudioProgram(
                AudioProgram.Kind.FINITE, List.of(track, track, track)));
        malformedMiddle.getCompound("Program").getList("Tracks", CompoundTag.TAG_COMPOUND)
                .getCompound(1).putString("Source", "ftp://audio.example/track");

        CompoundTag unknownSourceType = validProgramTag();
        firstTrack(unknownSourceType).putString("SourceType", "file");

        CompoundTag oversizedKind = validProgramTag();
        oversizedKind.getCompound("Program").putString("Kind",
                "k".repeat(AudioNbtCodec.MAX_DISCRIMINATOR_LENGTH + 1));

        CompoundTag oversizedSourceType = validProgramTag();
        firstTrack(oversizedSourceType).putString("SourceType",
                "s".repeat(AudioNbtCodec.MAX_DISCRIMINATOR_LENGTH + 1));

        assertError(AudioNbtCodec.readProgram(unknownKind));
        assertError(AudioNbtCodec.readProgram(wrongTracksType));
        assertTrue(error(AudioNbtCodec.readProgram(malformedMiddle)).contains("track 1"));
        assertError(AudioNbtCodec.readProgram(unknownSourceType));
        assertError(AudioNbtCodec.readProgram(oversizedKind));
        assertError(AudioNbtCodec.readProgram(oversizedSourceType));
    }

    @Test
    void rejectsOversizedFieldsAndCollections() {
        CompoundTag oversizedSource = validProgramTag();
        firstTrack(oversizedSource).putString("Source",
                "https://audio.example/" + "a".repeat(AudioTrack.MAX_REMOTE_SOURCE_LENGTH));

        CompoundTag oversizedMetadata = validProgramTag();
        firstTrack(oversizedMetadata).putString("Artist", "a".repeat(AudioTrack.MAX_METADATA_LENGTH + 1));

        CompoundTag oversizedList = validProgramTag();
        ListTag tracks = oversizedList.getCompound("Program").getList("Tracks", CompoundTag.TAG_COMPOUND);
        CompoundTag track = firstTrack(oversizedList).copy();
        for (int i = tracks.size(); i <= AudioProgram.MAX_TRACKS; i++) {
            tracks.add(track.copy());
        }

        assertError(AudioNbtCodec.readProgram(oversizedSource));
        assertError(AudioNbtCodec.readProgram(oversizedMetadata));
        assertError(AudioNbtCodec.readProgram(oversizedList));
    }

    @Test
    void rejectsInvalidAlbumAndLiveRecordContent() {
        CompoundTag invalidAlbum = validProgramTag();
        invalidAlbum.putString("AlbumMetadata", "not a compound");

        RecordContent content = new RecordContent(
                success(AudioNbtCodec.readProgram(validProgramTag())),
                Optional.of(new RecordContent.AlbumMetadata(AudioTrack.SourceType.REMOTE,
                        "https://audio.example/album", "Artist", "Album")));
        CompoundTag oversizedAlbum = AudioNbtCodec.write(content);
        oversizedAlbum.getCompound("AlbumMetadata").putString(
                "Title", "a".repeat(AudioTrack.MAX_METADATA_LENGTH + 1));

        AudioProgram live = new AudioProgram(AudioProgram.Kind.LIVE, List.of(
                new AudioTrack(AudioTrack.SourceType.REMOTE,
                        "https://radio.example/live", "", "")));

        assertError(AudioNbtCodec.readRecordContent(invalidAlbum));
        assertError(AudioNbtCodec.readRecordContent(oversizedAlbum));
        assertError(AudioNbtCodec.readRecordContent(AudioNbtCodec.write(live)));
    }

    @Test
    void enforcesAggregateAndLiveInvariantsWhileDecoding() {
        CompoundTag aggregateOverflow = validProgramTag();
        ListTag aggregateTracks = aggregateOverflow.getCompound("Program")
                .getList("Tracks", CompoundTag.TAG_COMPOUND);
        CompoundTag maximumRemote = firstTrack(aggregateOverflow).copy();
        String prefix = "https://audio.example/";
        maximumRemote.putString("Source",
                prefix + "a".repeat(AudioTrack.MAX_REMOTE_SOURCE_LENGTH - prefix.length()));
        maximumRemote.remove("Artist");
        maximumRemote.remove("Title");
        aggregateTracks.clear();
        for (int i = 0; i < 9; i++) {
            aggregateTracks.add(maximumRemote.copy());
        }

        CompoundTag localLive = validProgramTag();
        localLive.getCompound("Program").putString("Kind", "live");
        firstTrack(localLive).putString("SourceType", "sound_event");
        firstTrack(localLive).putString("Source", "minecraft:music_disc.13");

        CompoundTag multiTrackLive = validProgramTag();
        multiTrackLive.getCompound("Program").putString("Kind", "live");
        ListTag liveTracks = multiTrackLive.getCompound("Program")
                .getList("Tracks", CompoundTag.TAG_COMPOUND);
        liveTracks.add(firstTrack(multiTrackLive).copy());

        assertError(AudioNbtCodec.readProgram(aggregateOverflow));
        assertError(AudioNbtCodec.readProgram(localLive));
        assertError(AudioNbtCodec.readProgram(multiTrackLive));
    }

    @Test
    void toleratesUnknownCurrentFieldsButRejectsLegacyOnlyData() {
        CompoundTag current = validProgramTag();
        current.putString("FutureOptionalField", "ignored");
        current.getCompound("Program").putInt("AnotherField", 42);

        CompoundTag legacy = new CompoundTag();
        legacy.putString("Music", "https://audio.example/track");
        legacy.putString("Album", "https://audio.example/album");
        legacy.putString("Url", "https://radio.example/live");
        CompoundTag snapshot = legacy.copy();

        success(AudioNbtCodec.readProgram(current));
        assertError(AudioNbtCodec.readRecordContent(legacy));
        assertEquals(snapshot, legacy);
    }

    private static CompoundTag validProgramTag() {
        return AudioNbtCodec.write(new AudioProgram(AudioProgram.Kind.FINITE, List.of(
                new AudioTrack(AudioTrack.SourceType.REMOTE,
                        "https://audio.example/track", "Artist", "Title"))));
    }

    private static CompoundTag firstTrack(CompoundTag root) {
        return root.getCompound("Program").getList("Tracks", CompoundTag.TAG_COMPOUND).getCompound(0);
    }

    private static <T> T success(DataResult<T> result) {
        return result.result().orElseThrow(() -> new AssertionError(error(result)));
    }

    private static void assertError(DataResult<?> result) {
        assertTrue(result.result().isEmpty());
        assertTrue(result.error().isPresent());
    }

    private static String error(DataResult<?> result) {
        return result.error().orElseThrow().message();
    }
}
