package gg.moonflower.etched.common.sound.download;

import gg.moonflower.etched.api.util.DownloadProgressListener;
import gg.moonflower.etched.common.audio.RadioFailure;
import gg.moonflower.etched.common.audio.net.AudioNetworkPolicy;
import gg.moonflower.etched.common.audio.net.RadioHttpTransportImpl;
import gg.moonflower.etched.common.audio.net.RadioTransportException;
import gg.moonflower.etched.common.audio.net.TestAudioHttpResponse;
import gg.moonflower.etched.common.audio.net.TestHttpServer;
import gg.moonflower.etched.common.audio.provider.BandcampMetadataResolver;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.contents.TranslatableContents;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.Proxy;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

class BandcampSourceTest {

    private static final String INPUT = "https://artist.bandcamp.com/album/test";
    private static final String HTML = """
            <div data-tralbum='{"artist":"Artist", "current":{"type":"album","title":"Album","art_id":123},
             "trackinfo":[{"title":"One","title_link":"/track/one","file":{"mp3-128":"https://media.example/one.mp3"}},
                          {"title":"Two","title_link":"/track/two","file":{"mp3-128":"https://media.example/two.mp3"}}]}'></div>
            """;

    @Test
    void retainedCoverEntrypointPreservesProxyOwnershipAndProgress() throws Exception {
        AtomicInteger opened = new AtomicInteger();
        AtomicInteger closed = new AtomicInteger();
        List<Object> tokens = new ArrayList<>();
        Proxy proxy = new Proxy(Proxy.Type.HTTP, new InetSocketAddress("127.0.0.1", 9000));
        var source = new BandcampSource(configuredProxy -> {
            assertSame(proxy, configuredProxy);
            return new BandcampMetadataResolver((request, token) -> {
                assertEquals(URI.create(INPUT), request.uri());
                assertEquals(5, request.maxRedirects());
                tokens.add(token);
                opened.incrementAndGet();
                var body = new ByteArrayInputStream(HTML.getBytes(StandardCharsets.UTF_8)) {
                    @Override
                    public void close() {
                        closed.incrementAndGet();
                    }
                };
                return TestAudioHttpResponse.owned(request.uri(), 200, Map.of(), body, token);
            }, uri -> assertEquals(opened.get(), closed.get()), BandcampMetadataResolver.Limits.DEFAULT);
        });
        Progress listener = new Progress();
        assertEquals("https://f4.bcbits.com/img/a123_1.jpg", source.resolveAlbumCover(INPUT, listener, proxy, null).orElseThrow());
        assertEquals(1, opened.get());
        assertEquals(opened.get(), closed.get());
        assertEquals(1, tokens.stream().distinct().count());
        assertEquals(List.of("sound_source.etched.requesting"), listener.requests);
        assertEquals("Bandcamp", source.getApiName());
        assertTrue(source.getBrandText(INPUT).isPresent());
    }

    @Test
    void invalidInputsFailAllPublicEntrypointsBeforeCreatingAResolver() {
        var source = new BandcampSource(proxy -> {
            throw new AssertionError("Invalid input created a resolver");
        });
        for (String url : new String[]{null, "bad url", "file:///etc/passwd", "https://notbandcamp.com/album/test",
                "https://user@artist.bandcamp.com/track/test", "https://bandcamp.com.example/test"}) {
            assertFalse(source.isValidUrl(url));
            assertThrows(IOException.class, () -> source.resolveAlbumCover(url, null, Proxy.NO_PROXY, null));
        }
        assertTrue(new BandcampSource().isValidUrl(INPUT));
    }

    @Test
    void destinationFailuresPropagateFromEveryProjectionWithoutFallback() {
        AtomicInteger closed = new AtomicInteger();
        var source = new BandcampSource(proxy -> new BandcampMetadataResolver((request, token) -> {
            var body = new ByteArrayInputStream(HTML.getBytes(StandardCharsets.UTF_8)) {
                @Override
                public void close() {
                    closed.incrementAndGet();
                }
            };
            return TestAudioHttpResponse.owned(request.uri(), 200, Map.of(), body, token);
        }, uri -> {
            throw new RadioTransportException(RadioFailure.Code.BLOCKED_ADDRESS, false, "blocked", null);
        }, BandcampMetadataResolver.Limits.DEFAULT));
        assertEquals(RadioFailure.Code.BLOCKED_ADDRESS, assertThrows(RadioTransportException.class,
                () -> source.resolveAlbumCover(INPUT, null, Proxy.NO_PROXY, null)).code());
        assertEquals(1, closed.get());
    }

    @Test
    void coverResolutionPreservesProxyAndRejectsRedirectsBeforeOpeningPrivateTargets() throws Exception {
        URI forbidden = URI.create("http://127.0.0.1/private");
        AtomicInteger opened = new AtomicInteger();
        AudioNetworkPolicy policy = uri -> {
            if (uri.equals(forbidden)) {
                throw new RadioTransportException(RadioFailure.Code.BLOCKED_ADDRESS, false, "blocked redirect", null);
            }
        };
        try (TestHttpServer server = new TestHttpServer()) {
            server.handle("/album/test", exchange -> {
                opened.incrementAndGet();
                assertEquals("artist.bandcamp.com", exchange.getRequestURI().getHost());
                exchange.getResponseHeaders().set("Location", forbidden.toString());
                exchange.sendResponseHeaders(302, -1);
                exchange.close();
            });
            Proxy proxy = new Proxy(Proxy.Type.HTTP, new InetSocketAddress("127.0.0.1", server.uri("/").getPort()));
            var source = new BandcampSource(configuredProxy -> new BandcampMetadataResolver(
                    new RadioHttpTransportImpl(configuredProxy, policy, Duration.ofSeconds(1), Duration.ofSeconds(1), 5),
                    policy, BandcampMetadataResolver.Limits.DEFAULT));
            assertEquals(RadioFailure.Code.BLOCKED_ADDRESS, assertThrows(RadioTransportException.class,
                    () -> source.resolveAlbumCover(INPUT.replace("https:", "http:"), null, proxy, null)).code());
            assertEquals(1, opened.get());
        }
    }

    private static final class Progress implements DownloadProgressListener {
        private final List<String> requests = new ArrayList<>();

        @Override
        public void progressStartRequest(Component component) {
            requests.add(((TranslatableContents) component.getContents()).getKey());
        }

        @Override public void progressStartDownload(float size) { fail("Metadata emitted a media download event"); }
        @Override public void progressStagePercentage(int percentage) { fail("Metadata emitted a media download event"); }
        @Override public void progressStartLoading() { fail("Metadata emitted a decoder event"); }
        @Override public void onSuccess() { fail("Metadata completed playback"); }
        @Override public void onFail() { fail("Provider swallowed a failure"); }
    }
}
