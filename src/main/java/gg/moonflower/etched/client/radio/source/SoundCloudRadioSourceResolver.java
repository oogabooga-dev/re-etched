package gg.moonflower.etched.client.radio.source;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParseException;
import com.google.gson.JsonParser;
import gg.moonflower.etched.client.radio.RadioFailure;
import gg.moonflower.etched.client.radio.net.AudioHttpRequest;
import gg.moonflower.etched.client.radio.net.AudioHttpResponse;
import gg.moonflower.etched.client.radio.net.RadioTransportException;
import org.jetbrains.annotations.Nullable;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CancellationException;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Resolves SoundCloud tracks and albums without sharing opened media responses. */
public final class SoundCloudRadioSourceResolver implements AudioSourceResolver {

    private static final URI HOMEPAGE = URI.create("https://soundcloud.com/");
    private static final URI RESOLVE_ENDPOINT = URI.create("https://api-v2.soundcloud.com/resolve");
    private static final int MAX_SCRIPT_CANDIDATES = 10;
    private static final Pattern SCRIPT_PATTERN = Pattern.compile(
            "(?i)<script\\b[^>]*\\bsrc\\s*=\\s*([\"'])(.*?)\\1");
    private static final Pattern CLIENT_ID_PATTERN = Pattern.compile(
            "[\"']?client_id[\"']?\\s*:\\s*[\"']([A-Za-z0-9_-]+)[\"']");

    private final DirectRadioSourceResolver direct;
    private final URI homepage;
    private final URI resolveEndpoint;
    private final Object clientIdLock = new Object();
    private volatile String clientId;
    private Discovery clientIdDiscovery;

    public SoundCloudRadioSourceResolver() {
        this(new DirectRadioSourceResolver(), HOMEPAGE, RESOLVE_ENDPOINT);
    }

    SoundCloudRadioSourceResolver(DirectRadioSourceResolver direct, URI homepage,
                                  URI resolveEndpoint) {
        this.direct = Objects.requireNonNull(direct, "direct");
        this.homepage = requireHttpUri(homepage, "SoundCloud homepage");
        this.resolveEndpoint = requireHttpUri(resolveEndpoint, "SoundCloud resolve endpoint");
    }

    @Override
    public boolean supports(URI input) {
        if (input == null || !input.isAbsolute() || input.getUserInfo() != null) {
            return false;
        }
        String scheme = input.getScheme();
        String host = input.getHost();
        if (scheme == null || host == null
                || !scheme.equalsIgnoreCase("http") && !scheme.equalsIgnoreCase("https")) {
            return false;
        }
        String normalizedHost = host.toLowerCase(Locale.ROOT);
        return normalizedHost.equals("soundcloud.com")
                || normalizedHost.endsWith(".soundcloud.com");
    }

    @Override
    public RadioSourceProgram resolveProgram(URI input, RadioResolveContext context)
            throws RadioSourceException {
        Objects.requireNonNull(input, "input");
        Objects.requireNonNull(context, "context");
        if (!this.supports(input)) {
            throw failure(RadioFailure.Code.INVALID_URL, false,
                    "SoundCloud sources must use an HTTP(S) soundcloud.com URL without userinfo", null);
        }

        AuthState auth = new AuthState();
        JsonObject page = this.resolvePage(input, context, auth);
        String kind = requiredString(page, "kind", "SoundCloud response");
        if ("track".equals(kind)) {
            requireStreamable(page);
            selectProgressive(page);
            context.budget().consumeEntries(1);
            TrackReference track = trackReference(page, input, 0);
            return new RadioSourceProgram(RadioSourceProgram.Kind.SERVICE_TRACKS, input, List.of(
                    new RadioSourceProgram.Track(track.source(), track.title(),
                            next -> this.openTrack(track, next))));
        }
        if (!"playlist".equals(kind)) {
            throw failure(RadioFailure.Code.UNSUPPORTED_AUDIO, false,
                    "The SoundCloud URL is not a track or playlist", null);
        }

        JsonArray entries = requiredArray(page, "tracks", "SoundCloud album");
        context.budget().consumeEntries(entries.size());
        List<RadioSourceProgram.Track> tracks = new ArrayList<>(entries.size());
        for (int i = 0; i < entries.size(); i++) {
            context.cancellation().throwIfCancelled();
            JsonObject track = object(entries.get(i), "SoundCloud album track " + i);
            if (isExplicitlyUnavailable(track)) {
                continue;
            }
            TrackReference reference = trackReference(track, null, i);
            tracks.add(new RadioSourceProgram.Track(reference.source(), reference.title(),
                    next -> this.openTrack(reference, next)));
        }
        if (tracks.isEmpty()) {
            throw failure(RadioFailure.Code.UNSUPPORTED_AUDIO, false,
                    "The SoundCloud album has no available tracks", null);
        }
        return new RadioSourceProgram(RadioSourceProgram.Kind.SERVICE_TRACKS, input, tracks);
    }

