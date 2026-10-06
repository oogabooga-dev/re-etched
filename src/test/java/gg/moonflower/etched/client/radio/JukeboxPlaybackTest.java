package gg.moonflower.etched.client.radio;

import gg.moonflower.etched.common.audio.AudioProgram;
import gg.moonflower.etched.common.audio.AudioTrack;
import gg.moonflower.etched.common.audio.PlaybackState;
import gg.moonflower.etched.common.audio.RecordContent;
import gg.moonflower.etched.common.network.play.ClientboundPlayMusicPacket;
import net.minecraft.core.BlockPos;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.JukeboxBlock;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

class JukeboxPlaybackTest {

    static {
        MinecraftTestBootstrap.bootStrap();
    }

    private static final PlaybackOwnerKey.BlockOwner KEY = PlaybackOwnerKey.block(Level.OVERWORLD, BlockPos.ZERO);
    private final JukeboxSessionOwners sessions = new JukeboxSessionOwners();

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
        JukeboxStartGate starts = new JukeboxStartGate();
        JukeboxRevisionGate revisions = new JukeboxRevisionGate();

        starts.start(KEY, 42, true);
        assertTrue(deliver(manager, starts, revisions, new ClientboundPlayMusicPacket(Level.OVERWORLD, BlockPos.ZERO,
                42, 10L, Optional.of(first.program()))));
        starts.start(KEY, 42, true);
        assertTrue(deliver(manager, starts, revisions, new ClientboundPlayMusicPacket(Level.OVERWORLD, BlockPos.ZERO,
                42, 11L, Optional.of(replacement.program()))));
        assertEquals(11L, manager.getPlaybackState(KEY).orElseThrow().revision());
        assertEquals(replacement.program(), manager.getPlaybackState(KEY).orElseThrow().program().orElseThrow());
        assertTrue(manager.remove(KEY));
        assertFalse(manager.getPlaybackState(KEY).isPresent());
    }

    @Test
    void mixedAlbumSupersedesThePreviousProgramWithOneOwnerRevision() {
        AudioPlaybackManager manager = new AudioPlaybackManager(AudioPlaybackManager.PlaybackDriver.NOOP);
        JukeboxStartGate starts = new JukeboxStartGate();
        JukeboxRevisionGate revisions = new JukeboxRevisionGate();
        starts.start(KEY, 42, true);
        assertTrue(deliver(manager, starts, revisions, packet(20L, "first")));
        starts.start(KEY, 42, true);
        var mixed = program(remote("second"),
                new AudioTrack(AudioTrack.SourceType.SOUND_EVENT, "minecraft:music_disc.cat", "", ""));
        assertTrue(deliver(manager, starts, revisions, new ClientboundPlayMusicPacket(Level.OVERWORLD, BlockPos.ZERO,
                42, 21L, Optional.of(mixed.program()))));
        assertEquals(21L, manager.getPlaybackState(KEY).orElseThrow().revision());
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

    @Test
    void actualVanillaDiscEventsOnlyAdmitServerPacketsAndRetainTheExactRevision() {
        AudioPlaybackManager manager = new AudioPlaybackManager(AudioPlaybackManager.PlaybackDriver.NOOP);
        JukeboxStartGate starts = new JukeboxStartGate();
        JukeboxRevisionGate revisions = new JukeboxRevisionGate();
        var cat = ClientboundPlayMusicPacket.fromRecord(Level.OVERWORLD, BlockPos.ZERO, 50L, new ItemStack(Items.MUSIC_DISC_CAT));
        assertFalse(deliver(manager, starts, revisions, cat));
        JukeboxPlayback.levelEvent(starts, Level.OVERWORLD, 1010, BlockPos.ZERO, cat.itemId(), true);
        assertTrue(deliver(manager, starts, revisions, cat));
        assertEquals(cat.state(), manager.getPlaybackState(KEY).orElseThrow());
        JukeboxPlayback.levelEvent(starts, Level.OVERWORLD, 1010, BlockPos.ZERO, cat.itemId(), true);
        assertFalse(deliver(manager, starts, revisions, cat));
        assertEquals(cat.state(), manager.getPlaybackState(KEY).orElseThrow());
    }

    @Test
    void nonManagedEventsInvalidatePendingVanillaTicketsWithoutCreatingTheirOwn() {
        JukeboxStartGate starts = new JukeboxStartGate();
        int vanillaId = Item.getId(Items.MUSIC_DISC_CAT);
        int foreignId = Item.getId(Items.AIR); // Registered native foreign disc is covered in transformed tests.
        JukeboxPlayback.levelEvent(starts, Level.OVERWORLD, 1010, BlockPos.ZERO, vanillaId, true);
        JukeboxPlayback.levelEvent(starts, Level.OVERWORLD, 1010, BlockPos.ZERO, foreignId, true);
        assertFalse(starts.consume(KEY, vanillaId));
        assertFalse(starts.consume(KEY, foreignId));
        JukeboxPlayback.levelEvent(starts, Level.OVERWORLD, 1010, BlockPos.ZERO, vanillaId, true);
        assertTrue(starts.consume(KEY, vanillaId));
    }

    private static ClientboundPlayMusicPacket packet(long revision, String name) {
        return new ClientboundPlayMusicPacket(Level.OVERWORLD, BlockPos.ZERO, 42, revision,
                Optional.of(program(remote(name)).program()));
    }

    @Test
    void chunkUntrackingRemovesOnlyMatchingSessionsAndInvalidatesPendingTicketsWithoutForgettingRevisions() {
        var manager = new AudioPlaybackManager(AudioPlaybackManager.PlaybackDriver.NOOP);
        var starts = new JukeboxStartGate();
        var revisions = new JukeboxRevisionGate();
        var sessions = this.sessions;
        var record = new ItemStack(Items.MUSIC_DISC_CAT);
        var adjacent = PlaybackOwnerKey.block(Level.OVERWORLD, new BlockPos(-1, 0, 0));
        var foreign = PlaybackOwnerKey.block(Level.NETHER, BlockPos.ZERO);
        for (var key : List.of(KEY, adjacent, foreign)) {
            var packet = ClientboundPlayMusicPacket.fromRecord(key.dimension(), key.pos(), 10L, record);
            starts.start(key, packet.itemId(), true);
            assertTrue(deliver(manager, starts, revisions, packet));
        }
        var pending = ClientboundPlayMusicPacket.fromRecord(Level.OVERWORLD, new BlockPos(15, 64, 15), 100L, record);
        var pendingKey = PlaybackOwnerKey.block(pending.dimension(), pending.pos());
        starts.start(pendingKey, pending.itemId(), true);
        JukeboxPlayback.unloadChunk(manager, starts, sessions, revisions, Level.OVERWORLD, new ChunkPos(0, 0));
        assertTrue(manager.getPlaybackState(KEY).isEmpty());
        assertTrue(manager.getPlaybackState(adjacent).isPresent());
        assertTrue(manager.getPlaybackState(foreign).isPresent());
        assertFalse(deliver(manager, starts, revisions, pending));
        var old = ClientboundPlayMusicPacket.fromRecord(Level.OVERWORLD, BlockPos.ZERO, 10L, record);
        starts.start(KEY, old.itemId(), true);
        assertFalse(deliver(manager, starts, revisions, old));
        var snapshot = ClientboundPlayMusicPacket.fromRecord(Level.OVERWORLD, BlockPos.ZERO, 11L, record);
        starts.start(KEY, snapshot.itemId(), true);
        assertTrue(deliver(manager, starts, revisions, snapshot));
    }

    @Test
    void directedTrackingSnapshotUsesTheSameAdmissionAndSupersedesLocalRemoval() {
        var manager = new AudioPlaybackManager(AudioPlaybackManager.PlaybackDriver.NOOP);
        var starts = new JukeboxStartGate();
        var revisions = new JukeboxRevisionGate();
        var record = new ItemStack(Items.MUSIC_DISC_CAT);
        var original = ClientboundPlayMusicPacket.fromRecord(Level.OVERWORLD, BlockPos.ZERO, 10L, record);
        var snapshot = ClientboundPlayMusicPacket.fromRecord(Level.OVERWORLD, BlockPos.ZERO, 12L, record);
        JukeboxPlayback.levelEvent(starts, Level.OVERWORLD, 1010, BlockPos.ZERO, original.itemId(), true);
        assertTrue(deliver(manager, starts, revisions, original));
        manager.remove(KEY); // Local cleanup during untracking does not allocate playback intent.
        assertFalse(deliver(manager, starts, revisions, snapshot)); // Snapshot cannot bypass its event ticket.
        JukeboxPlayback.levelEvent(starts, Level.OVERWORLD, 1010, BlockPos.ZERO, snapshot.itemId(), true);
        assertTrue(deliver(manager, starts, revisions, snapshot));
        assertEquals(snapshot.state(), manager.getPlaybackState(KEY).orElseThrow());
        JukeboxPlayback.levelEvent(starts, Level.OVERWORLD, 1010, BlockPos.ZERO, original.itemId(), true);
        assertFalse(deliver(manager, starts, revisions, original));
        assertTrue(deliver(manager, starts, revisions, ClientboundPlayMusicPacket.stopped(Level.OVERWORLD, BlockPos.ZERO, 13L)));
        JukeboxPlayback.levelEvent(starts, Level.OVERWORLD, 1010, BlockPos.ZERO, snapshot.itemId(), true);
        assertFalse(deliver(manager, starts, revisions, snapshot));
        assertTrue(manager.getPlaybackState(KEY).isEmpty());
    }

    @Test
    void snapshotTicketInvalidatedByNativeReplacementCannotPoisonWatermarks() {
        var manager = new AudioPlaybackManager(AudioPlaybackManager.PlaybackDriver.NOOP);
        var starts = new JukeboxStartGate();
        var revisions = new JukeboxRevisionGate();
        var snapshot = ClientboundPlayMusicPacket.fromRecord(Level.OVERWORLD, BlockPos.ZERO, 100L, new ItemStack(Items.MUSIC_DISC_CAT));
        JukeboxPlayback.levelEvent(starts, Level.OVERWORLD, 1010, BlockPos.ZERO, snapshot.itemId(), true);
        JukeboxPlayback.levelEvent(starts, Level.OVERWORLD, 1011, BlockPos.ZERO, 0, true);
        assertFalse(deliver(manager, starts, revisions, snapshot));
        var current = ClientboundPlayMusicPacket.fromRecord(Level.OVERWORLD, BlockPos.ZERO, 1L, new ItemStack(Items.MUSIC_DISC_CAT));
        JukeboxPlayback.levelEvent(starts, Level.OVERWORLD, 1010, BlockPos.ZERO, current.itemId(), true);
        assertTrue(deliver(manager, starts, revisions, current));
        assertEquals(1L, manager.getPlaybackState(KEY).orElseThrow().revision());
    }

    @Test
    void delayedAuthoritativeStopCannotCloseAnotherAdapterAtTheSamePosition() {
        var manager = new AudioPlaybackManager(AudioPlaybackManager.PlaybackDriver.NOOP);
        var starts = new JukeboxStartGate();
        var revisions = new JukeboxRevisionGate();
        starts.start(KEY, 42, true);
        assertTrue(deliver(manager, starts, revisions, packet(10L, "first")));
        var previous = manager.getPlaybackState(KEY).orElseThrow();
        manager.remove(KEY);
        var other = new PlaybackState(previous.revision(), previous.program(), true);
        manager.update(KEY, other); // Same values, different adapter-owned state instance.
        var stop = ClientboundPlayMusicPacket.stopped(Level.OVERWORLD, BlockPos.ZERO, 11L);
        assertTrue(deliver(manager, starts, revisions, stop));
        assertSame(other, manager.getPlaybackState(KEY).orElse(null));
    }

    @Test
    void unknownStopsAndUnsupportedReplacementDoNotOwnAnotherAdapterAndStillCompressTheirRevision() {
        var manager = new AudioPlaybackManager(AudioPlaybackManager.PlaybackDriver.NOOP);
        var starts = new JukeboxStartGate();
        var revisions = new JukeboxRevisionGate();
        var other = new PlaybackState(500L, Optional.of(program(remote("other adapter")).program()), true);
        manager.update(KEY, other);
        assertTrue(deliver(manager, starts, revisions, ClientboundPlayMusicPacket.stopped(Level.OVERWORLD, BlockPos.ZERO, 10L)));
        assertSame(other, manager.getPlaybackState(KEY).orElseThrow());
        starts.start(KEY, 42, true);
        assertTrue(deliver(manager, starts, revisions, new ClientboundPlayMusicPacket(Level.OVERWORLD, BlockPos.ZERO,
                42, 11L, Optional.empty())));
        assertSame(other, manager.getPlaybackState(KEY).orElseThrow());
        starts.start(KEY, 42, true);
        assertFalse(deliver(manager, starts, revisions, packet(11L, "late")));
        assertSame(other, manager.getPlaybackState(KEY).orElseThrow());
    }

    @Test
    void directNativeStopInvalidatesPendingTicketsAndReleasesOnlyItsExactSession() {
        var manager = new AudioPlaybackManager(AudioPlaybackManager.PlaybackDriver.NOOP);
        var starts = new JukeboxStartGate();
        var revisions = new JukeboxRevisionGate();
        starts.start(KEY, 42, true);
        assertTrue(deliver(manager, starts, revisions, packet(10L, "first")));
        starts.start(KEY, 42, true); // A managed event has arrived, but its packet has not.
        assertTrue(JukeboxPlayback.stop(manager, starts, this.sessions, revisions, KEY));
        assertTrue(manager.getPlaybackState(KEY).isEmpty());
        assertFalse(deliver(manager, starts, revisions, packet(100L, "late native handoff")));
        starts.start(KEY, 42, true);
        assertTrue(deliver(manager, starts, revisions, packet(11L, "fresh snapshot")));

        var accepted = manager.getPlaybackState(KEY).orElseThrow();
        manager.remove(KEY);
        var other = new PlaybackState(accepted.revision(), accepted.program(), true);
        manager.update(KEY, other);
        starts.start(KEY, 42, true);
        assertFalse(JukeboxPlayback.stop(manager, starts, this.sessions, revisions, KEY));
        assertSame(other, manager.getPlaybackState(KEY).orElseThrow());
        assertFalse(deliver(manager, starts, revisions, packet(101L, "another late handoff")));
        assertFalse(JukeboxPlayback.stop(manager, starts, this.sessions, revisions, KEY));
        assertSame(other, manager.getPlaybackState(KEY).orElseThrow());
    }

    @Test
    void rejectedManagedUpdateDoesNotAdoptAnotherAdapterAndItsLaterStopCannotCloseIt() {
        var manager = new AudioPlaybackManager(AudioPlaybackManager.PlaybackDriver.NOOP);
        var starts = new JukeboxStartGate();
        var revisions = new JukeboxRevisionGate();
        var other = new PlaybackState(100L, Optional.of(program(remote("other")).program()), true);
        manager.update(KEY, other);
        starts.start(KEY, 42, true);
        assertTrue(deliver(manager, starts, revisions, packet(10L, "rejected by manager")));
        assertSame(other, manager.getPlaybackState(KEY).orElseThrow());
        assertTrue(deliver(manager, starts, revisions, ClientboundPlayMusicPacket.stopped(Level.OVERWORLD, BlockPos.ZERO, 11L)));
        assertSame(other, manager.getPlaybackState(KEY).orElseThrow());
    }

    private boolean deliver(AudioPlaybackManager manager, JukeboxStartGate starts,
                            JukeboxRevisionGate revisions, ClientboundPlayMusicPacket packet) {
        if (!JukeboxPlayback.acceptPacket(starts, revisions, packet, true)) {
            return false;
        }
        JukeboxPlayback.applyPacket(manager, this.sessions, revisions, packet);
        return true;
    }

    private static AudioTrack remote(String name) {
        return new AudioTrack(AudioTrack.SourceType.REMOTE, "https://audio.example/" + name.replace(' ', '-') + ".mp3", "Artist", name);
    }

    private static RecordContent program(AudioTrack... tracks) {
        return new RecordContent(new AudioProgram(AudioProgram.Kind.FINITE, List.of(tracks)));
    }
}
