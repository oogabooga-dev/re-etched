package gg.moonflower.etched.common.menu;

import gg.moonflower.etched.common.audio.AudioCancellation;
import gg.moonflower.etched.api.record.PlayableRecord;
import gg.moonflower.etched.core.Etched;
import gg.moonflower.etched.core.registry.EtchedItems;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraftforge.gametest.GameTestHolder;
import net.minecraftforge.gametest.PrefixGameTestTemplate;

import java.util.concurrent.CompletableFuture;

@GameTestHolder(Etched.MOD_ID)
@PrefixGameTestTemplate(false)
public final class EtchingMenuGameTests {

    private EtchingMenuGameTests() {
    }

    @GameTest(template = "empty", timeoutTicks = 100)
    public static void localSoundEtchingStillPublishesForTheOpenMenu(GameTestHelper helper) {
        Player player = helper.makeMockSurvivalPlayer();
        EtchingMenu menu = new EtchingMenu(1, player.getInventory());
        player.containerMenu = menu;
        menu.setUrl("minecraft:music_disc.blocks");
        menu.getSlot(0).set(new ItemStack(EtchedItems.ETCHED_MUSIC_DISC.get()));
        helper.succeedWhen(() -> {
            ItemStack result = menu.getSlot(2).getItem();
            helper.assertTrue(result.is(EtchedItems.ETCHED_MUSIC_DISC.get()), "Open menu has no etching result yet");
            var tracks = PlayableRecord.getStackMusic(result).orElseThrow();
            helper.assertTrue(tracks.length == 1 && tracks[0].url().equals("minecraft:music_disc.blocks"),
                    "Etching lost the submitted local sound");
        });
    }

    @GameTest(template = "empty", timeoutTicks = 100)
    public static void replacementAndCloseCancelEtchingRequests(GameTestHelper helper) {
        Player player = helper.makeMockSurvivalPlayer();
        EtchingMenu menu = new EtchingMenu(1, player.getInventory());
        player.containerMenu = menu;
        menu.setUrl("minecraft:music_disc.blocks");
        menu.getSlot(0).set(new ItemStack(EtchedItems.ETCHED_MUSIC_DISC.get()));
        AudioCancellation first = field(menu, "currentCancellation", AudioCancellation.class);
        CompletableFuture<?> firstRequest = field(menu, "currentRequest", CompletableFuture.class);

        menu.setUrl("minecraft:music_disc.cat");
        helper.assertTrue(first.isCancelled(), "Changing the URL did not cancel the previous request");
        AudioCancellation second = field(menu, "currentCancellation", AudioCancellation.class);
        CompletableFuture<?> secondRequest = field(menu, "currentRequest", CompletableFuture.class);
        helper.assertTrue(second != first && !second.isCancelled(), "Replacement did not get a fresh token");

        menu.removed(player);
        helper.assertTrue(second.isCancelled(), "Closing the menu did not cancel its request");
        menu.setUrl("minecraft:music_disc.13");
        helper.assertTrue(field(menu, "currentCancellation", AudioCancellation.class) == second,
                "Closed etching menu started another request");
        helper.succeedWhen(() -> {
            helper.assertTrue(firstRequest.isDone() && secondRequest.isDone(), "Etching work has not retired yet");
            helper.assertTrue(menu.getSlot(2).getItem().isEmpty(), "Retired work published an etching result");
        });
    }

    /** Inspect internal request ownership without adding testing accessors to the menu API. */
    private static <T> T field(EtchingMenu menu, String name, Class<T> type) {
        try {
            var field = EtchingMenu.class.getDeclaredField(name);
            field.setAccessible(true);
            return type.cast(field.get(menu));
        } catch (ReflectiveOperationException exception) {
            throw new AssertionError("Could not inspect etching request ownership", exception);
        }
    }
}
