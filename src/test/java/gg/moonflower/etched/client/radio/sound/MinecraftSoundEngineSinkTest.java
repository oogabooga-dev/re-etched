package gg.moonflower.etched.client.radio.sound;

import gg.moonflower.etched.client.radio.MinecraftTestBootstrap;
import gg.moonflower.etched.client.radio.PlaybackOwnerKey;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.Level;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MinecraftSoundEngineSinkTest {

    static {
        MinecraftTestBootstrap.bootStrap();
    }

    @Test
    void supportsOnlyBlockOwners() {
        ResourceKey<Level> dimension = ResourceKey.create(Registries.DIMENSION,
                ResourceLocation.fromNamespaceAndPath("etched_test", "sink"));
        MinecraftSoundEngineSink sink = new MinecraftSoundEngineSink();

        assertTrue(sink.supports(PlaybackOwnerKey.block(dimension, BlockPos.ZERO)));
        assertFalse(sink.supports(PlaybackOwnerKey.entity(dimension,
                UUID.fromString("e0053355-4076-4b5b-9b93-6425a501d538"))));
    }
}
