package gg.moonflower.etched.common.item;

import gg.moonflower.etched.client.radio.MinecraftTestBootstrap;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.RecordItem;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class JukeboxRecordSupportTest {

    static {
        MinecraftTestBootstrap.bootStrap();
    }

    @Test
    @SuppressWarnings("deprecation") // Use the bootstrapped registry without a Forge runtime in this unit harness.
    void allActualVanillaDiscsUseTheAuthoritativePacketPath() {
        int discs = 0;
        for (Item item : BuiltInRegistries.ITEM) {
            if (item instanceof RecordItem record && VanillaRecordAdapter.isVanilla(record)) {
                assertTrue(JukeboxRecordSupport.requiresPlaybackPacket(record));
                discs++;
            }
        }
        assertTrue(discs >= 16);
    }

    @Test
    void nonNativeUnsupportedReplacementsStillUsePacketsButAirDoesNot() {
        assertTrue(JukeboxRecordSupport.requiresPlaybackPacket(Items.PAPER));
        assertTrue(JukeboxRecordSupport.requiresPlaybackPacket(Items.STONE));
        assertFalse(JukeboxRecordSupport.requiresPlaybackPacket(Items.AIR));
        assertFalse(JukeboxRecordSupport.requiresPlaybackPacket(null));
    }
}
