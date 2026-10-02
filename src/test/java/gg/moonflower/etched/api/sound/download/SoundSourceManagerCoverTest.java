package gg.moonflower.etched.api.sound.download;

import gg.moonflower.etched.api.record.AlbumCover;
import gg.moonflower.etched.api.record.TrackData;
import gg.moonflower.etched.api.util.DownloadProgressListener;
import net.minecraft.server.packs.resources.ResourceManager;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.Proxy;
import java.net.URL;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

class SoundSourceManagerCoverTest {

    @Test
    void thirdPartyMetadataKeepsTheExplicitProxyAndMissingArtworkCompletesEmpty() throws Exception {
        String input = "https://thirdparty.example/test-missing-artwork";
        Proxy proxy = new Proxy(Proxy.Type.HTTP, new InetSocketAddress("127.0.0.1", 9000));
        AtomicInteger calls = new AtomicInteger();
        SoundSourceManager.registerSource(new Provider(input) {
            @Override
            public Optional<String> resolveAlbumCover(String url, DownloadProgressListener listener,
                                                       Proxy configuredProxy, ResourceManager resources) {
                assertEquals(input, url);
                assertSame(proxy, configuredProxy);
                assertNull(listener);
                assertNull(resources);
                calls.incrementAndGet();
                return Optional.empty();
            }
        });
        assertSame(AlbumCover.EMPTY, SoundSourceManager.resolveAlbumCover(input, null, proxy, null).get(2, TimeUnit.SECONDS));
        assertEquals(1, calls.get());
    }

    @Test
    void failedOrMalformedThirdPartyMetadataStillCompletesAsAnEmptyCover() throws Exception {
        for (boolean failure : new boolean[]{false, true}) {
            String input = "https://thirdparty.example/test-invalid-cover-" + failure;
            SoundSourceManager.registerSource(new Provider(input) {
                @Override
                public Optional<String> resolveAlbumCover(String url, DownloadProgressListener listener,
                                                           Proxy proxy, ResourceManager resources) throws IOException {
                    if (failure) {
                        throw new IOException("fixture provider failure");
                    }
                    return Optional.of("bad URL");
                }
            });
            assertSame(AlbumCover.EMPTY, SoundSourceManager.resolveAlbumCover(input, null, Proxy.NO_PROXY, null)
                    .get(2, TimeUnit.SECONDS));
        }
    }

    @Test
    void cancellingThePublicFallbackFuturePreventsLateImageWorkAndDoesNotPoisonAnotherRequest() throws Exception {
        String input = "https://thirdparty.example/test-cancel-cover";
        CountDownLatch started = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        AtomicInteger calls = new AtomicInteger();
        SoundSourceManager.registerSource(new Provider(input) {
            @Override
            public Optional<String> resolveAlbumCover(String url, DownloadProgressListener listener,
                                                       Proxy proxy, ResourceManager resources) throws IOException {
                if (calls.incrementAndGet() > 1) {
                    return Optional.empty();
                }
                started.countDown();
                try {
                    if (!release.await(3, TimeUnit.SECONDS)) {
                        throw new IOException("Fixture was not released");
                    }
                } catch (InterruptedException exception) {
                    Thread.currentThread().interrupt();
                    throw new IOException(exception);
                }
                return Optional.of("https://image.example/late.png");
            }
        });
        var first = SoundSourceManager.resolveAlbumCover(input, null, Proxy.NO_PROXY, null);
        try {
            assertTrue(started.await(2, TimeUnit.SECONDS));
            assertTrue(first.cancel(false));
            release.countDown();
            assertSame(AlbumCover.EMPTY, SoundSourceManager.resolveAlbumCover(input, null, Proxy.NO_PROXY, null)
                    .get(2, TimeUnit.SECONDS));
            assertTrue(first.isCancelled());
            assertEquals(2, calls.get());
        } finally {
            release.countDown();
        }
    }

    private abstract static class Provider implements SoundDownloadSource {
        private final String input;

        private Provider(String input) { this.input = input; }

        @Override public boolean isValidUrl(String url) { return input.equals(url); }
        @Override public boolean isTemporary(String url) { return true; }
        @Override public String getApiName() { return "Fixture provider"; }
        @Override public List<URL> resolveUrl(String url, DownloadProgressListener listener, Proxy proxy) {
            throw new AssertionError("Cover resolution reached audio URL lookup");
        }
        @Override public List<TrackData> resolveTracks(String url, DownloadProgressListener listener, Proxy proxy) {
            throw new AssertionError("Cover resolution reached track lookup");
        }
    }
}