    private RadioResolvedSource openTrack(TrackReference track, RadioResolveContext context)
            throws RadioSourceException {
        AuthState auth = new AuthState();
        return this.openTrack(track, context, auth, false);
    }

    private RadioResolvedSource openTrack(TrackReference reference, RadioResolveContext context,
                                          AuthState auth, boolean mediaRetried)
            throws RadioSourceException {
        context.cancellation().throwIfCancelled();
        JsonObject track = reference.apiTrack()
                ? this.authenticatedJson(reference.source(), context, auth)
                : this.resolvePage(reference.source(), context, auth);
        if (!"track".equals(requiredString(track, "kind", "SoundCloud response"))) {
            throw failure(RadioFailure.Code.UNSUPPORTED_AUDIO, false,
                    "The SoundCloud album entry no longer resolves to a track", null);
        }
        requireStreamable(track);
        Transcoding transcoding = selectProgressive(track);
        JsonObject transcodingResponse = this.authenticatedJson(
                transcoding.uri(), context, auth, transcoding.authorization());
        URI media = parseHttpUri(requiredString(transcodingResponse, "url",
                "SoundCloud transcoding response"), "SoundCloud media URL");
        try {
            return this.direct.resolve(media, context);
        } catch (RadioSourceException exception) {
            if (!mediaRetried && exception.code() == RadioFailure.Code.HTTP_STATUS
                    && (exception.httpStatus() == 401 || exception.httpStatus() == 403
                    || exception.httpStatus() == 404)) {
                return this.openTrack(reference, context, auth, true);
            }
            throw exception;
        }
    }

    private JsonObject resolvePage(URI page, RadioResolveContext context, AuthState auth)
            throws RadioSourceException {
        URI request = appendQuery(this.resolveEndpoint, "url", page.toASCIIString());
        return this.authenticatedJson(request, context, auth);
    }

    private JsonObject authenticatedJson(URI endpoint, RadioResolveContext context, AuthState auth)
            throws RadioSourceException {
        return this.authenticatedJson(endpoint, context, auth, null);
    }

    private JsonObject authenticatedJson(URI endpoint, RadioResolveContext context, AuthState auth,
                                          @Nullable String trackAuthorization)
            throws RadioSourceException {
        if (!sameOrigin(endpoint, this.resolveEndpoint)) {
            throw failure(RadioFailure.Code.BLOCKED_ADDRESS, false,
                    "SoundCloud returned an untrusted API endpoint", null);
        }
        String currentId = this.getClientId(context);
        while (true) {
            URI request = appendQuery(endpoint, "client_id", currentId);
            if (trackAuthorization != null) {
                request = appendQuery(request, "track_authorization", trackAuthorization);
            }
            int rejectedStatus;
            try (AudioHttpResponse response = execute(request, context)) {
                rejectedStatus = response.statusCode() == 401 || response.statusCode() == 403
                        ? response.statusCode() : -1;
                if (rejectedStatus == -1) {
                    RadioHttpStatus.requireSuccess(response, "SoundCloud API");
                    return parseObject(readBounded(response, context, "SoundCloud API response"),
                            "SoundCloud API response");
                }
            }
            if (auth.refreshed) {
                throw httpFailure(rejectedStatus, "SoundCloud API");
            }
            auth.refreshed = true;
            currentId = this.refreshClientId(currentId, context);
        }
    }

