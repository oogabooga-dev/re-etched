package gg.moonflower.etched.client.radio.source;

import gg.moonflower.etched.common.audio.RadioFailure;
import gg.moonflower.etched.client.radio.net.AudioHttpRequest;
import gg.moonflower.etched.client.radio.net.AudioHttpResponse;
import gg.moonflower.etched.client.radio.net.RadioTransportException;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;

public final class DirectRadioSourceResolver implements AudioSourceResolver {

    private static final int MINIMUM_SNIFF_BYTES = 4;
    private static final int OGG_PAGE_HEADER_BYTES = 27;
    private static final int VORBIS_IDENTIFICATION_BYTES = 30;

    @Override
    public boolean supports(URI input) {
        if (input == null || !input.isAbsolute()) {
            return false;
        }
        String scheme = input.getScheme();
        return scheme != null && (scheme.equalsIgnoreCase("http") || scheme.equalsIgnoreCase("https"));
    }

    @Override
    public RadioSourceProgram resolveProgram(URI input, AudioResolveContext context)
            throws RadioSourceException {
        Objects.requireNonNull(context, "context");
        if (!this.supports(input)) {
            throw failure(RadioFailure.Code.INVALID_URL, false,
                    "Direct radio sources must use an absolute HTTP(S) URL", null);
        }
        return new RadioSourceProgram(RadioSourceProgram.Kind.STATION, input, List.of(
                new RadioSourceProgram.Track(input, null, next -> this.resolve(input, next))));
    }

    public RadioResolvedSource resolve(URI input, AudioResolveContext context) throws RadioSourceException {
        Objects.requireNonNull(input, "input");
        Objects.requireNonNull(context, "context");
        if (!this.supports(input)) {
            throw failure(RadioFailure.Code.INVALID_URL, false,
                    "Direct radio sources must use an absolute HTTP(S) URL", null);
        }
        return this.resolveEndpoint(input, context, new ResolutionState(), 0, List.of(input));
    }

    private RadioResolvedSource resolveEndpoint(URI input, AudioResolveContext context,
                                                ResolutionState state, int playlistDepth,
                                                List<URI> stationEndpoints) throws RadioSourceException {
        context.cancellation().throwIfCancelled();
        context.budget().consumeSteps(1);

        AudioHttpRequest request = AudioHttpRequest.audio(input);
        request = request.withMaxRedirects(context.budget().remainingSteps());
        AudioHttpResponse response;
        try {
            response = context.transport().execute(request, context.cancellation());
        } catch (RadioTransportException exception) {
            context.budget().consumeSteps(exception.redirectCount());
            if (exception.code() == RadioFailure.Code.TOO_MANY_REDIRECTS
                    && context.budget().remainingSteps() == 0) {
                throw failure(RadioFailure.Code.RESOURCE_LIMIT, false,
                        "Radio source resolution exceeded the configured step limit", exception);
            }
            throw RadioSourceException.fromTransport(exception);
        }
        boolean transferred = false;
        try {
            context.budget().consumeSteps(response.redirectCount());
            RadioHttpStatus.requireSuccess(response, "Radio host");
            byte[] prefix = readPrefix(response, context);
            SourceKind kind = classify(response, input, prefix, context.limits().maxId3PrefixBytes());
            switch (kind) {
                case HLS -> throw failure(RadioFailure.Code.UNSUPPORTED_HLS, false,
                        "HLS radio playlists are not supported", null);
                case AAC -> throw failure(RadioFailure.Code.UNSUPPORTED_AAC, false,
                        "AAC radio streams are not supported", null);
                case MP3, OGG -> {
                    RadioResolvedSource resolved = new RadioResolvedSource(response,
                            kind == SourceKind.MP3
                                    ? RadioResolvedSource.Format.MP3 : RadioResolvedSource.Format.OGG,
                            prefix, stationEndpoints, context.cancellation());
                    transferred = true;
                    return resolved;
                }
                case M3U, PLS -> {
                    if (playlistDepth >= context.limits().maxPlaylistDepth()) {
                        throw failure(RadioFailure.Code.RESOURCE_LIMIT, false,
                                "Radio playlist nesting exceeded the configured limit", null);
                    }
                    URI playlistUri = response.uri();
                    if (!state.playlists.add(playlistUri)) {
                        throw failure(RadioFailure.Code.RESOURCE_LIMIT, false,
                                "Radio playlist cycle detected", null);
                    }
                    try {
                        byte[] body = readPlaylist(response, prefix, context);
                        List<RadioPlaylistEntry> entries = kind == SourceKind.M3U
                                ? M3uRadioPlaylistParser.parse(playlistUri, body, context.limits())
                                : PlsRadioPlaylistParser.parse(playlistUri, body, context.limits());
                        context.budget().consumeEntries(entries.size());
                        List<URI> endpoints = entries.stream().map(RadioPlaylistEntry::uri).toList();
                        validateAllEntries(endpoints, context);
                        response.close();
                        return this.resolveFallbacks(endpoints, context, state, playlistDepth + 1);
                    } finally {
                        state.playlists.remove(playlistUri);
                    }
                }
                default -> throw failure(RadioFailure.Code.UNSUPPORTED_AUDIO, false,
                        "The radio response is not a supported MP3, Ogg, M3U, or PLS source", null);
            }
        } finally {
            if (!transferred) {
                response.close();
            }
        }
    }

