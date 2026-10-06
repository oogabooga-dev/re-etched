package gg.moonflower.etched.clientsmoke;

import gg.moonflower.etched.client.radio.AudioPlaybackManager;
import gg.moonflower.etched.client.radio.PlaybackOwnerKey;
import gg.moonflower.etched.common.audio.AudioProgram;
import gg.moonflower.etched.common.audio.AudioTrack;
import gg.moonflower.etched.common.audio.PlaybackRevision;
import gg.moonflower.etched.common.audio.PlaybackState;
import gg.moonflower.etched.common.audio.RecordContent;
import gg.moonflower.etched.common.audio.ServerPlaybackClock;
import gg.moonflower.etched.common.item.BoomboxItem;
import gg.moonflower.etched.common.item.EtchedMusicDiscItem;
import gg.moonflower.etched.common.network.EtchedMessages;
import gg.moonflower.etched.common.network.play.ClientboundBoomboxStatePacket;
import gg.moonflower.etched.common.network.play.ClientboundPlayMusicPacket;
import gg.moonflower.etched.common.network.play.EtchedPacket;
import gg.moonflower.etched.core.mixin.client.LevelRendererAccessor;
import gg.moonflower.etched.core.registry.EtchedItems;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.TitleScreen;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.NbtIo;
import net.minecraft.nbt.Tag;
import net.minecraft.network.protocol.game.ClientboundLevelEventPacket;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.JukeboxBlockEntity;
import net.minecraft.world.level.storage.LevelResource;
import net.minecraftforge.network.PacketDistributor;
import net.minecraftforge.registries.ForgeRegistries;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Consumer;

/** Saves and reopens the same world through vanilla flows, not in-memory BE replacement or manual snapshots. */
final class WorldReopenSmoke {

    private static int step;
    private static int ticks;
    private static ResourceKey<Level> dimension;
    private static BlockPos pos;
    private static MinecraftServer oldServer;
    private static Path clockFile;
    private static volatile List<UUID> dropped = List.of();
    private static volatile long beforeClose;
    private static volatile List<Long> startTicks = List.of();
    private static volatile boolean serverChecked;
    private static long diskRevision;
    private static List<ClientboundPlayMusicPacket> oldBlocks;
    private static List<ClientboundBoomboxStatePacket> oldEntities;
    private static List<PlaybackState> restored;

    private WorldReopenSmoke() {
    }

