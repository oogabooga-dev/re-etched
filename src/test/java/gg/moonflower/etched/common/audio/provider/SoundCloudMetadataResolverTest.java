package gg.moonflower.etched.common.audio.provider;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import gg.moonflower.etched.common.audio.AudioCancellation;
import gg.moonflower.etched.common.audio.RadioFailure;
import gg.moonflower.etched.common.audio.net.AudioNetworkPolicy;
import gg.moonflower.etched.common.audio.net.RadioHttpTransportImpl;
import gg.moonflower.etched.common.audio.net.RadioTransportException;
import gg.moonflower.etched.common.audio.net.TestAudioHttpResponse;
import gg.moonflower.etched.common.audio.net.TestHttpServer;
import gg.moonflower.etched.common.sound.download.SoundCloudSource;
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
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.CancellationException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

class SoundCloudMetadataResolverTest {

    private static final URI TRACK = URI.create("https://soundcloud.com/artist/track?name=a&other=b");
    private static final AudioNetworkPolicy ALLOW_ALL = uri -> {};
    private static final String TRACK_JSON = """
            {"kind":"track","streamable":true,"title":"Track & Title","user":{"username":"Artist"}}
            """;
    private static final String ALBUM_JSON = """
            {"kind":"playlist","is_album":true,"title":"Album","user":{"username":"Artist"},"tracks":[
              {"permalink_url":"https://soundcloud.com/a/first","title":"First"},
              {"title":"Paid track"},
              {"permalink_url":"https://soundcloud.com/a/second","title":"Second","user":{"username":"Guest"}}
            ]}
            """;

    @Test
    void preservesAlbumDescriptorOrderAndArtistFallbackWithoutOpeningMedia() throws Exception {
        try (Fixture fixture = new Fixture()) {
            fixture.discovery();
            fixture.server.handle("/resolve", exchange -> {
                var query = query(exchange.getRequestURI());
                assertEquals(TRACK.toString(), query.get("url"));
                assertEquals("client-one", query.get("client_id"));
                respond(exchange, 200, ALBUM_JSON);
            });
            var tracks = fixture.resolver().resolveTracks(TRACK, new AudioCancellation());
            assertEquals(List.of(TRACK.toString(), "https://soundcloud.com/a/first", "https://soundcloud.com/a/second"),
                    tracks.stream().map(track -> track.url()).toList());
            assertEquals(List.of("Album", "First", "Second"), tracks.stream().map(track -> track.title().getString()).toList());
            assertEquals(List.of("Artist", "Artist", "Guest"), tracks.stream().map(track -> track.artist()).toList());
            assertEquals(List.of(TRACK, TRACK, URI.create("https://soundcloud.com/a/first"),
                    URI.create("https://soundcloud.com/a/second")), fixture.checkedPages);
        }
    }

    @Test
    void resolvesCoverWithoutTrackTitlesOrArtistAndPreservesMissingArtwork() throws Exception {
        for (String kind : List.of("\"kind\":\"track\",\"streamable\":true",
                "\"kind\":\"playlist\",\"is_album\":true")) {
            for (String value : List.of("", ",\"artwork_url\":null",
                    ",\"artwork_url\":\"https://images.example/cover.jpg\"")) {
                try (Fixture fixture = new Fixture()) {
                    fixture.discovery();
                    fixture.server.handle("/resolve", exchange -> respond(exchange, 200, "{" + kind + value + "}"));
                    var cover = fixture.resolver().resolveAlbumCover(TRACK, new AudioCancellation());
                    assertEquals(!value.contains("images.example"), cover.isEmpty());
                    if (cover.isPresent()) {
                        assertEquals(URI.create("https://images.example/cover.jpg"), cover.get());
                        assertTrue(fixture.checkedPages.contains(cover.get()));
                    }
                }
            }
        }
    }