    private RadioResolvedSource resolveFallbacks(List<URI> endpoints, AudioResolveContext context,
                                                 ResolutionState state, int playlistDepth)
            throws RadioSourceException {
        RadioSourceException lastRecoverable = null;
        long retryAfterMillis = RadioFailure.NO_RETRY_AFTER;
        for (URI endpoint : endpoints) {
            context.cancellation().throwIfCancelled();
            try {
                return this.resolveEndpoint(endpoint, context, state, playlistDepth, endpoints);
            } catch (RadioSourceException exception) {
                if (!exception.recoverable()) {
                    throw exception;
                }
                lastRecoverable = exception;
                retryAfterMillis = Math.max(retryAfterMillis, exception.retryAfterMillis());
            }
        }
        if (lastRecoverable != null) {
            if (retryAfterMillis != lastRecoverable.retryAfterMillis()) {
                throw new RadioSourceException(lastRecoverable.code(), true,
                        lastRecoverable.getMessage(), lastRecoverable, retryAfterMillis);
            }
            throw lastRecoverable;
        }
        throw failure(RadioFailure.Code.UNSUPPORTED_AUDIO, false,
                "Radio playlist does not contain a usable station", null);
    }

    private static void validateAllEntries(List<URI> endpoints, AudioResolveContext context)
            throws RadioSourceException {
        for (URI endpoint : endpoints) {
            context.cancellation().throwIfCancelled();
            try {
                context.networkPolicy().check(endpoint, context.cancellation());
            } catch (RadioTransportException exception) {
                if (!exception.recoverable()) {
                    throw RadioSourceException.fromTransport(exception);
                }
            }
        }
    }

    private static byte[] readPrefix(AudioHttpResponse response, AudioResolveContext context)
            throws RadioSourceException {
        int limit = context.limits().sniffBytes();
        int id3Limit = context.limits().maxId3PrefixBytes();
        ByteArrayOutputStream prefix = new ByteArrayOutputStream(limit);
        byte[] buffer = new byte[Math.min(8192, Math.max(limit, id3Limit))];
        InputStream body = response.body();
        try {
            readUntil(prefix, body, buffer, MINIMUM_SNIFF_BYTES, limit, context);
            if (startsWith(prefix.toByteArray(), "ID3")) {
                readUntil(prefix, body, buffer, 10, id3Limit, context);
                int frame = id3FrameOffset(prefix.toByteArray(), id3Limit);
                if (frame < 0) {
                    return prefix.toByteArray();
                }
                readUntil(prefix, body, buffer, frame + MINIMUM_SNIFF_BYTES, id3Limit, context);
            } else if (startsWith(prefix.toByteArray(), "OggS")) {
                readUntil(prefix, body, buffer, OGG_PAGE_HEADER_BYTES, limit, context);
                if (prefix.size() >= OGG_PAGE_HEADER_BYTES) {
                    int segments = prefix.toByteArray()[26] & 0xFF;
                    if (segments > 0) {
                        readUntil(prefix, body, buffer,
                                OGG_PAGE_HEADER_BYTES + segments + VORBIS_IDENTIFICATION_BYTES,
                                limit, context);
                    }
                }
            }
            boolean readTextPrefix = looksLikeTextPrefix(prefix.toByteArray());
            while (prefix.size() < limit) {
                context.cancellation().throwIfCancelled();
                int available = body.available();
                if (available <= 0 && !readTextPrefix) {
                    break;
                }
                int read = body.read(buffer, 0, Math.min(
                        available > 0 ? available : buffer.length, limit - prefix.size()));
                if (read <= 0) {
                    break;
                }
                prefix.write(buffer, 0, read);
            }
            return prefix.toByteArray();
        } catch (RadioTransportException exception) {
            throw RadioSourceException.fromTransport(exception);
        } catch (IOException exception) {
            throw failure(RadioFailure.Code.UNKNOWN, true,
                    "Could not inspect the radio response", exception);
        }
    }

