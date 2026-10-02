package gg.moonflower.etched.api.util;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.util.Arrays;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executor;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

@Timeout(10)
class AsyncInputStreamTest {

    private final ExecutorService worker = Executors.newSingleThreadExecutor(task -> {
        Thread thread = new Thread(task, "AsyncInputStream fixture");
        thread.setDaemon(true);
        return thread;
    });
    private final CompletableFuture<Void> finished = new CompletableFuture<>();
    private final Executor executor = task -> this.worker.execute(() -> {
        try {
            task.run();
            this.finished.complete(null);
        } catch (Throwable failure) {
            this.finished.completeExceptionally(failure);
        }
    });

    @AfterEach
    void stopWorker() throws Exception {
        this.worker.shutdownNow();
        assertTrue(this.worker.awaitTermination(2, TimeUnit.SECONDS), "Fixture worker did not retire");
    }

    @Test
    void emptyEofReleasesInitialWaitAndTerminatesTheProducer() throws Exception {
        var source = new TrackedStream(new byte[0]);
        try (var stream = new AsyncInputStream(() -> source, 8, 8, this.executor)) {
            this.finished.get(2, TimeUnit.SECONDS);
            assertEquals(-1, stream.read());
            assertEquals(-1, stream.read(new byte[8]));
            assertEquals(0, stream.read(new byte[0]));
            assertEquals(0, stream.skip(20));
            assertEquals(1, source.closes.get());
        }
        assertEquals(1, source.closes.get());
    }

    @Test
    void shortEofPublishesItsPartialBufferWithoutWaitingForTheInitialTarget() throws Exception {
        byte[] bytes = {1, (byte) 255, 3};
        var source = new TrackedStream(bytes);
        try (var stream = new AsyncInputStream(() -> source, 8, 8, this.executor)) {
            this.finished.get(2, TimeUnit.SECONDS);
            assertArrayEquals(bytes, stream.readAllBytes());
            assertEquals(-1, stream.read());
            assertEquals(1, source.closes.get());
        }
    }

    @Test
    void finiteDataRemainsOrderedAcrossFullAndPartialBuffers() throws Exception {
        byte[] bytes = new byte[103];
        for (int i = 0; i < bytes.length; i++) {
            bytes[i] = (byte) i;
        }
        var source = new TrackedStream(bytes);
        try (var stream = new AsyncInputStream(() -> source, 8, 2, this.executor)) {
            assertEquals(0, stream.read(new byte[0]));
            assertThrows(IndexOutOfBoundsException.class, () -> stream.read(new byte[1], 1, 1));
            assertArrayEquals(bytes, stream.readAllBytes());
            this.finished.get(2, TimeUnit.SECONDS);
            assertEquals(1, source.closes.get());
        }
    }

    @Test
    void bufferingCapsRequestedPrefetchAt32KiBIncludingTheConsumerHeldBuffer() throws Exception {
        AtomicInteger bytesRead = new AtomicInteger();
        AtomicInteger closes = new AtomicInteger();
        CountDownLatch refilled = new CountDownLatch(1);
        InputStream source = new InputStream() {
            @Override public int read() { return 42; }
            @Override public int read(byte[] bytes, int offset, int length) {
                Arrays.fill(bytes, offset, offset + length, (byte) 42);
                if (bytesRead.addAndGet(length) > 32768) {
                    refilled.countDown();
                }
                return length;
            }
            @Override public void close() { closes.incrementAndGet(); }
        };
        try (var stream = new AsyncInputStream(() -> source, 8192, Integer.MAX_VALUE, this.executor)) {
            assertEquals(32768, bytesRead.get());
            assertEquals(42, stream.read());
            assertEquals(32768, bytesRead.get(), "Partially consumed buffer must still occupy a slot");
            assertEquals(8191, stream.skip(8191));
            assertTrue(refilled.await(2, TimeUnit.SECONDS));
            assertEquals(40960, bytesRead.get());
        }
        this.finished.get(2, TimeUnit.SECONDS);
        assertEquals(1, closes.get());
    }

