package gg.moonflower.etched.common.audio.net;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import gg.moonflower.etched.common.audio.AudioCancellation;
import gg.moonflower.etched.common.audio.RadioFailure;
import gg.moonflower.etched.common.audio.provider.BandcampMetadataResolver;
import gg.moonflower.etched.common.audio.provider.BandcampPageReader;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.lang.reflect.InvocationTargetException;
import java.net.Authenticator;
import java.net.HttpURLConnection;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.Proxy;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

class BandcampMetadataResolverTest {

    private static final URI ALBUM = URI.create("https://artist.bandcamp.com/album/example");
    private static final AudioNetworkPolicy ALLOW_ALL = uri -> {};
    private static final String TRACK_JSON = """
            {"artist":"Artist &amp; Co", "current":{"type":"track","title":"Only Track"}}
            """;
    private static final String ALBUM_JSON = """
            {"artist":"Artist &amp; Co", "current":{"type":"album","title":"Album &amp; Title"},
             "trackinfo":[{"title_link":"/track/first","title":"First &amp; One"},
                          {"title_link":"/track/second","title":"Second","artist":"Guest"}]}
             """;

    @Test
    void preservesAlbumDescriptorTrackOrderAndArtistFallbackWithoutOpeningMedia() throws Exception {
        FixtureConnection page = page(ALBUM_JSON);
        AtomicInteger requests = new AtomicInteger();
        Proxy proxy = new Proxy(Proxy.Type.HTTP, new InetSocketAddress("127.0.0.1", 9000));
        var transport = new RadioHttpTransportImpl(proxy, ALLOW_ALL, Duration.ofSeconds(1),
                Duration.ofSeconds(1), 5, (uri, configuredProxy) -> {
            assertEquals(ALBUM, uri);
            assertSame(proxy, configuredProxy);
            requests.incrementAndGet();
            return page;
        });
        var checked = new java.util.ArrayList<URI>();
        var tracks = new BandcampMetadataResolver(transport, checked::add,
                BandcampMetadataResolver.Limits.DEFAULT).resolveTracks(ALBUM, new AudioCancellation());
        assertEquals(ALBUM.toString(), tracks.album().orElseThrow().source());
        assertEquals("Album & Title", tracks.album().orElseThrow().title());
        assertEquals(List.of("https://artist.bandcamp.com/track/first", "https://artist.bandcamp.com/track/second"),
                tracks.program().tracks().stream().map(track -> track.source()).toList());
        assertEquals(List.of("First & One", "Second"), tracks.program().tracks().stream().map(track -> track.title()).toList());
        assertEquals(List.of("Artist & Co", "Guest"), tracks.program().tracks().stream().map(track -> track.artist()).toList());
        assertEquals(List.of(ALBUM, URI.create("https://artist.bandcamp.com/track/first"),
                URI.create("https://artist.bandcamp.com/track/second")), checked);
        assertEquals(1, requests.get());
        assertTrue(page.disconnected);
        assertTrue(page.bodyClosed);
        assertEquals("GET", page.getRequestMethod());
        assertFalse(page.getInstanceFollowRedirects());
        assertNull(page.getRequestProperty("Icy-MetaData"));
    }

