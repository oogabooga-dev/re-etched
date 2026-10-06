package gg.moonflower.etched.client.radio;

import gg.moonflower.etched.common.audio.PlaybackRevision;

import java.util.HashMap;
import java.util.Map;
import java.util.Objects;

/** Retains managed jukebox revision tombstones after local session removal, until world cleanup. */
final class JukeboxRevisionGate {

    private static final int MAX_OWNERS = 256;

    private final Map<PlaybackOwnerKey.BlockOwner, Long> revisions = new HashMap<>();

    boolean accept(PlaybackOwnerKey.BlockOwner key, long revision) {
        Objects.requireNonNull(key, "key");
        Long previous = this.revisions.get(key);
        if (previous != null && !PlaybackRevision.isNewer(revision, previous)) {
            return false;
        }
        if (previous == null && this.revisions.size() >= MAX_OWNERS) {
            return false; // Do not evict tombstones and re-admit a stale start when the bound is exhausted.
        }
        this.revisions.put(key, revision);
        return true;
    }

    void clearAll() {
        this.revisions.clear();
    }
}
