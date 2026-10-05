package gg.moonflower.etched.common.menu;

import gg.moonflower.etched.common.audio.AudioCancellation;
import gg.moonflower.etched.common.audio.AudioContentProbe;
import gg.moonflower.etched.common.audio.net.AudioHttpRequest;
import gg.moonflower.etched.common.audio.net.AudioHttpResponse;
import gg.moonflower.etched.common.audio.net.AudioHttpTransport;
import gg.moonflower.etched.common.audio.net.DefaultRadioNetworkPolicy;
import gg.moonflower.etched.common.audio.net.RadioHttpTransportImpl;

import java.io.IOException;
import java.net.Proxy;
import java.net.URI;
import java.util.Objects;

/** Server-side HTTP boundary for direct etching URLs, accepting only recognizable MPEG/Vorbis bodies. */
final class EtchingUrlValidator {

    private final AudioHttpTransport transport;

    EtchingUrlValidator(Proxy proxy) {
        this(new RadioHttpTransportImpl(proxy, new DefaultRadioNetworkPolicy(() -> false),
                RadioHttpTransportImpl.DEFAULT_CONNECT_TIMEOUT,
                RadioHttpTransportImpl.DEFAULT_READ_TIMEOUT,
                RadioHttpTransportImpl.DEFAULT_MAX_REDIRECTS));
    }

    EtchingUrlValidator(AudioHttpTransport transport) {
        this.transport = Objects.requireNonNull(transport, "transport");
    }

    void check(String url, AudioCancellation cancellation) throws IOException {
        URI uri;
        try {
            uri = URI.create(url);
        } catch (IllegalArgumentException exception) {
            throw new IOException("Invalid etching URL", exception);
        }
        // GET avoids host-specific HEAD exceptions; this scope owns and closes the response.
        try (AudioHttpResponse response = this.transport.execute(AudioHttpRequest.resource(uri), cancellation)) {
            cancellation.throwIfCancelled();
            if (response.statusCode() != 200) {
                throw new IOException("Etching request returned HTTP " + response.statusCode());
            }
            byte[] prefix = AudioContentProbe.readPrefix(response.body(), cancellation,
                    AudioContentProbe.DEFAULT_SNIFF_BYTES, AudioContentProbe.DEFAULT_MAX_ID3_PREFIX_BYTES);
            cancellation.throwIfCancelled();
            AudioContentProbe.Format format = AudioContentProbe.classify(prefix,
                    AudioContentProbe.DEFAULT_MAX_ID3_PREFIX_BYTES);
            if (format != AudioContentProbe.Format.MP3 && format != AudioContentProbe.Format.OGG) {
                throw new IOException("Etching response is not supported MPEG or Ogg/Vorbis audio");
            }
        }
    }
}
