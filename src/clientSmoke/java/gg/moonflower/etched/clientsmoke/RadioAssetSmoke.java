package gg.moonflower.etched.clientsmoke;

import com.mojang.logging.LogUtils;
import gg.moonflower.etched.common.block.RadioBlock;
import gg.moonflower.etched.core.Etched;
import gg.moonflower.etched.core.registry.EtchedBlocks;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.block.model.BakedQuad;
import net.minecraft.client.resources.model.BakedModel;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.RandomSource;
import net.minecraft.world.item.ItemStack;
import net.minecraftforge.client.model.data.ModelData;

/** Actual Forge model baking/atlas check; no world or playback session is needed. */
final class RadioAssetSmoke {

    private RadioAssetSmoke() {
    }

    static void verify(Minecraft client) {
        var block = EtchedBlocks.RADIO.get();
        for (boolean portal : new boolean[]{false, true}) {
            String name = portal ? "portal_radio" : "radio";
            for (int rotation = 0; rotation < 16; rotation++) {
                for (boolean powered : new boolean[]{false, true}) {
                    var state = block.defaultBlockState().setValue(RadioBlock.PORTAL, portal)
                            .setValue(RadioBlock.ROTATION, rotation).setValue(RadioBlock.POWERED, powered);
                    var model = client.getBlockRenderer().getBlockModel(state);
                    require(model != client.getModelManager().getMissingModel(), "Missing radio state model: " + state);
                    require(model.getParticleIcon(ModelData.EMPTY).contents().name().equals(id("block/" + name + "_particle")),
                            "Wrong/missing particle texture: " + state);
                    require(model.getParticleIcon(ModelData.EMPTY).contents().width() == 16, "Wrong particle width: " + state);
                    var quads = model.getQuads(state, null, RandomSource.create(0), ModelData.EMPTY, null);
                    require(quads.size() == 42, "Expected seven six-faced radio cuboids: " + state);
                    for (BakedQuad quad : quads) {
                        require(quad.getSprite().contents().name().equals(id("block/" + name)),
                                "Wrong/missing atlas surface: " + state);
                        require(quad.getSprite().contents().width() == 32, "Wrong block atlas width: " + state);
                    }
                }
            }
            ItemStack stack = new ItemStack(portal ? EtchedBlocks.PORTAL_RADIO_ITEM.get() : block.asItem());
            BakedModel item = client.getItemRenderer().getItemModelShaper().getItemModel(stack);
            require(item != client.getModelManager().getMissingModel(), "Missing " + name + " item model");
            require(!item.getQuads(null, null, RandomSource.create(0), ModelData.EMPTY, null).isEmpty(), "Empty " + name + " item mesh");
            require(item.getParticleIcon(ModelData.EMPTY).contents().name().equals(id("item/" + name)), "Missing " + name + " item sprite");
        }
        LogUtils.getLogger().info("Radio asset smoke passed: 64 block states, two item models and replacement atlas sprites");
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
