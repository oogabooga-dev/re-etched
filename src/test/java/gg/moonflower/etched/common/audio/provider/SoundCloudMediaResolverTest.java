package gg.moonflower.etched.common.audio.provider;

import gg.moonflower.etched.common.audio.AudioCancellation;
import gg.moonflower.etched.common.audio.RadioFailure;
import gg.moonflower.etched.common.audio.net.AudioNetworkPolicy;
import gg.moonflower.etched.common.audio.net.RadioHttpTransportImpl;
import gg.moonflower.etched.common.audio.net.RadioTransportException;
import gg.moonflower.etched.common.audio.net.TestAudioHttpResponse;
import gg.moonflower.etched.common.audio.net.TestHttpServer;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.net.Proxy;
import java.net.URI;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.IntUnaryOperator;

import static org.junit.jupiter.api.Assertions.*;

class SoundCloudMediaResolverTest {

    private static final URI INPUT = URI.create("https://soundcloud.com/artist/track?x=a&y=b");
    private static final URI HOME = URI.create("https://soundcloud.com/");
    private static final URI API = URI.create("https://api-v2.soundcloud.com/resolve");
    private static final URI MEDIA = URI.create("https://media.example/track.mp3");
    private static final AudioNetworkPolicy ALLOW_ALL = uri -> {};
    private static final String TRACK = """
            {"kind":"track","streamable":true,"track_authorization":"token &/?", "media":{"transcodings":[
              {"url":"https://api-v2.soundcloud.com/hls", "format":{"protocol":"hls","mime_type":"audio/mpeg"}},
              {"url":"https://api-v2.soundcloud.com/transcoding", "format":{"protocol":"progressive","mime_type":"audio/mpeg"}},
              {"url":"https://api-v2.soundcloud.com/second", "format":{"protocol":"progressive","mime_type":"audio/mpeg"}}]}}
            """;

    @Test
    void selectsFirstProgressiveMp3WithoutOpeningHlsOrMediaAndEncodesAuthorization() throws Exception {
        try (Fixture fixture = new Fixture()) {
            var resolver = fixture.resolver();
            assertEquals(List.of(MEDIA), resolver.resolveMediaUrls(INPUT, new AudioCancellation()));
            assertEquals(List.of("/", "/app.js", "/resolve", "/transcoding"), fixture.paths());
            URI page = fixture.requests.get(2);
            assertEquals(INPUT.toString(), query(page, "url"));
            assertEquals("client-1", query(page, "client_id"));
            URI transcoding = fixture.requests.get(3);
            assertEquals("token &/?", query(transcoding, "track_authorization"));
            assertEquals("client-1", query(transcoding, "client_id"));
            assertEquals(List.of(INPUT, MEDIA), fixture.checked);
            resolver.resolveMediaUrls(INPUT, new AudioCancellation());
            assertEquals(2, fixture.discoveries);
            assertEquals("client-2", query(fixture.requests.get(7), "client_id"));
        }
    }

    @Test
    void hlsOnlyAndNonMpegTranscodingsDoNotFallBackOrOpenTheirUrls() {
        for (String protocol : List.of("hls", "progressive", "dash")) {
            try (Fixture fixture = new Fixture()) {
                fixture.track = "{\"kind\":\"track\",\"streamable\":true,\"media\":{\"transcodings\":["
                        + "{\"url\":\"https://api-v2.soundcloud.com/unsupported\",\"format\":{\"protocol\":\""
                        + protocol + "\",\"mime_type\":\"audio/ogg\"}}]}}";
                RadioTransportException error = assertThrows(RadioTransportException.class,
                        () -> fixture.resolver().resolveMediaUrls(INPUT, new AudioCancellation()));
                assertEquals(protocol.equals("hls") ? RadioFailure.Code.UNSUPPORTED_HLS
                        : RadioFailure.Code.UNSUPPORTED_AUDIO, error.code());
                assertEquals(List.of("/", "/app.js", "/resolve"), fixture.paths());
            }
        }
    }

