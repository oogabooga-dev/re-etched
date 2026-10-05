package gg.moonflower.etched.clientsmoke;

import com.mojang.blaze3d.platform.NativeImage;
import gg.moonflower.etched.client.render.item.AlbumCoverItemRenderer;
import gg.moonflower.etched.client.render.item.AlbumImageProcessor;
import gg.moonflower.etched.core.Etched;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.TitleScreen;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

@Mod.EventBusSubscriber(modid = Etched.MOD_ID, value = Dist.CLIENT)
public final class EtchedClientSmoke {

    private static boolean complete;
    private static boolean overlayChecked;

    private EtchedClientSmoke() {
    }

    @SubscribeEvent
    public static void onClientTick(TickEvent.ClientTickEvent event) {
        if (event.phase != TickEvent.Phase.END) {
            return;
        }
        Minecraft client = Minecraft.getInstance();
        if (!overlayChecked && client.screen instanceof TitleScreen) {
            verifyCoverOverlay();
            overlayChecked = true;
        }
        if (!PlaybackStopSmoke.tick(client)) {
            return;
        }
        if ("1".equals(System.getenv("ETCHED_CLIENT_SMOKE_JUKEBOX"))) {
            JukeboxPacketSmoke.tick(client);
            return;
        }
        if (complete || !(client.screen instanceof TitleScreen)) {
            return;
        }

        complete = true;
        try {
            Files.writeString(Path.of("etched-client-smoke-success"), "passed\n");
        } catch (IOException exception) {
            throw new IllegalStateException("Could not record the Etched client smoke result", exception);
        }
        client.stop();
    }

    private static void verifyCoverOverlay() {
        try (NativeImage first = AlbumCoverItemRenderer.copyOverlayImage();
             NativeImage second = AlbumCoverItemRenderer.copyOverlayImage()) {
            int expected = second.getPixelRGBA(0, 0);
            first.setPixelRGBA(0, 0, ~expected);
            first.close();
            if (second.getPixelRGBA(0, 0) != expected) {
                throw new AssertionError("Cover overlay consumers share native pixels");
            }
            NativeImage decoded = new NativeImage(1, 1, true);
            decoded.setPixelRGBA(0, 0, -1);
            try (NativeImage processed = AlbumImageProcessor.applyOwnedOverlay(decoded,
                    AlbumCoverItemRenderer::copyOverlayImage)) {
                if (processed.getWidth() != second.getWidth() || processed.getHeight() != second.getHeight()) {
                    throw new AssertionError("Cover processing changed overlay dimensions");
                }
                processed.getPixelRGBA(0, 0);
            }
            try {
                decoded.getPixelRGBA(0, 0);
                throw new AssertionError("Cover processing retained its decoded native input");
            } catch (IllegalStateException expectedClosed) {
                // Input ownership ended before publication of the processed image.
            }
        }
    }
}
