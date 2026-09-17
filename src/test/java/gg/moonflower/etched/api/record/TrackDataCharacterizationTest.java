package gg.moonflower.etched.api.record;

import com.mojang.serialization.DataResult;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtOps;
import net.minecraft.network.chat.Component;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TrackDataCharacterizationTest {

    @Test
    void readsLegacyPlainTextTitleAndDefaultAuthor() {
        CompoundTag tag = new CompoundTag();
        tag.putString("Url", "minecraft:music_disc.13");
        tag.putString("Title", "Legacy title");

        TrackData track = parse(tag);

        assertEquals("minecraft:music_disc.13", track.url());
        assertEquals(TrackData.EMPTY.artist(), track.artist());
        assertEquals(Component.literal("Legacy title"), track.title());
    }

    @Test
    void savesAndLoadsStructuredTitleWithoutLosingMetadata() {
        TrackData expected = new TrackData(
                "https://audio.example/track.mp3",
                "Artist",
                Component.literal("Title").withStyle(style -> style.withItalic(true)));

        CompoundTag saved = expected.save(new CompoundTag());
        TrackData actual = parse(saved);

        assertEquals(expected, actual);
    }

    @Test
    void preservesLegacyLocalAndHttpUrlRules() {
        assertTrue(TrackData.isValidURL("minecraft:music_disc.13"));
        assertTrue(TrackData.isValidURL("custom_sound"));
        assertTrue(TrackData.isValidURL("http://audio.example/track.mp3"));
        assertTrue(TrackData.isValidURL("https://audio.example/track.ogg"));
        assertFalse(TrackData.isValidURL("ftp://audio.example/track.mp3"));
        assertFalse(TrackData.isValidURL("not a resource location"));
        assertFalse(TrackData.isValidURL(null));
    }

    private static TrackData parse(CompoundTag tag) {
        DataResult<TrackData> result = TrackData.CODEC.parse(NbtOps.INSTANCE, tag);
        return result.result().orElseThrow(() -> new AssertionError(result.error().orElseThrow().message()));
    }
}
