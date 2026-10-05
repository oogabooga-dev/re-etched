package gg.moonflower.etched.api.sound.download;

import gg.moonflower.etched.api.record.TrackData;
import gg.moonflower.etched.api.sound.source.AudioSource;
import gg.moonflower.etched.api.util.DownloadProgressListener;
import gg.moonflower.etched.common.audio.AudioCancellation;
import net.minecraft.server.packs.resources.ResourceManager;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.Proxy;
import java.net.URL;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletionException;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

@Timeout(10)
class SoundSourceManagerAudioTest {

    @Test
    void independentUrlLookupsPreserveExplicitProxyAndNeverOpenMediaBeforeConsumerRequestsIt() throws Exception {
        String input = "https://provider.example/audio-independence";
        Proxy proxy = new Proxy(Proxy.Type.HTTP, new InetSocketAddress("127.0.0.1", 9999));
        AtomicInteger calls = new AtomicInteger();
        SoundSourceManager.registerSource(new Provider(input) {
            @Override public List<URL> resolveUrl(String url, DownloadProgressListener listener, Proxy actual) throws IOException {
                assertSame(proxy, actual);
                calls.incrementAndGet();
                return List.of(new URL("https://audio.example/track"));
            }
        });
        var first = SoundSourceManager.getAudioSource(input, null, proxy, AudioSource.AudioFileType.STREAM);
        var second = SoundSourceManager.getAudioSource(input, null, proxy, AudioSource.AudioFileType.STREAM);
        assertNotSame(first, second);
        assertNotSame(first.get(2, TimeUnit.SECONDS), second.get(2, TimeUnit.SECONDS));
        assertEquals(2, calls.get());
    }

    @Test
    void cancellingRunningUrlLookupWakesWaiterAndSuppressesLateProviderCallbacks() throws Exception {
        String input = "https://provider.example/audio-cancellation";
        CountDownLatch started = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        CountDownLatch returned = new CountDownLatch(1);
        AtomicInteger callbacks = new AtomicInteger();
        SoundSourceManager.registerSource(new Provider(input) {
            @Override public List<URL> resolveUrl(String url, DownloadProgressListener listener, Proxy proxy) throws IOException {
                started.countDown();
                await(release);
                listener.onFail();
                returned.countDown();
                return List.of(new URL("https://audio.example/late"));
            }
        });
        var scope = new AudioCancellation();
        var pending = SoundSourceManager.getAudioSource(input, new DownloadProgressListener() {
            @Override public void progressStartRequest(net.minecraft.network.chat.Component component) { callbacks.incrementAndGet(); }
            @Override public void progressStartDownload(float size) { callbacks.incrementAndGet(); }
            @Override public void progressStagePercentage(int percentage) { callbacks.incrementAndGet(); }
            @Override public void progressStartLoading() { callbacks.incrementAndGet(); }
            @Override public void onSuccess() { callbacks.incrementAndGet(); }
            @Override public void onFail() { callbacks.incrementAndGet(); }
        }, Proxy.NO_PROXY, AudioSource.AudioFileType.STREAM, scope);
        try {
            assertTrue(started.await(2, TimeUnit.SECONDS));
            scope.cancel();
            assertTrue(pending.isCancelled());
            release.countDown();
            assertTrue(returned.await(2, TimeUnit.SECONDS));
            assertEquals(0, callbacks.get());
            assertTrue(pending.isCancelled());
        } finally {
            release.countDown();
        }
    }

    @Test
    void malformedProviderResultsFailBeforeSourceConstructionOrMediaIo() throws Exception {
        List<List<URL>> results = Arrays.asList(null, List.of(), Arrays.asList((URL) null),
                List.of(new URL("file:///tmp/music")), List.of(new URL("https://u:p@audio.example/music")),
                Collections.nCopies(101, new URL("https://audio.example/music")));
        int index = 0;
        for (List<URL> urls : results) {
            String input = "https://provider.example/audio-invalid-" + index++;
            SoundSourceManager.registerSource(new Provider(input) {
                @Override public List<URL> resolveUrl(String url, DownloadProgressListener listener, Proxy proxy) { return urls; }
            });
            var result = SoundSourceManager.getAudioSource(input, null, Proxy.NO_PROXY, AudioSource.AudioFileType.STREAM);
            assertInstanceOf(IOException.class, assertThrows(CompletionException.class, result::join).getCause());
        }
    }

    @Test
    void cancelledResolvedSourceNeverStartsOpeningItsMedia() throws Exception {
        var cancellation = new AudioCancellation();
        var pending = SoundSourceManager.getAudioSource("https://audio.example/cancelled-open", null,
                Proxy.NO_PROXY, AudioSource.AudioFileType.STREAM, cancellation);
        var source = pending.get(2, TimeUnit.SECONDS);
        cancellation.cancel();
        var open = source.openStream();
        assertInstanceOf(java.util.concurrent.CancellationException.class,
                assertThrows(CompletionException.class, open::join).getCause());
    }

    @Test
    void firstPartyHostsCannotFallThroughToRegisteredLegacyProviders() throws Exception {
        String input = "https://soundcloud.com/artist/cancelled-first-party";
        SoundSourceManager.registerSource(new Provider(input) {
            @Override public List<URL> resolveUrl(String url, DownloadProgressListener listener, Proxy proxy) {
                throw new AssertionError("First-party URL fell back to compatibility provider");
            }
        });
        var scope = new AudioCancellation();
        scope.cancel();
        assertTrue(SoundSourceManager.getAudioSource(input, null, Proxy.NO_PROXY,
                AudioSource.AudioFileType.STREAM, scope).isCancelled());
    }

    private static void await(CountDownLatch release) throws IOException {
        try {
            assertTrue(release.await(5, TimeUnit.SECONDS));
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IOException(exception);
        }
    }

    private abstract static class Provider implements SoundDownloadSource {
        private final String input;
        private Provider(String input) { this.input = input; }
        @Override public boolean isValidUrl(String url) { return input.equals(url); }
        @Override public boolean isTemporary(String url) { return true; }
        @Override public String getApiName() { return "Fixture audio provider"; }
        @Override public List<TrackData> resolveTracks(String url, DownloadProgressListener listener, Proxy proxy) {
            throw new AssertionError("Audio lookup reached metadata");
        }
        @Override public Optional<String> resolveAlbumCover(String url, DownloadProgressListener listener, Proxy proxy, ResourceManager resources) {
            throw new AssertionError("Audio lookup reached cover metadata");
        }
    }
}
