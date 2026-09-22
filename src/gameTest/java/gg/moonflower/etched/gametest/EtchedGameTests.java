package gg.moonflower.etched.gametest;

import gg.moonflower.etched.api.record.PlayableRecord;
import gg.moonflower.etched.core.Etched;
import gg.moonflower.etched.core.registry.EtchedBlocks;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.world.item.Items;
import net.minecraftforge.fml.ModList;
import net.minecraftforge.gametest.GameTestHolder;
import net.minecraftforge.gametest.PrefixGameTestTemplate;
import net.minecraftforge.registries.ForgeRegistries;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

@GameTestHolder(Etched.MOD_ID)
@PrefixGameTestTemplate(false)
public final class EtchedGameTests {

    private EtchedGameTests() {
    }

    @GameTest(template = "empty", timeoutTicks = 20)
    public static void forgeRuntimeLoadsModAndCommonMixins(GameTestHelper helper) {
        helper.assertTrue(ModList.get().isLoaded(Etched.MOD_ID), "Etched was not loaded by Forge");
        helper.assertTrue(
                ForgeRegistries.BLOCKS.getValue(EtchedBlocks.RADIO.getId()) == EtchedBlocks.RADIO.get(),
                "Etched blocks were not registered");
        helper.assertTrue(Items.MUSIC_DISC_13 instanceof PlayableRecord, "Etched common mixins were not applied");
        try {
            Files.writeString(Path.of("etched-gametest-success"), "passed\n");
        } catch (IOException exception) {
            throw new IllegalStateException("Could not record the Etched GameTest result", exception);
        }
        helper.succeed();
    }
}
