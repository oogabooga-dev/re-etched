package gg.moonflower.etched.client.radio.sound;

import gg.moonflower.etched.common.audio.AudioCancellation;
import gg.moonflower.etched.client.radio.PlaybackOwnerKey;
import net.minecraft.client.Minecraft;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.tags.BlockTags;

/** Block- or entity-positioned vanilla SoundManager output for local sound events. */
public final class MinecraftLocalSoundEventSink implements LocalSoundEventSink {

    @Override
    public boolean supports(PlaybackOwnerKey key) {
        return key instanceof PlaybackOwnerKey.BlockOwner || key instanceof PlaybackOwnerKey.EntityOwner;
    }

    @Override
    public Handle create(PlaybackOwnerKey key, ResourceLocation event, AudioCancellation cancellation,
                         Runnable soundStopped) {
        Minecraft minecraft = Minecraft.getInstance();
        LocalSoundEventInstance sound;
        if (key instanceof PlaybackOwnerKey.BlockOwner blockOwner) {
            if (minecraft.level == null || !minecraft.level.dimension().equals(blockOwner.dimension())) {
                throw new IllegalStateException("Sound event playback owner is unavailable");
            }
            boolean muffled = minecraft.level.getBlockState(blockOwner.pos().above()).is(BlockTags.WOOL);
            sound = new LocalSoundEventInstance(blockOwner, event, cancellation, soundStopped, muffled);
        } else if (key instanceof PlaybackOwnerKey.EntityOwner entityOwner) {
            sound = new LocalSoundEventInstance(entityOwner, event, cancellation, soundStopped,
                    EntitySoundPosition.find(entityOwner));
        } else {
            throw new IllegalArgumentException("Unsupported sound event playback owner");
        }
        return new Handle() {
            @Override
            public boolean play() {
                var manager = Minecraft.getInstance().getSoundManager();
                manager.play(sound);
                return manager.isActive(sound);
            }

            @Override
            public void requestStop() {
                sound.requestStop();
            }

            @Override
            public void stop() {
                Minecraft.getInstance().getSoundManager().stop(sound);
            }
        };
    }
}