    static boolean tick(Minecraft client, String world) {
        if (step == 6) {
            return true;
        }
        if (++ticks > 600) {
            throw new AssertionError("World reopen smoke timed out at step " + step);
        }
        var manager = AudioPlaybackManager.getInstance();
        switch (step) {
            case 0 -> {
                dimension = client.level.dimension();
                pos = client.player.blockPosition().offset(2, 0, 2);
                oldServer = client.getSingleplayerServer();
                clockFile = oldServer.getWorldPath(LevelResource.ROOT).resolve("data/etched_playback_clock.dat");
                submit(client, player -> {
                    var level = player.serverLevel();
                    var custom = new ItemStack(EtchedItems.ETCHED_MUSIC_DISC.get());
                    EtchedMusicDiscItem.setContent(custom, new RecordContent(new AudioProgram(AudioProgram.Kind.FINITE,
                            List.of(new AudioTrack(AudioTrack.SourceType.SOUND_EVENT, "minecraft:music_disc.blocks", "", "Reopen")))));
                    var nativeDisc = new ItemStack(ForgeRegistries.ITEMS.getValue(UnsupportedSmokeRecord.FOREIGN_DISC_ID));
                    var records = List.of(new ItemStack(Items.MUSIC_DISC_CAT), custom,
                            new ItemStack(Items.MUSIC_DISC_CAT), new ItemStack(EtchedItems.ALBUM_COVER.get()), nativeDisc);
                    for (int i = 0; i < records.size(); i++) {
                        var at = pos.above(i * 2);
                        level.setBlockAndUpdate(at, Blocks.JUKEBOX.defaultBlockState());
                        var jukebox = (JukeboxBlockEntity) level.getBlockEntity(at);
                        jukebox.setFirstItem(records.get(i));
                        if (i == 2) {
                            var saved = jukebox.saveWithoutMetadata();
                            saved.putBoolean("IsPlaying", false);
                            jukebox.load(saved); // Persist a retained stopped record, not an empty slot.
                            jukebox.setChanged();
                        }
                    }
                    var ids = new ArrayList<UUID>();
                    var itemPos = pos.above(12);
                    // Keep generated Nether lava/fire from destroying persistence fixtures while loading/saving.
                    for (var at : BlockPos.betweenClosed(itemPos.offset(-1, -1, -1), itemPos.offset(1, 1, 1))) {
                        level.setBlockAndUpdate(at, (at.equals(itemPos) ? Blocks.AIR : Blocks.GLASS).defaultBlockState());
                    }
                    for (var record : List.of(new ItemStack(Items.MUSIC_DISC_CAT), new ItemStack(Items.MUSIC_DISC_CAT),
                            new ItemStack(EtchedItems.ALBUM_COVER.get()))) {
                        var stack = new ItemStack(EtchedItems.BOOMBOX.get());
                        BoomboxItem.setRecord(stack, record);
                        BoomboxItem.setPaused(stack, ids.size() == 1);
                        var item = new ItemEntity(level, itemPos.getX() + 0.5, itemPos.getY(), itemPos.getZ() + 0.5, stack);
                        item.setNoGravity(true);
                        item.setNeverPickUp();
                        item.setUnlimitedLifetime();
                        level.addFreshEntity(item);
                        ids.add(item.getUUID());
                    }
                    dropped = List.copyOf(ids);
                });
                advance();
            }
            case 1 -> {
                if (dropped.size() != 3 || dropped.stream().anyMatch(id -> find(client, id) == null)
                        || !positiveKeys(client).stream().allMatch(manager::isPlaying) || ticks < 20) {
                    return false;
                }
                oldBlocks = List.of(0, 1).stream().map(i -> {
                    var at = pos.above(i * 2);
                    var state = manager.getPlaybackState(PlaybackOwnerKey.block(dimension, at)).orElseThrow();
                    return new ClientboundPlayMusicPacket(dimension, at,
                            Item.getId(i == 0 ? Items.MUSIC_DISC_CAT : EtchedItems.ETCHED_MUSIC_DISC.get()),
                            state.revision(), state.program());
                }).toList();
                oldEntities = List.of(client.player.getUUID(), dropped.get(0)).stream().map(id -> {
                    var state = manager.getPlaybackState(PlaybackOwnerKey.entity(dimension, id)).orElseThrow();
                    return new ClientboundBoomboxStatePacket(dimension, find(client, id).getId(), id, state);
                }).toList();
                submit(client, player -> {
                    var savedStartTicks = List.of(0, 1).stream().map(i -> ((JukeboxBlockEntity) player.serverLevel()
                            .getBlockEntity(pos.above(i * 2))).saveWithoutMetadata().getLong("RecordStartTick")).toList();
                    beforeClose = ServerPlaybackClock.get(player.serverLevel()).next();
                    startTicks = savedStartTicks; // Publish readiness only after the clock boundary is allocated.
                });
                advance();
            }
            case 2 -> {
                if (startTicks.size() != 2) {
                    return false;
                }
                advance(); // clearLevel and loadLevel may pump client events; do not recursively close again.
                client.level.disconnect();
                client.clearLevel(new TitleScreen()); // Waits for the integrated server save and shutdown.
                if (client.level != null || !oldServer.isShutdown()
                        || oldBlocks.stream().anyMatch(packet -> manager.getPlaybackState(
                                PlaybackOwnerKey.block(dimension, packet.pos())).isPresent())
                        || oldEntities.stream().anyMatch(packet -> manager.getPlaybackState(
                                PlaybackOwnerKey.entity(dimension, packet.owner())).isPresent())) {
                    throw new AssertionError("World close retained playback or did not finish saving");
                }
                try {
                    var data = NbtIo.readCompressed(clockFile.toFile()).getCompound("data");
                    if (!data.contains("Revision", Tag.TAG_LONG)) {
                        throw new AssertionError("World close did not persist the typed shared playback clock");
                    }
                    diskRevision = data.getLong("Revision");
                    if (diskRevision != beforeClose && !PlaybackRevision.isNewer(diskRevision, beforeClose)) {
                        throw new AssertionError("Saved shared clock rolled back at world close");
                    }
                } catch (IOException exception) {
                    throw new IllegalStateException("Could not read the saved world clock", exception);
                }
                client.createWorldOpenFlows().loadLevel(new TitleScreen(), world);
            }
            case 3 -> {
                if (client.level == null || client.player == null || client.getSingleplayerServer() == null
                        || client.getSingleplayerServer() == oldServer || !oldServer.isShutdown()
                        || !client.level.dimension().equals(dimension) || dropped.stream().anyMatch(id -> find(client, id) == null)
                        || !positiveKeys(client).stream().allMatch(manager::isPlaying)) {
                    return false;
                }
                var keys = positiveKeys(client);
                for (int i = 0; i < keys.size(); i++) {
                    var state = manager.getPlaybackState(keys.get(i)).orElseThrow();
                    var expected = i < 2 ? oldBlocks.get(i).program() : oldEntities.get(i - 2).state().program();
                    if (!PlaybackRevision.isNewer(state.revision(), diskRevision) || !state.program().equals(expected)) {
                        throw new AssertionError("Reopened owner reset its clock or lost its persisted program: " + keys.get(i));
                    }
                }
                assertSilent(client, manager);
                restored = keys.stream().map(key -> manager.getPlaybackState(key).orElseThrow()).toList();
                submit(client, player -> {
                    if (!PlaybackRevision.isNewer(ServerPlaybackClock.get(player.serverLevel()).current(), diskRevision)) {
                        throw new AssertionError("Reopened server did not resume the persisted global allocator");
                    }
                    for (int i = 0; i < 2; i++) {
                        var box = (JukeboxBlockEntity) player.serverLevel().getBlockEntity(pos.above(i * 2));
                        if (!box.isRecordPlaying() || box.saveWithoutMetadata().getLong("RecordStartTick") != startTicks.get(i)) {
                            throw new AssertionError("Reopen snapshot restarted the saved server jukebox duration");
                        }
                    }
                    for (var packet : oldBlocks) {
                        send(player, ClientboundPlayMusicPacket.stopped(dimension, packet.pos(), packet.revision()));
                        player.connection.send(new ClientboundLevelEventPacket(1010, packet.pos(), packet.itemId(), false));
                        send(player, packet); // Valid ticket/HAS_RECORD: an old revision must still be rejected.
                    }
                    for (var packet : oldEntities) {
                        // Use the restored entity ID so rejection cannot be explained by a stale incarnation ID alone.
                        int id = player.serverLevel().getEntity(packet.owner()).getId();
                        send(player, new ClientboundBoomboxStatePacket(dimension, id, packet.owner(),
                                new PlaybackState(packet.state().revision(), Optional.empty(), false)));
                        send(player, new ClientboundBoomboxStatePacket(dimension, id, packet.owner(), packet.state()));
                    }
                    serverChecked = true;
                });
                advance();
            }
            case 4 -> {
                if (ticks < 20 || !serverChecked) {
                    return false;
                }
                var keys = positiveKeys(client);
                for (int i = 0; i < keys.size(); i++) {
                    if (manager.getPlaybackState(keys.get(i)).orElse(null) != restored.get(i) || !manager.isPlaying(keys.get(i))) {
                        throw new AssertionError("Old publication mutated a fresh reopened session: " + keys.get(i));
                    }
                }
                assertSilent(client, manager);
                submit(client, player -> {
                    for (int i = 0; i < 5; i++) {
                        var at = pos.above(i * 2);
                        ((JukeboxBlockEntity) player.serverLevel().getBlockEntity(at)).removeFirstItem();
                        player.serverLevel().setBlockAndUpdate(at, Blocks.AIR.defaultBlockState());
                    }
                    dropped.forEach(id -> player.serverLevel().getEntity(id).discard());
                    var itemPos = pos.above(12);
                    for (var at : BlockPos.betweenClosed(itemPos.offset(-1, -1, -1), itemPos.offset(1, 1, 1))) {
                        player.serverLevel().setBlockAndUpdate(at, Blocks.AIR.defaultBlockState());
                    }
                });
                advance();
            }
            case 5 -> {
                if (oldBlocks.stream().anyMatch(packet -> manager.getPlaybackState(PlaybackOwnerKey.block(dimension, packet.pos())).isPresent())
                        || dropped.stream().anyMatch(id -> find(client, id) != null
                        || manager.getPlaybackState(PlaybackOwnerKey.entity(dimension, id)).isPresent())) {
                    return false;
                }
                if (manager.getPlaybackState(PlaybackOwnerKey.entity(dimension, client.player.getUUID())).orElse(null) != restored.get(2)) {
                    throw new AssertionError("Reopen fixture cleanup changed the held boombox session");
                }
                System.out.println("ETCHED WORLD SAVE CLOSE REOPEN PERSISTED CLOCK JUKEBOX AND BOOMBOX SMOKE PASSED");
                advance();
                return true;
            }
            default -> throw new AssertionError("Unknown world reopen smoke step " + step);
        }
        return false;
    }

