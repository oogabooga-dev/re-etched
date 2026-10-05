package gg.moonflower.etched.clientsmoke;

import gg.moonflower.etched.client.radio.sound.RecordSoundInstance;
import gg.moonflower.etched.core.mixin.client.LevelRendererAccessor;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.animal.Parrot;

/** Checks actual LevelRenderer wrappers without relying on the retired record API. */
final class VanillaRecordStopSmoke {

    private VanillaRecordStopSmoke() {
    }

    static void verify(Minecraft client) {
        BlockPos firstPos = client.player.blockPosition().offset(8, 0, 0);
        BlockPos secondPos = client.player.blockPosition().offset(16, 0, 0);
        Parrot first = EntityType.PARROT.create(client.level);
        Parrot second = EntityType.PARROT.create(client.level);
        if (first == null || second == null) {
            throw new AssertionError("Could not create vanilla record callback probes");
        }
        first.moveTo(firstPos.getX() + 0.5, firstPos.getY(), firstPos.getZ() + 0.5);
        second.moveTo(secondPos.getX() + 0.5, secondPos.getY(), secondPos.getZ() + 0.5);
        client.level.putNonPlayerEntity(first.getId(), first);
        client.level.putNonPlayerEntity(second.getId(), second);
        try {
            var records = ((LevelRendererAccessor) client.levelRenderer).getPlayingRecords();
            // A null disc uses vanilla's SoundEvent path, without the first-party manager adapter.
            client.levelRenderer.playStreamingMusic(SoundEvents.MUSIC_DISC_CAT, firstPos, null);
            client.levelRenderer.playStreamingMusic(SoundEvents.MUSIC_DISC_CAT, secondPos, null);
            if (!(records.get(firstPos) instanceof RecordSoundInstance firstSound)
                    || !(records.get(secondPos) instanceof RecordSoundInstance secondSound)
                    || !first.isPartyParrot() || !second.isPartyParrot()) {
                throw new AssertionError("Vanilla records did not install internal wrappers/nearby state");
            }
            firstSound.onStop();
            if (first.isPartyParrot() || !second.isPartyParrot()) {
                throw new AssertionError("A vanilla record callback used another sound's position");
            }
            client.levelRenderer.playStreamingMusic(SoundEvents.MUSIC_DISC_CAT, secondPos, null);
            if (records.get(secondPos) == secondSound || !second.isPartyParrot()) {
                throw new AssertionError("Vanilla replacement was not installed");
            }
            secondSound.onStop();
            if (!second.isPartyParrot()) {
                throw new AssertionError("A retired vanilla record callback cleared its replacement");
            }
            client.getSoundManager().stop();
            if (second.isPartyParrot()) {
                throw new AssertionError("SoundEngine did not remove the current vanilla record wrapper");
            }
            System.out.println("ETCHED VANILLA RECORD CALLBACK OWNERSHIP SMOKE PASSED");
        } finally {
            client.levelRenderer.playStreamingMusic(null, firstPos, null);
            client.levelRenderer.playStreamingMusic(null, secondPos, null);
            client.level.removeEntity(first.getId(), Entity.RemovalReason.DISCARDED);
            client.level.removeEntity(second.getId(), Entity.RemovalReason.DISCARDED);
        }
    }
}
