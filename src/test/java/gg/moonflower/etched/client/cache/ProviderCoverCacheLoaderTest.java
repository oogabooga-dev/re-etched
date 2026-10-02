package gg.moonflower.etched.client.cache;

import gg.moonflower.etched.client.radio.source.AudioResolveContext;
import gg.moonflower.etched.client.radio.source.AudioResolveLimits;
import gg.moonflower.etched.common.audio.AudioCancellation;
import gg.moonflower.etched.common.audio.RadioFailure;
import gg.moonflower.etched.common.audio.net.AudioHttpTransport;
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
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;

import static org.junit.jupiter.api.Assertions.*;

class ProviderCoverCacheLoaderTest {

    private static final URI BANDCAMP = URI.create("https://artist.bandcamp.com/album/test");
    private static final URI SOUNDCLOUD = URI.create("https://soundcloud.com/artist/track");
    private static final URI BANDCAMP_IMAGE = URI.create("https://f4.bcbits.com/img/a123_1.jpg");
    private static final URI SOUNDCLOUD_IMAGE = URI.create("https://images.example/cover.png");
    private static final AudioNetworkPolicy ALLOW_ALL = uri -> {};

    @TempDir
    Path temporary;

    @Test
    void bothProvidersResolveMetadataAndReuseOnlyImageBytesWithIndependentLeases() throws Exception {
        byte[] png = png();
        for (URI provider : List.of(BANDCAMP, SOUNDCLOUD)) {
            AtomicInteger images = new AtomicInteger();
            AtomicInteger metadata = new AtomicInteger();
            AtomicInteger closed = new AtomicInteger();
            var contexts = contexts(transport(provider, true, png, images, metadata, closed), ALLOW_ALL);
            var cache = new BoundedMediaCache(temporary.resolve(provider.getHost()));
            try (var first = ProviderCoverCacheLoader.open(cache, provider, new AudioCancellation(), contexts, null).orElseThrow();
                 var second = ProviderCoverCacheLoader.open(cache, provider, new AudioCancellation(), contexts, null).orElseThrow()) {
                assertEquals(png[0] & 0xFF, first.body().read());
                assertArrayEquals(png, second.body().readAllBytes());
            }
            assertEquals(1, images.get());
            assertEquals(provider.equals(BANDCAMP) ? 2 : 6, metadata.get());
            assertEquals(images.get() + metadata.get(), closed.get());
        }
    }

    @Test
    void missingArtworkDoesNotOpenAnImageAndPreCancellationDoesNotStartMetadata() throws Exception {
        for (URI provider : List.of(BANDCAMP, SOUNDCLOUD)) {
            AtomicInteger images = new AtomicInteger();
            AtomicInteger metadata = new AtomicInteger();
            AtomicInteger closed = new AtomicInteger();
            var contexts = contexts(transport(provider, false, png(), images, metadata, closed), ALLOW_ALL);
            var cache = new BoundedMediaCache(temporary.resolve(provider.getHost()));
            AudioCancellation cancelled = new AudioCancellation();
            cancelled.cancel();
            assertThrows(CancellationException.class,
                    () -> ProviderCoverCacheLoader.open(cache, provider, cancelled, contexts, null));
            assertEquals(0, metadata.get());
            assertTrue(ProviderCoverCacheLoader.open(cache, provider, new AudioCancellation(), contexts, null).isEmpty());
            assertEquals(0, images.get());
            assertEquals(metadata.get(), closed.get());
        }
    }

    @Test
    void blockedOrCancelledMetadataDestinationsCannotStartTheImageStage() throws Exception {
        for (boolean cancel : new boolean[]{false, true}) {
            for (URI provider : List.of(BANDCAMP, SOUNDCLOUD)) {
                AtomicInteger images = new AtomicInteger();
                AtomicInteger metadata = new AtomicInteger();
                AtomicInteger closed = new AtomicInteger();
                AudioCancellation cancellation = new AudioCancellation();
                AudioNetworkPolicy policy = uri -> {
                    if (uri.equals(BANDCAMP_IMAGE) || uri.equals(SOUNDCLOUD_IMAGE)) {
                        assertEquals(metadata.get(), closed.get());
                        if (cancel) {
                            cancellation.cancel();
                        } else {
                            throw new RadioTransportException(RadioFailure.Code.BLOCKED_ADDRESS, false, "blocked", null);
                        }
                    }
                };
                var cache = new BoundedMediaCache(temporary.resolve(provider.getHost() + cancel));
                var contexts = contexts(transport(provider, true, png(), images, metadata, closed), policy);
                if (cancel) {
                    assertThrows(CancellationException.class,
                            () -> ProviderCoverCacheLoader.open(cache, provider, cancellation, contexts, null));
                } else {
                    assertThrows(RadioTransportException.class,
                            () -> ProviderCoverCacheLoader.open(cache, provider, cancellation, contexts, null));
                }
                assertEquals(0, images.get());
            }
        }
    }

