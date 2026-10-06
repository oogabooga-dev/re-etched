package gg.moonflower.etched.common.audio;

import net.minecraft.nbt.CompoundTag;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class ServerPlaybackClockTest {

    @Test
    void allocationsAdvanceAndDirtyTheSavedClock() {
        ServerPlaybackClock clock = new ServerPlaybackClock();
        assertEquals(0L, clock.current());
        assertFalse(clock.isDirty());
        assertEquals(1L, clock.next());
        assertTrue(clock.isDirty());
        clock.setDirty(false);
        assertEquals(2L, clock.next());
        assertTrue(clock.isDirty());
    }

    @Test
    void diskRoundTripContinuesRatherThanResettingOwnerRevisions() {
        ServerPlaybackClock clock = new ServerPlaybackClock();
        clock.next();
        clock.next();
        CompoundTag saved = clock.save(new CompoundTag());
        ServerPlaybackClock restored = ServerPlaybackClock.load(saved);
        assertEquals(2L, restored.current());
        assertFalse(restored.isDirty());
        assertEquals(3L, restored.next());
        assertTrue(PlaybackRevision.isNewer(restored.current(), clock.current()));
    }

    @Test
    void missingOrWrongTypedRevisionStartsAnEmptyClock() {
        assertEquals(1L, ServerPlaybackClock.load(new CompoundTag()).next());
        CompoundTag malformed = new CompoundTag();
        malformed.putString("Revision", "invalid");
        assertEquals(1L, ServerPlaybackClock.load(malformed).next());
    }

    @Test
    void allocationsAndDiskPersistencePreserveWrapSafeOrdering() {
        CompoundTag saved = new CompoundTag();
        saved.putLong("Revision", Long.MAX_VALUE);
        ServerPlaybackClock clock = ServerPlaybackClock.load(saved);
        assertEquals(Long.MIN_VALUE, clock.next());
        assertTrue(PlaybackRevision.isNewer(clock.current(), Long.MAX_VALUE));
        ServerPlaybackClock restored = ServerPlaybackClock.load(clock.save(new CompoundTag()));
        assertEquals(Long.MIN_VALUE + 1L, restored.next());
    }
}