    @Test
    void temporaryUnderflowWaitsForDataRatherThanReturningFalseEof() throws Exception {
        CountDownLatch waiting = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        var source = new TrackedStream(new byte[]{1, 2}) {
            private int reads;
            @Override public synchronized int read(byte[] bytes, int offset, int length) {
                if (this.reads++ == 1) {
                    waiting.countDown();
                    await(release);
                }
                return super.read(bytes, offset, length);
            }
        };
        try (var stream = new AsyncInputStream(() -> source, 1, 1, this.executor)) {
            assertEquals(1, stream.read());
            assertTrue(waiting.await(2, TimeUnit.SECONDS));
            var reader = CompletableFuture.supplyAsync(() -> {
                try {
                    return stream.read();
                } catch (IOException failure) {
                    throw new java.util.concurrent.CompletionException(failure);
                }
            });
            assertFalse(reader.isDone());
            release.countDown();
            assertEquals(2, reader.get(2, TimeUnit.SECONDS));
            assertEquals(-1, stream.read());
        } finally {
            release.countDown();
        }
    }

    @Test
    void closeWakesConsumerClosesDelegateAndDoesNotJoinUninterruptibleRead() throws Exception {
        CountDownLatch blocked = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        AtomicInteger closes = new AtomicInteger();
        InputStream source = new InputStream() {
            private int reads;
            @Override public int read() { throw new AssertionError("Unexpected single byte read"); }
            @Override public int read(byte[] bytes, int offset, int length) {
                if (this.reads++ == 0) {
                    bytes[offset] = 42;
                    return 1;
                }
                blocked.countDown();
                await(release);
                bytes[offset] = 99;
                return 1;
            }
            @Override public void close() { closes.incrementAndGet(); }
        };
        var stream = new AsyncInputStream(() -> source, 1, 1, this.executor);
        try {
            assertEquals(42, stream.read());
            assertTrue(blocked.await(2, TimeUnit.SECONDS));
            var reader = CompletableFuture.supplyAsync(() -> assertThrows(IOException.class, stream::read));
            stream.close();
            assertEquals("Stream is closed", reader.get(2, TimeUnit.SECONDS).getMessage());
            assertEquals(1, closes.get());
            assertFalse(this.finished.isDone(), "close must not wait for the provider's blocked read");
            assertThrows(IOException.class, () -> stream.read(new byte[1]));
            assertInstanceOf(IOException.class, assertThrows(UncheckedIOException.class, () -> stream.skip(1)).getCause());
            release.countDown();
            this.finished.get(2, TimeUnit.SECONDS);
            stream.close();
            assertEquals(1, closes.get());
        } finally {
            release.countDown();
            stream.close();
        }
    }

    @Test
    void closeWhileProducerWaitsForCapacityTerminatesWithoutReadingExtraData() throws Exception {
        var source = new TrackedStream(new byte[100]);
        var stream = new AsyncInputStream(() -> source, 4, 1, this.executor);
        stream.close();
        this.finished.get(2, TimeUnit.SECONDS);
        assertEquals(1, source.closes.get());
        stream.close();
        assertEquals(1, source.closes.get());
    }

    @Test
    void initialIoFailureKeepsItsCauseAndClosesTheSource() throws Exception {
        IOException failure = new IOException("fixture initial read failure");
        AtomicInteger closes = new AtomicInteger();
        InputStream source = new InputStream() {
            @Override public int read() throws IOException { throw failure; }
            @Override public void close() { closes.incrementAndGet(); }
        };
        assertSame(failure, assertThrows(IOException.class, () -> new AsyncInputStream(() -> source, 4, 1, this.executor)));
        this.finished.get(2, TimeUnit.SECONDS);
        assertEquals(1, closes.get());
    }

    @Test
    void uncheckedSupplierFailureReleasesInitialWaitInsteadOfHanging() throws Exception {
        LinkageError failure = new LinkageError("fixture provider failure");
        assertSame(failure, assertThrows(IOException.class, () -> new AsyncInputStream(() -> { throw failure; }, 4, 1, this.executor)).getCause());
        this.finished.get(2, TimeUnit.SECONDS);
    }

