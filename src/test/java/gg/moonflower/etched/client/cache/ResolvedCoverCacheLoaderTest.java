package gg.moonflower.etched.client.cache;

import gg.moonflower.etched.client.radio.source.AudioResolveContext;
import gg.moonflower.etched.client.radio.source.AudioResolveLimits;
import gg.moonflower.etched.common.audio.AudioCancellation;
import gg.moonflower.etched.common.audio.RadioFailure;
import gg.moonflower.etched.common.audio.net.AudioNetworkPolicy;
import gg.moonflower.etched.common.audio.net.RadioHttpTransportImpl;
import gg.moonflower.etched.common.audio.net.RadioTransportException;
import gg.moonflower.etched.common.audio.net.TestAudioHttpResponse;
import gg.moonflower.etched.common.audio.net.TestHttpServer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.Proxy;
import java.net.URI;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CancellationException;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;
import java.util.function.Supplier;

import static org.junit.jupiter.api.Assertions.*;

class ResolvedCoverCacheLoaderTest {

    private static final URI IMAGE = URI.create("http://image.example/cover");
    private static final AudioNetworkPolicy ALLOW_ALL = uri -> {};

    @TempDir Path temporary;

    @Test
    void emptyFailedOrCancelledMetadataDoesNotInitializeTheCacheOrImageContext() throws Exception {
        Supplier<BoundedMediaCache> cache = () -> { throw new AssertionError("Metadata initialized the image cache"); };
        Function<AudioCancellation, AudioResolveContext> contexts = token -> {
            throw new AssertionError("Metadata initialized the image transport");
        };
        assertTrue(CoverCacheLoader.openResolved(cache, token -> Optional.empty(), new AudioCancellation(), contexts).isEmpty());
        IOException failure = new IOException("metadata failed");
        assertSame(failure, assertThrows(IOException.class, () -> CoverCacheLoader.openResolved(cache,
                token -> { throw failure; }, new AudioCancellation(), contexts)));
        AudioCancellation preCancelled = new AudioCancellation();
        preCancelled.cancel();
        assertThrows(CancellationException.class, () -> CoverCacheLoader.openResolved(cache,
                token -> { throw new AssertionError("Pre-cancelled request started metadata"); }, preCancelled, contexts));
        AudioCancellation cancellation = new AudioCancellation();
        assertThrows(CancellationException.class, () -> CoverCacheLoader.openResolved(cache, token -> {
            token.cancel();
            // Models a provider that returns a URL without observing cancellation.
            return Optional.of(IMAGE);
        }, cancellation, contexts));
    }

    @Test
    void resolvedImagesUseTheConfiguredProxyAndCacheOnlyBytesWithIndependentLeases() throws Exception {
        byte[] image = png();
        AtomicInteger metadata = new AtomicInteger();
        AtomicInteger requests = new AtomicInteger();
        try (TestHttpServer proxyServer = new TestHttpServer()) {
            proxyServer.handle("/cover", exchange -> {
                assertEquals(IMAGE, exchange.getRequestURI());
                requests.incrementAndGet();
                exchange.sendResponseHeaders(200, image.length);
                exchange.getResponseBody().write(image);
                exchange.close();
            });
            Proxy proxy = new Proxy(Proxy.Type.HTTP, proxyServer.address());
            Function<AudioCancellation, AudioResolveContext> contexts = token -> new AudioResolveContext(
                    new RadioHttpTransportImpl(proxy, ALLOW_ALL, Duration.ofSeconds(1), Duration.ofSeconds(1), 5),
                    ALLOW_ALL, token, AudioResolveLimits.DEFAULT);
            var cache = new BoundedMediaCache(temporary.resolve("v5"));
            CoverCacheLoader.CoverUrlResolver urls = token -> {
                metadata.incrementAndGet();
                return Optional.of(IMAGE);
            };
            try (var first = CoverCacheLoader.openResolved(() -> cache, urls, new AudioCancellation(), contexts).orElseThrow();
                 var second = CoverCacheLoader.openResolved(() -> cache, urls, new AudioCancellation(), contexts).orElseThrow()) {
                assertEquals(image[0] & 0xFF, first.body().read());
                assertArrayEquals(image, second.body().readAllBytes());
            }
            assertEquals(2, metadata.get());
            assertEquals(1, requests.get());
        }
    }

    @Test
    void returnedDestinationsArePolicyCheckedEvenWhenTheirImageBytesAreCached() throws Exception {
        byte[] image = png();
        AtomicInteger downloads = new AtomicInteger();
        var cache = new BoundedMediaCache(temporary.resolve("v5"));
        Function<AudioCancellation, AudioResolveContext> contexts = token -> new AudioResolveContext((request, cancellation) -> {
            downloads.incrementAndGet();
            return TestAudioHttpResponse.owned(request.uri(), 200, Map.of(), new ByteArrayInputStream(image), cancellation);
        }, ALLOW_ALL, token, AudioResolveLimits.DEFAULT);
        try (var ignored = CoverCacheLoader.openResolved(() -> cache, token -> Optional.of(IMAGE),
                new AudioCancellation(), contexts).orElseThrow()) {
        }
        AudioNetworkPolicy blocked = uri -> {
            throw new RadioTransportException(RadioFailure.Code.BLOCKED_ADDRESS, false, "blocked", null);
        };
        Function<AudioCancellation, AudioResolveContext> blockedContexts = token -> new AudioResolveContext(
                (request, cancellation) -> { throw new AssertionError("Blocked destination reached HTTP"); },
                blocked, token, AudioResolveLimits.DEFAULT);
        assertEquals(RadioFailure.Code.BLOCKED_ADDRESS, assertThrows(RadioTransportException.class,
                () -> CoverCacheLoader.openResolved(() -> cache, token -> Optional.of(IMAGE),
                        new AudioCancellation(), blockedContexts)).code());
        assertEquals(1, downloads.get());
    }

    private static byte[] png() throws IOException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        ImageIO.write(new BufferedImage(16, 16, BufferedImage.TYPE_INT_ARGB), "png", bytes);
        return bytes.toByteArray();
    }
}