    @Test
    void rejectsUnsafeCoverUrlsAndBlockedArtworkInsteadOfReturningThem() throws Exception {
        for (String value : List.of("file:///etc/passwd", "https://user@images.example/cover", "../relative", "bad url")) {
            try (Fixture fixture = new Fixture()) {
                fixture.discovery();
                fixture.server.handle("/resolve", exchange -> respond(exchange, 200,
                        "{\"kind\":\"track\",\"streamable\":true,\"artwork_url\":\"" + value + "\"}"));
                assertThrows(IOException.class, () -> fixture.resolver().resolveAlbumCover(TRACK, new AudioCancellation()));
            }
        }
        try (Fixture fixture = new Fixture()) {
            fixture.discovery();
            fixture.server.handle("/resolve", exchange -> respond(exchange, 200,
                    "{\"kind\":\"track\",\"streamable\":true,\"artwork_url\":\"http://127.0.0.1/private\"}"));
            AudioNetworkPolicy policy = uri -> {
                if (uri.getPath().equals("/private")) {
                    throw new RadioTransportException(RadioFailure.Code.BLOCKED_ADDRESS, false, "blocked", null);
                }
            };
            var resolver = new SoundCloudMetadataResolver(new RadioHttpTransportImpl(Proxy.NO_PROXY, ALLOW_ALL,
                    Duration.ofSeconds(2), Duration.ofSeconds(2), 5), policy, fixture.server.uri("/"),
                    fixture.server.uri("/resolve"), SoundCloudMetadataResolver.Limits.DEFAULT);
            assertEquals(RadioFailure.Code.BLOCKED_ADDRESS, assertThrows(RadioTransportException.class,
                    () -> resolver.resolveAlbumCover(TRACK, new AudioCancellation())).code());
        }
    }

    @Test
    void refreshesClientIdOnceAndDoesNotShareDiscoveryAcrossRequests() throws Exception {
        AtomicInteger scripts = new AtomicInteger();
        AtomicInteger api = new AtomicInteger();
        try (Fixture fixture = new Fixture()) {
            fixture.server.handle("/", exchange -> respond(exchange, 200, "<script src='/app.js'></script>"));
            fixture.server.handle("/app.js", exchange -> respond(exchange, 200,
                    "client_id:'client-" + scripts.incrementAndGet() + "'"));
            fixture.server.handle("/resolve", exchange -> {
                api.incrementAndGet();
                respond(exchange, query(exchange.getRequestURI()).get("client_id").equals("client-1") ? 401 : 200, TRACK_JSON);
            });
            var resolver = fixture.resolver();
            assertEquals("Track & Title", resolver.resolveTracks(TRACK, new AudioCancellation()).get(0).title().getString());
            assertEquals(2, scripts.get());
            assertEquals(2, api.get());
            resolver.resolveTracks(TRACK, new AudioCancellation());
            assertEquals(3, scripts.get());
            assertEquals(3, api.get());
        }
    }

    @Test
    void permanentAuthenticationFailureStopsAfterOneRefresh() throws Exception {
        AtomicInteger api = new AtomicInteger();
        try (Fixture fixture = new Fixture()) {
            fixture.discovery();
            fixture.server.handle("/resolve", exchange -> {
                api.incrementAndGet();
                respond(exchange, 403, "{}");
            });
            assertEquals(RadioFailure.Code.HTTP_STATUS, assertThrows(RadioTransportException.class,
                    () -> fixture.resolver().resolveTracks(TRACK, new AudioCancellation())).code());
            assertEquals(2, api.get());
        }
    }

    @Test
    void enforcesBodyEntryFieldAndSharedRetryStepLimits() throws Exception {
        for (var limits : List.of(
                new SoundCloudMetadataResolver.Limits(64, 100, 8192, 128, 10, 5),
                new SoundCloudMetadataResolver.Limits(4096, 1, 8192, 128, 10, 5),
                new SoundCloudMetadataResolver.Limits(4096, 100, 64, 128, 10, 5),
                new SoundCloudMetadataResolver.Limits(4096, 100, 8192, 3, 10, 5))) {
            try (Fixture fixture = new Fixture()) {
                fixture.discovery();
                AtomicInteger api = new AtomicInteger();
                fixture.server.handle("/resolve", exchange -> {
                    api.incrementAndGet();
                    String json = limits.maxFieldLength() == 64 ? TRACK_JSON.replace("Track & Title", "x".repeat(65)) : ALBUM_JSON;
                    respond(exchange, limits.maxResolutionSteps() == 3 ? 401 : 200, json);
                });
                assertEquals(RadioFailure.Code.RESOURCE_LIMIT, assertThrows(RadioTransportException.class,
                        () -> fixture.resolver(limits).resolveTracks(TRACK, new AudioCancellation())).code());
                assertEquals(1, api.get());
            }
        }
    }