    @Test
    void typedMetadataBoundsArtistTitleAndWholeProgramAndPreservesAOneTrackAlbum() throws Exception {
        for (String field : List.of("Artist &amp; Co", "Album &amp; Title", "First &amp; One", "Guest")) {
            FixtureConnection page = page(ALBUM_JSON.replace(field, "x".repeat(129)));
            assertEquals(RadioFailure.Code.RESOURCE_LIMIT, assertThrows(RadioTransportException.class,
                    () -> resolver(page, ALLOW_ALL, BandcampMetadataResolver.Limits.DEFAULT)
                            .resolveTracks(ALBUM, new AudioCancellation())).code());
            assertTrue(page.bodyClosed);
            assertTrue(page.disconnected);
        }
        var single = resolver(page(TRACK_JSON.replace("Only Track", "x".repeat(128))), ALLOW_ALL,
                BandcampMetadataResolver.Limits.DEFAULT).resolveTracks(ALBUM, new AudioCancellation());
        assertEquals(128, single.program().tracks().get(0).title().length());
        String albumJson = "{\"artist\":\"Artist\",\"current\":{\"type\":\"album\",\"title\":\"Album\"},"
                + "\"trackinfo\":[{\"title_link\":\"/track/one\",\"title\":\"Only\"}]}";
        var album = resolver(page(albumJson), ALLOW_ALL, BandcampMetadataResolver.Limits.DEFAULT)
                .resolveTracks(ALBUM, new AudioCancellation());
        assertEquals(1, album.program().tracks().size());
        assertEquals("Album", album.album().orElseThrow().title());
        assertEquals("Only", album.program().tracks().get(0).title());
        var json = JsonParser.parseString(albumJson).getAsJsonObject();
        var entries = json.getAsJsonArray("trackinfo");
        var template = entries.get(0).getAsJsonObject().deepCopy();
        template.addProperty("title_link", "/track/" + "x".repeat(7000));
        entries.remove(0);
        for (int i = 0; i < 10; i++) {
            entries.add(template.deepCopy());
        }
        FixtureConnection excessive = page(json.toString());
        assertEquals(RadioFailure.Code.RESOURCE_LIMIT, assertThrows(RadioTransportException.class,
                () -> resolver(excessive, ALLOW_ALL, BandcampMetadataResolver.Limits.DEFAULT)
                        .resolveTracks(ALBUM, new AudioCancellation())).code());
        assertTrue(excessive.bodyClosed);
        assertTrue(excessive.disconnected);
    }

    @Test
    void resolvesCoverIdWithoutRequiringTrackMetadataAndClosesThePageBeforePolicyChecks() throws Exception {
        FixtureConnection page = page("{\"current\":{\"type\":\"album\",\"art_id\":123456}}");
        URI cover = URI.create("https://f4.bcbits.com/img/a123456_1.jpg");
        AudioNetworkPolicy policy = uri -> {
            assertEquals(cover, uri);
            assertTrue(page.disconnected);
            assertTrue(page.bodyClosed);
        };
        assertEquals(cover, resolver(page, policy, BandcampMetadataResolver.Limits.DEFAULT)
                .resolveAlbumCover(ALBUM, new AudioCancellation()).orElseThrow());
    }

    @Test
    void missingCoverIsEmptyButMalformedIdsAndBlockedCoverDestinationsFail() throws Exception {
        for (String value : List.of("", ",\"art_id\":null")) {
            FixtureConnection page = page("{\"current\":{\"type\":\"track\"" + value + "}}");
            assertTrue(resolver(page, ALLOW_ALL, BandcampMetadataResolver.Limits.DEFAULT)
                    .resolveAlbumCover(ALBUM, new AudioCancellation()).isEmpty());
            assertTrue(page.disconnected);
        }
        for (String value : List.of("-1", "0", "1.5", "true", "{}", "\"../secret\"", "9223372036854775808")) {
            FixtureConnection page = page("{\"current\":{\"type\":\"track\",\"art_id\":" + value + "}}");
            assertThrows(IOException.class, () -> resolver(page, ALLOW_ALL, BandcampMetadataResolver.Limits.DEFAULT)
                    .resolveAlbumCover(ALBUM, new AudioCancellation()));
            assertTrue(page.disconnected);
            assertTrue(page.bodyClosed);
        }
        FixtureConnection blocked = page("{\"current\":{\"type\":\"track\",\"art_id\":123}}");
        RadioTransportException error = assertThrows(RadioTransportException.class,
                () -> resolver(blocked, uri -> {
                    throw new RadioTransportException(RadioFailure.Code.BLOCKED_ADDRESS, false, "blocked", null);
                }, BandcampMetadataResolver.Limits.DEFAULT).resolveAlbumCover(ALBUM, new AudioCancellation()));
        assertEquals(RadioFailure.Code.BLOCKED_ADDRESS, error.code());
        assertTrue(blocked.disconnected);
    }

