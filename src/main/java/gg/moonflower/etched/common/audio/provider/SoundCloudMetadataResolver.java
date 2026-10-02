package gg.moonflower.etched.common.audio.provider;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import gg.moonflower.etched.api.record.TrackData;
import gg.moonflower.etched.common.audio.AudioCancellation;
import gg.moonflower.etched.common.audio.AudioProgram;
import gg.moonflower.etched.common.audio.RadioFailure;
import gg.moonflower.etched.common.audio.net.AudioHttpRequest;
import gg.moonflower.etched.common.audio.net.AudioHttpResponse;
import gg.moonflower.etched.common.audio.net.AudioHttpTransport;
import gg.moonflower.etched.common.audio.net.AudioNetworkPolicy;
import gg.moonflower.etched.common.audio.net.DefaultRadioNetworkPolicy;
import gg.moonflower.etched.common.audio.net.RadioHttpTransportImpl;
import gg.moonflower.etched.common.audio.net.RadioTransportException;
import net.minecraft.network.chat.Component;

import java.io.IOException;
import java.net.Proxy;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/** Server-safe metadata with request-owned client-ID discovery, responses, and resolution budget. */
public final class SoundCloudMetadataResolver {

    private static final URI HOMEPAGE = URI.create("https://soundcloud.com/");
    private static final URI RESOLVE_ENDPOINT = URI.create("https://api-v2.soundcloud.com/resolve");

    public record Limits(int maxBodyBytes, int maxTracks, int maxFieldLength,
                         int maxResolutionSteps, int maxScriptCandidates, int maxRedirects) {
        public static final Limits DEFAULT = new Limits(256 * 1024, AudioProgram.MAX_TRACKS, 8192,
                128, SoundCloudPageReader.MAX_SCRIPT_CANDIDATES, RadioHttpTransportImpl.DEFAULT_MAX_REDIRECTS);

        public Limits {
            if (maxBodyBytes < 1 || maxTracks < 1 || maxFieldLength < 1 || maxResolutionSteps < 1
                    || maxScriptCandidates < 1 || maxRedirects < 0) {
                throw new IllegalArgumentException("Invalid SoundCloud metadata limits");
            }
        }
    }

    private final AudioHttpTransport transport;
    private final AudioNetworkPolicy networkPolicy;
    private final URI homepage;
    private final URI resolveEndpoint;
    private final Limits limits;

    public SoundCloudMetadataResolver(Proxy proxy) {
        this.networkPolicy = new DefaultRadioNetworkPolicy(() -> false);
        this.transport = new RadioHttpTransportImpl(proxy, this.networkPolicy,
                RadioHttpTransportImpl.DEFAULT_CONNECT_TIMEOUT, RadioHttpTransportImpl.DEFAULT_READ_TIMEOUT,
                RadioHttpTransportImpl.DEFAULT_MAX_REDIRECTS);
        this.homepage = HOMEPAGE;
        this.resolveEndpoint = RESOLVE_ENDPOINT;
        this.limits = Limits.DEFAULT;
    }

    public SoundCloudMetadataResolver(AudioHttpTransport transport, AudioNetworkPolicy networkPolicy,
                                      URI homepage, URI resolveEndpoint, Limits limits) {
        this.transport = Objects.requireNonNull(transport, "transport");
        this.networkPolicy = Objects.requireNonNull(networkPolicy, "networkPolicy");
        this.homepage = SoundCloudPageReader.requireHttpUri(homepage, "SoundCloud homepage");
        this.resolveEndpoint = SoundCloudPageReader.requireHttpUri(resolveEndpoint, "SoundCloud resolve endpoint");
        this.limits = Objects.requireNonNull(limits, "limits");
    }

    public List<TrackData> resolveTracks(URI input, AudioCancellation cancellation) throws IOException {
        JsonObject page = this.fetchPage(input, cancellation);
        List<TrackData> tracks = this.parseTracks(input, page, cancellation);
        for (TrackData track : tracks) {
            cancellation.throwIfCancelled();
            this.networkPolicy.check(URI.create(track.url()), cancellation);
        }
        cancellation.throwIfCancelled();
        return List.copyOf(tracks);
    }

    public Optional<URI> resolveAlbumCover(URI input, AudioCancellation cancellation) throws IOException {
        JsonObject page = this.fetchPage(input, cancellation);
        String kind = field(page, "kind");
        if (kind.equals("track")) {
            if (!requiredBoolean(page, "streamable")) {
                throw invalid("SoundCloud track is not streamable");
            }
        } else if (!kind.equals("playlist") || !requiredBoolean(page, "is_album")) {
            throw invalid("SoundCloud URL is not a track or album");
        }
        if (!page.has("artwork_url") || page.get("artwork_url").isJsonNull()) {
            cancellation.throwIfCancelled();
            return Optional.empty();
        }
        URI cover;
        try {
            cover = SoundCloudPageReader.requireHttpUri(URI.create(field(page, "artwork_url")), "SoundCloud cover URL");
        } catch (IllegalArgumentException exception) {
            throw invalid("SoundCloud cover URL is invalid");
        }
        this.networkPolicy.check(cover, cancellation);
        cancellation.throwIfCancelled();
        return Optional.of(cover);
    }