    private static void readUntil(ByteArrayOutputStream prefix, InputStream body, byte[] buffer,
                                  int target, int limit, AudioResolveContext context) throws IOException {
        int end = Math.min(limit, target);
        while (prefix.size() < end) {
            context.cancellation().throwIfCancelled();
            int read = body.read(buffer, 0, Math.min(buffer.length, end - prefix.size()));
            if (read < 0) {
                return;
            }
            if (read > 0) {
                prefix.write(buffer, 0, read);
            }
        }
    }

    private static byte[] readPlaylist(AudioHttpResponse response, byte[] prefix,
                                       AudioResolveContext context) throws RadioSourceException {
        int limit = context.limits().maxPlaylistBytes();
        if (response.contentLength().isPresent() && response.contentLength().getAsLong() > limit) {
            throw failure(RadioFailure.Code.PLAYLIST_TOO_LARGE, false,
                    "Radio playlist exceeds the configured size limit", null);
        }
        ByteArrayOutputStream body = new ByteArrayOutputStream(Math.min(limit, prefix.length + 1024));
        body.writeBytes(prefix);
        byte[] buffer = new byte[Math.min(8192, limit + 1)];
        try {
            while (body.size() <= limit) {
                context.cancellation().throwIfCancelled();
                int read = response.body().read(buffer, 0, Math.min(buffer.length, limit + 1 - body.size()));
                if (read < 0) {
                    return body.toByteArray();
                }
                if (read > 0) {
                    body.write(buffer, 0, read);
                }
            }
        } catch (RadioTransportException exception) {
            throw RadioSourceException.fromTransport(exception);
        } catch (IOException exception) {
            throw failure(RadioFailure.Code.UNKNOWN, true,
                    "Could not read the radio playlist", exception);
        }
        throw failure(RadioFailure.Code.PLAYLIST_TOO_LARGE, false,
                "Radio playlist exceeds the configured size limit", null);
    }

