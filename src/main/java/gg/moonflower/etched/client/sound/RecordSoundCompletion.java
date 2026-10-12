package gg.moonflower.etched.client.sound;

import gg.moonflower.etched.api.sound.SoundStopListener;

/** Distinguishes a drained finite track from a failed or cancelled sound. */
public interface RecordSoundCompletion extends SoundStopListener {
    boolean completedNormally();
}
