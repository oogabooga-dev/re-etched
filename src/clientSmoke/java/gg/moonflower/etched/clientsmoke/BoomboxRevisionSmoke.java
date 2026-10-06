package gg.moonflower.etched.clientsmoke;

import gg.moonflower.etched.client.radio.AudioPlaybackManager;
import gg.moonflower.etched.client.radio.BoomboxPlayback;
import gg.moonflower.etched.client.radio.PlaybackOwnerKey;
import gg.moonflower.etched.common.audio.AudioProgram;
import gg.moonflower.etched.common.audio.AudioTrack;
import gg.moonflower.etched.common.audio.BoomboxServerPlayback;
import gg.moonflower.etched.common.audio.PlaybackState;
import gg.moonflower.etched.common.audio.ServerPlaybackClock;
import gg.moonflower.etched.common.item.BoomboxItem;
import gg.moonflower.etched.common.network.EtchedMessages;
import gg.moonflower.etched.common.network.play.ClientboundBoomboxStatePacket;
import gg.moonflower.etched.core.registry.EtchedItems;
import net.minecraft.client.Minecraft;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.Level;
import net.minecraftforge.network.PacketDistributor;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Consumer;

/** Real packet ordering, source guards and directed snapshots, without client-generated revisions. */
final class BoomboxRevisionSmoke {

    private static int step;
    private static int ticks;
    private static PlaybackState stable;
    private static volatile ClientboundBoomboxStatePacket early;
    private static volatile ItemEntity future;
    private static volatile long snapshotRevision;

    private BoomboxRevisionSmoke() {
    }

    static boolean tick(Minecraft client) {
        if (step == 5) {
            return true;
        }
        if (++ticks > 200) {
            throw new AssertionError("Boombox revision smoke timed out at step " + step);
        }
        var manager = AudioPlaybackManager.getInstance();
        var key = PlaybackOwnerKey.entity(client.level.dimension(), client.player.getUUID());
        switch (step) {
            case 0 -> {
                stable = manager.getPlaybackState(key).orElseThrow();
                if (stable.revision() == 0L) {
                    throw new AssertionError("Held boombox still synthesized its initial local revision");
                }
                submit(client, player -> {
                    var level = player.serverLevel();
                    var conflict = new AudioProgram(AudioProgram.Kind.FINITE, List.of(new AudioTrack(
                            AudioTrack.SourceType.SOUND_EVENT, "minecraft:music_disc.blocks", "", "Conflict")));
                    for (var state : List.of(stable, new PlaybackState(stable.revision(), Optional.of(conflict), true),
                            new PlaybackState(stable.revision() - 1L, Optional.empty(), false))) {
                        send(player, new ClientboundBoomboxStatePacket(level.dimension(), player.getId(), player.getUUID(), state));
                    }
                    send(player, new ClientboundBoomboxStatePacket(Level.NETHER, player.getId(), player.getUUID(),
                            new PlaybackState(Long.MAX_VALUE, stable.program(), true)));
                    send(player, new ClientboundBoomboxStatePacket(level.dimension(), player.getId(), UUID.randomUUID(),
                            new PlaybackState(ServerPlaybackClock.get(level).next(), stable.program(), true)));
                    ItemStack boombox = new ItemStack(EtchedItems.BOOMBOX.get());
                    BoomboxItem.setRecord(boombox, new ItemStack(Items.MUSIC_DISC_CAT));
                    future = new ItemEntity(level, player.getX() + 6, player.getY(), player.getZ(), boombox);
                    early = new ClientboundBoomboxStatePacket(level.dimension(), future.getId(), future.getUUID(),
                            new PlaybackState(ServerPlaybackClock.get(level).next(), stable.program(), true));
                    send(player, early); // No spawn packet/entity yet: this must be bounded waiting, not playback.
                });
                advance();
            }
            case 1 -> {
                if (ticks < 20 || early == null) {
                    return false;
                }
                if (manager.getPlaybackState(key).orElseThrow() != stable) {
                    throw new AssertionError("Duplicate/conflicting/stale/foreign-dimension/UUID packets mutated the held session");
                }
                if (BoomboxPlayback.getInstance().receive(early)
                        || manager.getPlaybackState(PlaybackOwnerKey.entity(early.dimension(), early.owner())).isPresent()) {
                    throw new AssertionError("Early entity state was not retained as a pending authoritative publication");
                }
                submit(client, player -> player.serverLevel().addFreshEntity(future));
                advance();
            }
            case 2 -> {
                var futureKey = PlaybackOwnerKey.entity(early.dimension(), early.owner());
                if (!manager.isPlaying(futureKey)) {
                    return false;
                }
                if (manager.getPlaybackState(futureKey).orElseThrow().revision() <= early.state().revision()) {
                    throw new AssertionError("Spawn/tracking did not supersede the early publication with server state");
                }
                submit(client, player -> {
                    BoomboxServerPlayback.sendSnapshot(player, player);
                    snapshotRevision = ServerPlaybackClock.get(player.serverLevel()).current();
                    future.discard();
                    ItemStack disc = BoomboxItem.getRecord(player.getMainHandItem());
                    disc.getOrCreateTag().putString("Cosmetic", "unchanged-program");
                    BoomboxItem.setRecord(player.getMainHandItem(), disc);
                    long before = ServerPlaybackClock.get(player.serverLevel()).current();
                    BoomboxServerPlayback.observe(player);
                    if (ServerPlaybackClock.get(player.serverLevel()).current() != before) {
                        throw new AssertionError("Cosmetic record NBT allocated another server revision");
                    }
                });
                advance();
            }
            case 3 -> {
                var state = manager.getPlaybackState(key);
                if (state.isEmpty() || state.orElseThrow().revision() != snapshotRevision || !manager.isPlaying(key)
                        || manager.getPlaybackState(PlaybackOwnerKey.entity(early.dimension(), early.owner())).isPresent()) {
                    return false;
                }
                stable = state.orElseThrow();
                advance();
            }
            case 4 -> {
                if (ticks < 20) {
                    return false;
                }
                if (manager.getPlaybackState(key).orElseThrow() != stable) {
                    throw new AssertionError("Unchanged equipment/program restarted after its directed server snapshot");
                }
                System.out.println("ETCHED BOOMBOX SERVER REVISION AND EARLY ENTITY SNAPSHOT SMOKE PASSED");
                advance();
                return true;
            }
            default -> throw new AssertionError("Unknown boombox smoke step " + step);
        }
        return false;
    }

    private static void advance() {
        step++;
        ticks = 0;
    }

    private static void submit(Minecraft client, Consumer<ServerPlayer> action) {
        var server = client.getSingleplayerServer();
        UUID playerId = client.player.getUUID();
        server.execute(() -> action.accept(server.getPlayerList().getPlayer(playerId)));
    }

    private static void send(ServerPlayer player, ClientboundBoomboxStatePacket packet) {
        EtchedMessages.PLAY.send(PacketDistributor.PLAYER.with(() -> player), packet);
    }
}
