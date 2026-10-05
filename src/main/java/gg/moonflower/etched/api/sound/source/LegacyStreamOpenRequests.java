package gg.moonflower.etched.api.sound.source;

import gg.moonflower.etched.api.util.AsyncInputStream;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.io.InputStream;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.Executor;

/** Owns the open-to-future handoff; cancellation cannot discard a late stream. */
final class LegacyStreamOpenRequests {

    private static final Logger LOGGER = LogManager.getLogger();

    private LegacyStreamOpenRequests() {
    }

    static CompletableFuture<InputStream> open(
            CompletableFuture<AsyncInputStream.InputStreamSupplier> location, Executor executor) {
        CompletableFuture<InputStream> result = new CompletableFuture<>();
        location.whenComplete((supplier, failure) -> {
            if (result.isDone()) {
                return;
            }
            if (failure != null) {
                result.completeExceptionally(failure);
                return;
            }
            try {
                executor.execute(() -> {
                    if (result.isDone()) {
                        return;
                    }
                    InputStream opened;
                    try {
                        opened = Objects.requireNonNull(supplier.get(), "opened stream");
                    } catch (Throwable exception) {
                        result.completeExceptionally(new CompletionException("Failed to open stream", exception));
                        return;
                    }
                    if (!result.complete(opened)) {
                        // Never cancel a resource-producing task: observe and dispose its result instead.
                        try {
                            opened.close();
                        } catch (Exception exception) {
                            LOGGER.warn("Failed to close retired legacy audio stream", exception);
                        }
                    }
                });
            } catch (RuntimeException exception) {
                result.completeExceptionally(exception);
            }
        });
        return result;
    }
}
