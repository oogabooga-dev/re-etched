package gg.moonflower.etched.client.cache;

import gg.moonflower.etched.client.radio.AudioCancellation;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BoundedMediaCacheTest {

    private static final byte[] OGG = "OggS-data".getBytes(StandardCharsets.UTF_8);
    private static final BoundedMediaCache.Limits SMALL = new BoundedMediaCache.Limits(
            16, 16, 1, Duration.ofDays(7));

    @TempDir
    Path temporary;

    @Test
    void cacheHitCreatesIndependentStreamsAndPinsDuringEviction() throws Exception {
        BoundedMediaCache cache = cache();
        AtomicInteger downloads = new AtomicInteger();
        BoundedMediaCache.Loader loader = token -> {
            downloads.incrementAndGet();
            return new BoundedMediaCache.Content(new ByteArrayInputStream(OGG), OGG.length);
        };
        try (var first = cache.acquire(BoundedMediaCache.Namespace.AUDIO, "one", new AudioCancellation(),
                loader, MediaValidators::audio);
             var second = cache.acquire(BoundedMediaCache.Namespace.AUDIO, "one", new AudioCancellation(),
                     loader, MediaValidators::audio)) {
            assertEquals('O', first.body().read());
            assertEquals('O', second.body().read());
            assertThrows(IOException.class, () -> cache.acquire(BoundedMediaCache.Namespace.AUDIO,
                    "two", new AudioCancellation(), loader, MediaValidators::audio));
            assertArrayEquals("ggS-data".getBytes(StandardCharsets.UTF_8), first.body().readAllBytes());
        }
        try (var next = cache.acquire(BoundedMediaCache.Namespace.AUDIO, "two", new AudioCancellation(),
                loader, MediaValidators::audio)) {
            assertArrayEquals(OGG, next.body().readAllBytes());
        }
        assertEquals(3, downloads.get());
    }

    @Test
    void rejectsOversizeAndMalformedFilesWithoutPromotingPartialDownloads() throws Exception {
        BoundedMediaCache cache = cache();
        for (byte[] body : new byte[][]{new byte[17], "not-mp3".getBytes(StandardCharsets.UTF_8)}) {
            assertThrows(IOException.class, () -> cache.acquire(BoundedMediaCache.Namespace.AUDIO, "bad",
                    new AudioCancellation(), token -> new BoundedMediaCache.Content(
                            new ByteArrayInputStream(body), -1), MediaValidators::audio));
        }
        try (var files = Files.list(temporary.resolve("v5/audio"))) {
            assertEquals(0, files.count());
        }
        assertThrows(IOException.class, () -> cache.acquire(BoundedMediaCache.Namespace.AUDIO, "hint",
                new AudioCancellation(), token -> new BoundedMediaCache.Content(
                        new ByteArrayInputStream(OGG), 17), MediaValidators::audio));
    }

    @Test
    void coalescedWaiterSurvivesTheFirstConsumersCancellation() throws Exception {
        BoundedMediaCache cache = cache();
        AtomicInteger downloads = new AtomicInteger();
        AudioCancellation firstCancellation = new AudioCancellation();
        CountDownLatch fetching = new CountDownLatch(1);
        CountDownLatch finish = new CountDownLatch(1);
        var workers = Executors.newFixedThreadPool(2);
        try {
            var first = workers.submit(() -> cache.acquire(BoundedMediaCache.Namespace.AUDIO, "shared",
                    firstCancellation, token -> {
                        downloads.incrementAndGet();
                        fetching.countDown();
                        try {
                            if (!finish.await(5, TimeUnit.SECONDS)) {
                                throw new IOException("Download stalled");
                            }
                        } catch (InterruptedException exception) {
                            throw new IOException(exception);
                        }
                        token.throwIfCancelled();
                        return new BoundedMediaCache.Content(new ByteArrayInputStream(OGG), OGG.length);
                    }, MediaValidators::audio));
            assertTrue(fetching.await(5, TimeUnit.SECONDS));
            var second = workers.submit(() -> cache.acquire(BoundedMediaCache.Namespace.AUDIO, "shared",
                    new AudioCancellation(), token -> {
                        throw new AssertionError("The coalesced request must not download again");
                    }, MediaValidators::audio));
            Thread.sleep(100L);
            firstCancellation.cancel();
            finish.countDown();
            assertThrows(java.util.concurrent.ExecutionException.class, () -> first.get(5, TimeUnit.SECONDS));
            try (var leased = second.get(5, TimeUnit.SECONDS)) {
                assertArrayEquals(OGG, leased.body().readAllBytes());
            }
        } finally {
            finish.countDown();
            workers.shutdownNow();
        }
        assertEquals(1, downloads.get());
    }

    @Test
    void startupRemovesPartialAndSymlinkButLeavesUnknownFilesAndTheirTargets() throws Exception {
        Path root = temporary.resolve("v5");
        Files.createDirectory(root);
        Path audio = root.resolve("audio");
        Files.createDirectory(audio);
        Path target = temporary.resolve("outside.txt");
        Files.writeString(target, "outside");
        Path unsafe = audio.resolve("a".repeat(64) + ".bin");
        Files.createSymbolicLink(unsafe, target);
        Path partial = audio.resolve("b".repeat(64) + "-unfinished.part");
        Files.write(partial, OGG);
        Path unrelated = audio.resolve("note.txt");
        Files.writeString(unrelated, "leave me");

        cache();

        assertFalse(Files.exists(unsafe));
        assertFalse(Files.exists(partial));
        assertEquals("outside", Files.readString(target));
        assertTrue(Files.exists(unrelated));
        Path alias = temporary.resolve("alias");
        Files.createSymbolicLink(alias, root);
        assertThrows(IOException.class, () -> new BoundedMediaCache(alias));
    }

    @Test
    void startupDropsExpiredAndMalformedMediaWithoutOpeningLegacyDirectories() throws Exception {
        BoundedMediaCache cache = cache();
        try (var expired = cache.acquire(BoundedMediaCache.Namespace.AUDIO, "expired",
                new AudioCancellation(), token -> new BoundedMediaCache.Content(
                        new ByteArrayInputStream(OGG), OGG.length), MediaValidators::audio)) {
            assertArrayEquals(OGG, expired.body().readAllBytes());
        }
        Path audio = temporary.resolve("v5/audio");
        try (var files = Files.list(audio)) {
            Files.setLastModifiedTime(files.findFirst().orElseThrow(), FileTime.from(
                    Instant.parse("2026-09-01T00:00:00Z")));
        }
        Path invalid = audio.resolve("a".repeat(64) + ".bin");
        Files.writeString(invalid, "not audio");
        Files.setLastModifiedTime(invalid, FileTime.from(Instant.parse("2026-09-25T00:00:00Z")));
        Path legacy = temporary.resolve("etched-sounds");
        Files.createDirectory(legacy);
        Files.writeString(legacy.resolve("cache.json"), "legacy");

        cache();

        try (var files = Files.list(audio)) {
            assertEquals(0, files.count());
        }
        assertEquals("legacy", Files.readString(legacy.resolve("cache.json")));
    }

    @Test
    void coverValidationChecksDecodedDimensionsAndPixels() throws Exception {
        BoundedMediaCache cache = new BoundedMediaCache(temporary.resolve("v5"),
                Clock.fixed(Instant.parse("2026-09-25T00:00:00Z"), ZoneOffset.UTC), SMALL,
                new BoundedMediaCache.Limits(8192, 8192, 2, Duration.ofDays(1)));
        byte[] acceptable = png(16, 16);
        byte[] oversized = png(2049, 1);
        try (var cover = cache.acquire(BoundedMediaCache.Namespace.COVERS, "valid",
                new AudioCancellation(), token -> new BoundedMediaCache.Content(
                        new ByteArrayInputStream(acceptable), acceptable.length), MediaValidators::cover)) {
            assertArrayEquals(acceptable, cover.body().readAllBytes());
        }
        assertThrows(IOException.class, () -> cache.acquire(BoundedMediaCache.Namespace.COVERS,
                "too-wide", new AudioCancellation(), token -> new BoundedMediaCache.Content(
                        new ByteArrayInputStream(oversized), oversized.length), MediaValidators::cover));
    }

    @Test
    void replacedNamespaceDirectoryIsNotFollowedAfterStartup() throws Exception {
        BoundedMediaCache cache = cache();
        var loader = (BoundedMediaCache.Loader) token -> new BoundedMediaCache.Content(
                new ByteArrayInputStream(OGG), OGG.length);
        try (var first = cache.acquire(BoundedMediaCache.Namespace.AUDIO, "one",
                new AudioCancellation(), loader, MediaValidators::audio)) {
            assertArrayEquals(OGG, first.body().readAllBytes());
        }
        Path namespace = temporary.resolve("v5/audio");
        Path original = temporary.resolve("original-audio");
        Files.move(namespace, original);
        Files.createSymbolicLink(namespace, original);

        assertThrows(IOException.class, () -> cache.acquire(BoundedMediaCache.Namespace.AUDIO, "one",
                new AudioCancellation(), loader, MediaValidators::audio));
        try (var files = Files.list(original)) {
            assertEquals(1, files.count());
        }
    }

    @Test
    void cancellingTheOnlyDownloadRemovesItsPartialFile() throws Exception {
        BoundedMediaCache cache = cache();
        AudioCancellation cancellation = new AudioCancellation();
        CountDownLatch reading = new CountDownLatch(1);
        CountDownLatch closed = new CountDownLatch(1);
        var worker = Executors.newSingleThreadExecutor();
        try {
            var load = worker.submit(() -> cache.acquire(BoundedMediaCache.Namespace.AUDIO, "slow",
                    cancellation, token -> new BoundedMediaCache.Content(new InputStream() {
                        @Override
                        public int read() throws IOException {
                            reading.countDown();
                            try {
                                closed.await(5, TimeUnit.SECONDS);
                            } catch (InterruptedException exception) {
                                Thread.currentThread().interrupt();
                                throw new IOException(exception);
                            }
                            throw new IOException("Download closed");
                        }

                        @Override
                        public void close() {
                            closed.countDown();
                        }
                    }, -1), MediaValidators::audio));
            assertTrue(reading.await(5, TimeUnit.SECONDS));
            cancellation.cancel();
            assertThrows(java.util.concurrent.ExecutionException.class, () -> load.get(5, TimeUnit.SECONDS));
            try (var files = Files.list(temporary.resolve("v5/audio"))) {
                assertEquals(0, files.count());
            }
        } finally {
            closed.countDown();
            worker.shutdownNow();
        }
    }

    private static byte[] png(int width, int height) throws IOException {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        ImageIO.write(new BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB), "png", output);
        return output.toByteArray();
    }

    private BoundedMediaCache cache() throws IOException {
        return new BoundedMediaCache(temporary.resolve("v5"),
                Clock.fixed(Instant.parse("2026-09-25T00:00:00Z"), ZoneOffset.UTC), SMALL, SMALL);
    }
}
