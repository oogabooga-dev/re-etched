package gg.moonflower.etched.client.cache;

import gg.moonflower.etched.client.radio.AudioCancellation;
import gg.moonflower.etched.client.radio.net.AudioHttpRequest;
import gg.moonflower.etched.client.radio.net.AudioHttpResponse;
import gg.moonflower.etched.client.radio.source.AudioResolveContext;

import java.io.IOException;
import java.net.URI;
import java.util.Objects;
import java.util.function.Function;

/** Checks destinations even on cache hits; misses use the shared proxy-aware secure transport. */
public final class CoverCacheLoader {

    private CoverCacheLoader() {
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
