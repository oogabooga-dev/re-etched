package gg.moonflower.etched.client.radio;

import gg.moonflower.etched.common.audio.PlaybackRevision;
import gg.moonflower.etched.common.audio.PlaybackState;
import gg.moonflower.etched.common.audio.RecordContent;
import gg.moonflower.etched.common.item.RecordContentResolver;
import gg.moonflower.etched.common.network.play.ClientboundPlayMusicPacket;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.RecordItem;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.JukeboxBlock;
import net.minecraft.world.level.block.state.BlockState;

import java.util.Optional;

/** Client-side jukebox owner. The SoundEngine and finite cache remain owned by the playback backends. */
public final class JukeboxPlayback {

    private static final JukeboxStartGate STARTS = new JukeboxStartGate();
    private static final JukeboxRevisionGate REVISIONS = new JukeboxRevisionGate();

    private JukeboxPlayback() {
    }

    public static boolean start(BlockPos pos, ItemStack record) {
        var level = Minecraft.getInstance().level;
        if (level == null || !hasRecord(level.getBlockState(pos))) {
            return false;
        }
        Optional<RecordContent> content = RecordContentResolver.resolve(record);
        if (content.isEmpty()) {
            return false;
        }
        AudioPlaybackManager manager = AudioPlaybackManager.getInstance();
        PlaybackOwnerKey key = PlaybackOwnerKey.block(level.dimension(), pos);
        return apply(manager, key, content.orElseThrow());
    }

    /** A delayed start must not resurrect a sound after the server cleared HAS_RECORD. */
    public static boolean hasRecord(BlockState state) {
        return state.is(Blocks.JUKEBOX) && state.getValue(JukeboxBlock.HAS_RECORD);
    }

    public static void levelEvent(ResourceKey<Level> dimension, int event, BlockPos pos, int itemId,
                                  boolean hasRecord) {
        PlaybackOwnerKey.BlockOwner key = PlaybackOwnerKey.block(dimension, pos);
        if (event == 1010 && !(Item.byId(itemId) instanceof RecordItem)) {
            STARTS.start(key, itemId, hasRecord);
        } else if (event == 1011) {
            STARTS.stop(key);
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
        // Native event playback still uses local revisions until its separate migration.
        manager.remove(key);
        if (!packet.isStop() && packet.program().isPresent()) {
            manager.update(key, packet.state());
        }
    }

    /** World/logout cleanup clears both pending event tickets and revision tombstones. */
    public static void clearPendingStarts() {
        STARTS.clearAll();
        REVISIONS.clearAll();
    }

    /** Native disc events retain their local ordering until the native-owner synchronization slice. */
    static boolean apply(AudioPlaybackManager manager, PlaybackOwnerKey key, RecordContent content) {
        long revision = manager.getPlaybackState(key).map(PlaybackState::revision)
                .map(PlaybackRevision::next).orElse(0L);
        return manager.update(key, new PlaybackState(revision, Optional.of(content.program()), true));
    }

    public static void stop(BlockPos pos) {
        var level = Minecraft.getInstance().level;
        if (level != null) {
            AudioPlaybackManager.getInstance().remove(PlaybackOwnerKey.block(level.dimension(), pos));
        }
    }

}
