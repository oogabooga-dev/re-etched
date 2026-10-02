package gg.moonflower.etched.common.audio.provider;

import gg.moonflower.etched.api.record.TrackData;
import gg.moonflower.etched.common.audio.AudioCancellation;
import net.minecraft.network.chat.Component;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.util.List;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

class LegacyTrackMetadataRequestsTest {

    private static final TrackData TRACK = new TrackData("https://audio.example/track", "Artist", Component.literal("Track"));

    @Test
    void overlappingRequestsHaveIndependentFuturesAndArraysAndPreserveAlbumOrder() throws Exception {
        var workers = workers();
        CountDownLatch started = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        TrackData album = new TrackData("https://provider.example/album", "Artist", Component.literal("Album"));
        List<TrackData> tracks = List.of(album, TRACK);
        AtomicInteger calls = new AtomicInteger();
        LegacyTrackMetadataRequests.MetadataLookup lookup = () -> {
            calls.incrementAndGet();
            started.countDown();
            await(release);
            return tracks;
        };
        try {
            var first = LegacyTrackMetadataRequests.submit(lookup, workers);
            assertTrue(started.await(2, TimeUnit.SECONDS));
            var second = LegacyTrackMetadataRequests.submit(lookup, workers);
            assertNotSame(first, second);
            release.countDown();
            TrackData[] one = first.get(2, TimeUnit.SECONDS);
            TrackData[] two = second.get(2, TimeUnit.SECONDS);
            assertNotSame(one, two);
            assertArrayEquals(new TrackData[]{album, TRACK}, one);
            one[1] = TRACK.withArtist("Label artist");
            assertEquals("Artist", two[1].artist());
            assertEquals("Artist", tracks.get(1).artist());
            assertEquals(2, calls.get());
        } finally {
            release.countDown();
            workers.shutdownNow();
        }
    }

    @Test
    void cancellationWakesTheWaiterBeforeTheUninterruptibleProviderReturns() throws Exception {
        var workers = workers();
        CountDownLatch started = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        CountDownLatch returned = new CountDownLatch(1);
        AtomicReference<CompletableFuture<TrackData[]>> pending = new AtomicReference<>();
        AudioCancellation cancellation = new AudioCancellation();
        var waiter = CompletableFuture.supplyAsync(() -> {
            try {
                return LegacyTrackMetadataRequests.await(() -> {
                    var request = LegacyTrackMetadataRequests.submit(() -> {
                        started.countDown();
                        try {
                            await(release);
                            return List.of(TRACK);
                        } finally {
                            returned.countDown();
                        }
                    }, workers);
                    pending.set(request);
                    return request;
                }, cancellation);
            } catch (IOException exception) {
                throw new CompletionException(exception);
            }
        });
        try {
            assertTrue(started.await(2, TimeUnit.SECONDS));
            cancellation.cancel();
            assertInstanceOf(CancellationException.class, assertThrows(ExecutionException.class,
                    () -> waiter.get(2, TimeUnit.SECONDS)).getCause());
            assertTrue(pending.get().isCancelled());
            assertEquals(1, returned.getCount());
            release.countDown();
            assertTrue(returned.await(2, TimeUnit.SECONDS));
            assertTrue(pending.get().isCancelled());
            assertEquals(TRACK, LegacyTrackMetadataRequests.submit(() -> List.of(TRACK), workers).get(2, TimeUnit.SECONDS)[0]);
        } finally {
            release.countDown();
            workers.shutdownNow();
        }
    }

    @Test
    void cancellingQueuedRequestsPreventsProviderCallsAndFreesTheirQueueSlot() throws Exception {
        var workers = workers();
        CountDownLatch started = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        AtomicBoolean called = new AtomicBoolean();
        try {
            var running = LegacyTrackMetadataRequests.submit(() -> {
                started.countDown();
                await(release);
                return List.of(TRACK);
            }, workers);
            assertTrue(started.await(2, TimeUnit.SECONDS));
            var queued = LegacyTrackMetadataRequests.submit(() -> {
                called.set(true);
                return List.of(TRACK);
            }, workers);
            assertEquals(1, workers.getQueue().size());
            assertTrue(queued.cancel(false));
            assertTrue(workers.getQueue().isEmpty());
            var next = LegacyTrackMetadataRequests.submit(() -> List.of(TRACK), workers);
            release.countDown();
            running.get(2, TimeUnit.SECONDS);
            next.get(2, TimeUnit.SECONDS);
            assertFalse(called.get());
        } finally {
            release.countDown();
            workers.shutdownNow();
        }
    }

    @Test
    void overloadReturnsAFailedFutureWithoutRunningTheRejectedProvider() throws Exception {
        var workers = workers();
        CountDownLatch started = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        try {
            var running = LegacyTrackMetadataRequests.submit(() -> {
                started.countDown();
                await(release);
                return List.of(TRACK);
            }, workers);
            assertTrue(started.await(2, TimeUnit.SECONDS));
            var queued = LegacyTrackMetadataRequests.submit(() -> List.of(TRACK), workers);
            var rejected = LegacyTrackMetadataRequests.submit(() -> {
                throw new AssertionError("Rejected provider was invoked");
            }, workers);
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
    void awaitDoesNotStartPreCancelledRequestsOrExposeProviderArrays() throws Exception {
        AudioCancellation cancelled = new AudioCancellation();
        cancelled.cancel();
        assertThrows(CancellationException.class, () -> LegacyTrackMetadataRequests.await(() -> {
            throw new AssertionError("Pre-cancelled operation started a provider request");
        }, cancelled));
        TrackData[] source = {TRACK};
        TrackData[] copy = LegacyTrackMetadataRequests.await(() -> CompletableFuture.completedFuture(source), new AudioCancellation());
        assertNotSame(source, copy);
        copy[0] = TRACK.withArtist("Label artist");
        assertEquals("Artist", source[0].artist());
        AudioCancellation cancelledDuringStart = new AudioCancellation();
        assertThrows(CancellationException.class, () -> LegacyTrackMetadataRequests.await(() -> {
            cancelledDuringStart.cancel();
            return CompletableFuture.completedFuture(source);
        }, cancelledDuringStart));
    }

    @Test
    void failureCausesArePreservedAndDoNotPoisonLaterRequests() throws Exception {
        var workers = workers();
        IOException failure = new IOException("fixture provider failure");
        try {
            var request = LegacyTrackMetadataRequests.submit(() -> { throw failure; }, workers);
            assertSame(failure, assertThrows(CompletionException.class, request::join).getCause());
            var broken = LegacyTrackMetadataRequests.submit(() -> { throw new LinkageError("fixture error"); }, workers);
            assertInstanceOf(LinkageError.class, assertThrows(CompletionException.class, broken::join).getCause());
            assertEquals(TRACK, LegacyTrackMetadataRequests.await(
                    () -> LegacyTrackMetadataRequests.submit(() -> List.of(TRACK), workers), new AudioCancellation())[0]);
        } finally {
            workers.shutdownNow();
        }
    }

    private static ThreadPoolExecutor workers() {
        return new ThreadPoolExecutor(1, 1, 0L, TimeUnit.MILLISECONDS, new ArrayBlockingQueue<>(1));
    }

    private static void await(CountDownLatch latch) throws IOException {
        try {
            if (!latch.await(5, TimeUnit.SECONDS)) {
                throw new IOException("Fixture was not released");
            }
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IOException(exception);
        }
    }
}