    @Test
    void cachedImageStillNeedsDestinationPolicyApproval() throws Exception {
        var cache = new BoundedMediaCache(temporary.resolve("v5"));
        AtomicInteger images = new AtomicInteger();
        AtomicInteger metadata = new AtomicInteger();
        AtomicInteger closed = new AtomicInteger();
        var transport = transport(BANDCAMP, true, png(), images, metadata, closed);
        try (var ignored = ProviderCoverCacheLoader.open(cache, BANDCAMP, new AudioCancellation(),
                contexts(transport, ALLOW_ALL), null).orElseThrow()) {
        }
        AtomicInteger checks = new AtomicInteger();
        AudioNetworkPolicy policy = uri -> {
            if (uri.equals(BANDCAMP_IMAGE) && checks.incrementAndGet() == 2) {
                throw new RadioTransportException(RadioFailure.Code.BLOCKED_ADDRESS, false, "blocked cache hit", null);
            }
        };
        assertThrows(RadioTransportException.class, () -> ProviderCoverCacheLoader.open(cache, BANDCAMP,
                new AudioCancellation(), contexts(transport, policy), null));
        assertEquals(1, images.get());
        assertEquals(2, checks.get());
    }

    @Test
    void cancellationDuringImageReadRetiresTheCacheDownload() throws Exception {
        CountDownLatch started = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        AudioCancellation cancellation = new AudioCancellation();
        try (TestHttpServer server = new TestHttpServer()) {
            server.handle("/cover", exchange -> {
                exchange.sendResponseHeaders(200, 0);
                exchange.getResponseBody().write(new byte[]{(byte) 137, 'P', 'N', 'G', 13, 10, 26, 10});
                exchange.getResponseBody().flush();
                started.countDown();
                try {
                    release.await(5, TimeUnit.SECONDS);
                } catch (InterruptedException exception) {
                    Thread.currentThread().interrupt();
                } finally {
                    exchange.close();
                }
            });
            var realTransport = new RadioHttpTransportImpl(Proxy.NO_PROXY, ALLOW_ALL,
                    Duration.ofSeconds(1), Duration.ofMillis(500), 5);
            AudioHttpTransport transport = (request, token) -> {
                if (request.uri().equals(server.uri("/cover"))) {
                    return realTransport.execute(request, token);
                }
                String body = request.uri().getPath().equals("/") ? "<script src='/app.js'></script>"
                        : request.uri().getPath().equals("/app.js") ? "client_id:'test'"
                        : "{\"kind\":\"track\",\"streamable\":true,\"artwork_url\":\"" + server.uri("/cover") + "\"}";
                return TestAudioHttpResponse.owned(request.uri(), 200, Map.of(),
                        new ByteArrayInputStream(body.getBytes(StandardCharsets.UTF_8)), token);
            };
            var cache = new BoundedMediaCache(temporary.resolve("v5"));
            CompletableFuture<?> request = CompletableFuture.runAsync(() -> {
                try {
                    var cover = ProviderCoverCacheLoader.open(cache, SOUNDCLOUD, cancellation, contexts(transport, ALLOW_ALL), null);
                    if (cover.isPresent()) {
                        cover.get().close();
                    }
                } catch (IOException exception) {
                    throw new CompletionException(exception);
                }
            });
            try {
                assertTrue(started.await(2, TimeUnit.SECONDS));
                cancellation.cancel();
                assertInstanceOf(CancellationException.class, assertThrows(ExecutionException.class,
                        () -> request.get(2, TimeUnit.SECONDS)).getCause());
            } finally {
                release.countDown();
            }
        }
    }

    private static Function<AudioCancellation, AudioResolveContext> contexts(AudioHttpTransport transport,
                                                                            AudioNetworkPolicy policy) {
        return token -> new AudioResolveContext(transport, policy, token, AudioResolveLimits.DEFAULT);
    }

    private static AudioHttpTransport transport(URI provider, boolean artwork, byte[] png, AtomicInteger images,
                                                AtomicInteger metadata, AtomicInteger closed) {
        return (request, token) -> {
            byte[] body;
            if (request.uri().equals(BANDCAMP_IMAGE) || request.uri().equals(SOUNDCLOUD_IMAGE)) {
                images.incrementAndGet();
                body = png;
            } else {
                metadata.incrementAndGet();
                String text;
                if (provider.equals(BANDCAMP)) {
                    assertEquals(BANDCAMP, request.uri());
                    text = "<div data-tralbum='{\"current\":{\"type\":\"album\""
                            + (artwork ? ",\"art_id\":123" : "") + "}}'></div>";
                } else if (request.uri().getPath().equals("/")) {
                    text = "<script src='/app.js'></script>";
                } else if (request.uri().getPath().equals("/app.js")) {
                    text = "client_id:'test'";
                } else {
                    assertEquals("/resolve", request.uri().getPath());
                    text = "{\"kind\":\"track\",\"streamable\":true"
                            + (artwork ? ",\"artwork_url\":\"" + SOUNDCLOUD_IMAGE + "\"" : "") + "}";
                }
                body = text.getBytes(StandardCharsets.UTF_8);
            }
            var stream = new ByteArrayInputStream(body) {
                @Override
                public void close() {
                    closed.incrementAndGet();
                }
            };
            return TestAudioHttpResponse.owned(request.uri(), 200, Map.of(), stream, token);
        };
    }

    private static byte[] png() throws IOException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        ImageIO.write(new BufferedImage(16, 16, BufferedImage.TYPE_INT_ARGB), "png", bytes);
        return bytes.toByteArray();
    }
}
