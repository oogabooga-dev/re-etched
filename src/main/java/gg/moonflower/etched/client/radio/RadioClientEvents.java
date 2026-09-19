package gg.moonflower.etched.client.radio;

import gg.moonflower.etched.core.Etched;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.client.event.ClientPlayerNetworkEvent;
import net.minecraftforge.event.level.LevelEvent;
import net.minecraftforge.event.GameShuttingDownEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

@Mod.EventBusSubscriber(value = Dist.CLIENT, modid = Etched.MOD_ID, bus = Mod.EventBusSubscriber.Bus.FORGE)
public final class RadioClientEvents {

    private RadioClientEvents() {
    }

    @SubscribeEvent
    public static void onLogout(ClientPlayerNetworkEvent.LoggingOut event) {
        RadioClientRuntime.getInstance().logout();
    }

    @SubscribeEvent
    public static void onLevelUnload(LevelEvent.Unload event) {
        if (event.getLevel().isClientSide()) {
            RadioClientRuntime.getInstance().clearAll();
        }
    }

    @SubscribeEvent
    public static void onGameShuttingDown(GameShuttingDownEvent event) {
        RadioClientRuntime.getInstance().shutdown();
    }
}
