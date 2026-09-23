package gg.moonflower.etched.common.audio;

import net.minecraft.resources.ResourceLocation;

import java.net.URI;
import java.net.URISyntaxException;
import java.util.Locale;
import java.util.Objects;

/** A bounded, side-neutral description of one playable audio source. */
public record AudioTrack(SourceType sourceType, String source, String artist, String title) {

    public static final int MAX_REMOTE_SOURCE_LENGTH = 8_192;
    public static final int MAX_SOUND_EVENT_LENGTH = 256;
    public static final int MAX_METADATA_LENGTH = 128;

    public AudioTrack {
        Objects.requireNonNull(sourceType, "sourceType");
        Objects.requireNonNull(source, "source");
        Objects.requireNonNull(artist, "artist");
        Objects.requireNonNull(title, "title");

        source = normalizeSource(sourceType, source);
        requireLength(artist, MAX_METADATA_LENGTH, "artist");
        requireLength(title, MAX_METADATA_LENGTH, "title");
    }

    int textLength() {
        return this.source.length() + this.artist.length() + this.title.length();
    }

    static String normalizeSource(SourceType sourceType, String source) {
        int maximumLength = sourceType == SourceType.SOUND_EVENT
                ? MAX_SOUND_EVENT_LENGTH : MAX_REMOTE_SOURCE_LENGTH;
        requireLength(source, maximumLength, "source");
        if (source.isEmpty()) {
            throw new IllegalArgumentException("Audio source must not be empty");
        }

        if (sourceType == SourceType.SOUND_EVENT) {
            ResourceLocation location = ResourceLocation.tryParse(source);
            if (location == null) {
                throw new IllegalArgumentException("Invalid sound event source");
            }
            String canonicalSource = location.toString();
            requireLength(canonicalSource, MAX_SOUND_EVENT_LENGTH, "source");
            return canonicalSource;
        }

        try {
            URI uri = new URI(source);
            String scheme = uri.getScheme();
            if (!uri.isAbsolute() || scheme == null
                    || !switch (scheme.toLowerCase(Locale.ROOT)) {
                case "http", "https" -> true;
                default -> false;
            }
                    || uri.getHost() == null || uri.getHost().isBlank()
                    || uri.getRawUserInfo() != null
                    || uri.getPort() == 0 || uri.getPort() > 65_535
                    || uri.getHost().contains("%")) {
                throw new IllegalArgumentException("Invalid remote audio source");
            }
        } catch (URISyntaxException exception) {
            throw new IllegalArgumentException("Invalid remote audio source", exception);
        }
        return source;
    }

    static void requireLength(String value, int maximumLength, String field) {
        if (value.length() > maximumLength) {
            throw new IllegalArgumentException(field + " exceeds " + maximumLength + " characters");
        }
    }

    public enum SourceType {
        SOUND_EVENT("sound_event"),
        REMOTE("remote");

        private final String serializedName;

        SourceType(String serializedName) {
            this.serializedName = serializedName;
        }

        public String serializedName() {
            return this.serializedName;
        }

        static SourceType bySerializedName(String name) {
            for (SourceType value : values()) {
                if (value.serializedName.equals(name)) {
                    return value;
                }
            }
            return null;
        }
    }
}
