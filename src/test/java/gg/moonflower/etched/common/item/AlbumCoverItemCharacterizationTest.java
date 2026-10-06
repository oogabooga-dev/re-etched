package gg.moonflower.etched.common.item;

import gg.moonflower.etched.client.radio.MinecraftTestBootstrap;
import gg.moonflower.etched.common.audio.AudioProgram;
import gg.moonflower.etched.common.audio.AudioTrack;
import gg.moonflower.etched.common.audio.RecordContent;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.RecordItem;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AlbumCoverItemCharacterizationTest {

    static {
        MinecraftTestBootstrap.bootStrap();
    }

    @Test
    void recordsRoundTripInOrderAndAreCappedAtNine() {
        ItemStack albumCover = new ItemStack(Items.BUNDLE);
        List<ItemStack> records = new ArrayList<>();
        for (int i = 0; i < AlbumCoverItem.MAX_RECORDS + 1; i++) {
            ItemStack record = new ItemStack(Items.PAPER);
            record.getOrCreateTag().putInt("Order", i);
            records.add(record);
        }

        AlbumCoverItem.writeRecords(albumCover, records);

        List<ItemStack> restored = AlbumCoverItem.readRecords(albumCover);
        assertEquals(AlbumCoverItem.MAX_RECORDS, restored.size());
        for (int i = 0; i < restored.size(); i++) {
            assertEquals(i, restored.get(i).getOrCreateTag().getInt("Order"));
        }
    }

    @Test
    void emptyRecordCollectionClearsStoredContents() {
        ItemStack albumCover = new ItemStack(Items.BUNDLE);
        AlbumCoverItem.writeRecords(albumCover, List.of(new ItemStack(Items.PAPER)));
        assertTrue(albumCover.getOrCreateTag().contains("Records"));

        AlbumCoverItem.writeRecords(albumCover, List.of());

        assertFalse(albumCover.getOrCreateTag().contains("Records"));
        assertTrue(AlbumCoverItem.readRecords(albumCover).isEmpty());
    }

    @Test
    void coverRecordRoundTripsAndCanBeRemoved() {
        ItemStack albumCover = new ItemStack(Items.BUNDLE);
        ItemStack cover = new ItemStack(Items.MAP);
        cover.getOrCreateTag().putString("Marker", "cover");

        AlbumCoverItem.writeCover(albumCover, cover);
        assertEquals("cover", AlbumCoverItem.readCoverStack(albumCover).orElseThrow()
                .getOrCreateTag().getString("Marker"));

        AlbumCoverItem.writeCover(albumCover, ItemStack.EMPTY);
        assertTrue(AlbumCoverItem.readCoverStack(albumCover).isEmpty());
    }

    @Test
    void nestedRecordPersistenceKeepsTypedContentSeparateFromItsAlbumMetadata() {
        var content = new RecordContent(new AudioProgram(AudioProgram.Kind.FINITE, List.of(
                new AudioTrack(AudioTrack.SourceType.REMOTE, "https://audio.example/first", "Artist", "First"),
                new AudioTrack(AudioTrack.SourceType.REMOTE, "https://audio.example/second", "Guest", "Second"))),
                java.util.Optional.of(new RecordContent.AlbumMetadata(AudioTrack.SourceType.REMOTE,
                        "https://audio.example/album", "Artist", "Album")));
        ItemStack disc = new ItemStack(Items.PAPER);
        EtchedMusicDiscItem.setContent(disc, content);
        ItemStack container = new ItemStack(Items.BUNDLE);
        AlbumCoverItem.writeRecords(container, List.of(disc));
        var restored = AlbumCoverItem.readRecords(container).get(0);
        assertEquals(content, EtchedMusicDiscItem.readContent(restored).orElseThrow());
        disc.getTag().getCompound(EtchedMusicDiscItem.CONTENT_TAG).putInt("SchemaVersion", 999);
        assertEquals(content, EtchedMusicDiscItem.readContent(restored).orElseThrow());
    }

    @Test
    void vanillaDiscAdmissionAndManagedSoundRemainAvailableWithoutAnItemApiMixin() {
        RecordItem vanilla = (RecordItem) Items.MUSIC_DISC_CAT;
        assertTrue(VanillaRecordAdapter.isVanilla(vanilla));
        var program = RecordContentResolver.resolve(new ItemStack(vanilla)).orElseThrow().program();
        assertEquals(1, program.tracks().size());
        assertEquals(vanilla.getSound().getLocation().toString(), program.tracks().get(0).source());
    }
}