    private static SourceKind classify(AudioHttpResponse response, URI requestedUri, byte[] prefix,
                                       int maxId3PrefixBytes) {
        String contentType = response.firstHeader("Content-Type")
                .map(value -> value.split(";", 2)[0].trim().toLowerCase(Locale.ROOT))
                .orElse("");
        String suffix = suffix(response.uri());
        String requestedSuffix = suffix(requestedUri);
        String text = new String(prefix, StandardCharsets.UTF_8).stripLeading();
        if (text.startsWith("\uFEFF")) {
            text = text.substring(1).stripLeading();
        }
        String upper = text.toUpperCase(Locale.ROOT);

        if (startsWith(prefix, "OggS")) {
            return hasVorbisIdentification(prefix) ? SourceKind.OGG : SourceKind.UNKNOWN;
        }
        if (hasAdtsSignature(prefix)) {
            return SourceKind.AAC;
        }
        if (hasMpegAudioSignature(prefix)) {
            return SourceKind.MP3;
        }
        if (startsWith(prefix, "ID3")) {
            int frame = id3FrameOffset(prefix, maxId3PrefixBytes);
            return frame >= 0 && hasMpegAudioSignature(prefix, frame)
                    ? SourceKind.MP3 : SourceKind.UNKNOWN;
        }
        if (containsHlsDirective(upper)) {
            return SourceKind.HLS;
        }
        if (upper.startsWith("[PLAYLIST]")) {
            return SourceKind.PLS;
        }
        if (upper.startsWith("#EXTM3U")
                && (suffix.equals("m3u8") || requestedSuffix.equals("m3u8")
                || contentType.equals("application/vnd.apple.mpegurl"))) {
            return SourceKind.HLS;
        }
        if (upper.startsWith("#EXTM3U") || looksLikeTextPrefix(prefix) && looksLikePlainM3u(text)) {
            return SourceKind.M3U;
        }
        if (text.startsWith("<")) {
            return SourceKind.UNKNOWN;
        }
        if (isAacHint(contentType, suffix, requestedSuffix)) {
            return SourceKind.AAC;
        }
        if (suffix.equals("m3u8") || requestedSuffix.equals("m3u8")
                || contentType.equals("application/vnd.apple.mpegurl")) {
            return SourceKind.HLS;
        }
        if (contentType.equals("audio/x-scpls") || suffix.equals("pls")
                || requestedSuffix.equals("pls")) {
            return SourceKind.PLS;
        }
        if (contentType.equals("audio/x-mpegurl") || contentType.equals("audio/mpegurl")
                || contentType.equals("application/x-mpegurl") || suffix.equals("m3u")
                || requestedSuffix.equals("m3u")) {
            return SourceKind.M3U;
        }
        // Audio needs a recognizable body. A MIME type or extension alone cannot
        // turn an arbitrary response into something safe to hand to a decoder.
        if (contentType.equals("text/plain")) {
            return SourceKind.M3U;
        }
        return SourceKind.UNKNOWN;
    }

    private static boolean isAacHint(String contentType, String suffix, String requestedSuffix) {
        return suffix.equals("aac") || suffix.equals("aacp")
                || requestedSuffix.equals("aac") || requestedSuffix.equals("aacp")
                || contentType.equals("audio/aac") || contentType.equals("audio/aacp");
    }

    private static boolean looksLikeTextPrefix(byte[] prefix) {
        if (prefix.length == 0) {
            return false;
        }
        if (startsWith(prefix, "ID3") || startsWith(prefix, "OggS")
                || hasAdtsSignature(prefix) || hasMpegAudioSignature(prefix)) {
            return false;
        }
        // Only inspect the first bytes: later UTF-8 text in a playlist is fine.
        for (int i = 0; i < Math.min(prefix.length, MINIMUM_SNIFF_BYTES); i++) {
            int current = prefix[i] & 0xFF;
            if (current != '\t' && current != '\r' && current != '\n'
                    && (current < 0x20 || current > 0x7E)) {
                return i == 0 && current == 0xEF;
            }
        }
        return true;
    }

    private static boolean containsHlsDirective(String text) {
        for (String line : text.split("\\R")) {
            if (line.trim().startsWith("#EXT-X-")) {
                return true;
            }
        }
        return false;
    }

    private static boolean looksLikePlainM3u(String text) {
        String first = text.lines()
                .map(String::trim)
                .filter(line -> !line.isEmpty() && !line.startsWith("#"))
                .findFirst()
                .orElse("");
        if (first.startsWith("<")) {
            return false;
        }
        try {
            URI candidate = URI.create(first);
            return candidate.isAbsolute()
                    || candidate.getRawAuthority() != null
                    || candidate.getRawQuery() != null
                    || candidate.getRawPath() != null && !candidate.getRawPath().isEmpty();
        } catch (IllegalArgumentException exception) {
            return false;
        }
    }

    private static boolean hasAdtsSignature(byte[] bytes) {
        return bytes.length >= 2 && (bytes[0] & 0xFF) == 0xFF && (bytes[1] & 0xF6) == 0xF0;
    }