    private String getClientId(RadioResolveContext context) throws RadioSourceException {
        Discovery discovery;
        boolean owner = false;
        synchronized (this.clientIdLock) {
            context.cancellation().throwIfCancelled();
            if (this.clientId != null) {
                return this.clientId;
            }
            discovery = this.clientIdDiscovery;
            if (discovery == null) {
                discovery = new Discovery();
                this.clientIdDiscovery = discovery;
                owner = true;
            }
        }
        if (owner) {
            try {
                String discovered = this.discoverClientId(context);
                synchronized (this.clientIdLock) {
                    if (this.clientId == null) {
                        this.clientId = discovered;
                    }
                    discovery.result.complete(this.clientId);
                }
            } catch (Exception failure) {
                discovery.result.completeExceptionally(failure);
            } finally {
                synchronized (this.clientIdLock) {
                    if (this.clientIdDiscovery == discovery) {
                        this.clientIdDiscovery = null;
                    }
                }
            }
        }
        try {
            return awaitDiscovery(discovery, context);
        } catch (CancellationException exception) {
            context.cancellation().throwIfCancelled();
            synchronized (this.clientIdLock) {
                if (this.clientIdDiscovery == discovery) {
                    this.clientIdDiscovery = null;
                }
            }
            return this.getClientId(context);
        }
    }

    private String refreshClientId(String rejected, RadioResolveContext context)
            throws RadioSourceException {
        synchronized (this.clientIdLock) {
            context.cancellation().throwIfCancelled();
            if (this.clientId != null && !this.clientId.equals(rejected)) {
                return this.clientId;
            }
            if (this.clientId != null) {
                this.clientId = null;
            }
        }
        return this.getClientId(context);
    }

    private static String awaitDiscovery(Discovery discovery, RadioResolveContext context)
            throws RadioSourceException {
        while (true) {
            context.cancellation().throwIfCancelled();
            try {
                return discovery.result.get(50L, TimeUnit.MILLISECONDS);
            } catch (TimeoutException ignored) {
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
                context.cancellation().throwIfCancelled();
                throw failure(RadioFailure.Code.UNKNOWN, true,
                        "Interrupted while discovering a SoundCloud client ID", exception);
            } catch (ExecutionException exception) {
                Throwable cause = exception.getCause();
                if (cause instanceof RadioSourceException source) {
                    throw source;
                }
                if (cause instanceof RuntimeException runtime) {
                    throw runtime;
                }
                throw failure(RadioFailure.Code.UNKNOWN, true,
                        "Could not discover a SoundCloud client ID", cause);
            }
        }
    }

    private String discoverClientId(RadioResolveContext context) throws RadioSourceException {
        URI pageUri;
        String html;
        try (AudioHttpResponse response = execute(this.homepage, context)) {
            RadioHttpStatus.requireSuccess(response, "SoundCloud homepage");
            pageUri = response.uri();
            html = new String(readBounded(response, context, "SoundCloud homepage"),
                    StandardCharsets.UTF_8);
        }

        Deque<URI> scripts = new ArrayDeque<>();
        Matcher matcher = SCRIPT_PATTERN.matcher(html);
        int candidateLimit = Math.min(MAX_SCRIPT_CANDIDATES,
                context.limits().maxPlaylistEntries());
        while (matcher.find()) {
            context.cancellation().throwIfCancelled();
            URI script;
            try {
                script = requireHttpUri(pageUri.resolve(matcher.group(2)),
                        "SoundCloud application script");
            } catch (IllegalArgumentException exception) {
                continue;
            }
            if (scripts.size() == candidateLimit) {
                scripts.removeFirst();
            }
            scripts.addLast(script);
        }
        RadioSourceException limitFailure = null;
        while (!scripts.isEmpty()) {
            URI script = scripts.removeLast();
            try (AudioHttpResponse response = execute(script, context)) {
                try {
                    RadioHttpStatus.requireSuccess(response, "SoundCloud application script");
                    String found = scanClientId(response, context);
                    if (found != null) {
                        return found;
                    }
                } catch (RadioSourceException exception) {
                    if (exception.recoverable()) {
                        throw exception;
                    }
                    if (exception.code() == RadioFailure.Code.RESOURCE_LIMIT) {
                        limitFailure = exception;
                    }
                }
            }
        }
        if (limitFailure != null) {
            throw limitFailure;
        }
        throw failure(RadioFailure.Code.UNSUPPORTED_AUDIO, false,
                "Could not discover a SoundCloud client ID", null);
    }

