package gg.moonflower.etched.api.util;

import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.URL;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

class StreamingInputStreamTest {

    @Test
    void orderedPartsAreClosedAtEofAndOnlyThreeArePrefetched() throws Exception {
        List<TrackedStream> parts = new ArrayList<>();
        AtomicInteger requests = new AtomicInteger();
        try (var stream = new StreamingInputStream(urls(6), index -> {
            requests.incrementAndGet();
            var part = new TrackedStream(new byte[]{(byte) index});
            parts.add(part);
            return CompletableFuture.completedFuture(part);
        })) {
            assertEquals(3, requests.get());
            assertEquals(0, stream.read());
            assertEquals(3, requests.get());
            assertEquals(1, stream.read());
            assertEquals(4, requests.get());
            assertEquals(1, parts.get(0).closes.get());
            assertArrayEquals(new byte[]{2, 3, 4, 5}, stream.readAllBytes());
            assertEquals(-1, stream.read());
            assertEquals(6, requests.get());
            assertTrue(parts.stream().allMatch(part -> part.closes.get() == 1));
        }
        assertTrue(parts.stream().allMatch(part -> part.closes.get() == 1));
    }

    @Test
    void closeWakesPendingReaderAndDisposesAllLateStreamsWithoutCancellingTheirFutures() throws Exception {
        List<CompletableFuture<InputStream>> pending = List.of(
                new CompletableFuture<>(), new CompletableFuture<>(), new CompletableFuture<>());
        AtomicInteger requests = new AtomicInteger();
        var stream = new StreamingInputStream(urls(10), index -> {
            requests.incrementAndGet();
            return pending.get(index);
        });
        CountDownLatch reading = new CountDownLatch(1);
        var reader = CompletableFuture.supplyAsync(() -> {
            reading.countDown();
            return assertThrows(IOException.class, stream::read);
        });
        try {
            assertTrue(reading.await(2, TimeUnit.SECONDS));
            stream.close();
            assertEquals("Stream is closed", reader.get(2, TimeUnit.SECONDS).getMessage());
            stream.close();
            for (var future : pending) {
                assertFalse(future.isDone());
                var late = new TrackedStream(new byte[]{42});
                assertTrue(future.complete(late));
                assertEquals(1, late.closes.get());
            }
            assertEquals(3, requests.get());
            assertThrows(IOException.class, stream::read);
            assertThrows(IOException.class, () -> stream.read(new byte[1]));
            assertThrows(IOException.class, () -> stream.skip(1));
        } finally {
            stream.close();
        }
    }

    @Test
    void failedPartIsNotSkippedAndOtherOpenedOrLatePartsAreClosed() throws Exception {
        IOException failure = new IOException("fixture open failure");
        var first = new TrackedStream(new byte[]{1});
        var late = new CompletableFuture<InputStream>();
        var stream = new StreamingInputStream(urls(4), index -> switch (index) {
            case 0 -> CompletableFuture.completedFuture(first);
            case 1 -> CompletableFuture.failedFuture(failure);
            case 2 -> late;
            default -> CompletableFuture.completedFuture(new TrackedStream(new byte[]{4}));
        });
        assertEquals(1, stream.read());
        assertSame(failure, assertThrows(IOException.class, stream::read));
        assertEquals(1, first.closes.get());
        var last = new TrackedStream(new byte[]{3});
        late.complete(last);
        assertEquals(1, last.closes.get());
        assertThrows(IOException.class, stream::read);
        stream.close();
    }

    @Test
    void delegateReadFailureRetiresEveryPrefetchedPart() throws Exception {
        IOException failure = new IOException("fixture read failure");
        AtomicInteger closed = new AtomicInteger();
        InputStream broken = new InputStream() {
            @Override public int read() throws IOException { throw failure; }
            @Override public void close() { closed.incrementAndGet(); }
        };
        var next = new TrackedStream(new byte[]{2});
        var stream = new StreamingInputStream(urls(2), index -> CompletableFuture.completedFuture(index == 0 ? broken : next));
        assertSame(failure, assertThrows(IOException.class, stream::read));
        assertEquals(1, closed.get());
        assertEquals(1, next.closes.get());
        stream.close();
    }

