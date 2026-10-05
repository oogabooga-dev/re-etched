package gg.moonflower.etched.common.item;

import gg.moonflower.etched.api.record.TrackData;
import gg.moonflower.etched.core.Etched;
import gg.moonflower.etched.core.registry.EtchedItems;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.contents.TranslatableContents;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.TooltipFlag;
import net.minecraftforge.gametest.GameTestHolder;
import net.minecraftforge.gametest.PrefixGameTestTemplate;

import java.util.ArrayList;
import java.util.List;

@GameTestHolder(Etched.MOD_ID)
@PrefixGameTestTemplate(false)
public final class RecordPresentationGameTests {

    private RecordPresentationGameTests() {
    }

    @GameTest(template = "empty")
    public static void builtInAndUnbrandedRecordTooltipsRetainAlbumPresentationWithoutProviderRegistration(GameTestHelper helper) {
        for (String source : List.of("https://artist.bandcamp.com/album/test", "https://soundcloud.com/artist/sets/test",
                "https://audio.example/track.mp3", "minecraft:music_disc.blocks")) {
            for (boolean album : new boolean[]{false, true}) {
                ItemStack stack = new ItemStack(EtchedItems.ETCHED_MUSIC_DISC.get());
                var descriptor = new TrackData(source, "Artist", Component.literal("Title"));
                if (album) {
                    EtchedMusicDiscItem.setMusic(stack, descriptor, descriptor.withTitle(Component.literal("One")),
                            descriptor.withTitle(Component.literal("Two")));
                } else {
                    EtchedMusicDiscItem.setMusic(stack, descriptor);
                }
                var tooltip = new ArrayList<Component>();
                stack.getItem().appendHoverText(stack, helper.getLevel(), tooltip, TooltipFlag.Default.NORMAL);
                helper.assertTrue(tooltip.get(0).getString().equals(descriptor.getDisplayName().getString()),
                        "Tooltip lost the artist/title");
                boolean branded = source.contains("bandcamp.com") || source.contains("soundcloud.com");
                helper.assertTrue(tooltip.size() == (album || branded ? 2 : 1), "Tooltip has missing or extra lines");
                if (branded) {
                    var brand = tooltip.get(1).getSiblings().get(0);
                    String key = source.contains("bandcamp.com") ? "bandcamp" : "sound_cloud";
                    helper.assertTrue(brand.getContents() instanceof TranslatableContents text
                            && text.getKey().equals("sound_source.etched." + key), "Tooltip lost the built-in brand");
                    helper.assertTrue(brand.getStyle().getColor().getValue() == (key.equals("bandcamp") ? 0x477987 : 0xFF5500),
                            "Tooltip lost the service brand color");
                }
                if (album) {
                    var line = tooltip.get(1);
                    var albumText = branded ? line.getSiblings().get(line.getSiblings().size() - 1) : line;
                    helper.assertTrue(albumText.getContents() instanceof TranslatableContents text
                            && text.getKey().equals("item.etched.etched_music_disc.album"), "Tooltip lost the album marker");
                }
            }
        }
        helper.succeed();
    }
}
