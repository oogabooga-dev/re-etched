package gg.moonflower.etched.client;

import gg.moonflower.etched.api.record.AlbumCover;
import gg.moonflower.etched.client.render.item.AlbumCoverItemRenderer;
import gg.moonflower.etched.common.item.EtchedMusicDiscItem;
import gg.moonflower.etched.common.item.RecordPresentation;
import gg.moonflower.etched.common.item.VanillaRecordAdapter;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.packs.resources.ResourceManager;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.RecordItem;
import net.minecraftforge.registries.ForgeRegistries;
import org.jetbrains.annotations.ApiStatus;

import java.net.Proxy;
import java.util.concurrent.CompletableFuture;

/** First-party cover dispatch; never calls an item's legacy metadata or cover API. */
@ApiStatus.Internal
public final class RecordCoverRequests {

    private RecordCoverRequests() {
    }

    public static CompletableFuture<AlbumCover> request(ItemStack stack, Proxy proxy, ResourceManager resources) {
        return request(stack, proxy, resources, AlbumCoverCache::requestProviderResource);
    }

    static CompletableFuture<AlbumCover> request(ItemStack stack, Proxy proxy, ResourceManager resources,
                                                ProviderRequest providers) {
        if (stack.isEmpty()) {
            return CompletableFuture.completedFuture(AlbumCover.EMPTY);
        }
        if (stack.getItem() instanceof EtchedMusicDiscItem) {
            return requestDisc(stack, proxy, providers);
        }
        if (stack.getItem() instanceof RecordItem record && VanillaRecordAdapter.isVanilla(record)) {
            ResourceLocation key = ForgeRegistries.ITEMS.getKey(record);
            ResourceLocation model = ResourceLocation.fromNamespaceAndPath(key.getNamespace(),
                    AlbumCoverItemRenderer.FOLDER_NAME + "/" + key.getPath());
            ResourceLocation file = ResourceLocation.fromNamespaceAndPath(model.getNamespace(), "models/item/" + model.getPath() + ".json");
            return CompletableFuture.completedFuture(resources.getResource(file).isPresent() ? AlbumCover.of(model) : AlbumCover.EMPTY);
        }
        return CompletableFuture.completedFuture(AlbumCover.EMPTY);
    }

    static CompletableFuture<AlbumCover> requestDisc(ItemStack stack, Proxy proxy, ProviderRequest providers) {
        return EtchedMusicDiscItem.readContent(stack).map(RecordPresentation::source)
                .filter(AlbumCoverCache::supportsProvider)
                // Return the worker's future directly: cancellation and image handoff keep their owner.
                .map(source -> providers.request(source, proxy))
                .orElseGet(() -> CompletableFuture.completedFuture(AlbumCover.EMPTY));
    }

    @FunctionalInterface
    interface ProviderRequest {
        CompletableFuture<AlbumCover> request(String source, Proxy proxy);
    }
}
