package gg.moonflower.etched.common.item;

import gg.moonflower.etched.client.radio.MinecraftTestBootstrap;
import gg.moonflower.etched.common.audio.AudioTrack;
import gg.moonflower.etched.common.audio.AudioProgram;
import gg.moonflower.etched.common.audio.RecordContent;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.RecordItem;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
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
    void albumAggregationKeepsOrderIgnoresEmptySlotsAndRejectsUnsupportedRecords() {
        var first = new ItemStack(Items.MUSIC_DISC_CAT);
        var second = new ItemStack(Items.MUSIC_DISC_BLOCKS);
        var content = RecordContentResolver.fromRecords(List.of(first, ItemStack.EMPTY, second)).orElseThrow();
        assertEquals(List.of("minecraft:music_disc.cat", "minecraft:music_disc.blocks"),
                content.program().tracks().stream().map(AudioTrack::source).toList());
        assertTrue(content.album().isEmpty());
        assertTrue(RecordContentResolver.fromRecords(List.of(first, new ItemStack(Items.PAPER), second)).isEmpty());
        assertTrue(RecordContentResolver.fromRecords(List.of(ItemStack.EMPTY)).isEmpty());
        assertTrue(RecordContentResolver.isPlayableDisc(first));
        assertFalse(RecordContentResolver.isPlayableDisc(new ItemStack(Items.PAPER)));
    }

    @Test
    void albumTrackLimitRejectsRatherThanTruncatingTheSequence() {
        var disc = new ItemStack(Items.MUSIC_DISC_CAT);
        assertEquals(AudioProgram.MAX_TRACKS, RecordContentResolver.fromRecords(
                java.util.Collections.nCopies(AudioProgram.MAX_TRACKS, disc)).orElseThrow().program().tracks().size());
        assertTrue(RecordContentResolver.fromRecords(java.util.Collections.nCopies(AudioProgram.MAX_TRACKS + 1, disc)).isEmpty());
    }

    @Test
    void versionedDiscTracksRetainOrderAndSourceTypes() {
        var expected = new RecordContent(new AudioProgram(AudioProgram.Kind.FINITE, List.of(
                new AudioTrack(AudioTrack.SourceType.REMOTE, "https://audio.example/first.mp3", "Artist", "First"),
                new AudioTrack(AudioTrack.SourceType.SOUND_EVENT, "minecraft:music_disc.cat", "Minecraft", "Cat"),
                new AudioTrack(AudioTrack.SourceType.REMOTE, "https://audio.example/last.ogg", "Artist", "Last"))));
        ItemStack disc = new ItemStack(Items.PAPER);
        EtchedMusicDiscItem.setContent(disc, expected);
        var content = RecordContentResolver.fromDisc(disc).orElseThrow();
        assertEquals(expected, content);
        assertEquals(3, content.program().tracks().size());
        assertEquals(AudioTrack.SourceType.REMOTE, content.program().tracks().get(0).sourceType());
        assertEquals(AudioTrack.SourceType.SOUND_EVENT, content.program().tracks().get(1).sourceType());
        assertEquals("https://audio.example/last.ogg", content.program().tracks().get(2).source());
    }

    @Test
    void malformedDiscContentRejectsTheWholeProgramRatherThanFilteringTracks() {
        ItemStack disc = new ItemStack(Items.PAPER);
        EtchedMusicDiscItem.setContent(disc, album());
        disc.getTag().getCompound(EtchedMusicDiscItem.CONTENT_TAG).getCompound("Program")
                .getList("Tracks", net.minecraft.nbt.Tag.TAG_COMPOUND).getCompound(1).putString("Artist", "x".repeat(129));
        assertTrue(RecordContentResolver.fromDisc(disc).isEmpty());
    }

    @Test
    void etchedAlbumMetadataDoesNotBecomeAnExtraAudioTrack() {
        ItemStack disc = new ItemStack(Items.PAPER);
        EtchedMusicDiscItem.setContent(disc, album());

        var content = RecordContentResolver.fromDisc(disc).orElseThrow();
        assertEquals(2, content.program().tracks().size());
        assertEquals("https://audio.example/album.mp3", content.album().orElseThrow().source());
    }

    private static RecordContent album() {
        return new RecordContent(new AudioProgram(AudioProgram.Kind.FINITE, List.of(
                new AudioTrack(AudioTrack.SourceType.REMOTE, "https://audio.example/one.mp3", "Artist", "One"),
                new AudioTrack(AudioTrack.SourceType.REMOTE, "https://audio.example/two.mp3", "Artist", "Two"))),
                java.util.Optional.of(new RecordContent.AlbumMetadata(AudioTrack.SourceType.REMOTE,
                        "https://audio.example/album.mp3", "Artist", "Album")));
    }
}
