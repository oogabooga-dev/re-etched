package gg.moonflower.etched.api.sound;

import com.mojang.blaze3d.audio.OggAudioStream;
import gg.moonflower.etched.api.sound.stream.MonoWrapper;
import gg.moonflower.etched.client.sound.EmptyAudioStream;
import net.minecraft.client.sounds.AudioStream;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import javax.sound.sampled.AudioFormat;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executor;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

@Timeout(10)
class LegacyLocalAudioStreamRequestTest {

    private final ExecutorService workers = Executors.newFixedThreadPool(2, task -> {
        Thread thread = new Thread(task, "Local legacy request fixture");
        thread.setDaemon(true);
        return thread;
    });
    private final AtomicInteger successes = new AtomicInteger();
    private final AtomicInteger failures = new AtomicInteger();

    @AfterEach
    void stopWorkers() throws Exception {
        this.workers.shutdownNow();
        assertTrue(this.workers.awaitTermination(2, TimeUnit.SECONDS));
    }

    @Test
    void cancellationDoesNotCancelLoaderFutureAndClosesLateStreamBeforeReadingItsFormat() {
        var loading = new CompletableFuture<AudioStream>();
        var queue = new QueueExecutor();
        var result = start(loading, queue);
        assertTrue(result.cancel(true));
        assertFalse(loading.isDone());
        var late = new TrackedAudio() {
            @Override public AudioFormat getFormat() { throw new AssertionError("Late stream reached mono conversion"); }
        };
        assertTrue(loading.complete(late));
        assertEquals(1, late.closeCount());
        assertEquals(0, queue.tasks.size());
        assertEquals(0, this.successes.get());
        assertEquals(0, this.failures.get());
    }

    @Test
    void cancellationRetiresQueuedMonoConversionWithoutCallingGetFormat() {
        var loaded = new TrackedAudio() {
            @Override public AudioFormat getFormat() { throw new AssertionError("Cancelled mono conversion ran"); }
        };
        var queue = new QueueExecutor();
        var result = start(CompletableFuture.completedFuture(loaded), queue);
        assertEquals(1, queue.tasks.size());
        result.cancel(false);
        queue.runNext();
        assertEquals(1, loaded.closeCount());
        assertEquals(0, this.successes.get());
        assertEquals(0, this.failures.get());
    }

    @Test
    void cancellationDuringFormatReadDisposesLateWrapperWithoutDoubleClosingItsSource() throws Exception {
        CountDownLatch readingFormat = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        var loaded = new TrackedAudio() {
            @Override public AudioFormat getFormat() {
                readingFormat.countDown();
                await(release);
                return super.getFormat();
            }
        };
        var queue = new QueueExecutor();
        var result = start(CompletableFuture.completedFuture(loaded), queue);
        var modifying = CompletableFuture.runAsync(queue::runNext, this.workers);
        try {
            assertTrue(readingFormat.await(2, TimeUnit.SECONDS));
            result.cancel(false);
            assertEquals(1, loaded.closeCount());
            release.countDown();
            modifying.get(2, TimeUnit.SECONDS);
            assertEquals(1, loaded.closeCount());
            assertEquals(0, queue.tasks.size());
            assertEquals(0, this.successes.get());
            assertEquals(0, this.failures.get());
        } finally {
            release.countDown();
            result.cancel(false);
        }
    }

    @Test
    void cancellationRetiresQueuedPublicationAndClosesMonoSourceExactlyOnce() {
        var loaded = new TrackedAudio();
        var queue = new QueueExecutor();
        var result = start(CompletableFuture.completedFuture(loaded), queue);
        queue.runNext();
        assertFalse(result.isDone());
        assertEquals(1, queue.tasks.size());
        result.cancel(false);
        queue.runNext();
        assertEquals(1, loaded.closeCount());
        assertEquals(0, this.successes.get());
        assertEquals(0, this.failures.get());
    }

