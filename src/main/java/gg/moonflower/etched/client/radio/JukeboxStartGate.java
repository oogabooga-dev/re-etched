package gg.moonflower.etched.client.radio;

import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;

import java.util.ArrayDeque;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;

/**
 * Correlates first-party jukebox 1010 events and bounded program packets in send order.
 * The server sends one program packet immediately after each first-party 1010;
 * both use the same ordered Minecraft connection. Stop events invalidate slots
 * but cannot remove them until their packets arrive.
 */
final class JukeboxStartGate {

    private static final int MAX_OWNERS = 256;
    private static final int MAX_PENDING_PER_OWNER = 64;

    private final Map<PlaybackOwnerKey.BlockOwner, Pending> owners = new HashMap<>();

    void start(PlaybackOwnerKey.BlockOwner key, int itemId, boolean hasRecord) {
        Objects.requireNonNull(key, "key");
        Pending pending = this.owners.get(key);
        if (pending == null) {
            if (this.owners.size() >= MAX_OWNERS) {
                return; // Fail closed if a server sends more distinct starts than we can track.
            }
            pending = new Pending();
            this.owners.put(key, pending);
        }
        if (pending.blocked) {
            return;
        }
        if (pending.tickets.size() >= MAX_PENDING_PER_OWNER) {
            pending.tickets.clear();
            pending.blocked = true;
            return;
        }
        pending.tickets.addLast(new Ticket(itemId, hasRecord));
    }

    void stop(PlaybackOwnerKey.BlockOwner key) {
        Pending pending = this.owners.get(Objects.requireNonNull(key, "key"));
        if (pending != null) {
            // Do not remove tombstones: the matching packet can still be in flight.
            pending.tickets.forEach(ticket -> ticket.valid = false);
        }
    }

    boolean consume(PlaybackOwnerKey.BlockOwner key, int itemId) {
        Pending pending = this.owners.get(Objects.requireNonNull(key, "key"));
        if (pending == null || pending.blocked) {
            return false;
        }
        Ticket ticket = pending.tickets.pollFirst();
        if (pending.tickets.isEmpty()) {
            this.owners.remove(key);
        }
        return ticket != null && ticket.valid && ticket.itemId == itemId;
    }

    void clearAll() {
        this.owners.clear();
    }

    void unloadChunk(ResourceKey<Level> dimension, ChunkPos pos) {
        this.owners.forEach((key, pending) -> {
            if (key.dimension().equals(dimension) && new ChunkPos(key.pos()).equals(pos)) {
                pending.tickets.forEach(ticket -> ticket.valid = false);
            }
        }); // Preserve send-order slots until the matching packets arrive.
    }

    private static final class Pending {
        private final ArrayDeque<Ticket> tickets = new ArrayDeque<>();
        private boolean blocked;
    }

    private static final class Ticket {
        private final int itemId;
        private boolean valid;

        private Ticket(int itemId, boolean valid) {
            this.itemId = itemId;
            this.valid = valid;
        }
    }
}
