package gg.moonflower.etched.clientsmoke;

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

    private EtchedClientSmoke() {
    }

    @SubscribeEvent
    public static void onClientTick(TickEvent.ClientTickEvent event) {
        Minecraft client = Minecraft.getInstance();
        if (complete || event.phase != TickEvent.Phase.END || !(client.screen instanceof TitleScreen)) {
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
}
