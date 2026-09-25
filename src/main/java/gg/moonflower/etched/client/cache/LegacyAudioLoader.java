package gg.moonflower.etched.client.cache;

import gg.moonflower.etched.api.util.DownloadProgressListener;
import gg.moonflower.etched.api.util.ProgressTrackingInputStream;
import gg.moonflower.etched.client.radio.AudioCancellation;
import gg.moonflower.etched.client.radio.net.AudioHttpRequest;
import gg.moonflower.etched.client.radio.net.AudioHttpResponse;
import gg.moonflower.etched.client.radio.source.AudioResolveContext;

import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.util.function.Function;
import org.jetbrains.annotations.Nullable;

/** Preserves the legacy AudioSource entry point while sharing the v5 transport and cache. */
public final class LegacyAudioLoader {

    private LegacyAudioLoader() {
    }

    public static InputStream file(BoundedMediaCache cache, URI uri,
                                   Function<AudioCancellation, AudioResolveContext> contexts) throws IOException {
        return file(cache, uri, contexts, null);
    }

    public static InputStream file(BoundedMediaCache cache, URI uri,
                                   Function<AudioCancellation, AudioResolveContext> contexts,
                                   @Nullable DownloadProgressListener listener) throws IOException {
        AudioCancellation cancellation = new AudioCancellation();
        AudioResolveContext context = contexts.apply(cancellation);
        context.networkPolicy().check(uri, cancellation);
        BoundedMediaCache.Lease lease;
        try {
            lease = cache.acquire(BoundedMediaCache.Namespace.AUDIO, uri.toString(), cancellation, token -> {
                AudioHttpResponse response = execute(token, contexts, AudioHttpRequest.resource(uri));
                long length = response.contentLength().orElse(-1L);
                InputStream body = listener != null && length > 0
                        ? new ProgressTrackingInputStream(response.body(), length, listener) : response.body();
                return new BoundedMediaCache.Content(body, length);
            }, MediaValidators::audio);
        } catch (MediaValidators.UnsupportedAudioException unsupported) {
            // WAV and other legacy file types are playable, but must not be cached as MP3/Ogg.
            return stream(uri, new AudioCancellation(), contexts);
        }
        return new FilterInputStream(lease.body()) {
            @Override
            public void close() throws IOException {
                cancellation.cancel();
                lease.close();
            }
        };
    }

    /** Live stations never write disk entries. Closing the stream closes its owned HTTP response. */
    public static InputStream stream(URI uri,
                                     AudioCancellation cancellation,
                                     Function<AudioCancellation, AudioResolveContext> contexts) throws IOException {
        AudioHttpResponse response = execute(cancellation, contexts, AudioHttpRequest.resource(uri));
        return new FilterInputStream(response.body()) {
            @Override
            public void close() throws IOException {
                cancellation.cancel();
                response.close();
            }
        };
    }

    private static AudioHttpResponse execute(AudioCancellation cancellation,
                                             Function<AudioCancellation, AudioResolveContext> contexts,
                                             AudioHttpRequest request) throws IOException {
        AudioResolveContext context = contexts.apply(cancellation);
        AudioHttpResponse response = context.transport().execute(request, cancellation);
        if (response.statusCode() != 200) {
            int status = response.statusCode();
            response.close();
            throw new IOException("Audio request returned HTTP " + status);
        }
        return response;
    }
}
