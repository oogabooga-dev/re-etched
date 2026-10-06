package gg.moonflower.etched.client;

import gg.moonflower.etched.api.record.AlbumCover;
import gg.moonflower.etched.client.render.item.ModelAlbumCover;
import gg.moonflower.etched.common.audio.AudioProgram;
import gg.moonflower.etched.common.audio.AudioTrack;
import gg.moonflower.etched.common.audio.RecordContent;
import gg.moonflower.etched.common.item.EtchedMusicDiscItem;
import gg.moonflower.etched.core.Etched;
import gg.moonflower.etched.core.registry.EtchedItems;
import net.minecraft.client.Minecraft;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraftforge.registries.ForgeRegistries;

import java.net.Proxy;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;

/** Transformed client-only first-party cover dispatch check, with no live provider I/O. */
public final class RecordCoverDispatchSmoke {

    private RecordCoverDispatchSmoke() {
    }

    public static void verify(Minecraft client) {
        var resources = client.getResourceManager();
        var noProviders = (RecordCoverRequests.ProviderRequest) (source, proxy) -> {
            throw new AssertionError("Unexpected provider lookup: " + source);
        };
        var vanilla = RecordCoverRequests.request(new ItemStack(Items.MUSIC_DISC_CAT), Proxy.NO_PROXY, resources, noProviders).join();
        if (!(vanilla instanceof ModelAlbumCover model)
                || !model.model().toString().equals("minecraft:etched_album_cover/music_disc_cat#inventory")) {
            throw new AssertionError("Vanilla cover route lost its bundled model/namespace");
        }
        ItemStack invalid = disc("https://artist.bandcamp.com/track/test", Optional.empty());
        invalid.getTag().getCompound(EtchedMusicDiscItem.CONTENT_TAG).putInt("SchemaVersion", 999);
        ItemStack unsupported = new ItemStack(ForgeRegistries.ITEMS.getValue(
                ResourceLocation.fromNamespaceAndPath(Etched.MOD_ID, "client_smoke_legacy_record")));
        for (ItemStack stack : List.of(unsupported, invalid, new ItemStack(EtchedItems.ALBUM_COVER.get()),
                disc("minecraft:music_disc.cat", Optional.empty()), disc("https://audio.example/test.mp3", Optional.empty()))) {
            if (RecordCoverRequests.request(stack, Proxy.NO_PROXY, resources, noProviders).join() != AlbumCover.EMPTY) {
                throw new AssertionError("Unsupported/local/direct/invalid cover route produced a cover");
            }
        }
        for (boolean album : new boolean[]{false, true}) {
            String expected = album ? "https://artist.bandcamp.com/album/test" : "https://soundcloud.com/a/track";
            var pending = new CompletableFuture<AlbumCover>();
            ItemStack stack = disc("https://soundcloud.com/a/track", album ? Optional.of(expected) : Optional.empty());
            var result = RecordCoverRequests.request(stack, client.getProxy(), resources, (source, proxy) -> {
                if (!source.equals(expected) || proxy != client.getProxy()) {
                    throw new AssertionError("Cover dispatch lost album selection or Minecraft proxy");
                }
                return pending;
            });
            if (result != pending || !result.cancel(false) || !pending.isCancelled()) {
                throw new AssertionError("Cover dispatch wrapped/lost the request-owned cancellation future");
            }
        }
        System.out.println("ETCHED FIRST-PARTY COVER DISPATCH SMOKE PASSED");
    }

    private static ItemStack disc(String source, Optional<String> album) {
        var type = source.startsWith("minecraft:") ? AudioTrack.SourceType.SOUND_EVENT : AudioTrack.SourceType.REMOTE;
        ItemStack stack = new ItemStack(EtchedItems.ETCHED_MUSIC_DISC.get());
        EtchedMusicDiscItem.setContent(stack, new RecordContent(new AudioProgram(AudioProgram.Kind.FINITE,
                List.of(new AudioTrack(type, source, "Artist", "Title"))), album.map(url -> new RecordContent.AlbumMetadata(
                        AudioTrack.SourceType.REMOTE, url, "Album artist", "Album title"))));
        return stack;
    }
}
