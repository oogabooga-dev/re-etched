package gg.moonflower.etched.client.radio.net;

import java.net.URI;
import java.util.Objects;

/**
 * A GET request whose headers are controlled by the audio HTTP transport.
 */
public record AudioHttpRequest(URI uri, Purpose purpose, int maxRedirects) {

    public AudioHttpRequest(URI uri, Purpose purpose) {
        this(uri, purpose, Integer.MAX_VALUE);
    }

    public AudioHttpRequest {
        Objects.requireNonNull(uri, "uri");
        Objects.requireNonNull(purpose, "purpose");
        if (maxRedirects < 0) {
            throw new IllegalArgumentException("The redirect limit cannot be negative");
        }
    }

    public static AudioHttpRequest resource(URI uri) {
        return new AudioHttpRequest(uri, Purpose.RESOURCE, Integer.MAX_VALUE);
    }

    public static AudioHttpRequest audio(URI uri) {
        return new AudioHttpRequest(uri, Purpose.AUDIO, Integer.MAX_VALUE);
    }

    public AudioHttpRequest withMaxRedirects(int maxRedirects) {
        return new AudioHttpRequest(this.uri, this.purpose, maxRedirects);
    }

    public enum Purpose {
        RESOURCE,
        AUDIO
    }
}
