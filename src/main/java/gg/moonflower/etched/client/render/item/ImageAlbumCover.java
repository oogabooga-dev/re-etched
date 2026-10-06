package gg.moonflower.etched.client.render.item;

import com.mojang.blaze3d.platform.NativeImage;
import org.jetbrains.annotations.ApiStatus;

@ApiStatus.Internal
public record ImageAlbumCover(NativeImage image) implements CoverDescriptor {
    public ImageAlbumCover {
        java.util.Objects.requireNonNull(image, "image");
    }
}
