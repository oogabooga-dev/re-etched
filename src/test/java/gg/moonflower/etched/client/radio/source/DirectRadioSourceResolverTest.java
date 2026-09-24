package gg.moonflower.etched.client.radio.source;

import com.sun.net.httpserver.Headers;
import gg.moonflower.etched.client.radio.RadioFailure;
import gg.moonflower.etched.client.radio.PlaybackSession;
import gg.moonflower.etched.client.radio.net.AudioNetworkPolicy;
import gg.moonflower.etched.client.radio.net.RadioHttpTransportImpl;
import gg.moonflower.etched.client.radio.net.RadioTransportException;
import gg.moonflower.etched.client.radio.net.TestHttpServer;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.Proxy;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DirectRadioSourceResolverTest {

    private static final Duration TEST_TIMEOUT = Duration.ofSeconds(2);
    private static final AudioNetworkPolicy ALLOW_TEST_SERVER = uri -> {
    };

    @Test
    void returnsTheOriginalMp3ResponseWithSniffedBytesRestored() throws Exception {
        byte[] audio = "ID3-complete-audio-body".getBytes(StandardCharsets.US_ASCII);
        AtomicInteger requests = new AtomicInteger();
        AtomicReference<Headers> headers = new AtomicReference<>();
        try (TestHttpServer server = new TestHttpServer()) {
            server.handle("/live", exchange -> {
                requests.incrementAndGet();
                headers.set(exchange.getRequestHeaders());
                exchange.getResponseHeaders().add("Content-Type", "audio/mpeg; charset=binary");
                respond(exchange, 200, audio);
            });

            try (RadioResolvedSource source = resolver().resolve(server.uri("/live"), context())) {
                assertEquals(RadioResolvedSource.Format.MP3, source.format());
                assertEquals(server.uri("/live"), source.uri());
                assertArrayEquals(audio, source.body().readAllBytes());
            }

            assertEquals(1, requests.get());
            assertEquals("1", headers.get().getFirst("Icy-MetaData"));
        }
    }

    @Test
    void detectsOggFromSignatureWhenContentTypeIsGeneric() throws Exception {
        byte[] audio = "OggS-independent-stream".getBytes(StandardCharsets.US_ASCII);
        try (TestHttpServer server = new TestHttpServer()) {
            server.handle("/unknown", exchange -> {
                exchange.getResponseHeaders().add("Content-Type", "application/octet-stream");
                respond(exchange, 200, audio);
            });

            try (RadioResolvedSource source = resolver().resolve(server.uri("/unknown"), context())) {
                assertEquals(RadioResolvedSource.Format.OGG, source.format());
                assertArrayEquals(audio, source.body().readAllBytes());
            }
        }
    }

    @Test
    void detectsSuffixlessPlaylistsWithWhitespaceAndBareRelativeEntries() throws Exception {
        try (TestHttpServer server = new TestHttpServer()) {
            server.handle("/m3u", exchange -> {
                exchange.getResponseHeaders().add("Content-Type", "application/octet-stream");
                respond(exchange, 200, bytes("   station.mp3\n"));
            });
            server.handle("/pls", exchange -> {
                exchange.getResponseHeaders().add("Content-Type", "application/octet-stream");
                respond(exchange, 200, bytes("  [playlist]\nFile1=station.mp3\n"));
            });
            server.handle("/station.mp3", exchange -> {
                exchange.getResponseHeaders().add("Content-Type", "audio/mpeg");
                respond(exchange, 200, bytes("ID3-audio"));
            });

            try (RadioResolvedSource m3u = resolver().resolve(server.uri("/m3u"), context());
                 RadioResolvedSource pls = resolver().resolve(server.uri("/pls"), context())) {
                assertEquals(server.uri("/station.mp3"), m3u.uri());
                assertEquals(server.uri("/station.mp3"), pls.uri());
            }
        }
    }

    @Test
    void playlistLookingCandidatesStillRequestIcyMetadataWhenTheyReturnAudio() throws Exception {
        AtomicReference<Headers> headers = new AtomicReference<>();
        try (TestHttpServer server = new TestHttpServer()) {
            server.handle("/station.m3u", exchange -> {
                headers.set(exchange.getRequestHeaders());
                exchange.getResponseHeaders().add("Content-Type", "audio/mpeg");
                respond(exchange, 200, bytes("ID3-audio"));
            });

            try (RadioResolvedSource source = resolver().resolve(server.uri("/station.m3u"), context())) {
                assertEquals(RadioResolvedSource.Format.MP3, source.format());
            }
            assertEquals("1", headers.get().getFirst("Icy-MetaData"));
        }
    }

    @Test
    void repeatedResolutionCreatesIndependentResponses() throws Exception {
        AtomicInteger requests = new AtomicInteger();
        try (TestHttpServer server = new TestHttpServer()) {
            server.handle("/live", exchange -> {
                int request = requests.incrementAndGet();
                exchange.getResponseHeaders().add("Content-Type", "audio/mpeg");
                respond(exchange, 200, bytes("ID3-stream-" + request));
            });
            DirectRadioSourceResolver resolver = resolver();
            RadioHttpTransportImpl transport = new RadioHttpTransportImpl(
                    Proxy.NO_PROXY, ALLOW_TEST_SERVER, TEST_TIMEOUT, TEST_TIMEOUT, 5);
            PlaybackSession firstSession = new PlaybackSession();
            PlaybackSession secondSession = new PlaybackSession();
            PlaybackSession.Attempt firstAttempt = firstSession.start(server.uri("/live").toString());
            PlaybackSession.Attempt secondAttempt = secondSession.start(server.uri("/live").toString());
            AudioResolveContext firstContext = new AudioResolveContext(
                    transport, ALLOW_TEST_SERVER, firstAttempt.cancellation(), limits());
            AudioResolveContext secondContext = new AudioResolveContext(
                    transport, ALLOW_TEST_SERVER, secondAttempt.cancellation(), limits());

            try (RadioResolvedSource first = resolver.resolve(server.uri("/live"), firstContext);
                 RadioResolvedSource second = resolver.resolve(server.uri("/live"), secondContext)) {
                firstSession.stop();
                assertThrows(CancellationException.class, () -> first.body().read());
                assertArrayEquals(bytes("ID3-stream-2"), second.body().readAllBytes());
            }
            assertEquals(2, requests.get());
        }
    }

    @Test
    void binaryAudioSignatureDoesNotWaitForTheStreamToEnd() throws Exception {
        CountDownLatch signatureSent = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        try (TestHttpServer server = new TestHttpServer()) {
            server.handle("/live", exchange -> {
                exchange.sendResponseHeaders(200, 0);
                exchange.getResponseBody().write(new byte[]{(byte) 0xFF, (byte) 0xFB, 0, 0});
                exchange.getResponseBody().flush();
                signatureSent.countDown();
                await(release);
                exchange.close();
            });
            CompletableFuture<RadioResolvedSource> result = CompletableFuture.supplyAsync(() -> {
                try {
                    return resolver().resolve(server.uri("/live"), context());
                } catch (RadioSourceException exception) {
                    throw new java.util.concurrent.CompletionException(exception);
                }
            });

            try {
                assertTrue(signatureSent.await(1, TimeUnit.SECONDS));
                try (RadioResolvedSource source = result.get(1, TimeUnit.SECONDS)) {
                    assertEquals(RadioResolvedSource.Format.MP3, source.format());
                }
            } finally {
                release.countDown();
            }
        }
    }

    @Test
    void resolvesRelativeM3uEntriesAndUsesRecoverableFallback() throws Exception {
        AtomicInteger primaryRequests = new AtomicInteger();
        AtomicInteger backupRequests = new AtomicInteger();
        byte[] audio = "ID3-backup-stream".getBytes(StandardCharsets.US_ASCII);
        try (TestHttpServer server = new TestHttpServer()) {
            server.handle("/lists/stations.m3u", exchange -> {
                exchange.getResponseHeaders().add("Content-Type", "audio/x-mpegurl");
                respond(exchange, 200, bytes("../primary\n../backup\n"));
            });
            server.handle("/primary", exchange -> {
                primaryRequests.incrementAndGet();
                respond(exchange, 503, new byte[0]);
            });
            server.handle("/backup", exchange -> {
                backupRequests.incrementAndGet();
                exchange.getResponseHeaders().add("Content-Type", "audio/mpeg");
                respond(exchange, 200, audio);
            });

            try (RadioResolvedSource source = resolver().resolve(
                    server.uri("/lists/stations.m3u"), context())) {
                assertEquals(server.uri("/backup"), source.uri());
                assertEquals(List.of(server.uri("/primary"), server.uri("/backup")),
                        source.stationEndpoints());
                assertArrayEquals(audio, source.body().readAllBytes());
            }
            assertEquals(1, primaryRequests.get());
            assertEquals(1, backupRequests.get());
        }
    }

    @Test
    void usesTheRedirectedPlaylistUriAsTheRelativeBase() throws Exception {
        byte[] audio = "ID3-redirected-playlist".getBytes(StandardCharsets.US_ASCII);
        try (TestHttpServer server = new TestHttpServer()) {
            server.handle("/start.m3u", exchange -> {
                exchange.getResponseHeaders().add("Location", "/generated/stations");
                exchange.sendResponseHeaders(302, -1);
                exchange.close();
            });
            server.handle("/generated/stations", exchange -> {
                exchange.getResponseHeaders().add("Content-Type", "application/octet-stream");
                respond(exchange, 200, bytes("stream\n"));
            });
            server.handle("/generated/stream", exchange -> {
                exchange.getResponseHeaders().add("Content-Type", "audio/mpeg");
                respond(exchange, 200, audio);
            });

            try (RadioResolvedSource source = resolver().resolve(server.uri("/start.m3u"), context())) {
                assertEquals(server.uri("/generated/stream"), source.uri());
                assertArrayEquals(audio, source.body().readAllBytes());
            }
        }
    }

    @Test
    void resolvesPlsFilesUsingNumericFallbackOrder() throws Exception {
        AtomicInteger firstRequests = new AtomicInteger();
        AtomicInteger secondRequests = new AtomicInteger();
        try (TestHttpServer server = new TestHttpServer()) {
            server.handle("/stations.pls", exchange -> respond(exchange, 200, bytes("""
                    [playlist]
                    File2=second
                    File1=first
                    Version=2
                    """)));
            server.handle("/first", exchange -> {
                firstRequests.incrementAndGet();
                respond(exchange, 500, new byte[0]);
            });
            server.handle("/second", exchange -> {
                secondRequests.incrementAndGet();
                exchange.getResponseHeaders().add("Content-Type", "audio/ogg");
                respond(exchange, 200, bytes("OggS-backup"));
            });

            try (RadioResolvedSource source = resolver().resolve(server.uri("/stations.pls"), context())) {
                assertEquals(server.uri("/second"), source.uri());
                assertEquals(RadioResolvedSource.Format.OGG, source.format());
            }
            assertEquals(1, firstRequests.get());
            assertEquals(1, secondRequests.get());
        }
    }

    @Test
    void validatesEveryPlaylistEntryBeforeOpeningTheFirstStation() throws Exception {
        AtomicInteger stationRequests = new AtomicInteger();
        try (TestHttpServer server = new TestHttpServer()) {
            URI blocked = URI.create("http://private.example/live");
            server.handle("/stations.m3u", exchange -> respond(exchange, 200,
                    bytes(server.uri("/station") + "\n" + blocked + "\n")));
            server.handle("/station", exchange -> {
                stationRequests.incrementAndGet();
                respond(exchange, 200, bytes("ID3-audio"));
            });
            AudioNetworkPolicy policy = uri -> {
                if (uri.equals(blocked)) {
                    throw new RadioTransportException(RadioFailure.Code.BLOCKED_ADDRESS, false,
                            "blocked test address", null);
                }
            };

            RadioSourceException exception = assertThrows(RadioSourceException.class,
                    () -> resolver().resolve(server.uri("/stations.m3u"), context(policy, limits())));

            assertEquals(RadioFailure.Code.BLOCKED_ADDRESS, exception.code());
            assertEquals(0, stationRequests.get());
        }
    }

    @Test
    void rejectsHlsAndAacBeforeTheDecoderStage() throws Exception {
        AtomicInteger hlsRequests = new AtomicInteger();
        try (TestHttpServer server = new TestHttpServer()) {
            server.handle("/live.m3u8", exchange -> {
                hlsRequests.incrementAndGet();
                respond(exchange, 200, bytes("#EXTM3U\n#EXT-X-VERSION:3\n"));
            });
            server.handle("/live", exchange -> {
                exchange.getResponseHeaders().add("Content-Type", "audio/aacp");
                respond(exchange, 200, new byte[]{(byte) 0xFF, (byte) 0xF1, 0, 0});
            });

            RadioSourceException hls = assertThrows(RadioSourceException.class,
                    () -> resolver().resolve(server.uri("/live.m3u8"), context()));
            RadioSourceException aac = assertThrows(RadioSourceException.class,
                    () -> resolver().resolve(server.uri("/live"), context()));

            assertEquals(RadioFailure.Code.UNSUPPORTED_HLS, hls.code());
            assertEquals(RadioFailure.Code.UNSUPPORTED_AAC, aac.code());
            assertEquals(1, hlsRequests.get());
        }
    }

    @Test
    void conclusiveAudioSignaturesOverrideMisleadingSuffixAndContentType() throws Exception {
        try (TestHttpServer server = new TestHttpServer()) {
            server.handle("/wrong.aac", exchange -> {
                exchange.getResponseHeaders().add("Content-Type", "audio/aacp");
                respond(exchange, 200, bytes("OggS-audio"));
            });
            server.handle("/wrong.m3u8", exchange -> {
                exchange.getResponseHeaders().add("Content-Type", "application/vnd.apple.mpegurl");
                respond(exchange, 200, new byte[]{(byte) 0xFF, (byte) 0xFB, 0, 0});
            });
            server.handle("/tagged.aac", exchange -> {
                exchange.getResponseHeaders().add("Content-Type", "audio/aac");
                respond(exchange, 200, bytes("ID3-tagged-aac"));
            });

            try (RadioResolvedSource ogg = resolver().resolve(server.uri("/wrong.aac"), context());
                 RadioResolvedSource mp3 = resolver().resolve(server.uri("/wrong.m3u8"), context())) {
                assertEquals(RadioResolvedSource.Format.OGG, ogg.format());
                assertEquals(RadioResolvedSource.Format.MP3, mp3.format());
            }
            RadioSourceException aac = assertThrows(RadioSourceException.class,
                    () -> resolver().resolve(server.uri("/tagged.aac"), context()));
            assertEquals(RadioFailure.Code.UNSUPPORTED_AAC, aac.code());
        }
    }

    @Test
    void enforcesPlaylistBodyAndNestingLimits() throws Exception {
        RadioResolveLimits smallBody = new RadioResolveLimits(4, 16, 4, 64, 1, 8);
        RadioResolveLimits shallow = new RadioResolveLimits(4, 256, 4, 64, 1, 8);
        RadioResolveLimits cumulativeEntries = new RadioResolveLimits(4, 256, 2, 64, 2, 8);
        try (TestHttpServer server = new TestHttpServer()) {
            server.handle("/large.m3u", exchange -> respond(exchange, 200,
                    bytes("https://radio.example/stream\n")));
            server.handle("/outer.m3u", exchange -> respond(exchange, 200,
                    bytes(server.uri("/inner.m3u") + "\n")));
            server.handle("/inner.m3u", exchange -> respond(exchange, 200,
                    bytes(server.uri("/audio") + "\n")));
            server.handle("/entry-root.m3u", exchange -> respond(exchange, 200,
                    bytes("entry-inner.m3u\n")));
            server.handle("/entry-inner.m3u", exchange -> respond(exchange, 200,
                    bytes("one\ntwo\n")));

            RadioSourceException large = assertThrows(RadioSourceException.class,
                    () -> resolver().resolve(server.uri("/large.m3u"),
                            context(ALLOW_TEST_SERVER, smallBody)));
            RadioSourceException nested = assertThrows(RadioSourceException.class,
                    () -> resolver().resolve(server.uri("/outer.m3u"),
                            context(ALLOW_TEST_SERVER, shallow)));
            RadioSourceException entries = assertThrows(RadioSourceException.class,
                    () -> resolver().resolve(server.uri("/entry-root.m3u"),
                            context(ALLOW_TEST_SERVER, cumulativeEntries)));

            assertEquals(RadioFailure.Code.PLAYLIST_TOO_LARGE, large.code());
            assertEquals(RadioFailure.Code.RESOURCE_LIMIT, nested.code());
            assertEquals(RadioFailure.Code.RESOURCE_LIMIT, entries.code());
        }
    }

    @Test
    void enforcesPlaylistSizeWithoutContentLengthAndTheAggregateRedirectBudget() throws Exception {
        RadioResolveLimits smallBody = new RadioResolveLimits(4, 16, 4, 64, 2, 8);
        RadioResolveLimits twoSteps = new RadioResolveLimits(4, 64, 4, 64, 2, 2);
        RadioResolveLimits twoRequestSteps = new RadioResolveLimits(4, 256, 4, 64, 2, 2);
        AtomicInteger finalRequests = new AtomicInteger();
        AtomicInteger crossRequestFinalRequests = new AtomicInteger();
        try (TestHttpServer server = new TestHttpServer()) {
            server.handle("/large.m3u", exchange -> {
                exchange.sendResponseHeaders(200, 0);
                exchange.getResponseBody().write(bytes("https://radio.example/stream\n"));
                exchange.close();
            });
            server.handle("/one", exchange -> redirect(exchange, "/two"));
            server.handle("/two", exchange -> redirect(exchange, "/final"));
            server.handle("/final", exchange -> {
                finalRequests.incrementAndGet();
                respond(exchange, 200, bytes("ID3-audio"));
            });
            server.handle("/budget.m3u", exchange -> respond(exchange, 200,
                    bytes("budget-station\n")));
            server.handle("/budget-station", exchange -> redirect(exchange, "/budget-final"));
            server.handle("/budget-final", exchange -> {
                crossRequestFinalRequests.incrementAndGet();
                respond(exchange, 200, bytes("ID3-audio"));
            });

            RadioSourceException large = assertThrows(RadioSourceException.class,
                    () -> resolver().resolve(server.uri("/large.m3u"),
                            context(ALLOW_TEST_SERVER, smallBody)));
            RadioSourceException redirects = assertThrows(RadioSourceException.class,
                    () -> resolver().resolve(server.uri("/one"),
                            context(ALLOW_TEST_SERVER, twoSteps)));
            RadioSourceException crossRequestRedirect = assertThrows(RadioSourceException.class,
                    () -> resolver().resolve(server.uri("/budget.m3u"),
                            context(ALLOW_TEST_SERVER, twoRequestSteps)));

            assertEquals(RadioFailure.Code.PLAYLIST_TOO_LARGE, large.code());
            assertEquals(RadioFailure.Code.RESOURCE_LIMIT, redirects.code());
            assertEquals(RadioFailure.Code.RESOURCE_LIMIT, crossRequestRedirect.code());
            assertEquals(0, finalRequests.get());
            assertEquals(0, crossRequestFinalRequests.get());
        }
    }

    @Test
    void acceptsAPlaylistBodyExactlyAtTheConfiguredLimit() throws Exception {
        RadioResolveLimits exact = new RadioResolveLimits(4, 6, 2, 16, 1, 4);
        try (TestHttpServer server = new TestHttpServer()) {
            server.handle("/exact.m3u", exchange -> respond(exchange, 200, bytes("audio\n")));
            server.handle("/audio", exchange -> {
                exchange.getResponseHeaders().add("Content-Type", "audio/mpeg");
                respond(exchange, 200, bytes("ID3-audio"));
            });

            try (RadioResolvedSource source = resolver().resolve(
                    server.uri("/exact.m3u"), context(ALLOW_TEST_SERVER, exact))) {
                assertEquals(server.uri("/audio"), source.uri());
            }
        }
    }

    @Test
    void permitsRepeatedPlaylistTraversalOutsideTheActiveRecursionPath() throws Exception {
        AtomicInteger sharedRequests = new AtomicInteger();
        try (TestHttpServer server = new TestHttpServer()) {
            server.handle("/root.m3u", exchange -> respond(exchange, 200,
                    bytes(server.uri("/left.m3u") + "\n" + server.uri("/right.m3u") + "\n")));
            server.handle("/left.m3u", exchange -> respond(exchange, 200,
                    bytes(server.uri("/shared.m3u") + "\n")));
            server.handle("/right.m3u", exchange -> respond(exchange, 200,
                    bytes(server.uri("/shared.m3u") + "\n" + server.uri("/good") + "\n")));
            server.handle("/shared.m3u", exchange -> {
                sharedRequests.incrementAndGet();
                respond(exchange, 200, bytes(server.uri("/dead") + "\n"));
            });
            server.handle("/dead", exchange -> respond(exchange, 503, new byte[0]));
            server.handle("/good", exchange -> {
                exchange.getResponseHeaders().add("Content-Type", "audio/mpeg");
                respond(exchange, 200, bytes("ID3-good"));
            });

            try (RadioResolvedSource source = resolver().resolve(server.uri("/root.m3u"), context())) {
                assertEquals(server.uri("/good"), source.uri());
            }
            assertEquals(2, sharedRequests.get());
        }
    }

    @Test
    void rejectsAnActivePlaylistCycle() throws Exception {
        try (TestHttpServer server = new TestHttpServer()) {
            server.handle("/a.m3u", exchange -> respond(exchange, 200,
                    bytes(server.uri("/b.m3u") + "\n")));
            server.handle("/b.m3u", exchange -> respond(exchange, 200,
                    bytes(server.uri("/a.m3u") + "\n")));

            RadioSourceException exception = assertThrows(RadioSourceException.class,
                    () -> resolver().resolve(server.uri("/a.m3u"), context()));

            assertEquals(RadioFailure.Code.RESOURCE_LIMIT, exception.code());
        }
    }

    @Test
    void classifiesHttpStatusesForFallbackAndReconnect() throws Exception {
        try (TestHttpServer server = new TestHttpServer()) {
            server.handle("/retry", exchange -> {
                exchange.getResponseHeaders().add("Retry-After", "7");
                respond(exchange, 429, new byte[0]);
            });
            server.handle("/fatal", exchange -> respond(exchange, 404, new byte[0]));
            server.handle("/not-implemented", exchange -> respond(exchange, 501, new byte[0]));

            RadioSourceException retry = assertThrows(RadioSourceException.class,
                    () -> resolver().resolve(server.uri("/retry"), context()));
            RadioSourceException fatal = assertThrows(RadioSourceException.class,
                    () -> resolver().resolve(server.uri("/fatal"), context()));
            RadioSourceException notImplemented = assertThrows(RadioSourceException.class,
                    () -> resolver().resolve(server.uri("/not-implemented"), context()));

            assertEquals(RadioFailure.Code.HTTP_STATUS, retry.code());
            assertEquals(RadioFailure.Code.HTTP_STATUS, fatal.code());
            assertFalse(fatal.recoverable());
            assertFalse(notImplemented.recoverable());
            assertTrue(retry.recoverable());
            assertEquals(7_000L, retry.retryAfterMillis());
        }
    }

    @Test
    void boundsAndIgnoresInvalidRetryAfterHints() throws Exception {
        try (TestHttpServer server = new TestHttpServer()) {
            server.handle("/bounded", exchange -> {
                exchange.getResponseHeaders().add("Retry-After", "999999999999999999999");
                respond(exchange, 429, new byte[0]);
            });
            server.handle("/capped", exchange -> {
                exchange.getResponseHeaders().add("Retry-After", "120");
                respond(exchange, 429, new byte[0]);
            });

            RadioSourceException invalid = assertThrows(RadioSourceException.class,
                    () -> resolver().resolve(server.uri("/bounded"), context()));
            RadioSourceException capped = assertThrows(RadioSourceException.class,
                    () -> resolver().resolve(server.uri("/capped"), context()));

            assertEquals(RadioFailure.NO_RETRY_AFTER, invalid.retryAfterMillis());
            assertEquals(30_000L, capped.retryAfterMillis());
        }
    }

    @Test
    void playlistFallbackPreservesLargestRetryAfterHint() throws Exception {
        try (TestHttpServer server = new TestHttpServer()) {
            server.handle("/stations.m3u", exchange -> respond(exchange, 200, bytes(
                    server.uri("/limited") + "\n" + server.uri("/unavailable") + "\n")));
            server.handle("/limited", exchange -> {
                exchange.getResponseHeaders().add("Retry-After", "30");
                respond(exchange, 429, new byte[0]);
            });
            server.handle("/unavailable", exchange -> respond(exchange, 503, new byte[0]));

            RadioSourceException failure = assertThrows(RadioSourceException.class,
                    () -> resolver().resolve(server.uri("/stations.m3u"), context()));

            assertTrue(failure.recoverable());
            assertEquals(30_000L, failure.retryAfterMillis());
        }
    }

    @Test
    void acceptsBoundedHttpDateRetryAfterHint() throws Exception {
        try (TestHttpServer server = new TestHttpServer()) {
            server.handle("/date", exchange -> {
                String retryAt = ZonedDateTime.now(ZoneOffset.UTC).plusMinutes(2)
                        .format(DateTimeFormatter.RFC_1123_DATE_TIME);
                exchange.getResponseHeaders().add("Retry-After", retryAt);
                respond(exchange, 429, new byte[0]);
            });

            RadioSourceException failure = assertThrows(RadioSourceException.class,
                    () -> resolver().resolve(server.uri("/date"), context()));

            assertEquals(30_000L, failure.retryAfterMillis());
        }
    }

    @Test
    void cancellationWhileReadingAPlaylistStopsResolution() throws Exception {
        CountDownLatch bodyStarted = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        PlaybackSession session = new PlaybackSession();
        PlaybackSession.Attempt attempt = session.start("http://radio.example/live");
        try (TestHttpServer server = new TestHttpServer()) {
            server.handle("/blocked.m3u", exchange -> {
                exchange.sendResponseHeaders(200, 0);
                exchange.getResponseBody().write(bytes("#EXT"));
                exchange.getResponseBody().flush();
                bodyStarted.countDown();
                await(release);
                exchange.close();
            });
            RadioHttpTransportImpl transport = new RadioHttpTransportImpl(
                    Proxy.NO_PROXY, ALLOW_TEST_SERVER, TEST_TIMEOUT, TEST_TIMEOUT, 5);
            AudioResolveContext context = new AudioResolveContext(
                    transport, ALLOW_TEST_SERVER, attempt.cancellation(), limits());
            CompletableFuture<RadioResolvedSource> result = CompletableFuture.supplyAsync(() -> {
                try {
                    return resolver().resolve(server.uri("/blocked.m3u"), context);
                } catch (RadioSourceException exception) {
                    throw new java.util.concurrent.CompletionException(exception);
                }
            });

            try {
                assertTrue(bodyStarted.await(1, TimeUnit.SECONDS));
                session.stop();
                release.countDown();
                ExecutionException exception = assertThrows(ExecutionException.class,
                        () -> result.get(2, TimeUnit.SECONDS));
                assertInstanceOf(CancellationException.class, exception.getCause());
            } finally {
                release.countDown();
            }
        }
    }

    private static DirectRadioSourceResolver resolver() {
        return new DirectRadioSourceResolver();
    }

    private static AudioResolveContext context() {
        return context(ALLOW_TEST_SERVER, limits());
    }

    private static AudioResolveContext context(AudioNetworkPolicy policy, RadioResolveLimits limits) {
        RadioHttpTransportImpl transport = new RadioHttpTransportImpl(
                Proxy.NO_PROXY, policy, TEST_TIMEOUT, TEST_TIMEOUT, 5);
        return new AudioResolveContext(transport, policy,
                new PlaybackSession().start("http://radio.example/live").cancellation(), limits);
    }

    private static RadioResolveLimits limits() {
        return new RadioResolveLimits(64, 4096, 10, 512, 3, 20);
    }

    private static byte[] bytes(String value) {
        return value.getBytes(StandardCharsets.UTF_8);
    }

    private static void respond(com.sun.net.httpserver.HttpExchange exchange, int status, byte[] body)
            throws IOException {
        exchange.sendResponseHeaders(status, body.length);
        exchange.getResponseBody().write(body);
        exchange.close();
    }

    private static void redirect(com.sun.net.httpserver.HttpExchange exchange, String location)
            throws IOException {
        exchange.getResponseHeaders().add("Location", location);
        exchange.sendResponseHeaders(302, -1);
        exchange.close();
    }

    private static void await(CountDownLatch latch) {
        try {
            latch.await(5, TimeUnit.SECONDS);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
        }
    }
}
