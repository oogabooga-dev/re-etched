package gg.moonflower.etched.client.radio.source;

import gg.moonflower.etched.client.radio.RadioCancellation;
import gg.moonflower.etched.client.radio.RadioFailure;
import gg.moonflower.etched.client.radio.net.*;
import gg.moonflower.etched.client.radio.stream.RadioAacAudioStream;
import gg.moonflower.etched.client.radio.stream.RadioAudioStream;
import org.junit.jupiter.api.Test;

import java.io.*;
import java.net.*;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Arrays;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

class SoundCloudHlsSourceTest {
    private static final URI BASE = URI.create("https://media.example/audio/list.m3u8");

    static byte[] fixture(String name) throws IOException {
        try (InputStream in = SoundCloudHlsSourceTest.class.getResourceAsStream(
                "/gg/moonflower/etched/client/radio/audio/aac/" + name)) {
            return in.readAllBytes();
        }
    }

    @Test
    void parsesFinitePlaylistAndRelativeUrls() throws Exception {
        var playlist = SoundCloudHlsSource.parse(BASE, fixture("playlist.m3u8"), 8192);
        assertEquals(BASE.resolve("init.mp4"), playlist.init());
        assertEquals(3, playlist.segments().size());
        assertEquals(BASE.resolve("segment0.m4s"), playlist.segments().get(0));
    }

    @Test
    void rejectsLiveEncryptedRangedDiscontinuousAndMasterPlaylists() throws Exception {
        String playlist = new String(fixture("playlist.m3u8"), StandardCharsets.UTF_8);
        for (String bad : new String[]{playlist.replace("#EXT-X-ENDLIST", ""),
                playlist.replace("#EXTINF:", "#EXT-X-DISCONTINUITY\n#EXTINF:"),
                playlist.replace("#EXTINF:", "#EXT-X-KEY:METHOD=AES-128,URI=\"key\"\n#EXTINF:"),
                playlist.replace("#EXTINF:", "#EXT-X-BYTERANGE:42@0\n#EXTINF:"),
                playlist.replace("segment0.m4s", "file:///etc/passwd"),
                playlist.replace("segment0.m4s", "https://user:pass@media.example/segment"),
                playlist.replace("#EXTINF:1.021678,", "#EXTINF:NaN,"),
                "#EXTM3U\n#EXT-X-STREAM-INF:BANDWIDTH=160000\nchild.m3u8\n"}) {
            assertThrows(IOException.class, () -> SoundCloudHlsSource.parse(BASE, bad.getBytes(StandardCharsets.UTF_8), 8192));
        }
        assertThrows(IOException.class, () -> SoundCloudHlsSource.parse(BASE, fixture("playlist.m3u8"), 10));
    }

    @Test
    void demuxesAndDecodesAllSegmentsWithFrameAlignedReads() throws Exception {
        var demuxer = FragmentedMp4Aac.initialization(fixture("init.mp4"));
        var adts = new ByteArrayOutputStream();
        for (int i = 0; i < 3; i++) adts.write(demuxer.fragment(fixture("segment" + i + ".m4s")));
        try (var audio = new RadioAacAudioStream(new ByteArrayInputStream(adts.toByteArray()))) {
            assertEquals(44100, audio.getFormat().getSampleRate());
            assertEquals(2, audio.getFormat().getChannels());
            int total = 0; boolean nonzero = false;
            for (ByteBuffer pcm; (pcm = audio.read(513)).hasRemaining();) {
                assertTrue(pcm.isDirect()); assertEquals(0, pcm.remaining() % 4);
                total += pcm.remaining();
                while (pcm.hasRemaining()) nonzero |= pcm.get() != 0;
            }
            assertEquals(88 * 1024 * 4, total);
            assertTrue(nonzero);
            assertEquals(RadioAudioStream.TerminalState.EOF, audio.termination().toCompletableFuture().get(1, TimeUnit.SECONDS).state());
        }
    }

