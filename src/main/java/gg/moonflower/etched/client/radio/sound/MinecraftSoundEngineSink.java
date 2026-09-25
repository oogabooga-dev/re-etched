package gg.moonflower.etched.client.radio.sound;

import gg.moonflower.etched.client.radio.PlaybackOwnerKey;
import gg.moonflower.etched.client.radio.AudioCancellation;
import gg.moonflower.etched.client.radio.stream.PlaybackAudioStream;
import net.minecraft.client.Minecraft;
import net.minecraft.tags.BlockTags;

/** Block-positioned SoundEngine output for the current radio runtime. */
public final class MinecraftSoundEngineSink implements SoundEngineSink {

    private static final float RADIO_VOLUME = 4.0F;
    private static final int ATTENUATION_DISTANCE = 8;

    @Override
    public boolean supports(PlaybackOwnerKey key) {
        return key instanceof PlaybackOwnerKey.BlockOwner;
    }

    @Override
    public Handle create(PlaybackOwnerKey key, long generation, PlaybackAudioStream stream,
                         AudioCancellation cancellation, Runnable streamHandedOff,
                         Runnable soundStopped) {
        if (!(key instanceof PlaybackOwnerKey.BlockOwner blockOwner)) {
            throw new IllegalArgumentException("The Minecraft radio sink requires a block playback owner");
        }
        Minecraft minecraft = Minecraft.getInstance();
        boolean muffled = minecraft.level != null && minecraft.level.dimension().equals(blockOwner.dimension())
                && minecraft.level.getBlockState(blockOwner.pos().above()).is(BlockTags.WOOL);
        float volume = muffled ? RADIO_VOLUME / 2.0F : RADIO_VOLUME;
        int attenuationDistance = muffled ? ATTENUATION_DISTANCE / 2 : ATTENUATION_DISTANCE;
        RadioSoundInstance sound = new RadioSoundInstance(blockOwner, generation, stream, cancellation,
                volume, attenuationDistance, streamHandedOff, soundStopped);
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
