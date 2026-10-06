package gg.moonflower.etched.client;

import com.mojang.blaze3d.platform.NativeImage;
import gg.moonflower.etched.client.render.item.CoverDescriptor;
import gg.moonflower.etched.client.cache.BoundedMediaCache;
import gg.moonflower.etched.client.cache.CoverCacheLoader;
import gg.moonflower.etched.client.cache.MediaValidators;
import gg.moonflower.etched.client.cache.ProviderCoverCacheLoader;
import gg.moonflower.etched.client.render.item.ImageAlbumCover;
import gg.moonflower.etched.client.radio.source.AudioResolveContext;
import gg.moonflower.etched.client.radio.source.AudioResolveLimits;
import gg.moonflower.etched.common.audio.AudioCancellation;
import gg.moonflower.etched.common.audio.net.TestAudioHttpResponse;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.io.InputStream;
import java.net.Proxy;
import java.net.URI;
import java.nio.file.Path;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

class CoverImageRequestsTest {

    @TempDir Path temporary;

    @Test
    void cancellingReturnedFutureDuringBuiltInMetadataReadClosesResponseAndNeverCreatesAnImage() throws Exception {
        for (String source : new String[]{"https://artist.bandcamp.com/album/test", "https://soundcloud.com/artist/track"}) {
            var cache = new BoundedMediaCache(temporary.resolve(URI.create(source).getHost()));
            CountDownLatch reading = new CountDownLatch(1);
            CountDownLatch release = new CountDownLatch(1);
            CountDownLatch closed = new CountDownLatch(1);
            AtomicReference<AudioCancellation> metadataToken = new AtomicReference<>();
            var body = new InputStream() {
                @Override public int read() throws IOException {
                    reading.countDown();
                    try {
                        if (!release.await(3, TimeUnit.SECONDS)) {
                            throw new IOException("Fixture was not released");
                        }
                        return -1;
                    } catch (InterruptedException exception) {
                        Thread.currentThread().interrupt();
                        throw new IOException(exception);
                    }
                }

                @Override public void close() {
                    closed.countDown();
                    release.countDown();
                }
            };
            var pending = CoverImageRequests.request(cancellation -> ProviderCoverCacheLoader.open(cache,
                    URI.create(source), cancellation, token -> {
                        assertSame(cancellation, token);
                        return new AudioResolveContext((request, responseToken) -> {
                            assertSame(cancellation, responseToken);
                            assertNull(metadataToken.getAndSet(responseToken));
                            return TestAudioHttpResponse.owned(request.uri(), 200, Map.of(), body, responseToken);
                        }, uri -> {}, token, AudioResolveLimits.DEFAULT);
                    }), lease -> { throw new AssertionError("Retired metadata created a native image"); });
            try {
                assertTrue(reading.await(2, TimeUnit.SECONDS));
                assertTrue(pending.cancel(false));
                assertTrue(metadataToken.get().isCancelled());
                assertTrue(closed.await(2, TimeUnit.SECONDS));
                assertTrue(pending.isCancelled());
            } finally {
                release.countDown();
                pending.cancel(false);
            }
        }
    }

    @Test
    void cancellingTheReturnedFutureDuringImageReadClosesItsResponse() throws Exception {
        CountDownLatch reading = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        CountDownLatch closed = new CountDownLatch(1);
        var cache = new BoundedMediaCache(temporary.resolve("v5"));
        var body = new InputStream() {
            @Override
            public int read() throws IOException {
                reading.countDown();
                try {
                    if (!release.await(3, TimeUnit.SECONDS)) {
                        throw new IOException("Fixture read remained blocked");
                    }
                    return -1;
                } catch (InterruptedException exception) {
                    Thread.currentThread().interrupt();
                    throw new IOException(exception);
                }
            }

            @Override
            public void close() {
                closed.countDown();
                release.countDown();
            }
        };
        var request = CoverImageRequests.request(cancellation -> Optional.of(CoverCacheLoader.open(cache,
                URI.create("https://image.example/cover"), cancellation,
                token -> new AudioResolveContext((httpRequest, responseToken) -> TestAudioHttpResponse.owned(
                        httpRequest.uri(), 200, Map.of(), body, responseToken), uri -> {}, token, AudioResolveLimits.DEFAULT))));
        try {
            assertTrue(reading.await(2, TimeUnit.SECONDS));
            assertTrue(request.cancel(false));
            assertTrue(closed.await(2, TimeUnit.SECONDS));
            assertTrue(request.isCancelled());
        } finally {
            release.countDown();
        }
    }

    @Test
    void cancellingReturnedFutureCancelsInFlightMetadataScope() throws Exception {
        CountDownLatch started = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        CountDownLatch retired = new CountDownLatch(1);
        AtomicReference<AudioCancellation> token = new AtomicReference<>();
        var request = CoverImageRequests.request(cancellation -> {
            token.set(cancellation);
            started.countDown();
            try {
                if (!release.await(2, TimeUnit.SECONDS)) {
                    throw new IOException("Fixture was not released");
                }
                cancellation.throwIfCancelled();
                return Optional.empty();
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
                throw new IOException(exception);
            } finally {
                retired.countDown();
            }
        });
        try {
            assertTrue(started.await(2, TimeUnit.SECONDS));
            assertTrue(request.cancel(false));
            assertTrue(token.get().isCancelled());
            release.countDown();
            assertTrue(retired.await(2, TimeUnit.SECONDS));
            assertTrue(request.isCancelled());
        } finally {
            release.countDown();
        }
    }

