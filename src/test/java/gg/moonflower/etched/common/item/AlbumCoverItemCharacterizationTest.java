package gg.moonflower.etched.common.item;

import gg.moonflower.etched.api.record.TrackData;
import gg.moonflower.etched.client.radio.MinecraftTestBootstrap;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
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
    void aggregatesPlayableRecordsWithoutChangingTrackOrder() {
        TrackData first = track("first");
        TrackData second = track("second");
        TrackData third = track("third");
        TrackData[] tracks = AlbumCoverItem.flattenPrograms(List.of(
                new TrackData[]{first, second},
                new TrackData[0],
                new TrackData[]{third}));

        assertArrayEquals(new TrackData[]{first, second, third}, tracks);
    }

    @Test
    void aggregatesSerializedEtchedDiscProgramsWhileSkippingEmptyRecords() {
        TrackData album = track("album");
        TrackData first = track("first");
        TrackData second = track("second");
        TrackData third = track("third");

        ItemStack multiTrack = new ItemStack(Items.PAPER);
        EtchedMusicDiscItem.setMusic(multiTrack, album, first, second);
        ItemStack empty = new ItemStack(Items.PAPER);
        ItemStack malformed = new ItemStack(Items.PAPER);
        malformed.getOrCreateTag().put("Music", new CompoundTag());
        ItemStack singleTrack = new ItemStack(Items.PAPER);
        EtchedMusicDiscItem.setMusic(singleTrack, third);

        TrackData[] tracks = AlbumCoverItem.flattenPrograms(List.of(
                EtchedMusicDiscItem.readMusic(multiTrack).orElseThrow(),
                EtchedMusicDiscItem.readMusic(empty).orElseGet(() -> new TrackData[0]),
                EtchedMusicDiscItem.readMusic(malformed).orElseGet(() -> new TrackData[0]),
                EtchedMusicDiscItem.readMusic(singleTrack).orElseThrow()));

        assertArrayEquals(new TrackData[]{first, second, third}, tracks);
        assertEquals(2, EtchedMusicDiscItem.countTracks(multiTrack));
        assertEquals(0, EtchedMusicDiscItem.countTracks(empty));
        assertEquals(0, EtchedMusicDiscItem.countTracks(malformed));
        assertEquals(1, EtchedMusicDiscItem.countTracks(singleTrack));
        assertEquals(tracks.length,
                EtchedMusicDiscItem.countTracks(multiTrack) + EtchedMusicDiscItem.countTracks(singleTrack));
    }

    private static TrackData track(String name) {
        return new TrackData("https://audio.example/" + name + ".mp3", "Artist", Component.literal(name));
    }
}
