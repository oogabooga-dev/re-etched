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

class TrackMetadataRequestsTest {

    private static final TrackData TRACK = new TrackData("https://audio.example/track", "Artist", Component.literal("Track"));

    @Test
    void overlappingRequestsHaveIndependentFuturesAndArraysAndPreserveAlbumOrder() throws Exception {
        var workers = workers();
        CountDownLatch started = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        TrackData album = new TrackData("https://provider.example/album", "Artist", Component.literal("Album"));
        List<TrackData> tracks = List.of(album, TRACK);
        AtomicInteger calls = new AtomicInteger();
        TrackMetadataRequests.MetadataLookup lookup = () -> {
            calls.incrementAndGet();
            started.countDown();
            await(release);
            return tracks;
        };
        try {
            var first = TrackMetadataRequests.submit(lookup, workers);
            assertTrue(started.await(2, TimeUnit.SECONDS));
            var second = TrackMetadataRequests.submit(lookup, workers);
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
                return TrackMetadataRequests.await(() -> {
                    var request = TrackMetadataRequests.submit(() -> {
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
            assertEquals(TRACK, TrackMetadataRequests.submit(() -> List.of(TRACK), workers).get(2, TimeUnit.SECONDS)[0]);
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
            var running = TrackMetadataRequests.submit(() -> {
                started.countDown();
                await(release);
                return List.of(TRACK);
            }, workers);
            assertTrue(started.await(2, TimeUnit.SECONDS));
            var queued = TrackMetadataRequests.submit(() -> {
                called.set(true);
                return List.of(TRACK);
            }, workers);
            assertEquals(1, workers.getQueue().size());
            assertTrue(queued.cancel(false));
            assertTrue(workers.getQueue().isEmpty());
            var next = TrackMetadataRequests.submit(() -> List.of(TRACK), workers);
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
            var running = TrackMetadataRequests.submit(() -> {
                started.countDown();
                await(release);
                return List.of(TRACK);
            }, workers);
            assertTrue(started.await(2, TimeUnit.SECONDS));
            var queued = TrackMetadataRequests.submit(() -> List.of(TRACK), workers);
            var rejected = TrackMetadataRequests.submit(() -> {
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
        assertThrows(CancellationException.class, () -> TrackMetadataRequests.await(() -> {
            throw new AssertionError("Pre-cancelled operation started a provider request");
        }, cancelled));
        TrackData[] source = {TRACK};
        TrackData[] copy = TrackMetadataRequests.await(() -> CompletableFuture.completedFuture(source), new AudioCancellation());
        assertNotSame(source, copy);
        copy[0] = TRACK.withArtist("Label artist");
        assertEquals("Artist", source[0].artist());
        AudioCancellation cancelledDuringStart = new AudioCancellation();
        assertThrows(CancellationException.class, () -> TrackMetadataRequests.await(() -> {
            cancelledDuringStart.cancel();
            return CompletableFuture.completedFuture(source);
        }, cancelledDuringStart));
    }

    @Test
    void failureCausesArePreservedAndDoNotPoisonLaterRequests() throws Exception {
        var workers = workers();
        IOException failure = new IOException("fixture provider failure");
        try {
            var request = TrackMetadataRequests.submit(() -> { throw failure; }, workers);
            assertSame(failure, assertThrows(CompletionException.class, request::join).getCause());
            var broken = TrackMetadataRequests.submit(() -> { throw new LinkageError("fixture error"); }, workers);
            assertInstanceOf(LinkageError.class, assertThrows(CompletionException.class, broken::join).getCause());
            assertEquals(TRACK, TrackMetadataRequests.await(
                    () -> TrackMetadataRequests.submit(() -> List.of(TRACK), workers), new AudioCancellation())[0]);
        } finally {
            workers.shutdownNow();
        }
    }

    @Test
    void callerScopeCancellationSignalsRunningMetadataAndClosesItsOwnedResponse() throws Exception {
        CountDownLatch reading = new CountDownLatch(1);
        CountDownLatch closed = new CountDownLatch(1);
        CountDownLatch returned = new CountDownLatch(1);
        AtomicInteger closes = new AtomicInteger();
        var scope = new AudioCancellation();
        var pending = TrackMetadataRequests.submitCancellable(cancellation -> {
            assertSame(scope, cancellation);
            java.io.InputStream body = new java.io.InputStream() {
                @Override public int read() throws IOException {
                    reading.countDown();
                    await(closed);
                    cancellation.throwIfCancelled();
                    return -1;
                }
                @Override public void close() { closes.incrementAndGet(); closed.countDown(); }
            };
            try (var response = gg.moonflower.etched.common.audio.net.TestAudioHttpResponse.owned(
                    java.net.URI.create("https://provider.example/metadata"), 200, java.util.Map.of(), body, cancellation)) {
                cancellation.onCancel(response::close);
                response.body().read();
                return List.of(TRACK);
            } finally {
                returned.countDown();
            }
        }, scope);
        try {
            assertTrue(reading.await(2, TimeUnit.SECONDS));
            scope.cancel();
            assertTrue(returned.await(2, TimeUnit.SECONDS));
            assertEquals(1, closes.get());
            assertTrue(pending.isCancelled());
        } finally {
            pending.cancel(false);
            closed.countDown();
        }
    }

    private static ThreadPoolExecutor workers() {
        return new ThreadPoolExecutor(1, 1, 0L, TimeUnit.MILLISECONDS, new ArrayBlockingQueue<>(1));
    }

    @Test
    void builtInRoutesUseTheExplicitProxyAndCallerScopeWithoutOpeningMedia() throws Exception {
        var proxy = new java.net.Proxy(java.net.Proxy.Type.HTTP, new java.net.InetSocketAddress("127.0.0.1", 9999));
        var bandcampInput = java.net.URI.create("https://artist.bandcamp.com/track/example");
        var soundcloudInput = java.net.URI.create("https://soundcloud.com/artist/track");
        for (var input : List.of(bandcampInput, soundcloudInput)) {
            var scope = new AudioCancellation();
            AtomicInteger opened = new AtomicInteger();
            AtomicInteger closed = new AtomicInteger();
            gg.moonflower.etched.common.audio.net.AudioHttpTransport transport = (request, token) -> {
                assertSame(scope, token);
                assertEquals(opened.get(), closed.get());
                opened.incrementAndGet();
                String body;
                if (request.uri().equals(bandcampInput)) {
                    body = "<div data-tralbum='{\"artist\":\"Artist\",\"current\":{\"type\":\"track\",\"title\":\"Track\"}}'></div>";
                } else {
                    body = switch (request.uri().getPath()) {
                        case "/" -> "<script src='/app.js'></script>";
                        case "/app.js" -> "client_id:'fixture'";
                        case "/resolve" -> "{\"kind\":\"track\",\"streamable\":true,\"title\":\"Track\",\"user\":{\"username\":\"Artist\"}}";
                        default -> throw new AssertionError("Metadata opened media: " + request.uri());
                    };
                }
                var stream = new java.io.ByteArrayInputStream(body.getBytes(java.nio.charset.StandardCharsets.UTF_8)) {
                    @Override public void close() { closed.incrementAndGet(); }
                };
                return gg.moonflower.etched.common.audio.net.TestAudioHttpResponse.owned(request.uri(), 200,
                        java.util.Map.of(), stream, token);
            };
            var request = TrackMetadataRequests.resolve(input, proxy, scope, configuredProxy -> {
                assertEquals(bandcampInput, input);
                assertSame(proxy, configuredProxy);
                return new BandcampMetadataResolver(transport, uri -> assertEquals(opened.get(), closed.get()),
                        BandcampMetadataResolver.Limits.DEFAULT);
            }, configuredProxy -> {
                assertEquals(soundcloudInput, input);
                assertSame(proxy, configuredProxy);
                return new SoundCloudMetadataResolver(transport, uri -> assertEquals(opened.get(), closed.get()),
                        java.net.URI.create("https://soundcloud.com/"), java.net.URI.create("https://api-v2.soundcloud.com/resolve"),
                        SoundCloudMetadataResolver.Limits.DEFAULT);
            });
            var tracks = request.get(2, TimeUnit.SECONDS);
            assertEquals(1, tracks.length);
            assertEquals(input.toString(), tracks[0].url());
            assertEquals("Artist", tracks[0].artist());
            assertEquals("Track", tracks[0].title().getString());
            assertEquals(input.equals(bandcampInput) ? 1 : 3, opened.get());
            assertEquals(opened.get(), closed.get());
            assertFalse(scope.isCancelled());
        }
    }

    @Test
    void invalidWorkerMetadataFailsWithoutPublishingPartialOrSharedValues() throws Exception {
        var workers = workers();
        try {
            for (List<TrackData> tracks : List.of(List.<TrackData>of(), List.of(TRACK.withUrl("file:///unsafe")),
                    java.util.Collections.nCopies(102, TRACK))) {
                var pending = TrackMetadataRequests.submit(() -> tracks, workers);
                assertInstanceOf(IOException.class, assertThrows(ExecutionException.class,
                        () -> pending.get(2, TimeUnit.SECONDS)).getCause());
            }
            var title = Component.literal("Original");
            var pending = TrackMetadataRequests.submit(() -> List.of(TRACK.withTitle(title)), workers);
            var result = pending.get(2, TimeUnit.SECONDS);
            title.append(" changed");
            assertEquals("Original", result[0].title().getString());
        } finally {
            workers.shutdownNow();
        }
    }

    @Test
    void recognizesOnlyBuiltInServicePagesAndRejectsUnknownMetadataWithoutNetworking() throws Exception {
        assertTrue(TrackMetadataRequests.supports(java.net.URI.create("https://artist.bandcamp.com/album/example")));
        assertTrue(TrackMetadataRequests.supports(java.net.URI.create("https://soundcloud.com/artist/track")));
        for (String url : List.of("https://audio.example/track.mp3", "https://evilsoundcloud.com/a",
                "https://bandcamp.com.evil.example/a", "https://user@soundcloud.com/a", "ftp://soundcloud.com/a")) {
            var input = java.net.URI.create(url);
            assertFalse(TrackMetadataRequests.supports(input));
            var request = TrackMetadataRequests.resolve(input, java.net.Proxy.NO_PROXY, new AudioCancellation());
            assertInstanceOf(IOException.class, assertThrows(ExecutionException.class,
                    () -> request.get(2, TimeUnit.SECONDS)).getCause());
        }
    }

    @Test
    void preCancelledServiceRequestNeverStartsDiscovery() {
        var scope = new AudioCancellation();
        scope.cancel();
        assertTrue(TrackMetadataRequests.resolve(java.net.URI.create("https://soundcloud.com/artist/track"),
                java.net.Proxy.NO_PROXY, scope).isCancelled());
    }

    @Test
    void callerCancellationRemovesQueuedWorkAndDoesNotRetireDeliveredValues() throws Exception {
        var workers = workers();
        CountDownLatch started = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        try {
            var running = TrackMetadataRequests.submit(() -> {
                started.countDown();
                await(release);
                return List.of(TRACK);
            }, workers);
            assertTrue(started.await(2, TimeUnit.SECONDS));
            var cancelled = new AudioCancellation();
            var queued = TrackMetadataRequests.submitCancellable(token -> {
                throw new AssertionError("Retired metadata ran");
            }, cancelled, workers);
            assertEquals(1, workers.getQueue().size());
            cancelled.cancel();
            assertTrue(queued.isCancelled());
            assertTrue(workers.getQueue().isEmpty());
            var deliveredScope = new AudioCancellation();
            var delivered = TrackMetadataRequests.submitCancellable(token -> List.of(TRACK), deliveredScope, workers);
            release.countDown();
            running.get(2, TimeUnit.SECONDS);
            var values = delivered.get(2, TimeUnit.SECONDS);
            deliveredScope.cancel();
            assertFalse(delivered.isCancelled());
            assertEquals(TRACK, values[0]);
        } finally {
            release.countDown();
            workers.shutdownNow();
        }
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