    private static Transcoding selectProgressive(JsonObject track) throws RadioSourceException {
        JsonObject media = requiredObject(track, "media", "SoundCloud track");
        JsonArray transcodings = requiredArray(media, "transcodings", "SoundCloud track media");
        URI progressive = null;
        String authorization = optionalString(track, "track_authorization");
        boolean hls = false;
        boolean other = false;
        for (int i = 0; i < transcodings.size(); i++) {
            JsonObject transcoding = object(transcodings.get(i),
                    "SoundCloud transcoding " + i);
            JsonObject format = requiredObject(transcoding, "format",
                    "SoundCloud transcoding " + i);
            String protocol = requiredString(format, "protocol",
                    "SoundCloud transcoding format " + i);
            String mimeType = optionalString(format, "mime_type");
            if ("progressive".equals(protocol) && "audio/mpeg".equalsIgnoreCase(mimeType)) {
                if (progressive == null) {
                    progressive = parseHttpUri(requiredString(transcoding, "url",
                            "SoundCloud transcoding " + i), "SoundCloud transcoding URL");
                }
            } else if ("hls".equals(protocol)) {
                hls = true;
            } else {
                other = true;
            }
        }
        if (progressive != null) {
            return new Transcoding(progressive, authorization);
        }
        if (hls && !other) {
            throw failure(RadioFailure.Code.UNSUPPORTED_HLS, false,
                    "This SoundCloud track is only available as HLS", null);
        }
        throw failure(RadioFailure.Code.UNSUPPORTED_AUDIO, false,
                "The SoundCloud track has no progressive MP3 transcoding", null);
    }

    private static void requireStreamable(JsonObject track) throws RadioSourceException {
        if (track.has("streamable") && !optionalBoolean(track, "streamable", false)) {
            throw failure(RadioFailure.Code.UNSUPPORTED_AUDIO, false,
                    "The SoundCloud track is not streamable", null);
        }
    }

    private static boolean isExplicitlyUnavailable(JsonObject track) throws RadioSourceException {
        if (track.has("streamable") && !optionalBoolean(track, "streamable", false)) {
            return true;
        }
        String policy = optionalString(track, "policy");
        return policy != null && policy.equalsIgnoreCase("BLOCK");
    }

    private TrackReference trackReference(JsonObject track, @Nullable URI fallback, int index)
            throws RadioSourceException {
        String value = optionalString(track, "permalink_url");
        Long id = optionalLong(track, "id");
        if (value == null && fallback == null && id == null) {
            throw failure(RadioFailure.Code.UNSUPPORTED_AUDIO, false,
                    "SoundCloud playlist track " + index + " has no page URL or ID", null);
        }
        if (value != null) {
            URI uri = parseHttpUri(value, "SoundCloud playlist track URL");
            String host = uri.getHost().toLowerCase(Locale.ROOT);
            if (!host.equals("soundcloud.com") && !host.endsWith(".soundcloud.com")) {
                throw failure(RadioFailure.Code.UNSUPPORTED_AUDIO, false,
                        "SoundCloud returned an invalid playlist track URL", null);
            }
            return new TrackReference(uri, optionalString(track, "title"), false);
        }
        if (fallback != null) {
            return new TrackReference(fallback, optionalString(track, "title"), false);
        }
        return new TrackReference(this.resolveEndpoint.resolve("/tracks/" + id),
                optionalString(track, "title"), true);
    }

    private static AudioHttpResponse execute(URI uri, RadioResolveContext context)
            throws RadioSourceException {
        context.cancellation().throwIfCancelled();
        context.budget().consumeSteps(1);
        AudioHttpResponse response;
        try {
            response = context.transport().execute(
                    AudioHttpRequest.resource(uri).withMaxRedirects(context.budget().remainingSteps()),
                    context.cancellation());
        } catch (RadioTransportException exception) {
            context.budget().consumeSteps(exception.redirectCount());
            if (exception.code() == RadioFailure.Code.TOO_MANY_REDIRECTS
                    && context.budget().remainingSteps() == 0) {
                throw failure(RadioFailure.Code.RESOURCE_LIMIT, false,
                        "Radio source resolution exceeded the configured step limit", exception);
            }
            throw RadioSourceException.fromTransport(exception);
        }
        try {
            context.budget().consumeSteps(response.redirectCount());
            return response;
        } catch (RadioSourceException exception) {
            response.close();
            throw exception;
        }
    }

