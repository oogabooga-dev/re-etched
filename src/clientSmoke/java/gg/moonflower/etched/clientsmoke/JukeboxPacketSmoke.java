package gg.moonflower.etched.clientsmoke;

import gg.moonflower.etched.api.record.TrackData;
import gg.moonflower.etched.client.radio.AudioPlaybackManager;
import gg.moonflower.etched.client.radio.PlaybackOwnerKey;
import gg.moonflower.etched.common.item.EtchedMusicDiscItem;
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
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
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

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;

/** Opt-in end-to-end check of vanilla level events and the unchanged Etched item packet. */
final class JukeboxPacketSmoke {

    private static final String WORLD = "etched-jukebox-smoke-" + UUID.randomUUID();
    private static final long DEADLINE = System.nanoTime() + 180_000_000_000L;
    private static final ItemStack A = disc("minecraft:music_disc.blocks", "A");
    private static final ItemStack B = disc("minecraft:music_disc.cat", "B");
    private static int step;
    private static int ticks;
    private static BlockPos pos;

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
                try {
                    Files.writeString(Path.of("etched-jukebox-packet-smoke-success"), WORLD + "\n");
                } catch (IOException exception) {
                    throw new IllegalStateException("Could not record jukebox smoke result", exception);
                }
                System.out.println("ETCHED JUKEBOX PACKET SMOKE PASSED");
                step = 5;
                client.stop();
            } else if (++ticks >= 200) {
                throw new AssertionError("B packet did not start playback after the cancelled A slot");
            }
        }
    }

    private static ItemStack disc(String sound, String title) {
        ItemStack stack = new ItemStack(EtchedItems.ETCHED_MUSIC_DISC.get());
        EtchedMusicDiscItem.setMusic(stack, new TrackData(sound, "Minecraft", Component.literal(title)));
        return stack;
    }
}
