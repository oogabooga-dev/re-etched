package gg.moonflower.etched.clientsmoke;

import gg.moonflower.etched.api.record.TrackData;
import gg.moonflower.etched.api.sound.SoundTracker;
import gg.moonflower.etched.client.radio.sound.PlaybackStopListener;
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
import gg.moonflower.etched.core.Etched;
import gg.moonflower.etched.core.registry.EtchedItems;
import net.minecraft.client.Minecraft;
import net.minecraft.client.model.HumanoidModel;
import net.minecraft.client.model.geom.ModelLayers;
import net.minecraft.client.gui.screens.TitleScreen;
import net.minecraft.client.renderer.item.ItemProperties;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.contents.TranslatableContents;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.Difficulty;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.HumanoidArm;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.animal.Parrot;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.TooltipFlag;
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
import net.minecraft.world.level.portal.PortalInfo;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.common.util.ITeleporter;
import net.minecraftforge.network.PacketDistributor;
import net.minecraftforge.registries.ForgeRegistries;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Function;

/** Opt-in end-to-end check of jukebox packet ordering and entity-owned boombox playback. */
final class JukeboxPacketSmoke {

    private static final String WORLD = "etched-jukebox-smoke-" + UUID.randomUUID();
    private static final long DEADLINE = System.nanoTime() + 180_000_000_000L;
    private static final ItemStack A = disc("minecraft:music_disc.blocks", "A");
    private static final ItemStack B = disc("minecraft:music_disc.cat", "B");
    private static final ITeleporter SMOKE_TELEPORTER = new ITeleporter() {
        @Override
        public PortalInfo getPortalInfo(Entity owner, ServerLevel target,
                                        Function<ServerLevel, PortalInfo> fallback) {
            return new PortalInfo(new Vec3(0.5, 80, 0.5), Vec3.ZERO,
                    owner.getYRot(), owner.getXRot());
        }
    };
    private static int step;
    private static int ticks;
    private static int stableTicks;
    private static BlockPos pos;
    private static volatile UUID droppedId;
    private static volatile UUID standId;
    private static UUID travellingPlayerId;
    private static Parrot parrot;

    private JukeboxPacketSmoke() {
    }

