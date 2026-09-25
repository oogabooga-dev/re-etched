package gg.moonflower.etched.client.radio.source;

import gg.moonflower.etched.client.radio.RadioFailure;
import gg.moonflower.etched.client.radio.PlaybackSession;
import gg.moonflower.etched.client.radio.net.AudioNetworkPolicy;
import gg.moonflower.etched.client.radio.net.RadioHttpTransportImpl;
import gg.moonflower.etched.client.radio.net.TestHttpServer;
import org.junit.jupiter.api.Test;

import java.net.Proxy;
import java.time.Duration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class RadioSourceExceptionHttpStatusTest {

    private static final AudioNetworkPolicy ALLOW_TEST_SERVER = uri -> {
    };

    @Test
    void exposesTheNumericStatusReturnedByTheRadioHost() throws Exception {
        try (TestHttpServer server = new TestHttpServer()) {
            server.handle("/missing", exchange -> {
                exchange.sendResponseHeaders(404, -1);
                exchange.close();
            });

            RadioSourceException failure = assertThrows(RadioSourceException.class,
                    () -> new DirectRadioSourceResolver().resolve(server.uri("/missing"), context()));

            assertEquals(RadioFailure.Code.HTTP_STATUS, failure.code());
            assertEquals(404, failure.httpStatus());
        }
    }

    @Test
    void usesMinusOneWhenNoHttpStatusIsPresent() {
        RadioSourceException failure = new RadioSourceException(
                RadioFailure.Code.UNSUPPORTED_AUDIO, false, "unsupported", null);

        assertEquals(-1, failure.httpStatus());
    }

    @Test
    void rejectsValuesThatAreNeitherAbsentNorThreeDigitStatuses() {
        assertThrows(IllegalArgumentException.class, () -> exceptionWithStatus(-2));
        assertThrows(IllegalArgumentException.class, () -> exceptionWithStatus(99));
        assertThrows(IllegalArgumentException.class, () -> exceptionWithStatus(1_000));
    }

    private static RadioSourceException exceptionWithStatus(int status) {
        return new RadioSourceException(RadioFailure.Code.HTTP_STATUS, false,
                "status", null, RadioFailure.NO_RETRY_AFTER, status);
    }

    private static AudioResolveContext context() {
        RadioHttpTransportImpl transport = new RadioHttpTransportImpl(
                Proxy.NO_PROXY, ALLOW_TEST_SERVER, Duration.ofSeconds(2), Duration.ofSeconds(2), 2);
        return new AudioResolveContext(transport, ALLOW_TEST_SERVER,
                new PlaybackSession().start("http://radio.example/live").cancellation(),
                new AudioResolveLimits(4, 64, 2, 32, 1, 4));
    }
}