    @Test
    void projectionCancellationIsNotReclassifiedAsMalformedMetadata() throws Exception {
        var resolver = resolver(page(ALBUM_JSON), ALLOW_ALL, BandcampMetadataResolver.Limits.DEFAULT);
        AudioCancellation cancellation = new AudioCancellation();
        cancellation.cancel();
        // Target the projection boundary: public entrypoints reject an already-cancelled request before fetching.
        var projection = BandcampMetadataResolver.class.getDeclaredMethod("parseContent",
                URI.class, URI.class, JsonObject.class, AudioCancellation.class);
        projection.setAccessible(true);
        InvocationTargetException failure = assertThrows(InvocationTargetException.class,
                () -> projection.invoke(resolver, ALBUM, ALBUM, JsonParser.parseString(ALBUM_JSON).getAsJsonObject(), cancellation));
        assertInstanceOf(CancellationException.class, failure.getCause());
    }

    @Test
    void trackMetadataKeepsSubmittedPageUrlAndStopsBeforeLivePageEof() throws Exception {
        byte[] body = html(TRACK_JSON).getBytes(StandardCharsets.UTF_8);
        FixtureConnection page = new FixtureConnection(ALBUM, 200, body) {
            @Override
            public InputStream getInputStream() {
                return new ByteArrayInputStream(body) {
                    @Override
                    public synchronized int read(byte[] bytes, int offset, int length) {
                        if (super.available() == 0) {
                            throw new AssertionError("Metadata waited for page EOF after complete data");
                        }
                        return super.read(bytes, offset, length);
                    }

                    @Override
                    public void close() {
                        bodyClosed = true;
                        closed.countDown();
                    }
                };
            }
        };
        var tracks = resolver(page, ALLOW_ALL, BandcampMetadataResolver.Limits.DEFAULT)
                .resolveTracks(ALBUM, new AudioCancellation());
        assertTrue(tracks.album().isEmpty());
        assertEquals(1, tracks.program().tracks().size());
        assertEquals(ALBUM.toString(), tracks.program().tracks().get(0).source());
        assertEquals("Only Track", tracks.program().tracks().get(0).title());
        assertTrue(page.disconnected);
        assertTrue(page.bodyClosed);
    }

    @Test
    void limitsBodyTracksAndFieldsAndClosesEveryRejectedResponse() throws Exception {
        byte[] body = html(ALBUM_JSON).getBytes(StandardCharsets.UTF_8);
        for (var limits : List.of(
                new BandcampMetadataResolver.Limits(body.length - 1, 100, 8192, 5),
                new BandcampMetadataResolver.Limits(4096, 1, 8192, 5),
                new BandcampMetadataResolver.Limits(4096, 100, 50, 5))) {
            String json = limits.maxFieldLength() == 50
                    ? TRACK_JSON.replace("Only Track", "x".repeat(51)) : ALBUM_JSON;
            FixtureConnection page = page(json);
            RadioTransportException error = assertThrows(RadioTransportException.class,
                    () -> resolver(page, ALLOW_ALL, limits).resolveTracks(ALBUM, new AudioCancellation()));
            assertEquals(limits.maxBodyBytes() == body.length - 1
                    ? RadioFailure.Code.PLAYLIST_TOO_LARGE : RadioFailure.Code.RESOURCE_LIMIT, error.code());
            assertTrue(page.disconnected);
            assertTrue(page.bodyClosed);
        }
        FixtureConnection exact = page(ALBUM_JSON);
        assertEquals(2, resolver(exact, ALLOW_ALL,
                new BandcampMetadataResolver.Limits(body.length, 100, 8192, 5))
                .resolveTracks(ALBUM, new AudioCancellation()).program().tracks().size());
    }

