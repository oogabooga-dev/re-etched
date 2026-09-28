package gg.moonflower.etched.client.radio;

import gg.moonflower.etched.common.audio.PlaybackRevision;
import gg.moonflower.etched.common.audio.PlaybackState;
import gg.moonflower.etched.common.audio.RecordContent;
import gg.moonflower.etched.common.item.RecordContentResolver;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.JukeboxBlock;
import net.minecraft.world.level.block.state.BlockState;

import java.util.Optional;

/** Client-side jukebox owner. The SoundEngine and finite cache remain owned by the playback backends. */
public final class JukeboxPlayback {

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
