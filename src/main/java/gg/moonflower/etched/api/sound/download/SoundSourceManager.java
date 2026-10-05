package gg.moonflower.etched.api.sound.download;

import gg.moonflower.etched.api.record.AlbumCover;
import gg.moonflower.etched.api.util.DownloadProgressListener;
import gg.moonflower.etched.client.AlbumCoverCache;
import gg.moonflower.etched.common.audio.provider.LegacyProviderResults;
import gg.moonflower.etched.common.audio.provider.CancellationProgressListener;
import net.minecraft.network.chat.Component;
import net.minecraft.server.packs.resources.ResourceManager;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.jetbrains.annotations.Nullable;

import java.net.Proxy;
import java.util.*;
import java.util.concurrent.CompletableFuture;

/**
 * Manages all sources of sound obtained through sources besides direct downloads.
 *
 * @author Ocelot
 * @since 2.0.0
 */
public final class SoundSourceManager {

    private static final Set<SoundDownloadSource> SOURCES = new HashSet<>();
    private static final Logger LOGGER = LogManager.getLogger();

    private SoundSourceManager() {
    }

    /**
     * Registers a new source for sound.
     *
     * @param source The source to add
     */
    public static synchronized void registerSource(SoundDownloadSource source) {
        SOURCES.add(source);
    }

    private static synchronized List<SoundDownloadSource> sources() {
        return List.copyOf(SOURCES);
    }

    /**
     * Resolves the album cover from an external source.
     *
     * @param url      The URL to get the cover from
     * @param listener The listener for events
     * @param proxy    The connection proxy
     * @return The album cover found or nothing
     */
    public static CompletableFuture<AlbumCover> resolveAlbumCover(String url, @Nullable DownloadProgressListener listener, Proxy proxy, ResourceManager resourceManager) {
        if (AlbumCoverCache.supportsProvider(url)) {
            return AlbumCoverCache.requestProviderResource(url, listener, proxy);
        }
        return AlbumCoverCache.requestResolvedResource(cancellation -> {
            LegacyProviderResults.remote(url);
            return sources().stream()
                    .filter(s -> s.isValidUrl(url)).findFirst().flatMap(source -> {
                        try {
                            Optional<String> cover = source.resolveAlbumCover(url, listener == null ? null
                                    : new CancellationProgressListener(listener, cancellation::isCancelled), proxy, resourceManager);
                            cancellation.throwIfCancelled();
                            return cover.isPresent() ? Optional.of(LegacyProviderResults.remote(cover.get())) : Optional.empty();
                        } catch (Exception e) {
                            if (!cancellation.isCancelled()) {
                                LOGGER.error("Failed to connect to " + source.getApiName() + " API", e);
                            }
                            return Optional.empty();
                        }
                    });
        }, proxy);
    }

    /**
     * Retrieves the brand information for an external source.
     *
     * @param url The URL to get the brand from
     * @return The brand of that source or nothing
     */
    public static Optional<Component> getBrandText(String url) {
        return sources().stream().filter(source -> source.isValidUrl(url)).findFirst().flatMap(s -> s.getBrandText(url));
    }

}
