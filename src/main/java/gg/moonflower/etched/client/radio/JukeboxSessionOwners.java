package gg.moonflower.etched.client.radio;

import gg.moonflower.etched.common.audio.PlaybackState;

import java.util.HashMap;
import java.util.Map;
import java.util.function.Predicate;

/** Adapter-owned sessions only; a later radio/other block owner at the same position is not ours to close. */
final class JukeboxSessionOwners {

    private final Map<PlaybackOwnerKey.BlockOwner, PlaybackState> states = new HashMap<>();

    void remember(PlaybackOwnerKey.BlockOwner key, PlaybackState state) {
        this.states.put(key, state); // Admission is already bounded by JukeboxRevisionGate.
    }

    void forget(PlaybackOwnerKey.BlockOwner key) {
        this.states.remove(key);
    }

    void prune(AudioPlaybackManager manager, Predicate<PlaybackOwnerKey.BlockOwner> valid) {
        var iterator = this.states.entrySet().iterator();
        while (iterator.hasNext()) {
            var entry = iterator.next();
            boolean ours = manager.getPlaybackState(entry.getKey()).orElse(null) == entry.getValue();
            if (!ours || !valid.test(entry.getKey())) {
                iterator.remove();
                if (ours) {
                    manager.remove(entry.getKey());
                }
            }
        }
    }

    void clearAll() {
        this.states.clear();
    }
}
