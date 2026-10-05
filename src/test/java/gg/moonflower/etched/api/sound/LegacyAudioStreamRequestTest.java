package gg.moonflower.etched.api.sound;

import gg.moonflower.etched.api.sound.source.AudioSource;
import gg.moonflower.etched.client.cache.LegacyAudioLoader;
import gg.moonflower.etched.client.radio.source.AudioResolveContext;
import gg.moonflower.etched.client.radio.source.AudioResolveLimits;
import gg.moonflower.etched.client.sound.EmptyAudioStream;
import gg.moonflower.etched.common.audio.AudioCancellation;
import gg.moonflower.etched.common.audio.net.TestAudioHttpResponse;
import net.minecraft.client.sounds.AudioStream;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import javax.sound.sampled.AudioFormat;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.nio.ByteBuffer;
import java.util.Map;
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
import java.util.function.Function;

import static org.junit.jupiter.api.Assertions.*;

@Timeout(10)
class LegacyAudioStreamRequestTest {

    private final ExecutorService workers = Executors.newFixedThreadPool(2, task -> {
        Thread thread = new Thread(task, "Legacy request fixture");
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
    void cancellationBeforeSourceArrivalNeverOpensItOrRunsCallbacks() {
        var source = new CompletableFuture<AudioSource>();
        var queue = new QueueExecutor();
        var result = start(source, queue, input -> { throw new AssertionError("Cancelled request decoded"); });
        assertTrue(result.cancel(false));
        assertFalse(source.isCancelled());
        source.complete(() -> { throw new AssertionError("Late source was opened"); });
        assertEquals(0, queue.tasks.size());
        assertEquals(0, this.successes.get());
        assertEquals(0, this.failures.get());
    }

    @Test
    void pendingThirdPartyOpenIsObservedRatherThanCancelledAndItsLateInputIsClosed() {
        var open = new CompletableFuture<InputStream>();
        var queue = new QueueExecutor();
        var result = start(CompletableFuture.completedFuture(() -> open), queue, FakeAudio::new);
        result.cancel(true);
        assertFalse(open.isDone());
        var late = new TrackedInput();
        assertTrue(open.complete(late));
        assertEquals(1, late.closes.get());
        assertEquals(0, queue.tasks.size());
        assertEquals(0, this.successes.get());
        assertEquals(0, this.failures.get());
    }

    @Test
    void cancellationRetiresQueuedDecodeAndClosesInputWithoutRunningTheDecoder() {
        var input = new TrackedInput();
        var queue = new QueueExecutor();
        var result = start(source(input), queue, owned -> { throw new AssertionError("Retired decode ran"); });
        assertEquals(1, queue.tasks.size());
        result.cancel(false);
        assertEquals(1, input.closes.get());
        queue.runNext();
        assertEquals(0, this.successes.get());
        assertEquals(0, this.failures.get());
    }

    @Test
    void cancellationDuringDecodeClosesInputAndDisposesLateDecoderWithoutCallbacks() throws Exception {
        var input = new TrackedInput();
        CountDownLatch decoding = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        AtomicReference<FakeAudio> decoded = new AtomicReference<>();
        var result = start(source(input), this.workers, owned -> {
            var audio = new FakeAudio(owned);
            decoded.set(audio);
            decoding.countDown();
            await(release);
            assertFalse(Thread.currentThread().isInterrupted());
            return audio;
        });
        try {
            assertTrue(decoding.await(2, TimeUnit.SECONDS));
            result.cancel(true);
            assertEquals(1, input.closes.get());
            assertEquals(0, decoded.get().closes.get());
            release.countDown();
            assertTrue(decoded.get().closed.await(2, TimeUnit.SECONDS));
            assertEquals(1, decoded.get().closes.get());
            assertEquals(1, input.closes.get());
            assertEquals(0, this.successes.get());
            assertEquals(0, this.failures.get());
        } finally {
            release.countDown();
        }
    }

    @Test
    void cancellationRetiresQueuedPublicationAndClosesDecoderExactlyOnce() {
        var input = new TrackedInput();
        var queue = new QueueExecutor();
        AtomicReference<FakeAudio> decoded = new AtomicReference<>();
        var result = start(source(input), queue, owned -> {
            var audio = new FakeAudio(owned);
            decoded.set(audio);
            return audio;
        });
        queue.runNext();
        assertFalse(result.isDone());
        assertEquals(1, queue.tasks.size());
        result.cancel(false);
        queue.runNext();
        assertEquals(1, decoded.get().closes.get());
        assertEquals(1, input.closes.get());
        assertEquals(0, this.successes.get());
        assertEquals(0, this.failures.get());
    }

    @Test
    void cancellationDuringSuccessCallbackCannotRetractTheCallbackButPreventsPublication() throws Exception {
        var input = new TrackedInput();
        CountDownLatch reporting = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        CountDownLatch returned = new CountDownLatch(1);
        AtomicReference<FakeAudio> decoded = new AtomicReference<>();
        var result = LegacyAudioStreamRequest.start(source(input), this.workers, owned -> {
            var audio = new FakeAudio(owned);
            decoded.set(audio);
            return audio;
        }, () -> {
            this.successes.incrementAndGet();
            reporting.countDown();
            await(release);
            returned.countDown();
        }, error -> this.failures.incrementAndGet());
        try {
            assertTrue(reporting.await(2, TimeUnit.SECONDS));
            result.cancel(false);
            assertEquals(1, decoded.get().closes.get());
            assertEquals(1, input.closes.get());
            release.countDown();
            assertTrue(returned.await(2, TimeUnit.SECONDS));
            assertTrue(result.isCancelled());
            assertEquals(1, this.successes.get());
            assertEquals(0, this.failures.get());
        } finally {
            release.countDown();
        }
    }

    @Test
    void successfulPublicationTransfersOwnershipAndCannotBeCancelledAfterwards() throws Exception {
        var input = new TrackedInput();
        var queue = new QueueExecutor();
        var result = start(source(input), queue, FakeAudio::new);
        queue.runNext();
        queue.runNext();
        var delivered = result.join();
        assertFalse(result.cancel(false));
        assertEquals(0, input.closes.get());
        assertEquals(1, this.successes.get());
        assertEquals(0, this.failures.get());
        assertEquals(42, delivered.read(1).get() & 0xFF);
        delivered.close();
        assertEquals(1, input.closes.get());
    }

    @Test
    void publicationRacingWithCancellationEitherTransfersOrDisposesOneDecoder() throws Exception {
        for (int i = 0; i < 100; i++) {
            var input = new TrackedInput();
            var queue = new QueueExecutor();
            AtomicReference<FakeAudio> decoded = new AtomicReference<>();
            var result = start(source(input), queue, owned -> {
                var audio = new FakeAudio(owned);
                decoded.set(audio);
                return audio;
            });
            queue.runNext();
            var publishing = CompletableFuture.runAsync(queue::runNext, this.workers);
            boolean cancelled = result.cancel(false);
            publishing.get(2, TimeUnit.SECONDS);
            if (!cancelled) {
                assertSame(decoded.get(), result.join());
                assertEquals(0, input.closes.get());
                result.join().close();
            }
            assertEquals(1, decoded.get().closes.get());
            assertEquals(1, input.closes.get());
        }
        assertEquals(0, this.failures.get());
    }

    @Test
    void sourceAndOpenFailuresStillReportTheirCauseAndCompleteAsEmpty() {
        IOException failure = new IOException("fixture source failure");
        for (int kind = 0; kind < 3; kind++) {
            var queue = new QueueExecutor();
            AtomicReference<Throwable> reported = new AtomicReference<>();
            CompletableFuture<AudioSource> source = switch (kind) {
                case 0 -> CompletableFuture.failedFuture(new CompletionException(failure));
                case 1 -> CompletableFuture.completedFuture(() -> { throw new CompletionException(failure); });
                default -> CompletableFuture.completedFuture(() -> CompletableFuture.failedFuture(failure));
            };
            var result = LegacyAudioStreamRequest.start(source, queue, FakeAudio::new,
                    () -> { throw new AssertionError("Failure reported success"); }, reported::set);
            queue.runNext();
            assertSame(EmptyAudioStream.INSTANCE, result.join());
            assertSame(failure, reported.get());
        }
    }

    @Test
    void sourceFactoryAndNullResourceFailuresCompleteInsteadOfLeavingWaitersPending() {
        var queue = new QueueExecutor();
        LinkageError failure = new LinkageError("fixture factory failure");
        AtomicReference<Throwable> reported = new AtomicReference<>();
        var failed = LegacyAudioStreamRequest.start(cancelled -> { throw failure; }, queue, FakeAudio::new,
                () -> {}, reported::set);
        queue.runNext();
        assertSame(EmptyAudioStream.INSTANCE, failed.join());
        assertSame(failure, reported.get());
        var missing = start(CompletableFuture.completedFuture(() -> CompletableFuture.completedFuture(null)),
                queue, FakeAudio::new);
        queue.runNext();
        assertSame(EmptyAudioStream.INSTANCE, missing.join());
        var input = new TrackedInput();
        var nullDecoder = start(source(input), queue, owned -> null);
        queue.runNext();
        queue.runNext();
        assertSame(EmptyAudioStream.INSTANCE, nullDecoder.join());
        assertEquals(1, input.closes.get());
    }

    @Test
    void asynchronousOpenFailureAfterCancellationDoesNotReportFailure() {
        var open = new CompletableFuture<InputStream>();
        var queue = new QueueExecutor();
        var result = start(CompletableFuture.completedFuture(() -> open), queue, FakeAudio::new);
        result.cancel(false);
        open.completeExceptionally(new IOException("fixture late open failure"));
        assertEquals(0, queue.tasks.size());
        assertEquals(0, this.failures.get());
    }

    @Test
    void decodeFailureClosesInputAndRetainsCleanupErrorOnTheReportedCause() {
        IOException closeFailure = new IOException("fixture close failure");
        TrackedInput input = new TrackedInput() {
            @Override public void close() throws IOException { super.close(); throw closeFailure; }
        };
        IOException failure = new IOException("fixture decode failure");
        var queue = new QueueExecutor();
        AtomicReference<Throwable> reported = new AtomicReference<>();
        var result = LegacyAudioStreamRequest.start(source(input), queue, owned -> { throw new CompletionException(failure); },
                () -> { throw new AssertionError("Decode failure reported success"); }, reported::set);
        queue.runNext();
        assertEquals(1, input.closeCount());
        queue.runNext();
        assertSame(EmptyAudioStream.INSTANCE, result.join());
        assertSame(failure, reported.get());
        assertArrayEquals(new Throwable[]{closeFailure}, failure.getSuppressed());
    }

    @Test
    void cancellationSuppressesQueuedFailureCallback() {
        var queue = new QueueExecutor();
        var result = start(CompletableFuture.failedFuture(new IOException("fixture failure")), queue, FakeAudio::new);
        result.cancel(false);
        queue.runNext();
        assertEquals(0, this.failures.get());
        assertEquals(0, this.successes.get());
    }

    @Test
    void failingDecoderDisposalDuringCancellationStillClosesInputAndSuppressesCallbacks() {
        var input = new TrackedInput();
        var queue = new QueueExecutor();
        AtomicInteger closes = new AtomicInteger();
        var result = start(source(input), queue, owned -> new FakeAudio(owned) {
            @Override public void close() throws IOException {
                closes.incrementAndGet();
                throw new IOException("fixture disposal failure");
            }
        });
        queue.runNext();
        assertTrue(result.cancel(false));
        queue.runNext();
        assertEquals(1, closes.get());
        assertEquals(1, input.closes.get());
        assertEquals(0, this.failures.get());
        assertEquals(0, this.successes.get());
    }

    @Test
    void successAndFailureCallbackExceptionsPreserveTheirExceptionChannel() {
        IllegalStateException failure = new IllegalStateException("fixture callback failure");
        var input = new TrackedInput();
        var queue = new QueueExecutor();
        var result = LegacyAudioStreamRequest.start(source(input), queue, FakeAudio::new,
                () -> { throw failure; }, error -> { throw new AssertionError("Success callback error invoked onFail"); });
        queue.runNext();
        queue.runNext();
        assertSame(failure, assertThrows(CompletionException.class, result::join).getCause());
        assertEquals(1, input.closes.get());
        var failed = LegacyAudioStreamRequest.start(CompletableFuture.failedFuture(new IOException("fixture failure")),
                queue, FakeAudio::new, () -> {}, error -> { throw failure; });
        queue.runNext();
        assertSame(failure, assertThrows(CompletionException.class, failed::join).getCause());
    }

    @Test
    void rejectingDecodeOrPublicationClosesOwnedResourcesAndReturnsAFailedFuture() {
        for (boolean rejectPublication : new boolean[]{false, true}) {
            var input = new TrackedInput();
            var queue = new QueueExecutor();
            AtomicReference<FakeAudio> decoded = new AtomicReference<>();
            AtomicInteger schedules = new AtomicInteger();
            var rejection = new RejectedExecutionException("fixture overload");
            Executor executor = task -> {
                if (rejectPublication && schedules.getAndIncrement() == 0) {
                    queue.execute(task);
                } else {
                    throw rejection;
                }
            };
            var result = start(source(input), executor, owned -> {
                var audio = new FakeAudio(owned);
                decoded.set(audio);
                return audio;
            });
            if (rejectPublication) {
                queue.runNext();
                assertEquals(1, decoded.get().closes.get());
            }
            assertSame(rejection, assertThrows(CompletionException.class, result::join).getCause());
            assertEquals(1, input.closes.get());
        }
        assertEquals(0, this.failures.get());
    }

    @Test
    void independentRequestsDoNotShareCancellationOrStreams() throws Exception {
        var one = new TrackedInput();
        var two = new TrackedInput();
        var queue = new QueueExecutor();
        var first = start(source(one), queue, FakeAudio::new);
        var second = start(source(two), queue, FakeAudio::new);
        first.cancel(false);
        queue.runNext();
        queue.runNext();
        queue.runNext();
        assertTrue(first.isCancelled());
        assertEquals(1, one.closes.get());
        assertEquals(0, two.closes.get());
        second.join().close();
        assertEquals(1, two.closes.get());
    }

    @Test
    void cancellingFinalResultSignalsProviderLookupBeforeItHasProducedASource() throws Exception {
        CountDownLatch started = new CountDownLatch(1);
        CountDownLatch cancelled = new CountDownLatch(1);
        CountDownLatch returned = new CountDownLatch(1);
        AtomicReference<AudioCancellation> scope = new AtomicReference<>();
        var result = LegacyAudioStreamRequest.startCancellable(cancellation -> {
            scope.set(cancellation);
            cancellation.onCancel(cancelled::countDown);
            return gg.moonflower.etched.common.audio.provider.ProviderAudioSourceRequests.submit(() -> {
                started.countDown();
                await(cancelled);
                returned.countDown();
                return () -> { throw new AssertionError("Retired lookup opened audio"); };
            }, cancellation, false);
        }, Runnable::run, input -> { throw new AssertionError("Retired lookup decoded"); },
                this.successes::incrementAndGet, error -> this.failures.incrementAndGet());
        try {
            assertTrue(started.await(2, TimeUnit.SECONDS));
            result.cancel(false);
            assertTrue(scope.get().isCancelled());
            assertTrue(returned.await(2, TimeUnit.SECONDS));
            assertEquals(0, this.successes.get());
            assertEquals(0, this.failures.get());
        } finally {
            result.cancel(false);
            cancelled.countDown();
        }
    }

    @Test
    void deliveredDecoderCloseRetiresProviderScopeWithoutCancellingTheCompletedResult() throws Exception {
        var input = new TrackedInput();
        AtomicReference<AudioCancellation> scope = new AtomicReference<>();
        AtomicInteger retired = new AtomicInteger();
        var result = LegacyAudioStreamRequest.startCancellable(cancellation -> {
            scope.set(cancellation);
            cancellation.onCancel(retired::incrementAndGet);
            return source(input);
        }, Runnable::run, FakeAudio::new, this.successes::incrementAndGet, error -> this.failures.incrementAndGet());
        var delivered = result.join();
        assertFalse(scope.get().isCancelled());
        assertFalse(result.cancel(false));
        assertEquals(42, delivered.read(1).get() & 0xFF);
        delivered.close();
        assertTrue(scope.get().isCancelled());
        assertEquals(1, retired.get());
        assertSame(delivered, result.join());
        assertEquals(1, input.closes.get());
        assertEquals(0, this.failures.get());
    }

    @Test
    void cancellationDuringBodyReadClosesAndCancelsTheOwnedTransportResponse() throws Exception {
        CountDownLatch reading = new CountDownLatch(1);
        CountDownLatch closed = new CountDownLatch(1);
        CountDownLatch returned = new CountDownLatch(1);
        AtomicInteger closes = new AtomicInteger();
        InputStream body = new InputStream() {
            @Override public int read() {
                reading.countDown();
                await(closed);
                return -1;
            }
            @Override public void close() { closes.incrementAndGet(); closed.countDown(); }
        };
        AudioCancellation cancellation = new AudioCancellation();
        URI uri = URI.create("https://audio.example/request-cancellation");
        Function<AudioCancellation, AudioResolveContext> contexts = token -> new AudioResolveContext(
                (request, scope) -> TestAudioHttpResponse.owned(uri, 200, Map.of(), body, scope),
                ignored -> {}, token, AudioResolveLimits.DEFAULT);
        InputStream input = LegacyAudioLoader.stream(uri, cancellation, contexts);
        var result = start(source(input), this.workers, owned -> {
            try {
                owned.read();
                throw new AssertionError("Cancelled body read produced audio");
            } catch (IOException error) {
                throw new CompletionException(error);
            } finally {
                returned.countDown();
            }
        });
        try {
            assertTrue(reading.await(2, TimeUnit.SECONDS));
            result.cancel(false);
            assertTrue(cancellation.isCancelled());
            assertTrue(returned.await(2, TimeUnit.SECONDS));
            assertEquals(1, closes.get());
            assertEquals(0, this.failures.get());
            assertEquals(0, this.successes.get());
        } finally {
            result.cancel(false);
            closed.countDown();
        }
    }

    private CompletableFuture<AudioStream> start(CompletableFuture<AudioSource> source, Executor executor,
                                               Function<InputStream, AudioStream> decoder) {
        return LegacyAudioStreamRequest.start(source, executor, decoder, this.successes::incrementAndGet,
                error -> this.failures.incrementAndGet());
    }

    private static CompletableFuture<AudioSource> source(InputStream input) {
        return CompletableFuture.completedFuture(() -> CompletableFuture.completedFuture(input));
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

    private static class TrackedInput extends ByteArrayInputStream {
        private final AtomicInteger closes = new AtomicInteger();
        private TrackedInput() { super(new byte[]{42}); }
        private int closeCount() { return this.closes.get(); }
        @Override public void close() throws IOException { this.closes.incrementAndGet(); }
    }

    private static class FakeAudio implements AudioStream {
        private static final AudioFormat FORMAT = new AudioFormat(44100, 16, 1, true, false);
        private final InputStream input;
        private final AtomicInteger closes = new AtomicInteger();
        private final CountDownLatch closed = new CountDownLatch(1);
        private FakeAudio(InputStream input) { this.input = input; }
        @Override public AudioFormat getFormat() { return FORMAT; }
        @Override public ByteBuffer read(int amount) throws IOException { return ByteBuffer.wrap(this.input.readNBytes(amount)); }
        @Override public void close() throws IOException {
            this.closes.incrementAndGet();
            this.input.close();
            this.closed.countDown();
        }
    }
}