    @Test
    void scriptCandidatesAreBoundedAndTriedInReversePageOrder() throws Exception {
        var limits = new SoundCloudMetadataResolver.Limits(4096, 100, 8192, 128, 2, 5);
        List<String> opened = new ArrayList<>();
        try (Fixture fixture = new Fixture()) {
            fixture.server.handle("/", exchange -> respond(exchange, 200,
                    "<script src='/one.js'></script><script src='/two.js'></script>"
                            + "<script src='/three.js'></script><script src='/four.js'></script>"));
            fixture.server.handle("/four.js", exchange -> {
                opened.add("four");
                respond(exchange, 200, "no client id");
            });
            fixture.server.handle("/three.js", exchange -> {
                opened.add("three");
                respond(exchange, 200, "client_id:'client-one'");
            });
            fixture.server.handle("/resolve", exchange -> respond(exchange, 200, TRACK_JSON));
            fixture.resolver(limits).resolveTracks(TRACK, new AudioCancellation());
            assertEquals(List.of("four", "three"), opened);
        }
    }

    @Test
    void rejectsMalformedAndNonAlbumDataAndUntrustedStoredPageUrls() throws Exception {
        for (String json : List.of("not json", "{}", TRACK_JSON.replace("true", "false"),
                ALBUM_JSON.replace("\"is_album\":true", "\"is_album\":false"),
                ALBUM_JSON.replace("https://soundcloud.com/a/first", "http://127.0.0.1/private"),
                ALBUM_JSON.replace("https://soundcloud.com/a/first", "https://evilsoundcloud.com/a/first"),
                ALBUM_JSON.replace("https://soundcloud.com/a/first", "https://user@soundcloud.com/a/first"),
                ALBUM_JSON.replace("https://soundcloud.com/a/first", "file:///etc/passwd"))) {
            try (Fixture fixture = new Fixture()) {
                fixture.discovery();
                fixture.server.handle("/resolve", exchange -> respond(exchange, 200, json));
                assertThrows(IOException.class, () -> fixture.resolver().resolveTracks(TRACK, new AudioCancellation()));
            }
        }
    }

    @Test
    void validatesSubmittedAndStoredPagesBeforeReturningMetadata() throws Exception {
        try (Fixture fixture = new Fixture()) {
            var transport = new RadioHttpTransportImpl(Proxy.NO_PROXY, ALLOW_ALL,
                    Duration.ofSeconds(2), Duration.ofSeconds(2), 5);
            var blocked = new SoundCloudMetadataResolver(transport, uri -> {
                throw new RadioTransportException(RadioFailure.Code.BLOCKED_ADDRESS, false, "blocked", null);
            }, fixture.server.uri("/"), fixture.server.uri("/resolve"), SoundCloudMetadataResolver.Limits.DEFAULT);
            assertEquals(RadioFailure.Code.BLOCKED_ADDRESS, assertThrows(RadioTransportException.class,
                    () -> blocked.resolveTracks(TRACK, new AudioCancellation())).code());
            fixture.discovery();
            fixture.server.handle("/resolve", exchange -> respond(exchange, 200, ALBUM_JSON));
            var resolver = new SoundCloudMetadataResolver(transport, uri -> {
                if (uri.getPath().equals("/a/second")) {
                    throw new RadioTransportException(RadioFailure.Code.BLOCKED_ADDRESS, false, "blocked", null);
                }
            }, fixture.server.uri("/"), fixture.server.uri("/resolve"), SoundCloudMetadataResolver.Limits.DEFAULT);
            assertEquals(RadioFailure.Code.BLOCKED_ADDRESS, assertThrows(RadioTransportException.class,
                    () -> resolver.resolveTracks(TRACK, new AudioCancellation())).code());
        }
        SoundCloudSource legacy = new SoundCloudSource();
        for (String value : List.of("https://evilsoundcloud.com/x", "https://soundcloud.com.evil.example/x",
                "https://user@soundcloud.com/x", "ftp://soundcloud.com/x")) {
            assertFalse(legacy.isValidUrl(value));
            assertFalse(SoundCloudPageReader.supports(URI.create(value)));
        }
        assertTrue(legacy.isValidUrl("https://M.SoundCloud.com/a/track"));
    }