    private static byte[] readBounded(AudioHttpResponse response, RadioResolveContext context,
                                      String description) throws RadioSourceException {
        int limit = context.limits().maxPlaylistBytes();
        if (response.contentLength().isPresent() && response.contentLength().getAsLong() > limit) {
            throw failure(RadioFailure.Code.RESOURCE_LIMIT, false,
                    description + " exceeds the configured size limit", null);
        }
        ByteArrayOutputStream output = new ByteArrayOutputStream(Math.min(limit, 8192));
        byte[] buffer = new byte[Math.min(8192, limit + 1)];
        try {
            while (output.size() <= limit) {
                context.cancellation().throwIfCancelled();
                int read = response.body().read(buffer, 0,
                        Math.min(buffer.length, limit + 1 - output.size()));
                if (read < 0) {
                    return output.toByteArray();
                }
                if (read > 0) {
                    output.write(buffer, 0, read);
                }
            }
        } catch (RadioTransportException exception) {
            throw RadioSourceException.fromTransport(exception);
        } catch (IOException exception) {
            throw failure(RadioFailure.Code.UNKNOWN, true,
                    "Could not read the " + description.toLowerCase(Locale.ROOT), exception);
        }
        throw failure(RadioFailure.Code.RESOURCE_LIMIT, false,
                description + " exceeds the configured size limit", null);
    }

    private static @Nullable String scanClientId(AudioHttpResponse response,
                                                  RadioResolveContext context)
            throws RadioSourceException {
        int limit = context.limits().maxPlaylistBytes();
        ByteArrayOutputStream prefix = new ByteArrayOutputStream(Math.min(limit, 8192));
        byte[] buffer = new byte[Math.min(8192, limit)];
        try {
            while (prefix.size() < limit) {
                context.cancellation().throwIfCancelled();
                int read = response.body().read(buffer, 0,
                        Math.min(buffer.length, limit - prefix.size()));
                if (read < 0) {
                    return null;
                }
                if (read == 0) {
                    continue;
                }
                prefix.write(buffer, 0, read);
                Matcher matcher = CLIENT_ID_PATTERN.matcher(
                        prefix.toString(StandardCharsets.UTF_8));
                if (matcher.find()) {
                    return matcher.group(1);
                }
            }
        } catch (RadioTransportException exception) {
            throw RadioSourceException.fromTransport(exception);
        } catch (IOException exception) {
            throw failure(RadioFailure.Code.UNKNOWN, true,
                    "Could not scan the SoundCloud application script", exception);
        }
        throw failure(RadioFailure.Code.RESOURCE_LIMIT, false,
                "SoundCloud application script scan exceeded the configured size limit", null);
    }

    private static JsonObject parseObject(byte[] bytes, String description)
            throws RadioSourceException {
        try {
            return JsonParser.parseString(new String(bytes, StandardCharsets.UTF_8)).getAsJsonObject();
        } catch (JsonParseException | IllegalStateException exception) {
            throw failure(RadioFailure.Code.UNSUPPORTED_AUDIO, false,
                    description + " is not valid JSON", exception);
        }
    }

    private static JsonObject object(JsonElement element, String description)
            throws RadioSourceException {
        if (element == null || !element.isJsonObject()) {
            throw failure(RadioFailure.Code.UNSUPPORTED_AUDIO, false,
                    description + " is not an object", null);
        }
        return element.getAsJsonObject();
    }

    private static JsonObject requiredObject(JsonObject object, String key, String description)
            throws RadioSourceException {
        return object(object.get(key), description + " field '" + key + "'");
    }

    private static JsonArray requiredArray(JsonObject object, String key, String description)
            throws RadioSourceException {
        JsonElement element = object.get(key);
        if (element == null || !element.isJsonArray()) {
            throw failure(RadioFailure.Code.UNSUPPORTED_AUDIO, false,
                    description + " field '" + key + "' is not an array", null);
        }
        return element.getAsJsonArray();
    }

