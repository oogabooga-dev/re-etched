package gg.moonflower.etched.common.audio.provider;

import gg.moonflower.etched.api.sound.source.AudioSource;
import gg.moonflower.etched.common.audio.AudioCancellation;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.io.IOException;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

@Timeout(10)
class ProviderAudioSourceRequestsTest {

    private static final AudioSource SOURCE = () -> { throw new AssertionError("URL resolution opened a stream"); };

    @Test
    void preCancelledLookupNeverStarts() {
        var workers = workers();
        var cancellation = new AudioCancellation();
        cancellation.cancel();
        try {
            var result = ProviderAudioSourceRequests.submit(() -> { throw new AssertionError("Cancelled lookup ran"); }, cancellation, workers);
            assertTrue(result.isCancelled());
            assertTrue(workers.getQueue().isEmpty());
        } finally {
            workers.shutdownNow();
        }
    }

    @Test
    void cancellingQueuedLookupFreesSlotAndCancellingRunningLookupDiscardsItsLateSource() throws Exception {
        var workers = workers();
        var runningScope = new AudioCancellation();
        var queuedScope = new AudioCancellation();
        CountDownLatch started = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        AtomicInteger calls = new AtomicInteger();
        try {
            var running = ProviderAudioSourceRequests.submit(() -> {
                started.countDown();
                await(release);
                calls.incrementAndGet();
                return SOURCE;
            }, runningScope, workers);
            assertTrue(started.await(2, TimeUnit.SECONDS));
            var queued = ProviderAudioSourceRequests.submit(() -> { throw new AssertionError("Cancelled queued lookup ran"); }, queuedScope, workers);
            assertEquals(1, workers.getQueue().size());
            queued.cancel(false);
            assertTrue(queuedScope.isCancelled());
            assertTrue(workers.getQueue().isEmpty());
            runningScope.cancel();
            assertTrue(running.isCancelled());
            var next = ProviderAudioSourceRequests.submit(() -> SOURCE, new AudioCancellation(), workers);
            release.countDown();
            assertSame(SOURCE, next.get(2, TimeUnit.SECONDS));
            assertEquals(1, calls.get());
            assertTrue(running.isCancelled());
        } finally {
            release.countDown();
            workers.shutdownNow();
        }
    }

    @Test
    void overloadFailsWithoutCallingRejectedSupplier() throws Exception {
        var workers = workers();
        CountDownLatch started = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        try {
            var running = ProviderAudioSourceRequests.submit(() -> { started.countDown(); await(release); return SOURCE; }, new AudioCancellation(), workers);
            assertTrue(started.await(2, TimeUnit.SECONDS));
            var queued = ProviderAudioSourceRequests.submit(() -> SOURCE, new AudioCancellation(), workers);
            var rejected = ProviderAudioSourceRequests.submit(() -> { throw new AssertionError("Rejected lookup ran"); }, new AudioCancellation(), workers);
            assertInstanceOf(RejectedExecutionException.class, assertThrows(CompletionException.class, rejected::join).getCause());
            release.countDown();
            running.get(2, TimeUnit.SECONDS);
            queued.get(2, TimeUnit.SECONDS);
        } finally {
            release.countDown();
            workers.shutdownNow();
        }
    }

    @Test
    void errorsAndNullResultsKeepExceptionChannelAndDoNotPoisonLaterRequests() {
        var workers = workers();
        IOException failure = new IOException("fixture lookup failure");
        try {
            var failed = ProviderAudioSourceRequests.submit(() -> { throw failure; }, new AudioCancellation(), workers);
            assertSame(failure, assertThrows(CompletionException.class, failed::join).getCause());
            var broken = ProviderAudioSourceRequests.submit(() -> { throw new LinkageError("fixture linkage"); }, new AudioCancellation(), workers);
            assertInstanceOf(LinkageError.class, assertThrows(CompletionException.class, broken::join).getCause());
            var missing = ProviderAudioSourceRequests.submit(() -> null, new AudioCancellation(), workers);
            assertInstanceOf(NullPointerException.class, assertThrows(CompletionException.class, missing::join).getCause());
            assertSame(SOURCE, ProviderAudioSourceRequests.submit(() -> SOURCE, new AudioCancellation(), workers).join());
        } finally {
            workers.shutdownNow();
        }
    }

    @Test
    void successfulSourceDeliveryDoesNotCancelItsStreamScope() {
        var workers = workers();
        var cancellation = new AudioCancellation();
        try {
            var result = ProviderAudioSourceRequests.submit(() -> SOURCE, cancellation, workers);
            assertSame(SOURCE, result.join());
            assertFalse(result.cancel(false));
            assertFalse(cancellation.isCancelled());
        } finally {
            workers.shutdownNow();
        }
    }

    @Test
    void blockedCompatibilitySuppliersCannotOccupyFirstPartyWorkers() throws Exception {
        CountDownLatch started = new CountDownLatch(2);
        CountDownLatch release = new CountDownLatch(1);
        ProviderAudioSourceRequests.Lookup blocked = () -> { started.countDown(); await(release); return SOURCE; };
        var one = ProviderAudioSourceRequests.submit(blocked, new AudioCancellation(), true);
        var two = ProviderAudioSourceRequests.submit(blocked, new AudioCancellation(), true);
        try {
            assertTrue(started.await(2, TimeUnit.SECONDS));
            assertSame(SOURCE, ProviderAudioSourceRequests.submit(() -> SOURCE, new AudioCancellation(), false).get(2, TimeUnit.SECONDS));
        } finally {
            release.countDown();
            one.get(2, TimeUnit.SECONDS);
            two.get(2, TimeUnit.SECONDS);
        }
    }

    private static ThreadPoolExecutor workers() {
        return new ThreadPoolExecutor(1, 1, 0L, TimeUnit.MILLISECONDS, new ArrayBlockingQueue<>(1));
    }

    private static void await(CountDownLatch release) throws IOException {
        try {
            assertTrue(release.await(5, TimeUnit.SECONDS));
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IOException(exception);
        }
    }
}
