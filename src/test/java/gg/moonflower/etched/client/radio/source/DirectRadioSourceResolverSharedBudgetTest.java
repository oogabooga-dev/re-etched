package gg.moonflower.etched.client.radio.source;

import gg.moonflower.etched.client.radio.RadioFailure;
import gg.moonflower.etched.client.radio.PlaybackSession;
import gg.moonflower.etched.client.radio.net.AudioNetworkPolicy;
import gg.moonflower.etched.client.radio.net.RadioHttpTransportImpl;
import gg.moonflower.etched.client.radio.net.TestHttpServer;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.Proxy;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class DirectRadioSourceResolverSharedBudgetTest {

    private static final AudioNetworkPolicy ALLOW_TEST_SERVER = uri -> {
    };

    @Test
    void permitsRequestsAtTheExactSharedStepLimitAndBlocksTheNextRequest() throws Exception {
        AtomicInteger requests = new AtomicInteger();
        try (TestHttpServer server = new TestHttpServer()) {
            server.handle("/live", exchange -> {
                requests.incrementAndGet();
                exchange.getResponseHeaders().add("Content-Type", "audio/mpeg");
                respond(exchange, 200, bytes("ID3-audio"));
            });
            AudioResolveContext context = context(new RadioResolveLimits(4, 128, 4, 64, 1, 2));
            DirectRadioSourceResolver resolver = new DirectRadioSourceResolver();

            try (RadioResolvedSource ignored = resolver.resolve(server.uri("/live"), context)) {
                assertEquals(1, context.budget().remainingSteps());
            }
            try (RadioResolvedSource ignored = resolver.resolve(server.uri("/live"), context)) {
                assertEquals(0, context.budget().remainingSteps());
            }
            RadioSourceException failure = assertThrows(RadioSourceException.class,
                    () -> resolver.resolve(server.uri("/live"), context));

            assertEquals(RadioFailure.Code.RESOURCE_LIMIT, failure.code());
            assertEquals(2, requests.get());
        }
    }

    @Test
    void sharesTheAggregatePlaylistEntryBudgetAcrossDirectResolutions() throws Exception {
        AtomicInteger playlistRequests = new AtomicInteger();
        AtomicInteger stationRequests = new AtomicInteger();
        try (TestHttpServer server = new TestHttpServer()) {
            server.handle("/stations.m3u", exchange -> {
                playlistRequests.incrementAndGet();
                exchange.getResponseHeaders().add("Content-Type", "audio/x-mpegurl");
                respond(exchange, 200, bytes("station\n"));
            });
            server.handle("/station", exchange -> {
                stationRequests.incrementAndGet();
                exchange.getResponseHeaders().add("Content-Type", "audio/mpeg");
                respond(exchange, 200, bytes("ID3-audio"));
            });
            AudioResolveContext context = context(new RadioResolveLimits(4, 128, 1, 64, 1, 4));
            DirectRadioSourceResolver resolver = new DirectRadioSourceResolver();

            try (RadioResolvedSource ignored = resolver.resolve(server.uri("/stations.m3u"), context)) {
                assertEquals(2, context.budget().remainingSteps());
            }
            RadioSourceException failure = assertThrows(RadioSourceException.class,
                    () -> resolver.resolve(server.uri("/stations.m3u"), context));

            assertEquals(RadioFailure.Code.RESOURCE_LIMIT, failure.code());
            assertEquals(2, playlistRequests.get());
            assertEquals(1, stationRequests.get());
        }
    }

    private static AudioResolveContext context(RadioResolveLimits limits) {
        RadioHttpTransportImpl transport = new RadioHttpTransportImpl(
                Proxy.NO_PROXY, ALLOW_TEST_SERVER, Duration.ofSeconds(2), Duration.ofSeconds(2), 3);
        return new AudioResolveContext(transport, ALLOW_TEST_SERVER,
                new PlaybackSession().start("http://radio.example/live").cancellation(), limits);
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
}