    private static boolean hasVorbisIdentification(byte[] bytes) {
        if (bytes.length < OGG_PAGE_HEADER_BYTES + 1 + VORBIS_IDENTIFICATION_BYTES
                || bytes[4] != 0 || bytes[5] != 2) { // First page, begin of stream.
            return false;
        }
        int segments = bytes[26] & 0xFF;
        int packet = OGG_PAGE_HEADER_BYTES + segments;
        if (segments == 0 || bytes.length < packet + VORBIS_IDENTIFICATION_BYTES
                || (bytes[OGG_PAGE_HEADER_BYTES] & 0xFF) != VORBIS_IDENTIFICATION_BYTES
                || bytes[packet] != 1) {
            return false;
        }
        for (int i = 0; i < 6; i++) {
            if (bytes[packet + 1 + i] != "vorbis".charAt(i)) {
                return false;
            }
        }
        // Identification packet: version, channels, sample rate, block sizes, framing bit.
        for (int i = 7; i <= 10; i++) {
            if (bytes[packet + i] != 0) {
                return false;
            }
        }
        int blocks = bytes[packet + 28] & 0xFF;
        int small = blocks & 0x0F;
        int large = blocks >>> 4;
        return bytes[packet + 11] != 0
                && (bytes[packet + 12] != 0 || bytes[packet + 13] != 0
                || bytes[packet + 14] != 0 || bytes[packet + 15] != 0)
                && small >= 6 && large <= 13 && small <= large
                && bytes[packet + 29] == 1;
    }

    private static boolean hasMpegAudioSignature(byte[] bytes) {
        return hasMpegAudioSignature(bytes, 0);
    }

    private static boolean hasMpegAudioSignature(byte[] bytes, int offset) {
        if (bytes.length - offset < 4 || (bytes[offset] & 0xFF) != 0xFF
                || (bytes[offset + 1] & 0xE0) != 0xE0) {
            return false;
        }
        int version = (bytes[offset + 1] >>> 3) & 0x03;
        int layer = (bytes[offset + 1] >>> 1) & 0x03;
        int bitrate = (bytes[offset + 2] >>> 4) & 0x0F;
        int sampleRate = (bytes[offset + 2] >>> 2) & 0x03;
        return version != 1 && layer != 0 && bitrate != 0 && bitrate != 15 && sampleRate != 3;
    }

    /** Returns the first MPEG byte only when the complete tag and frame header fit the sniff budget. */
    private static int id3FrameOffset(byte[] bytes, int limit) {
        if (bytes.length < 10 || !startsWith(bytes, "ID3")) {
            return -1;
        }
        int version = bytes[3] & 0xFF;
        int flags = bytes[5] & 0xFF;
        if (version < 2 || version > 4 || (bytes[4] & 0xFF) == 0xFF
                || (flags & (version == 2 ? 0x3F : version == 3 ? 0x1F : 0x0F)) != 0) {
            return -1;
        }
        int size = 0;
        for (int i = 6; i < 10; i++) {
            int part = bytes[i] & 0xFF;
            if (part > 0x7F) {
                return -1;
            }
            size = (size << 7) | part;
        }
        long frame = 10L + size + (version == 4 && (flags & 0x10) != 0 ? 10 : 0);
        return frame + MINIMUM_SNIFF_BYTES <= limit ? (int) frame : -1;
    }

    private static boolean startsWith(byte[] bytes, String signature) {
        byte[] expected = signature.getBytes(StandardCharsets.US_ASCII);
        if (bytes.length < expected.length) {
            return false;
        }
        for (int i = 0; i < expected.length; i++) {
            if (bytes[i] != expected[i]) {
                return false;
            }
        }
        return true;
    }

    private static String suffix(URI uri) {
        String path = uri.getPath();
        if (path == null) {
            return "";
        }
        int slash = path.lastIndexOf('/');
        int dot = path.lastIndexOf('.');
        return dot > slash && dot + 1 < path.length()
                ? path.substring(dot + 1).toLowerCase(Locale.ROOT) : "";
    }

    private static RadioSourceException failure(RadioFailure.Code code, boolean recoverable,
                                                String message, Throwable cause) {
        return new RadioSourceException(code, recoverable, message, cause);
    }

    private enum SourceKind {
        MP3,
        OGG,
        M3U,
        PLS,
        HLS,
        AAC,
        UNKNOWN
    }

    private static final class ResolutionState {

        private final Set<URI> playlists = new HashSet<>();
    }
}
