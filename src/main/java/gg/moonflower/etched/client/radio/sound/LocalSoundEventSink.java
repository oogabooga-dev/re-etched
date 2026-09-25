package gg.moonflower.etched.client.radio.sound;

import gg.moonflower.etched.client.radio.AudioCancellation;
import gg.moonflower.etched.client.radio.PlaybackOwnerKey;
import net.minecraft.resources.ResourceLocation;

/** Plays a vanilla sound event without opening an HTTP response or a custom decoder. */
public interface LocalSoundEventSink {

    boolean supports(PlaybackOwnerKey key);

    /** Called on the sound engine's owner thread. The callback may run after the handle is stopped. */
    Handle create(PlaybackOwnerKey key, ResourceLocation event, AudioCancellation cancellation,
                  Runnable soundStopped);

    interface Handle {

        /** Called on the owner thread. */
        boolean play();

        /** May run on any thread; must not touch SoundManager. */
        void requestStop();

        /** Called on the owner thread. */
        void stop();
    }
}
