package gg.moonflower.etched.client.cache;

import com.sun.net.httpserver.HttpServer;
import gg.moonflower.etched.client.radio.AudioCancellation;
import gg.moonflower.etched.client.radio.net.AudioNetworkPolicy;
import gg.moonflower.etched.client.radio.net.RadioHttpTransportImpl;
import gg.moonflower.etched.client.radio.source.AudioResolveContext;
import gg.moonflower.etched.client.radio.source.AudioResolveLimits;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.Proxy;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;

class LegacyAudioLoaderTest {

    @TempDir
    Path temporary;

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