    @Test
    void rejectsMalformedOrUnavailableTracksAndUnsafeEndpointsBeforeUsingThem() {
        for (String json : List.of("{}", "not json", TRACK.replace("\"track\"", "\"playlist\""),
                TRACK.replace("true", "false"), TRACK.replace("\"transcodings\":[", "\"transcodings\":null,\"ignored\":["),
                TRACK.replace("https://api-v2.soundcloud.com/transcoding", "file:///etc/passwd"),
                TRACK.replace("https://api-v2.soundcloud.com/transcoding", "https://user@api-v2.soundcloud.com/transcoding"),
                TRACK.replace("https://api-v2.soundcloud.com/transcoding", "../relative"),
                TRACK.replace("https://api-v2.soundcloud.com/transcoding", "https://other.example/transcoding"),
                TRACK.replace("https://api-v2.soundcloud.com/transcoding", "http://api-v2.soundcloud.com/transcoding"))) {
            try (Fixture fixture = new Fixture()) {
                fixture.track = json;
                assertThrows(IOException.class, () -> fixture.resolver().resolveMediaUrls(INPUT, new AudioCancellation()));
                assertEquals(List.of("/", "/app.js", "/resolve"), fixture.paths());
            }
        }
    }

    @Test
    void rejectsUnsafeMalformedOrBlockedMediaAfterReleasingTranscodingResponse() {
        for (String value : List.of("file:///etc/passwd", "https://user@media.example/file", "../relative", "bad url", "blocked")) {
            try (Fixture fixture = new Fixture()) {
                fixture.transcoding = value.equals("blocked") ? "{\"url\":\"" + MEDIA + "\"}"
                        : "{\"url\":\"" + value + "\"}";
                if (value.equals("blocked")) {
                    fixture.policy = uri -> {
                        if (uri.equals(MEDIA)) {
                            throw new RadioTransportException(RadioFailure.Code.BLOCKED_ADDRESS, false, "blocked", null);
                        }
                    };
                }
                assertThrows(IOException.class, () -> fixture.resolver().resolveMediaUrls(INPUT, new AudioCancellation()));
                assertEquals(4, fixture.requests.size());
            }
        }
    }

    @Test
    void refreshIsSharedAcrossPageAndTranscodingRequestsAndNeverRepeats() throws Exception {
        try (Fixture fixture = new Fixture()) {
            fixture.transcodingStatus = attempt -> attempt == 1 ? 403 : 200;
            assertEquals(List.of(MEDIA), fixture.resolver().resolveMediaUrls(INPUT, new AudioCancellation()));
            assertEquals(2, fixture.discoveries);
            assertEquals(2, fixture.transcodingAttempts);
            assertEquals("client-2", query(fixture.requests.get(6), "client_id"));
        }
        try (Fixture fixture = new Fixture()) {
            fixture.resolveStatus = attempt -> attempt == 1 ? 401 : 200;
            fixture.transcodingStatus = attempt -> 403;
            assertEquals(RadioFailure.Code.HTTP_STATUS, assertThrows(RadioTransportException.class,
                    () -> fixture.resolver().resolveMediaUrls(INPUT, new AudioCancellation())).code());
            assertEquals(2, fixture.discoveries);
            assertEquals(2, fixture.resolveAttempts);
            assertEquals(1, fixture.transcodingAttempts);
        }
        try (Fixture fixture = new Fixture()) {
            fixture.transcodingStatus = attempt -> 401;
            assertThrows(IOException.class, () -> fixture.resolver().resolveMediaUrls(INPUT, new AudioCancellation()));
            assertEquals(2, fixture.discoveries);
            assertEquals(2, fixture.transcodingAttempts);
        }
    }

