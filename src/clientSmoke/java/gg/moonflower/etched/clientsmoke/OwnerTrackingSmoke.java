package gg.moonflower.etched.clientsmoke;

import gg.moonflower.etched.client.radio.AudioPlaybackManager;
import gg.moonflower.etched.client.radio.PlaybackOwnerKey;
import gg.moonflower.etched.common.audio.PlaybackRevision;
import gg.moonflower.etched.common.audio.PlaybackState;
import gg.moonflower.etched.common.audio.ServerPlaybackClock;
import gg.moonflower.etched.common.item.BoomboxItem;
import gg.moonflower.etched.common.network.EtchedMessages;
import gg.moonflower.etched.common.network.play.ClientboundBoomboxStatePacket;
import gg.moonflower.etched.common.network.play.ClientboundPlayMusicPacket;
import gg.moonflower.etched.common.network.play.EtchedPacket;
import gg.moonflower.etched.core.registry.EtchedItems;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.JukeboxBlockEntity;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.network.PacketDistributor;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.function.Consumer;

/** Actual entity untracking/retracking and dimension round-trip; no direct client start or snapshot calls. */
final class OwnerTrackingSmoke {

    private static int step;
    private static int ticks;
    private static Vec3 origin;
    private static volatile List<UUID> owners = List.of();
    private static volatile long beforeTracking;
    private static volatile long beforeDimension;
    private static PlaybackState held;
    private static PlaybackState jukebox;
    private static List<ClientboundBoomboxStatePacket> stale = List.of();
    private static List<PlaybackState> retracked = List.of();

    private OwnerTrackingSmoke() {
    }

