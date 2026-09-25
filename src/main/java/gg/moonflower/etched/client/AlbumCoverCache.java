package gg.moonflower.etched.client;

import com.mojang.blaze3d.platform.NativeImage;
import gg.moonflower.etched.api.record.AlbumCover;
import gg.moonflower.etched.client.cache.BoundedMediaCache;
import gg.moonflower.etched.client.cache.ClientMediaCache;
import gg.moonflower.etched.client.cache.CoverCacheLoader;
import gg.moonflower.etched.client.radio.AudioCancellation;
import gg.moonflower.etched.client.radio.source.AudioResolveContext;
import gg.moonflower.etched.client.render.item.AlbumCoverItemRenderer;
import gg.moonflower.etched.client.render.item.AlbumImageProcessor;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.jetbrains.annotations.ApiStatus;

import java.net.URI;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

/** Legacy-facing cover request entry point, backed by the v5 namespaced secure cache. */
@ApiStatus.Internal
public final class AlbumCoverCache {

    private static final Logger LOGGER = LogManager.getLogger();
    private static final ThreadPoolExecutor WORKERS = new ThreadPoolExecutor(2, 2,
            0L, TimeUnit.MILLISECONDS, new ArrayBlockingQueue<>(32), task -> {
        Thread thread = new Thread(task, "Etched cover cache");
        thread.setDaemon(true);
        return thread;
    }, new ThreadPoolExecutor.AbortPolicy());

    private AlbumCoverCache() {
    }

    public static CompletableFuture<AlbumCover> requestResource(String url) {
        AudioCancellation cancellation = new AudioCancellation();
        CompletableFuture<AlbumCover> result = new CompletableFuture<>();
        result.whenComplete((cover, failure) -> {
            if (result.isCancelled()) {
                cancellation.cancel();
            }
        });
        try {
            WORKERS.execute(() -> {
                try {
                    cancellation.throwIfCancelled();
                    URI uri = URI.create(url);
                    BoundedMediaCache cache = ClientMediaCache.get();
                    try (BoundedMediaCache.Lease cover = CoverCacheLoader.open(cache, uri,
                            cancellation, AudioResolveContext::createDefault)) {
                        cancellation.throwIfCancelled();
                        NativeImage image = AlbumImageProcessor.apply(
                                NativeImage.read(cover.body()), AlbumCoverItemRenderer.getOverlayImage());
                        if (!result.complete(AlbumCover.of(image))) {
                            image.close();
                        }
                    }
                } catch (Exception exception) {
                    if (!cancellation.isCancelled()) {
                        LOGGER.warn("Could not load album cover", exception);
                    }
                    result.complete(AlbumCover.EMPTY);
                }
            });
        } catch (RejectedExecutionException exception) {
            result.complete(AlbumCover.EMPTY);
        }
        return result;
    }

}