    @Test
    void mediaRequestsUseTheSameEntryBodyFieldAndRetryStepBudgets() {
        for (int scenario = 0; scenario < 4; scenario++) {
            try (Fixture fixture = new Fixture()) {
                var limits = new SoundCloudMetadataResolver.Limits(4096, scenario == 0 ? 2 : 100,
                        scenario == 2 ? 128 : 8192, scenario == 3 ? 4 : 128, 10, 5);
                if (scenario == 1) {
                    fixture.transcoding = "{\"url\":\"" + "x".repeat(4096) + "\"}";
                } else if (scenario == 2) {
                    fixture.track = TRACK.replace("token &/?", "x".repeat(129));
                } else if (scenario == 3) {
                    fixture.transcodingStatus = attempt -> 401;
                }
                assertEquals(RadioFailure.Code.RESOURCE_LIMIT, assertThrows(RadioTransportException.class,
                        () -> fixture.resolver(limits).resolveMediaUrls(INPUT, new AudioCancellation())).code());
                assertEquals(scenario == 0 || scenario == 2 ? 3 : 4, fixture.requests.size());
            }
        }
    }

    @Test
    void precancellationAndCancellationAtDestinationValidationDoNotReturnMedia() {
        try (Fixture fixture = new Fixture()) {
            AudioCancellation cancellation = new AudioCancellation();
            cancellation.cancel();
            assertThrows(CancellationException.class, () -> fixture.resolver().resolveMediaUrls(INPUT, cancellation));
            assertTrue(fixture.requests.isEmpty());
        }
        try (Fixture fixture = new Fixture()) {
            AudioCancellation cancellation = new AudioCancellation();
            fixture.policy = uri -> {
                if (uri.equals(MEDIA)) {
                    cancellation.cancel();
                }
            };
            assertThrows(CancellationException.class, () -> fixture.resolver().resolveMediaUrls(INPUT, cancellation));
            assertEquals(4, fixture.requests.size());
        }
    }