    @Test
    void queuedCancellationDoesNotBeginProviderWork() throws Exception {
        CountDownLatch started = new CountDownLatch(2);
        CountDownLatch release = new CountDownLatch(1);
        CoverImageRequests.CoverOperation blocker = cancellation -> {
            started.countDown();
            try {
                if (!release.await(3, TimeUnit.SECONDS)) {
                    throw new IOException("Fixture was not released");
                }
                return Optional.empty();
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
                throw new IOException(exception);
            }
        };
        var first = CoverImageRequests.request(blocker);
        var second = CoverImageRequests.request(blocker);
        AtomicBoolean opened = new AtomicBoolean();
        try {
            assertTrue(started.await(2, TimeUnit.SECONDS));
            var queued = CoverImageRequests.request(cancellation -> {
                opened.set(true);
                return Optional.empty();
            });
            assertTrue(queued.cancel(false));
            release.countDown();
            assertSame(CoverDescriptor.EMPTY, first.get(2, TimeUnit.SECONDS));
            assertSame(CoverDescriptor.EMPTY, second.get(2, TimeUnit.SECONDS));
            CoverImageRequests.request(cancellation -> Optional.empty()).get(2, TimeUnit.SECONDS);
            assertFalse(opened.get());
        } finally {
            release.countDown();
        }
    }

    @Test
    void unsupportedSourcesReturnEmptyImmediatelyWithoutImageOrCacheInitialization() {
        assertTrue(CoverImageRequests.supportsProvider("https://artist.bandcamp.com/album/test"));
        assertTrue(CoverImageRequests.supportsProvider("https://soundcloud.com/artist/track"));
        for (String url : new String[]{null, "minecraft:music_disc.13", "https://images.example/cover.png",
                "https://notbandcamp.com/test", "https://evilsoundcloud.com/test", "https://user@soundcloud.com/test",
                "bad url", "ftp://soundcloud.com/artist/track", "https://soundcloud.com.evil.example/test"}) {
            assertFalse(CoverImageRequests.supportsProvider(url));
            var result = CoverImageRequests.requestProviderResource(url, Proxy.NO_PROXY);
            assertTrue(result.isDone());
            assertSame(CoverDescriptor.EMPTY, result.join());
        }
    }

    @Test
    void cancellationDuringNativeImageCreationClosesLateResultAndReleasesCacheLease() throws Exception {
        var cache = new BoundedMediaCache(temporary.resolve("native"));
        CountDownLatch processing = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        AtomicReference<NativeImage> lateImage = new AtomicReference<>();
        var worker = new java.util.concurrent.ThreadPoolExecutor(1, 1, 0, TimeUnit.MILLISECONDS,
                new java.util.concurrent.ArrayBlockingQueue<>(2));
        var pending = CoverImageRequests.request(cancellation -> Optional.of(coverLease(cache, cancellation)), worker, lease -> {
            NativeImage image = new NativeImage(1, 1, true);
            lateImage.set(image);
            processing.countDown();
            try {
                assertTrue(release.await(5, TimeUnit.SECONDS));
            } catch (InterruptedException exception) {
                image.close();
                Thread.currentThread().interrupt();
                throw new IOException(exception);
            }
            return image;
        });
        try {
            assertTrue(processing.await(2, TimeUnit.SECONDS));
            pending.cancel(false);
            release.countDown();
            worker.submit(() -> {}).get(2, TimeUnit.SECONDS);
            assertThrows(IllegalStateException.class, () -> lateImage.get().getPixelRGBA(0, 0));
            assertTrue(pending.isCancelled());
        } finally {
            release.countDown();
            pending.cancel(false);
            worker.shutdownNow();
        }
    }

    @Test
    void nativeImagePublicationTransfersOwnershipWithoutClosingConsumersImage() throws Exception {
        var cache = new BoundedMediaCache(temporary.resolve("native-success"));
        var pending = CoverImageRequests.request(cancellation -> Optional.of(coverLease(cache, cancellation)),
                lease -> new NativeImage(1, 1, true));
        ImageAlbumCover cover = assertInstanceOf(ImageAlbumCover.class, pending.get(2, TimeUnit.SECONDS));
        try (NativeImage image = cover.image()) {
            assertFalse(pending.cancel(false));
            image.setPixelRGBA(0, 0, -1);
            assertEquals(-1, image.getPixelRGBA(0, 0));
        }
    }

    @Test
    void nativeImageFactoryErrorsAndProviderLinkageErrorsCompleteWaitersExceptionally() throws Exception {
        var cache = new BoundedMediaCache(temporary.resolve("native-error"));
        LinkageError failure = new LinkageError("fixture native image failure");
        var pending = CoverImageRequests.request(cancellation -> Optional.of(coverLease(cache, cancellation)),
                lease -> { throw failure; });
        assertSame(failure, assertThrows(java.util.concurrent.ExecutionException.class,
                () -> pending.get(2, TimeUnit.SECONDS)).getCause());
        var provider = CoverImageRequests.request(cancellation -> { throw failure; });
        assertSame(failure, assertThrows(java.util.concurrent.ExecutionException.class,
                () -> provider.get(2, TimeUnit.SECONDS)).getCause());
    }

    private static BoundedMediaCache.Lease coverLease(BoundedMediaCache cache, AudioCancellation cancellation) throws IOException {
        var bytes = new java.io.ByteArrayOutputStream();
        javax.imageio.ImageIO.write(new java.awt.image.BufferedImage(1, 1, java.awt.image.BufferedImage.TYPE_INT_ARGB), "png", bytes);
        byte[] png = bytes.toByteArray();
        return cache.acquire(BoundedMediaCache.Namespace.COVERS, "native-fixture", cancellation,
                token -> new BoundedMediaCache.Content(new java.io.ByteArrayInputStream(png), png.length), MediaValidators::cover);
    }
}