    @Test
    void rejectsMalformedDataAndUntrustedStoredUrlsWithoutReturningPartialMetadata() throws Exception {
        for (String json : List.of("{}", "not json", TRACK_JSON.replace("\"track\"", "\"other\""),
                "{\"artist\":\"Artist\",\"current\":4}",
                "{\"artist\":\"Artist\",\"current\":{\"type\":\"album\",\"title\":\"Album\"},\"trackinfo\":{}}",
                ALBUM_JSON.replace("/track/first", "http://127.0.0.1/private"),
                ALBUM_JSON.replace("/track/first", "https://notbandcamp.com/track/first"),
                ALBUM_JSON.replace("/track/first", "https://user@artist.bandcamp.com/track/first"),
                ALBUM_JSON.replace("/track/first", "file:///etc/passwd"))) {
            FixtureConnection page = page(json);
            assertThrows(IOException.class, () -> resolver(page, ALLOW_ALL,
                    BandcampMetadataResolver.Limits.DEFAULT).resolveTracks(ALBUM, new AudioCancellation()));
            assertTrue(page.disconnected);
            assertTrue(page.bodyClosed);
        }
        FixtureConnection blocked = page(ALBUM_JSON);
        AudioNetworkPolicy policy = uri -> {
            if (uri.getPath().equals("/track/second")) {
                throw new RadioTransportException(RadioFailure.Code.BLOCKED_ADDRESS, false, "blocked", null);
            }
        };
        RadioTransportException error = assertThrows(RadioTransportException.class,
                () -> resolver(blocked, policy, BandcampMetadataResolver.Limits.DEFAULT)
                        .resolveTracks(ALBUM, new AudioCancellation()));
        assertEquals(RadioFailure.Code.BLOCKED_ADDRESS, error.code());
        assertTrue(blocked.disconnected);
    }

    @Test
    void redirectDestinationsAreCheckedBeforeOpeningAndFinalPageMustStayOnBandcamp() throws Exception {
        FixtureConnection redirect = new FixtureConnection(ALBUM, 302, new byte[0]);
        URI forbidden = URI.create("https://private.example/page");
        redirect.headers = Map.of("Location", List.of(forbidden.toString()));
        AtomicInteger requests = new AtomicInteger();
        AudioNetworkPolicy policy = uri -> {
            if (uri.equals(forbidden)) {
                throw new RadioTransportException(RadioFailure.Code.BLOCKED_ADDRESS, false, "blocked", null);
            }
        };
        var transport = new RadioHttpTransportImpl(Proxy.NO_PROXY, policy, Duration.ofSeconds(1),
                Duration.ofSeconds(1), 5, (uri, proxy) -> {
            requests.incrementAndGet();
            return redirect;
        });
        var resolver = new BandcampMetadataResolver(transport, policy, BandcampMetadataResolver.Limits.DEFAULT);
        assertEquals(RadioFailure.Code.BLOCKED_ADDRESS, assertThrows(RadioTransportException.class,
                () -> resolver.resolveTracks(ALBUM, new AudioCancellation())).code());
        assertEquals(1, requests.get());
        assertTrue(redirect.disconnected);

        FixtureConnection outside = new FixtureConnection(forbidden, 200,
                html(TRACK_JSON).getBytes(StandardCharsets.UTF_8));
        var untrusted = new BandcampMetadataResolver((request, cancellation) -> response(outside, cancellation),
                ALLOW_ALL, BandcampMetadataResolver.Limits.DEFAULT);
        assertEquals(RadioFailure.Code.INVALID_URL, assertThrows(RadioTransportException.class,
                () -> untrusted.resolveTracks(ALBUM, new AudioCancellation())).code());
        assertTrue(outside.disconnected);
    }

