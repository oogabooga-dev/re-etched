package gg.moonflower.etched.clientsmoke;

import gg.moonflower.etched.api.record.TrackData;
import gg.moonflower.etched.api.sound.SoundTracker;
import gg.moonflower.etched.api.sound.SoundStopListener;
import gg.moonflower.etched.client.radio.AudioPlaybackManager;
import gg.moonflower.etched.client.radio.BoomboxPlayback;
import gg.moonflower.etched.client.radio.PlaybackOwnerKey;
import gg.moonflower.etched.common.audio.AudioProgram;
import gg.moonflower.etched.common.audio.AudioTrack;
import gg.moonflower.etched.common.audio.PlaybackState;
import gg.moonflower.etched.common.item.EtchedMusicDiscItem;
import gg.moonflower.etched.common.item.BoomboxItem;
import gg.moonflower.etched.common.item.RecordContentResolver;
import gg.moonflower.etched.common.network.EtchedMessages;
import gg.moonflower.etched.common.network.play.ClientboundPlayMusicPacket;
import gg.moonflower.etched.core.registry.EtchedItems;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.TitleScreen;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.Difficulty;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.level.GameRules;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.LevelSettings;
import net.minecraft.world.level.WorldDataConfiguration;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.JukeboxBlock;
import net.minecraft.world.level.levelgen.WorldOptions;
import net.minecraft.world.level.levelgen.presets.WorldPresets;
import net.minecraftforge.network.PacketDistributor;
import net.minecraftforge.registries.ForgeRegistries;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** Opt-in end-to-end check of jukebox packet ordering and entity-owned boombox playback. */
final class JukeboxPacketSmoke {

    private static final String WORLD = "etched-jukebox-smoke-" + UUID.randomUUID();
    private static final long DEADLINE = System.nanoTime() + 180_000_000_000L;
    private static final ItemStack A = disc("minecraft:music_disc.blocks", "A");
    private static final ItemStack B = disc("minecraft:music_disc.cat", "B");
    private static int step;
    private static int ticks;
    private static BlockPos pos;
    private static volatile UUID droppedId;

    private JukeboxPacketSmoke() {
    }

