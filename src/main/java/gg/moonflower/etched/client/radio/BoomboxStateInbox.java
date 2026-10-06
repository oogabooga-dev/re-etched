package gg.moonflower.etched.client.radio;

import gg.moonflower.etched.common.network.play.ClientboundBoomboxStatePacket;

import java.util.ArrayList;
import java.util.List;
import java.util.HashMap;
import java.util.Map;

/** Bounded spawn/equipment waiting plus compressed retired revision history; duplicates cannot extend waiting. */
final class BoomboxStateInbox {

    private static final int MAX_PENDING = 32;
    private static final int PENDING_TICKS = 100;

    private final Map<PlaybackOwnerKey.EntityOwner, Entry> entries = new HashMap<>();
    private final PlaybackRevisionHistory<PlaybackOwnerKey.EntityOwner> history = new PlaybackRevisionHistory<>();

    boolean accept(ClientboundBoomboxStatePacket packet) {
        this.compactRetired();
        var key = PlaybackOwnerKey.entity(packet.dimension(), packet.owner());
        Entry previous = this.entries.get(key);
        if (packet.state().enabled() && (previous == null || !previous.pending()) && this.pendingCount() >= MAX_PENDING) {
            return false;
        }
        if (!this.history.accept(key, packet.state().revision(), packet.state().enabled())) {
            return false;
        }
        if (packet.state().enabled()) {
            this.entries.put(key, new Entry(packet));
        } else {
            this.entries.remove(key);
        }
        return true;
    }

    Entry get(PlaybackOwnerKey.EntityOwner key) {
        return this.entries.get(key);
    }

    List<Map.Entry<PlaybackOwnerKey.EntityOwner, Entry>> snapshot() {
        return new ArrayList<>(this.entries.entrySet());
    }

    void waitForSource(Entry entry) {
        if (entry.packet == null || entry.pending()) {
            return;
        }
        if (this.pendingCount() >= MAX_PENDING) {
            entry.retire();
        } else {
            entry.pendingTicks = PENDING_TICKS;
        }
    }

    private long pendingCount() {
        return this.entries.values().stream().filter(Entry::pending).count();
    }

    void compactRetired() {
        var iterator = this.entries.entrySet().iterator();
        while (iterator.hasNext()) {
            var entry = iterator.next();
            if (entry.getValue().packet == null) {
                this.history.release(entry.getKey());
                iterator.remove(); // No UUID, entity ID, program or per-owner tombstone survives retirement.
            }
        }
    }

    void clearAll() {
        this.entries.clear();
        this.history.clearAll();
    }

    static final class Entry {
        final long revision;
        final int entityId;
        ClientboundBoomboxStatePacket packet;
        private int pendingTicks;

        Entry(ClientboundBoomboxStatePacket packet) {
            this.revision = packet.state().revision();
            this.entityId = packet.entityId();
            if (packet.state().enabled()) {
                this.packet = packet;
                this.pendingTicks = PENDING_TICKS;
            }
        }

        boolean pending() {
            return this.pendingTicks > 0;
        }

        void activate() {
            this.pendingTicks = 0;
        }

        void tickPending() {
            if (this.pendingTicks > 0 && --this.pendingTicks == 0) {
                this.retire();
            }
        }

        void retire() {
            this.packet = null;
            this.pendingTicks = 0;
        }
    }
}
