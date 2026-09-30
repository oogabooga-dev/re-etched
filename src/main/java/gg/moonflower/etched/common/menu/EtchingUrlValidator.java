package gg.moonflower.etched.common.menu;

import gg.moonflower.etched.common.audio.AudioCancellation;
import gg.moonflower.etched.common.audio.net.AudioHttpRequest;
import gg.moonflower.etched.common.audio.net.AudioHttpResponse;
import gg.moonflower.etched.common.audio.net.AudioHttpTransport;
import gg.moonflower.etched.common.audio.net.DefaultRadioNetworkPolicy;
import gg.moonflower.etched.common.audio.net.RadioHttpTransportImpl;

import java.io.IOException;
import java.net.Proxy;
import java.net.URI;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;

/** Server-side HTTP boundary for direct etching URLs; media sniffing is a separate migration step. */
final class EtchingUrlValidator {

    private static final Set<String> LEGACY_CONTENT_TYPES = Set.of(
            "audio/wav", "audio/x-wav", "audio/opus", "application/ogg", "audio/ogg",
            "audio/mpeg", "audio/mp3", "application/octet-stream", "application/binary");

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
            String contentType = response.firstHeader("Content-Type")
                    .map(value -> value.split(";", 2)[0].trim().toLowerCase(Locale.ROOT))
                    .orElse("");
            if (!LEGACY_CONTENT_TYPES.contains(contentType)) {
                throw new IOException("Unsupported Content-Type: " + contentType);
            }
        }
    }
}