    @Test
    void successfulHandoffPreservesMonoPcmAndTransfersOwnershipToConsumer() throws Exception {
        var loaded = new TrackedAudio();
        var queue = new QueueExecutor();
        var result = start(CompletableFuture.completedFuture(loaded), queue);
        queue.runNext();
        queue.runNext();
        var delivered = result.join();
        assertInstanceOf(MonoWrapper.class, delivered);
        assertEquals(1, delivered.getFormat().getChannels());
        assertEquals(16, delivered.getFormat().getSampleSizeInBits());
        ByteBuffer pcm = delivered.read(4);
        byte[] bytes = new byte[pcm.remaining()];
        pcm.get(bytes);
        assertArrayEquals(new byte[]{1, 0, 3, 0}, bytes);
        assertEquals(8, loaded.lastRead.get());
        assertFalse(result.cancel(false));
        assertEquals(0, loaded.closeCount());
        assertEquals(1, this.successes.get());
        assertEquals(0, this.failures.get());
        delivered.close();
        delivered.close();
        assertEquals(1, loaded.closeCount());
        assertThrows(IOException.class, () -> delivered.read(4));
    }

    @Test
    void formatFailureClosesSourceReportsFailureAndCompletesAsEmpty() {
        IllegalStateException failure = new IllegalStateException("fixture format failure");
        var loaded = new TrackedAudio() {
            @Override public AudioFormat getFormat() { throw failure; }
        };
        var queue = new QueueExecutor();
        AtomicReference<Throwable> reported = new AtomicReference<>();
        var result = LegacyLocalAudioStreamRequest.start(CompletableFuture.completedFuture(loaded), queue,
                () -> { throw new AssertionError("Invalid format reported success"); }, reported::set);
        queue.runNext();
        assertEquals(1, loaded.closeCount());
        queue.runNext();
        assertSame(EmptyAudioStream.INSTANCE, result.join());
        assertSame(failure, reported.get());
    }

    @Test
    void loaderFailuresNullResultsAndModifierErrorsDoNotLeaveWaitersPending() {
        IOException loaderFailure = new IOException("fixture loader failure");
        var queue = new QueueExecutor();
        AtomicReference<Throwable> reported = new AtomicReference<>();
        var failed = LegacyLocalAudioStreamRequest.start(CompletableFuture.failedFuture(loaderFailure), queue,
                () -> {}, reported::set);
        queue.runNext();
        assertSame(EmptyAudioStream.INSTANCE, failed.join());
        assertSame(loaderFailure, reported.get());
        var missing = start(CompletableFuture.completedFuture(null), queue);
        queue.runNext();
        assertSame(EmptyAudioStream.INSTANCE, missing.join());
        LinkageError modifierFailure = new LinkageError("fixture modifier failure");
        var loaded = new TrackedAudio();
        var broken = LegacyLocalAudioStreamRequest.start(CompletableFuture.completedFuture(loaded), queue,
                stream -> { throw modifierFailure; }, () -> {}, reported::set);
        queue.runNext();
        queue.runNext();
        assertSame(EmptyAudioStream.INSTANCE, broken.join());
        assertSame(modifierFailure, reported.get());
        assertEquals(1, loaded.closeCount());
        var another = new TrackedAudio();
        var nullModifier = LegacyLocalAudioStreamRequest.start(CompletableFuture.completedFuture(another), queue,
                stream -> null, () -> {}, reported::set);
        queue.runNext();
        queue.runNext();
        assertSame(EmptyAudioStream.INSTANCE, nullModifier.join());
        assertEquals(1, another.closeCount());
    }

    @Test
    void cancelledLateLoaderFailureAndQueuedFailureCallbacksAreSuppressed() {
        var loading = new CompletableFuture<AudioStream>();
        var queue = new QueueExecutor();
        var result = start(loading, queue);
        result.cancel(false);
        loading.completeExceptionally(new IOException("fixture late loader failure"));
        assertEquals(0, queue.tasks.size());
        var failed = start(CompletableFuture.failedFuture(new IOException("fixture queued failure")), queue);
        failed.cancel(false);
        queue.runNext();
        assertEquals(0, this.failures.get());
        assertEquals(0, this.successes.get());
    }

    @Test
    void successCallbackFailureClosesStreamAndPreservesItsExceptionalChannel() {
        var loaded = new TrackedAudio();
        var queue = new QueueExecutor();
        IllegalStateException failure = new IllegalStateException("fixture success callback failure");
        var result = LegacyLocalAudioStreamRequest.start(CompletableFuture.completedFuture(loaded), queue,
                () -> { throw failure; }, error -> { throw new AssertionError("Success callback failure invoked onFail"); });
        queue.runNext();
        queue.runNext();
        assertSame(failure, assertThrows(CompletionException.class, result::join).getCause());
        assertEquals(1, loaded.closeCount());
    }

