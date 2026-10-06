package gg.moonflower.etched.client.radio;

import gg.moonflower.etched.common.item.JukeboxRecordSupport;
import gg.moonflower.etched.common.network.play.ClientboundPlayMusicPacket;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.world.item.Item;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.chunk.ChunkStatus;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.JukeboxBlock;
import net.minecraft.world.level.block.state.BlockState;

/** Client-side jukebox owner. The SoundEngine and finite cache remain owned by the playback backends. */
public final class JukeboxPlayback {

    private static final JukeboxStartGate STARTS = new JukeboxStartGate();
    private static final JukeboxRevisionGate REVISIONS = new JukeboxRevisionGate();
    private static final JukeboxSessionOwners SESSIONS = new JukeboxSessionOwners();

    private JukeboxPlayback() {
    }

    /** A delayed start must not resurrect a sound after the server cleared HAS_RECORD. */
    public static boolean hasRecord(BlockState state) {
        return state.is(Blocks.JUKEBOX) && state.getValue(JukeboxBlock.HAS_RECORD);
    }

    public static void levelEvent(ResourceKey<Level> dimension, int event, BlockPos pos, int itemId,
                                  boolean hasRecord) {
        levelEvent(STARTS, dimension, event, pos, itemId, hasRecord);
    }

    static void levelEvent(JukeboxStartGate starts, ResourceKey<Level> dimension, int event, BlockPos pos,
                           int itemId, boolean hasRecord) {
        PlaybackOwnerKey.BlockOwner key = PlaybackOwnerKey.block(dimension, pos);
        if (event == 1010 && JukeboxRecordSupport.requiresPlaybackPacket(Item.byId(itemId))) {
            starts.start(key, itemId, hasRecord);
        } else if (event == 1011 || event == 1010) {
            // A native third-party replacement also retires pending managed starts, without consuming their slots.
            starts.stop(key);
        }
    }

    public static boolean acceptPacket(ClientboundPlayMusicPacket packet, boolean hasRecord) {
        return acceptPacket(STARTS, REVISIONS, packet, hasRecord);
    }

    static boolean acceptPacket(JukeboxStartGate starts, JukeboxRevisionGate revisions,
                                ClientboundPlayMusicPacket packet, boolean hasRecord) {
        PlaybackOwnerKey.BlockOwner key = PlaybackOwnerKey.block(packet.dimension(), packet.pos());
        if (!packet.isStop() && (!starts.consume(key, packet.itemId()) || !hasRecord)) {
            return false;
        }
        return revisions.accept(key, packet.revision(), !packet.isStop() && packet.program().isPresent());
    }

    /** Called only after ticket/revision admission, including disabled unsupported replacements and stops. */
    public static void applyPacket(ClientboundPlayMusicPacket packet) {
        var manager = AudioPlaybackManager.getInstance();
        boolean applied = applyPacket(manager, packet);
        var key = PlaybackOwnerKey.block(packet.dimension(), packet.pos());
        var state = manager.getPlaybackState(key);
        if (applied && !packet.isStop() && packet.program().isPresent() && state.isPresent()
                && state.orElseThrow().equals(packet.state())) {
            SESSIONS.remember(key, state.orElseThrow());
        } else {
            SESSIONS.forget(key);
            REVISIONS.release(key);
        }
    }

    static boolean applyPacket(AudioPlaybackManager manager, ClientboundPlayMusicPacket packet) {
        PlaybackOwnerKey.BlockOwner key = PlaybackOwnerKey.block(packet.dimension(), packet.pos());
        if (!packet.isStop() && packet.program().isPresent()) {
            return manager.update(key, packet.state());
        } else {
            return manager.remove(key);
        }
    }

    /** World/logout cleanup clears both pending event tickets and revision tombstones. */
    public static void clearPendingStarts() {
        STARTS.clearAll();
        REVISIONS.clearAll();
        SESSIONS.clearAll();
    }

    public static void stop(BlockPos pos) {
        var level = Minecraft.getInstance().level;
        if (level != null) {
            var key = PlaybackOwnerKey.block(level.dimension(), pos);
            SESSIONS.forget(key);
            REVISIONS.release(key);
            AudioPlaybackManager.getInstance().remove(key);
        }
    }

    /** Untracking releases sessions and invalidates pending starts, but does not forget revision watermarks. */
    public static void unloadChunk(ResourceKey<Level> dimension, ChunkPos pos) {
        unloadChunk(AudioPlaybackManager.getInstance(), STARTS, SESSIONS, REVISIONS, dimension, pos);
    }

    static void unloadChunk(AudioPlaybackManager manager, JukeboxStartGate starts, JukeboxSessionOwners sessions,
                            JukeboxRevisionGate revisions,
                            ResourceKey<Level> dimension, ChunkPos pos) {
        starts.unloadChunk(dimension, pos);
        sessions.prune(manager, key -> !key.dimension().equals(dimension) || !new ChunkPos(key.pos()).equals(pos), revisions::release);
    }

    /** ClientChunkCache can move its center without posting Unload for out-of-range slots. */
    public static void prune() {
        var level = Minecraft.getInstance().level;
        SESSIONS.prune(AudioPlaybackManager.getInstance(), key -> {
            var pos = key.pos();
            boolean valid = level != null && level.dimension().equals(key.dimension())
                    && level.getChunkSource().getChunk(pos.getX() >> 4, pos.getZ() >> 4, ChunkStatus.FULL, false) != null
                    && hasRecord(level.getBlockState(pos));
            if (!valid) {
                STARTS.stop(key); // Keep watermark and send-order slots even when local resources are gone.
            }
            return valid;
        }, REVISIONS::release);
    }

}
