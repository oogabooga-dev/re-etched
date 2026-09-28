package gg.moonflower.etched.client.radio;

import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class JukeboxStartGateTest {

    static {
        MinecraftTestBootstrap.bootStrap();
    }

    private static final PlaybackOwnerKey.BlockOwner KEY = PlaybackOwnerKey.block(Level.OVERWORLD, BlockPos.ZERO);

    @Test
    void stoppedStartKeepsItsSlotEvenWhenReplacementUsesTheSameItemId() {
        JukeboxStartGate gate = new JukeboxStartGate();
        gate.start(KEY, 42, true); // Disc A event, packet delayed.
        gate.stop(KEY);           // A is ejected.
        gate.start(KEY, 42, true); // Disc B has the same item type but different NBT.

        assertFalse(gate.consume(KEY, 42)); // A packet cannot start B's session.
        assertTrue(gate.consume(KEY, 42));  // B packet claims B's own start.
        assertFalse(gate.consume(KEY, 42));
    }

    @Test
    void cancelledEventAndUnexpectedPacketsNeverStartPlayback() {
        JukeboxStartGate gate = new JukeboxStartGate();
        gate.start(KEY, 42, false); // Stale event after ejection.
        assertFalse(gate.consume(KEY, 42));
        assertFalse(gate.consume(KEY, 42));

        gate.start(KEY, 42, true);
        assertFalse(gate.consume(KEY, 43)); // Wrong disc cannot claim this start.
        assertFalse(gate.consume(KEY, 42));
    }

    @Test
    void ownersAreIndependentAndWorldCleanupDropsPendingStarts() {
        JukeboxStartGate gate = new JukeboxStartGate();
        PlaybackOwnerKey.BlockOwner other = PlaybackOwnerKey.block(Level.OVERWORLD, BlockPos.ZERO.above());
        gate.start(KEY, 42, true);
        gate.start(other, 42, true);
        gate.stop(KEY);
        assertTrue(gate.consume(other, 42));
        gate.clearAll();
        assertFalse(gate.consume(KEY, 42));
    }

    @Test
    void overflowFailsClosedUntilWorldCleanup() {
        JukeboxStartGate gate = new JukeboxStartGate();
        for (int i = 0; i < 65; i++) {
            gate.start(KEY, 42, true);
        }
        assertFalse(gate.consume(KEY, 42));
        gate.clearAll();
        gate.start(KEY, 42, true);
        assertTrue(gate.consume(KEY, 42));
    }
}
