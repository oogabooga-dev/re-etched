package gg.moonflower.etched.common.audio.provider;

import com.google.gson.JsonObject;
import com.google.gson.JsonParseException;
import com.google.gson.JsonParser;
import gg.moonflower.etched.common.audio.AudioCancellation;
import gg.moonflower.etched.common.audio.RadioFailure;
import gg.moonflower.etched.common.audio.net.AudioHttpResponse;
import gg.moonflower.etched.common.audio.net.RadioTransportException;
import org.jetbrains.annotations.Nullable;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Side-neutral, bounded SoundCloud page/script inspection. Callers retain response ownership. */
public final class SoundCloudPageReader {

    public static final int MAX_SCRIPT_CANDIDATES = 10;
    private static final Pattern SCRIPT_PATTERN = Pattern.compile(
            "(?i)<script\\b[^>]*\\bsrc\\s*=\\s*([\"'])(.*?)\\1");
    private static final Pattern CLIENT_ID_PATTERN = Pattern.compile(
            "[\"']?client_id[\"']?\\s*:\\s*[\"']([A-Za-z0-9_-]+)[\"']");

    private SoundCloudPageReader() {
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
        return normalizedHost.equals("soundcloud.com") || normalizedHost.endsWith(".soundcloud.com");
    }

    public static List<URI> scriptCandidates(URI pageUri, String html, int limit, AudioCancellation cancellation) {
        if (limit < 1) {
            throw new IllegalArgumentException("SoundCloud script candidate limit must be positive");
        }
        var scripts = new ArrayDeque<URI>();
        Matcher matcher = SCRIPT_PATTERN.matcher(html);
        while (matcher.find()) {
            cancellation.throwIfCancelled();
            URI script;
            try {
                script = requireHttpUri(pageUri.resolve(matcher.group(2)), "SoundCloud application script");
            } catch (IllegalArgumentException exception) {
                continue;
            }
            if (scripts.size() == Math.min(limit, MAX_SCRIPT_CANDIDATES)) {
                scripts.removeFirst();
            }
            scripts.addLast(script);
        }
        return List.copyOf(scripts);
    }

    public static byte[] readBounded(AudioHttpResponse response, AudioCancellation cancellation,
                                     int limit, String description) throws RadioTransportException {
        if (limit < 1) {
            throw new IllegalArgumentException("SoundCloud body limit must be positive");
        }
        if (response.contentLength().isPresent() && response.contentLength().getAsLong() > limit) {
            throw sizeLimit(description);
        }
        ByteArrayOutputStream output = new ByteArrayOutputStream(Math.min(limit, 8192));
        byte[] buffer = new byte[(int) Math.min(8192L, (long) limit + 1)];
        try {
            while (output.size() <= limit) {
                cancellation.throwIfCancelled();
                int read = response.body().read(buffer, 0,
                        (int) Math.min(buffer.length, (long) limit + 1 - output.size()));
                if (read < 0) {
                    return output.toByteArray();
                }
                if (read > 0) {
                    output.write(buffer, 0, read);
                }
            }
        } catch (RadioTransportException exception) {
            throw exception;
        } catch (IOException exception) {
            throw failure(RadioFailure.Code.UNKNOWN, true,
                    "Could not read the " + description.toLowerCase(Locale.ROOT), exception);
        }
        throw sizeLimit(description);
    }

    public static @Nullable String scanClientId(AudioHttpResponse response, AudioCancellation cancellation, int limit)
            throws RadioTransportException {
        if (limit < 1) {
            throw new IllegalArgumentException("SoundCloud script scan limit must be positive");
        }
        ByteArrayOutputStream prefix = new ByteArrayOutputStream(Math.min(limit, 8192));
        byte[] buffer = new byte[Math.min(8192, limit)];
        try {
            while (prefix.size() < limit) {
                cancellation.throwIfCancelled();
                int read = response.body().read(buffer, 0, Math.min(buffer.length, limit - prefix.size()));
                if (read < 0) {
                    return null;
                }
                if (read == 0) {
                    continue;
                }
                prefix.write(buffer, 0, read);
                Matcher matcher = CLIENT_ID_PATTERN.matcher(prefix.toString(StandardCharsets.UTF_8));
                if (matcher.find()) {
                    return matcher.group(1);
                }
            }
        } catch (RadioTransportException exception) {
            throw exception;
        } catch (IOException exception) {
            throw failure(RadioFailure.Code.UNKNOWN, true, "Could not scan the SoundCloud application script", exception);
        }
        throw sizeLimit("SoundCloud application script scan");
    }

    public static JsonObject parseObject(byte[] bytes, String description) throws RadioTransportException {
        try {
            return JsonParser.parseString(new String(bytes, StandardCharsets.UTF_8)).getAsJsonObject();
        } catch (JsonParseException | IllegalStateException exception) {
            throw failure(RadioFailure.Code.UNSUPPORTED_AUDIO, false, description + " is not valid JSON", exception);
        }
    }

    public static URI appendQuery(URI uri, String key, String value) throws RadioTransportException {
        String separator = uri.getRawQuery() == null ? "?" : "&";
        String base = uri.toASCIIString();
        int fragment = base.indexOf('#');
        if (fragment >= 0) {
            base = base.substring(0, fragment);
        }
        try {
            return URI.create(base + separator + URLEncoder.encode(key, StandardCharsets.UTF_8)
                    + "=" + URLEncoder.encode(value, StandardCharsets.UTF_8));
        } catch (IllegalArgumentException exception) {
            throw failure(RadioFailure.Code.INVALID_URL, false, "Could not construct a SoundCloud API URL", exception);
        }
    }

    public static URI requireHttpUri(URI uri, String description) {
        Objects.requireNonNull(uri, description);
        String scheme = uri.getScheme();
        if (!uri.isAbsolute() || uri.getHost() == null || uri.getRawUserInfo() != null
                || scheme == null || !scheme.equalsIgnoreCase("http") && !scheme.equalsIgnoreCase("https")) {
            throw new IllegalArgumentException(description + " must be an HTTP(S) URL without userinfo");
        }
        return uri;
    }

    public static boolean sameOrigin(URI first, URI second) {
        return first.getScheme().equalsIgnoreCase(second.getScheme())
                && first.getHost().equalsIgnoreCase(second.getHost())
                && effectivePort(first) == effectivePort(second);
    }

    private static int effectivePort(URI uri) {
        return uri.getPort() >= 0 ? uri.getPort() : uri.getScheme().equalsIgnoreCase("https") ? 443 : 80;
    }

    private static RadioTransportException sizeLimit(String description) {
        return failure(RadioFailure.Code.RESOURCE_LIMIT, false, description + " exceeds the configured size limit", null);
    }

    private static RadioTransportException failure(RadioFailure.Code code, boolean recoverable,
                                                   String message, Throwable cause) {
        return new RadioTransportException(code, recoverable, message, cause);
    }
}