    private static String requiredString(JsonObject object, String key, String description)
            throws RadioSourceException {
        String value = optionalString(object, key);
        if (value == null) {
            throw failure(RadioFailure.Code.UNSUPPORTED_AUDIO, false,
                    description + " field '" + key + "' is not a string", null);
        }
        return value;
    }

    private static @Nullable String optionalString(JsonObject object, String key)
            throws RadioSourceException {
        JsonElement element = object.get(key);
        if (element == null || element.isJsonNull()) {
            return null;
        }
        if (!element.isJsonPrimitive() || !element.getAsJsonPrimitive().isString()) {
            throw failure(RadioFailure.Code.UNSUPPORTED_AUDIO, false,
                    "SoundCloud field '" + key + "' is not a string", null);
        }
        return element.getAsString();
    }

    private static boolean optionalBoolean(JsonObject object, String key, boolean fallback)
            throws RadioSourceException {
        JsonElement element = object.get(key);
        if (element == null || element.isJsonNull()) {
            return fallback;
        }
        if (!element.isJsonPrimitive() || !element.getAsJsonPrimitive().isBoolean()) {
            throw failure(RadioFailure.Code.UNSUPPORTED_AUDIO, false,
                    "SoundCloud field '" + key + "' is not a boolean", null);
        }
        return element.getAsBoolean();
    }

    private static @Nullable Long optionalLong(JsonObject object, String key)
            throws RadioSourceException {
        JsonElement element = object.get(key);
        if (element == null || element.isJsonNull()) {
            return null;
        }
        try {
            long value = element.getAsLong();
            if (value <= 0L) {
                throw new NumberFormatException("non-positive ID");
            }
            return value;
        } catch (RuntimeException exception) {
            throw failure(RadioFailure.Code.UNSUPPORTED_AUDIO, false,
                    "SoundCloud field '" + key + "' is not a positive integer", exception);
        }
    }

    private static URI appendQuery(URI uri, String key, String value) throws RadioSourceException {
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
            throw failure(RadioFailure.Code.INVALID_URL, false,
                    "Could not construct a SoundCloud API URL", exception);
        }
    }

    private static URI parseHttpUri(String value, String description) throws RadioSourceException {
        try {
            return requireHttpUri(URI.create(value), description);
        } catch (IllegalArgumentException exception) {
            throw failure(RadioFailure.Code.UNSUPPORTED_AUDIO, false,
                    description + " is invalid", exception);
        }
    }

    private static URI requireHttpUri(URI uri, String description) {
        Objects.requireNonNull(uri, description);
        String scheme = uri.getScheme();
        if (!uri.isAbsolute() || uri.getHost() == null || uri.getUserInfo() != null
                || scheme == null || !scheme.equalsIgnoreCase("http")
                && !scheme.equalsIgnoreCase("https")) {
            throw new IllegalArgumentException(description + " must be an HTTP(S) URL without userinfo");
        }
        return uri;
    }

    private static boolean sameOrigin(URI first, URI second) {
        return first.getScheme().equalsIgnoreCase(second.getScheme())
                && first.getHost().equalsIgnoreCase(second.getHost())
                && effectivePort(first) == effectivePort(second);
    }

    private static int effectivePort(URI uri) {
        if (uri.getPort() >= 0) {
            return uri.getPort();
        }
        return uri.getScheme().equalsIgnoreCase("https") ? 443 : 80;
    }

    private static RadioSourceException httpFailure(int status, String description) {
        boolean recoverable = status == 408 || status == 429 || status == 500
                || status == 502 || status == 503 || status == 504;
        return new RadioSourceException(RadioFailure.Code.HTTP_STATUS, recoverable,
                description + " returned HTTP status " + status, null,
                RadioFailure.NO_RETRY_AFTER, status);
    }

    private static RadioSourceException failure(RadioFailure.Code code, boolean recoverable,
                                                String message, @Nullable Throwable cause) {
        return new RadioSourceException(code, recoverable, message, cause);
    }

    private static final class AuthState {

        private boolean refreshed;
    }

    private static final class Discovery {

        private final CompletableFuture<String> result = new CompletableFuture<>();
    }

    private record TrackReference(URI source, @Nullable String title, boolean apiTrack) {
    }

    private record Transcoding(URI uri, @Nullable String authorization) {
    }
}
