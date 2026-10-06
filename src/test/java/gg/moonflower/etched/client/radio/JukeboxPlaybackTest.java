package gg.moonflower.etched.client.radio;

import gg.moonflower.etched.common.audio.AudioProgram;
import gg.moonflower.etched.common.audio.AudioTrack;
import gg.moonflower.etched.common.audio.RecordContent;
import gg.moonflower.etched.common.network.play.ClientboundPlayMusicPacket;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.JukeboxBlock;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class JukeboxPlaybackTest {

    static {
        MinecraftTestBootstrap.bootStrap();
    }

    private static final PlaybackOwnerKey.BlockOwner KEY = PlaybackOwnerKey.block(Level.OVERWORLD, BlockPos.ZERO);

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

    @Test
    void customStartsUseServerRevisionAndDoNotRestartForDuplicateConflictingOrStalePackets() {
        AudioPlaybackManager manager = new AudioPlaybackManager(AudioPlaybackManager.PlaybackDriver.NOOP);
        JukeboxStartGate starts = new JukeboxStartGate();
        JukeboxRevisionGate revisions = new JukeboxRevisionGate();
        var first = packet(20L, "first");
        starts.start(KEY, 42, true);
        assertTrue(deliver(manager, starts, revisions, first));
        var state = manager.getPlaybackState(KEY).orElseThrow();
        assertEquals(20L, state.revision());
        for (var rejected : List.of(first, packet(20L, "conflict"), packet(19L, "stale"),
                ClientboundPlayMusicPacket.stopped(Level.OVERWORLD, BlockPos.ZERO, 19L))) {
            if (!rejected.isStop()) {
                starts.start(KEY, 42, true);
            }
            assertFalse(deliver(manager, starts, revisions, rejected));
            assertEquals(state, manager.getPlaybackState(KEY).orElseThrow());
        }
    }

    @Test
    void authoritativeStopAndUnsupportedReplacementRetainTheirWatermarkAfterSessionRemoval() {
        AudioPlaybackManager manager = new AudioPlaybackManager(AudioPlaybackManager.PlaybackDriver.NOOP);
        JukeboxStartGate starts = new JukeboxStartGate();
        JukeboxRevisionGate revisions = new JukeboxRevisionGate();
        starts.start(KEY, 42, true);
        assertTrue(deliver(manager, starts, revisions, packet(30L, "first")));
        assertTrue(deliver(manager, starts, revisions, ClientboundPlayMusicPacket.stopped(Level.OVERWORLD, BlockPos.ZERO, 31L)));
        assertTrue(manager.getPlaybackState(KEY).isEmpty());
        starts.start(KEY, 42, true);
        assertFalse(deliver(manager, starts, revisions, packet(30L, "late")));
        starts.start(KEY, 42, true);
        assertTrue(deliver(manager, starts, revisions, packet(32L, "replacement")));
        starts.start(KEY, 42, true);
        assertTrue(deliver(manager, starts, revisions, new ClientboundPlayMusicPacket(Level.OVERWORLD, BlockPos.ZERO,
                42, 33L, Optional.empty())));
        assertTrue(manager.getPlaybackState(KEY).isEmpty());
        starts.start(KEY, 42, true);
        assertFalse(deliver(manager, starts, revisions, packet(32L, "late replacement")));
    }

    @Test
    void invalidTicketsAndMissingRecordsCannotPoisonTheRevisionWatermark() {
        AudioPlaybackManager manager = new AudioPlaybackManager(AudioPlaybackManager.PlaybackDriver.NOOP);
        JukeboxStartGate starts = new JukeboxStartGate();
        JukeboxRevisionGate revisions = new JukeboxRevisionGate();
        assertFalse(deliver(manager, starts, revisions, packet(100L, "no ticket")));
        starts.start(KEY, 42, true);
        assertFalse(JukeboxPlayback.acceptPacket(starts, revisions, packet(100L, "ejected"), false));
        starts.start(KEY, 42, true);
        starts.stop(KEY);
        starts.start(KEY, 42, true);
        assertFalse(deliver(manager, starts, revisions, packet(100L, "cancelled")));
        assertTrue(deliver(manager, starts, revisions, packet(1L, "current")));
        assertEquals(1L, manager.getPlaybackState(KEY).orElseThrow().revision());
    }

    @Test
    void localEventRemovalDoesNotForgetTheLastAcceptedServerRevision() {
        AudioPlaybackManager manager = new AudioPlaybackManager(AudioPlaybackManager.PlaybackDriver.NOOP);
        JukeboxStartGate starts = new JukeboxStartGate();
        JukeboxRevisionGate revisions = new JukeboxRevisionGate();
        starts.start(KEY, 42, true);
        assertTrue(deliver(manager, starts, revisions, packet(10L, "first")));
        manager.remove(KEY); // 1011, block removal, or native sound cleanup precedes a late packet.
        starts.start(KEY, 42, true);
        assertFalse(deliver(manager, starts, revisions, packet(10L, "late")));
        assertTrue(manager.getPlaybackState(KEY).isEmpty());
        starts.start(KEY, 42, true);
        assertTrue(deliver(manager, starts, revisions, packet(11L, "next incarnation")));
    }

    private static ClientboundPlayMusicPacket packet(long revision, String name) {
        return new ClientboundPlayMusicPacket(Level.OVERWORLD, BlockPos.ZERO, 42, revision,
                Optional.of(program(remote(name)).program()));
    }

    private static boolean deliver(AudioPlaybackManager manager, JukeboxStartGate starts,
                                   JukeboxRevisionGate revisions, ClientboundPlayMusicPacket packet) {
        if (!JukeboxPlayback.acceptPacket(starts, revisions, packet, true)) {
            return false;
        }
        JukeboxPlayback.applyPacket(manager, packet);
        return true;
    }

    private static AudioTrack remote(String name) {
        return new AudioTrack(AudioTrack.SourceType.REMOTE, "https://audio.example/" + name.replace(' ', '-') + ".mp3", "Artist", name);
    }

    private static RecordContent program(AudioTrack... tracks) {
        return new RecordContent(new AudioProgram(AudioProgram.Kind.FINITE, List.of(tracks)));
    }
}
