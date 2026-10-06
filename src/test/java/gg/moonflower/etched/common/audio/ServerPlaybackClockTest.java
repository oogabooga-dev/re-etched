package gg.moonflower.etched.common.audio;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtIo;
import net.minecraft.nbt.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Path;

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
    void nbtRoundTripContinuesRatherThanResettingOwnerRevisions() {
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
    void allocationsAndNbtPersistencePreserveWrapSafeOrdering() {
        CompoundTag saved = new CompoundTag();
        saved.putLong("Revision", Long.MAX_VALUE);
        ServerPlaybackClock clock = ServerPlaybackClock.load(saved);
        assertEquals(Long.MIN_VALUE, clock.next());
        assertTrue(PlaybackRevision.isNewer(clock.current(), Long.MAX_VALUE));
        ServerPlaybackClock restored = ServerPlaybackClock.load(clock.save(new CompoundTag()));
        assertEquals(Long.MIN_VALUE + 1L, restored.next());
    }

    @Test
    void savedDataFileReloadContinuesAcrossWrapAndPersistsSubsequentAllocations(@TempDir Path directory) throws IOException {
        var initial = new CompoundTag();
        initial.putLong("Revision", Long.MAX_VALUE);
        var clock = ServerPlaybackClock.load(initial);
        assertEquals(Long.MIN_VALUE, clock.next());
        var file = directory.resolve("etched_playback_clock.dat").toFile();
        clock.save(file);
        assertTrue(file.isFile());
        assertFalse(clock.isDirty());

        var disk = NbtIo.readCompressed(file).getCompound("data");
        assertTrue(disk.contains("Revision", Tag.TAG_LONG));
        var restored = ServerPlaybackClock.load(disk);
        assertNotSame(clock, restored);
        assertEquals(Long.MIN_VALUE, restored.current());
        assertFalse(restored.isDirty());
        assertEquals(Long.MIN_VALUE + 1L, restored.next());
        assertTrue(PlaybackRevision.isNewer(restored.current(), clock.current()));
        restored.save(file);

        var reopened = ServerPlaybackClock.load(NbtIo.readCompressed(file).getCompound("data"));
        assertEquals(Long.MIN_VALUE + 1L, reopened.current());
        assertEquals(Long.MIN_VALUE + 2L, reopened.next());
    }
}
