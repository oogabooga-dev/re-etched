package gg.moonflower.etched.client.radio;

import gg.moonflower.etched.common.audio.AudioProgram;
import gg.moonflower.etched.common.item.BoomboxClientBridge;
import gg.moonflower.etched.common.item.BoomboxItem;
import gg.moonflower.etched.common.item.RecordContentResolver;
import gg.moonflower.etched.common.network.play.ClientboundBoomboxStatePacket;
import net.minecraft.client.Minecraft;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.ItemStack;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;

/** One managed playback owner per held or dropped boombox; unsupported records never start another engine. */
public final class BoomboxPlayback implements BoomboxClientBridge.Listener {

    private static final BoomboxPlayback INSTANCE = new BoomboxPlayback(AudioPlaybackManager.getInstance());

    private final AudioPlaybackManager playback;
    private final Map<PlaybackOwnerKey.EntityOwner, Active> active = new HashMap<>();
    private final Map<PlaybackOwnerKey.EntityOwner, LocalRecord> localRecords = new HashMap<>();
    private final BoomboxStateInbox inbox = new BoomboxStateInbox();

    private BoomboxPlayback(AudioPlaybackManager playback) {
        this.playback = Objects.requireNonNull(playback, "playback");
    }

    public static BoomboxPlayback getInstance() {
        return INSTANCE;
    }

    public boolean receive(ClientboundBoomboxStatePacket packet) {
        var level = Minecraft.getInstance().level;
        if (level == null || !level.dimension().equals(packet.dimension()) || !this.inbox.accept(packet)) {
            return false;
        }
        var key = PlaybackOwnerKey.entity(packet.dimension(), packet.owner());
        this.removeActive(key);
        this.localRecords.remove(key);
        this.tryBind(key, this.inbox.get(key));
        return true;
    }

    @Override
    public void update(Entity entity, ItemStack record) {
        var key = key(entity);
        var entry = this.inbox.get(key);
        if (entry == null || entry.packet == null || entry.entityId != entity.getId()) {
            return; // Equipment/item NBT is a guard, never a source of playback intent or revisions.
        }
        if (!this.matchesRecord(key, entry, record)) {
            this.removeActive(key);
            this.inbox.waitForSource(entry);
            if (entry.packet == null) {
                this.localRecords.remove(key);
            }
        } else if (entry.pending()) {
            this.tryBind(key, entry);
        }
    }

    private void tryBind(PlaybackOwnerKey.EntityOwner key, BoomboxStateInbox.Entry entry) {
        if (entry == null || entry.packet == null || !entry.pending()) {
            return;
        }
        var level = Minecraft.getInstance().level;
        if (level == null || !level.dimension().equals(key.dimension())) {
            return;
        }
        Entity entity = level.getEntity(entry.entityId);
        if (entity == null) {
            return;
        }
        if (!entity.getUUID().equals(key.uuid()) || !entity.isAlive() || entity.isRemoved()
                || !(entity instanceof LivingEntity || entity instanceof ItemEntity)) {
            entry.retire();
            this.localRecords.remove(key);
            return;
        }
        if (!this.matchesRecord(key, entry, selectedRecord(entity))) {
            return;
        }
        var state = entry.packet.state();
        if (this.playback.update(key, state)) {
            this.active.put(key, new Active(entity));
            entry.activate();
            this.playback.getSessionSnapshot(key).ifPresent(snapshot ->
                    this.playback.setFiniteLoop(key, state.revision(), snapshot.generation(), FiniteLoopMode.ALL));
        }
    }

    private boolean matchesRecord(PlaybackOwnerKey.EntityOwner key, BoomboxStateInbox.Entry entry, ItemStack record) {
        LocalRecord previous = this.localRecords.get(key);
        if (previous == null || !ItemStack.matches(previous.record, record)) {
            previous = new LocalRecord(record.copy(), RecordContentResolver.resolve(record).map(content -> content.program()).orElse(null));
            this.localRecords.put(key, previous);
        }
        return previous.program != null && sameSources(entry.packet.state().program().orElseThrow(), previous.program);
    }

    static boolean sameSources(AudioProgram authoritative, AudioProgram local) {
        if (authoritative.kind() != local.kind() || authoritative.tracks().size() != local.tracks().size()) {
            return false;
        }
        for (int i = 0; i < authoritative.tracks().size(); i++) {
            var expected = authoritative.tracks().get(i);
            var observed = local.tracks().get(i);
            if (expected.sourceType() != observed.sourceType() || !expected.source().equals(observed.source())) {
                return false;
            }
        }
        return true; // Localized vanilla titles/cosmetics must not block a valid server program.
    }

    private static ItemStack selectedRecord(Entity entity) {
        if (entity instanceof LivingEntity living) {
            return BoomboxItem.selectPlayingRecord(living.getMainHandItem(), living.getOffhandItem());
        }
        ItemStack stack = ((ItemEntity) entity).getItem();
        return stack.getItem() instanceof BoomboxItem && BoomboxItem.hasRecord(stack) && !BoomboxItem.isPaused(stack)
                ? BoomboxItem.getRecord(stack) : ItemStack.EMPTY;
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
        var entry = this.inbox.get(key);
        if (entry != null && entry.entityId == entity.getId() && (current == null || current.entity == entity)) {
            entry.retire();
            this.removeActive(key);
            this.localRecords.remove(key);
            this.inbox.compactRetired();
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
                this.remove(entity);
            } else {
                this.update(entity, selectedRecord(entity));
            }
        }
        for (var entry : this.inbox.snapshot()) {
            if (entry.getValue().pending()) {
                entry.getValue().tickPending();
                this.tryBind(entry.getKey(), entry.getValue());
                if (entry.getValue().packet == null) {
                    this.localRecords.remove(entry.getKey());
                }
            }
        }
        this.inbox.compactRetired();
    }

    public void clearAll() {
        for (var entry : new ArrayList<>(this.active.entrySet())) {
            this.removeActive(entry.getKey());
        }
        this.inbox.clearAll();
        this.localRecords.clear();
    }

    private void removeActive(PlaybackOwnerKey.EntityOwner key) {
        if (this.active.remove(key) != null) {
            this.playback.remove(key);
        }
    }

    private static PlaybackOwnerKey.EntityOwner key(Entity entity) {
        return PlaybackOwnerKey.entity(entity.level().dimension(), entity.getUUID());
    }

    private record Active(Entity entity) {
    }

    private record LocalRecord(ItemStack record, AudioProgram program) {
    }
}