    @Test
    void relativeTrackLinksUseTheValidatedFinalPageUri() throws Exception {
        URI redirected = URI.create("https://artist.bandcamp.com/catalog/album");
        FixtureConnection start = new FixtureConnection(ALBUM, 302, new byte[0]);
        start.headers = Map.of("Location", List.of(redirected.toString()));
        FixtureConnection finalPage = new FixtureConnection(redirected, 200,
                html(ALBUM_JSON.replace("/track/first", "first")).getBytes(StandardCharsets.UTF_8));
        var transport = new RadioHttpTransportImpl(Proxy.NO_PROXY, ALLOW_ALL, Duration.ofSeconds(1),
                Duration.ofSeconds(1), 5, (uri, proxy) -> {
            if (uri.equals(ALBUM)) {
                return start;
            }
            assertEquals(redirected, uri);
            return finalPage;
        });
        var tracks = new BandcampMetadataResolver(transport, ALLOW_ALL, BandcampMetadataResolver.Limits.DEFAULT)
                .resolveTracks(ALBUM, new AudioCancellation());
        assertEquals(ALBUM.toString(), tracks.album().orElseThrow().source());
        assertEquals("https://artist.bandcamp.com/catalog/first", tracks.program().tracks().get(0).source());
        assertTrue(start.disconnected);
        assertTrue(finalPage.disconnected);
    }

    @Test
    void cancellationReleasesARequestBlockedReadingMetadata() throws Exception {
        CountDownLatch reading = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        AudioCancellation cancellation = new AudioCancellation();
        FixtureConnection page = new FixtureConnection(ALBUM, 200, new byte[0]) {
            @Override
            public InputStream getInputStream() {
                return new InputStream() {
                    @Override
                    public int read() throws IOException {
                        reading.countDown();
                        try {
                            if (!release.await(5, TimeUnit.SECONDS)) {
                                throw new IOException("Fixture body remained blocked");
                            }
                        } catch (InterruptedException exception) {
                            Thread.currentThread().interrupt();
                            throw new IOException(exception);
                        }
                        return -1;
                    }

                    @Override
                    public void close() {
                        bodyClosed = true;
                        closed.countDown();
                        release.countDown();
                    }
                };
            }
        };
        var resolver = resolver(page, ALLOW_ALL, BandcampMetadataResolver.Limits.DEFAULT);
        CompletableFuture<?> request = CompletableFuture.runAsync(() -> {
            try {
                resolver.resolveTracks(ALBUM, cancellation);
            } catch (IOException exception) {
                throw new CompletionException(exception);
            }
        });
        try {
            assertTrue(reading.await(2, TimeUnit.SECONDS));
            cancellation.cancel();
            assertInstanceOf(CancellationException.class, assertThrows(ExecutionException.class,
                    () -> request.get(2, TimeUnit.SECONDS)).getCause());
            assertTrue(page.disconnected);
            assertTrue(page.bodyClosed);
        } finally {
            release.countDown();
        }
    }

    @Test
    void enforcesDnsPolicyAndStrictRecognitionBeforeConnecting() throws Exception {
        var policy = new DefaultRadioNetworkPolicy(() -> false,
                host -> new InetAddress[]{InetAddress.getByAddress(new byte[]{127, 0, 0, 1})});
        var transport = new RadioHttpTransportImpl(Proxy.NO_PROXY, policy, Duration.ofSeconds(1),
                Duration.ofSeconds(1), 5, (uri, proxy) -> {
            throw new AssertionError("Blocked DNS result reached the connection factory");
        });
        var resolver = new BandcampMetadataResolver(transport, policy, BandcampMetadataResolver.Limits.DEFAULT);
        assertEquals(RadioFailure.Code.BLOCKED_ADDRESS, assertThrows(RadioTransportException.class,
                () -> resolver.resolveTracks(ALBUM, new AudioCancellation())).code());
        for (String url : List.of("https://notbandcamp.com/album/x", "https://bandcamp.com.example/x",
                "https://user@artist.bandcamp.com/x", "ftp://artist.bandcamp.com/x")) {
            assertFalse(BandcampPageReader.supports(URI.create(url)));
            assertEquals(RadioFailure.Code.INVALID_URL, assertThrows(RadioTransportException.class,
                    () -> resolver.resolveTracks(URI.create(url), new AudioCancellation())).code());
        }
        assertTrue(BandcampPageReader.supports(URI.create("https://Artist.Bandcamp.com/track/example")));
    }

