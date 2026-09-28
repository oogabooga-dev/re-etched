package gg.moonflower.etched.client.radio;

import gg.moonflower.etched.common.audio.AudioTrack;
import gg.moonflower.etched.common.audio.PlaybackRevision;
import gg.moonflower.etched.common.audio.PlaybackState;
import gg.moonflower.etched.common.audio.RecordContent;
import gg.moonflower.etched.common.item.RecordContentResolver;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.Blocks;

import java.util.Optional;

/** Client-side jukebox owner. The SoundEngine and finite cache remain owned by the playback backends. */
public final class JukeboxPlayback {

    private JukeboxPlayback() {
    }

    public static boolean start(BlockPos pos, ItemStack record) {
        var level = Minecraft.getInstance().level;
        if (level == null || !level.getBlockState(pos).is(Blocks.JUKEBOX)) {
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

    static boolean apply(AudioPlaybackManager manager, PlaybackOwnerKey key, RecordContent content) {
        if (!supportsProgram(content)) {
            return false;
        }
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

    // Mixed local/remote albums still use the legacy path until the finite backends can route
    // track-by-track. Never accept a program the manager cannot actually play.
    private static boolean supportsProgram(RecordContent content) {
        AudioTrack.SourceType first = content.program().tracks().get(0).sourceType();
        return content.program().tracks().stream().allMatch(track -> track.sourceType() == first);
    }
}