    private JsonObject fetchPage(URI input, AudioCancellation cancellation) throws IOException {
        cancellation.throwIfCancelled();
        requirePage(input);
        requireLength(input.toString());
        // The API resolves the submitted page indirectly, so validate it before contacting any service.
        this.networkPolicy.check(input, cancellation);
        Operation operation = new Operation(cancellation);
        JsonObject page = this.resolvePage(input, operation);
        cancellation.throwIfCancelled();
        return page;
    }

    private JsonObject resolvePage(URI input, Operation operation) throws IOException {
        URI endpoint = SoundCloudPageReader.appendQuery(this.resolveEndpoint, "url", input.toASCIIString());
        String clientId = this.discoverClientId(operation);
        for (int attempt = 0; attempt < 2; attempt++) {
            int rejectedStatus;
            URI request = SoundCloudPageReader.appendQuery(endpoint, "client_id", clientId);
            try (AudioHttpResponse response = operation.execute(request)) {
                if (!SoundCloudPageReader.sameOrigin(response.uri(), this.resolveEndpoint)) {
                    throw failure(RadioFailure.Code.BLOCKED_ADDRESS, false,
                            "SoundCloud API redirected outside its trusted origin", null);
                }
                rejectedStatus = response.statusCode();
                if (rejectedStatus != 401 && rejectedStatus != 403) {
                    requireSuccess(response, "SoundCloud API");
                    return SoundCloudPageReader.parseObject(SoundCloudPageReader.readBounded(response,
                            operation.cancellation, this.limits.maxBodyBytes(), "SoundCloud API response"),
                            "SoundCloud API response");
                }
                if (attempt == 1) {
                    requireSuccess(response, "SoundCloud API");
                }
            }
            // The rejected response has been released before discovery is retried, once only.
            clientId = this.discoverClientId(operation);
        }
        throw new AssertionError("SoundCloud authentication retry did not terminate");
    }