    @Test
    void cancellationAndFailedStatusesCloseOwnedResponses() throws Exception {
        AudioCancellation cancellation = new AudioCancellation();
        FixtureConnection page = new FixtureConnection(ALBUM, 200, new byte[0]) {
            @Override
            public InputStream getInputStream() {
                return new InputStream() {
                    @Override
                    public int read() {
                        cancellation.cancel();
                        cancellation.throwIfCancelled();
                        return -1;
                    }

                    @Override
                    public void close() {
                        bodyClosed = true;
                        closed.countDown();
                    }
                };
            }
        };
        assertThrows(CancellationException.class, () -> resolver(page, ALLOW_ALL,
                BandcampMetadataResolver.Limits.DEFAULT).resolveTracks(ALBUM, cancellation));
        cancellation.cancel();
        assertThrows(CancellationException.class, () -> resolver(page, ALLOW_ALL,
                BandcampMetadataResolver.Limits.DEFAULT).resolveTracks(ALBUM, cancellation));
        // Cancellation cleanup is asynchronous; wait only for its observable resource release.
        assertTrue(page.closed.await(2, TimeUnit.SECONDS));
        assertTrue(page.disconnected);
        assertTrue(page.bodyClosed);
        FixtureConnection failed = new FixtureConnection(ALBUM, 503, new byte[0]);
        assertEquals(RadioFailure.Code.HTTP_STATUS, assertThrows(RadioTransportException.class,
                () -> resolver(failed, ALLOW_ALL, BandcampMetadataResolver.Limits.DEFAULT)
                        .resolveTracks(ALBUM, new AudioCancellation())).code());
        assertTrue(failed.disconnected);
    }

    private static BandcampMetadataResolver resolver(FixtureConnection page, AudioNetworkPolicy outputPolicy,
                                                      BandcampMetadataResolver.Limits limits) {
        var transport = new RadioHttpTransportImpl(Proxy.NO_PROXY, ALLOW_ALL, Duration.ofSeconds(1),
                Duration.ofSeconds(1), 5, (uri, proxy) -> page);
        return new BandcampMetadataResolver(transport, outputPolicy, limits);
    }

    private static AudioHttpResponse response(FixtureConnection connection, AudioCancellation cancellation) {
        var exchange = new RadioHttpTransportImpl.ActiveExchange();
        exchange.installConnection(connection);
        InputStream body = connection.getInputStream();
        exchange.installBody(connection, body);
        return new AudioHttpResponse(URI.create(connection.getURL().toString()), 200, Map.of(), body,
                0, cancellation, exchange);
    }

    private static FixtureConnection page(String json) throws IOException {
        return new FixtureConnection(ALBUM, 200, html(json).getBytes(StandardCharsets.UTF_8));
    }

    private static String html(String json) {
        return "<div data-tralbum=\"" + json.replace("&", "&amp;").replace("\"", "&quot;") + "\"></div>";
    }

    private static class FixtureConnection extends HttpURLConnection {
        private final int status;
        private final byte[] body;
        private Map<String, List<String>> headers = Map.of();
        volatile boolean disconnected;
        volatile boolean bodyClosed;
        final CountDownLatch closed = new CountDownLatch(1);

        private FixtureConnection(URI uri, int status, byte[] body) throws IOException {
            super(uri.toURL());
            this.status = status;
            this.body = body;
        }

        @Override
        public void connect() {
            this.connected = true;
        }

        @Override
        public void disconnect() {
            this.disconnected = true;
            this.connected = false;
        }

        @Override
        public boolean usingProxy() {
            return false;
        }

        @Override
        public void setAuthenticator(Authenticator authenticator) {
        }

        @Override
        public int getResponseCode() {
            return this.status;
        }

        @Override
        public Map<String, List<String>> getHeaderFields() {
            return this.headers;
        }

        @Override
        public String getHeaderField(String name) {
            return this.headers.entrySet().stream()
                    .filter(entry -> entry.getKey().equalsIgnoreCase(name))
                    .map(entry -> entry.getValue().get(0)).findFirst().orElse(null);
        }

        @Override
        public InputStream getInputStream() {
            return new ByteArrayInputStream(this.body) {
                @Override
                public void close() {
                    bodyClosed = true;
                    closed.countDown();
                }
            };
        }
    }
}
