package gg.moonflower.etched.common.audio.provider;

import gg.moonflower.etched.api.record.TrackData;
import gg.moonflower.etched.common.audio.AudioCancellation;

import java.io.IOException;
import java.util.List;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

/** Request-owned compatibility metadata. The old provider API cannot interrupt its own I/O. */
public final class LegacyTrackMetadataRequests {

    private static final ThreadPoolExecutor WORKERS = new ThreadPoolExecutor(2, 2,
            0L, TimeUnit.MILLISECONDS, new ArrayBlockingQueue<>(32), task -> {
        Thread thread = new Thread(task, "Etched compatibility track metadata");
        thread.setDaemon(true);
        return thread;
    }, new ThreadPoolExecutor.AbortPolicy());
    private static final ThreadPoolExecutor FIRST_PARTY = new ThreadPoolExecutor(2, 2,
            0L, TimeUnit.MILLISECONDS, new ArrayBlockingQueue<>(32), task -> {
        Thread thread = new Thread(task, "Etched provider track metadata");
        thread.setDaemon(true);
        return thread;
    }, new ThreadPoolExecutor.AbortPolicy());

    private LegacyTrackMetadataRequests() {
    }

    public static CompletableFuture<TrackData[]> submit(MetadataLookup lookup) {
        return submit(lookup, WORKERS);
    }

    static CompletableFuture<TrackData[]> submit(MetadataLookup lookup, ThreadPoolExecutor workers) {
        return submitCancellable(cancellation -> lookup.resolve(), workers);
    }

    public static CompletableFuture<TrackData[]> submitCancellable(CancellableMetadataLookup lookup, boolean compatibility) {
        return submitCancellable(lookup, compatibility ? WORKERS : FIRST_PARTY);
    }

    private static CompletableFuture<TrackData[]> submitCancellable(CancellableMetadataLookup lookup, ThreadPoolExecutor workers) {
        CompletableFuture<TrackData[]> result = new CompletableFuture<>();
        AudioCancellation cancellation = new AudioCancellation();
        Runnable task = () -> {
            if (result.isDone()) {
                return;
            }
            try {
                List<TrackData> tracks = lookup.resolve(cancellation);
                if (!result.isDone()) {
                    result.complete(LegacyProviderResults.tracks(tracks));
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
    public static TrackData[] await(FutureRequest request, AudioCancellation cancellation) throws IOException {
        cancellation.throwIfCancelled();
        CompletableFuture<TrackData[]> pending = request.start();
        cancellation.onCancel(() -> pending.cancel(false));
        TrackData[] tracks = pending.join();
        cancellation.throwIfCancelled();
        // Menus replace entries while applying label artist fallbacks; never expose a provider's array.
        return LegacyProviderResults.tracks(java.util.Arrays.asList(tracks));
    }

    @FunctionalInterface
    public interface MetadataLookup {
        List<TrackData> resolve() throws IOException;
    }

    @FunctionalInterface
    public interface CancellableMetadataLookup {
        List<TrackData> resolve(AudioCancellation cancellation) throws IOException;
    }

    @FunctionalInterface
    public interface FutureRequest {
        CompletableFuture<TrackData[]> start() throws IOException;
    }
}