    static void tick(Minecraft client) {
        if (System.nanoTime() > DEADLINE) {
            throw new AssertionError("Jukebox packet smoke timed out at step " + step);
        }
        if (step == 0 && client.screen instanceof TitleScreen) {
            step = 1;
            // Limit chunks to save when the opt-in test leaves its integrated world.
            client.options.renderDistance().set(2);
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
                assertPlayingModel(client, client.player, client.player.getMainHandItem(), 1.0F);
                if (ticks == 0) {
                    assertPlayingArm(client, InteractionHand.MAIN_HAND, true);
                    assertPlayingArm(client, InteractionHand.OFF_HAND, false);
                    assertBoomboxTooltip(client, client.player.getMainHandItem(), false);
                    assertParrotDances(client, true);
                }
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
                assertPlayingModel(client, client.player, client.player.getOffhandItem(), 1.0F);
                assertPlayingArm(client, InteractionHand.OFF_HAND, true);
                assertPlayingArm(client, InteractionHand.MAIN_HAND, false);
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
                assertPlayingModel(client, client.player, client.player.getOffhandItem(), 0.0F);
                assertPlayingArm(client, InteractionHand.OFF_HAND, false);
                assertBoomboxTooltip(client, client.player.getOffhandItem(), true);
                assertParrotDances(client, false);
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
                assertPlayingModel(client, client.player, client.player.getOffhandItem(), 1.0F);
                assertParrotDances(client, true);
                if (AudioPlaybackManager.getInstance().getPlaybackState(entityKey).isPresent()) {
                    throw new AssertionError("Third-party record incorrectly entered managed playback");
                }
                // Queue a completion from off-thread, then retire its sound before the client
                // thread handles it. The old callback must not start another legacy track.
                PlaybackStopListener sound = (PlaybackStopListener) SoundTracker.getEntitySound(client.player.getId());
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
                step = 15;
                ticks = 0;
                MinecraftServer server = client.getSingleplayerServer();
                UUID playerId = client.player.getUUID();
                var dimension = client.level.dimension();
                server.execute(() -> {
                    ServerLevel level = server.getLevel(dimension);
                    ServerPlayer player = server.getPlayerList().getPlayer(playerId);
                    standId = spawnStand(level, player);
                });
            } else if (++ticks >= 100) {
                throw new AssertionError("Pausing a third-party record did not stop legacy playback");
            }
        }
        if (step == 15 && standId != null && client.level != null) {
            var key = PlaybackOwnerKey.entity(client.level.dimension(), standId);
            if (AudioPlaybackManager.getInstance().isPlaying(key)) {
                boolean foundStand = false;
                for (var entity : client.level.entitiesForRendering()) {
                    if (entity.getUUID().equals(standId) && entity instanceof LivingEntity living) {
                        assertPlayingModel(client, living, living.getMainHandItem(), 1.0F);
                        foundStand = true;
                    }
                }
                if (!foundStand) {
                    throw new AssertionError("Playing armor stand was not in the client world");
                }
                step = 16;
                ticks = 0;
                MinecraftServer server = client.getSingleplayerServer();
                UUID id = standId;
                var dimension = client.level.dimension();
                server.execute(() -> {
                    var stand = server.getLevel(dimension).getEntity(id);
                    if (stand == null) {
                        throw new AssertionError("Armor stand disappeared before the death check");
                    }
                    stand.kill();
                });
            } else if (++ticks >= 100) {
                throw new AssertionError("Living boombox owner did not start managed playback");
            }
        }
        if (step == 16 && client.level != null) {
            var key = PlaybackOwnerKey.entity(client.level.dimension(), standId);
            // The old owner must leave the client world as well as the manager.
            for (var entity : client.level.entitiesForRendering()) {
                if (entity.getUUID().equals(standId)) {
                    if (++ticks >= 100) {
                        throw new AssertionError("Killed boombox owner stayed in the client world");
                    }
                    return;
                }
            }
            if (AudioPlaybackManager.getInstance().getPlaybackState(key).isEmpty()) {
                step = 17;
                ticks = 0;
                MinecraftServer server = client.getSingleplayerServer();
                UUID playerId = client.player.getUUID();
                var dimension = client.level.dimension();
                server.execute(() -> standId = spawnStand(server.getLevel(dimension),
                        server.getPlayerList().getPlayer(playerId)));
            } else if (++ticks >= 100) {
                throw new AssertionError("Killed living boombox owner left a managed session");
            }
        }
        if (step == 17 && client.level != null && standId != null) {
            var key = PlaybackOwnerKey.entity(client.level.dimension(), standId);
            if (AudioPlaybackManager.getInstance().isPlaying(key)) {
                step = 18;
                ticks = 0;
                MinecraftServer server = client.getSingleplayerServer();
                UUID id = standId;
                var dimension = client.level.dimension();
                server.execute(() -> {
                    var stand = server.getLevel(dimension).getEntity(id);
                    ServerLevel nether = server.getLevel(Level.NETHER);
                    if (stand == null || nether == null) {
                        throw new AssertionError("Could not find the stand or target dimension");
                    }
                    Entity moved = stand.changeDimension(nether, SMOKE_TELEPORTER);
                    if (moved == null || moved.level() != nether || !moved.getUUID().equals(id)) {
                        throw new AssertionError("Stand did not move into the target dimension");
                    }
                });
            } else if (++ticks >= 100) {
                throw new AssertionError("Stand did not begin playback before changing dimensions");
            }
        }
        if (step == 18 && client.level != null) {
            var key = PlaybackOwnerKey.entity(client.level.dimension(), standId);
            if (!client.level.dimension().equals(Level.OVERWORLD)) {
                throw new AssertionError("Client unexpectedly followed the stand into the Nether");
            }
            for (var entity : client.level.entitiesForRendering()) {
                if (entity.getUUID().equals(standId)) {
                    if (++ticks >= 100) {
                        throw new AssertionError("Transferred stand stayed in the old client world");
                    }
                    return;
                }
            }
            if (AudioPlaybackManager.getInstance().getPlaybackState(key).isEmpty()) {
                step = 19;
                ticks = 0;
                MinecraftServer server = client.getSingleplayerServer();
                UUID playerId = client.player.getUUID();
                server.execute(() -> {
                    ServerPlayer player = server.getPlayerList().getPlayer(playerId);
                    ItemStack boombox = player.getOffhandItem().copy();
                    BoomboxItem.setRecord(boombox, new ItemStack(Items.MUSIC_DISC_CAT));
                    BoomboxItem.setPaused(boombox, false);
                    player.setItemInHand(InteractionHand.OFF_HAND, boombox);
                });
            } else if (++ticks >= 100) {
                throw new AssertionError("Transferred stand left playback in the old dimension");
            }
        }
        if (step == 19 && client.player != null && client.level != null) {
            var key = PlaybackOwnerKey.entity(client.level.dimension(), client.player.getUUID());
            if (client.level.dimension().equals(Level.OVERWORLD)
                    && client.player.getOffhandItem().is(EtchedItems.BOOMBOX.get())
                    && AudioPlaybackManager.getInstance().isPlaying(key)) {
                travellingPlayerId = client.player.getUUID();
                step = 20;
                ticks = 0;
                MinecraftServer server = client.getSingleplayerServer();
                UUID id = travellingPlayerId;
                server.execute(() -> {
                    ServerPlayer player = server.getPlayerList().getPlayer(id);
                    ServerLevel nether = server.getLevel(Level.NETHER);
                    if (player == null || nether == null) {
                        throw new AssertionError("Player or Nether was unavailable for dimension smoke");
                    }
                    Entity moved = player.changeDimension(nether, SMOKE_TELEPORTER);
                    if (moved == null || moved.level() != nether || !moved.getUUID().equals(id)) {
                        throw new AssertionError("Player did not enter the target dimension");
                    }
                });
            } else if (++ticks >= 100) {
                throw new AssertionError("Player boombox did not resume before changing dimensions");
            }
        }
        if (step == 20 && client.player != null && client.level != null) {
            if (client.level.dimension().equals(Level.NETHER)) {
                var oldKey = PlaybackOwnerKey.entity(Level.OVERWORLD, travellingPlayerId);
                var newKey = PlaybackOwnerKey.entity(Level.NETHER, travellingPlayerId);
                var jukeboxKey = PlaybackOwnerKey.block(Level.OVERWORLD, pos);
                if (AudioPlaybackManager.getInstance().getPlaybackState(oldKey).isPresent()
                        || AudioPlaybackManager.getInstance().getPlaybackState(jukeboxKey).isPresent()) {
                    throw new AssertionError("Old-world playback survived the player dimension change");
                }
                if (!AudioPlaybackManager.getInstance().isPlaying(newKey)) {
                    stableTicks = 0;
                    if (++ticks >= 100) {
                        throw new AssertionError("Boombox did not start a fresh session in the Nether");
                    }
                    return;
                }
                var state = AudioPlaybackManager.getInstance().getPlaybackState(newKey).orElseThrow();
                assertPlayingModel(client, client.player, client.player.getOffhandItem(), 1.0F);
                if (state.revision() != 0L || !"minecraft:music_disc.cat".equals(
                        state.program().orElseThrow().tracks().get(0).source())) {
                    throw new AssertionError("Nether boombox restarted or selected the wrong record");
                }
                if (++stableTicks < 20) {
                    return;
                }
                step = 21;
                var oldPlayer = client.player;
                var server = client.getSingleplayerServer();
                client.tell(() -> {
                    client.level.disconnect();
                    client.clearLevel(new TitleScreen());
                    if (client.level != null || !server.isShutdown()
                            || AudioPlaybackManager.getInstance().getPlaybackState(newKey).isPresent()
                            || AudioPlaybackManager.getInstance().getPlaybackState(jukeboxKey).isPresent()
                            || BoomboxPlayback.getInstance().isPlaying(oldPlayer)) {
                        throw new AssertionError("Disconnect left boombox or jukebox playback behind");
                    }
                    assertPlayingModel(client, oldPlayer, oldPlayer.getOffhandItem(), 0.0F);
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
                    System.out.println("ETCHED LIVING BOOMBOX OWNER DEATH SMOKE PASSED");
                    System.out.println("ETCHED BOOMBOX OWNER DIMENSION TRANSFER SMOKE PASSED");
                    System.out.println("ETCHED PLAYER BOOMBOX DIMENSION CHANGE SMOKE PASSED");
                    System.out.println("ETCHED BOOMBOX PARROT DANCING SMOKE PASSED");
                    System.out.println("ETCHED BOOMBOX POSE AND TOOLTIP SMOKE PASSED");
                    System.out.println("ETCHED INTEGRATED DISCONNECT CLEANUP SMOKE PASSED");
                    step = 22;
                    client.stop();
                });
            } else if (++ticks >= 100) {
                throw new AssertionError("Client did not enter the Nether after the player transfer");
            }
        }
    }

    private static UUID spawnStand(ServerLevel level, ServerPlayer player) {
        var stand = EntityType.ARMOR_STAND.create(level);
        if (stand == null) {
            throw new AssertionError("Could not spawn an armor stand for boombox smoke");
        }
        stand.moveTo(player.getX() + 4, player.getY(), player.getZ());
        ItemStack boombox = new ItemStack(EtchedItems.BOOMBOX.get());
        BoomboxItem.setRecord(boombox, new ItemStack(Items.MUSIC_DISC_CAT));
        stand.setItemSlot(EquipmentSlot.MAINHAND, boombox);
        level.addFreshEntity(stand);
        return stand.getUUID();
    }

    private static void assertPlayingModel(Minecraft client, LivingEntity entity, ItemStack stack, float expected) {
        var property = ItemProperties.getProperty(EtchedItems.BOOMBOX.get(),
                ResourceLocation.fromNamespaceAndPath(Etched.MOD_ID, "playing"));
        if (property == null || property.call(stack, client.level, entity, 0) != expected) {
            throw new AssertionError("Boombox model did not match the playing hand of " + entity.getUUID());
        }
    }

    private static void assertPlayingArm(Minecraft client, InteractionHand hand, boolean playing) {
        var model = new HumanoidModel<>(client.getEntityModels().bakeLayer(ModelLayers.PLAYER));
        model.rightArmPose = HumanoidModel.ArmPose.ITEM;
        model.leftArmPose = HumanoidModel.ArmPose.ITEM;
        model.setupAnim(client.player, 0, 0, 0, 0, 0);
        boolean rightArm = (client.player.getMainArm() == HumanoidArm.RIGHT) == (hand == InteractionHand.MAIN_HAND);
        float rotation = rightArm ? model.rightArm.xRot : model.leftArm.xRot;
        if ((rotation > 2.5F) != playing) {
            throw new AssertionError("Boombox arm pose did not match " + hand + " playback: " + rotation);
        }
    }

    private static void assertBoomboxTooltip(Minecraft client, ItemStack stack, boolean paused) {
        List<Component> lines = stack.getTooltipLines(client.player, TooltipFlag.NORMAL);
        boolean pauseHint = lines.stream().anyMatch(line -> line.getContents() instanceof TranslatableContents text
                && text.getKey().equals("item.etched.boombox.pause"));
        boolean pausedStatus = lines.stream().anyMatch(line -> line.getContents() instanceof TranslatableContents text
                && text.getKey().equals("item.etched.boombox.paused"));
        if (!pauseHint || pausedStatus != paused) {
            throw new AssertionError("Boombox tooltip did not match the stored pause state");
        }
    }

    private static void assertParrotDances(Minecraft client, boolean expected) {
        if (parrot == null) {
            parrot = EntityType.PARROT.create(client.level);
            if (parrot == null) {
                throw new AssertionError("Could not create a parrot for boombox smoke");
            }
        }
        // Keep it near the player and beyond vanilla jukebox range so only the boombox counts.
        parrot.moveTo(client.player.getX() - 1.5, client.player.getY(), client.player.getZ() - 1.5);
        parrot.aiStep();
        if (parrot.isPartyParrot() != expected) {
            throw new AssertionError("Parrot dancing did not match boombox playback");
        }
    }

    private static ItemStack disc(String sound, String title) {
        ItemStack stack = new ItemStack(EtchedItems.ETCHED_MUSIC_DISC.get());
        EtchedMusicDiscItem.setMusic(stack, new TrackData(sound, "Minecraft", Component.literal(title)));
        return stack;
    }
}
