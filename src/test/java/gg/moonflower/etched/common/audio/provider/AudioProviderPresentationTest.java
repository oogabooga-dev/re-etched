package gg.moonflower.etched.common.audio.provider;

import net.minecraft.network.chat.contents.TranslatableContents;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class AudioProviderPresentationTest {

    @Test
    void builtInBrandsRetainTranslationAndColorAndReturnIndependentComponents() {
        for (String source : new String[]{"https://artist.bandcamp.com/album/test", "http://soundcloud.com/artist/track"}) {
            boolean bandcamp = source.contains("bandcamp");
            var brand = AudioProviderPresentation.brand(source).orElseThrow();
            var second = AudioProviderPresentation.brand(source).orElseThrow();
            assertNotSame(brand, second);
            assertEquals("sound_source.etched." + (bandcamp ? "bandcamp" : "sound_cloud"),
                    assertInstanceOf(TranslatableContents.class, brand.getContents()).getKey());
            assertEquals(bandcamp ? 0x477987 : 0xFF5500, brand.getStyle().getColor().getValue());
            assertEquals(brand, second);
            brand.getSiblings().add(net.minecraft.network.chat.Component.literal("changed"));
            assertTrue(second.getSiblings().isEmpty());
            assertEquals(second, AudioProviderPresentation.brand(source).orElseThrow());
        }
    }

    @Test
    void invalidLocalDirectAndLookalikeSourcesHaveNoBrand() {
        for (String source : new String[]{null, "bad url", "minecraft:music_disc.13", "https://audio.example/music.mp3",
                "ftp://soundcloud.com/a", "https://user@soundcloud.com/a", "https://soundcloud.com.evil.example/a",
                "https://evilsoundcloud.com/a", "https://notbandcamp.com/a", "https://bandcamp.com.evil.example/a"}) {
            assertTrue(AudioProviderPresentation.brand(source).isEmpty(), source);
        }
    }
}
