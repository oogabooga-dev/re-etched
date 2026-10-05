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
import net.minecraft.world.phys.Vec3;
import org.junit.jupiter.api.Test;

import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MinecraftLocalSoundEventSinkTest {

    static {
        MinecraftTestBootstrap.bootStrap();
    }

    private static final ResourceKey<Level> DIMENSION = ResourceKey.create(Registries.DIMENSION,
            ResourceLocation.fromNamespaceAndPath("etched_test", "local_sound"));

    @Test
    void acceptsBlockAndEntityOwners() {
        MinecraftLocalSoundEventSink sink = new MinecraftLocalSoundEventSink();
        PlaybackOwnerKey entity = PlaybackOwnerKey.entity(DIMENSION, new UUID(0L, 1L));

        assertTrue(sink.supports(PlaybackOwnerKey.block(DIMENSION, BlockPos.ZERO)));
        assertTrue(sink.supports(entity));
    }

    @Test
    void nativeSoundEventReportsStopOnlyOnce() {
        PlaybackOwnerKey.BlockOwner owner = PlaybackOwnerKey.block(DIMENSION, BlockPos.ZERO);
        ResourceLocation event = ResourceLocation.parse("minecraft:music_disc.13");
        AtomicInteger stopped = new AtomicInteger();
        LocalSoundEventInstance sound = new LocalSoundEventInstance(owner, event,
                new PlaybackSession().start(event.toString()).cancellation(), stopped::incrementAndGet, false);

        PlaybackStopListener listener = sound;
        listener.onStop();
        listener.onStop();

        assertEquals(1, stopped.get());
        assertEquals(event, sound.getLocation());
        assertEquals(SoundSource.RECORDS, sound.getSource());
    }

    @Test
    void failedEngineStopCallbackIsIsolatedAndNotRepeated() {
        ResourceLocation event = ResourceLocation.parse("minecraft:music_disc.13");
        PlaybackSession.Attempt attempt = new PlaybackSession().start(event.toString());
        AtomicInteger stopped = new AtomicInteger();
        PlaybackStopListener listener = new LocalSoundEventInstance(
                PlaybackOwnerKey.block(DIMENSION, BlockPos.ZERO), event, attempt.cancellation(), () -> {
                    stopped.incrementAndGet();
                    throw new IllegalStateException("fixture stop callback failure");
                }, false);

        listener.onStop();
        listener.onStop();

        assertEquals(1, stopped.get());
        assertFalse(attempt.cancellation().isCancelled());
    }

    @Test
    void entitySoundFollowsPositionAndStopsWhenOwnerDisappears() {
        ResourceLocation event = ResourceLocation.parse("minecraft:music_disc.13");
        AtomicReference<Vec3> position = new AtomicReference<>(new Vec3(1, 2, 3));
        LocalSoundEventInstance sound = new LocalSoundEventInstance(
                PlaybackOwnerKey.entity(DIMENSION, new UUID(0L, 2L)), event,
                new PlaybackSession().start(event.toString()).cancellation(), () -> {
                }, position::get);

        assertEquals(1, sound.getX());
        position.set(new Vec3(4, 5, 6));
        sound.tick();
        assertEquals(4, sound.getX());
        assertEquals(5, sound.getY());
        assertEquals(6, sound.getZ());
        position.set(null);
        sound.tick();
        assertTrue(sound.isStopped());
    }
}
