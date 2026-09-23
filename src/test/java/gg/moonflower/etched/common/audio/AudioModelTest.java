package gg.moonflower.etched.common.audio;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class AudioModelTest {

    @Test
    void representsLocalRemoteAndLiveAudio() {
        AudioTrack local = new AudioTrack(AudioTrack.SourceType.SOUND_EVENT,
                "minecraft:music_disc.13", "Minecraft", "C418 - 13");
        AudioTrack remote = new AudioTrack(AudioTrack.SourceType.REMOTE,
                "https://audio.example/track.mp3", "Artist", "Track");

        AudioProgram finite = new AudioProgram(AudioProgram.Kind.FINITE, List.of(local, remote));
        AudioProgram live = new AudioProgram(AudioProgram.Kind.LIVE, List.of(remote));
        RecordContent.AlbumMetadata album = new RecordContent.AlbumMetadata(
                AudioTrack.SourceType.REMOTE, "https://audio.example/album", "Artist", "Album");
        RecordContent content = new RecordContent(finite, Optional.of(album));

        assertEquals(List.of(local, remote), finite.tracks());
        assertEquals(AudioProgram.Kind.LIVE, live.kind());
        assertEquals(Optional.of(album), content.album());
    }

    @Test
    void defensivelyCopiesTrackLists() {
        AudioTrack track = localTrack();
        List<AudioTrack> input = new ArrayList<>(List.of(track));

        AudioProgram program = new AudioProgram(AudioProgram.Kind.FINITE, input);
        input.clear();

        assertEquals(List.of(track), program.tracks());
        assertThrows(UnsupportedOperationException.class, () -> program.tracks().clear());
    }

    @Test
    void canonicalizesDefaultSoundEventNamespace() {
        AudioTrack track = new AudioTrack(AudioTrack.SourceType.SOUND_EVENT,
                "music_disc.13", "", "");
        RecordContent.AlbumMetadata album = new RecordContent.AlbumMetadata(
                AudioTrack.SourceType.SOUND_EVENT, "music_disc.13", "", "");

        assertEquals("minecraft:music_disc.13", track.source());
        assertEquals("minecraft:music_disc.13", album.source());
    }

    @Test
    void appliesSoundEventLimitAfterCanonicalization() {
        int maximumPathLength = AudioTrack.MAX_SOUND_EVENT_LENGTH - "minecraft:".length();
        AudioTrack maximum = new AudioTrack(AudioTrack.SourceType.SOUND_EVENT,
                "a".repeat(maximumPathLength), "", "");

        assertEquals(AudioTrack.MAX_SOUND_EVENT_LENGTH, maximum.source().length());
        assertThrows(IllegalArgumentException.class, () -> new AudioTrack(
                AudioTrack.SourceType.SOUND_EVENT, "a".repeat(maximumPathLength + 1), "", ""));
    }

    @Test
    void enforcesSourceAndMetadataLimits() {
        String maximumRemote = remoteSource(AudioTrack.MAX_REMOTE_SOURCE_LENGTH);
        String maximumSound = "etched:" + "a".repeat(AudioTrack.MAX_SOUND_EVENT_LENGTH - 7);
        String maximumMetadata = "m".repeat(AudioTrack.MAX_METADATA_LENGTH);

        new AudioTrack(AudioTrack.SourceType.REMOTE, maximumRemote, maximumMetadata, maximumMetadata);
        new AudioTrack(AudioTrack.SourceType.SOUND_EVENT, maximumSound, maximumMetadata, maximumMetadata);

        assertThrows(IllegalArgumentException.class, () -> new AudioTrack(
                AudioTrack.SourceType.REMOTE, maximumRemote + "a", "", ""));
        assertThrows(IllegalArgumentException.class, () -> new AudioTrack(
                AudioTrack.SourceType.SOUND_EVENT, maximumSound + "a", "", ""));
        assertThrows(IllegalArgumentException.class, () -> new AudioTrack(
                AudioTrack.SourceType.REMOTE, "https://audio.example/track", maximumMetadata + "m", ""));
        assertThrows(IllegalArgumentException.class, () -> new AudioTrack(
                AudioTrack.SourceType.REMOTE, "ftp://audio.example/track", "", ""));
        assertThrows(IllegalArgumentException.class, () -> new AudioTrack(
                AudioTrack.SourceType.SOUND_EVENT, "Invalid Sound", "", ""));
    }

    @Test
    void enforcesTrackCountAndAggregateTextLimits() {
        List<AudioTrack> maximumCount = Collections.nCopies(AudioProgram.MAX_TRACKS, localTrack());
        new AudioProgram(AudioProgram.Kind.FINITE, maximumCount);

        assertThrows(IllegalArgumentException.class,
                () -> new AudioProgram(AudioProgram.Kind.FINITE, List.of()));
        assertThrows(IllegalArgumentException.class, () -> new AudioProgram(
                AudioProgram.Kind.FINITE,
                Collections.nCopies(AudioProgram.MAX_TRACKS + 1, localTrack())));

        AudioTrack maximumRemote = new AudioTrack(AudioTrack.SourceType.REMOTE,
                remoteSource(AudioTrack.MAX_REMOTE_SOURCE_LENGTH), "", "");
        AudioProgram maximumText = new AudioProgram(AudioProgram.Kind.FINITE,
                Collections.nCopies(8, maximumRemote));
        assertEquals(AudioProgram.MAX_TOTAL_TEXT_LENGTH, maximumText.textLength());
        assertThrows(IllegalArgumentException.class, () -> new AudioProgram(
                AudioProgram.Kind.FINITE, Collections.nCopies(9, maximumRemote)));

        RecordContent.AlbumMetadata album = new RecordContent.AlbumMetadata(
                AudioTrack.SourceType.SOUND_EVENT, "etched:album", "", "");
        assertThrows(IllegalArgumentException.class,
                () -> new RecordContent(maximumText, Optional.of(album)));
    }

    @Test
    void enforcesProgramKinds() {
        AudioTrack local = localTrack();
        AudioTrack remote = new AudioTrack(AudioTrack.SourceType.REMOTE,
                "https://radio.example/live", "", "");

        new AudioProgram(AudioProgram.Kind.LIVE, List.of(remote));

        assertThrows(IllegalArgumentException.class,
                () -> new AudioProgram(AudioProgram.Kind.LIVE, List.of(local)));
        assertThrows(IllegalArgumentException.class,
                () -> new AudioProgram(AudioProgram.Kind.LIVE, List.of(remote, remote)));
        assertThrows(IllegalArgumentException.class, () -> new RecordContent(
                new AudioProgram(AudioProgram.Kind.LIVE, List.of(remote))));
        assertThrows(NullPointerException.class, () -> new AudioProgram(
                AudioProgram.Kind.FINITE, Collections.singletonList(null)));
    }

    private static AudioTrack localTrack() {
        return new AudioTrack(AudioTrack.SourceType.SOUND_EVENT,
                "minecraft:music_disc.13", "Minecraft", "C418 - 13");
    }

    private static String remoteSource(int length) {
        String prefix = "https://audio.example/";
        return prefix + "a".repeat(length - prefix.length());
    }
}