    @Test
    void failureCallbackExceptionKeepsTheExceptionalChannel() {
        var queue = new QueueExecutor();
        IllegalStateException failure = new IllegalStateException("fixture failure callback error");
        var result = LegacyLocalAudioStreamRequest.start(CompletableFuture.failedFuture(new IOException("fixture failure")),
                queue, () -> {}, error -> { throw failure; });
        queue.runNext();
        assertSame(failure, assertThrows(CompletionException.class, result::join).getCause());
    }

    @Test
    void cancellationDuringRunningSuccessCallbackCannotRetractItButPreventsPublication() throws Exception {
        var loaded = new TrackedAudio();
        CountDownLatch reporting = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        CountDownLatch returned = new CountDownLatch(1);
        var result = LegacyLocalAudioStreamRequest.start(CompletableFuture.completedFuture(loaded), this.workers,
                () -> {
                    this.successes.incrementAndGet();
                    reporting.countDown();
                    await(release);
                    returned.countDown();
                }, error -> this.failures.incrementAndGet());
        try {
            assertTrue(reporting.await(2, TimeUnit.SECONDS));
            result.cancel(false);
            assertEquals(1, loaded.closeCount());
            release.countDown();
            assertTrue(returned.await(2, TimeUnit.SECONDS));
            assertTrue(result.isCancelled());
            assertEquals(1, this.successes.get());
            assertEquals(0, this.failures.get());
        } finally {
            release.countDown();
            result.cancel(false);
        }
    }

    @Test
    void publicationRacingWithCancellationEitherTransfersOrDisposesOneSource() throws Exception {
        for (int i = 0; i < 100; i++) {
            var loaded = new TrackedAudio();
            var queue = new QueueExecutor();
            var result = start(CompletableFuture.completedFuture(loaded), queue);
            queue.runNext();
            var publishing = CompletableFuture.runAsync(queue::runNext, this.workers);
            boolean cancelled = result.cancel(false);
            publishing.get(2, TimeUnit.SECONDS);
            if (!cancelled) {
                assertEquals(0, loaded.closeCount());
                result.join().close();
            }
            assertEquals(1, loaded.closeCount());
        }
        assertEquals(0, this.failures.get());
    }

    @Test
    void rejectingModificationOrPublicationClosesSourceAndReturnsAFailedFuture() {
        for (boolean rejectPublication : new boolean[]{false, true}) {
            var loaded = new TrackedAudio();
            var queue = new QueueExecutor();
            AtomicInteger schedules = new AtomicInteger();
            var rejection = new RejectedExecutionException("fixture overload");
            Executor executor = task -> {
                if (rejectPublication && schedules.getAndIncrement() == 0) {
                    queue.execute(task);
                } else {
                    throw rejection;
                }
            };
            var result = start(CompletableFuture.completedFuture(loaded), executor);
            if (rejectPublication) {
                queue.runNext();
            }
            assertSame(rejection, assertThrows(CompletionException.class, result::join).getCause());
            assertEquals(1, loaded.closeCount());
        }
    }

    @Test
    void cleanupFailurePreservesPrimaryErrorAndDoesNotDoubleCloseSource() {
        IOException closeFailure = new IOException("fixture close failure");
        IllegalStateException failure = new IllegalStateException("fixture format failure");
        var loaded = new TrackedAudio() {
            @Override public AudioFormat getFormat() { throw failure; }
            @Override public void close() throws IOException { super.close(); throw closeFailure; }
        };
        var queue = new QueueExecutor();
        AtomicReference<Throwable> reported = new AtomicReference<>();
        var result = LegacyLocalAudioStreamRequest.start(CompletableFuture.completedFuture(loaded), queue,
                () -> {}, reported::set);
        queue.runNext();
        queue.runNext();
        assertSame(EmptyAudioStream.INSTANCE, result.join());
        assertSame(failure, reported.get());
        assertArrayEquals(new Throwable[]{closeFailure}, failure.getSuppressed());
        assertEquals(1, loaded.closeCount());
    }

