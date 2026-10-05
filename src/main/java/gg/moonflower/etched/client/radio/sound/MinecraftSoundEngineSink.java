package gg.moonflower.etched.client.radio.sound;

import gg.moonflower.etched.client.radio.PlaybackOwnerKey;
import gg.moonflower.etched.common.audio.AudioCancellation;
import gg.moonflower.etched.client.radio.stream.PlaybackAudioStream;
import net.minecraft.client.Minecraft;
import net.minecraft.tags.BlockTags;

/** Block- or entity-positioned SoundEngine output for managed streams. */
public final class MinecraftSoundEngineSink implements SoundEngineSink {

    private static final float RADIO_VOLUME = 4.0F;
    private static final int ATTENUATION_DISTANCE = 8;

    @Override
    public boolean supports(PlaybackOwnerKey key) {
        return key instanceof PlaybackOwnerKey.BlockOwner || key instanceof PlaybackOwnerKey.EntityOwner;
    }

    @Override
    public Handle create(PlaybackOwnerKey key, long generation, PlaybackAudioStream stream,
                         AudioCancellation cancellation, Runnable streamHandedOff,
                         Runnable soundStopped) {
        RadioSoundInstance sound;
        if (key instanceof PlaybackOwnerKey.BlockOwner blockOwner) {
            Minecraft minecraft = Minecraft.getInstance();
            boolean muffled = minecraft.level != null && minecraft.level.dimension().equals(blockOwner.dimension())
                    && minecraft.level.getBlockState(blockOwner.pos().above()).is(BlockTags.WOOL);
            float volume = muffled ? RADIO_VOLUME / 2.0F : RADIO_VOLUME;
            int attenuationDistance = muffled ? ATTENUATION_DISTANCE / 2 : ATTENUATION_DISTANCE;
            sound = new RadioSoundInstance(blockOwner, generation, stream, cancellation,
                    volume, attenuationDistance, streamHandedOff, soundStopped);
        } else if (key instanceof PlaybackOwnerKey.EntityOwner entityOwner) {
            sound = new RadioSoundInstance(entityOwner, generation, stream, cancellation,
                    RADIO_VOLUME, ATTENUATION_DISTANCE, streamHandedOff, soundStopped,
                    EntitySoundPosition.find(entityOwner));
        } else {
            throw new IllegalArgumentException("Unsupported stream playback owner");
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
