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
        assertNull(inbox.get(KEY));
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
        assertFalse(inbox.accept(packet(new UUID(0, 33), 2L, true)));
        assertTrue(inbox.accept(packet(new UUID(0, 33), 4L, true)));
    }

    @Test
    void retiredOwnerChurnIsCompressedWithoutReAdmittingStaleStartsAndWorldCleanupResetsIt() {
        var inbox = new BoomboxStateInbox();
        for (int i = 1; i <= 2_000; i++) {
            assertTrue(inbox.accept(packet(new UUID(0, i), i, false)));
        }
        assertTrue(inbox.snapshot().isEmpty());
        assertFalse(inbox.accept(packet(OWNER, 1L, true)));
        assertTrue(inbox.accept(packet(OWNER, 2_001L, true)));
        inbox.clearAll();
        assertTrue(inbox.accept(packet(new UUID(0, 257), 1L, true)));
    }

    @Test
    void retainedDimensionsAndOwnersAreIndependentBelowTheWrapSafeRetirementFloor() {
        var inbox = new BoomboxStateInbox();
        assertTrue(inbox.accept(packet(OWNER, Long.MAX_VALUE, true)));
        var other = new ClientboundBoomboxStatePacket(Level.NETHER, 1, OWNER,
                new PlaybackState(Long.MAX_VALUE - 10L, Optional.of(PROGRAM), true));
        assertTrue(inbox.accept(other));
        assertTrue(inbox.accept(packet(OWNER, Long.MIN_VALUE, false)));
        assertFalse(inbox.accept(packet(OWNER, Long.MAX_VALUE, true)));
        assertTrue(inbox.accept(new ClientboundBoomboxStatePacket(Level.NETHER, 1, OWNER,
                new PlaybackState(Long.MAX_VALUE - 9L, Optional.of(PROGRAM), true))));
        assertNull(inbox.get(KEY));
        assertTrue(inbox.accept(packet(new UUID(0, 2), Long.MIN_VALUE + 1L, true)));
    }

    @Test
    void allRetainedOwnersStayBoundedButRetirementAndStopsStillMakeProgress() {
        var inbox = new BoomboxStateInbox();
        for (int i = 1; i <= 256; i++) {
            UUID owner = new UUID(0, i);
            assertTrue(inbox.accept(packet(owner, i, true)));
            inbox.get(PlaybackOwnerKey.entity(Level.OVERWORLD, owner)).activate();
        }
        UUID extra = new UUID(0, 257);
        assertFalse(inbox.accept(packet(extra, 1_000L, true)));
        assertTrue(inbox.accept(packet(extra, 300L, false)));
        assertTrue(inbox.accept(packet(OWNER, 301L, false)));
        assertTrue(inbox.accept(packet(extra, 302L, true)));
        assertFalse(inbox.accept(packet(OWNER, 1L, true)));
        assertTrue(inbox.accept(packet(new UUID(0, 2), 3L, true))); // Known active owner below the floor is retained.
    }

    @Test
    void expiryAndLocalRetirementReleaseIdentityWithoutAllowingOldPacketsBack() {
        var inbox = new BoomboxStateInbox();
        for (int i = 1; i <= 1_000; i++) {
            UUID owner = new UUID(0, i);
            var key = PlaybackOwnerKey.entity(Level.OVERWORLD, owner);
            assertTrue(inbox.accept(packet(owner, i, true)));
            var entry = inbox.get(key);
            if (i % 2 == 0) {
                entry.activate();
                entry.retire(); // Entity leave or source-capacity failure.
            } else {
                for (int tick = 0; tick < 100; tick++) {
                    entry.tickPending();
                }
            }
            inbox.compactRetired();
            assertNull(inbox.get(key));
            assertFalse(inbox.accept(packet(owner, i, true)));
        }
        assertTrue(inbox.snapshot().isEmpty());
        assertTrue(inbox.accept(packet(OWNER, 1_001L, true)));
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