    @Test
    void closeAlsoClosesAnOpenedDelegateThatIsBlockingInRead() throws Exception {
        CountDownLatch reading = new CountDownLatch(1);
        CountDownLatch closed = new CountDownLatch(1);
        InputStream blocking = new InputStream() {
            @Override public int read() throws IOException {
                reading.countDown();
                try {
                    if (!closed.await(2, TimeUnit.SECONDS)) {
                        throw new AssertionError("Delegate was not closed");
                    }
                } catch (InterruptedException failure) {
                    Thread.currentThread().interrupt();
                    throw new IOException(failure);
                }
                throw new IOException("Delegate closed");
            }
            @Override public void close() { closed.countDown(); }
        };
        var stream = new StreamingInputStream(urls(1), index -> CompletableFuture.completedFuture(blocking));
        var reader = CompletableFuture.supplyAsync(() -> assertThrows(IOException.class, stream::read));
        try {
            assertTrue(reading.await(2, TimeUnit.SECONDS));
            stream.close();
            assertEquals("Delegate closed", reader.get(2, TimeUnit.SECONDS).getMessage());
        } finally {
            stream.close();
        }
    }

    @Test
    void oneDelegateCloseFailureDoesNotLeakOtherPrefetchedParts() throws Exception {
        AtomicInteger closes = new AtomicInteger();
        InputStream brokenClose = new InputStream() {
            @Override public int read() { return -1; }
            @Override public void close() throws IOException {
                closes.incrementAndGet();
                throw new IOException("fixture close failure");
            }
        };
        var next = new TrackedStream(new byte[]{2});
        var stream = new StreamingInputStream(urls(2), index -> CompletableFuture.completedFuture(index == 0 ? brokenClose : next));
        assertDoesNotThrow(stream::close);
        assertEquals(1, closes.get());
        assertEquals(1, next.closes.get());
        stream.close();
        assertEquals(1, closes.get());
    }

    @Test
    void futureWaiterIsRetiredBeforeAnotherDelegatesBlockingCloseFinishes() throws Exception {
        var pending = new CompletableFuture<InputStream>();
        CountDownLatch closing = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        InputStream slowClose = new InputStream() {
            @Override public int read() { return -1; }
            @Override public void close() throws IOException {
                closing.countDown();
                try {
                    if (!release.await(5, TimeUnit.SECONDS)) {
                        throw new IOException("Fixture close was not released");
                    }
                } catch (InterruptedException failure) {
                    Thread.currentThread().interrupt();
                    throw new IOException(failure);
                }
            }
        };
        var stream = new StreamingInputStream(urls(2), index -> index == 0 ? pending : CompletableFuture.completedFuture(slowClose));
        var reader = CompletableFuture.supplyAsync(() -> assertThrows(IOException.class, stream::read));
        var closer = CompletableFuture.runAsync(stream::close);
        try {
            assertTrue(closing.await(2, TimeUnit.SECONDS));
            reader.get(2, TimeUnit.SECONDS);
            assertFalse(closer.isDone());
        } finally {
            release.countDown();
            closer.get(2, TimeUnit.SECONDS);
            pending.complete(InputStream.nullInputStream());
            stream.close();
        }
    }

    @Test
    void cancelledNullOrThrowingSourcesFailInsteadOfBecomingEmptyAudio() throws Exception {
        var cancelled = new CompletableFuture<InputStream>();
        cancelled.cancel(false);
        try (var stream = new StreamingInputStream(urls(1), index -> cancelled)) {
            assertInstanceOf(java.util.concurrent.CancellationException.class,
                    assertThrows(IOException.class, stream::read).getCause());
        }
        try (var stream = new StreamingInputStream(urls(1), index -> CompletableFuture.completedFuture(null))) {
            assertEquals("Audio part returned no stream", assertThrows(IOException.class, stream::read).getMessage());
        }
        IllegalStateException failure = new IllegalStateException("fixture source failure");
        try (var stream = new StreamingInputStream(urls(1), index -> { throw failure; })) {
            assertSame(failure, assertThrows(IOException.class, stream::read).getCause());
        }
        try (var stream = new StreamingInputStream(urls(1), index -> null)) {
            assertInstanceOf(NullPointerException.class, assertThrows(IOException.class, stream::read).getCause());
        }
        IOException ioFailure = new IOException("fixture asynchronous I/O failure");
        try (var stream = new StreamingInputStream(urls(1), index -> CompletableFuture.failedFuture(new CompletionException(ioFailure)))) {
            assertSame(ioFailure, assertThrows(IOException.class, stream::read));
        }
    }

