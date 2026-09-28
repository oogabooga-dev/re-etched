package gg.moonflower.etched.client.radio;

import gg.moonflower.etched.common.audio.AudioProgram;
import gg.moonflower.etched.common.audio.AudioTrack;
import gg.moonflower.etched.common.audio.RecordContent;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.JukeboxBlock;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class JukeboxPlaybackTest {

    static {
        MinecraftTestBootstrap.bootStrap();
    }

    private static final PlaybackOwnerKey KEY = PlaybackOwnerKey.block(Level.OVERWORLD, BlockPos.ZERO);

    @Test
    void delayedStartsRequireAnInsertedRecordInTheCurrentJukebox() {
        assertFalse(JukeboxPlayback.hasRecord(Blocks.AIR.defaultBlockState()));
        assertFalse(JukeboxPlayback.hasRecord(Blocks.JUKEBOX.defaultBlockState()));
        assertTrue(JukeboxPlayback.hasRecord(Blocks.JUKEBOX.defaultBlockState()
                .setValue(JukeboxBlock.HAS_RECORD, true)));
    }

    @Test
    void replacingAndRemovingDiscSupersedesOldOwnerState() {
        AudioPlaybackManager manager = new AudioPlaybackManager(AudioPlaybackManager.PlaybackDriver.NOOP);
        RecordContent first = program(remote("first"));
        RecordContent replacement = program(remote("second"));

        assertTrue(JukeboxPlayback.apply(manager, KEY, first));
        assertTrue(JukeboxPlayback.apply(manager, KEY, replacement));
        assertEquals(1, manager.getPlaybackState(KEY).orElseThrow().revision());
        assertEquals(replacement.program(), manager.getPlaybackState(KEY).orElseThrow().program().orElseThrow());
        assertTrue(manager.remove(KEY));
        assertFalse(manager.getPlaybackState(KEY).isPresent());
    }

    @Test
    void mixedAlbumSupersedesThePreviousProgramWithOneOwnerRevision() {
        AudioPlaybackManager manager = new AudioPlaybackManager(AudioPlaybackManager.PlaybackDriver.NOOP);
        assertTrue(JukeboxPlayback.apply(manager, KEY, program(remote("first"))));
        assertTrue(JukeboxPlayback.apply(manager, KEY, program(remote("second"),
                new AudioTrack(AudioTrack.SourceType.SOUND_EVENT, "minecraft:music_disc.cat", "", ""))));
        assertEquals(1, manager.getPlaybackState(KEY).orElseThrow().revision());
    }

    private static AudioTrack remote(String name) {
        return new AudioTrack(AudioTrack.SourceType.REMOTE, "https://audio.example/" + name + ".mp3", "Artist", name);
    }

    private static RecordContent program(AudioTrack... tracks) {
        return new RecordContent(new AudioProgram(AudioProgram.Kind.FINITE, List.of(tracks)));
    }
}
