package gg.moonflower.etched.client.radio.sound;

import org.jetbrains.annotations.ApiStatus;

/** Internal SoundEngine removal notification; stream ownership stays with the sink/engine. */
@ApiStatus.Internal
@FunctionalInterface
public interface PlaybackStopListener {

    /** Called on engine removal or stopAll; managed sounds must tolerate repeated notifications. */
    void onStop();
}
