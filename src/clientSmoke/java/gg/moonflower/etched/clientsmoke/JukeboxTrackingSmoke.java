package gg.moonflower.etched.clientsmoke;

import gg.moonflower.etched.client.radio.AudioPlaybackManager;
import gg.moonflower.etched.client.radio.PlaybackOwnerKey;
import gg.moonflower.etched.common.audio.PlaybackRevision;
import gg.moonflower.etched.common.audio.ServerPlaybackClock;
import gg.moonflower.etched.core.mixin.client.LevelRendererAccessor;
import gg.moonflower.etched.core.registry.EtchedItems;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.JukeboxBlockEntity;
import net.minecraft.world.level.chunk.ChunkStatus;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.registries.ForgeRegistries;

import java.util.List;
import java.util.function.Consumer;

/** Real chunk unwatch/rewatch, restored BE load, and automatic directed snapshots over the integrated connection. */
final class JukeboxTrackingSmoke {

    private static int step;
    private static int ticks;
    private static Vec3 origin;
    private static volatile long beforeTracking;

    private JukeboxTrackingSmoke() {
    }

    static boolean tick(Minecraft client, BlockPos pos) {
        if (step == 4) {
            return true;
        }
        if (++ticks > 400) {
            throw new AssertionError("Jukebox tracking smoke timed out at step " + step);
        }
        var chunk = new ChunkPos(pos);
        var manager = AudioPlaybackManager.getInstance();
        var key = PlaybackOwnerKey.block(client.level.dimension(), pos);
        switch (step) {
            case 0 -> {
                origin = client.player.position();
                submit(client, player -> {
                    player.serverLevel().setChunkForced(chunk.x, chunk.z, true);
                    player.teleportTo(player.serverLevel(), origin.x + 256, origin.y, origin.z,
                            player.getYRot(), player.getXRot());
                });
                advance();
            }
            case 1 -> {
                if (client.level.getChunkSource().getChunk(chunk.x, chunk.z, ChunkStatus.FULL, false) != null) {
                    return false;
                }
                if (manager.getPlaybackState(key).isPresent()) {
                    if (ticks < 100) {
                        return false; // The client end-tick cleanup follows this smoke listener.
                    }
                    throw new AssertionError("Unwatched jukebox chunk retained its managed playback session");
                }
                submit(client, player -> {
                    var level = player.serverLevel();
                    var jukebox = (JukeboxBlockEntity) level.getBlockEntity(pos);
                    var restored = new JukeboxBlockEntity(pos, jukebox.getBlockState());
                    restored.load(jukebox.saveWithoutMetadata());
                    level.setBlockEntity(restored); // Exercise the transformed saved-playing restore, without startPlaying.
                    for (int offset : List.of(2, 4, 6, 8)) {
                        var fixturePos = pos.above(offset);
                        level.setBlockAndUpdate(fixturePos, Blocks.JUKEBOX.defaultBlockState());
                        var fixture = (JukeboxBlockEntity) level.getBlockEntity(fixturePos);
                        switch (offset) {
                            case 2 -> {
                                fixture.setFirstItem(new ItemStack(Items.MUSIC_DISC_CAT));
                                var saved = fixture.saveWithoutMetadata();
                                saved.putBoolean("IsPlaying", false);
                                fixture.load(saved);
                            }
                            case 4 -> fixture.setFirstItem(new ItemStack(EtchedItems.ALBUM_COVER.get()));
                            case 6 -> fixture.setFirstItem(new ItemStack(ForgeRegistries.ITEMS.getValue(UnsupportedSmokeRecord.FOREIGN_DISC_ID)));
                            case 8 -> fixture.setFirstItem(new ItemStack(Items.MUSIC_DISC_BLOCKS));
                            default -> throw new AssertionError("Unknown tracking fixture");
                        }
                    }
                    beforeTracking = ServerPlaybackClock.get(level).current();
                    player.teleportTo(level, origin.x, origin.y, origin.z, player.getYRot(), player.getXRot());
                });
                advance();
            }
            case 2 -> {
                var restored = manager.getPlaybackState(key);
                var vanillaKey = PlaybackOwnerKey.block(client.level.dimension(), pos.above(8));
                var vanilla = manager.getPlaybackState(vanillaKey);
                if (client.level.getChunkSource().getChunk(chunk.x, chunk.z, ChunkStatus.FULL, false) == null
                        || restored.isEmpty() || vanilla.isEmpty()
                        || !manager.isPlaying(key) || !manager.isPlaying(vanillaKey)) {
                    return false;
                }
                if (!PlaybackRevision.isNewer(restored.orElseThrow().revision(), beforeTracking)
                        || !PlaybackRevision.isNewer(vanilla.orElseThrow().revision(), beforeTracking)
                        || !"minecraft:music_disc.cat".equals(restored.orElseThrow().program().orElseThrow().tracks().get(0).source())
                        || !"minecraft:music_disc.blocks".equals(vanilla.orElseThrow().program().orElseThrow().tracks().get(0).source())) {
                    throw new AssertionError("Chunk watch did not publish authoritative custom/restored and actual vanilla snapshots");
                }
                advance();
            }
            case 3 -> {
                if (ticks < 20) {
                    return false;
                }
                var nativeRecords = ((LevelRendererAccessor) client.levelRenderer).getPlayingRecords();
                for (int offset : List.of(2, 4, 6)) {
                    var fixturePos = pos.above(offset);
                    if (manager.getPlaybackState(PlaybackOwnerKey.block(client.level.dimension(), fixturePos)).isPresent()
                            || nativeRecords.containsKey(fixturePos)) {
                        throw new AssertionError("Chunk snapshot auto-started stopped/invalid/native disc at " + fixturePos);
                    }
                }
                submit(client, player -> {
                    for (int offset : List.of(2, 4, 6, 8)) {
                        var fixturePos = pos.above(offset);
                        ((JukeboxBlockEntity) player.serverLevel().getBlockEntity(fixturePos)).removeFirstItem();
                        player.serverLevel().setBlockAndUpdate(fixturePos, Blocks.AIR.defaultBlockState());
                    }
                    player.serverLevel().setChunkForced(chunk.x, chunk.z, false);
                });
                System.out.println("ETCHED JUKEBOX CHUNK UNTRACK RETRACK AND SAVED PLAYING SNAPSHOT SMOKE PASSED");
                advance();
                return true;
            }
            default -> throw new AssertionError("Unknown jukebox tracking smoke step " + step);
        }
        return false;
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
}
