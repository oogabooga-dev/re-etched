package gg.moonflower.etched.common.audio.provider;

import com.google.gson.JsonObject;
import com.google.gson.JsonParseException;
import com.google.gson.JsonParser;
import gg.moonflower.etched.common.audio.AudioCancellation;
import gg.moonflower.etched.common.audio.RadioFailure;
import gg.moonflower.etched.common.audio.net.AudioHttpResponse;
import gg.moonflower.etched.common.audio.net.RadioTransportException;
import org.apache.commons.lang3.StringEscapeUtils;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Shared bounded Bandcamp page inspection. The caller owns and closes the HTTP response. */
public final class BandcampPageReader {

    public static final int DEFAULT_MAX_BODY_BYTES = 256 * 1024;
    private static final Pattern TRALBUM_DATA = Pattern.compile(
            "(?is)\\bdata-tralbum\\s*=\\s*([\"'])(.*?)\\1");

    private BandcampPageReader() {
    }

    public static boolean supports(URI input) {
        if (input == null || !input.isAbsolute() || input.getRawUserInfo() != null) {
            return false;
        }
        String scheme = input.getScheme();
        String host = input.getHost();
        if (scheme == null || host == null
                || !scheme.equalsIgnoreCase("http") && !scheme.equalsIgnoreCase("https")) {
            return false;
        }
        String normalizedHost = host.toLowerCase(Locale.ROOT);
        return normalizedHost.equals("bandcamp.com") || normalizedHost.endsWith(".bandcamp.com");
    }

    public static JsonObject read(AudioHttpResponse response, AudioCancellation cancellation, int limit)
            throws IOException {
        if (limit < 1) {
            throw new IllegalArgumentException("Bandcamp body limit must be positive");
        }
        String html = readHtml(response, cancellation, limit);
        cancellation.throwIfCancelled();
        return parseData(html);
    }

    private static String readHtml(AudioHttpResponse response, AudioCancellation cancellation, int limit)
            throws IOException {
        ByteArrayOutputStream body = new ByteArrayOutputStream(Math.min(limit, 8192));
        byte[] buffer = new byte[(int) Math.min((long) limit + 1, 8192L)];
        while (body.size() <= limit) {
            cancellation.throwIfCancelled();
            int remaining = (int) Math.min((long) buffer.length, (long) limit + 1 - body.size());
            int read = response.body().read(buffer, 0, remaining);
            if (read < 0) {
                return body.toString(StandardCharsets.UTF_8);
            }
            if (read > 0) {
                body.write(buffer, 0, read);
                if (body.size() > limit) {
                    break;
                }
                String html = body.toString(StandardCharsets.UTF_8);
                if (TRALBUM_DATA.matcher(html).find()) {
                    return html;
                }
            }
        }
        throw new RadioTransportException(RadioFailure.Code.PLAYLIST_TOO_LARGE, false,
                "Bandcamp page exceeds the configured body limit", null);
    }

    @SuppressWarnings("deprecation") // commons-lang3 is supplied by Minecraft 1.20.1.
    private static JsonObject parseData(String html) throws RadioTransportException {
        Matcher matcher = TRALBUM_DATA.matcher(html);
        if (!matcher.find()) {
            throw new RadioTransportException(RadioFailure.Code.UNSUPPORTED_AUDIO, false,
                    "Bandcamp page does not contain track data", null);
        }
        try {
            return JsonParser.parseString(StringEscapeUtils.unescapeHtml4(matcher.group(2))).getAsJsonObject();
        } catch (JsonParseException | IllegalStateException | IllegalArgumentException exception) {
            throw new RadioTransportException(RadioFailure.Code.UNSUPPORTED_AUDIO, false,
                    "Bandcamp page contains invalid track data", exception);
        }
    }
}
