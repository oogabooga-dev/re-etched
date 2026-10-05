package gg.moonflower.etched.client.cache;

import gg.moonflower.etched.common.audio.AudioCancellation;
import gg.moonflower.etched.common.audio.AudioContentProbe;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

class MediaValidatorsTest {

    @TempDir Path temporary;

    @Test
    void acceptsNativeMp3AndVorbisFixturesAndBoundedId3() throws Exception {
        for (String name : List.of("mono.mp3", "stereo.ogg")) {
            byte[] bytes;
            try (var fixture = getClass().getResourceAsStream("/gg/moonflower/etched/client/radio/audio/" + name)) {
                bytes = fixture.readAllBytes();
            }
            Path file = temporary.resolve(name);
            Files.write(file, bytes);
            MediaValidators.audio(file);
        }
        byte[] tagged = new byte[32 * 1024 + 14];
        tagged[0] = 'I'; tagged[1] = 'D'; tagged[2] = '3'; tagged[3] = 4;
        tagged[7] = 2; // synchsafe 32768-byte tag
        tagged[tagged.length - 4] = (byte) 0xFF;
        tagged[tagged.length - 3] = (byte) 0xFB;
        tagged[tagged.length - 2] = (byte) 0x90;
        Path file = temporary.resolve("tagged");
        Files.write(file, tagged);
        MediaValidators.audio(file);
    }

    @Test
    void rejectsFakeSignaturesOpusAacHtmlAndOversizedOrTruncatedId3() throws Exception {
        byte[] excessive = new byte[10];
        excessive[0] = 'I'; excessive[1] = 'D'; excessive[2] = '3'; excessive[3] = 4;
        excessive[7] = 32; // 524288 exceeds the default prefix budget
        byte[] opus;
        try (var fixture = getClass().getResourceAsStream("/gg/moonflower/etched/client/radio/audio/stereo.ogg")) {
            opus = fixture.readAllBytes();
        }
        System.arraycopy("OpusHead".getBytes(java.nio.charset.StandardCharsets.US_ASCII), 0, opus, 28, 8);
        int index = 0;
        for (byte[] bytes : new byte[][]{"OggS-data".getBytes(), "ID3-not-a-tag".getBytes(),
                "<html>error</html>".getBytes(), "RIFF0000WAVE".getBytes(),
                {(byte) 0xFF, (byte) 0xF1, 0x50, (byte) 0x80},
                {(byte) 0xFF, (byte) 0xFB, (byte) 0xFC, 0}, excessive, opus, new byte[3]}) {
            Path file = temporary.resolve("invalid-" + index++);
            Files.write(file, bytes);
            assertThrows(MediaValidators.UnsupportedAudioException.class, () -> MediaValidators.audio(file));
        }
        assertEquals(256 * 1024, AudioContentProbe.DEFAULT_MAX_ID3_PREFIX_BYTES);
    }

    @Test
    void rejectsFakeHeadersBeforePromotionAndEvictsPreviouslyAdmittedCacheEntries() throws Exception {
        var cache = new BoundedMediaCache(temporary.resolve("cache"));
        byte[] fake = "OggS-data".getBytes(java.nio.charset.StandardCharsets.US_ASCII);
        assertThrows(IOException.class, () -> cache.acquire(BoundedMediaCache.Namespace.AUDIO, "fake",
                new AudioCancellation(), token -> new BoundedMediaCache.Content(new ByteArrayInputStream(fake), fake.length), MediaValidators::audio));
        try (var files = Files.list(temporary.resolve("cache/audio"))) {
            assertEquals(0, files.count());
        }
        byte[] mpeg = {(byte) 0xFF, (byte) 0xFB, (byte) 0x90, 0};
        AtomicInteger loads = new AtomicInteger();
        BoundedMediaCache.Loader loader = token -> {
            loads.incrementAndGet();
            return new BoundedMediaCache.Content(new ByteArrayInputStream(mpeg), mpeg.length);
        };
        try (var first = cache.acquire(BoundedMediaCache.Namespace.AUDIO, "old", new AudioCancellation(), loader, MediaValidators::audio)) {
            assertArrayEquals(mpeg, first.body().readAllBytes());
        }
        try (var files = Files.list(temporary.resolve("cache/audio"))) {
            Files.write(files.findFirst().orElseThrow(), fake);
        }
        try (var replacement = cache.acquire(BoundedMediaCache.Namespace.AUDIO, "old", new AudioCancellation(), loader, MediaValidators::audio)) {
            assertArrayEquals(mpeg, replacement.body().readAllBytes());
        }
        assertEquals(2, loads.get());
    }
}