    @Test
    void redirectsShareTheStepBudgetAndBlockedScriptsAreNeverOpened() throws Exception {
        AtomicInteger api = new AtomicInteger();
        try (Fixture fixture = new Fixture()) {
            fixture.server.handle("/", exchange -> {
                exchange.getResponseHeaders().add("Location", "/landing");
                exchange.sendResponseHeaders(302, -1);
                exchange.close();
            });
            fixture.server.handle("/landing", exchange -> respond(exchange, 200, "<script src='/app.js'></script>"));
            fixture.server.handle("/app.js", exchange -> respond(exchange, 200, "client_id:'client-one'"));
            fixture.server.handle("/resolve", exchange -> {
                api.incrementAndGet();
                respond(exchange, 200, TRACK_JSON);
            });
            var limits = new SoundCloudMetadataResolver.Limits(4096, 100, 8192, 3, 10, 5);
            assertEquals(RadioFailure.Code.RESOURCE_LIMIT, assertThrows(RadioTransportException.class,
                    () -> fixture.resolver(limits).resolveTracks(TRACK, new AudioCancellation())).code());
            assertEquals(0, api.get());
        }
        AtomicInteger scripts = new AtomicInteger();
        try (Fixture fixture = new Fixture()) {
            fixture.server.handle("/", exchange -> respond(exchange, 200, "<script src='/blocked.js'></script>"));
            fixture.server.handle("/blocked.js", exchange -> {
                scripts.incrementAndGet();
                respond(exchange, 200, "client_id:'private'");
            });
            AudioNetworkPolicy policy = uri -> {
                if (uri.getPath().equals("/blocked.js")) {
                    throw new RadioTransportException(RadioFailure.Code.BLOCKED_ADDRESS, false, "blocked", null);
                }
            };
            var resolver = new SoundCloudMetadataResolver(new RadioHttpTransportImpl(Proxy.NO_PROXY, policy,
                    Duration.ofSeconds(2), Duration.ofSeconds(2), 5), policy, fixture.server.uri("/"),
                    fixture.server.uri("/resolve"), SoundCloudMetadataResolver.Limits.DEFAULT);
            assertEquals(RadioFailure.Code.BLOCKED_ADDRESS, assertThrows(RadioTransportException.class,
                    () -> resolver.resolveTracks(TRACK, new AudioCancellation())).code());
            assertEquals(0, scripts.get());
        }
    }

    @Test
    void apiRedirectsCannotSubstituteAnUntrustedOrigin() throws Exception {
        try (Fixture fixture = new Fixture(); TestHttpServer outside = new TestHttpServer()) {
            fixture.discovery();
            fixture.server.handle("/resolve", exchange -> {
                exchange.getResponseHeaders().add("Location", outside.uri("/json").toString());
                exchange.sendResponseHeaders(302, -1);
                exchange.close();
            });
            outside.handle("/json", exchange -> respond(exchange, 200, TRACK_JSON));
            assertEquals(RadioFailure.Code.BLOCKED_ADDRESS, assertThrows(RadioTransportException.class,
                    () -> fixture.resolver().resolveTracks(TRACK, new AudioCancellation())).code());
        }
    }

    @Test
    void cancellationRetiresHomepageScriptAndApiBodyReads() throws Exception {
        for (String path : List.of("/", "/app.js", "/resolve")) {
            CountDownLatch started = new CountDownLatch(1);
            CountDownLatch release = new CountDownLatch(1);
            AudioCancellation cancellation = new AudioCancellation();
            try (Fixture fixture = new Fixture()) {
                AtomicInteger attempts = new AtomicInteger();
                HttpHandler stalled = exchange -> {
                    exchange.sendResponseHeaders(200, 0);
                    exchange.getResponseBody().write((path.equals("/app.js") ? "client_id:'" : "{")
                            .getBytes(StandardCharsets.UTF_8));
                    exchange.getResponseBody().flush();
                    started.countDown();
                    try {
                        release.await(5, TimeUnit.SECONDS);
                    } catch (InterruptedException exception) {
                        Thread.currentThread().interrupt();
                    } finally {
                        exchange.close();
                    }
                };
                for (String candidate : List.of("/", "/app.js", "/resolve")) {
                    fixture.server.handle(candidate, exchange -> {
                        if (candidate.equals(path) && attempts.getAndIncrement() == 0) {
                            stalled.handle(exchange);
                        } else {
                            respond(exchange, 200, candidate.equals("/") ? "<script src='/app.js'></script>"
                                    : candidate.equals("/app.js") ? "client_id:'client-two'" : TRACK_JSON);
                        }
                    });
                }
                var resolver = fixture.resolver();
                CompletableFuture<?> request = CompletableFuture.runAsync(() -> {
                    try {
                        resolver.resolveTracks(TRACK, cancellation);
                    } catch (IOException exception) {
                        throw new CompletionException(exception);
                    }
                });
                try {
                    assertTrue(started.await(2, TimeUnit.SECONDS));
                    cancellation.cancel();
                    assertInstanceOf(CancellationException.class, assertThrows(ExecutionException.class,
                            () -> request.get(2, TimeUnit.SECONDS)).getCause());
                    // A retired operation must not poison subsequent discovery on the same resolver.
                    assertEquals(1, resolver.resolveTracks(TRACK, new AudioCancellation()).size());
                } finally {
                    release.countDown();
                }
            }
        }
    }

