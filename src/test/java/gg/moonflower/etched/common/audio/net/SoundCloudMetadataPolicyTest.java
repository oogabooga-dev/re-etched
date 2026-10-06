package gg.moonflower.etched.common.audio.net;

import gg.moonflower.etched.common.audio.AudioCancellation;
import gg.moonflower.etched.common.audio.RadioFailure;
import gg.moonflower.etched.common.audio.provider.SoundCloudMetadataResolver;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.net.InetAddress;
import java.net.Proxy;
import java.net.URI;
import java.time.Duration;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class SoundCloudMetadataPolicyTest {

    @Test
    void coverUrlsResolvingToPrivateAddressesAreBlockedAfterClosingTheApiResponse() {
        var policy = new DefaultRadioNetworkPolicy(() -> false,
                host -> new InetAddress[]{InetAddress.getByAddress(host.equals("media.example")
                        ? new byte[]{127, 0, 0, 1} : new byte[]{8, 8, 8, 8})});
        AtomicInteger closed = new AtomicInteger();
        AtomicInteger opened = new AtomicInteger();
        var resolver = new SoundCloudMetadataResolver((request, cancellation) -> {
            opened.incrementAndGet();
            String body = switch (request.uri().getPath()) {
                case "/" -> "<script src='/app.js'></script>";
                case "/app.js" -> "client_id:'test'";
                case "/resolve" -> """
                        {"kind":"track","streamable":true,"artwork_url":"https://media.example/cover.jpg"}
                        """;
                default -> throw new AssertionError("Unexpected media request " + request.uri());
            };
            var stream = new ByteArrayInputStream(body.getBytes(StandardCharsets.UTF_8)) {
                @Override public void close() { closed.incrementAndGet(); }
            };
            return TestAudioHttpResponse.owned(request.uri(), 200, Map.of(), stream, cancellation);
        }, policy, URI.create("https://soundcloud.com/"), URI.create("https://api-v2.soundcloud.com/resolve"),
                SoundCloudMetadataResolver.Limits.DEFAULT);
        assertEquals(RadioFailure.Code.BLOCKED_ADDRESS, assertThrows(RadioTransportException.class,
                () -> resolver.resolveAlbumCover(URI.create("https://soundcloud.com/artist/track"), new AudioCancellation())).code());
        assertEquals(3, opened.get());
        assertEquals(opened.get(), closed.get());
    }

    @Test
    void validatesTheSubmittedPagesDnsBeforeDiscovery() {
        var policy = new DefaultRadioNetworkPolicy(() -> false,
                host -> new InetAddress[]{InetAddress.getByAddress(new byte[]{127, 0, 0, 1})});
        var transport = new RadioHttpTransportImpl(Proxy.NO_PROXY, policy, Duration.ofSeconds(1),
                Duration.ofSeconds(1), 5, (uri, proxy) -> {
            throw new AssertionError("A blocked SoundCloud page started discovery");
        });
        var resolver = new SoundCloudMetadataResolver(transport, policy,
                URI.create("https://soundcloud.com/"), URI.create("https://api-v2.soundcloud.com/resolve"),
                SoundCloudMetadataResolver.Limits.DEFAULT);
        assertEquals(RadioFailure.Code.BLOCKED_ADDRESS, assertThrows(RadioTransportException.class,
                () -> resolver.resolveTracks(URI.create("https://soundcloud.com/a/track"), new AudioCancellation())).code());
    }
}