    private static List<PlaybackOwnerKey> positiveKeys(Minecraft client) {
        return List.of(PlaybackOwnerKey.block(dimension, pos), PlaybackOwnerKey.block(dimension, pos.above(2)),
                PlaybackOwnerKey.entity(dimension, client.player.getUUID()), PlaybackOwnerKey.entity(dimension, dropped.get(0)));
    }

    private static void assertSilent(Minecraft client, AudioPlaybackManager manager) {
        var nativeRecords = ((LevelRendererAccessor) client.levelRenderer).getPlayingRecords();
        for (int i = 2; i < 5; i++) {
            var at = pos.above(i * 2);
            if (manager.getPlaybackState(PlaybackOwnerKey.block(dimension, at)).isPresent() || nativeRecords.containsKey(at)) {
                throw new AssertionError("Reopen auto-started a stopped/invalid/native jukebox");
            }
        }
        for (var id : dropped.subList(1, 3)) {
            if (manager.getPlaybackState(PlaybackOwnerKey.entity(dimension, id)).isPresent()) {
                throw new AssertionError("Reopen auto-started a paused/invalid dropped boombox");
            }
        }
    }

    private static Entity find(Minecraft client, UUID id) {
        if (client.player.getUUID().equals(id)) {
            return client.player;
        }
        for (var entity : client.level.entitiesForRendering()) {
            if (entity.getUUID().equals(id)) {
                return entity;
            }
        }
        return null;
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
