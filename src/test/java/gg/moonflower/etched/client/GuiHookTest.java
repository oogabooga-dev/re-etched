package gg.moonflower.etched.client;

import net.minecraft.world.phys.Vec3;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class GuiHookTest {

    @Test
    void absentPlayerDoesNotSuppressPlayingText() {
        assertTrue(GuiHook.isWithinPlayingTextRange(null, new Vec3(100_000, -100_000, 100_000)));
    }

    @Test
    void rangeIsInclusiveAtSixtyFourBlocksFromThePlayer() {
        Vec3 player = new Vec3(12.5, 64, -37.5);
        assertTrue(GuiHook.isWithinPlayingTextRange(player, player));
        assertTrue(GuiHook.isWithinPlayingTextRange(player, player.add(63.999, 0, 0)));
        assertTrue(GuiHook.isWithinPlayingTextRange(player, player.add(64, 0, 0)));
        assertFalse(GuiHook.isWithinPlayingTextRange(player, player.add(64.001, 0, 0)));
    }

    @Test
    void rangeUsesAllThreeCoordinatesRatherThanAxisOrHorizontalDistance() {
        assertTrue(GuiHook.isWithinPlayingTextRange(Vec3.ZERO, new Vec3(32, 32, 32)));
        assertFalse(GuiHook.isWithinPlayingTextRange(Vec3.ZERO, new Vec3(40, 40, 40)));
        assertTrue(GuiHook.isWithinPlayingTextRange(Vec3.ZERO, new Vec3(0, -64, 0)));
        assertFalse(GuiHook.isWithinPlayingTextRange(Vec3.ZERO, new Vec3(0, -64.001, 0)));
    }
}
