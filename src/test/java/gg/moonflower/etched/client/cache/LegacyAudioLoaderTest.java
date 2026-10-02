package gg.moonflower.etched.client.cache;

import com.sun.net.httpserver.HttpServer;
import gg.moonflower.etched.api.util.StreamingInputStream;
import gg.moonflower.etched.common.audio.AudioCancellation;
import gg.moonflower.etched.common.audio.net.AudioNetworkPolicy;
import gg.moonflower.etched.common.audio.net.RadioHttpTransportImpl;
import gg.moonflower.etched.common.audio.net.TestAudioHttpResponse;
import gg.moonflower.etched.client.radio.source.AudioResolveContext;
import gg.moonflower.etched.client.radio.source.AudioResolveLimits;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.InetSocketAddress;
import java.net.Proxy;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LegacyAudioLoaderTest {

    @TempDir
    Path temporary;

    @Test
    void multipartEofCloseAndLateOpenReleaseTheOwnedTransportResponses() throws Exception {
        Map<URI, AtomicInteger> closes = new ConcurrentHashMap<>();
        Map<URI, AudioCancellation> tokens = new ConcurrentHashMap<>();
        CountDownLatch firstClosed = new CountDownLatch(1);
        URI first = URI.create("https://audio.example/first");
        URI second = URI.create("https://audio.example/second");
        URI third = URI.create("https://audio.example/third");
        Function<AudioCancellation, AudioResolveContext> contexts = token -> new AudioResolveContext(
                (request, cancellation) -> {
                    URI uri = request.uri();
                    tokens.put(uri, cancellation);
                    var closeCount = new AtomicInteger();
                    closes.put(uri, closeCount);
                    InputStream body = new ByteArrayInputStream(new byte[]{42}) {
                        @Override public void close() {
                            closeCount.incrementAndGet();
                            if (uri.equals(first)) {
                                firstClosed.countDown();
                            }
                        }
                    };
                    return TestAudioHttpResponse.owned(uri, 200, Map.of(), body, cancellation);
                }, ignored -> {}, token, AudioResolveLimits.DEFAULT);
        InputStream one = LegacyAudioLoader.stream(first, new AudioCancellation(), contexts);
        var late = new CompletableFuture<InputStream>();
        InputStream three = LegacyAudioLoader.stream(third, new AudioCancellation(), contexts);
        var stream = new StreamingInputStream(new java.net.URL[]{first.toURL(), second.toURL(), third.toURL()},
                index -> switch (index) {
                    case 0 -> CompletableFuture.completedFuture(one);
                    case 1 -> late;
                    default -> CompletableFuture.completedFuture(three);
                });
        try {
            assertEquals(42, stream.read());
            var reader = CompletableFuture.supplyAsync(() -> assertThrows(IOException.class, stream::read));
            assertTrue(firstClosed.await(2, TimeUnit.SECONDS));
            assertTrue(tokens.get(first).isCancelled());
            stream.close();
            reader.get(2, TimeUnit.SECONDS);
            assertTrue(tokens.get(third).isCancelled());
            late.complete(LegacyAudioLoader.stream(second, new AudioCancellation(), contexts));
            assertTrue(tokens.get(second).isCancelled());
            assertEquals(3, closes.size());
            assertTrue(closes.values().stream().allMatch(count -> count.get() == 1));
        } finally {
            stream.close();
        }
    }

    @Test
    void finiteUsesOneSecureCachedDownloadAndLiveBypassesDisk() throws Exception {
        byte[] mp3;
        try (var fixture = this.getClass().getResourceAsStream(
                "/gg/moonflower/etched/client/radio/audio/mono.mp3")) {
            mp3 = fixture.readAllBytes();
        }
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        AtomicInteger requests = new AtomicInteger();
        server.createContext("/song", exchange -> {
            requests.incrementAndGet();
            exchange.sendResponseHeaders(200, mp3.length);
            try (exchange; var body = exchange.getResponseBody()) {
                body.write(mp3);
            }
        });
        server.start();
        try {
            URI uri = URI.create("http://127.0.0.1:" + server.getAddress().getPort() + "/song");
            BoundedMediaCache cache = new BoundedMediaCache(temporary.resolve("v5"));
            AudioNetworkPolicy local = ignored -> {
            };
            Function<AudioCancellation, AudioResolveContext> contexts = token ->
                    new AudioResolveContext(new RadioHttpTransportImpl(Proxy.NO_PROXY, local,
                            Duration.ofSeconds(2), Duration.ofSeconds(2), 2), local, token,
                            AudioResolveLimits.DEFAULT);
            try (var first = LegacyAudioLoader.file(cache, uri, contexts);
                 var second = LegacyAudioLoader.file(cache, uri, contexts)) {
                assertEquals(mp3[0] & 0xFF, first.read());
                assertArrayEquals(mp3, second.readAllBytes());
            }
            assertEquals(1, requests.get());
            try (var live = LegacyAudioLoader.stream(uri, new AudioCancellation(), contexts)) {
                assertEquals(mp3[0] & 0xFF, live.read());
            }
            assertEquals(2, requests.get());
            try (var files = Files.list(temporary.resolve("v5/audio"))) {
                assertEquals(1, files.count());
            }
        } finally {
            server.stop(0);
        }
    }

    @Test
    void legacyWavFileRemainsStreamableWithoutCaching() throws Exception {
        byte[] wav = "RIFF0000WAVE".getBytes(java.nio.charset.StandardCharsets.US_ASCII);
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        AtomicInteger requests = new AtomicInteger();
        server.createContext("/wave", exchange -> {
            requests.incrementAndGet();
            exchange.sendResponseHeaders(200, wav.length);
            try (exchange; var body = exchange.getResponseBody()) {
                body.write(wav);
            }
        });
        server.start();
        try {
            URI uri = URI.create("http://127.0.0.1:" + server.getAddress().getPort() + "/wave");
            BoundedMediaCache cache = new BoundedMediaCache(temporary.resolve("v5"));
            AudioNetworkPolicy local = ignored -> {
            };
            Function<AudioCancellation, AudioResolveContext> contexts = token ->
                    new AudioResolveContext(new RadioHttpTransportImpl(Proxy.NO_PROXY, local,
                            Duration.ofSeconds(2), Duration.ofSeconds(2), 2), local, token,
                            AudioResolveLimits.DEFAULT);

            try (var stream = LegacyAudioLoader.file(cache, uri, contexts)) {
                assertArrayEquals(wav, stream.readAllBytes());
            }
            assertEquals(2, requests.get());
            try (var files = Files.list(temporary.resolve("v5/audio"))) {
                assertEquals(0, files.count());
            }
        } finally {
            server.stop(0);
        }
    }
}