    static boolean tick(Minecraft client, BlockPos pos) {
        if (step == 10) {
            return true;
        }
        if (++ticks > 400) {
            throw new AssertionError("Owner tracking smoke timed out at step " + step);
        }
        if (client.level == null || client.player == null) {
            return false;
        }
        var manager = AudioPlaybackManager.getInstance();
        var heldKey = PlaybackOwnerKey.entity(client.level.dimension(), client.player.getUUID());
        var blockKey = PlaybackOwnerKey.block(client.level.dimension(), pos);
        var chunk = new ChunkPos(pos);
        switch (step) {
            case 0 -> {
                origin = client.player.position();
                held = manager.getPlaybackState(heldKey).orElseThrow();
                submit(client, player -> {
                    var level = player.serverLevel();
                    level.setChunkForced(chunk.x, chunk.z, true);
                    var fixtures = new ArrayList<UUID>();
                    var stand = EntityType.ARMOR_STAND.create(level);
                    if (stand == null) {
                        throw new AssertionError("Could not create tracking fixture");
                    }
                    stand.moveTo(pos.getX() + 0.5, pos.getY() + 2, pos.getZ() + 0.5);
                    stand.setNoGravity(true);
                    stand.setItemSlot(EquipmentSlot.MAINHAND, boombox(new ItemStack(Items.MUSIC_DISC_BLOCKS), false));
                    level.addFreshEntity(stand);
                    fixtures.add(stand.getUUID());
                    for (var stack : List.of(boombox(new ItemStack(Items.MUSIC_DISC_CAT), false),
                            boombox(new ItemStack(Items.MUSIC_DISC_CAT), true),
                            boombox(new ItemStack(EtchedItems.ALBUM_COVER.get()), false))) {
                        var item = new ItemEntity(level, pos.getX() + 0.5, pos.getY() + 3, pos.getZ() + 0.5, stack);
                        item.setNoGravity(true);
                        item.setNeverPickUp();
                        item.setUnlimitedLifetime();
                        level.addFreshEntity(item);
                        fixtures.add(item.getUUID());
                    }
                    owners = List.copyOf(fixtures);
                });
                advance();
            }
            case 1 -> {
                if (owners.size() != 4 || owners.stream().anyMatch(id -> find(client, id) == null)
                        || !playing(manager, owners.get(0)) || !playing(manager, owners.get(1))) {
                    return false;
                }
                if (ticks < 20) {
                    return false;
                }
                assertSilent(manager);
                assertSame(manager, heldKey, held);
                stale = owners.subList(0, 2).stream().map(id -> new ClientboundBoomboxStatePacket(Level.OVERWORLD,
                        find(client, id).getId(), id, manager.getPlaybackState(entityKey(id)).orElseThrow())).toList();
                submit(client, player -> player.teleportTo(player.serverLevel(), origin.x + 512, origin.y, origin.z,
                        player.getYRot(), player.getXRot()));
                advance();
            }
            case 2 -> {
                if (owners.stream().anyMatch(id -> find(client, id) != null)
                        || owners.stream().anyMatch(id -> manager.getPlaybackState(entityKey(id)).isPresent())) {
                    return false;
                }
                assertSame(manager, heldKey, held);
                submit(client, player -> {
                    if (owners.stream().anyMatch(id -> player.serverLevel().getEntity(id) == null)) {
                        throw new AssertionError("Untracking fixture was removed server-side instead of retracked");
                    }
                    for (var packet : stale) {
                        if (player.serverLevel().getEntity(packet.owner()).getId() != packet.entityId()) {
                            throw new AssertionError("Retracking fixture changed server entity incarnation");
                        }
                    }
                    beforeTracking = ServerPlaybackClock.get(player.serverLevel()).current();
                    player.teleportTo(player.serverLevel(), origin.x, origin.y, origin.z, player.getYRot(), player.getXRot());
                });
                advance();
            }
            case 3 -> {
                if (owners.stream().anyMatch(id -> find(client, id) == null)
                        || !playing(manager, owners.get(0)) || !playing(manager, owners.get(1))) {
                    return false;
                }
                for (int i = 0; i < 2; i++) {
                    var state = manager.getPlaybackState(entityKey(owners.get(i))).orElseThrow();
                    if (!PlaybackRevision.isNewer(state.revision(), beforeTracking)
                            || !state.program().equals(stale.get(i).state().program())) {
                        throw new AssertionError("Retracked owner did not receive fresh authoritative same-program state");
                    }
                }
                assertSilent(manager);
                assertSame(manager, heldKey, held);
                retracked = owners.subList(0, 2).stream().map(id -> manager.getPlaybackState(entityKey(id)).orElseThrow()).toList();
                submit(client, player -> stale.forEach(packet -> send(player, packet)));
                advance();
            }
            case 4 -> {
                if (ticks < 20) {
                    return false;
                }
                for (int i = 0; i < stale.size(); i++) {
                    var packet = stale.get(i);
                    assertSame(manager, entityKey(packet.owner()), retracked.get(i));
                    if (!playing(manager, packet.owner()) || !PlaybackRevision.isNewer(
                            manager.getPlaybackState(entityKey(packet.owner())).orElseThrow().revision(), beforeTracking)) {
                        throw new AssertionError("Delayed pre-untracking publication replaced fresh entity state");
                    }
                }
                assertSame(manager, heldKey, held);
                jukebox = manager.getPlaybackState(blockKey).orElseThrow();
                submit(client, player -> {
                    var nether = player.server.getLevel(Level.NETHER);
                    nether.setChunkForced(chunk.x, chunk.z, true);
                    nether.setBlockAndUpdate(pos, Blocks.JUKEBOX.defaultBlockState());
                    ((JukeboxBlockEntity) nether.getBlockEntity(pos)).setFirstItem(new ItemStack(Items.MUSIC_DISC_BLOCKS));
                    beforeDimension = ServerPlaybackClock.get(player.serverLevel()).current();
                    player.teleportTo(nether, origin.x, origin.y, origin.z, player.getYRot(), player.getXRot());
                });
                System.out.println("ETCHED LIVING AND DROPPED BOOMBOX ENTITY UNTRACK RETRACK SNAPSHOT SMOKE PASSED");
                advance();
            }
            case 5 -> {
                if (!client.level.dimension().equals(Level.NETHER) || !manager.isPlaying(heldKey) || !manager.isPlaying(blockKey)) {
                    return false;
                }
                assertOldDimensionEmpty(manager, Level.OVERWORLD, client.player.getUUID(), pos);
                assertFresh(manager, heldKey, beforeDimension, "minecraft:music_disc.cat");
                assertFresh(manager, blockKey, beforeDimension, "minecraft:music_disc.blocks");
                held = manager.getPlaybackState(heldKey).orElseThrow();
                var oldJukebox = jukebox;
                jukebox = manager.getPlaybackState(blockKey).orElseThrow();
                submit(client, player -> {
                    stale.forEach(packet -> send(player, packet));
                    send(player, new ClientboundPlayMusicPacket(Level.OVERWORLD, pos,
                            Item.getId(EtchedItems.ETCHED_MUSIC_DISC.get()),
                            oldJukebox.revision(), oldJukebox.program()));
                });
                advance();
            }
            case 6 -> {
                if (ticks < 20) {
                    return false;
                }
                assertSame(manager, heldKey, held);
                assertSame(manager, blockKey, jukebox);
                assertOldDimensionEmpty(manager, Level.OVERWORLD, client.player.getUUID(), pos);
                var netherHeld = new ClientboundBoomboxStatePacket(Level.NETHER, client.player.getId(), client.player.getUUID(), held);
                var netherJukebox = new ClientboundPlayMusicPacket(Level.NETHER, pos,
                        Item.getId(Items.MUSIC_DISC_BLOCKS), jukebox.revision(), jukebox.program());
                submit(client, player -> {
                    beforeDimension = ServerPlaybackClock.get(player.serverLevel()).current();
                    player.teleportTo(player.server.overworld(), origin.x, origin.y, origin.z, player.getYRot(), player.getXRot());
                    send(player, netherHeld);
                    send(player, netherJukebox);
                });
                advance();
            }
            case 7 -> {
                if (!client.level.dimension().equals(Level.OVERWORLD) || !manager.isPlaying(heldKey) || !manager.isPlaying(blockKey)
                        || !playing(manager, owners.get(0)) || !playing(manager, owners.get(1))) {
                    return false;
                }
                assertOldDimensionEmpty(manager, Level.NETHER, client.player.getUUID(), pos);
                assertFresh(manager, heldKey, beforeDimension, "minecraft:music_disc.cat");
                assertFresh(manager, blockKey, beforeDimension, "minecraft:music_disc.cat");
                assertFresh(manager, entityKey(owners.get(0)), beforeDimension, "minecraft:music_disc.blocks");
                assertFresh(manager, entityKey(owners.get(1)), beforeDimension, "minecraft:music_disc.cat");
                assertSilent(manager);
                held = manager.getPlaybackState(heldKey).orElseThrow();
                jukebox = manager.getPlaybackState(blockKey).orElseThrow();
                advance();
            }
            case 8 -> {
                if (ticks < 20) {
                    return false;
                }
                assertSame(manager, heldKey, held);
                assertSame(manager, blockKey, jukebox);
                assertSilent(manager);
                submit(client, player -> {
                    owners.forEach(id -> player.serverLevel().getEntity(id).discard());
                    player.serverLevel().setChunkForced(chunk.x, chunk.z, false);
                    var nether = player.server.getLevel(Level.NETHER);
                    ((JukeboxBlockEntity) nether.getBlockEntity(pos)).removeFirstItem();
                    nether.setBlockAndUpdate(pos, Blocks.AIR.defaultBlockState());
                    nether.setChunkForced(chunk.x, chunk.z, false);
                });
                advance();
            }
            case 9 -> {
                if (owners.stream().anyMatch(id -> find(client, id) != null)
                        || owners.stream().anyMatch(id -> manager.getPlaybackState(entityKey(id)).isPresent())) {
                    return false;
                }
                assertSame(manager, heldKey, held);
                System.out.println("ETCHED DIMENSION ROUND TRIP HELD BOOMBOX AND SAME POSITION JUKEBOX SNAPSHOT SMOKE PASSED");
                advance();
                return true;
            }
            default -> throw new AssertionError("Unknown owner tracking smoke step " + step);
        }
        return false;
    }

