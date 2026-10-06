package gg.moonflower.etched.clientsmoke;

import gg.moonflower.etched.client.radio.AudioPlaybackManager;
import gg.moonflower.etched.client.radio.PlaybackOwnerKey;
import gg.moonflower.etched.client.radio.sound.RecordSoundInstance;
import gg.moonflower.etched.common.audio.PlaybackState;
import gg.moonflower.etched.common.audio.ServerPlaybackClock;
import gg.moonflower.etched.common.item.JukeboxRecordSupport;
import gg.moonflower.etched.common.network.EtchedMessages;
import gg.moonflower.etched.common.network.play.ClientboundPlayMusicPacket;
import gg.moonflower.etched.core.mixin.client.LevelRendererAccessor;
import net.minecraft.client.Minecraft;
import net.minecraft.client.resources.sounds.SoundInstance;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.RecordItem;
import net.minecraft.world.level.block.entity.JukeboxBlockEntity;
import net.minecraftforge.network.PacketDistributor;
import net.minecraftforge.registries.ForgeRegistries;

import java.util.List;
import java.util.function.Consumer;

/** Actual vanilla-disc packets and third-party native handoff on the integrated server connection. */
final class NativeJukeboxRevisionSmoke {

    private static int step;
    private static int ticks;
    private static volatile long serverRevision;
    private static PlaybackState stableState;
    private static SoundInstance nativeSound;

    private NativeJukeboxRevisionSmoke() {
    }