    @Test
    void interruptionIsPreservedAndRetiresLateResults() throws Exception {
        var pending = new CompletableFuture<InputStream>();
        var stream = new StreamingInputStream(urls(1), index -> pending);
        var outcome = new CompletableFuture<Boolean>();
        Thread reader = new Thread(() -> {
            try {
                Thread.currentThread().interrupt();
                assertInstanceOf(InterruptedException.class, assertThrows(IOException.class, stream::read).getCause());
                outcome.complete(Thread.currentThread().isInterrupted());
            } catch (Throwable failure) {
                outcome.completeExceptionally(failure);
            }
        });
        reader.start();
        try {
            assertTrue(outcome.get(2, TimeUnit.SECONDS));
            var late = new TrackedStream(new byte[]{1});
            pending.complete(late);
            assertEquals(1, late.closes.get());
        } finally {
            stream.close();
            reader.join(2000);
        }
    }

    @Test
    void completionRacingWithCloseClosesEveryStreamExactlyOnce() throws Exception {
        for (int i = 0; i < 200; i++) {
            var pending = new CompletableFuture<InputStream>();
            var stream = new StreamingInputStream(urls(1), index -> pending);
            var part = new TrackedStream(new byte[]{1});
            var completing = CompletableFuture.runAsync(() -> pending.complete(part));
            stream.close();
            completing.get(2, TimeUnit.SECONDS);
            stream.close();
            assertEquals(1, part.closes.get());
        }
    }

    @Test
    void emptyPartsDoNotRecurseAndZeroLengthReadsDoNotAdvanceTheQueue() throws Exception {
        AtomicInteger requests = new AtomicInteger();
        try (var stream = new StreamingInputStream(urls(20_000), index -> {
            requests.incrementAndGet();
            return CompletableFuture.completedFuture(InputStream.nullInputStream());
        })) {
            assertEquals(0, stream.read(new byte[0]));
            assertEquals(3, requests.get());
            assertEquals(-1, stream.read());
            assertEquals(20_000, requests.get());
            assertEquals(0, stream.read(new byte[0]));
            assertThrows(IndexOutOfBoundsException.class, () -> stream.read(new byte[1], 1, 1));
        }
        try (var stream = new StreamingInputStream(urls(0), index -> { throw new AssertionError("Empty input opened a part"); })) {
            assertEquals(-1, stream.read());
            assertEquals(-1, stream.read(new byte[1]));
            assertEquals(0, stream.skip(1));
        }
        try (var stream = new StreamingInputStream(urls(20_000), index -> CompletableFuture.completedFuture(InputStream.nullInputStream()))) {
            assertEquals(-1, stream.read(new byte[1]));
        }
    }

    @Test
    void skipAccumulatesAcrossPartsWithoutTreatingShortSkipAsEof() throws Exception {
        TrackedStream first = new TrackedStream(new byte[]{1, 2, 3}) {
            @Override public long skip(long n) { return 0; }
        };
        var second = new TrackedStream(new byte[]{4, 5, 6});
        try (var stream = new StreamingInputStream(urls(2), index -> CompletableFuture.completedFuture(index == 0 ? first : second))) {
            assertEquals(0, stream.skip(-1));
            assertEquals(0, stream.skip(0));
            assertEquals(4, stream.skip(4));
            assertEquals(1, first.closes.get());
            assertEquals(5, stream.read());
            assertEquals(1, stream.skip(100));
            assertEquals(1, second.closes.get());
            assertEquals(-1, stream.read());
        }
    }

    private static URL[] urls(int count) throws Exception {
        URL[] urls = new URL[count];
        java.util.Arrays.fill(urls, new URL("https://audio-fixture.example/part"));
        return urls;
    }

    private static class TrackedStream extends ByteArrayInputStream {
        private final AtomicInteger closes = new AtomicInteger();

        private TrackedStream(byte[] bytes) { super(bytes); }

        @Override public void close() { this.closes.incrementAndGet(); }
    }
}
