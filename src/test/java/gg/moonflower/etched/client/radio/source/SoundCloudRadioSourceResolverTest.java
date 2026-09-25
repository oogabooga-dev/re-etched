package gg.moonflower.etched.client.radio.source;

import com.sun.net.httpserver.HttpExchange;
import gg.moonflower.etched.client.radio.AudioCancellation;
import gg.moonflower.etched.client.radio.RadioFailure;
import gg.moonflower.etched.client.radio.PlaybackSession;
import gg.moonflower.etched.client.radio.net.AudioNetworkPolicy;
import gg.moonflower.etched.client.radio.net.RadioHttpTransportImpl;
import gg.moonflower.etched.client.radio.net.TestHttpServer;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.Proxy;
import java.net.URI;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.CancellationException;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SoundCloudRadioSourceResolverTest {

    private static final AudioNetworkPolicy ALLOW_TEST_SERVER = uri -> {
    };
    private static final Duration TIMEOUT = Duration.ofSeconds(2);
    private static final URI TRACK = URI.create("https://soundcloud.com/artist/track");

    @Test
    void supportsOnlyRealHttpSoundCloudHostsWithoutUserinfo() {
        SoundCloudRadioSourceResolver resolver = new SoundCloudRadioSourceResolver();

        assertTrue(resolver.supports(URI.create("https://soundcloud.com/artist/track")));
        assertTrue(resolver.supports(URI.create("http://www.soundcloud.com/artist/track")));
        assertTrue(resolver.supports(URI.create("https://m.soundcloud.com/artist/track")));
        assertFalse(resolver.supports(URI.create("https://evilsoundcloud.com/track")));
        assertFalse(resolver.supports(URI.create("https://soundcloud.com.evil.example/track")));
        assertFalse(resolver.supports(URI.create("https://soundcloud.com@evil.example/track")));
        assertFalse(resolver.supports(URI.create("https://user@soundcloud.com/track")));
        assertFalse(resolver.supports(URI.create("ftp://soundcloud.com/track")));
        assertFalse(resolver.supports(URI.create("/soundcloud.com/track")));
    }

    @Test
    void prefersProgressiveMp3EvenWhenHlsAppearsFirst() throws Exception {
        AtomicInteger hlsRequests = new AtomicInteger();
        AtomicInteger progressiveRequests = new AtomicInteger();
        byte[] audio = bytes("ID3-progressive-audio");
        try (Fixture fixture = new Fixture()) {
            fixture.installDiscovery("client-one");
            fixture.server.handle("/resolve", exchange -> respondJson(exchange, 200,
                    trackJson(fixture.server.uri("/hls"), fixture.server.uri("/progressive"))));
            fixture.server.handle("/hls", exchange -> {
                hlsRequests.incrementAndGet();
                respondJson(exchange, 200, "{\"url\":\"" + fixture.server.uri("/hls-media") + "\"}");
            });
            fixture.server.handle("/progressive", exchange -> {
                progressiveRequests.incrementAndGet();
                respondJson(exchange, 200, "{\"url\":\"" + fixture.server.uri("/media.mp3") + "\"}");
            });
            fixture.server.handle("/media.mp3", exchange -> respondAudio(exchange, 200, audio));

            RadioSourceProgram program = fixture.resolver.resolveProgram(TRACK, fixture.context());
            try (RadioResolvedSource source = program.openTrack(0, fixture.context())) {
                assertEquals(RadioSourceProgram.Kind.SERVICE_TRACKS, program.kind());
                assertEquals("Track", program.tracks().get(0).title());
                assertEquals(RadioResolvedSource.Format.MP3, source.format());
                assertArrayEquals(audio, source.body().readAllBytes());
            }

            assertEquals(1, progressiveRequests.get());
            assertEquals(0, hlsRequests.get());
        }
    }

    @Test
    void reportsHlsOnlyTracksWithTypedFailure() throws Exception {
        try (Fixture fixture = new Fixture()) {
            fixture.installDiscovery("client-one");
            fixture.server.handle("/resolve", exchange -> respondJson(exchange, 200,
                    hlsTrackJson(fixture.server.uri("/hls"))));

            RadioSourceException exception = assertThrows(RadioSourceException.class,
                    () -> fixture.resolver.resolveProgram(TRACK, fixture.context()));

            assertEquals(RadioFailure.Code.UNSUPPORTED_HLS, exception.code());
        }
    }

    @Test
    void preservesAlbumOrderSkipsExplicitlyUnavailableTracksAndOpensIndependently()
            throws Exception {
        AtomicInteger firstMediaRequests = new AtomicInteger();
        try (Fixture fixture = new Fixture()) {
            fixture.installDiscovery("client-one");
            fixture.server.handle("/resolve", exchange -> {
                String requested = query(exchange.getRequestURI()).get("url");
                if (requested.endsWith("/album")) {
                    respondJson(exchange, 200, """
                            {"kind":"playlist","is_album":true,"tracks":[
                              {"kind":"track","title":"First","permalink_url":"https://soundcloud.com/a/first"},
                              {"kind":"track","title":"Blocked","streamable":false},
                              {"kind":"track","title":"Second","permalink_url":"https://soundcloud.com/a/second"}
                            ]}
                            """);
                } else if (requested.endsWith("/first")) {
                    respondJson(exchange, 200, progressiveTrackJson("First",
                            fixture.server.uri("/first-transcoding")));
                } else if (requested.endsWith("/second")) {
                    respondJson(exchange, 200, progressiveTrackJson("Second",
                            fixture.server.uri("/second-transcoding")));
                } else {
                    respondJson(exchange, 404, "{}");
                }
            });
            fixture.server.handle("/first-transcoding", exchange -> respondJson(exchange, 200,
                    "{\"url\":\"" + fixture.server.uri("/first.mp3") + "\"}"));
            fixture.server.handle("/second-transcoding", exchange -> respondJson(exchange, 200,
                    "{\"url\":\"" + fixture.server.uri("/second.mp3") + "\"}"));
            fixture.server.handle("/first.mp3", exchange -> {
                int request = firstMediaRequests.incrementAndGet();
                respondAudio(exchange, 200, bytes("ID3-first-" + request));
            });
            fixture.server.handle("/second.mp3", exchange -> respondAudio(exchange, 200,
                    bytes("ID3-second")));

            URI album = URI.create("https://soundcloud.com/a/album");
            RadioSourceProgram program = fixture.resolver.resolveProgram(album, fixture.context());

            assertEquals(2, program.tracks().size());
            assertEquals("First", program.tracks().get(0).title());
            assertEquals("Second", program.tracks().get(1).title());
            try (RadioResolvedSource first = program.openTrack(0, fixture.context());
                 RadioResolvedSource second = program.openTrack(1, fixture.context())) {
                assertArrayEquals(bytes("ID3-first-1"), first.body().readAllBytes());
                assertArrayEquals(bytes("ID3-second"), second.body().readAllBytes());
            }
            try (RadioResolvedSource firstAgain = program.openTrack(0, fixture.context())) {
                assertArrayEquals(bytes("ID3-first-2"), firstAgain.body().readAllBytes());
            }
            assertEquals(2, firstMediaRequests.get());
        }
    }

    @Test
    void doesNotSilentlySkipMalformedAlbumTracks() throws Exception {
        try (Fixture fixture = new Fixture()) {
            fixture.installDiscovery("client-one");
            fixture.server.handle("/resolve", exchange -> respondJson(exchange, 200, """
                    {"kind":"playlist","is_album":true,"tracks":[
                      {"kind":"track","title":"Unknown availability"}
                    ]}
                    """));

            RadioSourceException exception = assertThrows(RadioSourceException.class,
                    () -> fixture.resolver.resolveProgram(
                            URI.create("https://soundcloud.com/a/album"), fixture.context()));

            assertEquals(RadioFailure.Code.UNSUPPORTED_AUDIO, exception.code());
        }
    }

    @Test
    void refreshesCachedClientIdOnlyOnceAfterApiRejection() throws Exception {
        AtomicInteger homepageRequests = new AtomicInteger();
        AtomicInteger scriptRequests = new AtomicInteger();
        AtomicInteger rejectedRequests = new AtomicInteger();
        AtomicInteger acceptedRequests = new AtomicInteger();
        try (Fixture fixture = new Fixture()) {
            fixture.server.handle("/", exchange -> {
                homepageRequests.incrementAndGet();
                respondHtml(exchange, "<script src=\"" + fixture.server.uri("/app.js") + "\"></script>");
            });
            fixture.server.handle("/app.js", exchange -> {
                int request = scriptRequests.incrementAndGet();
                respondJavascript(exchange, "client_id:\"client-" + request + "\"");
            });
            fixture.server.handle("/resolve", exchange -> {
                String id = query(exchange.getRequestURI()).get("client_id");
                if ("client-1".equals(id)) {
                    rejectedRequests.incrementAndGet();
                    respondJson(exchange, 401, "{}");
                } else {
                    acceptedRequests.incrementAndGet();
                    respondJson(exchange, 200,
                            progressiveTrackJson("Track", fixture.server.uri("/transcoding")));
                }
            });
            fixture.server.handle("/transcoding", exchange -> respondJson(exchange, 200,
                    "{\"url\":\"" + fixture.server.uri("/media.mp3") + "\"}"));
            fixture.server.handle("/media.mp3", exchange -> respondAudio(exchange, 200,
                    bytes("ID3-audio")));

            RadioSourceProgram program = fixture.resolver.resolveProgram(TRACK, fixture.context());
            try (RadioResolvedSource ignored = program.openTrack(0, fixture.context())) {
                // Opening uses the refreshed cached ID rather than discovering again.
            }

            assertEquals(2, homepageRequests.get());
            assertEquals(2, scriptRequests.get());
            assertEquals(1, rejectedRequests.get());
            assertEquals(2, acceptedRequests.get());
        }
    }

    @Test
    void reResolvesTrackPageOnceWhenFinalMediaUrlHasExpired() throws Exception {
        AtomicInteger trackResolutions = new AtomicInteger();
        AtomicInteger staleRequests = new AtomicInteger();
        try (Fixture fixture = new Fixture()) {
            fixture.installDiscovery("client-one");
            fixture.server.handle("/resolve", exchange -> {
                int resolution = trackResolutions.incrementAndGet();
                URI transcoding = resolution < 3
                        ? fixture.server.uri("/stale-transcoding")
                        : fixture.server.uri("/fresh-transcoding");
                respondJson(exchange, 200, progressiveTrackJson("Track", transcoding));
            });
            fixture.server.handle("/stale-transcoding", exchange -> respondJson(exchange, 200,
                    "{\"url\":\"" + fixture.server.uri("/stale.mp3") + "\"}"));
            fixture.server.handle("/fresh-transcoding", exchange -> respondJson(exchange, 200,
                    "{\"url\":\"" + fixture.server.uri("/fresh.mp3") + "\"}"));
            fixture.server.handle("/stale.mp3", exchange -> {
                staleRequests.incrementAndGet();
                respondAudio(exchange, 404, bytes("expired"));
            });
            fixture.server.handle("/fresh.mp3", exchange -> respondAudio(exchange, 200,
                    bytes("ID3-fresh")));

            RadioSourceProgram program = fixture.resolver.resolveProgram(TRACK, fixture.context());
            try (RadioResolvedSource source = program.openTrack(0, fixture.context())) {
                assertArrayEquals(bytes("ID3-fresh"), source.body().readAllBytes());
            }

            assertEquals(3, trackResolutions.get());
            assertEquals(1, staleRequests.get());
        }
    }

    @Test
    void doesNotLoopWhenRefreshedFinalMediaUrlAlsoFails() throws Exception {
        AtomicInteger trackResolutions = new AtomicInteger();
        AtomicInteger mediaRequests = new AtomicInteger();
        try (Fixture fixture = new Fixture()) {
            fixture.installDiscovery("client-one");
            fixture.server.handle("/resolve", exchange -> {
                trackResolutions.incrementAndGet();
                respondJson(exchange, 200, progressiveTrackJson("Track",
                        fixture.server.uri("/transcoding")));
            });
            fixture.server.handle("/transcoding", exchange -> respondJson(exchange, 200,
                    "{\"url\":\"" + fixture.server.uri("/expired.mp3") + "\"}"));
            fixture.server.handle("/expired.mp3", exchange -> {
                mediaRequests.incrementAndGet();
                respondAudio(exchange, 404, bytes("expired"));
            });

            RadioSourceProgram program = fixture.resolver.resolveProgram(TRACK, fixture.context());
            RadioSourceException exception = assertThrows(RadioSourceException.class,
                    () -> program.openTrack(0, fixture.context()));

            assertEquals(RadioFailure.Code.HTTP_STATUS, exception.code());
            assertEquals(404, exception.httpStatus());
            assertEquals(3, trackResolutions.get());
            assertEquals(2, mediaRequests.get());
        }
    }

    @Test
    void boundsDiscoveryBodiesAndClosesThemOnFailure() throws Exception {
        AtomicInteger scriptRequests = new AtomicInteger();
        try (Fixture fixture = new Fixture()) {
            fixture.server.handle("/", exchange -> respondHtml(exchange,
                    "x".repeat(257) + "<script src=\"" + fixture.server.uri("/app.js") + "\"></script>"));
            fixture.server.handle("/app.js", exchange -> {
                scriptRequests.incrementAndGet();
                respondJavascript(exchange, "client_id:\"unused\"");
            });
            AudioResolveLimits limits = new AudioResolveLimits(16, 256, 10, 64, 2, 20);

            RadioSourceException exception = assertThrows(RadioSourceException.class,
                    () -> fixture.resolver.resolveProgram(TRACK, fixture.context(limits)));

            assertEquals(RadioFailure.Code.RESOURCE_LIMIT, exception.code());
            assertEquals(0, scriptRequests.get());
        }
    }

    @Test
    void boundsApplicationScriptBodies() throws Exception {
        AtomicInteger apiRequests = new AtomicInteger();
        try (Fixture fixture = new Fixture()) {
            fixture.server.handle("/", exchange -> respondHtml(exchange,
                    "<script src=\"" + fixture.server.uri("/app.js") + "\"></script>"));
            fixture.server.handle("/app.js", exchange -> respondJavascript(exchange,
                    "x".repeat(257)));
            fixture.server.handle("/resolve", exchange -> {
                apiRequests.incrementAndGet();
                respondJson(exchange, 200, "{}");
            });
            AudioResolveLimits limits = new AudioResolveLimits(16, 256, 10, 64, 2, 20);

            RadioSourceException exception = assertThrows(RadioSourceException.class,
                    () -> fixture.resolver.resolveProgram(TRACK, fixture.context(limits)));

            assertEquals(RadioFailure.Code.RESOURCE_LIMIT, exception.code());
            assertEquals(0, apiRequests.get());
        }
    }

    @Test
    void findsClientIdInABoundedPrefixOfALargeApplicationScript() throws Exception {
        try (Fixture fixture = new Fixture()) {
            fixture.server.handle("/", exchange -> respondHtml(exchange,
                    "<script src=\"" + fixture.server.uri("/large.js") + "\"></script>"));
            fixture.server.handle("/large.js", exchange -> respondJavascript(exchange,
                    "client_id:\"early-id\";" + "x".repeat(300_000)));
            fixture.server.handle("/resolve", exchange -> respondJson(exchange, 200,
                    progressiveTrackJson("Track", fixture.server.uri("/transcoding"))));

            RadioSourceProgram program = fixture.resolver.resolveProgram(TRACK, fixture.context());

            assertEquals(1, program.tracks().size());
        }
    }

    @Test
    void acceptsPlaylistsAndHydratesPartialTracksById() throws Exception {
        AtomicInteger hydrated = new AtomicInteger();
        try (Fixture fixture = new Fixture()) {
            fixture.installDiscovery("client-one");
            fixture.server.handle("/resolve", exchange -> respondJson(exchange, 200, """
                    {"kind":"playlist","is_album":false,"tracks":[
                      {"id":42,"kind":"track","title":"Partial"}
                    ]}
                    """));
            fixture.server.handle("/tracks/42", exchange -> {
                hydrated.incrementAndGet();
                respondJson(exchange, 200, progressiveTrackJson("Hydrated",
                        fixture.server.uri("/transcoding")));
            });
            fixture.server.handle("/transcoding", exchange -> respondJson(exchange, 200,
                    "{\"url\":\"" + fixture.server.uri("/media.mp3") + "\"}"));
            fixture.server.handle("/media.mp3", exchange -> respondAudio(exchange, 200,
                    bytes("ID3-hydrated")));

            RadioSourceProgram program = fixture.resolver.resolveProgram(
                    URI.create("https://soundcloud.com/a/set"), fixture.context());
            try (RadioResolvedSource source = program.openTrack(0, fixture.context())) {
                assertArrayEquals(bytes("ID3-hydrated"), source.body().readAllBytes());
            }

            assertEquals(1, hydrated.get());
            assertEquals("Partial", program.tracks().get(0).title());
        }
    }

    @Test
    void sendsTrackAuthorizationOnlyToTheConfiguredApiOrigin() throws Exception {
        AtomicReference<String> authorization = new AtomicReference<>();
        try (Fixture fixture = new Fixture()) {
            fixture.installDiscovery("client-one");
            fixture.server.handle("/resolve", exchange -> respondJson(exchange, 200, """
                    {"kind":"track","title":"Track","streamable":true,
                     "track_authorization":"track-token","media":{"transcodings":[
                       {"url":"%s","format":{"protocol":"progressive","mime_type":"audio/mpeg"}}
                     ]}}
                    """.formatted(fixture.server.uri("/transcoding"))));
            fixture.server.handle("/transcoding", exchange -> {
                authorization.set(query(exchange.getRequestURI()).get("track_authorization"));
                respondJson(exchange, 200,
                        "{\"url\":\"" + fixture.server.uri("/media.mp3") + "\"}");
            });
            fixture.server.handle("/media.mp3", exchange -> respondAudio(exchange, 200,
                    bytes("ID3-authorized")));

            RadioSourceProgram program = fixture.resolver.resolveProgram(TRACK, fixture.context());
            try (RadioResolvedSource ignored = program.openTrack(0, fixture.context())) {
                assertEquals("track-token", authorization.get());
            }
        }
    }

    @Test
    void neverSendsSoundCloudCredentialsToAnUntrustedTranscodingOrigin() throws Exception {
        try (Fixture fixture = new Fixture()) {
            fixture.installDiscovery("client-one");
            fixture.server.handle("/resolve", exchange -> respondJson(exchange, 200,
                    progressiveTrackJson("Track", URI.create("https://media.example/transcoding"))));

            RadioSourceProgram program = fixture.resolver.resolveProgram(TRACK, fixture.context());
            RadioSourceException failure = assertThrows(RadioSourceException.class,
                    () -> program.openTrack(0, fixture.context()));

            assertEquals(RadioFailure.Code.BLOCKED_ADDRESS, failure.code());
        }
    }

    @Test
    void boundsResolveApiBodies() throws Exception {
        try (Fixture fixture = new Fixture()) {
            fixture.installDiscovery("client-one");
            fixture.server.handle("/resolve", exchange -> respondJson(exchange, 200,
                    "x".repeat(257)));
            AudioResolveLimits limits = new AudioResolveLimits(16, 256, 10, 64, 2, 20);

            RadioSourceException exception = assertThrows(RadioSourceException.class,
                    () -> fixture.resolver.resolveProgram(TRACK, fixture.context(limits)));

            assertEquals(RadioFailure.Code.RESOURCE_LIMIT, exception.code());
        }
    }

    @Test
    void boundsTranscodingApiBodies() throws Exception {
        try (Fixture fixture = new Fixture()) {
            fixture.installDiscovery("client-one");
            fixture.server.handle("/resolve", exchange -> respondJson(exchange, 200,
                    progressiveTrackJson("Track", fixture.server.uri("/transcoding"))));
            fixture.server.handle("/transcoding", exchange -> respondJson(exchange, 200,
                    "x".repeat(513)));
            RadioSourceProgram program = fixture.resolver.resolveProgram(TRACK, fixture.context());
            AudioResolveLimits limits = new AudioResolveLimits(16, 512, 10, 64, 2, 20);

            RadioSourceException exception = assertThrows(RadioSourceException.class,
                    () -> program.openTrack(0, fixture.context(limits)));

            assertEquals(RadioFailure.Code.RESOURCE_LIMIT, exception.code());
        }
    }

    @Test
    void sharesStepBudgetAcrossDiscoveryAndApiResolution() throws Exception {
        AtomicInteger apiRequests = new AtomicInteger();
        try (Fixture fixture = new Fixture()) {
            fixture.installDiscovery("client-one");
            fixture.server.handle("/resolve", exchange -> {
                apiRequests.incrementAndGet();
                respondJson(exchange, 200, progressiveTrackJson("Track",
                        fixture.server.uri("/transcoding")));
            });
            AudioResolveLimits limits = new AudioResolveLimits(16, 1024, 10, 64, 2, 2);

            RadioSourceException exception = assertThrows(RadioSourceException.class,
                    () -> fixture.resolver.resolveProgram(TRACK, fixture.context(limits)));

            assertEquals(RadioFailure.Code.RESOURCE_LIMIT, exception.code());
            assertEquals(0, apiRequests.get());
        }
    }

    @Test
    void observesCancellationBeforeStartingDiscovery() throws Exception {
        AtomicInteger requests = new AtomicInteger();
        try (Fixture fixture = new Fixture()) {
            fixture.server.handle("/", exchange -> {
                requests.incrementAndGet();
                respondHtml(exchange, "unused");
            });
            PlaybackSession session = new PlaybackSession();
            PlaybackSession.Attempt attempt = session.start(TRACK.toString());
            session.stop();

            assertThrows(CancellationException.class,
                    () -> fixture.resolver.resolveProgram(TRACK, fixture.context(attempt.cancellation())));
            assertEquals(0, requests.get());
        }
    }

    private static String trackJson(URI hls, URI progressive) {
        return """
                {"kind":"track","title":"Track","streamable":true,
                 "permalink_url":"https://soundcloud.com/artist/track","media":{"transcodings":[
                   {"url":"%s","format":{"protocol":"hls","mime_type":"audio/mpeg"}},
                   {"url":"%s","format":{"protocol":"progressive","mime_type":"audio/mpeg"}}
                 ]}}
                """.formatted(hls, progressive);
    }

    private static String hlsTrackJson(URI hls) {
        return """
                {"kind":"track","title":"Track","streamable":true,"media":{"transcodings":[
                  {"url":"%s","format":{"protocol":"hls","mime_type":"audio/mpeg"}}
                ]}}
                """.formatted(hls);
    }

    private static String progressiveTrackJson(String title, URI transcoding) {
        return """
                {"kind":"track","title":"%s","streamable":true,"media":{"transcodings":[
                  {"url":"%s","format":{"protocol":"progressive","mime_type":"audio/mpeg"}}
                ]}}
                """.formatted(title, transcoding);
    }

    private static Map<String, String> query(URI uri) {
        Map<String, String> values = new HashMap<>();
        if (uri.getRawQuery() == null) {
            return values;
        }
        for (String entry : uri.getRawQuery().split("&")) {
            String[] parts = entry.split("=", 2);
            values.put(URLDecoder.decode(parts[0], StandardCharsets.UTF_8),
                    parts.length == 1 ? "" : URLDecoder.decode(parts[1], StandardCharsets.UTF_8));
        }
        return values;
    }

    private static void respondHtml(HttpExchange exchange, String body) throws IOException {
        exchange.getResponseHeaders().add("Content-Type", "text/html; charset=utf-8");
        respond(exchange, 200, bytes(body));
    }

    private static void respondJavascript(HttpExchange exchange, String body) throws IOException {
        exchange.getResponseHeaders().add("Content-Type", "application/javascript");
        respond(exchange, 200, bytes(body));
    }

    private static void respondJson(HttpExchange exchange, int status, String body) throws IOException {
        exchange.getResponseHeaders().add("Content-Type", "application/json");
        respond(exchange, status, bytes(body));
    }

    private static void respondAudio(HttpExchange exchange, int status, byte[] body) throws IOException {
        exchange.getResponseHeaders().add("Content-Type", "audio/mpeg");
        respond(exchange, status, body);
    }

    private static void respond(HttpExchange exchange, int status, byte[] body) throws IOException {
        exchange.sendResponseHeaders(status, body.length);
        try (exchange; var output = exchange.getResponseBody()) {
            output.write(body);
        }
    }

    private static byte[] bytes(String value) {
        return value.getBytes(StandardCharsets.UTF_8);
    }

    private static final class Fixture implements AutoCloseable {

        private final TestHttpServer server = new TestHttpServer();
        private final SoundCloudRadioSourceResolver resolver = new SoundCloudRadioSourceResolver(
                new DirectRadioSourceResolver(), this.server.uri("/"), this.server.uri("/resolve"));

        private Fixture() throws IOException {
        }

        private void installDiscovery(String id) {
            this.server.handle("/", exchange -> respondHtml(exchange,
                    "<script src=\"" + this.server.uri("/app.js") + "\"></script>"));
            this.server.handle("/app.js", exchange -> respondJavascript(exchange,
                    "window.__sc={client_id:\"" + id + "\"};"));
        }

        private AudioResolveContext context() {
            return this.context(AudioResolveLimits.DEFAULT);
        }

        private AudioResolveContext context(AudioResolveLimits limits) {
            AudioCancellation cancellation = new PlaybackSession().start(TRACK.toString()).cancellation();
            return this.context(cancellation, limits);
        }

        private AudioResolveContext context(AudioCancellation cancellation) {
            return this.context(cancellation, AudioResolveLimits.DEFAULT);
        }

        private AudioResolveContext context(AudioCancellation cancellation, AudioResolveLimits limits) {
            RadioHttpTransportImpl transport = new RadioHttpTransportImpl(
                    Proxy.NO_PROXY, ALLOW_TEST_SERVER, TIMEOUT, TIMEOUT, 5);
            return new AudioResolveContext(transport, ALLOW_TEST_SERVER, cancellation, limits);
        }

        @Override
        public void close() {
            this.server.close();
        }
    }
}
