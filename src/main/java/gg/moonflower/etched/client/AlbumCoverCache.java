package gg.moonflower.etched.client;

import com.mojang.blaze3d.platform.NativeImage;
import gg.moonflower.etched.api.record.AlbumCover;
import gg.moonflower.etched.api.util.DownloadProgressListener;
import gg.moonflower.etched.client.cache.BoundedMediaCache;
import gg.moonflower.etched.client.cache.ClientMediaCache;
import gg.moonflower.etched.client.cache.CoverCacheLoader;
import gg.moonflower.etched.client.cache.ProviderCoverCacheLoader;
import gg.moonflower.etched.common.audio.AudioCancellation;
import gg.moonflower.etched.client.radio.source.AudioResolveContext;
import gg.moonflower.etched.client.render.item.AlbumCoverItemRenderer;
import gg.moonflower.etched.client.render.item.AlbumImageProcessor;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.jetbrains.annotations.ApiStatus;
import org.jetbrains.annotations.Nullable;

import java.io.IOException;
import java.net.Proxy;
import java.net.URI;
import java.util.Optional;
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
        return request(cancellation -> Optional.of(CoverCacheLoader.open(ClientMediaCache.get(), URI.create(url),
                cancellation, AudioResolveContext::createDefault)));
    }

    public static boolean supportsProvider(String url) {
        try {
            return url != null && ProviderCoverCacheLoader.supports(URI.create(url));
        } catch (IllegalArgumentException exception) {
            return false;
        }
    }

    public static CompletableFuture<AlbumCover> requestProviderResource(String url,
                                                                       @Nullable DownloadProgressListener listener,
                                                                       Proxy proxy) {
        return request(cancellation -> ProviderCoverCacheLoader.open(ClientMediaCache.get(), URI.create(url), cancellation,
                token -> AudioResolveContext.createDefault(proxy, token), listener));
    }

    static CompletableFuture<AlbumCover> request(CoverOperation operation) {
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
                    Optional<BoundedMediaCache.Lease> resolved = operation.open(cancellation);
                    if (resolved.isEmpty()) {
                        result.complete(AlbumCover.EMPTY);
                        return;
                    }
                    try (BoundedMediaCache.Lease cover = resolved.get()) {
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

    @FunctionalInterface
    interface CoverOperation {
        Optional<BoundedMediaCache.Lease> open(AudioCancellation cancellation) throws IOException;
    }

}
