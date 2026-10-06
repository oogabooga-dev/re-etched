package gg.moonflower.etched.common.audio.provider;

import gg.moonflower.etched.common.audio.RecordContent;
import gg.moonflower.etched.common.audio.AudioCancellation;
import org.jetbrains.annotations.ApiStatus;

import java.io.IOException;
import java.net.Proxy;
import java.net.URI;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;

/** Bounded, request-owned metadata for built-in services; no legacy provider registry or shared futures. */
@ApiStatus.Internal
public final class TrackMetadataRequests {

    private static final ThreadPoolExecutor WORKERS = new ThreadPoolExecutor(2, 2,
            0L, TimeUnit.MILLISECONDS, new ArrayBlockingQueue<>(32), task -> {
        Thread thread = new Thread(task, "Etched provider track metadata");
        thread.setDaemon(true);
        return thread;
    }, new ThreadPoolExecutor.AbortPolicy());

    private TrackMetadataRequests() {
    }

    public static boolean supports(URI input) {
        return BandcampPageReader.supports(input) || SoundCloudPageReader.supports(input);
    }

    public static CompletableFuture<RecordContent> resolve(URI input, Proxy proxy, AudioCancellation cancellation) {
        return resolve(input, proxy, cancellation, BandcampMetadataResolver::new, SoundCloudMetadataResolver::new);
    }

    static CompletableFuture<RecordContent> resolve(URI input, Proxy proxy, AudioCancellation cancellation,
                                                 Function<Proxy, BandcampMetadataResolver> bandcamp,
                                                 Function<Proxy, SoundCloudMetadataResolver> soundcloud) {
        return submitCancellable(token -> {
            if (BandcampPageReader.supports(input)) {
                return bandcamp.apply(proxy).resolveTracks(input, token);
            }
            if (SoundCloudPageReader.supports(input)) {
                return soundcloud.apply(proxy).resolveTracks(input, token);
            }
            throw new IOException("Unknown metadata service: " + input);
        }, cancellation);
    }

    static CompletableFuture<RecordContent> submit(MetadataLookup lookup, ThreadPoolExecutor workers) {
        return submitCancellable(cancellation -> lookup.resolve(), new AudioCancellation(), workers);
    }

    static CompletableFuture<RecordContent> submitCancellable(CancellableMetadataLookup lookup, AudioCancellation cancellation) {
        return submitCancellable(lookup, cancellation, WORKERS);
    }

    static CompletableFuture<RecordContent> submitCancellable(CancellableMetadataLookup lookup,
                                                          AudioCancellation cancellation, ThreadPoolExecutor workers) {
        CompletableFuture<RecordContent> result = new CompletableFuture<>();
        Runnable task = () -> {
            if (result.isDone() || cancellation.isCancelled()) {
                return;
            }
            try {
                RecordContent content = requireContent(lookup.resolve(cancellation));
                if (!result.isDone() && !cancellation.isCancelled()) {
                    result.complete(content);
                }
            } catch (Throwable failure) {
                // Match CompletableFuture's exception channel, including provider linkage/errors.
                result.completeExceptionally(failure);
            }
        };
        result.whenComplete((tracks, failure) -> {
            if (result.isCancelled()) {
                cancellation.cancel();
                workers.remove(task);
            }
        });
        cancellation.onCancel(() -> {
            result.cancel(false);
            workers.remove(task);
        });
        if (result.isDone()) {
            return result;
        }
        try {
            workers.execute(task);
            if (result.isCancelled()) {
                workers.remove(task);
            }
        } catch (RejectedExecutionException failure) {
            result.completeExceptionally(failure);
        }
        return result;
    }

    /** The factory must start a fresh request, not return a shared provider future. */
    public static RecordContent await(FutureRequest request, AudioCancellation cancellation) throws IOException {
        cancellation.throwIfCancelled();
        CompletableFuture<RecordContent> pending = request.start();
        cancellation.onCancel(() -> pending.cancel(false));
        RecordContent content = pending.join();
        cancellation.throwIfCancelled();
        return requireContent(content);
    }

    private static RecordContent requireContent(RecordContent content) throws IOException {
        if (content == null) {
            throw new IOException("Missing record metadata");
        }
        return content; // RecordContent and its program/metadata are already bounded and immutable.
    }

    @FunctionalInterface
    interface MetadataLookup {
        RecordContent resolve() throws IOException;
    }

    @FunctionalInterface
    interface CancellableMetadataLookup {
        RecordContent resolve(AudioCancellation cancellation) throws IOException;
    }

    @FunctionalInterface
    public interface FutureRequest {
        CompletableFuture<RecordContent> start() throws IOException;
    }
}
