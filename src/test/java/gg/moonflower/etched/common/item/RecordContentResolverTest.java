package gg.moonflower.etched.common.item;

import gg.moonflower.etched.api.record.TrackData;
import gg.moonflower.etched.client.radio.MinecraftTestBootstrap;
import gg.moonflower.etched.common.audio.AudioTrack;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.RecordItem;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RecordContentResolverTest {

    static {
        MinecraftTestBootstrap.bootStrap();
    }

    @Test
    void vanillaDiscUsesLocalSoundEventWithoutNetwork() {
        var content = RecordContentResolver.resolve(new ItemStack(Items.MUSIC_DISC_13)).orElseThrow();
        assertEquals(1, content.program().tracks().size());
        assertEquals(AudioTrack.SourceType.SOUND_EVENT, content.program().tracks().get(0).sourceType());
        assertEquals(((RecordItem) Items.MUSIC_DISC_13).getSound().getLocation().toString(), content.program().tracks().get(0).source());
        assertTrue(RecordContentResolver.resolve(new ItemStack(Items.STONE)).isEmpty());
    }

    @Test
    void oldDiscTracksRetainOrderAndFilterInvalidSources() {
        var content = RecordContentResolver.fromTracks(new TrackData[]{
                track("https://audio.example/first.mp3"), track("minecraft:music_disc.cat"),
                track("file:///private/track.mp3"), track("https://audio.example/last.ogg")
        }).orElseThrow();
        assertEquals(3, content.program().tracks().size());
        assertEquals(AudioTrack.SourceType.REMOTE, content.program().tracks().get(0).sourceType());
        assertEquals(AudioTrack.SourceType.SOUND_EVENT, content.program().tracks().get(1).sourceType());
        assertEquals("https://audio.example/last.ogg", content.program().tracks().get(2).source());
    }

    @Test
    void oversizeMetadataDoesNotInvalidateOtherTracks() {
        var invalid = new TrackData("https://audio.example/invalid.mp3", "x".repeat(129), Component.literal("Invalid"));
        assertEquals(1, RecordContentResolver.fromTracks(new TrackData[]{invalid, track("https://audio.example/ok.mp3")})
                .orElseThrow().program().tracks().size());
        assertTrue(RecordContentResolver.fromTracks(new TrackData[]{invalid}).isEmpty());
    }

    @Test
    void etchedAlbumMetadataDoesNotBecomeAnExtraAudioTrack() {
        ItemStack disc = new ItemStack(Items.PAPER);
        EtchedMusicDiscItem.setMusic(disc, track("https://audio.example/album.mp3"),
                track("https://audio.example/one.mp3"), track("https://audio.example/two.mp3"));

        var content = RecordContentResolver.fromDisc(disc).orElseThrow();
        assertEquals(2, content.program().tracks().size());
        assertEquals("https://audio.example/album.mp3", content.album().orElseThrow().source());
    }

    private static TrackData track(String url) {
        return new TrackData(url, "Artist", Component.literal("Title"));
    }
}
