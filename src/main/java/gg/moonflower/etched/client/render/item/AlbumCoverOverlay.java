package gg.moonflower.etched.client.render.item;

import com.mojang.blaze3d.platform.NativeImage;
import gg.moonflower.etched.client.cache.MediaValidators;

/** Immutable pixels: background cover processing never borrows a reload-owned native image. */
final class AlbumCoverOverlay {

    private final int width;
    private final int height;
    private final int[] pixels;

    AlbumCoverOverlay(NativeImage image) {
        this.width = image.getWidth();
        this.height = image.getHeight();
        if (this.width < 1 || this.height < 1 || this.width > MediaValidators.MAX_COVER_DIMENSION
                || this.height > MediaValidators.MAX_COVER_DIMENSION
                || (long) this.width * this.height > MediaValidators.MAX_COVER_PIXELS) {
            throw new IllegalArgumentException("Album overlay dimensions exceed the cover limit");
        }
        this.pixels = new int[this.width * this.height];
        for (int y = 0; y < this.height; y++) {
            for (int x = 0; x < this.width; x++) {
                this.pixels[y * this.width + x] = image.getPixelRGBA(x, y);
            }
        }
    }

    NativeImage copyImage() {
        NativeImage image = new NativeImage(this.width, this.height, true);
        try {
            for (int y = 0; y < this.height; y++) {
                for (int x = 0; x < this.width; x++) {
                    image.setPixelRGBA(x, y, this.pixels[y * this.width + x]);
                }
            }
            return image;
        } catch (RuntimeException | Error failure) {
            image.close();
            throw failure;
        }
    }
}