    @Test
    void cancellationClosesAStalledTranscodingBodyAndDoesNotPoisonTheNextOperation() throws Exception {
        CountDownLatch started = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        AudioCancellation cancellation = new AudioCancellation();
        AtomicInteger transcodings = new AtomicInteger();
        try (TestHttpServer server = new TestHttpServer()) {
            server.handle("/transcoding", exchange -> {
                if (transcodings.getAndIncrement() > 0) {
                    respond(exchange, "{\"url\":\"" + MEDIA + "\"}");
                    return;
                }
                exchange.sendResponseHeaders(200, 0);
                exchange.getResponseBody().write('{');
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
            var transport = new RadioHttpTransportImpl(Proxy.NO_PROXY, ALLOW_ALL,
                    Duration.ofSeconds(1), Duration.ofMillis(500), 5);
            server.handle("/", exchange -> respond(exchange, "<script src='/app.js'></script>"));
            server.handle("/app.js", exchange -> respond(exchange, "client_id:'test'"));
            server.handle("/resolve", exchange -> respond(exchange,
                    TRACK.replace("https://api-v2.soundcloud.com", server.uri("").toString())));
            var resolver = new SoundCloudMetadataResolver(transport, ALLOW_ALL, server.uri("/"), server.uri("/resolve"),
                    SoundCloudMetadataResolver.Limits.DEFAULT);
            var request = CompletableFuture.runAsync(() -> {
                try {
                    resolver.resolveMediaUrls(INPUT, cancellation);
                } catch (IOException exception) {
                    throw new CompletionException(exception);
                }
            });
            try {
                assertTrue(started.await(2, TimeUnit.SECONDS));
                cancellation.cancel();
                assertInstanceOf(CancellationException.class, assertThrows(ExecutionException.class,
                        () -> request.get(2, TimeUnit.SECONDS)).getCause());
                assertEquals(List.of(MEDIA), resolver.resolveMediaUrls(INPUT, new AudioCancellation()));
            } finally {
                release.countDown();
            }
        }
    }

    @Test
    void transcodingRedirectsCannotSubstituteAnotherApiOrigin() throws Exception {
        try (TestHttpServer server = new TestHttpServer(); TestHttpServer outside = new TestHttpServer()) {
            server.handle("/", exchange -> respond(exchange, "<script src='/app.js'></script>"));
            server.handle("/app.js", exchange -> respond(exchange, "client_id:'test'"));
            server.handle("/resolve", exchange -> respond(exchange,
                    TRACK.replace("https://api-v2.soundcloud.com", server.uri("").toString())));
            server.handle("/transcoding", exchange -> {
                exchange.getResponseHeaders().set("Location", outside.uri("/substitute").toString());
                exchange.sendResponseHeaders(302, -1);
                exchange.close();
            });
            outside.handle("/substitute", exchange -> respond(exchange, "{\"url\":\"" + MEDIA + "\"}"));
            var resolver = new SoundCloudMetadataResolver(new RadioHttpTransportImpl(Proxy.NO_PROXY, ALLOW_ALL,
                    Duration.ofSeconds(1), Duration.ofSeconds(1), 5), ALLOW_ALL, server.uri("/"), server.uri("/resolve"),
                    SoundCloudMetadataResolver.Limits.DEFAULT);
            assertEquals(RadioFailure.Code.BLOCKED_ADDRESS, assertThrows(RadioTransportException.class,
                    () -> resolver.resolveMediaUrls(INPUT, new AudioCancellation())).code());
        }
    }

    private static String query(URI uri, String name) {
        for (String part : uri.getRawQuery().split("&")) {
            String[] pieces = part.split("=", 2);
            if (URLDecoder.decode(pieces[0], StandardCharsets.UTF_8).equals(name)) {
                return URLDecoder.decode(pieces[1], StandardCharsets.UTF_8);
            }
        }
        return null;
    }

    private static void respond(com.sun.net.httpserver.HttpExchange exchange, String text) throws IOException {
        byte[] body = text.getBytes(StandardCharsets.UTF_8);
        exchange.sendResponseHeaders(200, body.length);
        exchange.getResponseBody().write(body);
        exchange.close();
    }

    private static final class Fixture implements AutoCloseable {
        private String track = TRACK;
        private String transcoding = "{\"url\":\"" + MEDIA + "\"}";
        private IntUnaryOperator resolveStatus = attempt -> 200;
        private IntUnaryOperator transcodingStatus = attempt -> 200;
        private AudioNetworkPolicy policy = ALLOW_ALL;
        private final List<URI> requests = new ArrayList<>();
        private final List<URI> checked = new ArrayList<>();
        private int closed;
        private int discoveries;
        private int resolveAttempts;
        private int transcodingAttempts;

        private SoundCloudMetadataResolver resolver() {
            return resolver(SoundCloudMetadataResolver.Limits.DEFAULT);
        }

        private SoundCloudMetadataResolver resolver(SoundCloudMetadataResolver.Limits limits) {
            return new SoundCloudMetadataResolver((request, cancellation) -> {
                assertEquals(requests.size(), closed, "Previous response was not released");
                requests.add(request.uri());
                int status = 200;
                String body;
                switch (request.uri().getPath()) {
                    case "/" -> { discoveries++; body = "<script src='/app.js'></script>"; }
                    case "/app.js" -> body = "client_id:'client-" + discoveries + "'";
                    case "/resolve" -> { status = resolveStatus.applyAsInt(++resolveAttempts); body = track; }
                    case "/transcoding" -> { status = transcodingStatus.applyAsInt(++transcodingAttempts); body = transcoding; }
                    default -> throw new AssertionError("Unexpected request " + request.uri());
                }
                var stream = new ByteArrayInputStream(body.getBytes(StandardCharsets.UTF_8)) {
                    @Override public void close() { closed++; }
                };
                return TestAudioHttpResponse.owned(request.uri(), status, Map.of(), stream, cancellation);
            }, uri -> {
                assertEquals(requests.size(), closed, "Destination checked with a response still open");
                checked.add(uri);
                policy.check(uri);
            }, HOME, API, limits);
        }

        private List<String> paths() {
            return requests.stream().map(URI::getPath).toList();
        }

        @Override public void close() { assertEquals(requests.size(), closed, "Leaked response"); }
    }
}
