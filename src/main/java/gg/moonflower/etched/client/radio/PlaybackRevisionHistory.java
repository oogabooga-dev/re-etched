package gg.moonflower.etched.client.radio;

import gg.moonflower.etched.common.audio.PlaybackRevision;

import java.util.HashMap;
import java.util.Map;
import java.util.Objects;

/**
 * Bounded retained-owner watermarks plus one conservative floor for retired owners.
 * Only for publications from the shared ServerPlaybackClock, including fresh tracking snapshots.
 * Retiring an owner may reject an unseen delayed publication too; a fresh server snapshot is required.
 * Known active/pending owners retain independent ordering, even below the floor. No TTL/LRU eviction.
 */
final class PlaybackRevisionHistory<K> {

    private static final int MAX_RETAINED = 256;

    private final Map<K, Long> retained = new HashMap<>();
    private boolean hasFloor;
    private long floor;

    boolean accept(K key, long revision, boolean retain) {
        Objects.requireNonNull(key, "key");
        Long previous = this.retained.get(key);
        if (previous != null ? !PlaybackRevision.isNewer(revision, previous)
                : this.hasFloor && !PlaybackRevision.isNewer(revision, this.floor)) {
            return false;
        }
        if (retain) {
            if (previous == null && this.retained.size() >= MAX_RETAINED) {
                return false; // Never evict an active/pending owner to admit another one.
            }
            this.retained.put(key, revision);
        } else {
            this.retained.remove(key);
            this.advanceFloor(revision); // Stops need no retained slot, even at the active-owner bound.
        }
        return true;
    }

    void release(K key) {
        Long revision = this.retained.remove(Objects.requireNonNull(key, "key"));
        if (revision != null) {
            this.advanceFloor(revision);
        }
    }

    private void advanceFloor(long revision) {
        if (!this.hasFloor || PlaybackRevision.isNewer(revision, this.floor)) {
            this.hasFloor = true;
            this.floor = revision;
        }
    }

    void clearAll() {
        this.retained.clear();
        this.hasFloor = false;
        this.floor = 0L;
    }
}
