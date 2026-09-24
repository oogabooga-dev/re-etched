package gg.moonflower.etched.client.radio;

import org.junit.jupiter.api.Test;

import java.util.concurrent.CancellationException;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AudioCancellationTest {

    @Test
    void cancellationRunsCallbacksExactlyOnce() {
        AudioCancellation cancellation = new AudioCancellation();
        AtomicInteger callbacks = new AtomicInteger();
        cancellation.onCancel(callbacks::incrementAndGet);

        assertTrue(cancellation.cancel());
        assertFalse(cancellation.cancel());
        assertTrue(cancellation.isCancelled());
        assertEquals(1, callbacks.get());
        assertThrows(CancellationException.class, cancellation::throwIfCancelled);
    }

    @Test
    void callbackRegisteredAfterCancellationRunsImmediately() {
        AudioCancellation cancellation = new AudioCancellation();
        cancellation.cancel();
        AtomicInteger callbacks = new AtomicInteger();

        cancellation.onCancel(callbacks::incrementAndGet);

        assertEquals(1, callbacks.get());
    }

    @Test
    void concurrentRegistrationCannotLoseCallbacks() throws Exception {
        AudioCancellation cancellation = new AudioCancellation();
        AtomicInteger callbacks = new AtomicInteger();
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService executor = Executors.newFixedThreadPool(8);

        try {
            @SuppressWarnings("unchecked")
            Future<Void>[] registrations = new Future[100];
            for (int i = 0; i < registrations.length; i++) {
                registrations[i] = executor.submit(() -> {
                    start.await();
                    cancellation.onCancel(callbacks::incrementAndGet);
                    return null;
                });
            }
            Future<Boolean> cancelled = executor.submit(() -> {
                start.await();
                return cancellation.cancel();
            });

            start.countDown();
            for (Future<Void> registration : registrations) {
                registration.get();
            }
            assertTrue(cancelled.get());
            assertEquals(100, callbacks.get());
        } finally {
            executor.shutdownNow();
        }
    }
}
