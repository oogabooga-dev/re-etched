package gg.moonflower.etched.client.radio.sound;

import gg.moonflower.etched.client.radio.AudioCancellation;
import gg.moonflower.etched.client.radio.PlaybackOwnerKey;
import net.minecraft.client.Minecraft;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.tags.BlockTags;

/** Block-positioned vanilla SoundManager output for local sound events. */
public final class MinecraftLocalSoundEventSink implements LocalSoundEventSink {

    @Override
    public boolean supports(PlaybackOwnerKey key) {
        return key instanceof PlaybackOwnerKey.BlockOwner;
    }

    @Override
    public Handle create(PlaybackOwnerKey key, ResourceLocation event, AudioCancellation cancellation,
                         Runnable soundStopped) {
        if (!(key instanceof PlaybackOwnerKey.BlockOwner blockOwner)) {
            throw new IllegalArgumentException("The Minecraft sound event sink requires a block playback owner");
        }
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.level == null || !minecraft.level.dimension().equals(blockOwner.dimension())) {
            throw new IllegalStateException("Sound event playback owner is unavailable");
        }
        boolean muffled = minecraft.level.getBlockState(blockOwner.pos().above()).is(BlockTags.WOOL);
        LocalSoundEventInstance sound = new LocalSoundEventInstance(blockOwner, event, cancellation, soundStopped, muffled);
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
