package gg.moonflower.etched.api.sound.download;

import gg.moonflower.etched.api.record.AlbumCover;
import gg.moonflower.etched.api.record.TrackData;
import gg.moonflower.etched.api.sound.source.AudioSource;
import gg.moonflower.etched.api.sound.source.RawAudioSource;
import gg.moonflower.etched.api.sound.source.StreamingAudioSource;
import gg.moonflower.etched.api.util.DownloadProgressListener;
import gg.moonflower.etched.client.AlbumCoverCache;
import gg.moonflower.etched.common.audio.provider.LegacyTrackMetadataRequests;
import gg.moonflower.etched.common.audio.provider.LegacyProviderResults;
import gg.moonflower.etched.common.audio.provider.ProviderAudioSourceRequests;
import gg.moonflower.etched.common.audio.provider.CancellationProgressListener;
import gg.moonflower.etched.common.audio.provider.BandcampPageReader;
import gg.moonflower.etched.common.audio.provider.BandcampMetadataResolver;
import gg.moonflower.etched.common.audio.provider.SoundCloudPageReader;
import gg.moonflower.etched.common.audio.provider.SoundCloudMetadataResolver;
import gg.moonflower.etched.common.audio.AudioCancellation;
import net.minecraft.network.chat.Component;
import net.minecraft.server.packs.resources.ResourceManager;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.jetbrains.annotations.Nullable;

import java.io.IOException;
import java.net.MalformedURLException;
import java.net.Proxy;
import java.net.URI;
import java.net.URL;
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

    /**
     * Retrieves an {@link AudioSource} from the specified URL.
     *
     * @param url      The URL to retrieve
     * @param listener The listener for events
     * @param proxy    The connection proxy
     * @return A future for the source
     * @throws MalformedURLException If any error occurs when resolving URLs
     */
    public static CompletableFuture<AudioSource> getAudioSource(String url, @Nullable DownloadProgressListener listener, Proxy proxy, AudioSource.AudioFileType type) throws MalformedURLException {
        return getAudioSource(url, listener, proxy, type, new AudioCancellation());
    }

    public static CompletableFuture<AudioSource> getAudioSource(String url, @Nullable DownloadProgressListener listener,
                                                               Proxy proxy, AudioSource.AudioFileType type,
                                                               AudioCancellation cancellation) throws MalformedURLException {
        URI input;
        try {
            input = LegacyProviderResults.remote(url);
        } catch (IOException exception) {
            throw new MalformedURLException("Invalid audio URL");
        }
        DownloadProgressListener progress = listener == null ? null
                : new CancellationProgressListener(listener, cancellation::isCancelled);
        boolean bandcamp = BandcampPageReader.supports(input);
        boolean soundcloud = SoundCloudPageReader.supports(input);
        Optional<SoundDownloadSource> provider = bandcamp || soundcloud ? Optional.empty()
                : sources().stream().filter(s -> s.isValidUrl(url)).findFirst();
        URL direct = !bandcamp && !soundcloud && provider.isEmpty() ? new URL(url) : null;
        return ProviderAudioSourceRequests.submit(() -> {
            List<URL> resolved;
            if (bandcamp || soundcloud) {
                if (progress != null) {
                    progress.progressStartRequest(Component.translatable("resourcepack.requesting"));
                }
                List<URI> media = bandcamp
                        ? new BandcampMetadataResolver(proxy).resolveMediaUrls(input, cancellation)
                        : new SoundCloudMetadataResolver(proxy).resolveMediaUrls(input, cancellation);
                resolved = new ArrayList<>();
                for (URI uri : media) {
                    resolved.add(uri.toURL());
                }
                if (progress != null) {
                    progress.progressStartRequest(SoundDownloadSource.RESOLVING_TRACKS);
                }
            } else if (provider.isPresent()) {
                resolved = provider.get().resolveUrl(url, progress, proxy);
            } else {
                resolved = List.of(direct);
            }
            cancellation.throwIfCancelled();
            List<URL> urls = LegacyProviderResults.urls(resolved);
            boolean temporary = bandcamp || soundcloud || provider.map(s -> s.isTemporary(url)).orElse(false);
            cancellation.throwIfCancelled();
            return urls.size() == 1
                    ? new RawAudioSource(urls.get(0), progress, temporary, type, proxy, cancellation)
                    : new StreamingAudioSource(urls.toArray(URL[]::new), progress, temporary, type, proxy, cancellation);
        }, cancellation, !bandcamp && !soundcloud && provider.isPresent());
    }

    private static synchronized List<SoundDownloadSource> sources() {
        return List.copyOf(SOURCES);
    }

    /**
     * Resolves the author and title of a track from an external source.
     *
     * @param url      The URL to get the track info from
     * @param listener The listener for events
     * @param proxy    The connection proxy
     * @return The track information found or nothing
     * @throws IOException If any error occurs when connecting to the sources
     */
    public static CompletableFuture<TrackData[]> resolveTracks(String url, @Nullable DownloadProgressListener listener, Proxy proxy) throws IOException {
        URI input = LegacyProviderResults.remote(url);
        boolean bandcamp = BandcampPageReader.supports(input);
        boolean soundcloud = SoundCloudPageReader.supports(input);
        if (bandcamp || soundcloud) {
            return LegacyTrackMetadataRequests.submitCancellable(cancellation -> {
                if (listener != null && !cancellation.isCancelled()) {
                    listener.progressStartRequest(SoundDownloadSource.RESOLVING_TRACKS);
                }
                return bandcamp ? new BandcampMetadataResolver(proxy).resolveTracks(input, cancellation)
                        : new SoundCloudMetadataResolver(proxy).resolveTracks(input, cancellation);
            }, false);
        }
        SoundDownloadSource source = sources().stream().filter(s -> s.isValidUrl(url)).findFirst().orElseThrow(() -> new IOException("Unknown source for: " + url));
        return LegacyTrackMetadataRequests.submitCancellable(cancellation -> source.resolveTracks(url,
                listener == null ? null : new CancellationProgressListener(listener, cancellation::isCancelled), proxy), true);
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

    /**
     * Validates the URL is for an external source.
     *
     * @param url The URL to check
     * @return Whether that URL refers to an external source
     */
    public static boolean isValidUrl(String url) {
        return sources().stream().anyMatch(s -> s.isValidUrl(url));
    }
}
