package gg.moonflower.etched.client.cache;

import com.sun.net.httpserver.HttpServer;
import gg.moonflower.etched.client.radio.AudioCancellation;
import gg.moonflower.etched.client.radio.RadioFailure;
import gg.moonflower.etched.client.radio.net.AudioNetworkPolicy;
import gg.moonflower.etched.client.radio.net.RadioHttpTransportImpl;
import gg.moonflower.etched.client.radio.net.RadioTransportException;
import gg.moonflower.etched.client.radio.source.AudioResolveContext;
import gg.moonflower.etched.client.radio.source.AudioResolveLimits;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.Proxy;
import java.net.URI;
import java.nio.file.Path;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class CoverCacheLoaderTest {

    @TempDir
    Path temporary;

    @Test
    void downloadsCoverOnceAndOpensIndependentStreams() throws Exception {
        byte[] image = png(16, 16);
        AtomicInteger requests = new AtomicInteger();
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/cover", exchange -> {
            requests.incrementAndGet();
            exchange.getResponseHeaders().set("Content-Type", "image/png");
            exchange.sendResponseHeaders(200, image.length);
            try (exchange; var response = exchange.getResponseBody()) {
                response.write(image);
            }
        });
        server.start();
        try {
            BoundedMediaCache cache = new BoundedMediaCache(temporary.resolve("v5"));
            URI uri = URI.create("http://127.0.0.1:" + server.getAddress().getPort() + "/cover");
            AudioNetworkPolicy allowLocal = ignored -> {
            };
            var contexts = contexts(allowLocal);
            try (var first = CoverCacheLoader.open(cache, uri, new AudioCancellation(), contexts);
                 var second = CoverCacheLoader.open(cache, uri, new AudioCancellation(), contexts)) {
                assertEquals(image[0] & 0xFF, first.body().read());
                assertArrayEquals(image, second.body().readAllBytes());
            }
            assertEquals(1, requests.get());
        } finally {
            server.stop(0);
        }
    }

    @Test
    void rejectsBlockedRedirectWithoutCachingIt() throws Exception {
        AtomicInteger forbidden = new AtomicInteger();
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/redirect", exchange -> {
            exchange.getResponseHeaders().set("Location", "/forbidden");
            exchange.sendResponseHeaders(302, -1);
            exchange.close();
        });
        server.createContext("/forbidden", exchange -> {
            forbidden.incrementAndGet();
            exchange.sendResponseHeaders(200, -1);
            exchange.close();
        });
        server.start();
        try {
            BoundedMediaCache cache = new BoundedMediaCache(temporary.resolve("v5"));
            URI uri = URI.create("http://127.0.0.1:" + server.getAddress().getPort() + "/redirect");
            AudioNetworkPolicy blockRedirect = requested -> {
                if (requested.getPath().equals("/forbidden")) {
                    throw new RadioTransportException(RadioFailure.Code.BLOCKED_ADDRESS,
                            false, "Blocked redirected destination", null);
                }
            };
            assertThrows(IOException.class, () -> CoverCacheLoader.open(cache, uri,
                    new AudioCancellation(), contexts(blockRedirect)));
            assertEquals(0, forbidden.get());
        } finally {
            server.stop(0);
        }
    }

    private static Function<AudioCancellation, AudioResolveContext> contexts(AudioNetworkPolicy policy) {
        return token -> new AudioResolveContext(new RadioHttpTransportImpl(Proxy.NO_PROXY, policy,
                Duration.ofSeconds(2), Duration.ofSeconds(2), 2), policy, token, AudioResolveLimits.DEFAULT);
    }

    private static byte[] png(int width, int height) throws IOException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        ImageIO.write(new BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB), "png", bytes);
        return bytes.toByteArray();
    }
}