    static boolean tick(Minecraft client, BlockPos pos, ItemStack customDisc) {
        if (step == 7) {
            return true;
        }
        if (++ticks > 200) {
            throw new AssertionError("Native jukebox revision smoke timed out at step " + step);
        }
        var manager = AudioPlaybackManager.getInstance();
        var key = PlaybackOwnerKey.block(client.level.dimension(), pos);
        var records = ((LevelRendererAccessor) client.levelRenderer).getPlayingRecords();
        switch (step) {
            case 0 -> {
                submit(client, player -> {
                    var jukebox = (JukeboxBlockEntity) player.serverLevel().getBlockEntity(pos);
                    jukebox.popOutRecord();
                    jukebox.setFirstItem(new ItemStack(Items.MUSIC_DISC_CAT));
                    serverRevision = ServerPlaybackClock.get(player.serverLevel()).current();
                });
                advance();
            }
            case 1 -> {
                var state = manager.getPlaybackState(key);
                if (state.isEmpty() || state.orElseThrow().revision() != serverRevision || !manager.isPlaying(key)) {
                    return false;
                }
                stableState = state.orElseThrow();
                requireSource(stableState, "minecraft:music_disc.cat");
                if (records.containsKey(pos)) {
                    throw new AssertionError("Managed vanilla disc also installed a native sound-map wrapper");
                }
                // Direct first-party calls must not create a local-revision fallback or retire accepted state.
                client.levelRenderer.playStreamingMusic(SoundEvents.MUSIC_DISC_CAT, pos, (RecordItem) Items.MUSIC_DISC_CAT);
                submit(client, player -> {
                    var level = player.serverLevel();
                    for (Item item : List.of(Items.MUSIC_DISC_CAT, Items.MUSIC_DISC_BLOCKS)) {
                        level.levelEvent(null, 1010, pos, Item.getId(item));
                        send(player, ClientboundPlayMusicPacket.fromRecord(level.dimension(), pos, serverRevision, new ItemStack(item)));
                    }
                    send(player, ClientboundPlayMusicPacket.stopped(level.dimension(), pos, serverRevision - 1L));
                });
                advance();
            }
            case 2 -> {
                if (ticks < 20) {
                    return false;
                }
                if (manager.getPlaybackState(key).orElseThrow() != stableState || records.containsKey(pos)) {
                    throw new AssertionError("Unrevisioned/direct, duplicate or conflicting vanilla start mutated the accepted owner");
                }
                submit(client, player -> {
                    var level = player.serverLevel();
                    var jukebox = (JukeboxBlockEntity) level.getBlockEntity(pos);
                    long oldRevision = serverRevision;
                    jukebox.popOutRecord();
                    jukebox.setFirstItem(new ItemStack(Items.MUSIC_DISC_BLOCKS));
                    serverRevision = ServerPlaybackClock.get(level).current();
                    send(player, ClientboundPlayMusicPacket.stopped(level.dimension(), pos, oldRevision));
                    level.levelEvent(null, 1010, pos, Item.getId(Items.MUSIC_DISC_CAT));
                    send(player, ClientboundPlayMusicPacket.fromRecord(level.dimension(), pos, oldRevision, new ItemStack(Items.MUSIC_DISC_CAT)));
                });
                advance();
            }
            case 3 -> {
                var state = manager.getPlaybackState(key);
                if (ticks < 20 || state.isEmpty() || state.orElseThrow().revision() != serverRevision || !manager.isPlaying(key)) {
                    return false;
                }
                requireSource(state.orElseThrow(), "minecraft:music_disc.blocks");
                if (records.containsKey(pos)) {
                    throw new AssertionError("Vanilla disc replacement started a second playback path");
                }
                submit(client, player -> {
                    var level = player.serverLevel();
                    var jukebox = (JukeboxBlockEntity) level.getBlockEntity(pos);
                    Item foreign = ForgeRegistries.ITEMS.getValue(UnsupportedSmokeRecord.FOREIGN_DISC_ID);
                    if (!(foreign instanceof RecordItem) || JukeboxRecordSupport.requiresPlaybackPacket(foreign)) {
                        throw new AssertionError("Native foreign disc fixture was classified as managed");
                    }
                    jukebox.popOutRecord();
                    long beforeForeign = ServerPlaybackClock.get(level).current();
                    jukebox.setFirstItem(new ItemStack(foreign));
                    if (!jukebox.isRecordPlaying() || ServerPlaybackClock.get(level).current() != beforeForeign) {
                        throw new AssertionError("Third-party disc did not retain its native server lifecycle");
                    }
                });
                advance();
            }
            case 4 -> {
                SoundInstance sound = records.get(pos);
                if (!(sound instanceof RecordSoundInstance) || !client.getSoundManager().isActive(sound)) {
                    return false;
                }
                if (manager.getPlaybackState(key).isPresent()) {
                    throw new AssertionError("Native third-party replacement retained the managed vanilla owner");
                }
                nativeSound = sound;
                submit(client, player -> {
                    var level = player.serverLevel();
                    // Even a newer managed stop does not own a third-party native wrapper.
                    send(player, ClientboundPlayMusicPacket.stopped(level.dimension(), pos, ServerPlaybackClock.get(level).next()));
                    level.levelEvent(null, 1010, pos, Item.getId(Items.MUSIC_DISC_CAT));
                    send(player, ClientboundPlayMusicPacket.fromRecord(level.dimension(), pos, serverRevision, new ItemStack(Items.MUSIC_DISC_CAT)));
                });
                advance();
            }
            case 5 -> {
                if (ticks < 20) {
                    return false;
                }
                if (records.get(pos) != nativeSound || !client.getSoundManager().isActive(nativeSound)
                        || manager.getPlaybackState(key).isPresent()) {
                    throw new AssertionError("Late managed start/stop closed the native third-party replacement");
                }
                submit(client, player -> {
                    var level = player.serverLevel();
                    var jukebox = (JukeboxBlockEntity) level.getBlockEntity(pos);
                    jukebox.popOutRecord();
                    jukebox.setFirstItem(customDisc.copy());
                    serverRevision = ServerPlaybackClock.get(level).current();
                });
                advance();
            }
            case 6 -> {
                var state = manager.getPlaybackState(key);
                if (ticks < 20 || state.isEmpty() || state.orElseThrow().revision() != serverRevision || !manager.isPlaying(key)) {
                    return false;
                }
                requireSource(state.orElseThrow(), "minecraft:music_disc.cat");
                if (records.containsKey(pos) || client.getSoundManager().isActive(nativeSound)) {
                    throw new AssertionError("Native wrapper survived the return to a managed custom disc");
                }
                System.out.println("ETCHED VANILLA JUKEBOX SERVER REVISION AND NATIVE THIRD-PARTY HANDOFF SMOKE PASSED");
                advance();
                return true;
            }
            default -> throw new AssertionError("Unknown native jukebox smoke step " + step);
        }
        return false;
    }

    private static void advance() {
        step++;
        ticks = 0;
    }

    private static void requireSource(PlaybackState state, String expected) {
        if (!state.program().orElseThrow().tracks().get(0).source().equals(expected)) {
            throw new AssertionError("Wrong native jukebox program: " + state);
        }
    }

    private static void submit(Minecraft client, Consumer<ServerPlayer> action) {
        var server = client.getSingleplayerServer();
        var playerId = client.player.getUUID();
        server.execute(() -> action.accept(server.getPlayerList().getPlayer(playerId)));
    }

    private static void send(ServerPlayer player, ClientboundPlayMusicPacket packet) {
        EtchedMessages.PLAY.send(PacketDistributor.PLAYER.with(() -> player), packet);
    }
}
