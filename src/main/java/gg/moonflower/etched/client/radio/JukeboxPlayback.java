package gg.moonflower.etched.client.radio;

import gg.moonflower.etched.common.item.JukeboxRecordSupport;
import gg.moonflower.etched.common.network.play.ClientboundPlayMusicPacket;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.world.item.Item;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.JukeboxBlock;
import net.minecraft.world.level.block.state.BlockState;

/** Client-side jukebox owner. The SoundEngine and finite cache remain owned by the playback backends. */
public final class JukeboxPlayback {

    private static final JukeboxStartGate STARTS = new JukeboxStartGate();
    private static final JukeboxRevisionGate REVISIONS = new JukeboxRevisionGate();

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
        return revisions.accept(key, packet.revision());
    }

    /** Called only after ticket/revision admission, including disabled unsupported replacements and stops. */
    public static void applyPacket(ClientboundPlayMusicPacket packet) {
        applyPacket(AudioPlaybackManager.getInstance(), packet);
    }

    static void applyPacket(AudioPlaybackManager manager, ClientboundPlayMusicPacket packet) {
        PlaybackOwnerKey.BlockOwner key = PlaybackOwnerKey.block(packet.dimension(), packet.pos());
        if (!packet.isStop() && packet.program().isPresent()) {
            manager.update(key, packet.state());
        } else {
            manager.remove(key);
        }
    }

    /** World/logout cleanup clears both pending event tickets and revision tombstones. */
    public static void clearPendingStarts() {
        STARTS.clearAll();
        REVISIONS.clearAll();
    }

    public static void stop(BlockPos pos) {
        var level = Minecraft.getInstance().level;
        if (level != null) {
            AudioPlaybackManager.getInstance().remove(PlaybackOwnerKey.block(level.dimension(), pos));
        }
    }

}
