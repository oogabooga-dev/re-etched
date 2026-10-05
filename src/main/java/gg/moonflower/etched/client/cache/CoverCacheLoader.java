package gg.moonflower.etched.client.cache;

import gg.moonflower.etched.common.audio.AudioCancellation;
import gg.moonflower.etched.common.audio.net.AudioHttpRequest;
import gg.moonflower.etched.common.audio.net.AudioHttpResponse;
import gg.moonflower.etched.client.radio.source.AudioResolveContext;

import java.io.IOException;
import java.net.URI;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Function;
import java.util.function.Supplier;

/** Checks destinations even on cache hits; misses use the shared proxy-aware secure transport. */
public final class CoverCacheLoader {

    private CoverCacheLoader() {
    }

    /** A legacy resolver may not observe cancellation; never advance to image I/O after it returns late. */
    public static Optional<BoundedMediaCache.Lease> openResolved(Supplier<BoundedMediaCache> cache,
                                                                CoverUrlResolver urls,
                                                                AudioCancellation cancellation,
                                                                Function<AudioCancellation, AudioResolveContext> contexts)
            throws IOException {
        Objects.requireNonNull(cache, "cache");
        Objects.requireNonNull(urls, "urls");
        Objects.requireNonNull(contexts, "contexts");
        cancellation.throwIfCancelled();
        Optional<URI> resolved = urls.resolve(cancellation);
        cancellation.throwIfCancelled();
        if (resolved.isEmpty()) {
            return Optional.empty();
        }
        return Optional.of(open(cache.get(), resolved.get(), cancellation, contexts));
    }

    @FunctionalInterface
    public interface CoverUrlResolver {
        Optional<URI> resolve(AudioCancellation cancellation) throws IOException;
    }

    public static BoundedMediaCache.Lease open(BoundedMediaCache cache, URI uri,
                                                AudioCancellation cancellation,
                                                Function<AudioCancellation, AudioResolveContext> contexts)
            throws IOException {
        Objects.requireNonNull(cache, "cache");
        Objects.requireNonNull(uri, "uri");
        Objects.requireNonNull(contexts, "contexts");
        AudioResolveContext context = contexts.apply(cancellation);
        context.networkPolicy().check(uri, cancellation);
        return cache.acquire(BoundedMediaCache.Namespace.COVERS, uri.toString(), cancellation, token -> {
            AudioResolveContext fetchContext = contexts.apply(token);
            AudioHttpResponse response = fetchContext.transport().execute(AudioHttpRequest.resource(uri), token);
            if (response.statusCode() != 200) {
                int status = response.statusCode();
                response.close();
                throw new IOException("Cover request returned HTTP " + status);
            }
            return new BoundedMediaCache.Content(response.body(), response.contentLength().orElse(-1L));
        }, MediaValidators::cover);
    }
}
