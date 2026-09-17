package gg.moonflower.etched.common.item;

import gg.moonflower.etched.api.record.TrackData;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class EtchedMusicDiscItemCharacterizationTest {

    private static final TrackData ALBUM = track("album");
    private static final TrackData FIRST = track("first");
    private static final TrackData SECOND = track("second");

    @Test
    void singleTrackRoundTripsAsMusicAndAlbumFallback() {
        ItemStack stack = new ItemStack(Items.PAPER);

        EtchedMusicDiscItem.setMusic(stack, FIRST);

        assertArrayEquals(new TrackData[]{FIRST}, EtchedMusicDiscItem.readMusic(stack).orElseThrow());
        assertEquals(FIRST, EtchedMusicDiscItem.readAlbum(stack).orElseThrow());
        assertEquals(1, EtchedMusicDiscItem.countTracks(stack));
        assertFalse(stack.getOrCreateTag().contains("Album"));
    }

    @Test
    void albumMetadataIsSeparateAndTrackOrderSurvivesInvalidEntries() {
        ItemStack stack = new ItemStack(Items.PAPER);
        EtchedMusicDiscItem.setMusic(stack, ALBUM, FIRST, SECOND);
        ListTag music = stack.getOrCreateTag().getList("Music", Tag.TAG_COMPOUND);
        music.add(1, new CompoundTag());

        assertArrayEquals(new TrackData[]{FIRST, SECOND}, EtchedMusicDiscItem.readMusic(stack).orElseThrow());
        assertEquals(ALBUM, EtchedMusicDiscItem.readAlbum(stack).orElseThrow());
        assertEquals(2, EtchedMusicDiscItem.countTracks(stack));
    }

    @Test
    void clearingMusicRemovesSingleAndAlbumFormats() {
        ItemStack stack = new ItemStack(Items.PAPER);
        EtchedMusicDiscItem.setMusic(stack, ALBUM, FIRST, SECOND);

        EtchedMusicDiscItem.setMusic(stack);

        assertTrue(EtchedMusicDiscItem.readMusic(stack).isEmpty());
        assertTrue(EtchedMusicDiscItem.readAlbum(stack).isEmpty());
        assertEquals(0, EtchedMusicDiscItem.countTracks(stack));
        assertFalse(stack.getOrCreateTag().contains("Music"));
        assertFalse(stack.getOrCreateTag().contains("Album"));
    }

    @Test
    void readingLegacyColorsMigratesThemToCurrentKeys() {
        ItemStack stack = new ItemStack(Items.PAPER);
        stack.getOrCreateTag().putInt("PrimaryColor", 0x123456);
        stack.getOrCreateTag().putInt("SecondaryColor", 0xABCDEF);

        assertEquals(0x123456, EtchedMusicDiscItem.getDiscColor(stack));
        assertEquals(0xABCDEF, EtchedMusicDiscItem.getLabelPrimaryColor(stack));
        assertEquals(0xABCDEF, EtchedMusicDiscItem.getLabelSecondaryColor(stack));
        assertFalse(stack.getOrCreateTag().contains("PrimaryColor"));
        assertFalse(stack.getOrCreateTag().contains("SecondaryColor"));
        assertEquals(0x123456, stack.getOrCreateTag().getInt("DiscColor"));
    }

    @Test
    void invalidPatternFallsBackToFlat() {
        ItemStack stack = new ItemStack(Items.PAPER);
        stack.getOrCreateTag().putByte("Pattern", (byte) 127);

        assertEquals(EtchedMusicDiscItem.LabelPattern.FLAT, EtchedMusicDiscItem.getPattern(stack));
    }

    private static TrackData track(String name) {
        return new TrackData("https://audio.example/" + name + ".mp3", "Artist", Component.literal(name));
    }
}
