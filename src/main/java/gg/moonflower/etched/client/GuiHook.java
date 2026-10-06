package gg.moonflower.etched.client;

import net.minecraft.client.Minecraft;
import net.minecraft.world.phys.Vec3;
import org.jetbrains.annotations.ApiStatus;
import org.jetbrains.annotations.Nullable;

@ApiStatus.Internal
public class GuiHook {

    private static final double PLAYING_TEXT_RANGE_SQUARED = 64.0 * 64.0;
    private static boolean hidePlayingText = false;

    /** Shared visibility rule for vanilla record text and managed playback overlays. */
    public static boolean canShowPlayingText(double x, double y, double z) {
        var player = Minecraft.getInstance().player;
        return isWithinPlayingTextRange(player == null ? null : player.position(), new Vec3(x, y, z));
    }

    static boolean isWithinPlayingTextRange(@Nullable Vec3 playerPosition, Vec3 sourcePosition) {
        return playerPosition == null || playerPosition.distanceToSqr(sourcePosition) <= PLAYING_TEXT_RANGE_SQUARED;
    }

    public static void setHidePlayingText(boolean hidePlayingText) {
        GuiHook.hidePlayingText = hidePlayingText;
    }

    public static boolean isHidePlayingText() {
        return hidePlayingText;
    }
}
