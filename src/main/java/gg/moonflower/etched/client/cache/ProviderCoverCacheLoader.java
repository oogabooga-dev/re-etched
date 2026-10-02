package gg.moonflower.etched.client.cache;

import gg.moonflower.etched.api.util.DownloadProgressListener;
import gg.moonflower.etched.client.radio.source.AudioResolveContext;
import gg.moonflower.etched.common.audio.AudioCancellation;
import gg.moonflower.etched.common.audio.provider.BandcampMetadataResolver;
import gg.moonflower.etched.common.audio.provider.BandcampPageReader;
import gg.moonflower.etched.common.audio.provider.SoundCloudMetadataResolver;
import gg.moonflower.etched.common.audio.provider.SoundCloudPageReader;
import gg.moonflower.etched.core.Etched;
import net.minecraft.network.chat.Component;
import org.jetbrains.annotations.Nullable;

import java.io.IOException;
import java.net.URI;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Function;

/** One cancellation scope across first-party cover metadata and the existing bounded image cache. */
public final class ProviderCoverCacheLoader {

    private ProviderCoverCacheLoader() {
    }

    public static boolean supports(URI uri) {
        return BandcampPageReader.supports(uri) || SoundCloudPageReader.supports(uri);
    }

    public static Optional<BoundedMediaCache.Lease> open(BoundedMediaCache cache, URI input,
                                                        AudioCancellation cancellation,
                                                        Function<AudioCancellation, AudioResolveContext> contexts,
                                                        @Nullable DownloadProgressListener listener) throws IOException {
        Objects.requireNonNull(cache, "cache");
        Objects.requireNonNull(contexts, "contexts");
        cancellation.throwIfCancelled();
        if (!supports(input)) {
            throw new IOException("Unsupported first-party cover provider");
        }
        AudioResolveContext context = contexts.apply(cancellation);
        boolean bandcamp = BandcampPageReader.supports(input);
        if (listener != null) {
            listener.progressStartRequest(Component.translatable("sound_source." + Etched.MOD_ID + ".requesting",
                    bandcamp ? "Bandcamp" : "SoundCloud"));
        }
        Optional<URI> cover = bandcamp
                ? new BandcampMetadataResolver(context.transport(), context.networkPolicy(), BandcampMetadataResolver.Limits.DEFAULT)
                    .resolveAlbumCover(input, cancellation)
                : new SoundCloudMetadataResolver(context.transport(), context.networkPolicy(),
                    URI.create("https://soundcloud.com/"), URI.create("https://api-v2.soundcloud.com/resolve"),
                    SoundCloudMetadataResolver.Limits.DEFAULT).resolveAlbumCover(input, cancellation);
        cancellation.throwIfCancelled();
        if (cover.isEmpty()) {
            return Optional.empty();
        }
        return Optional.of(CoverCacheLoader.open(cache, cover.get(), cancellation, contexts));
    }
}
