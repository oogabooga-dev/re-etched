package gg.moonflower.etched.client.render.item;

import com.mojang.blaze3d.platform.NativeImage;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class AlbumCoverOverlayTest {

    @Test
    void snapshotsSurviveReloadSourceClosureAndHaveIndependentNativeCopies() {
        NativeImage source = new NativeImage(1, 1, true);
        source.setPixelRGBA(0, 0, 0xFF123456);
        AlbumCoverOverlay original = new AlbumCoverOverlay(source);
        source.setPixelRGBA(0, 0, -1);
        AlbumCoverOverlay replacement = new AlbumCoverOverlay(source);
        source.close();
        try (NativeImage one = original.copyImage(); NativeImage two = original.copyImage();
             NativeImage newer = replacement.copyImage()) {
            assertEquals(0xFF123456, one.getPixelRGBA(0, 0));
            assertEquals(0xFF123456, two.getPixelRGBA(0, 0));
            assertEquals(-1, newer.getPixelRGBA(0, 0));
            one.setPixelRGBA(0, 0, 0);
            assertEquals(0xFF123456, two.getPixelRGBA(0, 0));
            one.close();
            assertEquals(0xFF123456, two.getPixelRGBA(0, 0));
        }
    }

    @Test
    void excessiveOverlayDimensionsAreRejectedWithoutTakingOwnershipFromTheCaller() {
        try (NativeImage source = new NativeImage(2049, 1, true)) {
            assertThrows(IllegalArgumentException.class, () -> new AlbumCoverOverlay(source));
            assertDoesNotThrow(() -> source.setPixelRGBA(0, 0, -1));
        }
    }
}
