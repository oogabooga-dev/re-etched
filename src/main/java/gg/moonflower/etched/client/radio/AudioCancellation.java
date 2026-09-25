package gg.moonflower.etched.client.radio;

import java.util.Objects;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;

/**
 * A one-shot cancellation signal shared by all resources owned by one attempt.
 */
public final class AudioCancellation {

    private final CompletableFuture<Void> cancelled = new CompletableFuture<>();

    public boolean isCancelled() {
        return this.cancelled.isDone();
    }

    public void throwIfCancelled() {
        if (this.isCancelled()) {
            throw new CancellationException("Radio playback attempt was cancelled");
        }
    }

    public void onCancel(Runnable action) {
        Objects.requireNonNull(action, "action");
        this.cancelled.thenRun(action);
    }

    public boolean cancel() {
        return this.cancelled.complete(null);
    }
}
