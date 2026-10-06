package gg.moonflower.etched.common.audio;

import gg.moonflower.etched.common.item.BoomboxItem;
import gg.moonflower.etched.common.network.EtchedMessages;
import gg.moonflower.etched.common.network.play.ClientboundBoomboxStatePacket;
import gg.moonflower.etched.core.Etched;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.event.entity.EntityLeaveLevelEvent;
import net.minecraftforge.event.entity.living.LivingEvent;
import net.minecraftforge.event.entity.player.PlayerEvent;
import net.minecraftforge.event.server.ServerStoppedEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.network.PacketDistributor;

import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.Map;

/** Logical-server observations only. No item NBT or client playback implementation crosses this boundary. */
@Mod.EventBusSubscriber(modid = Etched.MOD_ID, bus = Mod.EventBusSubscriber.Bus.FORGE)
public final class BoomboxServerPlayback {

    private static final int MAX_ACTIVE = 256;
    private static final Map<Entity, Observed> ACTIVE = new IdentityHashMap<>();

    private BoomboxServerPlayback() {
    }

    public static boolean observe(Entity entity) {
        if (!(entity.level() instanceof ServerLevel level)) {
            return false;
        }
        Observed previous = ACTIVE.get(entity);
        if (previous != null && (previous.level != level || previous.entityId != entity.getId())) {
            retire(entity, previous);
            previous = null;
        }
        ItemStack record = entity.isAlive() && !entity.isRemoved() ? selectedRecord(entity) : ItemStack.EMPTY;
        if (previous == null && (record.isEmpty() || ACTIVE.size() >= MAX_ACTIVE)) {
            return false;
        }
        Observed current = previous == null ? new Observed(level, entity.getId(), new BoomboxControlState()) : previous;
        var changed = current.control.observe(record, () -> ServerPlaybackClock.get(level).next());
        changed.ifPresent(state ->
                broadcast(entity, packet(entity, current, state)));
        if (current.control.enabled()) {
            ACTIVE.put(entity, current);
        } else {
            ACTIVE.remove(entity);
        }
        return changed.isPresent();
    }

    private static ItemStack selectedRecord(Entity entity) {
        if (entity instanceof LivingEntity living) {
            return BoomboxItem.selectPlayingRecord(living.getMainHandItem(), living.getOffhandItem());
        }
        if (entity instanceof ItemEntity item) {
            ItemStack stack = item.getItem();
            return stack.getItem() instanceof BoomboxItem && BoomboxItem.hasRecord(stack) && !BoomboxItem.isPaused(stack)
                    ? BoomboxItem.getRecord(stack) : ItemStack.EMPTY;
        }
        return ItemStack.EMPTY;
    }

    private static ClientboundBoomboxStatePacket packet(Entity entity, Observed observed, PlaybackState state) {
        return new ClientboundBoomboxStatePacket(observed.level.dimension(), observed.entityId, entity.getUUID(), state);
    }

    private static void broadcast(Entity entity, ClientboundBoomboxStatePacket packet) {
        EtchedMessages.PLAY.send(PacketDistributor.TRACKING_ENTITY_AND_SELF.with(() -> entity), packet);
    }

    private static void retire(Entity entity, Observed observed) {
        if (!ACTIVE.remove(entity, observed)) {
            return;
        }
        var stopped = new PlaybackState(ServerPlaybackClock.get(observed.level).next(), java.util.Optional.empty(), false);
        broadcast(entity, packet(entity, observed, stopped));
    }

    @SubscribeEvent
    public static void onLivingTick(LivingEvent.LivingTickEvent event) {
        observe(event.getEntity());
    }

    @SubscribeEvent
    public static void onLevelTick(TickEvent.LevelTickEvent event) {
        if (event.phase != TickEvent.Phase.END || !(event.level instanceof ServerLevel level)) {
            return;
        }
        // Also catches a dropped item's replacement with a non-boombox, whose Item hook no longer runs.
        for (var entry : new ArrayList<>(ACTIVE.entrySet())) {
            if (entry.getValue().level != level) {
                continue;
            }
            Entity entity = entry.getKey();
            if (entity.level() != level || entity.isRemoved() || !entity.isAlive()
                    || level.getEntity(entry.getValue().entityId) != entity) {
                retire(entity, entry.getValue());
            } else {
                observe(entity);
            }
        }
    }

    @SubscribeEvent
    public static void onStartTracking(PlayerEvent.StartTracking event) {
        if (!(event.getEntity() instanceof ServerPlayer player) || !(event.getTarget().level() instanceof ServerLevel)) {
            return;
        }
        sendSnapshot(player, event.getTarget());
    }

    public static void sendSnapshot(ServerPlayer player, Entity target) {
        if (!(target.level() instanceof ServerLevel) || player.level() != target.level()) {
            return;
        }
        boolean published = observe(target);
        Observed current = ACTIVE.get(target);
        if (current != null && !published) {
            EtchedMessages.PLAY.send(PacketDistributor.PLAYER.with(() -> player), packet(target, current,
                    current.control.snapshot(() -> ServerPlaybackClock.get(current.level).next())));
        }
    }

    @SubscribeEvent
    public static void onLogin(PlayerEvent.PlayerLoggedInEvent event) {
        observe(event.getEntity()); // The owning player is not a StartTracking recipient for itself.
    }

    @SubscribeEvent
    public static void onLeave(EntityLeaveLevelEvent event) {
        if (event.getLevel() instanceof ServerLevel) {
            Observed current = ACTIVE.get(event.getEntity());
            if (current != null) {
                retire(event.getEntity(), current);
            }
        }
    }

    @SubscribeEvent
    public static void onServerStopped(ServerStoppedEvent event) {
        ACTIVE.clear();
    }

    private record Observed(ServerLevel level, int entityId, BoomboxControlState control) {
    }
}