    @Test
    void laterReadFailureIsReportedAfterAlreadyPublishedBuffers() throws Exception {
        IOException failure = new IOException("fixture late read failure");
        AtomicInteger closes = new AtomicInteger();
        InputStream source = new InputStream() {
            private int reads;
            @Override public int read() throws IOException {
                if (this.reads++ == 0) {
                    return 42;
                }
                throw failure;
            }
            @Override public void close() { closes.incrementAndGet(); }
        };
        try (var stream = new AsyncInputStream(() -> source, 1, 1, this.executor)) {
            assertEquals(42, stream.read());
            assertSame(failure, assertThrows(IOException.class, stream::read));
            assertSame(failure, assertThrows(UncheckedIOException.class, () -> stream.skip(1)).getCause());
            assertEquals(1, closes.get());
        }
    }

    @Test
    void failureAfterAShortReadDoesNotDiscardAcceptedBytes() throws Exception {
        IOException failure = new IOException("fixture partial buffer failure");
        InputStream source = new InputStream() {
            private int reads;
            @Override public int read() { throw new AssertionError("Unexpected single byte read"); }
            @Override public int read(byte[] bytes, int offset, int length) throws IOException {
                int count = switch (this.reads++) {
                    case 0 -> 4;
                    case 1 -> 2;
                    default -> throw failure;
                };
                Arrays.fill(bytes, offset, offset + count, (byte) 42);
                return count;
            }
        };
        try (var stream = new AsyncInputStream(() -> source, 4, 1, this.executor)) {
            assertEquals(4, stream.read(new byte[4]));
            assertEquals(2, stream.read(new byte[4]));
            assertSame(failure, assertThrows(IOException.class, stream::read));
        }
    }

    @Test
    void zeroReturningBulkSourceMakesProgressViaSingleByteRead() throws Exception {
        InputStream source = new InputStream() {
            private int reads;
            @Override public int read() { return this.reads++ < 3 ? 42 : -1; }
            @Override public int read(byte[] bytes, int offset, int length) { return 0; }
        };
        try (var stream = new AsyncInputStream(() -> source, 4, 1, this.executor)) {
            assertArrayEquals(new byte[]{42, 42, 42}, stream.readAllBytes());
            this.finished.get(2, TimeUnit.SECONDS);
        }
    }

    @Test
    void delegateCloseFailureDoesNotJoinOrDoubleCloseTheProducer() throws Exception {
        IOException failure = new IOException("fixture close failure");
        AtomicInteger closes = new AtomicInteger();
        InputStream source = new InputStream() {
            @Override public int read() { return 42; }
            @Override public void close() throws IOException {
                closes.incrementAndGet();
                throw failure;
            }
        };
        var stream = new AsyncInputStream(() -> source, 1, 1, this.executor);
        assertSame(failure, assertThrows(IOException.class, stream::close));
        this.finished.get(2, TimeUnit.SECONDS);
        assertThrows(IOException.class, stream::read);
        assertDoesNotThrow(stream::close);
        assertEquals(1, closes.get());
    }

    @Test
    void eofCloseFailureReleasesTheConstructorAndKeepsItsCause() throws Exception {
        IOException failure = new IOException("fixture EOF close failure");
        InputStream source = new InputStream() {
            @Override public int read() { return -1; }
            @Override public void close() throws IOException { throw failure; }
        };
        assertSame(failure, assertThrows(IOException.class, () -> new AsyncInputStream(() -> source, 4, 1, this.executor)));
        this.finished.get(2, TimeUnit.SECONDS);
    }

    @Test
    void eofRacingWithCloseRetiresTheDelegateExactlyOnce() throws Exception {
        for (int i = 0; i < 100; i++) {
            var done = new CompletableFuture<Void>();
            Executor tracked = task -> this.worker.execute(() -> {
                try {
                    task.run();
                    done.complete(null);
                } catch (Throwable failure) {
                    done.completeExceptionally(failure);
                }
            });
            var source = new TrackedStream(new byte[]{42});
            var stream = new AsyncInputStream(() -> source, 1, 1, tracked);
            assertEquals(42, stream.read());
            stream.close();
            done.get(2, TimeUnit.SECONDS);
            stream.close();
            assertEquals(1, source.closes.get());
        }
    }

