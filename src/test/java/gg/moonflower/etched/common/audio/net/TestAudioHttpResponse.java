package gg.moonflower.etched.common.audio.net;

import gg.moonflower.etched.common.audio.AudioCancellation;

import java.io.InputStream;
import java.net.URI;
import java.util.Map;

/** An owned in-memory response for cross-package consumer lifecycle tests. */
public final class TestAudioHttpResponse {

    private TestAudioHttpResponse() {
    }

    public static AudioHttpResponse owned(InputStream body, AudioCancellation cancellation) {
        var exchange = new RadioHttpTransportImpl.ActiveExchange();
        exchange.installBody(null, body);
        return new AudioHttpResponse(URI.create("https://audio.example/track"), 200,
                Map.of(), body, 0, cancellation, exchange);
    }
}