    @Test
    void rejectsTruncatedBoxesWrongTracksAndOutOfBoundsSamples() throws Exception {
        byte[] init = fixture("init.mp4"), segment = fixture("segment0.m4s");
        for (int length : new int[]{0, 7, 20, init.length - 1}) {
            assertThrows(IOException.class, () -> FragmentedMp4Aac.initialization(Arrays.copyOf(init, length)));
        }
        for (String box : new String[]{"tfhd", "trun"}) {
            byte[] corrupted = segment.clone();
            int pos = find(corrupted, box);
            // tfhd track id / trun sample count
            ByteBuffer.wrap(corrupted).putInt(pos + 8, Integer.MAX_VALUE);
            assertThrows(IOException.class, () -> FragmentedMp4Aac.initialization(init).fragment(corrupted));
        }
        byte[] offset = segment.clone();
        ByteBuffer.wrap(offset).putInt(find(offset, "trun") + 12, Integer.MAX_VALUE);
        assertThrows(IOException.class, () -> FragmentedMp4Aac.initialization(init).fragment(offset));
        assertThrows(IOException.class, () -> FragmentedMp4Aac.initialization(init).fragment(Arrays.copyOf(segment, segment.length - 1)));
        var demuxer = FragmentedMp4Aac.initialization(init);
        demuxer.fragment(segment);
        assertThrows(IOException.class, () -> demuxer.fragment(segment), "Repeated/out-of-order segment must fail");
    }

    @Test
    void rejectsTruncatedAacAndFormatChanges() throws Exception {
        byte[] adts = FragmentedMp4Aac.initialization(fixture("init.mp4")).fragment(fixture("segment0.m4s"));
        assertThrows(IOException.class, () -> new RadioAacAudioStream(new ByteArrayInputStream(Arrays.copyOf(adts, 6))));
        byte[] changed = adts.clone();
        int length = ((changed[3] & 3) << 11) | ((changed[4] & 255) << 3) | ((changed[5] & 255) >>> 5);
        changed[length + 2] ^= 4;
        try (var audio = new RadioAacAudioStream(new ByteArrayInputStream(changed))) {
            assertThrows(IOException.class, () -> audio.read(64 * 1024));
        }
        try (var audio = new RadioAacAudioStream(new ByteArrayInputStream(Arrays.copyOf(adts, adts.length - 1)))) {
            assertThrows(IOException.class, () -> audio.read(1024 * 1024));
        }
    }

    @Test
    void downloadsLazilyClosesAndKeepsIndependentReaders() throws Exception {
        AtomicInteger requests = new AtomicInteger();
        try (TestHttpServer server = new TestHttpServer()) {
            install(server, requests);
            var context = context(uri -> { });
            try (var first = SoundCloudHlsSource.open(server.uri("/playlist.m3u8"), context);
                 var second = SoundCloudHlsSource.open(server.uri("/playlist.m3u8"), context(uri -> { }))) {
                assertEquals(0, requests.get(), "No segment is prefetched on the resolver thread");
                byte[] a = first.body().readNBytes(32);
                assertEquals(1, requests.get());
                first.close();
                assertThrows(CancellationException.class, () -> first.body().read());
                assertArrayEquals(a, second.body().readNBytes(32));
                assertEquals(2, requests.get());
            }
        }
    }

    @Test
    void checksInitSegmentAndRedirectDestinations() throws Exception {
        AtomicInteger requests = new AtomicInteger();
        try (TestHttpServer server = new TestHttpServer()) {
            install(server, requests);
            for (String blocked : new String[]{"/init.mp4", "/segment0.m4s"}) {
                var context = context(uri -> {
                    if (uri.getPath().equals(blocked)) throw new RadioTransportException(
                            RadioFailure.Code.BLOCKED_ADDRESS, false, "Blocked by fixture", null);
                });
                if (blocked.contains("init")) {
                    var ex = assertThrows(RadioSourceException.class, () -> SoundCloudHlsSource.open(server.uri("/playlist.m3u8"), context));
                    assertEquals(RadioFailure.Code.BLOCKED_ADDRESS, ex.code());
                } else try (var source = SoundCloudHlsSource.open(server.uri("/playlist.m3u8"), context)) {
                    var ex = assertThrows(RadioSourceException.class, () -> source.body().read());
                    assertEquals(RadioFailure.Code.BLOCKED_ADDRESS, ex.code());
                }
            }
            assertEquals(0, requests.get());
            server.handle("/redirect.m3u8", exchange -> {
                exchange.getResponseHeaders().add("Location", "http://blocked.example/playlist");
                exchange.sendResponseHeaders(302, -1); exchange.close();
            });
            var ctx = context(uri -> {
                if (uri.getHost().equals("blocked.example")) throw new RadioTransportException(
                        RadioFailure.Code.BLOCKED_ADDRESS, false, "Blocked redirect", null);
            });
            assertThrows(RadioSourceException.class, () -> SoundCloudHlsSource.open(server.uri("/redirect.m3u8"), ctx));
        }
    }

