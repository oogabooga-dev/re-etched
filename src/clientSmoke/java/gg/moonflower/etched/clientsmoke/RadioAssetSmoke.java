package gg.moonflower.etched.clientsmoke;

import com.mojang.blaze3d.platform.NativeImage;
import com.mojang.logging.LogUtils;
import gg.moonflower.etched.common.block.RadioBlock;
import gg.moonflower.etched.core.Etched;
import gg.moonflower.etched.core.registry.EtchedBlocks;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.block.model.BakedQuad;
import net.minecraft.client.resources.model.BakedModel;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.RandomSource;
import net.minecraft.world.inventory.InventoryMenu;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.EmptyBlockGetter;
import net.minecraft.world.phys.shapes.CollisionContext;
import net.minecraftforge.client.model.data.ModelData;

import java.io.IOException;

/** Actual Forge model baking/atlas check; no world or playback session is needed. */
final class RadioAssetSmoke {

    private RadioAssetSmoke() {
    }

    @SuppressWarnings("deprecation") // Compare baked geometry with the runtime block selection hook.
    static void verify(Minecraft client) {
        var block = EtchedBlocks.RADIO.get();
        for (int rotation = 0; rotation < 16; rotation++) {
            for (boolean powered : new boolean[]{false, true}) {
                var state = block.defaultBlockState().setValue(RadioBlock.ROTATION, rotation)
                        .setValue(RadioBlock.POWERED, powered);
                var model = client.getBlockRenderer().getBlockModel(state);
                require(model != client.getModelManager().getMissingModel(), "Missing radio state model: " + state);
                require(model.getParticleIcon(ModelData.EMPTY).contents().name().equals(id("block/radio_particle")),
                        "Wrong/missing particle texture: " + state);
                require(model.getParticleIcon(ModelData.EMPTY).contents().width() == 16, "Wrong particle width: " + state);
                var quads = model.getQuads(state, null, RandomSource.create(0), ModelData.EMPTY, null);
                require(quads.size() == 20, "Expected 20 retained faces from four manual radio cuboids: " + state);
                var bounds = state.getShape(EmptyBlockGetter.INSTANCE, BlockPos.ZERO, CollisionContext.empty()).bounds();
                for (BakedQuad quad : quads) {
                    require(quad.getSprite().contents().name().equals(id("block/radio")),
                            "Wrong/missing atlas surface: " + state);
                    require(quad.getSprite().contents().width() == 32, "Wrong block atlas width: " + state);
                    var vertices = quad.getVertices();
                    int stride = vertices.length / 4;
                    for (int vertex = 0; vertex < 4; vertex++) {
                        float x = Float.intBitsToFloat(vertices[vertex * stride]);
                        float y = Float.intBitsToFloat(vertices[vertex * stride + 1]);
                        float z = Float.intBitsToFloat(vertices[vertex * stride + 2]);
                        require(x >= bounds.minX - 0.00001 && x <= bounds.maxX + 0.00001
                                        && y >= bounds.minY - 0.00001 && y <= bounds.maxY + 0.00001
                                        && z >= bounds.minZ - 0.00001 && z <= bounds.maxZ + 0.00001,
                                "Baked manual radio vertex outside its selection envelope: " + state);
                    }
                }
            }
        }
        ItemStack stack = new ItemStack(block.asItem());
        BakedModel item = client.getItemRenderer().getItemModelShaper().getItemModel(stack);
        require(item != client.getModelManager().getMissingModel(), "Missing radio item model");
        require(!item.getQuads(null, null, RandomSource.create(0), ModelData.EMPTY, null).isEmpty(), "Empty radio item mesh");
        require(item.getParticleIcon(ModelData.EMPTY).contents().name().equals(id("item/radio")), "Missing radio item sprite");
        verifySpritePixels(client);
        LogUtils.getLogger().info("Radio asset smoke passed: 32 block states, one item model and 1536 exact manual image pixels");
    }

    private static void verifySpritePixels(Minecraft client) {
        for (String path : new String[]{"block/radio", "block/radio_particle", "item/radio"}) {
            var sprite = client.getTextureAtlas(InventoryMenu.BLOCK_ATLAS).apply(id(path));
            // The atlas sprite is engine-owned; close only our independently decoded reference.
            try (var input = client.getResourceManager().open(id("textures/" + path + ".png"));
                 var reference = NativeImage.read(input)) {
                require(reference.getWidth() == sprite.contents().width()
                                && reference.getHeight() == sprite.contents().height(),
                        "Sprite dimensions differ from the manual PNG: " + path);
                for (int y = 0; y < reference.getHeight(); y++) {
                    for (int x = 0; x < reference.getWidth(); x++) {
                        require(reference.getPixelRGBA(x, y) == sprite.getPixelRGBA(0, x, y),
                                "Sprite pixels differ from the manual PNG: " + path + " at " + x + "," + y);
                    }
                }
            } catch (IOException exception) {
                throw new IllegalStateException("Could not check the manual radio sprite " + path, exception);
            }
        }
    }

    private static ResourceLocation id(String path) {
        return ResourceLocation.fromNamespaceAndPath(Etched.MOD_ID, path);
    }

    private static void require(boolean condition, String message) {
        if (!condition) {
            throw new AssertionError(message);
        }
    }
}
