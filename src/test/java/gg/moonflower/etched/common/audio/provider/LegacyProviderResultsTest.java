package gg.moonflower.etched.common.audio.provider;

import gg.moonflower.etched.api.record.TrackData;
import gg.moonflower.etched.common.audio.AudioProgram;
import net.minecraft.network.chat.Component;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.URL;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class LegacyProviderResultsTest {

    private static final TrackData TRACK = new TrackData("https://audio.example/track", "Artist", Component.literal("Title"));

    @Test
    void snapshotsPreserveAlbumOrderAndDeeplySeparateMutableTitles() throws Exception {
        var title = Component.literal("Album").append(Component.literal(" child"));
        var album = new TrackData("https://provider.example/album", "Artist", title);
        var entries = new ArrayList<>(List.of(album, TRACK));
        var first = LegacyProviderResults.tracks(entries);
        var second = LegacyProviderResults.tracks(entries);
        assertArrayEquals(new TrackData[]{album, TRACK}, first);
        assertNotSame(first[0].title(), title);
        title.append(" changed");
        ((net.minecraft.network.chat.MutableComponent) title.getSiblings().get(0)).append(" nested change");
        entries.clear();
        assertEquals("Album child", first[0].title().getString());
        ((net.minecraft.network.chat.MutableComponent) first[0].title()).append(" consumer");
        assertEquals("Album child", second[0].title().getString());
    }

    @Test
    void metadataAllowsOneAlbumDescriptorAndRejectsMissingOrExcessiveEntries() throws Exception {
        assertEquals(AudioProgram.MAX_TRACKS + 1, LegacyProviderResults.tracks(
                Collections.nCopies(AudioProgram.MAX_TRACKS + 1, TRACK)).length);
        assertThrows(IOException.class, () -> LegacyProviderResults.tracks(null));
        assertThrows(IOException.class, () -> LegacyProviderResults.tracks(List.of()));
        assertThrows(IOException.class, () -> LegacyProviderResults.tracks(Arrays.asList(TRACK, null)));
        assertThrows(IOException.class, () -> LegacyProviderResults.tracks(Collections.nCopies(AudioProgram.MAX_TRACKS + 2, TRACK)));
    }

    @Test
    void metadataRejectsMissingFieldsAndOversizedArtistTitleOrSource() {
        String excessive = "x".repeat(LegacyProviderResults.MAX_FIELD_LENGTH + 1);
        for (TrackData track : new TrackData[]{new TrackData(null, "Artist", TRACK.title()),
                new TrackData(TRACK.url(), null, TRACK.title()), new TrackData(TRACK.url(), "Artist", null),
                TRACK.withArtist(excessive), TRACK.withTitle(excessive), TRACK.withUrl("https://audio.example/" + excessive)}) {
            assertThrows(IOException.class, () -> LegacyProviderResults.tracks(List.of(track)));
        }
    }

    @Test
    void trackSourcesAllowRealSoundEventsButNotCredentialsOpaqueOrUnsafeSchemes() throws Exception {
        assertEquals("minecraft:music_disc.cat", LegacyProviderResults.tracks(List.of(TRACK.withUrl("minecraft:music_disc.cat")))[0].url());
        for (String url : List.of("file:///tmp/music", "ftp://audio.example/music", "https:opaque", "https://u:p@audio.example/music",
                "https://audio.example:0/music", "https://audio.example:99999/music", "", "minecraft:")) {
            assertThrows(IOException.class, () -> LegacyProviderResults.tracks(List.of(TRACK.withUrl(url))), url);
        }
    }

    @Test
    void mediaDestinationsAreBoundedHttpOnlyIndependentSnapshots() throws Exception {
        URL url = new URL("https://audio.example/media");
        var entries = new ArrayList<>(List.of(url, url));
        var copy = LegacyProviderResults.urls(entries);
        entries.clear();
        assertEquals(List.of(url, url), copy);
        assertThrows(UnsupportedOperationException.class, () -> copy.add(url));
        assertEquals(AudioProgram.MAX_TRACKS, LegacyProviderResults.urls(Collections.nCopies(AudioProgram.MAX_TRACKS, url)).size());
        assertThrows(IOException.class, () -> LegacyProviderResults.urls(null));
        assertThrows(IOException.class, () -> LegacyProviderResults.urls(List.of()));
        assertThrows(IOException.class, () -> LegacyProviderResults.urls(Arrays.asList(url, null)));
        assertThrows(IOException.class, () -> LegacyProviderResults.urls(Collections.nCopies(AudioProgram.MAX_TRACKS + 1, url)));
        for (String invalid : List.of("file:///tmp/music", "ftp://audio.example/media", "https://u:p@audio.example/media")) {
            assertThrows(IOException.class, () -> LegacyProviderResults.urls(List.of(new URL(invalid))));
        }
    }
}
