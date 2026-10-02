package gg.moonflower.etched.client;

import gg.moonflower.etched.api.record.AlbumCover;
import gg.moonflower.etched.common.audio.AudioCancellation;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

class AlbumCoverCacheTest {

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
}
