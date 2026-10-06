package gg.moonflower.etched.client.radio;

import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class JukeboxRevisionGateTest {

    static {
        MinecraftTestBootstrap.bootStrap();
    }

    private static final PlaybackOwnerKey.BlockOwner KEY = PlaybackOwnerKey.block(Level.OVERWORLD, BlockPos.ZERO);

    @Test
    void rejectsDuplicateConflictingAndOlderRevisionsUntilWorldCleanup() {
        JukeboxRevisionGate gate = new JukeboxRevisionGate();
        assertTrue(gate.accept(KEY, 5L));
        assertFalse(gate.accept(KEY, 5L));
        assertFalse(gate.accept(KEY, 4L));
        assertTrue(gate.accept(KEY, 6L));
        assertFalse(gate.accept(KEY, 5L));
        gate.clearAll();
        assertTrue(gate.accept(KEY, 1L));
    }

    @Test
    void positionsAndDimensionsHaveIndependentWatermarks() {
        JukeboxRevisionGate gate = new JukeboxRevisionGate();
        assertTrue(gate.accept(KEY, 5L));
        assertTrue(gate.accept(PlaybackOwnerKey.block(Level.NETHER, BlockPos.ZERO), 1L));
        assertTrue(gate.accept(PlaybackOwnerKey.block(Level.OVERWORLD, BlockPos.ZERO.above()), 1L));
        assertFalse(gate.accept(KEY, 1L));
    }

    @Test
    void followsTheSameWrapSafeRevisionOrderAsTheRuntime() {
        JukeboxRevisionGate gate = new JukeboxRevisionGate();
        assertTrue(gate.accept(KEY, Long.MAX_VALUE));
        assertTrue(gate.accept(KEY, Long.MIN_VALUE));
        assertFalse(gate.accept(KEY, Long.MAX_VALUE));
        assertFalse(gate.accept(KEY, Long.MIN_VALUE));
        assertTrue(gate.accept(KEY, Long.MIN_VALUE + 1L));
    }

    @Test
    void boundedTombstonesFailClosedWithoutEvictingExistingOwners() {
        JukeboxRevisionGate gate = new JukeboxRevisionGate();
        for (int i = 0; i < 256; i++) {
            assertTrue(gate.accept(PlaybackOwnerKey.block(Level.OVERWORLD, new BlockPos(i, 0, 0)), 10L));
        }
        var extra = PlaybackOwnerKey.block(Level.OVERWORLD, new BlockPos(256, 0, 0));
        assertFalse(gate.accept(extra, 11L));
        assertFalse(gate.accept(KEY, 9L));
        assertTrue(gate.accept(KEY, 12L));
        gate.clearAll();
        assertTrue(gate.accept(extra, 1L));
    }

    @Test
    void retiredOwnersAreCompressedAndOnlyFreshServerPublicationsCanRestoreThem() {
        var gate = new JukeboxRevisionGate();
        for (int i = 0; i < 2_000; i++) {
            var key = PlaybackOwnerKey.block(Level.OVERWORLD, new BlockPos(i, 64, 0));
            assertTrue(gate.accept(key, i + 1L));
            gate.release(key);
            assertFalse(gate.accept(key, i + 1L));
        }
        assertFalse(gate.accept(KEY, 1L));
        assertTrue(gate.accept(KEY, 2_001L));
        assertTrue(gate.accept(KEY, 2_002L, false));
        assertFalse(gate.accept(KEY, 2_001L));
        assertTrue(gate.accept(KEY, 2_003L));
    }
}
