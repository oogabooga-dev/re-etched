package gg.moonflower.etched.client.radio.sound;

import gg.moonflower.etched.client.radio.MinecraftTestBootstrap;
import gg.moonflower.etched.client.radio.PlaybackOwnerKey;
import gg.moonflower.etched.client.radio.PlaybackSession;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.level.Level;
import org.junit.jupiter.api.Test;

import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MinecraftLocalSoundEventSinkTest {

    static {
        MinecraftTestBootstrap.bootStrap();
    }

    private static final ResourceKey<Level> DIMENSION = ResourceKey.create(Registries.DIMENSION,
            ResourceLocation.fromNamespaceAndPath("etched_test", "local_sound"));

    @Test
    void requiresBlockOwnerBeforeAccessingTheClientSoundManager() {
        MinecraftLocalSoundEventSink sink = new MinecraftLocalSoundEventSink();
        PlaybackOwnerKey entity = PlaybackOwnerKey.entity(DIMENSION, new UUID(0L, 1L));

        assertTrue(sink.supports(PlaybackOwnerKey.block(DIMENSION, BlockPos.ZERO)));
        assertFalse(sink.supports(entity));
        assertThrows(IllegalArgumentException.class, () -> sink.create(entity,
                ResourceLocation.parse("minecraft:music_disc.13"),
                new PlaybackSession().start("minecraft:music_disc.13").cancellation(), () -> {
                }));
    }

    @Test
    void nativeSoundEventReportsStopOnlyOnce() {
        PlaybackOwnerKey.BlockOwner owner = PlaybackOwnerKey.block(DIMENSION, BlockPos.ZERO);
        ResourceLocation event = ResourceLocation.parse("minecraft:music_disc.13");
        AtomicInteger stopped = new AtomicInteger();
        LocalSoundEventInstance sound = new LocalSoundEventInstance(owner, event,
                new PlaybackSession().start(event.toString()).cancellation(), stopped::incrementAndGet);

        sound.onStop();
        sound.onStop();

        assertEquals(1, stopped.get());
        assertEquals(event, sound.getLocation());
        assertEquals(SoundSource.RECORDS, sound.getSource());
    }
}
