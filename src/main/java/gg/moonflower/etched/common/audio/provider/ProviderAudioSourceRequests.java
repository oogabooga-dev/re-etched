package gg.moonflower.etched.common.audio.provider;

import gg.moonflower.etched.api.sound.source.AudioSource;
import gg.moonflower.etched.common.audio.AudioCancellation;

import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

/** Bounded, request-owned URL resolution. No streams are created by these tasks. */
public final class ProviderAudioSourceRequests {

    private static final ThreadPoolExecutor FIRST_PARTY = workers("Etched provider audio");
    private static final ThreadPoolExecutor COMPATIBILITY = workers("Etched compatibility audio");

    private ProviderAudioSourceRequests() {
    }

    private static ThreadPoolExecutor workers(String name) {
        return new ThreadPoolExecutor(2, 2, 0L, TimeUnit.MILLISECONDS, new ArrayBlockingQueue<>(32), task -> {
            Thread thread = new Thread(task, name);
            thread.setDaemon(true);
            return thread;
        }, new ThreadPoolExecutor.AbortPolicy());
    }

    public static CompletableFuture<AudioSource> submit(Lookup lookup, AudioCancellation cancellation,
                                                         boolean compatibility) {
        return submit(lookup, cancellation, compatibility ? COMPATIBILITY : FIRST_PARTY);
    }

    static CompletableFuture<AudioSource> submit(Lookup lookup, AudioCancellation cancellation,
                                                ThreadPoolExecutor workers) {
        CompletableFuture<AudioSource> result = new CompletableFuture<>();
        Runnable task = () -> {
            if (result.isDone()) {
                return;
            }
            try {
                cancellation.throwIfCancelled();
                AudioSource source = lookup.resolve();
                cancellation.throwIfCancelled();
                result.complete(java.util.Objects.requireNonNull(source, "resolved source"));
            } catch (Throwable failure) {
                result.completeExceptionally(failure);
            }
        };
        result.whenComplete((source, failure) -> {
            if (result.isCancelled()) {
                cancellation.cancel();
                workers.remove(task);
            }
        });
        cancellation.onCancel(() -> result.cancel(false));
        if (!result.isDone()) {
            try {
                workers.execute(task);
                if (result.isDone()) {
                    workers.remove(task);
                }
            } catch (RuntimeException failure) {
                result.completeExceptionally(failure);
            }
        }
        return result;
    }

    @FunctionalInterface
    public interface Lookup {
        AudioSource resolve() throws Exception;
    }
}
