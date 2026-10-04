package gg.moonflower.etched.client.render.item;

import com.mojang.blaze3d.platform.NativeImage;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class AlbumImageProcessorTest {

    @Test
    void overlayLookupFailureClosesDecodedNativeImageAndPreservesCause() {
        NativeImage source = new NativeImage(2, 2, true);
        IllegalStateException failure = new IllegalStateException("fixture overlay lookup failure");
        assertSame(failure, assertThrows(IllegalStateException.class,
                () -> AlbumImageProcessor.apply(source, () -> { throw failure; })));
        assertClosed(source);
    }

    @Test
    void overlayLookupErrorAndMissingOverlayAlsoCloseDecodedImage() {
        NativeImage source = new NativeImage(2, 2, true);
        LinkageError failure = new LinkageError("fixture overlay linkage");
        assertSame(failure, assertThrows(LinkageError.class,
                () -> AlbumImageProcessor.apply(source, () -> { throw failure; })));
        assertClosed(source);
        NativeImage another = new NativeImage(2, 2, true);
        assertThrows(NullPointerException.class, () -> AlbumImageProcessor.apply(another, () -> null));
        assertClosed(another);
    }

    @Test
    void pixelFailureClosesSource() {
        NativeImage source = new NativeImage(2, 2, true);
        try (NativeImage overlay = new NativeImage(1, 1, true)) {
            overlay.close();
            assertThrows(IllegalStateException.class, () -> AlbumImageProcessor.apply(source, overlay));
            assertThrows(IllegalStateException.class, () -> source.setPixelRGBA(0, 0, -1));
        }
    }

    @Test
    void successfulProcessingClosesSourceAndTransfersUsableOutputWithoutOwningOverlay() {
        NativeImage source = new NativeImage(2, 2, true);
        for (int x = 0; x < 2; x++) {
            for (int y = 0; y < 2; y++) {
                source.setPixelRGBA(x, y, -1);
            }
        }
        try (NativeImage overlay = new NativeImage(1, 1, true)) {
            overlay.setPixelRGBA(0, 0, -1);
            try (NativeImage output = AlbumImageProcessor.apply(source, overlay)) {
                assertClosed(source);
                assertEquals(1, output.getWidth());
                assertEquals(0xFFF0F0F0, output.getPixelRGBA(0, 0));
                assertEquals(-1, overlay.getPixelRGBA(0, 0));
            }
        }
    }

    private static void assertClosed(NativeImage image) {
        assertThrows(IllegalStateException.class, () -> image.getPixelRGBA(0, 0));
    }

    @Test
    void ownedOverlayProcessingClosesBothInputsAndTransfersOnlyTheOutput() {
        NativeImage source = new NativeImage(2, 2, true);
        NativeImage overlay = new NativeImage(1, 1, true);
        try (NativeImage result = AlbumImageProcessor.applyOwnedOverlay(source, () -> overlay)) {
            assertClosed(source);
            assertClosed(overlay);
            assertDoesNotThrow(() -> result.setPixelRGBA(0, 0, -1));
        }
    }

    @Test
    void ownedOverlayLookupAndPixelFailuresCloseRequestOwnedInputs() {
        NativeImage source = new NativeImage(2, 2, true);
        LinkageError failure = new LinkageError("fixture snapshot failure");
        assertSame(failure, assertThrows(LinkageError.class,
                () -> AlbumImageProcessor.applyOwnedOverlay(source, () -> { throw failure; })));
        assertClosed(source);
        NativeImage another = new NativeImage(2, 2, true);
        NativeImage overlay = new NativeImage(1, 1, true);
        overlay.close();
        assertThrows(IllegalStateException.class, () -> AlbumImageProcessor.applyOwnedOverlay(another, () -> overlay));
        assertClosed(another);
    }
}
