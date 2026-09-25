package gg.moonflower.etched.client.radio.sound;

import gg.moonflower.etched.client.radio.PlaybackOwnerKey;
import gg.moonflower.etched.client.radio.AudioCancellation;
import gg.moonflower.etched.client.radio.stream.PlaybackAudioStream;

/**
 * Transfers prepared audio streams to a platform sound engine.
 *
 * <p>{@link #create} and handle {@link Handle#play()} and {@link Handle#stop()} calls run on the
 * sound engine's owner thread. {@link Handle#requestStop()} may run on any thread and must not call
 * the sound engine. A handle invokes {@code streamHandedOff} exactly when the sound engine accepts
 * ownership of the stream; after that point the sink, rather than the backend, owns stream closure.
 */
public interface SoundEngineSink {

    boolean supports(PlaybackOwnerKey key);

    Handle create(PlaybackOwnerKey key, long generation, PlaybackAudioStream stream,
                  AudioCancellation cancellation, Runnable streamHandedOff, Runnable soundStopped);

    interface Handle {

        boolean play();

        void requestStop();

        void stop();
    }
}
