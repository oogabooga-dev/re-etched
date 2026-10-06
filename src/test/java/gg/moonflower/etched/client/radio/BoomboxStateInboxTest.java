package gg.moonflower.etched.client.radio;

import gg.moonflower.etched.common.audio.AudioProgram;
import gg.moonflower.etched.common.audio.AudioTrack;
import gg.moonflower.etched.common.audio.PlaybackState;
import gg.moonflower.etched.common.network.play.ClientboundBoomboxStatePacket;
import net.minecraft.world.level.Level;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

class BoomboxStateInboxTest {

    static {
        MinecraftTestBootstrap.bootStrap();
    }

    private static final UUID OWNER = new UUID(0, 1);
    private static final PlaybackOwnerKey.EntityOwner KEY = PlaybackOwnerKey.entity(Level.OVERWORLD, OWNER);
    private static final AudioProgram PROGRAM = new AudioProgram(AudioProgram.Kind.FINITE, List.of(
            new AudioTrack(AudioTrack.SourceType.SOUND_EVENT, "minecraft:music_disc.cat", "", "")));

    @Test
    void duplicateConflictingAndStalePublicationsCannotRestartOrExtendWaiting() {
        var inbox = new BoomboxStateInbox();
        assertTrue(inbox.accept(packet(OWNER, 10L, true)));
        var original = inbox.get(KEY);
        for (int i = 0; i < 99; i++) {
            original.tickPending();
        }
        assertFalse(inbox.accept(packet(OWNER, 10L, true)));
        assertFalse(inbox.accept(packet(OWNER, 10L, false)));
        assertFalse(inbox.accept(packet(OWNER, 9L, true)));
        original.tickPending();
        assertNull(original.packet);
        assertEquals(10L, original.revision);
        assertTrue(inbox.accept(packet(OWNER, 11L, true)));
    }

    @Test
    void stopAndLocalRetirementKeepWatermarksButReleasePrograms() {
        var inbox = new BoomboxStateInbox();
        inbox.accept(packet(OWNER, 10L, true));
        inbox.get(KEY).activate();
        inbox.get(KEY).retire();
        assertFalse(inbox.accept(packet(OWNER, 10L, true)));
        assertTrue(inbox.accept(packet(OWNER, 11L, false)));
        assertNull(inbox.get(KEY).packet);
        assertFalse(inbox.accept(packet(OWNER, 10L, true)));
        assertTrue(inbox.accept(new ClientboundBoomboxStatePacket(Level.OVERWORLD, 99, OWNER,
                new PlaybackState(12L, Optional.of(PROGRAM), true))));
        assertEquals(99, inbox.get(KEY).entityId);
    }

    @Test
    void pendingBoundDoesNotPreventStopsOrReplacingAnExistingPendingPublication() {
        var inbox = new BoomboxStateInbox();
        for (int i = 1; i <= 32; i++) {
            assertTrue(inbox.accept(packet(new UUID(0, i), 1L, true)));
        }
        assertFalse(inbox.accept(packet(new UUID(0, 33), 1L, true)));
        assertTrue(inbox.accept(packet(OWNER, 2L, true)));
        assertTrue(inbox.accept(packet(OWNER, 3L, false)));
        assertTrue(inbox.accept(packet(new UUID(0, 33), 2L, true)));
    }

    @Test
    void ownerBoundFailsClosedWithoutEvictingTombstonesAndWorldCleanupResetsIt() {
        var inbox = new BoomboxStateInbox();
        for (int i = 1; i <= 256; i++) {
            assertTrue(inbox.accept(packet(new UUID(0, i), 1L, false)));
        }
        assertFalse(inbox.accept(packet(new UUID(0, 257), 2L, false)));
        assertFalse(inbox.accept(packet(OWNER, 1L, true)));
        assertTrue(inbox.accept(packet(OWNER, 2L, true)));
        inbox.clearAll();
        assertTrue(inbox.accept(packet(new UUID(0, 257), 1L, true)));
    }

    @Test
    void dimensionsAndOwnersAreIndependentAndOrderingIsWrapSafe() {
        var inbox = new BoomboxStateInbox();
        assertTrue(inbox.accept(packet(OWNER, Long.MAX_VALUE, true)));
        assertTrue(inbox.accept(packet(OWNER, Long.MIN_VALUE, false)));
        assertFalse(inbox.accept(packet(OWNER, Long.MAX_VALUE, true)));
        assertTrue(inbox.accept(new ClientboundBoomboxStatePacket(Level.NETHER, 1, OWNER,
                new PlaybackState(1L, Optional.of(PROGRAM), true))));
        assertEquals(Long.MIN_VALUE, inbox.get(KEY).revision);
    }

    @Test
    void sourceGuardsIgnoreLocalizedMetadataButNotProgramKindCountOrOrder() {
        var translated = new AudioProgram(AudioProgram.Kind.FINITE, List.of(
                new AudioTrack(AudioTrack.SourceType.SOUND_EVENT, "minecraft:music_disc.cat", "Artist", "Localized title")));
        assertTrue(BoomboxPlayback.sameSources(PROGRAM, translated));
        var other = new AudioTrack(AudioTrack.SourceType.REMOTE, "https://audio.example/track", "", "");
        assertFalse(BoomboxPlayback.sameSources(PROGRAM, new AudioProgram(AudioProgram.Kind.FINITE, List.of(other))));
        var mixed = new AudioProgram(AudioProgram.Kind.FINITE, List.of(PROGRAM.tracks().get(0), other));
        assertFalse(BoomboxPlayback.sameSources(PROGRAM, mixed));
        assertFalse(BoomboxPlayback.sameSources(mixed, new AudioProgram(AudioProgram.Kind.FINITE, List.of(other, PROGRAM.tracks().get(0)))));
        assertFalse(BoomboxPlayback.sameSources(new AudioProgram(AudioProgram.Kind.FINITE, List.of(other)),
                new AudioProgram(AudioProgram.Kind.LIVE, List.of(other))));
    }

    private static ClientboundBoomboxStatePacket packet(UUID owner, long revision, boolean enabled) {
        return new ClientboundBoomboxStatePacket(Level.OVERWORLD, 1, owner,
                new PlaybackState(revision, enabled ? Optional.of(PROGRAM) : Optional.empty(), enabled));
    }
}
