package gg.moonflower.etched.client.radio.source;

import gg.moonflower.etched.client.radio.RadioFailure;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.ArrayList;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;

final class PlsRadioPlaylistParser {

    private PlsRadioPlaylistParser() {
    }

    static List<RadioPlaylistEntry> parse(URI base, byte[] body, AudioResolveLimits limits)
            throws RadioSourceException {
        String text = new String(body, StandardCharsets.UTF_8);
        String[] lines = text.split("\\R", -1);
        Map<Integer, String> files = new TreeMap<>();
        Map<Integer, String> titles = new TreeMap<>();
        boolean header = false;

        for (int i = 0; i < lines.length; i++) {
            String line = lines[i];
            if (i == 0 && line.startsWith("\uFEFF")) {
                line = line.substring(1);
            }
            if (line.length() > limits.maxLineLength()) {
                throw limit("Radio playlist contains a line longer than the configured limit");
            }
            line = line.trim();
            if (line.isEmpty() || line.startsWith(";") || line.startsWith("#")) {
                continue;
            }
            if (!header) {
                if (!line.equalsIgnoreCase("[playlist]")) {
                    throw RadioPlaylistUris.failure("PLS playlist is missing its header", null);
                }
                header = true;
                continue;
            }

            int equals = line.indexOf('=');
            if (equals <= 0) {
                throw RadioPlaylistUris.failure("PLS playlist contains an invalid property", null);
            }
            String key = line.substring(0, equals).trim();
            String value = line.substring(equals + 1).trim();
            String lower = key.toLowerCase(Locale.ROOT);
            if (lower.startsWith("file")) {
                putIndexed(files, key, value, "File");
            } else if (lower.startsWith("title")) {
                putIndexed(titles, key, value, "Title");
            }
        }

        if (!header || files.isEmpty()) {
            throw RadioPlaylistUris.failure("PLS playlist does not contain a station URL", null);
        }
        if (files.size() > limits.maxPlaylistEntries()) {
            throw limit("Radio playlist contains too many entries");
        }
        List<RadioPlaylistEntry> entries = new ArrayList<>(files.size());
        for (Map.Entry<Integer, String> entry : files.entrySet()) {
            entries.add(new RadioPlaylistEntry(RadioPlaylistUris.resolve(base, entry.getValue()),
                    emptyToNull(titles.get(entry.getKey()))));
        }
        return List.copyOf(entries);
    }

    private static void putIndexed(Map<Integer, String> values, String key, String value, String prefix)
            throws RadioSourceException {
        int index;
        try {
            index = Integer.parseInt(key.substring(prefix.length()));
        } catch (IndexOutOfBoundsException | NumberFormatException exception) {
            throw RadioPlaylistUris.failure("PLS playlist contains an invalid entry index", exception);
        }
        if (index < 1 || value.isEmpty() || values.putIfAbsent(index, value) != null) {
            throw RadioPlaylistUris.failure("PLS playlist contains an invalid or duplicate entry", null);
        }
    }

    private static String emptyToNull(String value) {
        return value == null || value.isEmpty() ? null : value;
    }

    private static RadioSourceException limit(String message) {
        return new RadioSourceException(RadioFailure.Code.RESOURCE_LIMIT, false, message, null);
    }
}
