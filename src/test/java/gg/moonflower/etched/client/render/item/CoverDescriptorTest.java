package gg.moonflower.etched.client.render.item;

import com.mojang.blaze3d.platform.NativeImage;
import net.minecraft.client.resources.model.ModelResourceLocation;
import net.minecraft.resources.ResourceLocation;
import org.junit.jupiter.api.Test;

import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

class CoverDescriptorTest {

    @Test
    void descriptorHasOnlyInternalEmptyImageAndModelForms() {
        assertTrue(CoverDescriptor.class.isSealed());
        assertEquals(Set.of(CoverDescriptor.Empty.class, ImageAlbumCover.class, ModelAlbumCover.class),
                Set.of(CoverDescriptor.class.getPermittedSubclasses()));
        assertSame(CoverDescriptor.Empty.INSTANCE, CoverDescriptor.EMPTY);
    }

    @Test
    void modelFactoryPreservesNamespaceAndExplicitVariantAndDefaultsToInventory() {
        var location = ResourceLocation.parse("custom:etched_album_cover/record");
        assertEquals(new ModelResourceLocation(location, "inventory"),
                assertInstanceOf(ModelAlbumCover.class, CoverDescriptor.of(location)).model());
        var model = new ModelResourceLocation(location, "custom_variant");
        assertSame(model, assertInstanceOf(ModelAlbumCover.class, CoverDescriptor.of(model)).model());
    }

    @Test
    void imageDescriptorTransfersTheSameLiveImageWithoutCopyingOrClosingIt() {
        try (var image = new NativeImage(1, 1, true)) {
            image.setPixelRGBA(0, 0, -1);
            var cover = assertInstanceOf(ImageAlbumCover.class, CoverDescriptor.of(image));
            assertSame(image, cover.image());
            assertEquals(-1, cover.image().getPixelRGBA(0, 0));
        }
    }

    @Test
    void imageAndModelDescriptorsRejectMissingOwnedValues() {
        assertThrows(NullPointerException.class, () -> CoverDescriptor.of((NativeImage) null));
        assertThrows(NullPointerException.class, () -> CoverDescriptor.of((ModelResourceLocation) null));
    }
}
