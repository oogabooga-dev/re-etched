package gg.moonflower.etched.client.render.item;

import com.mojang.blaze3d.platform.NativeImage;
import net.minecraft.client.resources.model.ModelResourceLocation;
import net.minecraft.resources.ResourceLocation;
import org.jetbrains.annotations.ApiStatus;

/** Internal client handoff value, not an item/provider extension API. */
@ApiStatus.Internal
public sealed interface CoverDescriptor permits CoverDescriptor.Empty, ImageAlbumCover, ModelAlbumCover {

    CoverDescriptor EMPTY = Empty.INSTANCE;

    /** The successful recipient owns the image; failed/cancelled delivery remains the producer's responsibility. */
    static CoverDescriptor of(NativeImage image) {
        return new ImageAlbumCover(image);
    }

    static CoverDescriptor of(ModelResourceLocation model) {
        return new ModelAlbumCover(model);
    }

    static CoverDescriptor of(ResourceLocation model) {
        return of(new ModelResourceLocation(model, "inventory"));
    }

    enum Empty implements CoverDescriptor {
        INSTANCE
    }
}
