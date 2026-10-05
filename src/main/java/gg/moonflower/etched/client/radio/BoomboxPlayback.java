package gg.moonflower.etched.client.radio;

import gg.moonflower.etched.common.audio.PlaybackRevision;
import gg.moonflower.etched.common.audio.PlaybackState;
import gg.moonflower.etched.common.item.BoomboxClientBridge;
import gg.moonflower.etched.common.item.BoomboxItem;
import gg.moonflower.etched.common.item.RecordContentResolver;
import net.minecraft.client.Minecraft;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.ItemStack;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/** One managed playback owner per held or dropped boombox; unsupported records never start another engine. */
public final class BoomboxPlayback implements BoomboxClientBridge.Listener {

    private static final int MAX_ACTIVE = 256;
    private static final BoomboxPlayback INSTANCE = new BoomboxPlayback(AudioPlaybackManager.getInstance());

    private final AudioPlaybackManager playback;
    private final Map<PlaybackOwnerKey.EntityOwner, Active> active = new HashMap<>();

    private BoomboxPlayback(AudioPlaybackManager playback) {
        this.playback = Objects.requireNonNull(playback, "playback");
    }

    public static BoomboxPlayback getInstance() {
        return INSTANCE;
    }

    @Override
    public void update(Entity entity, ItemStack record) {
        PlaybackOwnerKey.EntityOwner key = key(entity);
        Active previous = this.active.get(key);
        if (previous != null && previous.entity != entity) {
            this.remove(previous, key);
            previous = null;
        }
        if (previous != null && ItemStack.matches(previous.record, record)) {
            return; // Ticking an unchanged disc must not restart its finite session.
        }
        if (record.isEmpty()) {
            if (previous != null) {
                this.remove(previous, key);
            }
            return;
        }
        if (previous == null && this.active.size() >= MAX_ACTIVE) {
            return;
        }

        var content = RecordContentResolver.resolve(record);
        if (content.isPresent()) {
            long revision = this.playback.getPlaybackState(key).map(PlaybackState::revision)
                    .map(PlaybackRevision::next).orElse(0L);
            if (this.playback.update(key, new PlaybackState(revision,
                    Optional.of(content.orElseThrow().program()), true))) {
                this.active.put(key, new Active(entity, record.copy()));
                this.playback.getSessionSnapshot(key).ifPresent(snapshot ->
                        this.playback.setFiniteLoop(key, revision, snapshot.generation(), FiniteLoopMode.ALL));
            }
        } else if (previous != null) {
            this.remove(previous, key);
        }
    }

    @Override
    public boolean isPlaying(Entity entity) {
        PlaybackOwnerKey.EntityOwner key = key(entity);
        Active current = this.active.get(key);
        return current != null && current.entity == entity && this.playback.isPlaying(key);
    }

    public void remove(Entity entity) {
        PlaybackOwnerKey.EntityOwner key = key(entity);
        Active current = this.active.get(key);
        if (current != null && current.entity == entity) {
            this.remove(current, key);
        }
    }

    /** Covers item-stack replacement when the new dropped item is no longer a boombox. */
    public void prune() {
        var level = Minecraft.getInstance().level;
        for (var entry : new ArrayList<>(this.active.entrySet())) {
            Entity entity = entry.getValue().entity;
            if (level == null || entity.level() != level || !entity.isAlive() || entity.isRemoved()
                    || level.getEntity(entity.getId()) != entity
                    || entity instanceof ItemEntity item && !(item.getItem().getItem() instanceof BoomboxItem)) {
                this.remove(entry.getValue(), entry.getKey());
            }
        }
    }

    public void clearAll() {
        for (var entry : new ArrayList<>(this.active.entrySet())) {
            this.remove(entry.getValue(), entry.getKey());
        }
    }

    private void remove(Active current, PlaybackOwnerKey.EntityOwner key) {
        if (!this.active.remove(key, current)) {
            return;
        }
        this.playback.remove(key);
    }

    private static PlaybackOwnerKey.EntityOwner key(Entity entity) {
        return PlaybackOwnerKey.entity(entity.level().dimension(), entity.getUUID());
    }

    private record Active(Entity entity, ItemStack record) {
    }
}
