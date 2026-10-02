package gg.moonflower.etched.common.sound.download;

import gg.moonflower.etched.api.util.DownloadProgressListener;
import gg.moonflower.etched.common.audio.AudioCancellation;
import gg.moonflower.etched.common.audio.RadioFailure;
import gg.moonflower.etched.common.audio.net.RadioTransportException;
import gg.moonflower.etched.common.audio.net.TestAudioHttpResponse;
import gg.moonflower.etched.common.audio.provider.SoundCloudMetadataResolver;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.contents.TranslatableContents;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.Proxy;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

class SoundCloudSourceTest {

    private static final String INPUT = "https://soundcloud.com/artist/track";
    private static final URI HOME = URI.create("https://soundcloud.com/");
    private static final URI API = URI.create("https://api-v2.soundcloud.com/resolve");
    private static final String TRACK = """
            {"kind":"track","streamable":true,"title":"Track","user":{"username":"Artist"},
             "artwork_url":"https://images.example/cover.jpg","media":{"transcodings":[
             {"url":"https://api-v2.soundcloud.com/transcoding","format":{"protocol":"progressive","mime_type":"audio/mpeg"}}]}}
            """;

    @Test
    void allLegacyEntrypointsUseFreshCommonScopesAndPreserveProxyResultsAndProgress() throws Exception {
        AtomicInteger opened = new AtomicInteger();
        AtomicInteger closed = new AtomicInteger();
        List<AudioCancellation> tokens = new ArrayList<>();
        Proxy proxy = new Proxy(Proxy.Type.HTTP, new InetSocketAddress("127.0.0.1", 9000));
        var source = new SoundCloudSource(configuredProxy -> {
            assertSame(proxy, configuredProxy);
            return new SoundCloudMetadataResolver((request, token) -> {
                assertEquals(opened.get(), closed.get());
                tokens.add(token);
                opened.incrementAndGet();
                String body = switch (request.uri().getPath()) {
                    case "/" -> "<script src='/app.js'></script>";
                    case "/app.js" -> "client_id:'test'";
                    case "/resolve" -> TRACK;
                    case "/transcoding" -> "{\"url\":\"https://media.example/track.mp3\"}";
                    default -> throw new AssertionError("Unexpected media/image request " + request.uri());
                };
                var stream = new ByteArrayInputStream(body.getBytes(StandardCharsets.UTF_8)) {
                    @Override public void close() { closed.incrementAndGet(); }
                };
                return TestAudioHttpResponse.owned(request.uri(), 200, Map.of(), stream, token);
            }, uri -> assertEquals(opened.get(), closed.get()), HOME, API, SoundCloudMetadataResolver.Limits.DEFAULT);
        });
        Progress listener = new Progress();
        assertEquals(List.of("https://media.example/track.mp3"), source.resolveUrl(INPUT, listener, proxy)
                .stream().map(Object::toString).toList());
        var tracks = source.resolveTracks(INPUT, listener, proxy);
        assertEquals(1, tracks.size());
        assertEquals(INPUT, tracks.get(0).url());
        assertEquals("Artist", tracks.get(0).artist());
        assertEquals("Track", tracks.get(0).title().getString());
        assertEquals("https://images.example/cover.jpg", source.resolveAlbumCover(INPUT, listener, proxy, null).orElseThrow());
        assertEquals(10, opened.get());
        assertEquals(opened.get(), closed.get());
        assertEquals(3, tokens.stream().distinct().count());
        assertEquals(List.of("sound_source.etched.requesting", "record.etched.resolvingTracks",
                "sound_source.etched.requesting", "sound_source.etched.requesting"), listener.requests);
        assertEquals("SoundCloud", source.getApiName());
        assertTrue(source.isTemporary(INPUT));
        assertTrue(source.getBrandText(INPUT).isPresent());
    }

    @Test
    void invalidInputsFailBeforeCreatingResolversOrAttemptingLegacyHttp() {
        var source = new SoundCloudSource(proxy -> {
            throw new AssertionError("Invalid input created a resolver");
        });
        for (String url : new String[]{null, "bad url", "file:///etc/passwd", "ftp://soundcloud.com/a",
                "https://evilsoundcloud.com/a", "https://soundcloud.com.evil.example/a", "https://user@soundcloud.com/a"}) {
            assertFalse(source.isValidUrl(url));
            assertThrows(IOException.class, () -> source.resolveUrl(url, null, Proxy.NO_PROXY));
            assertThrows(IOException.class, () -> source.resolveTracks(url, null, Proxy.NO_PROXY));
            assertThrows(IOException.class, () -> source.resolveAlbumCover(url, null, Proxy.NO_PROXY, null));
        }
        assertTrue(new SoundCloudSource().isValidUrl(INPUT));
    }

    @Test
    void policyFailuresPropagateFromAllEntrypointsWithoutOpeningDiscoveryOrFallingBack() {
        var source = new SoundCloudSource(proxy -> new SoundCloudMetadataResolver((request, cancellation) -> {
            throw new AssertionError("Blocked input reached HTTP");
        }, uri -> {
            throw new RadioTransportException(RadioFailure.Code.BLOCKED_ADDRESS, false, "blocked", null);
        }, HOME, API, SoundCloudMetadataResolver.Limits.DEFAULT));
        assertEquals(RadioFailure.Code.BLOCKED_ADDRESS, assertThrows(RadioTransportException.class,
                () -> source.resolveUrl(INPUT, null, Proxy.NO_PROXY)).code());
        assertEquals(RadioFailure.Code.BLOCKED_ADDRESS, assertThrows(RadioTransportException.class,
                () -> source.resolveTracks(INPUT, null, Proxy.NO_PROXY)).code());
        assertEquals(RadioFailure.Code.BLOCKED_ADDRESS, assertThrows(RadioTransportException.class,
                () -> source.resolveAlbumCover(INPUT, null, Proxy.NO_PROXY, null)).code());
    }

    private static final class Progress implements DownloadProgressListener {
        private final List<String> requests = new ArrayList<>();

        @Override
        public void progressStartRequest(Component component) {
            requests.add(((TranslatableContents) component.getContents()).getKey());
        }

        @Override public void progressStartDownload(float size) { fail("Provider emitted a media download event"); }
        @Override public void progressStagePercentage(int percentage) { fail("Provider emitted a media download event"); }
        @Override public void progressStartLoading() { fail("Provider emitted a decoder event"); }
        @Override public void onSuccess() { fail("Provider completed playback"); }
        @Override public void onFail() { fail("Provider swallowed a failure"); }
    }
}
