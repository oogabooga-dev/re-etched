package gg.moonflower.etched.api.sound.download;

import gg.moonflower.etched.api.record.TrackData;
import gg.moonflower.etched.api.util.DownloadProgressListener;
import gg.moonflower.etched.common.audio.AudioCancellation;
import gg.moonflower.etched.common.audio.provider.LegacyTrackMetadataRequests;
import net.minecraft.network.chat.Component;
import net.minecraft.server.packs.resources.ResourceManager;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.Proxy;
import java.net.URL;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

class SoundSourceManagerMetadataTest {

    @Test
    void publicMetadataRequestsKeepTheProxyAndReturnIndependentAlbumArrays() throws Exception {
        String input = "https://thirdparty.example/test-metadata-order";
        Proxy proxy = new Proxy(Proxy.Type.HTTP, new InetSocketAddress("127.0.0.1", 9000));
        TrackData album = new TrackData(input, "Artist", Component.literal("Album"));
        TrackData track = new TrackData("https://thirdparty.example/track", "Artist", Component.literal("Track"));
        AtomicInteger calls = new AtomicInteger();
        SoundSourceManager.registerSource(new Provider(input) {
            @Override
            public List<TrackData> resolveTracks(String url, DownloadProgressListener listener, Proxy configuredProxy) {
                assertEquals(input, url);
                assertSame(proxy, configuredProxy);
                assertNull(listener);
                calls.incrementAndGet();
                return List.of(album, track);
            }
        });
        var first = SoundSourceManager.resolveTracks(input, null, proxy);
        var second = SoundSourceManager.resolveTracks(input, null, proxy);
        assertNotSame(first, second);
        TrackData[] one = first.get(2, TimeUnit.SECONDS);
        TrackData[] two = second.get(2, TimeUnit.SECONDS);
        assertNotSame(one, two);
        assertArrayEquals(new TrackData[]{album, track}, two);
        one[1] = track.withArtist("Label artist");
        assertEquals("Artist", two[1].artist());
        assertEquals(2, calls.get());
    }

    @Test
    void etchingCancellationStopsWaitingWithoutCancellingAnotherRequestForTheSameUrl() throws Exception {
        String input = "https://thirdparty.example/test-metadata-cancellation";
        CountDownLatch started = new CountDownLatch(2);
        CountDownLatch release = new CountDownLatch(1);
        TrackData track = new TrackData(input, "Artist", Component.literal("Track"));
        SoundSourceManager.registerSource(new Provider(input) {
            @Override
            public List<TrackData> resolveTracks(String url, DownloadProgressListener listener, Proxy proxy) throws IOException {
                started.countDown();
                try {
                    if (!release.await(5, TimeUnit.SECONDS)) {
                        throw new IOException("Fixture was not released");
                    }
                } catch (InterruptedException exception) {
                    Thread.currentThread().interrupt();
                    throw new IOException(exception);
                }
                return List.of(track);
            }
        });
        AudioCancellation cancellation = new AudioCancellation();
        var first = CompletableFuture.supplyAsync(() -> {
            try {
                return LegacyTrackMetadataRequests.await(
                        () -> SoundSourceManager.resolveTracks(input, null, Proxy.NO_PROXY), cancellation);
            } catch (IOException exception) {
                throw new CompletionException(exception);
            }
        });
        var second = SoundSourceManager.resolveTracks(input, null, Proxy.NO_PROXY);
        try {
            assertTrue(started.await(2, TimeUnit.SECONDS));
            cancellation.cancel();
            assertInstanceOf(java.util.concurrent.CancellationException.class, assertThrows(ExecutionException.class,
                    () -> first.get(2, TimeUnit.SECONDS)).getCause());
            assertFalse(second.isDone());
            release.countDown();
            assertEquals(track, second.get(2, TimeUnit.SECONDS)[0]);
        } finally {
            release.countDown();
        }
    }

    @Test
    void unknownSourcesAndProviderFailuresKeepTheirExceptionContract() throws Exception {
        assertThrows(IOException.class, () -> SoundSourceManager.resolveTracks(
                "https://thirdparty.example/unregistered-metadata", null, Proxy.NO_PROXY));
        String input = "https://thirdparty.example/test-metadata-failure";
        IOException failure = new IOException("fixture failure");
        SoundSourceManager.registerSource(new Provider(input) {
            @Override
            public List<TrackData> resolveTracks(String url, DownloadProgressListener listener, Proxy proxy) throws IOException {
                throw failure;
            }
        });
        var request = SoundSourceManager.resolveTracks(input, null, Proxy.NO_PROXY);
        assertSame(failure, assertThrows(CompletionException.class, request::join).getCause());
    }

    @Test
    void publicMetadataRejectsMalformedResultsAndSnapshotsMutableTitleTrees() throws Exception {
        String malformed = "https://thirdparty.example/test-metadata-malformed";
        SoundSourceManager.registerSource(new Provider(malformed) {
            @Override public List<TrackData> resolveTracks(String url, DownloadProgressListener listener, Proxy proxy) {
                return List.of(new TrackData("file:///tmp/music", "Artist", Component.literal("Track")));
            }
        });
        var failure = SoundSourceManager.resolveTracks(malformed, null, Proxy.NO_PROXY);
        assertInstanceOf(IOException.class, assertThrows(CompletionException.class, failure::join).getCause());
        String input = "https://thirdparty.example/test-metadata-title-snapshot";
        var title = Component.literal("Track").append(" original");
        SoundSourceManager.registerSource(new Provider(input) {
            @Override public List<TrackData> resolveTracks(String url, DownloadProgressListener listener, Proxy proxy) {
                return List.of(new TrackData(input, "Artist", title));
            }
        });
        var tracks = SoundSourceManager.resolveTracks(input, null, Proxy.NO_PROXY).get(2, TimeUnit.SECONDS);
        title.append(" changed");
        assertEquals("Track original", tracks[0].title().getString());
    }

    private abstract static class Provider implements SoundDownloadSource {
        private final String input;

        private Provider(String input) { this.input = input; }
        @Override public boolean isValidUrl(String url) { return input.equals(url); }
        @Override public boolean isTemporary(String url) { return true; }
        @Override public String getApiName() { return "Fixture metadata provider"; }
        @Override public List<URL> resolveUrl(String url, DownloadProgressListener listener, Proxy proxy) {
            throw new AssertionError("Metadata reached audio URL resolution");
        }
        @Override public Optional<String> resolveAlbumCover(String url, DownloadProgressListener listener,
                                                           Proxy proxy, ResourceManager resources) {
            throw new AssertionError("Metadata reached cover resolution");
        }
    }
}
