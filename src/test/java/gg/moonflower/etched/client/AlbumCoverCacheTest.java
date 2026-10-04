package gg.moonflower.etched.client;

import com.mojang.blaze3d.platform.NativeImage;
import gg.moonflower.etched.api.record.AlbumCover;
import gg.moonflower.etched.client.cache.BoundedMediaCache;
import gg.moonflower.etched.client.cache.CoverCacheLoader;
import gg.moonflower.etched.client.cache.MediaValidators;
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

class AlbumCoverCacheTest {

    @TempDir Path temporary;

    @Test
    void stalledCompatibilityMetadataDoesNotOccupyFirstPartyCoverWorkers() throws Exception {
        CountDownLatch started = new CountDownLatch(2);
        CountDownLatch release = new CountDownLatch(1);
        CoverCacheLoader.CoverUrlResolver stalled = cancellation -> {
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
        var first = AlbumCoverCache.requestResolvedResource(stalled, Proxy.NO_PROXY);
        var second = AlbumCoverCache.requestResolvedResource(stalled, Proxy.NO_PROXY);
        try {
            assertTrue(started.await(2, TimeUnit.SECONDS));
            assertSame(AlbumCover.EMPTY, AlbumCoverCache.request(cancellation -> Optional.empty()).get(1, TimeUnit.SECONDS));
        } finally {
            release.countDown();
        }
        assertSame(AlbumCover.EMPTY, first.get(2, TimeUnit.SECONDS));
        assertSame(AlbumCover.EMPTY, second.get(2, TimeUnit.SECONDS));
    }

    @Test
    void cancellingTheReturnedFutureDuringResolvedImageReadClosesItsResponse() throws Exception {
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
        var request = AlbumCoverCache.request(cancellation -> CoverCacheLoader.openResolved(() -> cache,
                token -> Optional.of(URI.create("https://image.example/cover")), cancellation,
                token -> new AudioResolveContext((httpRequest, responseToken) -> TestAudioHttpResponse.owned(
                        httpRequest.uri(), 200, Map.of(), body, responseToken), uri -> {}, token, AudioResolveLimits.DEFAULT)));
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
    void resolvedResourceCancellationSuppressesLateProviderUrlsWithoutNativeImageOrCacheInitialization() throws Exception {
        CountDownLatch started = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        CountDownLatch returned = new CountDownLatch(1);
        AtomicReference<AudioCancellation> token = new AtomicReference<>();
        var request = AlbumCoverCache.requestResolvedResource(cancellation -> {
            token.set(cancellation);
            started.countDown();
            try {
                if (!release.await(3, TimeUnit.SECONDS)) {
                    throw new IOException("Fixture was not released");
                }
                return Optional.of(URI.create("https://image.example/late-cover.png"));
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
                throw new IOException(exception);
            } finally {
                returned.countDown();
            }
        }, Proxy.NO_PROXY);
        try {
            assertTrue(started.await(2, TimeUnit.SECONDS));
            assertTrue(request.cancel(false));
            assertTrue(token.get().isCancelled());
            release.countDown();
            assertTrue(returned.await(2, TimeUnit.SECONDS));
            assertTrue(request.isCancelled());
            assertSame(AlbumCover.EMPTY, AlbumCoverCache.requestResolvedResource(
                    cancellation -> Optional.empty(), Proxy.NO_PROXY).get(2, TimeUnit.SECONDS));
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
        var request = AlbumCoverCache.request(cancellation -> {
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
        AlbumCoverCache.CoverOperation blocker = cancellation -> {
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
        var first = AlbumCoverCache.request(blocker);
        var second = AlbumCoverCache.request(blocker);
        AtomicBoolean opened = new AtomicBoolean();
        try {
            assertTrue(started.await(2, TimeUnit.SECONDS));
            var queued = AlbumCoverCache.request(cancellation -> {
                opened.set(true);
                return Optional.empty();
            });
            assertTrue(queued.cancel(false));
            release.countDown();
            assertSame(AlbumCover.EMPTY, first.get(2, TimeUnit.SECONDS));
            assertSame(AlbumCover.EMPTY, second.get(2, TimeUnit.SECONDS));
            AlbumCoverCache.request(cancellation -> Optional.empty()).get(2, TimeUnit.SECONDS);
            assertFalse(opened.get());
        } finally {
            release.countDown();
        }
    }

    @Test
    void providerMatchingIsStrictAndDoesNotClaimDirectCoversOrLocalSounds() {
        assertTrue(AlbumCoverCache.supportsProvider("https://artist.bandcamp.com/album/test"));
        assertTrue(AlbumCoverCache.supportsProvider("https://soundcloud.com/artist/track"));
        for (String url : new String[]{null, "minecraft:music_disc.13", "https://images.example/cover.png",
                "https://notbandcamp.com/test", "https://evilsoundcloud.com/test", "https://user@soundcloud.com/test"}) {
            assertFalse(AlbumCoverCache.supportsProvider(url));
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
        var pending = AlbumCoverCache.request(cancellation -> Optional.of(coverLease(cache, cancellation)), worker, lease -> {
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
        var pending = AlbumCoverCache.request(cancellation -> Optional.of(coverLease(cache, cancellation)),
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
        var pending = AlbumCoverCache.request(cancellation -> Optional.of(coverLease(cache, cancellation)),
                lease -> { throw failure; });
        assertSame(failure, assertThrows(java.util.concurrent.ExecutionException.class,
                () -> pending.get(2, TimeUnit.SECONDS)).getCause());
        var provider = AlbumCoverCache.request(cancellation -> { throw failure; });
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
