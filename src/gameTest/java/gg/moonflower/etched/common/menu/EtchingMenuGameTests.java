package gg.moonflower.etched.common.menu;

import gg.moonflower.etched.common.audio.AudioCancellation;
import gg.moonflower.etched.common.audio.AudioNbtCodec;
import gg.moonflower.etched.api.record.PlayableRecord;
import gg.moonflower.etched.api.record.TrackData;
import gg.moonflower.etched.core.Etched;
import gg.moonflower.etched.core.registry.EtchedItems;
import gg.moonflower.etched.common.item.EtchedMusicDiscItem;
import gg.moonflower.etched.common.item.RecordContentResolver;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.ContainerLevelAccess;
import net.minecraft.world.item.ItemStack;
import net.minecraftforge.gametest.GameTestHolder;
import net.minecraftforge.gametest.PrefixGameTestTemplate;

import java.io.IOException;
import java.net.Proxy;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

@GameTestHolder(Etched.MOD_ID)
@PrefixGameTestTemplate(false)
public final class EtchingMenuGameTests {

    private EtchingMenuGameTests() {
    }

    @GameTest(template = "empty", timeoutTicks = 100)
    public static void replacementRetiresMetadataWaitBeforeWorkerReturns(GameTestHelper helper) {
        cancelledMetadataWait(helper, true);
    }

    @GameTest(template = "empty", timeoutTicks = 100)
    public static void closeRetiresMetadataWaitBeforeWorkerReturns(GameTestHelper helper) {
        cancelledMetadataWait(helper, false);
    }

    private static void cancelledMetadataWait(GameTestHelper helper, boolean replace) {
        String input = "https://fixture.bandcamp.com/album/" + UUID.randomUUID();
        CountDownLatch started = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        AtomicBoolean returned = new AtomicBoolean();
        Proxy expectedProxy = helper.getLevel().getServer().getProxy();
        Player player = helper.makeMockSurvivalPlayer();
        EtchingMenu menu = new EtchingMenu(1, player.getInventory(), ContainerLevelAccess.NULL,
                (uri, proxy, cancellation) -> CompletableFuture.supplyAsync(() -> {
                    helper.assertTrue(proxy == expectedProxy, "Etching metadata lost the server proxy");
                    helper.assertTrue(uri.toString().equals(input), "Etching metadata changed the submitted page");
                    started.countDown();
                    try {
                        if (!release.await(10, TimeUnit.SECONDS)) {
                            throw new CompletionException(new IOException("Fixture was not released"));
                        }
                        return new TrackData[]{new TrackData(input, "Artist", Component.literal("Late metadata"))};
                    } catch (InterruptedException exception) {
                        Thread.currentThread().interrupt();
                        throw new CompletionException(exception);
                    } finally {
                        returned.set(true);
                    }
                }));
        player.containerMenu = menu;
        menu.setUrl(input);
        menu.getSlot(0).set(new ItemStack(EtchedItems.ETCHED_MUSIC_DISC.get()));
        CompletableFuture<?> request = field(menu, "currentRequest", CompletableFuture.class);
        AtomicBoolean cancelled = new AtomicBoolean();
        // Release the deliberately uninterruptible fixture even if the GameTest never succeeds.
        helper.runAfterDelay(80, release::countDown);
        helper.succeedWhen(() -> {
            helper.assertTrue(started.getCount() == 0, "Metadata worker has not started yet");
            if (cancelled.compareAndSet(false, true)) {
                if (replace) {
                    menu.setUrl("minecraft:music_disc.blocks");
                } else {
                    menu.removed(player);
                }
            }
            helper.assertTrue(request.isDone(), "Cancelled etching still waits for metadata I/O");
            helper.assertTrue(!returned.get(), "Etching only retired after the metadata worker returned");
            if (replace) {
                var result = menu.getSlot(2).getItem();
                helper.assertTrue(result.is(EtchedItems.ETCHED_MUSIC_DISC.get()), "Replacement has no etching result yet");
                var tracks = PlayableRecord.getStackMusic(result).orElseThrow();
                helper.assertTrue(tracks.length == 1 && tracks[0].url().equals("minecraft:music_disc.blocks"),
                        "Late metadata replaced the fresh local result");
            } else {
                helper.assertTrue(menu.getSlot(2).getItem().isEmpty(), "Closed menu published retired metadata");
            }
            release.countDown();
        });
    }

