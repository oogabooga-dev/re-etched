package gg.moonflower.etched.common.audio;

import org.junit.jupiter.api.Test;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RadioResourceDisposerTest {

    @Test
    void runsCleanupOnADaemonThreadInsteadOfTheOwnerThread() throws Exception {
        Thread owner = Thread.currentThread();
        CompletableFuture<Thread> cleanup = new CompletableFuture<>();

        RadioResourceDisposer.dispose(() -> cleanup.complete(Thread.currentThread()));

        Thread worker = cleanup.get(2, TimeUnit.SECONDS);
        assertNotSame(owner, worker);
        assertTrue(worker.isDaemon());
    }

    @Test
    void blockingCleanupDoesNotPreventAnotherResourceFromClosing() throws Exception {
        CountDownLatch started = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        CompletableFuture<Void> second = new CompletableFuture<>();
        RadioResourceDisposer.dispose(() -> {
            started.countDown();
            try {
                release.await(5, TimeUnit.SECONDS);
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
            }
        });
        try {
            assertTrue(started.await(2, TimeUnit.SECONDS));
            RadioResourceDisposer.dispose(() -> second.complete(null));
            second.get(2, TimeUnit.SECONDS);
        } finally {
            release.countDown();
        }
    }

    @Test
    void rejectsMissingCleanupAction() {
        assertThrows(NullPointerException.class, () -> RadioResourceDisposer.dispose(null));
    }
}
