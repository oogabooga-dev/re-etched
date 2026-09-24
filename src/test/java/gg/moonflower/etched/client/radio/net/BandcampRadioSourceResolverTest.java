package gg.moonflower.etched.client.radio.net;

import gg.moonflower.etched.client.radio.RadioFailure;
import gg.moonflower.etched.client.radio.PlaybackSession;
import gg.moonflower.etched.client.radio.source.AudioResolveContext;
import gg.moonflower.etched.client.radio.source.AudioResolveLimits;
import gg.moonflower.etched.client.radio.source.BandcampRadioSourceResolver;
import gg.moonflower.etched.client.radio.source.RadioResolvedSource;
import gg.moonflower.etched.client.radio.source.RadioSourceException;
import gg.moonflower.etched.client.radio.source.RadioSourceProgram;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.Authenticator;
import java.net.HttpURLConnection;
import java.net.Proxy;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CancellationException;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BandcampRadioSourceResolverTest {

    private static final URI ALBUM = URI.create("https://artist.bandcamp.com/album/example");
    private static final URI TRACK = URI.create("https://artist.bandcamp.com/track/only");
    private static final URI FIRST_MEDIA = URI.create("https://t4.bcbits.com/stream/first.mp3");
    private static final URI SECOND_MEDIA = URI.create("https://t4.bcbits.com/stream/second.mp3");
    private static final AudioNetworkPolicy ALLOW_ALL = uri -> {
    };

    @Test
    void acceptsOnlyStrictBandcampHttpHostsWithoutUserInfo() {
        BandcampRadioSourceResolver resolver = new BandcampRadioSourceResolver();

        assertTrue(resolver.supports(URI.create("https://bandcamp.com/track/example")));
        assertTrue(resolver.supports(URI.create("http://Artist.Bandcamp.com/album/example")));
        assertFalse(resolver.supports(URI.create("https://notbandcamp.com/track/example")));
        assertFalse(resolver.supports(URI.create("https://bandcamp.com.example/track/example")));
        assertFalse(resolver.supports(URI.create("https://user@artist.bandcamp.com/track/example")));
        assertFalse(resolver.supports(URI.create("ftp://artist.bandcamp.com/track/example")));
        assertFalse(resolver.supports(URI.create("/track/example")));
    }

    @Test
    void parsesEscapedAlbumTracksInOrderAndOpensIndependentAudioResponses() throws Exception {
        TrackingConnection page = response(ALBUM, 200, albumHtml(FIRST_MEDIA, SECOND_MEDIA));
        TrackingConnection firstOpen = response(FIRST_MEDIA, 200, "ID3-first-open");
        TrackingConnection firstAgain = response(FIRST_MEDIA, 200, "ID3-first-again");
        TrackingConnection secondOpen = response(SECOND_MEDIA, 200, "ID3-second-open");
        RequestRouter router = new RequestRouter()
                .add(ALBUM, page)
                .add(FIRST_MEDIA, firstOpen, firstAgain)
                .add(SECOND_MEDIA, secondOpen);
        AudioResolveContext context = context(router.transport(), limits());

        RadioSourceProgram program = new BandcampRadioSourceResolver().resolveProgram(ALBUM, context);

        assertEquals(RadioSourceProgram.Kind.SERVICE_TRACKS, program.kind());
        assertEquals(ALBUM, program.source());
        assertEquals(List.of("First & One", "Second"),
                program.tracks().stream().map(RadioSourceProgram.Track::title).toList());
        assertEquals(List.of(
                        URI.create("https://artist.bandcamp.com/track/first"),
                        URI.create("https://artist.bandcamp.com/track/second")),
                program.tracks().stream().map(RadioSourceProgram.Track::source).toList());
        assertTrue(page.disconnected);
        assertNull(page.getRequestProperty("Icy-MetaData"));

        try (RadioResolvedSource first = program.openTrack(0, context)) {
            assertArrayEquals(bytes("ID3-first-open"), first.body().readAllBytes());
        }
        try (RadioResolvedSource first = program.openTrack(0, context);
             RadioResolvedSource second = program.openTrack(1, context)) {
            assertArrayEquals(bytes("ID3-first-again"), first.body().readAllBytes());
            assertArrayEquals(bytes("ID3-second-open"), second.body().readAllBytes());
        }

        assertEquals("1", firstOpen.getRequestProperty("Icy-MetaData"));
        assertTrue(firstOpen.disconnected);
        assertTrue(firstAgain.disconnected);
        assertTrue(secondOpen.disconnected);
        router.assertExhausted();
    }

    @Test
    void resolvesTrackPagesAsOneFiniteServiceTrack() throws Exception {
        TrackingConnection page = response(TRACK, 200, trackHtml(FIRST_MEDIA));
        RequestRouter router = new RequestRouter().add(TRACK, page);

        RadioSourceProgram program = new BandcampRadioSourceResolver().resolveProgram(
                TRACK, context(router.transport(), limits()));

        assertEquals(RadioSourceProgram.Kind.SERVICE_TRACKS, program.kind());
        assertEquals(1, program.tracks().size());
        assertEquals(TRACK, program.tracks().get(0).source());
        assertEquals("Only Track", program.tracks().get(0).title());
        assertTrue(page.disconnected);
    }

    @Test
    void enforcesBoundedHtmlEntryAndSharedStepLimits() throws Exception {
        AudioResolveLimits bodyLimit = new AudioResolveLimits(4, 32, 10, 32, 1, 10);
        TrackingConnection oversized = response(ALBUM, 200, "x".repeat(33));
        RequestRouter oversizedRouter = new RequestRouter().add(ALBUM, oversized);

        RadioSourceException bodyFailure = assertThrows(RadioSourceException.class,
                () -> new BandcampRadioSourceResolver().resolveProgram(
                        ALBUM, context(oversizedRouter.transport(), bodyLimit)));
        assertEquals(RadioFailure.Code.PLAYLIST_TOO_LARGE, bodyFailure.code());
        assertTrue(oversized.disconnected);

        AudioResolveLimits oneEntry = new AudioResolveLimits(4, 4096, 1, 32, 1, 10);
        TrackingConnection twoTracks = response(ALBUM, 200, albumHtml(FIRST_MEDIA, SECOND_MEDIA));
        RequestRouter entriesRouter = new RequestRouter().add(ALBUM, twoTracks);
        RadioSourceException entryFailure = assertThrows(RadioSourceException.class,
                () -> new BandcampRadioSourceResolver().resolveProgram(
                        ALBUM, context(entriesRouter.transport(), oneEntry)));
        assertEquals(RadioFailure.Code.RESOURCE_LIMIT, entryFailure.code());
        assertTrue(twoTracks.disconnected);

        AudioResolveLimits oneStep = new AudioResolveLimits(4, 4096, 10, 32, 1, 1);
        TrackingConnection page = response(TRACK, 200, trackHtml(FIRST_MEDIA));
        RequestRouter stepRouter = new RequestRouter().add(TRACK, page);
        AudioResolveContext stepContext = context(stepRouter.transport(), oneStep);
        RadioSourceProgram program = new BandcampRadioSourceResolver().resolveProgram(TRACK, stepContext);
        RadioSourceException stepFailure = assertThrows(RadioSourceException.class,
                () -> program.openTrack(0, stepContext));
        assertEquals(RadioFailure.Code.RESOURCE_LIMIT, stepFailure.code());
        assertEquals(0, stepRouter.requests(FIRST_MEDIA));
    }

    @Test
    void stopsReadingALargePageAfterCompleteTrackData() throws Exception {
        TrackingConnection page = response(ALBUM, 200,
                albumHtml(FIRST_MEDIA, SECOND_MEDIA) + "x".repeat(300_000));
        RequestRouter router = new RequestRouter().add(ALBUM, page);

        RadioSourceProgram program = new BandcampRadioSourceResolver().resolveProgram(
                ALBUM, context(router.transport(), limits()));

        assertEquals(2, program.tracks().size());
        assertTrue(page.disconnected);
    }

    @Test
    void treatsTemporaryBandcampServiceFailuresAsRecoverable() throws Exception {
        TrackingConnection unavailable = response(ALBUM, 503, "unavailable");
        RequestRouter router = new RequestRouter().add(ALBUM, unavailable);

        RadioSourceException failure = assertThrows(RadioSourceException.class,
                () -> new BandcampRadioSourceResolver().resolveProgram(
                        ALBUM, context(router.transport(), limits())));

        assertEquals(RadioFailure.Code.HTTP_STATUS, failure.code());
        assertEquals(503, failure.httpStatus());
        assertTrue(failure.recoverable());
        assertTrue(unavailable.disconnected);
    }

    @Test
    void refreshesTheOriginalPageOnceForExpiredFinalMediaWithoutLooping() throws Exception {
        URI refreshedMedia = URI.create("https://t4.bcbits.com/stream/refreshed.mp3");
        TrackingConnection initialPage = response(TRACK, 200, trackHtml(FIRST_MEDIA));
        TrackingConnection expired = response(FIRST_MEDIA, 403, "expired");
        TrackingConnection refreshedPage = response(TRACK, 200, trackHtml(refreshedMedia));
        TrackingConnection refreshedExpired = response(refreshedMedia, 404, "expired again");
        RequestRouter router = new RequestRouter()
                .add(TRACK, initialPage, refreshedPage)
                .add(FIRST_MEDIA, expired)
                .add(refreshedMedia, refreshedExpired);
        AudioResolveContext context = context(router.transport(), limits());
        RadioSourceProgram program = new BandcampRadioSourceResolver().resolveProgram(TRACK, context);

        RadioSourceException failure = assertThrows(RadioSourceException.class,
                () -> program.openTrack(0, context));

        assertEquals(RadioFailure.Code.HTTP_STATUS, failure.code());
        assertEquals(404, failure.httpStatus());
        assertEquals(2, router.requests(TRACK));
        assertEquals(1, router.requests(FIRST_MEDIA));
        assertEquals(1, router.requests(refreshedMedia));
        assertTrue(initialPage.disconnected);
        assertTrue(expired.disconnected);
        assertTrue(refreshedPage.disconnected);
        assertTrue(refreshedExpired.disconnected);
        router.assertExhausted();
    }

    @Test
    void opensTheRefreshedMediaAfterAnExpiredUrl() throws Exception {
        URI refreshedMedia = URI.create("https://t4.bcbits.com/stream/fresh.mp3");
        TrackingConnection initialPage = response(TRACK, 200, trackHtml(FIRST_MEDIA));
        TrackingConnection expired = response(FIRST_MEDIA, 401, "expired");
        TrackingConnection refreshedPage = response(TRACK, 200, trackHtml(refreshedMedia));
        TrackingConnection fresh = response(refreshedMedia, 200, "ID3-fresh-audio");
        RequestRouter router = new RequestRouter()
                .add(TRACK, initialPage, refreshedPage)
                .add(FIRST_MEDIA, expired)
                .add(refreshedMedia, fresh);
        AudioResolveContext context = context(router.transport(), limits());
        RadioSourceProgram program = new BandcampRadioSourceResolver().resolveProgram(TRACK, context);

        try (RadioResolvedSource source = program.openTrack(0, context)) {
            assertEquals(refreshedMedia, source.uri());
            assertArrayEquals(bytes("ID3-fresh-audio"), source.body().readAllBytes());
        }

        assertEquals(2, router.requests(TRACK));
        assertTrue(expired.disconnected);
        assertTrue(refreshedPage.disconnected);
        assertTrue(fresh.disconnected);
        router.assertExhausted();
    }

    @Test
    void refreshesAlbumTracksByStableIdInsteadOfChangedIndex() throws Exception {
        URI freshFirst = URI.create("https://t4.bcbits.com/stream/fresh-first.mp3");
        TrackingConnection initialPage = response(ALBUM, 200, albumHtml(FIRST_MEDIA, SECOND_MEDIA));
        TrackingConnection expired = response(FIRST_MEDIA, 403, "expired");
        TrackingConnection refreshedPage = response(ALBUM, 200, html("""
                {"current":{"type":"album"},"trackinfo":[
                  {"track_id":2,"title":"Second","title_link":"/track/second","file":{"mp3-128":"%s"}},
                  {"track_id":1,"title":"First","title_link":"/track/first","file":{"mp3-128":"%s"}}
                ]}
                """.formatted(SECOND_MEDIA, freshFirst)));
        TrackingConnection fresh = response(freshFirst, 200, "ID3-right-track");
        RequestRouter router = new RequestRouter()
                .add(ALBUM, initialPage, refreshedPage)
                .add(FIRST_MEDIA, expired)
                .add(freshFirst, fresh);
        AudioResolveContext context = context(router.transport(), limits());
        RadioSourceProgram program = new BandcampRadioSourceResolver().resolveProgram(ALBUM, context);

        try (RadioResolvedSource source = program.openTrack(0, context)) {
            assertEquals(freshFirst, source.uri());
            assertArrayEquals(bytes("ID3-right-track"), source.body().readAllBytes());
        }

        router.assertExhausted();
    }

    @Test
    void cancellationDuringPageReadClosesTheIntermediateResponse() throws Exception {
        PlaybackSession session = new PlaybackSession();
        PlaybackSession.Attempt attempt = session.start(ALBUM.toString());
        TrackingConnection page = response(ALBUM, 200, trackHtml(FIRST_MEDIA));
        page.onFirstRead = session::stop;
        RequestRouter router = new RequestRouter().add(ALBUM, page);
        AudioResolveContext context = new AudioResolveContext(router.transport(), ALLOW_ALL,
                attempt.cancellation(), limits());

        assertThrows(CancellationException.class,
                () -> new BandcampRadioSourceResolver().resolveProgram(ALBUM, context));

        await(() -> page.disconnected);
        router.assertExhausted();
    }

    private static void await(java.util.function.BooleanSupplier condition) throws Exception {
        long deadline = System.nanoTime() + java.util.concurrent.TimeUnit.SECONDS.toNanos(5);
        while (!condition.getAsBoolean()) {
            if (System.nanoTime() >= deadline) {
                throw new AssertionError("Timed out waiting for asynchronous radio cleanup");
            }
            Thread.sleep(10L);
        }
    }

    private static AudioResolveContext context(AudioHttpTransport transport, AudioResolveLimits limits) {
        return new AudioResolveContext(transport, ALLOW_ALL,
                new PlaybackSession().start(ALBUM.toString()).cancellation(), limits);
    }

    private static AudioResolveLimits limits() {
        return new AudioResolveLimits(16, 8192, 20, 256, 2, 20);
    }

    private static String albumHtml(URI first, URI second) {
        return html("""
                {"current":{"type":"album"},"trackinfo":[
                  {"track_id":1,"title":"First & One","title_link":"/track/first","file":{"mp3-128":"%s"}},
                  {"track_id":2,"title":"Second","title_link":"/track/second","file":{"mp3-128":"%s"}}
                ]}
                """.formatted(first, second));
    }

    private static String trackHtml(URI media) {
        return html("""
                {"current":{"type":"track"},"trackinfo":[
                  {"title":"Only Track","title_link":"/track/only","file":{"mp3-128":"%s"}}
                ]}
                """.formatted(media));
    }

    private static String html(String json) {
        String escaped = json.replace("&", "&amp;").replace("\"", "&quot;");
        return "<html><body><script data-tralbum=\"" + escaped + "\"></script></body></html>";
    }

    private static byte[] bytes(String value) {
        return value.getBytes(StandardCharsets.UTF_8);
    }

    private static TrackingConnection response(URI uri, int status, String body) throws IOException {
        return new TrackingConnection(uri, status, body.getBytes(StandardCharsets.UTF_8));
    }

    private static final class RequestRouter {

        private final Map<URI, Deque<TrackingConnection>> responses = new HashMap<>();
        private final Map<URI, Integer> requests = new HashMap<>();

        private RequestRouter add(URI uri, TrackingConnection... connections) {
            this.responses.computeIfAbsent(uri, ignored -> new ArrayDeque<>())
                    .addAll(List.of(connections));
            return this;
        }

        private AudioHttpTransport transport() {
            return new RadioHttpTransportImpl(Proxy.NO_PROXY, ALLOW_ALL,
                    Duration.ofSeconds(1), Duration.ofSeconds(1), 5, (uri, proxy) -> {
                this.requests.merge(uri, 1, Integer::sum);
                Deque<TrackingConnection> queued = this.responses.get(uri);
                if (queued == null || queued.isEmpty()) {
                    throw new IOException("Unexpected request: " + uri);
                }
                return queued.removeFirst();
            });
        }

        private int requests(URI uri) {
            return this.requests.getOrDefault(uri, 0);
        }

        private void assertExhausted() {
            List<URI> remaining = new ArrayList<>();
            this.responses.forEach((uri, queued) -> {
                if (!queued.isEmpty()) {
                    remaining.add(uri);
                }
            });
            assertEquals(List.of(), remaining);
        }
    }

    private static final class TrackingConnection extends HttpURLConnection {

        private final int status;
        private final byte[] bytes;
        private final Map<String, String> requestProperties = new HashMap<>();
        private volatile boolean disconnected;
        private Runnable onFirstRead;

        private TrackingConnection(URI uri, int status, byte[] bytes) throws IOException {
            super(uri.toURL());
            this.status = status;
            this.bytes = bytes;
        }

        @Override
        public void disconnect() {
            this.disconnected = true;
        }

        @Override
        public boolean usingProxy() {
            return false;
        }

        @Override
        public void connect() {
            this.connected = true;
        }

        @Override
        public int getResponseCode() {
            return this.status;
        }

        @Override
        public void setAuthenticator(Authenticator authenticator) {
        }

        @Override
        public void setRequestProperty(String name, String value) {
            this.requestProperties.put(name, value);
        }

        @Override
        public String getRequestProperty(String name) {
            return this.requestProperties.get(name);
        }

        @Override
        public InputStream getInputStream() {
            return this.body();
        }

        @Override
        public InputStream getErrorStream() {
            return this.body();
        }

        @Override
        public Map<String, List<String>> getHeaderFields() {
            return Map.of(
                    "Content-Type", List.of(this.status == 200 ? "text/html" : "text/plain"),
                    "Content-Length", List.of(Integer.toString(this.bytes.length)));
        }

        private InputStream body() {
            return new ByteArrayInputStream(this.bytes) {
                private boolean first = true;

                @Override
                public synchronized int read(byte[] target, int offset, int length) {
                    int read = super.read(target, offset, length);
                    if (this.first && read >= 0) {
                        this.first = false;
                        if (TrackingConnection.this.onFirstRead != null) {
                            TrackingConnection.this.onFirstRead.run();
                        }
                    }
                    return read;
                }
            };
        }
    }
}
