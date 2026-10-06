package gg.moonflower.etched.client.radio;

import gg.moonflower.etched.common.audio.PlaybackRevision;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.*;

class PlaybackRevisionHistoryTest {

    @Test
    void thousandsOfRetiredOwnersFreeSlotsWithoutReAdmittingOldPublications() {
        var history = new PlaybackRevisionHistory<Integer>();
        for (int i = 0; i < 10_000; i++) {
            long revision = i + 1L;
            assertTrue(history.accept(i, revision, true));
            history.release(i);
            assertFalse(history.accept(i, revision, true));
            assertFalse(history.accept(i, revision - 1L, false));
        }
        assertFalse(history.accept(0, 1L, true));
        assertFalse(history.accept(-1, 9_999L, true)); // Conservative rejection of unseen delayed state too.
        assertTrue(history.accept(0, 10_001L, true)); // Fresh tracking snapshot, not a TTL/LRU exception.
    }

    @Test
    void knownActiveAndPendingOwnersKeepTheirOwnOrderBelowAnotherRetirementFloor() {
        var history = new PlaybackRevisionHistory<String>();
        assertTrue(history.accept("active", 1L, true));
        assertTrue(history.accept("pending", 2L, true));
        assertTrue(history.accept("retired", 100L, false));
        assertTrue(history.accept("active", 3L, true));
        assertTrue(history.accept("pending", 4L, true));
        assertFalse(history.accept("active", 3L, true));
        assertFalse(history.accept("unseen", 99L, true));
        history.release("active");
        assertFalse(history.accept("active", 5L, true)); // A low retirement cannot move the floor backwards.
        assertTrue(history.accept("active", 101L, true));
        assertTrue(history.accept("pending", 5L, false));
        assertFalse(history.accept("pending", 100L, true));
    }

    @Test
    void activeBoundNeverEvictsButStopsNeedNoSlotAndReleaseMakesRoomForFreshOwners() {
        var history = new PlaybackRevisionHistory<Integer>();
        for (int i = 0; i < 256; i++) {
            assertTrue(history.accept(i, 1L, true));
        }
        assertFalse(history.accept(256, 2L, true));
        assertTrue(history.accept(256, 100L, false));
        assertFalse(history.accept(256, 100L, true));
        assertTrue(history.accept(0, 2L, true));
        assertFalse(history.accept(257, 101L, true));
        history.release(0);
        assertTrue(history.accept(257, 101L, true));
        assertFalse(history.accept(0, 99L, true));
    }

    @Test
    void capacityRejectionAndUnknownReleaseDoNotPoisonHistory() {
        var history = new PlaybackRevisionHistory<Integer>();
        history.release(-1);
        for (int i = 0; i < 256; i++) {
            assertTrue(history.accept(i, 1L, true));
        }
        assertFalse(history.accept(256, 1_000_000L, true));
        history.release(0);
        assertTrue(history.accept(256, 2L, true));
        assertFalse(history.accept(256, 1L, false));
        assertTrue(history.accept(256, 3L, true));
    }

    @Test
    void floorIsWrapSafeAndRejectsHalfRangeAmbiguity() {
        var history = new PlaybackRevisionHistory<Integer>();
        assertTrue(history.accept(0, Long.MAX_VALUE, true));
        history.release(0);
        assertTrue(history.accept(1, Long.MIN_VALUE, false));
        assertFalse(history.accept(0, Long.MAX_VALUE, true));
        assertFalse(history.accept(1, Long.MIN_VALUE, true));
        assertFalse(history.accept(2, 0L, true)); // Exactly half the serial-number space is unordered.
        assertTrue(history.accept(0, Long.MIN_VALUE + 1L, true));
        history.release(0);
        assertFalse(history.accept(2, Long.MIN_VALUE, true));
        assertTrue(history.accept(2, Long.MIN_VALUE + 2L, true));
    }

    @Test
    void worldCleanupResetsBothRetainedOwnersAndTheFloorAndNullKeysAreRejected() {
        var history = new PlaybackRevisionHistory<String>();
        assertTrue(history.accept("retired", 100L, false));
        assertTrue(history.accept("active", 101L, true));
        history.clearAll();
        assertTrue(history.accept("retired", 1L, true));
        assertTrue(history.accept("active", 1L, true));
        assertThrows(NullPointerException.class, () -> history.accept(null, 1L, true));
        assertThrows(NullPointerException.class, () -> history.release(null));
    }

    @Test
    void deterministicReorderedPublicationsAndRetirementsNeverForgetAnyAcceptedOwnerRevisionAcrossWrap() {
        var history = new PlaybackRevisionHistory<Integer>();
        var allAccepted = new HashMap<Integer, Long>();
        var random = new Random(0x5E7C4EDL);
        long clock = Long.MAX_VALUE - 100L;
        for (int i = 0; i < 20_000; i++) {
            int owner = random.nextInt(400);
            if (random.nextBoolean()) {
                history.release(owner);
            }
            clock = PlaybackRevision.next(clock);
            long revision = clock - random.nextInt(128); // Simulate a delayed packet, including across the long wrap.
            Long previous = allAccepted.get(owner);
            boolean accepted = history.accept(owner, revision, random.nextBoolean());
            if (accepted) {
                assertTrue(previous == null || PlaybackRevision.isNewer(revision, previous),
                        "Forgot an accepted revision for owner " + owner);
                allAccepted.put(owner, revision);
            }
        }
        for (int owner = 0; owner < 400; owner++) {
            history.release(owner);
        }
        assertTrue(history.accept(400, PlaybackRevision.next(clock), true));
    }
}
