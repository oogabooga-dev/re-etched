package gg.moonflower.etched.common.audio;

import gg.moonflower.etched.common.item.JukeboxRecordSupport;
import gg.moonflower.etched.common.network.EtchedMessages;
import gg.moonflower.etched.common.network.play.ClientboundPlayMusicPacket;
import gg.moonflower.etched.core.Etched;
import net.minecraft.network.protocol.game.ClientboundBlockUpdatePacket;
import net.minecraft.network.protocol.game.ClientboundLevelEventPacket;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.JukeboxBlock;
import net.minecraft.world.level.block.entity.JukeboxBlockEntity;
import net.minecraftforge.event.level.ChunkWatchEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.network.PacketDistributor;

import java.util.Optional;

/** Directed snapshots after chunk data, using the same ordered event/program admission as an ordinary start. */
@Mod.EventBusSubscriber(modid = Etched.MOD_ID, bus = Mod.EventBusSubscriber.Bus.FORGE)
public final class JukeboxServerPlayback {

    private static final int MAX_SNAPSHOTS_PER_CHUNK = 256;

    private JukeboxServerPlayback() {
    }

    /** No revision allocation for stopped, stale, native or invalid owners. Never starts the server jukebox. */
    public static Optional<ClientboundPlayMusicPacket> snapshot(JukeboxBlockEntity jukebox) {
        if (!(jukebox.getLevel() instanceof ServerLevel level) || jukebox.isRemoved()
                || level.getBlockEntity(jukebox.getBlockPos()) != jukebox || !jukebox.isRecordPlaying()) {
            return Optional.empty();
        }
        var state = level.getBlockState(jukebox.getBlockPos());
        var record = jukebox.getFirstItem();
        if (!state.is(Blocks.JUKEBOX) || !state.getValue(JukeboxBlock.HAS_RECORD) || record.isEmpty()
                || !JukeboxRecordSupport.requiresPlaybackPacket(record.getItem())) {
            return Optional.empty();
        }
        // Resolve before allocating; unsupported content must not create snapshot tickets or clock churn.
        var candidate = ClientboundPlayMusicPacket.fromRecord(level.dimension(), jukebox.getBlockPos(), 0L, record);
        if (candidate.program().isEmpty()) {
            return Optional.empty();
        }
        return Optional.of(new ClientboundPlayMusicPacket(candidate.dimension(), candidate.pos(), candidate.itemId(),
                ServerPlaybackClock.get(level).next(), candidate.program()));
    }

    public static boolean sendSnapshot(ServerPlayer player, JukeboxBlockEntity jukebox) {
        if (player.level() != jukebox.getLevel()) {
            return false;
        }
        var snapshot = snapshot(jukebox);
        if (snapshot.isEmpty()) {
            return false;
        }
        var packet = snapshot.orElseThrow();
        // Do not broadcast 1010 or call startPlaying: existing recipients and the server duration stay unchanged.
        player.connection.send(new ClientboundBlockUpdatePacket(player.serverLevel(), packet.pos()));
        player.connection.send(new ClientboundLevelEventPacket(1010, packet.pos(), packet.itemId(), false));
        EtchedMessages.PLAY.send(PacketDistributor.PLAYER.with(() -> player), packet);
        return true;
    }

    @SubscribeEvent
    public static void onChunkWatch(ChunkWatchEvent.Watch event) {
        if (event.getPlayer().level() != event.getLevel()) {
            return;
        }
        int sent = 0;
        for (var blockEntity : event.getChunk().getBlockEntities().values()) {
            if (blockEntity instanceof JukeboxBlockEntity jukebox && sendSnapshot(event.getPlayer(), jukebox)
                    && ++sent == MAX_SNAPSHOTS_PER_CHUNK) {
                break; // Bound publication work; no retained chunk, player, record or pending queue.
            }
        }
    }
}