    private static ItemStack boombox(ItemStack record, boolean paused) {
        var stack = new ItemStack(EtchedItems.BOOMBOX.get());
        BoomboxItem.setRecord(stack, record);
        BoomboxItem.setPaused(stack, paused);
        return stack;
    }

    private static Entity find(Minecraft client, UUID id) {
        for (var entity : client.level.entitiesForRendering()) {
            if (entity.getUUID().equals(id)) {
                return entity;
            }
        }
        return null;
    }

    private static PlaybackOwnerKey.EntityOwner entityKey(UUID id) {
        return PlaybackOwnerKey.entity(Level.OVERWORLD, id);
    }

    private static boolean playing(AudioPlaybackManager manager, UUID id) {
        return manager.isPlaying(entityKey(id));
    }

    private static void assertSilent(AudioPlaybackManager manager) {
        for (var id : owners.subList(2, 4)) {
            if (manager.getPlaybackState(entityKey(id)).isPresent()) {
                throw new AssertionError("Paused/invalid boombox gained state through a tracking snapshot");
            }
        }
    }

    private static void assertOldDimensionEmpty(AudioPlaybackManager manager,
                                               ResourceKey<Level> dimension, UUID player, BlockPos pos) {
        if (manager.getPlaybackState(PlaybackOwnerKey.entity(dimension, player)).isPresent()
                || manager.getPlaybackState(PlaybackOwnerKey.block(dimension, pos)).isPresent()
                || owners.stream().anyMatch(id -> manager.getPlaybackState(PlaybackOwnerKey.entity(dimension, id)).isPresent())) {
            throw new AssertionError("Old dimension retained a managed owner after transition");
        }
    }

    private static void assertFresh(AudioPlaybackManager manager, PlaybackOwnerKey key, long before, String source) {
        var state = manager.getPlaybackState(key).orElseThrow();
        if (!PlaybackRevision.isNewer(state.revision(), before)
                || !source.equals(state.program().orElseThrow().tracks().get(0).source())) {
            throw new AssertionError("Dimension snapshot did not restore a fresh correct program for " + key);
        }
    }

    private static void assertSame(AudioPlaybackManager manager, PlaybackOwnerKey key, PlaybackState state) {
        if (manager.getPlaybackState(key).orElse(null) != state) {
            throw new AssertionError("Unchanged active owner restarted during tracking/delayed packet check: " + key);
        }
    }

    private static void advance() {
        step++;
        ticks = 0;
    }

    private static void submit(Minecraft client, Consumer<ServerPlayer> action) {
        var server = client.getSingleplayerServer();
        var playerId = client.player.getUUID();
        server.execute(() -> action.accept(server.getPlayerList().getPlayer(playerId)));
    }

    private static void send(ServerPlayer player, EtchedPacket packet) {
        EtchedMessages.PLAY.send(PacketDistributor.PLAYER.with(() -> player), packet);
    }
}
