package gg.moonflower.etched.clientsmoke;

import com.mojang.blaze3d.platform.Lighting;
import com.mojang.blaze3d.platform.NativeImage;
import com.mojang.math.Axis;
import gg.moonflower.etched.common.block.RadioBlock;
import gg.moonflower.etched.core.registry.EtchedBlocks;
import net.minecraft.client.Minecraft;
import net.minecraft.client.Screenshot;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.renderer.LightTexture;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.ItemStack;
import net.minecraftforge.client.model.data.ModelData;

import java.io.IOException;
import java.nio.file.Path;

/** Optional real-engine model preview, not a generated art replacement or a world-playback test. */
final class RadioAssetPreviewScreen extends Screen {

    private int renderedFrames;

    RadioAssetPreviewScreen() {
        super(Component.literal("Manual radio artwork: model preview"));
    }

    @Override
    public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        graphics.fill(0, 0, this.width, this.height, 0xFF18222C);
        graphics.drawCenteredString(this.font, this.title, this.width / 2, 12, 0xFFFFFF);
        graphics.drawCenteredString(this.font, "Hand-drawn PNGs / existing AI-assisted geometry", this.width / 2, 26, 0xB9C9D5);
        renderModel(graphics, 0, this.width / 4);
        renderModel(graphics, 8, this.width * 3 / 4);
        graphics.drawCenteredString(this.font, "Inventory icon", this.width / 2, this.height - 45, 0xFFFFFF);
        graphics.renderItem(new ItemStack(EtchedBlocks.RADIO.get()), this.width / 2 - 8, this.height - 30);
        graphics.flush();
        if (this.minecraft.getOverlay() == null) {
            this.renderedFrames++;
        } else {
            this.renderedFrames = 0;
        }
    }

    private void renderModel(GuiGraphics graphics, int rotation, int centerX) {
        graphics.drawCenteredString(this.font, "Rotation " + rotation, centerX, 48, 0xFFFFFF);
        graphics.flush();
        Lighting.setupFor3DItems();
        var pose = graphics.pose();
        pose.pushPose();
        try {
            pose.translate(centerX, this.height * 0.64, 100);
            float scale = Math.min(this.width * 0.30F, this.height * 0.55F);
            pose.scale(scale, -scale, scale);
            pose.mulPose(Axis.XP.rotationDegrees(30));
            pose.mulPose(Axis.YP.rotationDegrees(225));
            pose.translate(-0.5, -0.25, -0.5);
            var state = EtchedBlocks.RADIO.get().defaultBlockState().setValue(RadioBlock.ROTATION, rotation);
            this.minecraft.getBlockRenderer().renderSingleBlock(state, pose, graphics.bufferSource(),
                    LightTexture.FULL_BRIGHT, OverlayTexture.NO_OVERLAY, ModelData.EMPTY, null);
            graphics.flush();
        } finally {
            pose.popPose();
            Lighting.setupForFlatItems();
        }
    }

    boolean readyForCapture() {
        return this.minecraft.getOverlay() == null && this.renderedFrames >= 2;
    }

    void capture(Minecraft client) {
        try (NativeImage image = Screenshot.takeScreenshot(client.getMainRenderTarget())) {
            image.writeToFile(Path.of("/tmp/opencode/etched-manual-radio-client-preview.png"));
        } catch (IOException exception) {
            throw new IllegalStateException("Could not save the manual radio model preview", exception);
        }
    }
}
