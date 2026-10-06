package gg.moonflower.etched.common.menu;

import gg.moonflower.etched.common.audio.AudioProgram;
import gg.moonflower.etched.common.audio.AudioTrack;
import gg.moonflower.etched.common.audio.RecordContent;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

class EtchingMetadataTest {

    @Test
    void directAndLocalInputsHaveExplicitBoundedSourceTypes() {
        assertEquals(AudioTrack.SourceType.REMOTE, EtchingMetadata.sourceType("https://audio.example/a"));
        assertEquals(AudioTrack.SourceType.REMOTE, EtchingMetadata.sourceType("HTTP://audio.example/a"));
        assertEquals(AudioTrack.SourceType.SOUND_EVENT, EtchingMetadata.sourceType("minecraft:music_disc.blocks"));
        assertEquals(AudioTrack.SourceType.SOUND_EVENT, EtchingMetadata.sourceType("custom:music/track"));
        for (String input : new String[]{null, "", "bad input", "https:relative", "https://user@audio.example/a",
                "file:///private", "ftp://audio.example/a", "http:///missing-host", "http://audio.example:0/a",
                "http://audio.example:65536/a", "https://audio.example/" + "x".repeat(8192), "x".repeat(257)}) {
            assertNull(EtchingMetadata.sourceType(input), input);
        }
    }

    @Test
    void singleTrackLabelEditingUsesTypedContentAndNoAlbumSentinel() {
        var content = EtchingMetadata.direct("custom_sound", Optional.empty(), true, "Label artist", "Label title");
        assertTrue(content.album().isEmpty());
        assertEquals(List.of(new AudioTrack(AudioTrack.SourceType.SOUND_EVENT, "minecraft:custom_sound", "Label artist", "Label title")),
                content.program().tracks());
        var edited = EtchingMetadata.direct("minecraft:custom_sound", Optional.of(content), true, "New artist", "New title");
        assertEquals("New title", edited.program().tracks().get(0).title());
        assertEquals("Label title", content.program().tracks().get(0).title());
        assertThrows(IllegalArgumentException.class, () -> EtchingMetadata.direct("custom_sound", Optional.empty(),
                true, "x".repeat(129), "Title"));
    }

    @Test
    void unchangedLocalAlbumRetainsEveryTrackAndExplicitAlbumInsteadOfPromotingTheFirstTrack() {
        var content = album();
        var edited = EtchingMetadata.direct(EtchingMetadata.source(content), Optional.of(content), true, "Label", "Unused");
        assertEquals(content, edited);
        assertEquals(2, edited.program().tracks().size());
        assertEquals("Album", edited.album().orElseThrow().title());
        assertEquals("Second", edited.program().tracks().get(1).title());
    }

    @Test
    void aOneTrackAlbumRemainsAnExplicitAlbumWhenApplyingALabel() {
        var original = album();
        var content = new RecordContent(new AudioProgram(AudioProgram.Kind.FINITE,
                List.of(original.program().tracks().get(0))), original.album());
        assertEquals(content, EtchingMetadata.direct(EtchingMetadata.source(content), Optional.of(content),
                true, "Label", "Must not replace the album"));
    }

    @Test
    void changingTheSourceMakesASingleTrackWithoutReusingAnAlbumDescriptorAsAudio() {
        var changed = EtchingMetadata.direct("https://audio.example/new.mp3", Optional.of(album()), false, "", "");
        assertTrue(changed.album().isEmpty());
        assertEquals(List.of(new AudioTrack(AudioTrack.SourceType.REMOTE, "https://audio.example/new.mp3", "Unknown", "First")),
                changed.program().tracks());
    }

    @Test
    void unknownArtistFallbackIsIndependentAndIncludesExplicitAlbumMetadata() {
        var content = album();
        var edited = EtchingMetadata.fallbackArtist(content, "Label artist");
        assertEquals("Label artist", edited.program().tracks().get(0).artist());
        assertEquals("Guest", edited.program().tracks().get(1).artist());
        assertEquals("Label artist", edited.album().orElseThrow().artist());
        assertEquals("Unknown", content.album().orElseThrow().artist());
        assertEquals("Unknown", content.program().tracks().get(0).artist());
        assertThrows(IllegalArgumentException.class, () -> EtchingMetadata.fallbackArtist(content, "x".repeat(129)));
    }

    private static RecordContent album() {
        return new RecordContent(new AudioProgram(AudioProgram.Kind.FINITE, List.of(
                new AudioTrack(AudioTrack.SourceType.SOUND_EVENT, "minecraft:music_disc.blocks", "Unknown", "First"),
                new AudioTrack(AudioTrack.SourceType.SOUND_EVENT, "minecraft:music_disc.cat", "Guest", "Second"))),
                Optional.of(new RecordContent.AlbumMetadata(AudioTrack.SourceType.SOUND_EVENT,
                        "minecraft:music_disc.blocks", "Unknown", "Album")));
    }
}
