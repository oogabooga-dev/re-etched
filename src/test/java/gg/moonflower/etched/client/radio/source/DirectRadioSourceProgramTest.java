package gg.moonflower.etched.client.radio.source;

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

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotSame;

class DirectRadioSourceProgramTest {

    private static final AudioNetworkPolicy ALLOW_TEST_SERVER = uri -> {
    };

    @Test
    void defersTheStationRequestAndOpensIndependentResponses() throws Exception {
        AtomicInteger requests = new AtomicInteger();
        try (TestHttpServer server = new TestHttpServer()) {
            server.handle("/live", exchange -> {
                int request = requests.incrementAndGet();
                exchange.getResponseHeaders().add("Content-Type", "audio/mpeg");
                respond(exchange, bytes("ID3-open-" + request));
            });
            AudioResolveContext context = context(4);

            RadioSourceProgram program = new DirectRadioSourceResolver()
                    .resolveProgram(server.uri("/live"), context);

            assertEquals(0, requests.get());
            assertEquals(RadioSourceProgram.Kind.STATION, program.kind());
            assertEquals(server.uri("/live"), program.source());
            assertEquals(1, program.tracks().size());
            assertEquals(server.uri("/live"), program.tracks().get(0).source());
            try (RadioResolvedSource first = program.openTrack(0, context);
                 RadioResolvedSource second = program.openTrack(0, context)) {
                assertNotSame(first, second);
                assertNotSame(first.body(), second.body());
                assertArrayEquals(bytes("ID3-open-1"), first.body().readAllBytes());
                first.close();
                assertArrayEquals(bytes("ID3-open-2"), second.body().readAllBytes());
            }
            assertEquals(2, requests.get());
        }
    }

    private static AudioResolveContext context(int maxSteps) {
        RadioHttpTransportImpl transport = new RadioHttpTransportImpl(
                Proxy.NO_PROXY, ALLOW_TEST_SERVER, Duration.ofSeconds(2), Duration.ofSeconds(2), 3);
        return new AudioResolveContext(transport, ALLOW_TEST_SERVER,
                new PlaybackSession().start("http://radio.example/live").cancellation(),
                new RadioResolveLimits(4, 128, 4, 64, 1, maxSteps));
    }

    private static byte[] bytes(String value) {
        return value.getBytes(StandardCharsets.US_ASCII);
    }

    private static void respond(com.sun.net.httpserver.HttpExchange exchange, byte[] body)
            throws IOException {
        exchange.sendResponseHeaders(200, body.length);
        exchange.getResponseBody().write(body);
        exchange.close();
    }
}
