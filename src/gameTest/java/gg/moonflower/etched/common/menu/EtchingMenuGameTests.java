package gg.moonflower.etched.common.menu;

import gg.moonflower.etched.common.audio.AudioCancellation;
import gg.moonflower.etched.common.audio.AudioNbtCodec;
import gg.moonflower.etched.common.audio.AudioProgram;
import gg.moonflower.etched.common.audio.AudioTrack;
import gg.moonflower.etched.common.audio.RecordContent;
import gg.moonflower.etched.core.Etched;
import gg.moonflower.etched.core.registry.EtchedItems;
import gg.moonflower.etched.common.item.EtchedMusicDiscItem;
import gg.moonflower.etched.common.item.MusicLabelItem;
import gg.moonflower.etched.common.item.RecordContentResolver;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.ContainerLevelAccess;
import net.minecraft.world.item.ItemStack;
import net.minecraftforge.gametest.GameTestHolder;
import net.minecraftforge.gametest.PrefixGameTestTemplate;

import java.io.IOException;
import java.net.Proxy;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

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
                        return new RecordContent(new AudioProgram(AudioProgram.Kind.FINITE,
                                List.of(new AudioTrack(AudioTrack.SourceType.REMOTE, input, "Artist", "Late metadata"))));
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
                var tracks = EtchedMusicDiscItem.readContent(result).orElseThrow().program().tracks();
                helper.assertTrue(tracks.size() == 1 && tracks.get(0).source().equals("minecraft:music_disc.blocks"),
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
        var metadata = new RecordContent(new AudioProgram(AudioProgram.Kind.FINITE, List.of(
                new AudioTrack(AudioTrack.SourceType.REMOTE, "https://fixture.bandcamp.com/track/one", "Artist", "One"),
                new AudioTrack(AudioTrack.SourceType.REMOTE, "https://fixture.bandcamp.com/track/two", "Guest", "Two"))),
                Optional.of(new RecordContent.AlbumMetadata(AudioTrack.SourceType.REMOTE, input, "Artist", "Album")));
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
            var content = RecordContentResolver.resolve(result).orElseThrow();
            var album = content.album().orElseThrow();
            var tracks = content.program().tracks();
            helper.assertTrue(album.source().equals(input) && album.title().equals("Album"),
                    "Etching lost the album descriptor");
            helper.assertTrue(tracks.size() == 2 && tracks.get(0).title().equals("One")
                    && tracks.get(1).title().equals("Two") && tracks.get(1).artist().equals("Guest"),
                    "Etching lost metadata track order or artist");
            helper.assertTrue(metadata.album().orElseThrow().title().equals("Album"), "Etching mutated worker metadata");
            helper.assertFalse(result.getTag().contains("Music") || result.getTag().contains("Album"),
                    "Etching wrote obsolete audio fields");
            helper.assertTrue(result.getTag().getCompound(EtchedMusicDiscItem.CONTENT_TAG).getInt("SchemaVersion")
                    == AudioNbtCodec.SCHEMA_VERSION, "Etching omitted the schema version");
            helper.assertTrue(content.equals(metadata),
                    "Managed playback cannot read the etched versioned album");
        });
    }

    @GameTest(template = "empty", timeoutTicks = 100)
    public static void localAlbumReEtchingRetainsExplicitMetadataAndAllTracks(GameTestHelper helper) {
        var metadata = new RecordContent(new AudioProgram(AudioProgram.Kind.FINITE, List.of(
                new AudioTrack(AudioTrack.SourceType.SOUND_EVENT, "minecraft:music_disc.blocks", "Artist", "One"),
                new AudioTrack(AudioTrack.SourceType.SOUND_EVENT, "minecraft:music_disc.cat", "Guest", "Two"))),
                Optional.of(new RecordContent.AlbumMetadata(AudioTrack.SourceType.SOUND_EVENT,
                        "minecraft:music_disc.blocks", "Artist", "Album")));
        ItemStack disc = new ItemStack(EtchedItems.ETCHED_MUSIC_DISC.get());
        EtchedMusicDiscItem.setContent(disc, metadata);
        Player player = helper.makeMockSurvivalPlayer();
        EtchingMenu menu = new EtchingMenu(1, player.getInventory(), ContainerLevelAccess.NULL,
                (uri, proxy, cancellation) -> { throw new AssertionError("Local album started provider I/O"); });
        player.containerMenu = menu;
        // Configure the new appearance before inserting the input, so unchanged audio still produces a result.
        menu.clickMenuButton(player, EtchedMusicDiscItem.LabelPattern.CROSS.ordinal());
        ItemStack label = new ItemStack(EtchedItems.MUSIC_LABEL.get());
        MusicLabelItem.setTitle(label, "Do not replace album tracks");
        MusicLabelItem.setAuthor(label, "Label artist");
        menu.getSlot(1).set(label);
        menu.getSlot(0).set(disc);
        helper.succeedWhen(() -> {
            ItemStack result = menu.getSlot(2).getItem();
            helper.assertTrue(result.is(EtchedItems.ETCHED_MUSIC_DISC.get()), "Local album has no re-etching result");
            helper.assertTrue(RecordContentResolver.resolve(result).orElseThrow().equals(metadata),
                    "Re-etching promoted a track to album metadata, lost a track or changed its source");
            helper.assertTrue(EtchedMusicDiscItem.getPattern(result) == EtchedMusicDiscItem.LabelPattern.CROSS,
                    "Re-etching lost the chosen physical label pattern");
            helper.assertTrue(EtchedMusicDiscItem.readContent(disc).orElseThrow().equals(metadata),
                    "Re-etching mutated the input program");
        });
    }

    @GameTest(template = "empty", timeoutTicks = 100)
    public static void oversizeMetadataCannotPublishAnEtchedDisc(GameTestHelper helper) {
        String input = "https://fixture.bandcamp.com/track/oversize";
        Player player = helper.makeMockSurvivalPlayer();
        EtchingMenu menu = new EtchingMenu(1, player.getInventory(), ContainerLevelAccess.NULL,
                (uri, proxy, cancellation) -> CompletableFuture.completedFuture(new RecordContent(
                        new AudioProgram(AudioProgram.Kind.FINITE, List.of(
                                new AudioTrack(AudioTrack.SourceType.REMOTE, input, "Artist", "x".repeat(129)))))));
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
            var tracks = EtchedMusicDiscItem.readContent(result).orElseThrow().program().tracks();
            helper.assertTrue(tracks.size() == 1 && tracks.get(0).source().equals("minecraft:music_disc.blocks"),
                    "Etching lost the submitted local sound");
        });
    }

    @GameTest(template = "empty", timeoutTicks = 100)
    public static void replacementAndCloseCancelEtchingRequests(GameTestHelper helper) {
        String firstInput = "https://fixture.bandcamp.com/track/first-" + UUID.randomUUID();
        String secondInput = "https://fixture.bandcamp.com/track/second-" + UUID.randomUUID();
        // Bound worker lifetime even if an early GameTest failure stops its delayed cleanup callbacks.
        var firstMetadata = new CompletableFuture<RecordContent>().orTimeout(10, TimeUnit.SECONDS);
        var secondMetadata = new CompletableFuture<RecordContent>().orTimeout(10, TimeUnit.SECONDS);
        var firstStarted = new AtomicInteger();
        var secondStarted = new AtomicInteger();
        Proxy expectedProxy = helper.getLevel().getServer().getProxy();
        Player player = helper.makeMockSurvivalPlayer();
        EtchingMenu menu = new EtchingMenu(1, player.getInventory(), ContainerLevelAccess.NULL,
                (uri, proxy, cancellation) -> {
                    helper.assertTrue(proxy == expectedProxy, "Pending metadata lost the server proxy");
                    helper.assertFalse(cancellation.isCancelled(), "Metadata started with an already retired token");
                    if (uri.toString().equals(firstInput)) {
                        firstStarted.incrementAndGet();
                        return firstMetadata;
                    }
                    helper.assertTrue(uri.toString().equals(secondInput), "Closed menu started unexpected metadata");
                    secondStarted.incrementAndGet();
                    return secondMetadata;
                });
        player.containerMenu = menu;
        // The fixture never supplies a successful result before cancellation. Real local resolution can finish
        // before removed(), in which case an already published result is not evidence of a cancellation failure.
        menu.setUrl(firstInput);
        menu.getSlot(0).set(new ItemStack(EtchedItems.ETCHED_MUSIC_DISC.get()));
        AudioCancellation first = field(menu, "currentCancellation", AudioCancellation.class);
        CompletableFuture<?> firstRequest = field(menu, "currentRequest", CompletableFuture.class);
        var second = new AtomicReference<AudioCancellation>();
        var secondRequest = new AtomicReference<CompletableFuture<?>>();
        var replaced = new AtomicBoolean();
        var closed = new AtomicBoolean();
        var cleanup = new AtomicBoolean();
        helper.runAfterDelay(80, () -> {
            cleanup.set(true); // Cleanup must unblock workers on failure, never make a broken cancellation pass.
            firstMetadata.cancel(false);
            secondMetadata.cancel(false);
            menu.removed(player);
        });
        helper.succeedWhen(() -> {
            helper.assertFalse(cleanup.get(), "Pending metadata fixture expired before cancellation completed");
            if (!replaced.get()) {
                helper.assertTrue(firstStarted.get() == 1, "First metadata request has not started exactly once");
                helper.assertFalse(firstMetadata.isDone() || firstRequest.isDone(), "First request completed before replacement");
                menu.setUrl(secondInput);
                helper.assertTrue(first.isCancelled(), "Changing the URL did not cancel the previous request");
                second.set(field(menu, "currentCancellation", AudioCancellation.class));
                secondRequest.set(field(menu, "currentRequest", CompletableFuture.class));
                helper.assertTrue(second.get() != first && !second.get().isCancelled(), "Replacement did not get a fresh token");
                replaced.set(true);
            }
            helper.assertTrue(firstMetadata.isCancelled() && firstRequest.isDone(), "Replacement still waits for retired metadata");
            helper.assertTrue(secondStarted.get() == 1, "Second metadata request has not started exactly once");
            if (!closed.get()) {
                helper.assertFalse(secondMetadata.isDone() || secondRequest.get().isDone(), "Second request completed before close");
                menu.removed(player);
                helper.assertTrue(second.get().isCancelled(), "Closing the menu did not cancel its request");
                menu.setUrl(firstInput);
                helper.assertTrue(field(menu, "currentCancellation", AudioCancellation.class) == second.get()
                                && field(menu, "currentRequest", CompletableFuture.class) == secondRequest.get(),
                        "Closed etching menu started another request");
                closed.set(true);
            }
            helper.assertTrue(secondMetadata.isCancelled() && secondRequest.get().isDone(), "Close still waits for retired metadata");
            var late = new RecordContent(new AudioProgram(AudioProgram.Kind.FINITE, List.of(
                    new AudioTrack(AudioTrack.SourceType.REMOTE, firstInput, "Artist", "Late result"))));
            helper.assertFalse(firstMetadata.complete(late), "First retired future accepted late metadata");
            helper.assertFalse(secondMetadata.complete(late), "Second retired future accepted late metadata");
            helper.assertTrue(firstStarted.get() == 1 && secondStarted.get() == 1, "Closed menu started another metadata lookup");
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