    @Test
    void rejectsOversizedInitializationAndSegmentBeforeReadingBodies() throws Exception {
        for (String oversized : new String[]{"init.mp4", "segment0.m4s"}) {
            try (TestHttpServer server = new TestHttpServer()) {
                serve(server, "/playlist.m3u8", fixture("playlist.m3u8"));
                if (!oversized.equals("init.mp4")) serve(server, "/init.mp4", fixture("init.mp4"));
                server.handle("/" + oversized, exchange -> {
                    exchange.sendResponseHeaders(200, SoundCloudHlsSource.MAX_SEGMENT_BYTES + 1L);
                    exchange.getResponseBody().flush(); exchange.close();
                });
                if (oversized.equals("init.mp4")) {
                    assertThrows(IOException.class, () -> SoundCloudHlsSource.open(server.uri("/playlist.m3u8"), context(uri -> { })));
                } else try (var source = SoundCloudHlsSource.open(server.uri("/playlist.m3u8"), context(uri -> { }))) {
                    assertThrows(IOException.class, () -> source.body().read());
                }
            }
        }
    }

    @Test
    void closeDoesNotBlockAndCancellationWinsWhenReadReturns() throws Exception {
        // Exclude first-use Log4j/cleanup-executor class initialization from the close deadline.
        CountDownLatch warmed = new CountDownLatch(1);
        gg.moonflower.etched.client.radio.RadioResourceDisposer.dispose(warmed::countDown);
        assertTrue(warmed.await(5, TimeUnit.SECONDS));
        CountDownLatch entered = new CountDownLatch(1), release = new CountDownLatch(1);
        ExecutorService executor = Executors.newSingleThreadExecutor();
        try (TestHttpServer server = new TestHttpServer()) {
            serve(server, "/playlist.m3u8", fixture("playlist.m3u8"));
            serve(server, "/init.mp4", fixture("init.mp4"));
            server.handle("/segment0.m4s", exchange -> {
                exchange.sendResponseHeaders(200, 100000);
                exchange.getResponseBody().write(0); exchange.getResponseBody().flush(); entered.countDown();
                try { release.await(5, TimeUnit.SECONDS); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
                exchange.close();
            });
            try (var source = SoundCloudHlsSource.open(server.uri("/playlist.m3u8"), context(uri -> { }))) {
                Future<?> read = executor.submit(() -> assertThrows(Exception.class, () -> source.body().read()));
                assertTrue(entered.await(2, TimeUnit.SECONDS));
                Future<?> close = CompletableFuture.runAsync(source::close);
                close.get(1, TimeUnit.SECONDS);
                // HttpURLConnection may hold its read lock until the server responds or the read timeout.
                // The caller must not wait for that lock; cancellation must win over returned bytes.
                release.countDown();
                read.get(2, TimeUnit.SECONDS);
            }
        } finally { release.countDown(); executor.shutdownNow(); }
    }

    private static int find(byte[] bytes, String text) {
        byte[] pattern = text.getBytes(StandardCharsets.US_ASCII);
        for (int i = 0; i <= bytes.length - pattern.length; i++) {
            if (Arrays.equals(bytes, i, i + pattern.length, pattern, 0, pattern.length)) return i;
        }
        throw new AssertionError("Missing box " + text);
    }

    private static RadioResolveContext context(RadioNetworkPolicy policy) {
        var transport = new RadioHttpTransportImpl(Proxy.NO_PROXY, policy, Duration.ofSeconds(2), Duration.ofSeconds(3), 5);
        return new RadioResolveContext(transport, policy, new RadioCancellation(), RadioResolveLimits.DEFAULT);
    }

    private static void install(TestHttpServer server, AtomicInteger requests) throws IOException {
        serve(server, "/playlist.m3u8", fixture("playlist.m3u8"));
        serve(server, "/init.mp4", fixture("init.mp4"));
        for (int i = 0; i < 3; i++) {
            byte[] bytes = fixture("segment" + i + ".m4s");
            server.handle("/segment" + i + ".m4s", exchange -> {
                requests.incrementAndGet(); exchange.sendResponseHeaders(200, bytes.length);
                try (exchange; var out = exchange.getResponseBody()) { out.write(bytes); }
            });
        }
    }

    private static void serve(TestHttpServer server, String path, byte[] bytes) {
        server.handle(path, exchange -> {
            exchange.sendResponseHeaders(200, bytes.length);
            try (exchange; var out = exchange.getResponseBody()) { out.write(bytes); }
        });
    }
}
