package gg.moonflower.etched.api.record;

import gg.moonflower.etched.core.Etched;
import net.minecraft.network.chat.Component;
import org.jetbrains.annotations.Nullable;

import java.net.URI;
import java.net.URISyntaxException;
import java.util.regex.Pattern;

/**
 * Information about track metadata for discs
 *
 * @param url The URL for the track
 * @param artist The name of the artist
 * @param title The title of the track
 * @author Ocelot
 * @since 2.0.0
 */
public record TrackData(String url, String artist, Component title) {

    public static final TrackData EMPTY = new TrackData(null, "Unknown", Component.literal("Custom Music"));

    private static final Pattern RESOURCE_LOCATION_PATTERN = Pattern.compile("[a-z0-9_.-]+");

    /**
     * Checks to see if the specified string is a valid music URL.
     *
     * @param url The text to check
     * @return Whether the data is valid
     */
    public static boolean isValidURL(@Nullable String url) {
        if (url == null) {
            return false;
        }
        if (isLocalSound(url)) {
            return true;
        }
        try {
            String scheme = new URI(url).getScheme();
            return "http".equals(scheme) || "https".equals(scheme);
        } catch (URISyntaxException e) {
            return false;
        }
    }

    /**
     * Checks to see if the specified URL is a resource location sound.
     *
     * @param url The url to check
     * @return Whether that sound can be played as a local sound event
     */
    public static boolean isLocalSound(@Nullable String url) {
        if (url == null) {
            return false;
        }
        String[] parts = url.split(":");
        if (parts.length > 2) {
            return false;
        }
        for (String part : parts) {
            if (!RESOURCE_LOCATION_PATTERN.matcher(part).matches()) {
                return false;
            }
        }
        return true;
    }

    public TrackData withUrl(String url) {
        return new TrackData(url, this.artist, this.title);
    }

    public TrackData withArtist(String artist) {
        return new TrackData(this.url, artist, this.title);
    }

    public TrackData withTitle(String title) {
        return new TrackData(this.url, this.artist, Component.literal(title));
    }

    public TrackData withTitle(Component title) {
        return new TrackData(this.url, this.artist, title);
    }

    /**
     * @return The name to show as the record title
     */
    public Component getDisplayName() {
        return Component.translatable("sound_source." + Etched.MOD_ID + ".info", this.artist, this.title);
    }
}