    static void tick(Minecraft client) {
        if (System.nanoTime() > DEADLINE) {
            throw new AssertionError("Jukebox packet smoke timed out at step " + step);
        }
        if (step == 0 && client.screen instanceof TitleScreen) {
            step = 1;
            client.createWorldOpenFlows().createFreshLevel(WORLD,
                    new LevelSettings(WORLD, GameType.CREATIVE, false, Difficulty.PEACEFUL, true,
                            new GameRules(), WorldDataConfiguration.DEFAULT),
                    WorldOptions.defaultWithRandomSeed(), WorldPresets::createNormalWorldDimensions);
        }
        if (step == 1 && client.level != null && client.player != null && client.getSingleplayerServer() != null) {
            step = 2;
            pos = client.player.blockPosition().offset(2, 0, 2);
            MinecraftServer server = client.getSingleplayerServer();
            ResourceKey<Level> dimension = client.level.dimension();
            server.execute(() -> {
                ServerLevel level = server.getLevel(dimension);
                level.setBlockAndUpdate(pos, Blocks.JUKEBOX.defaultBlockState().setValue(JukeboxBlock.HAS_RECORD, true));
            });
        }
        if (step == 2 && client.level != null && client.level.getBlockState(pos).is(Blocks.JUKEBOX)
                && client.level.getBlockState(pos).getValue(JukeboxBlock.HAS_RECORD)) {
            step = 3;
            ticks = 0;
            // All four messages traverse the integrated connection in this order. A's
            // legacy packet is intentionally held until B's start event is in flight.
            MinecraftServer server = client.getSingleplayerServer();
            ResourceKey<Level> dimension = client.level.dimension();
            UUID playerId = client.player.getUUID();
            server.execute(() -> {
                ServerLevel level = server.getLevel(dimension);
                ServerPlayer player = server.getPlayerList().getPlayer(playerId);
                int id = Item.getId(A.getItem());
                level.levelEvent(null, 1010, pos, id);
                level.levelEvent(null, 1011, pos, 0);
                level.levelEvent(null, 1010, pos, id);
                EtchedMessages.PLAY.send(PacketDistributor.PLAYER.with(() -> player), new ClientboundPlayMusicPacket(A, pos));
            });
        }
        if (step == 3 && ++ticks >= 40) {
            var key = PlaybackOwnerKey.block(client.level.dimension(), pos);
            if (AudioPlaybackManager.getInstance().getPlaybackState(key).isPresent()) {
                throw new AssertionError("Delayed A packet started playback after A was ejected");
            }
            step = 4;
            ticks = 0;
            MinecraftServer server = client.getSingleplayerServer();
            UUID playerId = client.player.getUUID();
            server.execute(() -> {
                ServerPlayer player = server.getPlayerList().getPlayer(playerId);
                EtchedMessages.PLAY.send(PacketDistributor.PLAYER.with(() -> player), new ClientboundPlayMusicPacket(B, pos));
            });
        }
        if (step == 4 && client.level != null) {
            var state = AudioPlaybackManager.getInstance().getPlaybackState(PlaybackOwnerKey.block(client.level.dimension(), pos));
            if (state.isPresent()) {
                String source = state.orElseThrow().program().orElseThrow().tracks().get(0).source();
                if (!"minecraft:music_disc.cat".equals(source)) {
                    throw new AssertionError("Expected B, got " + source);
                }
                step = 5;
                ticks = 0;
                var entityKey = PlaybackOwnerKey.entity(client.level.dimension(), client.player.getUUID());
                var track = new AudioTrack(AudioTrack.SourceType.SOUND_EVENT,
                        "minecraft:music_disc.cat", "Minecraft", "Entity sink smoke");
                if (!AudioPlaybackManager.getInstance().update(entityKey,
                        new PlaybackState(0L, Optional.of(new AudioProgram(AudioProgram.Kind.FINITE,
                                List.of(track))), true))) {
                    throw new AssertionError("Entity owner was not admitted to the shared manager");
                }
            } else if (++ticks >= 200) {
                throw new AssertionError("B packet did not start playback after the cancelled A slot");
            }
        }
        if (step == 5 && client.level != null) {
            var entityKey = PlaybackOwnerKey.entity(client.level.dimension(), client.player.getUUID());
            if (AudioPlaybackManager.getInstance().isPlaying(entityKey)) {
                AudioPlaybackManager.getInstance().remove(entityKey);
                step = 6;
                ticks = 0;
                MinecraftServer server = client.getSingleplayerServer();
                UUID playerId = client.player.getUUID();
                server.execute(() -> {
                    ServerPlayer player = server.getPlayerList().getPlayer(playerId);
                    ItemStack boombox = new ItemStack(EtchedItems.BOOMBOX.get());
                    BoomboxItem.setRecord(boombox, new ItemStack(Items.MUSIC_DISC_CAT));
                    player.setItemInHand(InteractionHand.MAIN_HAND, boombox);
                });
            } else if (++ticks >= 100) {
                throw new AssertionError("Entity sound sink did not begin local playback");
            }
        }
        if (step == 6 && client.level != null) {
            var entityKey = PlaybackOwnerKey.entity(client.level.dimension(), client.player.getUUID());
            if (client.player.getMainHandItem().is(EtchedItems.BOOMBOX.get())
                    && AudioPlaybackManager.getInstance().isPlaying(entityKey)) {
                var state = AudioPlaybackManager.getInstance().getPlaybackState(entityKey).orElseThrow();
                var program = state.program().orElseThrow();
                if (!"minecraft:music_disc.cat".equals(program.tracks().get(0).source())) {
                    throw new AssertionError("Boombox did not select the held disc");
                }
                if (state.revision() != 0L) {
                    throw new AssertionError("Unchanged boombox record restarted on a later tick");
                }
                if (++ticks < 20) {
                    return;
                }
                step = 7;
                ticks = 0;
                MinecraftServer server = client.getSingleplayerServer();
                UUID playerId = client.player.getUUID();
                server.execute(() -> {
                    ServerPlayer player = server.getPlayerList().getPlayer(playerId);
                    ItemStack boombox = player.getMainHandItem().copy();
                    BoomboxItem.setRecord(boombox, new ItemStack(Items.MUSIC_DISC_BLOCKS));
                    player.setItemInHand(InteractionHand.MAIN_HAND, boombox);
                });
            } else if (++ticks >= 100) {
                throw new AssertionError("Held boombox did not start managed playback");
            }
        }
        if (step == 7 && client.level != null) {
            var entityKey = PlaybackOwnerKey.entity(client.level.dimension(), client.player.getUUID());
            var state = AudioPlaybackManager.getInstance().getPlaybackState(entityKey);
            if (client.player.getMainHandItem().is(EtchedItems.BOOMBOX.get())
                    && state.isPresent() && state.orElseThrow().revision() == 1L
                    && "minecraft:music_disc.blocks".equals(state.orElseThrow().program()
                    .orElseThrow().tracks().get(0).source())
                    && AudioPlaybackManager.getInstance().isPlaying(entityKey)) {
                if (++ticks < 20) {
                    return;
                }
                step = 8;
                ticks = 0;
                MinecraftServer server = client.getSingleplayerServer();
                UUID playerId = client.player.getUUID();
                server.execute(() -> {
                    ServerPlayer player = server.getPlayerList().getPlayer(playerId);
                    ItemStack boombox = player.getMainHandItem().copy();
                    player.setItemInHand(InteractionHand.OFF_HAND, boombox);
                    player.setItemInHand(InteractionHand.MAIN_HAND, ItemStack.EMPTY);
                });
            } else if (++ticks >= 100) {
                throw new AssertionError("Replacing the held disc did not advance the boombox revision");
            }
        }
        if (step == 8 && client.level != null) {
            var entityKey = PlaybackOwnerKey.entity(client.level.dimension(), client.player.getUUID());
            if (client.player.getMainHandItem().isEmpty()
                    && client.player.getOffhandItem().is(EtchedItems.BOOMBOX.get())
                    && BoomboxItem.getPlayingHand(client.player) == InteractionHand.OFF_HAND
                    && AudioPlaybackManager.getInstance().isPlaying(entityKey)) {
                step = 9;
                ticks = 0;
                MinecraftServer server = client.getSingleplayerServer();
                UUID playerId = client.player.getUUID();
                server.execute(() -> {
                    ServerPlayer player = server.getPlayerList().getPlayer(playerId);
                    ItemStack boombox = player.getOffhandItem().copy();
                    BoomboxItem.setPaused(boombox, true);
                    player.setItemInHand(InteractionHand.OFF_HAND, boombox);
                });
            } else if (++ticks >= 100) {
                throw new AssertionError("Offhand boombox did not keep entity playback");
            }
        }
        if (step == 9 && client.level != null) {
            var entityKey = PlaybackOwnerKey.entity(client.level.dimension(), client.player.getUUID());
            if (BoomboxItem.isPaused(client.player.getOffhandItem())
                    && AudioPlaybackManager.getInstance().getPlaybackState(entityKey).isEmpty()) {
                step = 10;
                ticks = 0;
                MinecraftServer server = client.getSingleplayerServer();
                UUID playerId = client.player.getUUID();
                var dimension = client.level.dimension();
                server.execute(() -> {
                    ServerLevel level = server.getLevel(dimension);
                    ServerPlayer player = server.getPlayerList().getPlayer(playerId);
                    ItemStack boombox = new ItemStack(EtchedItems.BOOMBOX.get());
                    BoomboxItem.setRecord(boombox, new ItemStack(Items.MUSIC_DISC_BLOCKS));
                    ItemEntity dropped = new ItemEntity(level, player.getX() + 4, player.getY(),
                            player.getZ(), boombox);
                    droppedId = dropped.getUUID();
                    level.addFreshEntity(dropped);
                });
            } else if (++ticks >= 100) {
                throw new AssertionError("Pausing the offhand boombox did not stop playback");
            }
        }
        if (step == 10 && droppedId != null && client.level != null) {
            var entityKey = PlaybackOwnerKey.entity(client.level.dimension(), droppedId);
            if (AudioPlaybackManager.getInstance().isPlaying(entityKey)) {
                BoomboxPlayback.getInstance().clearAll();
                if (AudioPlaybackManager.getInstance().getPlaybackState(entityKey).isPresent()) {
                    throw new AssertionError("Clearing boombox owners left the dropped entity playing");
                }
                step = 11;
                ticks = 0;
            } else if (++ticks >= 100) {
                throw new AssertionError("Dropped boombox did not start managed playback");
            }
        }
        if (step == 11 && droppedId != null && client.level != null) {
            var entityKey = PlaybackOwnerKey.entity(client.level.dimension(), droppedId);
            // A still-present owner may start again after clearing the local runtime.
            if (AudioPlaybackManager.getInstance().isPlaying(entityKey)) {
                step = 12;
                ticks = 0;
                MinecraftServer server = client.getSingleplayerServer();
                UUID id = droppedId;
                var dimension = client.level.dimension();
                server.execute(() -> {
                    var dropped = server.getLevel(dimension).getEntity(id);
                    if (dropped != null) {
                        dropped.discard();
                    }
                });
            } else if (++ticks >= 100) {
                throw new AssertionError("Boombox did not recover after clearing active owners");
            }
        }
        if (step == 12 && client.level != null) {
            var entityKey = PlaybackOwnerKey.entity(client.level.dimension(), droppedId);
            if (AudioPlaybackManager.getInstance().getPlaybackState(entityKey).isEmpty()) {
                Item record = ForgeRegistries.ITEMS.getValue(LegacySmokeRecord.ID);
                if (!(record instanceof LegacySmokeRecord)
                        || RecordContentResolver.resolve(new ItemStack(record)).isPresent()) {
                    throw new AssertionError("Client smoke fallback record was not registered as a third-party item");
                }
                step = 13;
                ticks = 0;
                MinecraftServer server = client.getSingleplayerServer();
                UUID playerId = client.player.getUUID();
                server.execute(() -> {
                    ServerPlayer player = server.getPlayerList().getPlayer(playerId);
                    ItemStack boombox = player.getOffhandItem().copy();
                    BoomboxItem.setRecord(boombox, new ItemStack(record));
                    BoomboxItem.setPaused(boombox, false);
                    player.setItemInHand(InteractionHand.OFF_HAND, boombox);
                });
            } else if (++ticks >= 100) {
                throw new AssertionError("Removing a dropped boombox did not stop playback");
            }
        }
        if (step == 13 && client.level != null) {
            var entityKey = PlaybackOwnerKey.entity(client.level.dimension(), client.player.getUUID());
            if (client.player.getOffhandItem().is(EtchedItems.BOOMBOX.get())
                    && SoundTracker.getEntitySound(client.player.getId()) != null
                    && BoomboxPlayback.getInstance().isPlaying(client.player)) {
                if (AudioPlaybackManager.getInstance().getPlaybackState(entityKey).isPresent()) {
                    throw new AssertionError("Third-party record incorrectly entered managed playback");
                }
                // Queue a completion from off-thread, then retire its sound before the client
                // thread handles it. The old callback must not start another legacy track.
                SoundStopListener sound = (SoundStopListener) SoundTracker.getEntitySound(client.player.getId());
                Thread completion = new Thread(sound::onStop, "etched-smoke-legacy-completion");
                completion.start();
                try {
                    completion.join(2000L);
                } catch (InterruptedException exception) {
                    Thread.currentThread().interrupt();
                    throw new IllegalStateException("Interrupted while queuing legacy completion", exception);
                }
                if (completion.isAlive()) {
                    throw new AssertionError("Legacy completion was not queued");
                }
                BoomboxItem.setPaused(client.player.getOffhandItem(), true);
                BoomboxPlayback.getInstance().update(client.player, ItemStack.EMPTY);
                step = 14;
                ticks = 0;
                MinecraftServer server = client.getSingleplayerServer();
                UUID playerId = client.player.getUUID();
                server.execute(() -> {
                    ServerPlayer player = server.getPlayerList().getPlayer(playerId);
                    ItemStack boombox = player.getOffhandItem().copy();
                    BoomboxItem.setPaused(boombox, true);
                    player.setItemInHand(InteractionHand.OFF_HAND, boombox);
                });
            } else if (++ticks >= 100) {
                throw new AssertionError("Third-party record did not start legacy playback");
            }
        }
        if (step == 14 && client.level != null) {
            var entityKey = PlaybackOwnerKey.entity(client.level.dimension(), client.player.getUUID());
            if (BoomboxItem.isPaused(client.player.getOffhandItem())
                    && SoundTracker.getEntitySound(client.player.getId()) == null
                    && !BoomboxPlayback.getInstance().isPlaying(client.player)
                    && AudioPlaybackManager.getInstance().getPlaybackState(entityKey).isEmpty()) {
                if (++ticks < 20) {
                    return;
                }
                try {
                    Files.writeString(Path.of("etched-jukebox-packet-smoke-success"), WORLD + "\n");
                } catch (IOException exception) {
                    throw new IllegalStateException("Could not record jukebox smoke result", exception);
                }
                System.out.println("ETCHED JUKEBOX PACKET SMOKE PASSED");
                System.out.println("ETCHED ENTITY SOUND SINK SMOKE PASSED");
                System.out.println("ETCHED BOOMBOX REPLACEMENT AND OFFHAND SMOKE PASSED");
                System.out.println("ETCHED DROPPED BOOMBOX CLEANUP SMOKE PASSED");
                System.out.println("ETCHED BOOMBOX CLEAR ALL SMOKE PASSED");
                System.out.println("ETCHED THIRD-PARTY BOOMBOX FALLBACK AND LATE STOP SMOKE PASSED");
                step = 15;
                client.stop();
            } else if (++ticks >= 100) {
                throw new AssertionError("Pausing a third-party record did not stop legacy playback");
            }
        }
    }

    private static ItemStack disc(String sound, String title) {
        ItemStack stack = new ItemStack(EtchedItems.ETCHED_MUSIC_DISC.get());
        EtchedMusicDiscItem.setMusic(stack, new TrackData(sound, "Minecraft", Component.literal(title)));
        return stack;
    }
}
