package gg.moonflower.etched.client.sound;

import gg.moonflower.etched.api.sound.SoundTracker;
import gg.moonflower.etched.common.item.BoomboxItem;
import gg.moonflower.etched.client.radio.RecordStreamLoader;
import gg.moonflower.etched.core.Etched;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.client.event.ClientPlayerNetworkEvent;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.event.GameShuttingDownEvent;
import net.minecraftforge.event.level.LevelEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

@Mod.EventBusSubscriber(value = Dist.CLIENT, modid = Etched.MOD_ID, bus = Mod.EventBusSubscriber.Bus.FORGE)
public final class RecordClientEvents {
    private RecordClientEvents() {
    }

    @SubscribeEvent
    public static void tick(TickEvent.ClientTickEvent event) {
        if (event.phase == TickEvent.Phase.END) {
            BoomboxItem.prunePlayingRecords();
            SoundTracker.pruneRecordSounds();
        }
    }

    @SubscribeEvent
    public static void logout(ClientPlayerNetworkEvent.LoggingOut event) {
        clear();
    }

    @SubscribeEvent
    public static void unload(LevelEvent.Unload event) {
        if (event.getLevel().isClientSide()) {
            clear();
        }
    }

    private static void clear() {
        BoomboxItem.clearPlayingRecords();
        SoundTracker.clearRecordSounds();
    }

    @SubscribeEvent
    public static void shutdown(GameShuttingDownEvent event) {
        clear();
        RecordStreamLoader.getInstance().shutdown();
    }
}