    private String discoverClientId(Operation operation) throws IOException {
        URI pageUri;
        String html;
        try (AudioHttpResponse response = operation.execute(this.homepage)) {
            requireSuccess(response, "SoundCloud homepage");
            pageUri = response.uri();
            html = new String(SoundCloudPageReader.readBounded(response, operation.cancellation,
                    this.limits.maxBodyBytes(), "SoundCloud homepage"), StandardCharsets.UTF_8);
        }
        var scripts = new ArrayDeque<>(SoundCloudPageReader.scriptCandidates(pageUri, html,
                this.limits.maxScriptCandidates(), operation.cancellation));
        RadioTransportException limitFailure = null;
        while (!scripts.isEmpty()) {
            // Policy failures while opening a script must not be swallowed as an unavailable candidate.
            try (AudioHttpResponse response = operation.execute(scripts.removeLast())) {
                try {
                    requireSuccess(response, "SoundCloud application script");
                    String found = SoundCloudPageReader.scanClientId(response, operation.cancellation,
                            this.limits.maxBodyBytes());
                    if (found != null) {
                        requireLength(found);
                        return found;
                    }
                } catch (RadioTransportException exception) {
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
        throw failure(RadioFailure.Code.UNSUPPORTED_AUDIO, false, "Could not discover a SoundCloud client ID", null);
    }

    private List<TrackData> parseTracks(URI input, JsonObject page, AudioCancellation cancellation) throws IOException {
        String kind = field(page, "kind");
        String artist = field(object(page.get("user"), "user"), "username");
        String title = field(page, "title");
        if (kind.equals("track")) {
            if (!requiredBoolean(page, "streamable")) {
                throw invalid("SoundCloud track is not streamable");
            }
            return List.of(new TrackData(input.toString(), artist, Component.literal(title)));
        }
        if (!kind.equals("playlist") || !requiredBoolean(page, "is_album")) {
            throw invalid("SoundCloud URL is not a track or album");
        }
        JsonElement value = page.get("tracks");
        if (value == null || !value.isJsonArray()) {
            throw invalid("SoundCloud album tracks is not an array");
        }
        JsonArray entries = value.getAsJsonArray();
        if (entries.size() > this.limits.maxTracks()) {
            throw failure(RadioFailure.Code.RESOURCE_LIMIT, false, "SoundCloud album exceeds the track limit", null);
        }
        List<TrackData> tracks = new ArrayList<>(entries.size() + 1);
        tracks.add(new TrackData(input.toString(), artist, Component.literal(title)));
        for (JsonElement entry : entries) {
            cancellation.throwIfCancelled();
            JsonObject track = object(entry, "album track");
            // Preserve the legacy metadata's handling of paid/incomplete entries without a permalink.
            if (!track.has("permalink_url")) {
                continue;
            }
            URI uri;
            try {
                uri = URI.create(field(track, "permalink_url"));
            } catch (IllegalArgumentException exception) {
                throw invalid("SoundCloud album track URL is malformed");
            }
            requirePage(uri);
            JsonObject user = track.has("user") ? object(track.get("user"), "track user") : null;
            tracks.add(new TrackData(uri.toString(), user == null ? artist : field(user, "username"),
                    Component.literal(field(track, "title"))));
        }
        if (tracks.size() == 1) {
            throw invalid("SoundCloud album has no tracks with a page URL");
        }
        return tracks;
    }

    private String field(JsonObject object, String key) throws RadioTransportException {
        JsonElement value = object.get(key);
        if (value == null || !value.isJsonPrimitive() || !value.getAsJsonPrimitive().isString()) {
            throw invalid("SoundCloud field '" + key + "' is not a string");
        }
        String result = value.getAsString();
        requireLength(result);
        return result;
    }

    private void requireLength(String value) throws RadioTransportException {
        if (value.length() > this.limits.maxFieldLength()) {
            throw failure(RadioFailure.Code.RESOURCE_LIMIT, false, "SoundCloud metadata field exceeds the length limit", null);
        }
    }

    private static JsonObject object(JsonElement value, String description) throws RadioTransportException {
        if (value == null || !value.isJsonObject()) {
            throw invalid("SoundCloud " + description + " is not an object");
        }
        return value.getAsJsonObject();
    }

    private static boolean requiredBoolean(JsonObject object, String key) throws RadioTransportException {
        JsonElement value = object.get(key);
        if (value == null || !value.isJsonPrimitive() || !value.getAsJsonPrimitive().isBoolean()) {
            throw invalid("SoundCloud field '" + key + "' is not a boolean");
        }
        return value.getAsBoolean();
    }

    private static void requirePage(URI uri) throws RadioTransportException {
        if (!SoundCloudPageReader.supports(uri)) {
            throw failure(RadioFailure.Code.INVALID_URL, false, "SoundCloud metadata URL leaves soundcloud.com", null);
        }
    }

    private static void requireSuccess(AudioHttpResponse response, String description) throws RadioTransportException {
        int status = response.statusCode();
        if (status != 200) {
            boolean recoverable = status == 408 || status == 429 || status == 500
                    || status == 502 || status == 503 || status == 504;
            throw failure(RadioFailure.Code.HTTP_STATUS, recoverable, description + " returned HTTP " + status, null);
        }
    }

    private static RadioTransportException invalid(String message) {
        return failure(RadioFailure.Code.UNSUPPORTED_AUDIO, false, message, null);
    }

    private static RadioTransportException failure(RadioFailure.Code code, boolean recoverable,
                                                   String message, Throwable cause) {
        return new RadioTransportException(code, recoverable, message, cause);
    }

    private final class Operation {
        private final AudioCancellation cancellation;
        private int remainingSteps = limits.maxResolutionSteps();

        private Operation(AudioCancellation cancellation) {
            this.cancellation = cancellation;
        }

        private void consume(int steps) throws RadioTransportException {
            if (steps > this.remainingSteps || steps < 0) {
                throw failure(RadioFailure.Code.RESOURCE_LIMIT, false, "SoundCloud metadata exceeded the step limit", null);
            }
            this.remainingSteps -= steps;
        }

        private AudioHttpResponse execute(URI uri) throws RadioTransportException {
            this.cancellation.throwIfCancelled();
            consume(1);
            AudioHttpResponse response;
            try {
                response = transport.execute(AudioHttpRequest.resource(uri)
                        .withMaxRedirects(Math.min(limits.maxRedirects(), this.remainingSteps)), this.cancellation);
            } catch (RadioTransportException exception) {
                consume(exception.redirectCount());
                if (exception.code() == RadioFailure.Code.TOO_MANY_REDIRECTS && this.remainingSteps == 0) {
                    throw failure(RadioFailure.Code.RESOURCE_LIMIT, false, "SoundCloud metadata exceeded the step limit", exception);
                }
                throw exception;
            }
            try {
                consume(response.redirectCount());
                return response;
            } catch (RadioTransportException exception) {
                response.close();
                throw exception;
            }
        }
    }
}
