package gg.moonflower.etched.client.render.item;

import net.minecraft.client.resources.model.ModelResourceLocation;
import org.jetbrains.annotations.ApiStatus;

@ApiStatus.Internal
public record ModelAlbumCover(ModelResourceLocation model) implements CoverDescriptor {
    public ModelAlbumCover {
        java.util.Objects.requireNonNull(model, "model");
    }
}
