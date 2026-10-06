package gg.moonflower.etched.common.audio.net;

import gg.moonflower.etched.common.audio.AudioCancellation;

import java.io.InputStream;
import java.net.URI;
import java.util.Map;
import java.util.List;

/** An owned in-memory response for cross-package consumer lifecycle tests. */
public final class TestAudioHttpResponse {

    private TestAudioHttpResponse() {
    }

    public static AudioHttpResponse owned(InputStream body, AudioCancellation cancellation) {
        return owned(URI.create("https://audio.example/track"), 200, Map.of(), body, cancellation);
    }

    public static AudioHttpResponse owned(URI uri, int status, Map<String, List<String>> headers,
                                          InputStream body, AudioCancellation cancellation) {
        var exchange = new RadioHttpTransportImpl.ActiveExchange();
        cancellation.onCancel(exchange::cancelTerminal);
        exchange.installBody(null, body);
        return new AudioHttpResponse(uri, status, headers, body, 0, cancellation, exchange);
    }
}