    @Test
    void failingWrapperDisposalStillClosesItsSourceAndKeepsCancellation() {
        var loaded = new TrackedAudio();
        var queue = new QueueExecutor();
        AtomicInteger wrapperCloses = new AtomicInteger();
        var result = LegacyLocalAudioStreamRequest.start(CompletableFuture.completedFuture(loaded), queue,
                stream -> new AudioStream() {
                    @Override public AudioFormat getFormat() { return stream.getFormat(); }
                    @Override public ByteBuffer read(int amount) throws IOException { return stream.read(amount); }
                    @Override public void close() throws IOException {
                        wrapperCloses.incrementAndGet();
                        throw new IOException("fixture wrapper disposal failure");
                    }
                }, this.successes::incrementAndGet, error -> this.failures.incrementAndGet());
        queue.runNext();
        result.cancel(false);
        queue.runNext();
        assertEquals(1, wrapperCloses.get());
        assertEquals(1, loaded.closeCount());
        assertTrue(result.isCancelled());
        assertEquals(0, this.successes.get());
        assertEquals(0, this.failures.get());
    }

    @Test
    void independentLocalRequestsDoNotShareCancellationOrStreams() throws Exception {
        var one = new TrackedAudio();
        var two = new TrackedAudio();
        var queue = new QueueExecutor();
        var first = start(CompletableFuture.completedFuture(one), queue);
        var second = start(CompletableFuture.completedFuture(two), queue);
        first.cancel(false);
        queue.runNext();
        queue.runNext();
        queue.runNext();
        assertEquals(1, one.closeCount());
        assertEquals(0, two.closeCount());
        second.join().close();
        assertEquals(1, two.closeCount());
    }

    @Test
    void actualVanillaOggStreamProducesMonoAndClosesItsOwnedInputOnRetirement() throws Exception {
        byte[] bytes;
        try (var fixture = getClass().getResourceAsStream("/gg/moonflower/etched/client/radio/audio/stereo.ogg")) {
            bytes = fixture.readAllBytes();
        }
        for (int mode = 0; mode < 3; mode++) {
            AtomicInteger closes = new AtomicInteger();
            var input = new ByteArrayInputStream(bytes) {
                @Override public void close() throws IOException { closes.incrementAndGet(); super.close(); }
            };
            var queue = new QueueExecutor();
            var loading = new CompletableFuture<AudioStream>();
            var result = start(loading, queue);
            if (mode == 2) {
                result.cancel(false);
            }
            var loaded = new OggAudioStream(input);
            assertTrue(loading.complete(loaded));
            if (mode == 2) {
                assertEquals(0, queue.tasks.size());
            } else if (mode == 1) {
                result.cancel(false);
                queue.runNext();
            } else {
                queue.runNext();
                queue.runNext();
                try (var stream = result.join()) {
                    assertEquals(1, stream.getFormat().getChannels());
                    assertTrue(stream.read(1024).remaining() > 0);
                }
            }
            assertEquals(1, closes.get());
        }
    }

    private CompletableFuture<AudioStream> start(CompletableFuture<AudioStream> loading, Executor executor) {
        return LegacyLocalAudioStreamRequest.start(loading, executor, this.successes::incrementAndGet,
                error -> this.failures.incrementAndGet());
    }

    private static void await(CountDownLatch release) {
        try {
            assertTrue(release.await(5, TimeUnit.SECONDS), "Fixture was not released");
        } catch (InterruptedException error) {
            Thread.currentThread().interrupt();
            throw new AssertionError(error);
        }
    }

    private static final class QueueExecutor implements Executor {
        private final LinkedBlockingQueue<Runnable> tasks = new LinkedBlockingQueue<>();
        @Override public void execute(Runnable task) { this.tasks.add(task); }
        private void runNext() {
            Runnable task = this.tasks.poll();
            assertNotNull(task, "Missing queued task");
            task.run();
        }
    }

    private static class TrackedAudio implements AudioStream {
        private static final AudioFormat FORMAT = new AudioFormat(44100, 16, 2, true, false);
        private final AtomicInteger closes = new AtomicInteger();
        private final AtomicInteger lastRead = new AtomicInteger();
        int closeCount() { return this.closes.get(); }
        @Override public AudioFormat getFormat() { return FORMAT; }
        @Override public ByteBuffer read(int amount) {
            this.lastRead.set(amount);
            return ByteBuffer.wrap(new byte[]{1, 0, 2, 0, 3, 0, 4, 0});
        }
        @Override public void close() throws IOException { this.closes.incrementAndGet(); }
    }
}
