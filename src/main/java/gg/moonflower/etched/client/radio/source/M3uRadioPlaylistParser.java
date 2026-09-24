package gg.moonflower.etched.client.radio.source;

import gg.moonflower.etched.client.radio.RadioFailure;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

final class M3uRadioPlaylistParser {

    private M3uRadioPlaylistParser() {
    }

    static List<RadioPlaylistEntry> parse(URI base, byte[] body, AudioResolveLimits limits)
            throws RadioSourceException {
        String text = new String(body, StandardCharsets.UTF_8);
        String[] lines = text.split("\\R", -1);
        List<RadioPlaylistEntry> entries = new ArrayList<>();
        String title = null;

        for (int i = 0; i < lines.length; i++) {
            String line = lines[i];
            if (i == 0 && line.startsWith("\uFEFF")) {
                line = line.substring(1);
            }
            if (line.length() > limits.maxLineLength()) {
                throw limit("Radio playlist contains a line longer than the configured limit");
            }
            line = line.trim();
            if (line.isEmpty()) {
                continue;
            }
            String upper = line.toUpperCase(Locale.ROOT);
            if (upper.startsWith("#EXT-X-")) {
                throw new RadioSourceException(RadioFailure.Code.UNSUPPORTED_HLS, false,
                        "HLS radio playlists are not supported", null);
            }
            if (upper.startsWith("#EXTINF:")) {
                int comma = line.indexOf(',');
                title = comma >= 0 && comma + 1 < line.length()
                        ? emptyToNull(line.substring(comma + 1).trim()) : null;
                continue;
            }
            if (line.startsWith("#")) {
                continue;
            }
            if (entries.size() >= limits.maxPlaylistEntries()) {
                throw limit("Radio playlist contains too many entries");
            }
            entries.add(new RadioPlaylistEntry(RadioPlaylistUris.resolve(base, line), title));
            title = null;
        }

        if (entries.isEmpty()) {
            throw RadioPlaylistUris.failure("Radio playlist does not contain a station URL", null);
        }
        return List.copyOf(entries);
    }

    private static String emptyToNull(String value) {
        return value.isEmpty() ? null : value;
    }

    private static RadioSourceException limit(String message) {
        return new RadioSourceException(RadioFailure.Code.RESOURCE_LIMIT, false, message, null);
    }
}
