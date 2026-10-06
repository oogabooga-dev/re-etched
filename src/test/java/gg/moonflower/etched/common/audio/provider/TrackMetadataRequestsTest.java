package gg.moonflower.etched.common.audio.provider;

import gg.moonflower.etched.common.audio.AudioCancellation;
import gg.moonflower.etched.common.audio.AudioProgram;
import gg.moonflower.etched.common.audio.AudioTrack;
import gg.moonflower.etched.common.audio.RecordContent;
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

    private static final AudioTrack TRACK = new AudioTrack(AudioTrack.SourceType.REMOTE, "https://audio.example/track", "Artist", "Track");
    private static final RecordContent CONTENT = new RecordContent(new AudioProgram(AudioProgram.Kind.FINITE, List.of(TRACK)));

    @Test
    void overlappingRequestsHaveIndependentFuturesAndImmutableExplicitAlbumResults() throws Exception {
        var workers = workers();
        CountDownLatch started = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        var album = new RecordContent.AlbumMetadata(AudioTrack.SourceType.REMOTE, "https://provider.example/album", "Artist", "Album");
        var content = new RecordContent(CONTENT.program(), java.util.Optional.of(album));
        AtomicInteger calls = new AtomicInteger();
        TrackMetadataRequests.MetadataLookup lookup = () -> {
            calls.incrementAndGet();
            started.countDown();
            await(release);
            return content;
        };
        try {
            var first = TrackMetadataRequests.submit(lookup, workers);
            assertTrue(started.await(2, TimeUnit.SECONDS));
            var second = TrackMetadataRequests.submit(lookup, workers);
            assertNotSame(first, second);
            release.countDown();
            RecordContent one = first.get(2, TimeUnit.SECONDS);
            RecordContent two = second.get(2, TimeUnit.SECONDS);
            assertEquals(content, one);
            assertEquals(album, two.album().orElseThrow());
            assertEquals(List.of(TRACK), two.program().tracks());
            assertThrows(UnsupportedOperationException.class, () -> one.program().tracks().clear());
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
        AtomicReference<CompletableFuture<RecordContent>> pending = new AtomicReference<>();
        AudioCancellation cancellation = new AudioCancellation();
        var waiter = CompletableFuture.supplyAsync(() -> {
            try {
                return TrackMetadataRequests.await(() -> {
                    var request = TrackMetadataRequests.submit(() -> {
                        started.countDown();
                        try {
                            await(release);
                            return CONTENT;
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
            assertEquals(CONTENT, TrackMetadataRequests.submit(() -> CONTENT, workers).get(2, TimeUnit.SECONDS));
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
                return CONTENT;
            }, workers);
            assertTrue(started.await(2, TimeUnit.SECONDS));
            var queued = TrackMetadataRequests.submit(() -> {
                called.set(true);
                return CONTENT;
            }, workers);
            assertEquals(1, workers.getQueue().size());
            assertTrue(queued.cancel(false));
            assertTrue(workers.getQueue().isEmpty());
            var next = TrackMetadataRequests.submit(() -> CONTENT, workers);
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
                return CONTENT;
            }, workers);
            assertTrue(started.await(2, TimeUnit.SECONDS));
            var queued = TrackMetadataRequests.submit(() -> CONTENT, workers);
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
    void awaitDoesNotStartPreCancelledRequestsAndReturnsImmutableValues() throws Exception {
        AudioCancellation cancelled = new AudioCancellation();
        cancelled.cancel();
        assertThrows(CancellationException.class, () -> TrackMetadataRequests.await(() -> {
            throw new AssertionError("Pre-cancelled operation started a provider request");
        }, cancelled));
        var value = TrackMetadataRequests.await(() -> CompletableFuture.completedFuture(CONTENT), new AudioCancellation());
        assertEquals(CONTENT, value);
        assertThrows(UnsupportedOperationException.class, () -> value.program().tracks().clear());
        AudioCancellation cancelledDuringStart = new AudioCancellation();
        assertThrows(CancellationException.class, () -> TrackMetadataRequests.await(() -> {
            cancelledDuringStart.cancel();
            return CompletableFuture.completedFuture(CONTENT);
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
            assertEquals(CONTENT, TrackMetadataRequests.await(
                    () -> TrackMetadataRequests.submit(() -> CONTENT, workers), new AudioCancellation()));
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
                return CONTENT;
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
            var content = request.get(2, TimeUnit.SECONDS);
            assertTrue(content.album().isEmpty());
            var tracks = content.program().tracks();
            assertEquals(1, tracks.size());
            assertEquals(input.toString(), tracks.get(0).source());
            assertEquals("Artist", tracks.get(0).artist());
            assertEquals("Track", tracks.get(0).title());
            assertEquals(input.equals(bandcampInput) ? 1 : 3, opened.get());
            assertEquals(opened.get(), closed.get());
            assertFalse(scope.isCancelled());
        }
    }

    @Test
    void missingWorkerMetadataFailsAndDeliveredProgramsDoNotExposeMutableInputs() throws Exception {
        var workers = workers();
        try {
            var missing = TrackMetadataRequests.submit(() -> null, workers);
            assertInstanceOf(IOException.class, assertThrows(ExecutionException.class,
                    () -> missing.get(2, TimeUnit.SECONDS)).getCause());
            assertThrows(IOException.class, () -> TrackMetadataRequests.await(
                    () -> CompletableFuture.completedFuture(null), new AudioCancellation()));
            var mutable = new java.util.ArrayList<>(List.of(TRACK));
            var content = new RecordContent(new AudioProgram(AudioProgram.Kind.FINITE, mutable));
            var pending = TrackMetadataRequests.submit(() -> content, workers);
            mutable.clear();
            assertEquals(CONTENT, pending.get(2, TimeUnit.SECONDS));
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
                return CONTENT;
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
            var delivered = TrackMetadataRequests.submitCancellable(token -> CONTENT, deliveredScope, workers);
            release.countDown();
            running.get(2, TimeUnit.SECONDS);
            var values = delivered.get(2, TimeUnit.SECONDS);
            deliveredScope.cancel();
            assertFalse(delivered.isCancelled());
            assertEquals(CONTENT, values);
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
