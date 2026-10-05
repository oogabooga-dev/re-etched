package gg.moonflower.etched.common.menu;

import gg.moonflower.etched.common.audio.AudioCancellation;
import gg.moonflower.etched.api.record.PlayableRecord;
import gg.moonflower.etched.api.record.TrackData;
import gg.moonflower.etched.api.sound.download.SoundDownloadSource;
import gg.moonflower.etched.api.sound.download.SoundSourceManager;
import gg.moonflower.etched.api.util.DownloadProgressListener;
import gg.moonflower.etched.core.Etched;
import gg.moonflower.etched.core.registry.EtchedItems;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.network.chat.Component;
import net.minecraft.server.packs.resources.ResourceManager;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraftforge.gametest.GameTestHolder;
import net.minecraftforge.gametest.PrefixGameTestTemplate;

import java.io.IOException;
import java.net.Proxy;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

@GameTestHolder(Etched.MOD_ID)
@PrefixGameTestTemplate(false)
public final class EtchingMenuGameTests {

    private EtchingMenuGameTests() {
    }

    @GameTest(template = "empty", timeoutTicks = 100)
    public static void replacementRetiresThirdPartyWaitBeforeProviderReturns(GameTestHelper helper) {
        cancelledThirdPartyWait(helper, true);
    }

    @GameTest(template = "empty", timeoutTicks = 100)
    public static void closeRetiresThirdPartyWaitBeforeProviderReturns(GameTestHelper helper) {
        cancelledThirdPartyWait(helper, false);
    }

    private static void cancelledThirdPartyWait(GameTestHelper helper, boolean replace) {
        String input = "https://metadata-fixture.example/" + UUID.randomUUID();
        CountDownLatch started = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        AtomicBoolean returned = new AtomicBoolean();
        Proxy expectedProxy = helper.getLevel().getServer().getProxy();
        SoundSourceManager.registerSource(new SoundDownloadSource() {
            @Override
            public List<TrackData> resolveTracks(String url, DownloadProgressListener listener, Proxy proxy) throws IOException {
                helper.assertTrue(proxy == expectedProxy, "Third-party etching lost the server proxy");
                started.countDown();
                try {
                    if (!release.await(10, TimeUnit.SECONDS)) {
                        throw new IOException("Fixture was not released");
                    }
                    return List.of(new TrackData(input, "Artist", Component.literal("Late metadata")));
                } catch (InterruptedException exception) {
                    Thread.currentThread().interrupt();
                    throw new IOException(exception);
                } finally {
                    returned.set(true);
                }
            }

            @Override public boolean isValidUrl(String url) { return input.equals(url); }
            @Override public String getApiName() { return "GameTest metadata provider"; }
            @Override public Optional<String> resolveAlbumCover(String url, DownloadProgressListener listener,
                                                               Proxy proxy, ResourceManager resources) {
                throw new AssertionError("Etching tried to open a cover");
            }
        });
        Player player = helper.makeMockSurvivalPlayer();
        EtchingMenu menu = new EtchingMenu(1, player.getInventory());
        player.containerMenu = menu;
        menu.setUrl(input);
        menu.getSlot(0).set(new ItemStack(EtchedItems.ETCHED_MUSIC_DISC.get()));
        CompletableFuture<?> request = field(menu, "currentRequest", CompletableFuture.class);
        AtomicBoolean cancelled = new AtomicBoolean();
        // Release the bounded compatibility worker even if the GameTest never reaches its success path.
        helper.runAfterDelay(80, release::countDown);
        helper.succeedWhen(() -> {
            helper.assertTrue(started.getCount() == 0, "Third-party metadata has not started yet");
            if (cancelled.compareAndSet(false, true)) {
                if (replace) {
                    menu.setUrl("minecraft:music_disc.blocks");
                } else {
                    menu.removed(player);
                }
            }
            helper.assertTrue(request.isDone(), "Cancelled etching still waits for provider I/O");
            helper.assertTrue(!returned.get(), "Etching only retired after the provider returned");
            if (replace) {
                var result = menu.getSlot(2).getItem();
                helper.assertTrue(result.is(EtchedItems.ETCHED_MUSIC_DISC.get()), "Replacement has no etching result yet");
                var tracks = PlayableRecord.getStackMusic(result).orElseThrow();
                helper.assertTrue(tracks.length == 1 && tracks[0].url().equals("minecraft:music_disc.blocks"),
                        "Late third-party metadata replaced the fresh local result");
            } else {
                helper.assertTrue(menu.getSlot(2).getItem().isEmpty(), "Closed menu published retired metadata");
            }
            release.countDown();
        });
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