    @Test
    void interruptedQueuedConstructionPreventsTheSupplierFromStarting() throws Exception {
        CountDownLatch queued = new CountDownLatch(1);
        AtomicInteger opened = new AtomicInteger();
        var task = new java.util.concurrent.atomic.AtomicReference<Runnable>();
        var outcome = new CompletableFuture<Boolean>();
        Thread constructor = new Thread(() -> {
            try {
                assertThrows(IOException.class, () -> new AsyncInputStream(() -> {
                    opened.incrementAndGet();
                    return InputStream.nullInputStream();
                }, 1, 1, pending -> {
                    task.set(pending);
                    queued.countDown();
                }));
                outcome.complete(Thread.currentThread().isInterrupted());
            } catch (Throwable failure) {
                outcome.completeExceptionally(failure);
            }
        });
        constructor.start();
        try {
            assertTrue(queued.await(2, TimeUnit.SECONDS));
            constructor.interrupt();
            assertTrue(outcome.get(2, TimeUnit.SECONDS));
            this.executor.execute(task.get());
            this.finished.get(2, TimeUnit.SECONDS);
            assertEquals(0, opened.get());
        } finally {
            constructor.interrupt();
            constructor.join(2000);
        }
    }

    @Test
    void interruptedConstructorRetiresAndClosesTheLateOpenedSource() throws Exception {
        CountDownLatch opening = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        var late = new TrackedStream(new byte[]{42});
        var outcome = new CompletableFuture<Boolean>();
        Thread constructor = new Thread(() -> {
            try {
                IOException failure = assertThrows(IOException.class, () -> new AsyncInputStream(() -> {
                    opening.countDown();
                    await(release);
                    return late;
                }, 1, 1, this.executor));
                assertInstanceOf(InterruptedException.class, failure.getCause());
                outcome.complete(Thread.currentThread().isInterrupted());
            } catch (Throwable failure) {
                outcome.completeExceptionally(failure);
            }
        });
        constructor.start();
        try {
            assertTrue(opening.await(2, TimeUnit.SECONDS));
            constructor.interrupt();
            assertTrue(outcome.get(2, TimeUnit.SECONDS));
            assertFalse(this.finished.isDone());
            release.countDown();
            this.finished.get(2, TimeUnit.SECONDS);
            assertEquals(1, late.closes.get());
        } finally {
            release.countDown();
            constructor.interrupt();
            constructor.join(2000);
        }
    }

    @Test
    void invalidSettingsAndRejectedWorkNeverOpenTheSource() {
        AsyncInputStream.InputStreamSupplier source = () -> { throw new AssertionError("Invalid operation opened a source"); };
        for (int size : new int[]{0, -1, 32769}) {
            assertThrows(IllegalArgumentException.class, () -> new AsyncInputStream(source, size, 1, this.executor));
        }
        assertThrows(IllegalArgumentException.class, () -> new AsyncInputStream(source, 1, 0, this.executor));
        var rejection = new java.util.concurrent.RejectedExecutionException("fixture overload");
        assertSame(rejection, assertThrows(IOException.class,
                () -> new AsyncInputStream(source, 1, 1, task -> { throw rejection; })).getCause());
    }

    @Test
    void skipCountsBytesAcrossBuffersAndZeroLengthReadDoesNotConsume() throws Exception {
        try (var stream = new AsyncInputStream(() -> new ByteArrayInputStream(new byte[]{1, 2, 3, 4, 5}), 2, 1, this.executor)) {
            assertEquals(0, stream.read(new byte[0]));
            assertEquals(0, stream.skip(-1));
            assertEquals(0, stream.skip(0));
            assertEquals(3, stream.skip(3));
            assertEquals(4, stream.read());
            assertEquals(1, stream.skip(10));
            assertEquals(-1, stream.read());
        }
    }

    private static void await(CountDownLatch latch) {
        try {
            if (!latch.await(5, TimeUnit.SECONDS)) {
                throw new AssertionError("Fixture was not released");
            }
        } catch (InterruptedException failure) {
            Thread.currentThread().interrupt();
            throw new AssertionError(failure);
        }
    }

    private static class TrackedStream extends ByteArrayInputStream {
        private final AtomicInteger closes = new AtomicInteger();
        private TrackedStream(byte[] data) { super(data); }
        @Override public void close() { this.closes.incrementAndGet(); }
    }
}
