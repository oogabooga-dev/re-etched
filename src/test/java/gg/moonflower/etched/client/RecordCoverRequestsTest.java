package gg.moonflower.etched.client;

import gg.moonflower.etched.client.render.item.CoverDescriptor;
import gg.moonflower.etched.client.radio.MinecraftTestBootstrap;
import gg.moonflower.etched.client.render.item.ModelAlbumCover;
import gg.moonflower.etched.common.audio.AudioProgram;
import gg.moonflower.etched.common.audio.AudioTrack;
import gg.moonflower.etched.common.audio.RecordContent;
import gg.moonflower.etched.common.item.EtchedMusicDiscItem;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.packs.resources.Resource;
import net.minecraft.server.packs.resources.ResourceManager;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import org.junit.jupiter.api.Test;

import java.net.InetSocketAddress;
import java.net.Proxy;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

class RecordCoverRequestsTest {

    static {
        MinecraftTestBootstrap.bootStrap();
    }

    @Test
    void vanillaModelLookupRetainsNamespaceAndNeverOpensModelBytesOrProviders() {
        var requests = new AtomicInteger();
        ResourceManager resources = resources(path -> {
            assertEquals(ResourceLocation.parse("minecraft:models/item/etched_album_cover/music_disc_cat.json"), path);
            requests.incrementAndGet();
            return true;
        });
        var cover = RecordCoverRequests.request(new ItemStack(Items.MUSIC_DISC_CAT), Proxy.NO_PROXY, resources, noProviders()).join();
        var model = assertInstanceOf(ModelAlbumCover.class, cover).model();
        assertEquals("minecraft:etched_album_cover/music_disc_cat#inventory", model.toString());
        assertEquals(1, requests.get());
        assertSame(CoverDescriptor.EMPTY, RecordCoverRequests.request(new ItemStack(Items.MUSIC_DISC_CAT), Proxy.NO_PROXY,
                resources(path -> false), noProviders()).join());
    }

    @Test
    void providerDispatchUsesExplicitAlbumSourceProxyAndTheOriginalOwnedFuture() {
        ItemStack disc = disc("https://audio.example/track", Optional.of("https://artist.bandcamp.com/album/test"));
        Proxy proxy = new Proxy(Proxy.Type.HTTP, InetSocketAddress.createUnresolved("proxy.example", 8080));
        var pending = new CompletableFuture<CoverDescriptor>();
        var result = RecordCoverRequests.requestDisc(disc, proxy, (source, actualProxy) -> {
            assertEquals("https://artist.bandcamp.com/album/test", source);
            assertSame(proxy, actualProxy);
            return pending;
        });
        assertSame(pending, result);
        disc.getTag().getCompound(EtchedMusicDiscItem.CONTENT_TAG).getCompound("AlbumMetadata").putString("Source", "changed");
        result.cancel(false);
        assertTrue(pending.isCancelled());
        assertFalse(pending.complete(CoverDescriptor.EMPTY));
    }

    @Test
    void singleTrackProviderFallbackReturnsItsOwnedFutureWithoutRevokingADeliveredCover() {
        var delivered = CompletableFuture.completedFuture(CoverDescriptor.EMPTY);
        assertSame(delivered, RecordCoverRequests.requestDisc(disc("https://soundcloud.com/a/track", Optional.empty()),
                Proxy.NO_PROXY, (source, proxy) -> {
                    assertEquals("https://soundcloud.com/a/track", source);
                    return delivered;
                }));
        assertFalse(delivered.cancel(false));
        assertSame(CoverDescriptor.EMPTY, delivered.join());
    }

    @Test
    void unsupportedLocalDirectAndMalformedSourcesDoNotStartProviderOrResourceLookups() {
        var invalid = disc("https://artist.bandcamp.com/track/test", Optional.empty());
        invalid.getTag().getCompound(EtchedMusicDiscItem.CONTENT_TAG).putInt("SchemaVersion", 999);
        for (ItemStack stack : List.of(ItemStack.EMPTY, new ItemStack(Items.PAPER), invalid,
                disc("minecraft:music_disc.cat", Optional.empty()), disc("https://audio.example/a.mp3", Optional.empty()),
                disc("https://artist.bandcamp.com.evil.example/track/test", Optional.empty()))) {
            assertSame(CoverDescriptor.EMPTY, RecordCoverRequests.request(stack, Proxy.NO_PROXY, noResources(), noProviders()).join());
            assertSame(CoverDescriptor.EMPTY, RecordCoverRequests.requestDisc(stack, Proxy.NO_PROXY, noProviders()).join());
        }
        // A valid AudioContent tag on an unrelated item must not turn it into a first-party cover source.
        assertSame(CoverDescriptor.EMPTY, RecordCoverRequests.request(disc("https://artist.bandcamp.com/album/test", Optional.empty()),
                Proxy.NO_PROXY, noResources(), noProviders()).join());
    }

    private static ItemStack disc(String source, Optional<String> album) {
        var type = source.startsWith("minecraft:") ? AudioTrack.SourceType.SOUND_EVENT : AudioTrack.SourceType.REMOTE;
        // The NBT boundary can be tested without registering items after the JUnit bootstrap freezes registries.
        var stack = new ItemStack(Items.PAPER);
        EtchedMusicDiscItem.setContent(stack, new RecordContent(new AudioProgram(AudioProgram.Kind.FINITE,
                List.of(new AudioTrack(type, source, "Artist", "Title"))), album.map(url -> new RecordContent.AlbumMetadata(
                        AudioTrack.SourceType.REMOTE, url, "Album artist", "Album title"))));
        return stack;
    }

    private static RecordCoverRequests.ProviderRequest noProviders() {
        return (source, proxy) -> { throw new AssertionError("Unexpected provider lookup: " + source); };
    }

    private static ResourceManager noResources() {
        return resources(path -> { throw new AssertionError("Unexpected model lookup: " + path); });
    }

    private static ResourceManager resources(java.util.function.Predicate<ResourceLocation> present) {
        return (ResourceManager) java.lang.reflect.Proxy.newProxyInstance(ResourceManager.class.getClassLoader(),
                new Class<?>[]{ResourceManager.class}, (proxy, method, args) -> {
                    if (method.getName().equals("getResource")) {
                        return present.test((ResourceLocation) args[0]) ? Optional.of(new Resource(null,
                                () -> { throw new AssertionError("Model lookup opened bytes"); })) : Optional.empty();
                    }
                    throw new AssertionError("Unexpected ResourceManager call: " + method);
                });
    }
}
