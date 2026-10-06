package gg.moonflower.etched.common.item;

import gg.moonflower.etched.common.audio.AudioProgram;
import gg.moonflower.etched.common.audio.AudioTrack;
import gg.moonflower.etched.common.audio.RecordContent;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.contents.TranslatableContents;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

class RecordPresentationTest {

    @Test
    void explicitAlbumMetadataWinsWithoutPromotingAPlaybackTrackAndMarksAOneTrackAlbum() {
        var content = content("https://audio.example/track", true, 1);
        assertEquals("https://artist.bandcamp.com/album/test", RecordPresentation.source(content));
        var tooltip = RecordPresentation.tooltip(content);
        var name = (TranslatableContents) tooltip.get(0).getContents();
        assertEquals("sound_source.etched.info", name.getKey());
        assertEquals("Album artist", name.getArgs()[0]);
        assertEquals("Album title", ((Component) name.getArgs()[1]).getString());
        assertEquals(ChatFormatting.GRAY.getColor(), tooltip.get(0).getStyle().getColor().getValue());
        var brand = tooltip.get(1).getSiblings().get(0);
        assertEquals("sound_source.etched.bandcamp", ((TranslatableContents) brand.getContents()).getKey());
        assertEquals(0x477987, brand.getStyle().getColor().getValue());
        var marker = tooltip.get(1).getSiblings().get(2);
        assertEquals("item.etched.etched_music_disc.album", ((TranslatableContents) marker.getContents()).getKey());
        assertEquals(ChatFormatting.DARK_GRAY.getColor(), marker.getStyle().getColor().getValue());
        assertEquals(1, content.program().tracks().size());
        assertEquals("Track title", content.program().tracks().get(0).title());
    }

    @Test
    void firstTrackFallbackAndTrackCountMarkerRetainBrandedAndUnbrandedTooltips() {
        for (String source : List.of("https://audio.example/track", "minecraft:music_disc.cat", "https://soundcloud.com/a/track")) {
            for (int tracks : new int[]{1, 2}) {
                var content = content(source, false, tracks);
                var lines = RecordPresentation.tooltip(content);
                boolean branded = source.contains("soundcloud.com");
                assertEquals(source, RecordPresentation.source(content));
                assertEquals(tracks > 1 || branded ? 2 : 1, lines.size());
                var name = (TranslatableContents) lines.get(0).getContents();
                assertEquals("Track artist", name.getArgs()[0]);
                assertEquals("Track title", ((Component) name.getArgs()[1]).getString());
                if (branded) {
                    var brand = lines.get(1).getSiblings().get(0);
                    assertEquals("sound_source.etched.sound_cloud", ((TranslatableContents) brand.getContents()).getKey());
                    assertEquals(0xFF5500, brand.getStyle().getColor().getValue());
                }
                if (tracks > 1) {
                    var marker = branded ? lines.get(1).getSiblings().get(2) : lines.get(1);
                    assertEquals("item.etched.etched_music_disc.album", ((TranslatableContents) marker.getContents()).getKey());
                }
            }
        }
    }

    @Test
    void callersNeverShareMutableDisplayNamesBrandsOrAlbumLabels() {
        var content = content("minecraft:music_disc.cat", true, 1);
        var first = RecordPresentation.tooltip(content);
        var second = RecordPresentation.tooltip(content);
        assertEquals(first, second);
        assertNotSame(first.get(0), second.get(0));
        assertNotSame(first.get(1), second.get(1));
        ((net.minecraft.network.chat.MutableComponent) first.get(0)).append(" changed");
        ((net.minecraft.network.chat.MutableComponent) first.get(1).getSiblings().get(2)).append(" changed");
        assertEquals(second, RecordPresentation.tooltip(content));
        assertNotEquals(first, second);
    }

    private static RecordContent content(String source, boolean album, int count) {
        var type = source.startsWith("minecraft:") ? AudioTrack.SourceType.SOUND_EVENT : AudioTrack.SourceType.REMOTE;
        var track = new AudioTrack(type, source, "Track artist", "Track title");
        return new RecordContent(new AudioProgram(AudioProgram.Kind.FINITE, java.util.Collections.nCopies(count, track)),
                album ? Optional.of(new RecordContent.AlbumMetadata(AudioTrack.SourceType.REMOTE,
                        "https://artist.bandcamp.com/album/test", "Album artist", "Album title")) : Optional.empty());
    }
}