    @GameTest(template = "empty", timeoutTicks = 100)
    public static void builtInMetadataPublishesAlbumAndOrderedTracksForTheOpenMenu(GameTestHelper helper) {
        String input = "https://fixture.bandcamp.com/album/ordered";
        Proxy expectedProxy = helper.getLevel().getServer().getProxy();
        Player player = helper.makeMockSurvivalPlayer();
        TrackData[] metadata = {
                new TrackData(input, "Artist", Component.literal("Album")),
                new TrackData("https://fixture.bandcamp.com/track/one", "Artist", Component.literal("One")),
                new TrackData("https://fixture.bandcamp.com/track/two", "Guest", Component.literal("Two"))
        };
        EtchingMenu menu = new EtchingMenu(1, player.getInventory(), ContainerLevelAccess.NULL,
                (uri, proxy, cancellation) -> {
                    helper.assertTrue(uri.toString().equals(input), "Metadata lost the submitted album URL");
                    helper.assertTrue(proxy == expectedProxy, "Metadata lost the configured server proxy");
                    helper.assertFalse(cancellation.isCancelled(), "Open menu started retired metadata");
                    return CompletableFuture.completedFuture(metadata);
                });
        player.containerMenu = menu;
        menu.setUrl(input);
        menu.getSlot(0).set(new ItemStack(EtchedItems.ETCHED_MUSIC_DISC.get()));
        helper.succeedWhen(() -> {
            ItemStack result = menu.getSlot(2).getItem();
            helper.assertTrue(result.is(EtchedItems.ETCHED_MUSIC_DISC.get()), "Open menu has no metadata result yet");
            var album = PlayableRecord.getStackAlbum(result).orElseThrow();
            var tracks = PlayableRecord.getStackMusic(result).orElseThrow();
            helper.assertTrue(album.url().equals(input) && album.title().getString().equals("Album"),
                    "Etching lost the album descriptor");
            helper.assertTrue(tracks.length == 2 && tracks[0].title().getString().equals("One")
                    && tracks[1].title().getString().equals("Two") && tracks[1].artist().equals("Guest"),
                    "Etching lost metadata track order or artist");
            helper.assertTrue(metadata[0].title().getString().equals("Album"), "Etching mutated worker metadata");
            helper.assertFalse(result.getTag().contains("Music") || result.getTag().contains("Album"),
                    "Etching wrote obsolete audio fields");
            helper.assertTrue(result.getTag().getCompound(EtchedMusicDiscItem.CONTENT_TAG).getInt("SchemaVersion")
                    == AudioNbtCodec.SCHEMA_VERSION, "Etching omitted the schema version");
            var content = RecordContentResolver.resolve(result).orElseThrow();
            helper.assertTrue(content.program().tracks().size() == 2 && content.album().orElseThrow().source().equals(input),
                    "Managed playback cannot read the etched versioned album");
        });
    }

    @GameTest(template = "empty", timeoutTicks = 100)
    public static void oversizeMetadataCannotPublishAnEtchedDisc(GameTestHelper helper) {
        String input = "https://fixture.bandcamp.com/track/oversize";
        Player player = helper.makeMockSurvivalPlayer();
        EtchingMenu menu = new EtchingMenu(1, player.getInventory(), ContainerLevelAccess.NULL,
                (uri, proxy, cancellation) -> CompletableFuture.completedFuture(new TrackData[]{
                        new TrackData(input, "Artist", Component.literal("x".repeat(129)))}));
        player.containerMenu = menu;
        menu.setUrl(input);
        menu.getSlot(0).set(new ItemStack(EtchedItems.ETCHED_MUSIC_DISC.get()));
        CompletableFuture<?> request = field(menu, "currentRequest", CompletableFuture.class);
        helper.succeedWhen(() -> {
            helper.assertTrue(request.isDone(), "Invalid metadata request has not retired");
            helper.assertTrue(menu.getSlot(2).getItem().isEmpty(), "Invalid metadata published a partial disc");
            helper.assertTrue(menu.getSlot(0).getItem().is(EtchedItems.ETCHED_MUSIC_DISC.get()),
                    "Invalid etching consumed the input disc");
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
