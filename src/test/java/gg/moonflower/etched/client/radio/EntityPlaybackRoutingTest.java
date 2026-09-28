package gg.moonflower.etched.client.radio;

import gg.moonflower.etched.common.audio.AudioProgram;
import gg.moonflower.etched.common.audio.AudioTrack;
import gg.moonflower.etched.common.audio.PlaybackState;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.Level;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertTrue;

class EntityPlaybackRoutingTest {

    static {
        MinecraftTestBootstrap.bootStrap();
    }

    @Test
    void entityOwnerSupportsLocalRemoteAndMixedFinitePrograms() {
        var dimension = ResourceKey.create(Registries.DIMENSION,
                ResourceLocation.fromNamespaceAndPath("etched_test", "boombox"));
        PlaybackOwnerKey.EntityOwner owner = PlaybackOwnerKey.entity(dimension, new UUID(0L, 1L));
        AudioTrack local = new AudioTrack(AudioTrack.SourceType.SOUND_EVENT,
                "minecraft:music_disc.cat", "Minecraft", "Cat");
        AudioTrack remote = new AudioTrack(AudioTrack.SourceType.REMOTE,
                "https://audio.example/track.mp3", "Artist", "Track");
        LocalSoundEventPlaybackBackend localBackend = new LocalSoundEventPlaybackBackend();
        FiniteRemotePlaybackBackend remoteBackend = new FiniteRemotePlaybackBackend();
        try {
            assertTrue(localBackend.supports(owner, state(List.of(local))));
            assertTrue(remoteBackend.supports(owner, state(List.of(remote))));
            assertTrue(remoteBackend.supports(owner, state(List.of(local, remote))));
            assertTrue(remoteBackend.supportsMixedFinite(owner, state(List.of(local, remote))));
        } finally {
            remoteBackend.shutdown();
            localBackend.shutdown();
        }
    }

    private static PlaybackState state(List<AudioTrack> tracks) {
        return new PlaybackState(0L, Optional.of(new AudioProgram(AudioProgram.Kind.FINITE, tracks)), true);
    }
}