    @Test
    void everyDiscoveryAndApiResponseIsClosedOnSuccessParseFailureAndAuthRejection() throws Exception {
        URI homepage = URI.create("https://soundcloud.com/");
        URI endpoint = URI.create("https://api-v2.soundcloud.com/resolve");
        for (String json : List.of(TRACK_JSON, "not json", "rejected")) {
            AtomicInteger opened = new AtomicInteger();
            AtomicInteger closed = new AtomicInteger();
            var resolver = new SoundCloudMetadataResolver((request, cancellation) -> {
                opened.incrementAndGet();
                String body = request.uri().getPath().equals("/") ? "<script src='/app.js'></script>"
                        : request.uri().getPath().equals("/app.js") ? "client_id:'client-one'" : json;
                var stream = new ByteArrayInputStream(body.getBytes(StandardCharsets.UTF_8)) {
                    @Override
                    public void close() {
                        closed.incrementAndGet();
                    }
                };
                return TestAudioHttpResponse.owned(request.uri(), json.equals("rejected")
                        && request.uri().getPath().equals("/resolve") ? 401 : 200, Map.of(), stream, cancellation);
            }, ALLOW_ALL, homepage, endpoint, SoundCloudMetadataResolver.Limits.DEFAULT);
            if (json.equals(TRACK_JSON)) {
                resolver.resolveTracks(TRACK, new AudioCancellation());
            } else {
                assertThrows(IOException.class, () -> resolver.resolveTracks(TRACK, new AudioCancellation()));
            }
            assertEquals(opened.get(), closed.get());
            assertEquals(json.equals("rejected") ? 6 : 3, opened.get());
        }
    }

    private static Map<String, String> query(URI uri) {
        var result = new java.util.HashMap<String, String>();
        for (String part : uri.getRawQuery().split("&")) {
            String[] pieces = part.split("=", 2);
            result.put(URLDecoder.decode(pieces[0], StandardCharsets.UTF_8),
                    URLDecoder.decode(pieces[1], StandardCharsets.UTF_8));
        }
        return result;
    }

    private static void respond(HttpExchange exchange, int status, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.sendResponseHeaders(status, bytes.length);
        exchange.getResponseBody().write(bytes);
        exchange.close();
    }

    private static final class Fixture implements AutoCloseable {
        private final TestHttpServer server = new TestHttpServer();
        private final List<URI> checkedPages = new ArrayList<>();

        private Fixture() throws IOException {
        }

        private void discovery() {
            this.server.handle("/", exchange -> respond(exchange, 200, "<script src='/app.js'></script>"));
            this.server.handle("/app.js", exchange -> respond(exchange, 200, "client_id:'client-one'"));
        }

        private SoundCloudMetadataResolver resolver() {
            return resolver(SoundCloudMetadataResolver.Limits.DEFAULT);
        }

        private SoundCloudMetadataResolver resolver(SoundCloudMetadataResolver.Limits limits) {
            return new SoundCloudMetadataResolver(new RadioHttpTransportImpl(Proxy.NO_PROXY, ALLOW_ALL,
                    Duration.ofSeconds(2), Duration.ofMillis(500), 5), this.checkedPages::add,
                    this.server.uri("/"), this.server.uri("/resolve"), limits);
        }

        @Override
        public void close() {
            this.server.close();
        }
    }
}
