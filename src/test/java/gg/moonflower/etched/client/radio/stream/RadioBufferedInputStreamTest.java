package gg.moonflower.etched.client.radio.stream;

import gg.moonflower.etched.client.radio.AudioCancellation;
import gg.moonflower.etched.client.radio.PlaybackSession;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RadioBufferedInputStreamTest {

    private final ExecutorService producer = Executors.newSingleThreadExecutor();
    private final ExecutorService consumers = Executors.newCachedThreadPool();

    @AfterEach
    void tearDown() {
        this.producer.shutdownNow();
        this.consumers.shutdownNow();
    }

    @Test
    void shortEofBelowThresholdCompletesStartupAndPreservesBytes() throws Exception {
        byte[] expected = {1, 2, 3, 4, 5};
        try (RadioBufferedInputStream stream = this.buffer(expected, 16, 3, 12)) {
            assertEquals(RadioBufferedInputStream.Startup.READY, startup(stream));
            assertArrayEquals(expected, stream.readAllBytes());
            assertEquals(RadioBufferedInputStream.State.EOF, stream.state());
            assertEquals(-1, stream.read());
        }
    }

    @Test
    void emptySourceCompletesStartupWithoutHanging() throws Exception {
        try (RadioBufferedInputStream stream = this.buffer(new byte[0], 8, 4, 4)) {
            assertEquals(RadioBufferedInputStream.Startup.EMPTY_EOF, startup(stream));
            assertEquals(-1, stream.read());
        }
    }

    @Test
    void readsAcrossRingWrapAndHonorsNonZeroOffset() throws Exception {
        byte[] expected = new byte[97];
        for (int i = 0; i < expected.length; i++) {
            expected[i] = (byte) (i * 3);
        }

        try (RadioBufferedInputStream stream = this.buffer(expected, 11, 5, 7)) {
            assertEquals(RadioBufferedInputStream.Startup.READY, startup(stream));
            byte[] actual = new byte[expected.length + 8];
            Arrays.fill(actual, (byte) 0x6A);
            int offset = 4;
            int total = 0;
            while (total < expected.length) {
                int read = stream.read(actual, offset + total, expected.length - total);
                if (read < 0) {
                    break;
                }
                total += read;
            }

            assertEquals(expected.length, total);
            assertArrayEquals(expected, Arrays.copyOfRange(actual, offset, offset + total));
            assertArrayEquals(new byte[]{0x6A, 0x6A, 0x6A, 0x6A}, Arrays.copyOf(actual, offset));
            assertArrayEquals(new byte[]{0x6A, 0x6A, 0x6A, 0x6A},
                    Arrays.copyOfRange(actual, offset + total, actual.length));
        }
    }

    @Test
    void singleByteReadsAreUnsignedAndZeroLengthReadIsImmediate() throws Exception {
        try (RadioBufferedInputStream stream = this.buffer(new byte[]{(byte) 0x80, (byte) 0xFF}, 4, 2, 2)) {
            assertEquals(0, stream.read(new byte[1], 0, 0));
            assertEquals(128, stream.read());
            assertEquals(255, stream.read());
            assertEquals(-1, stream.read());
        }
    }

    @Test
    void underrunWaitsForProducerInsteadOfReturningEof() throws Exception {
        PausedInputStream source = new PausedInputStream(new byte[]{1, 2, 3}, new byte[]{4, 5});
        AudioCancellation cancellation = cancellation();
        try (RadioBufferedInputStream stream = new RadioBufferedInputStream(
                source, cancellation, this.producer, 8, 3, 3)) {
            assertEquals(RadioBufferedInputStream.Startup.READY, startup(stream));
            assertArrayEquals(new byte[]{1, 2, 3}, stream.readNBytes(3));

            CompletableFuture<Integer> waitingRead = CompletableFuture.supplyAsync(() -> {
                try {
                    return stream.read();
                } catch (IOException exception) {
                    throw new RuntimeException(exception);
                }
            }, this.consumers);
            assertThrows(TimeoutException.class, () -> waitingRead.get(100, TimeUnit.MILLISECONDS));

            source.release();
            assertEquals(4, waitingRead.get(2, TimeUnit.SECONDS));
            assertEquals(5, stream.read());
            assertEquals(-1, stream.read());
        }
    }

    @Test
    void returnsAvailablePartialDataWithoutFillingCallerRequest() throws Exception {
        PausedInputStream source = new PausedInputStream(new byte[]{1, 2, 3}, new byte[]{4});
        try (RadioBufferedInputStream stream = new RadioBufferedInputStream(
                source, cancellation(), this.producer, 8, 3, 3)) {
            startup(stream);
            byte[] output = new byte[20];
            assertEquals(3, stream.read(output));
            assertArrayEquals(new byte[]{1, 2, 3}, Arrays.copyOf(output, 3));
            source.release();
        }
    }

    @Test
    void producerFailureBeforeStartupIsExposed() throws Exception {
        IOException expected = new IOException("test failure");
        RadioBufferedInputStream stream = new RadioBufferedInputStream(
                new FailingInputStream(new byte[0], expected), cancellation(), this.producer, 8, 4, 4);
        try (stream) {
            ExecutionException startupFailure = assertThrows(ExecutionException.class,
                    () -> stream.startup().toCompletableFuture().get(2, TimeUnit.SECONDS));
            assertEquals(expected, startupFailure.getCause());
            assertEquals(expected, assertThrows(IOException.class, stream::read));
            assertEquals(RadioBufferedInputStream.State.FAILED, stream.state());
        }
    }

    @Test
    void producerFailureAfterStartupDrainsBufferedBytesFirst() throws Exception {
        IOException expected = new IOException("late failure");
        RadioBufferedInputStream stream = new RadioBufferedInputStream(
                new FailingInputStream(new byte[]{9, 8, 7, 6}, expected), cancellation(),
                this.producer, 8, 4, 4);
        try (stream) {
            assertEquals(RadioBufferedInputStream.Startup.READY, startup(stream));
            assertArrayEquals(new byte[]{9, 8, 7, 6}, stream.readNBytes(4));
            assertEquals(expected, assertThrows(IOException.class, stream::read));
        }
    }

    @Test
    void closeWakesBlockedConsumerAndIsIdempotent() throws Exception {
        BlockingInputStream source = new BlockingInputStream();
        RadioBufferedInputStream stream = new RadioBufferedInputStream(
                source, cancellation(), this.producer, 8, 4, 4);
        CompletableFuture<Throwable> read = CompletableFuture.supplyAsync(() -> {
            try {
                stream.read();
                return null;
            } catch (Throwable throwable) {
                return throwable;
            }
        }, this.consumers);

        source.awaitRead();
        stream.close();
        stream.close();

        assertInstanceOf(IOException.class, read.get(2, TimeUnit.SECONDS));
        await(() -> source.closeCount.get() == 1);
        assertEquals(RadioBufferedInputStream.State.CANCELLED, stream.state());
    }

    @Test
    void cancellationDiscardsBufferedDataAndClosesSource() throws Exception {
        PlaybackSession session = new PlaybackSession();
        PlaybackSession.Attempt attempt = session.start("https://radio.example/live");
        CloseCountingInputStream source = new CloseCountingInputStream(new byte[32]);
        RadioBufferedInputStream stream = new RadioBufferedInputStream(
                source, attempt.cancellation(), this.producer, 16, 4, 8);
        startup(stream);

        session.stop();

        assertEquals(RadioBufferedInputStream.State.CANCELLED, stream.state());
        assertEquals(0, stream.bufferedBytes());
        assertThrows(java.util.concurrent.CancellationException.class, stream::read);
        await(() -> source.closeCount.get() == 1);
    }

    @Test
    void alreadyCancelledAttemptNeverReadsSource() throws Exception {
        PlaybackSession session = new PlaybackSession();
        PlaybackSession.Attempt attempt = session.start("https://radio.example/live");
        session.stop();
        AtomicInteger reads = new AtomicInteger();
        InputStream source = new ByteArrayInputStream(new byte[]{1}) {
            @Override
            public synchronized int read(byte[] output, int offset, int length) {
                reads.incrementAndGet();
                return super.read(output, offset, length);
            }
        };

        try (RadioBufferedInputStream stream = new RadioBufferedInputStream(
                source, attempt.cancellation(), this.producer, 8, 4, 4)) {
            assertThrows(ExecutionException.class,
                    () -> stream.startup().toCompletableFuture().get(2, TimeUnit.SECONDS));
            assertEquals(0, reads.get());
            assertEquals(RadioBufferedInputStream.State.CANCELLED, stream.state());
        }
    }

    @Test
    void producerNeverBuffersPastConfiguredCapacity() throws Exception {
        byte[] source = new byte[128];
        RadioBufferedInputStream stream = this.buffer(source, 13, 7, 13);
        try (stream) {
            startup(stream);
            Thread.sleep(50);
            assertEquals(13, stream.bufferedBytes());
            assertTrue(stream.bufferedBytes() <= 13);
        }
    }

    @Test
    void rejectedSubmissionClosesTransferredSource() throws Exception {
        ExecutorService rejecting = Executors.newSingleThreadExecutor();
        rejecting.shutdownNow();
        CloseCountingInputStream source = new CloseCountingInputStream(new byte[1]);

        assertThrows(RejectedExecutionException.class, () -> new RadioBufferedInputStream(
                source, cancellation(), rejecting, 8, 4, 4));
        await(() -> source.closeCount.get() == 1);
    }

    @Test
    void closeRemovesQueuedProducerTask() throws Exception {
        ThreadPoolExecutor executor = new ThreadPoolExecutor(1, 1, 0L, TimeUnit.MILLISECONDS,
                new ArrayBlockingQueue<>(1));
        CountDownLatch occupied = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        executor.execute(() -> {
            occupied.countDown();
            try {
                release.await();
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
            }
        });
        assertTrue(occupied.await(2, TimeUnit.SECONDS));
        CloseCountingInputStream source = new CloseCountingInputStream(new byte[8]);
        RadioBufferedInputStream stream = new RadioBufferedInputStream(
                source, cancellation(), executor, 8, 4, 4);
        try {
            assertEquals(1, executor.getQueue().size());

            stream.close();

            assertTrue(executor.getQueue().isEmpty());
            await(() -> source.closeCount.get() == 1);
        } finally {
            release.countDown();
            executor.shutdownNow();
        }
    }

    @Test
    void validatesLimits() {
        List<int[]> invalid = List.of(
                new int[]{0, 1, 1},
                new int[]{8, 0, 1},
                new int[]{8, 9, 1},
                new int[]{8, 4, 0},
                new int[]{8, 4, 9});
        for (int[] values : invalid) {
            assertThrows(IllegalArgumentException.class, () -> new RadioBufferedInputStream(
                    new ByteArrayInputStream(new byte[0]), cancellation(), this.producer,
                    values[0], values[1], values[2]));
        }
    }

    private RadioBufferedInputStream buffer(byte[] data, int capacity, int chunk, int threshold) {
        return new RadioBufferedInputStream(new ByteArrayInputStream(data), cancellation(),
                this.producer, capacity, chunk, threshold);
    }

    private static AudioCancellation cancellation() {
        return new PlaybackSession().start("https://radio.example/live").cancellation();
    }

    private static RadioBufferedInputStream.Startup startup(RadioBufferedInputStream stream) throws Exception {
        return stream.startup().toCompletableFuture().get(2, TimeUnit.SECONDS);
    }

    private static void await(java.util.function.BooleanSupplier condition) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (!condition.getAsBoolean()) {
            if (System.nanoTime() >= deadline) {
                throw new AssertionError("Timed out waiting for asynchronous radio cleanup");
            }
            Thread.sleep(10L);
        }
    }

    private static final class PausedInputStream extends InputStream {

        private final byte[] first;
        private final byte[] second;
        private final CompletableFuture<Void> released = new CompletableFuture<>();
        private int position;

        private PausedInputStream(byte[] first, byte[] second) {
            this.first = first;
            this.second = second;
        }

        @Override
        public int read(byte[] output, int offset, int length) throws IOException {
            if (this.position < this.first.length) {
                int read = Math.min(length, this.first.length - this.position);
                System.arraycopy(this.first, this.position, output, offset, read);
                this.position += read;
                return read;
            }
            try {
                this.released.get();
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
                throw new IOException(exception);
            } catch (ExecutionException exception) {
                throw new IOException(exception.getCause());
            }
            int secondPosition = this.position - this.first.length;
            if (secondPosition >= this.second.length) {
                return -1;
            }
            int read = Math.min(length, this.second.length - secondPosition);
            System.arraycopy(this.second, secondPosition, output, offset, read);
            this.position += read;
            return read;
        }

        @Override
        public int read() throws IOException {
            byte[] value = new byte[1];
            int read = this.read(value, 0, 1);
            return read < 0 ? -1 : Byte.toUnsignedInt(value[0]);
        }

        private void release() {
            this.released.complete(null);
        }
    }

    private static final class FailingInputStream extends InputStream {

        private final byte[] data;
        private final IOException failure;
        private int position;

        private FailingInputStream(byte[] data, IOException failure) {
            this.data = data;
            this.failure = failure;
        }

        @Override
        public int read(byte[] output, int offset, int length) throws IOException {
            if (this.position >= this.data.length) {
                throw this.failure;
            }
            int read = Math.min(length, this.data.length - this.position);
            System.arraycopy(this.data, this.position, output, offset, read);
            this.position += read;
            return read;
        }

        @Override
        public int read() throws IOException {
            if (this.position >= this.data.length) {
                throw this.failure;
            }
            return Byte.toUnsignedInt(this.data[this.position++]);
        }
    }

    private static class CloseCountingInputStream extends ByteArrayInputStream {

        protected final AtomicInteger closeCount = new AtomicInteger();

        private CloseCountingInputStream(byte[] data) {
            super(data);
        }

        @Override
        public void close() throws IOException {
            this.closeCount.incrementAndGet();
            super.close();
        }
    }

    private static final class BlockingInputStream extends InputStream {

        private final CompletableFuture<Void> reading = new CompletableFuture<>();
        private final AtomicBoolean closed = new AtomicBoolean();
        private final AtomicInteger closeCount = new AtomicInteger();

        @Override
        public int read() throws IOException {
            this.reading.complete(null);
            while (!this.closed.get()) {
                try {
                    Thread.sleep(10);
                } catch (InterruptedException ignored) {
                }
            }
            throw new IOException("closed");
        }

        @Override
        public void close() {
            this.closeCount.incrementAndGet();
            this.closed.set(true);
        }

        private void awaitRead() throws Exception {
            this.reading.get(2, TimeUnit.SECONDS);
        }
    }
}
