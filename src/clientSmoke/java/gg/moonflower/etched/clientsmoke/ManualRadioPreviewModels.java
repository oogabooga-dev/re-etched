package gg.moonflower.etched.clientsmoke;

import com.mojang.logging.LogUtils;
import gg.moonflower.etched.core.Etched;
import net.minecraft.client.Minecraft;
import net.minecraft.client.resources.model.BakedModel;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.RandomSource;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.client.event.ModelEvent;
import net.minecraftforge.client.model.data.ModelData;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

/** Separate preview asset: never replaces the production radio or its block states. */
@Mod.EventBusSubscriber(modid = Etched.MOD_ID, value = Dist.CLIENT, bus = Mod.EventBusSubscriber.Bus.MOD)
public final class ManualRadioPreviewModels {

    private static final ResourceLocation MODEL = ResourceLocation.fromNamespaceAndPath(Etched.MOD_ID,
            "block/manual_radio_preview");

    private ManualRadioPreviewModels() {
    }

    @SubscribeEvent
    public static void register(ModelEvent.RegisterAdditional event) {
        event.register(MODEL);
    }

    static boolean enabled() {
        return "1".equals(System.getenv("ETCHED_CLIENT_SMOKE_RADIO_MANUAL_PREVIEW"));
    }

    static BakedModel model(Minecraft client) {
        return client.getModelManager().getModel(MODEL);
    }

    static void verify(Minecraft client) {
        var model = model(client);
        if (model == client.getModelManager().getMissingModel()) {
            throw new AssertionError("Missing manual radio preview model");
        }
        var quads = model.getQuads(null, null, RandomSource.create(0), ModelData.EMPTY, null);
        if (quads.size() != 20) {
            throw new AssertionError("Expected 20 retained manual radio faces, got " + quads.size());
        }
        var surface = ResourceLocation.fromNamespaceAndPath(Etched.MOD_ID, "block/radio");
        for (var quad : quads) {
            if (!quad.getSprite().contents().name().equals(surface)) {
                throw new AssertionError("Unresolved manual radio preview surface");
            }
        }
        if (!model.getParticleIcon(ModelData.EMPTY).contents().name().equals(
                ResourceLocation.fromNamespaceAndPath(Etched.MOD_ID, "block/radio_particle"))) {
            throw new AssertionError("Unresolved manual radio preview particle");
        }
        LogUtils.getLogger().info("Manual radio preview baking passed: four source cuboids, 20 retained faces");
    }
}
