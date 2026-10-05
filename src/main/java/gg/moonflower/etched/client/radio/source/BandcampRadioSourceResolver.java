package gg.moonflower.etched.client.radio.source;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParseException;
import gg.moonflower.etched.common.audio.RadioFailure;
import gg.moonflower.etched.common.audio.provider.BandcampPageReader;
import gg.moonflower.etched.common.audio.net.AudioHttpRequest;
import gg.moonflower.etched.common.audio.net.AudioHttpResponse;
import gg.moonflower.etched.common.audio.net.RadioTransportException;

import java.io.IOException;
import java.net.URI;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Objects;

/** Resolves Bandcamp track and album pages into finite radio programs. */
public final class BandcampRadioSourceResolver implements AudioSourceResolver {

    private final DirectRadioSourceResolver direct;

    public BandcampRadioSourceResolver() {
        this(new DirectRadioSourceResolver());
    }

    BandcampRadioSourceResolver(DirectRadioSourceResolver direct) {
        this.direct = Objects.requireNonNull(direct, "direct");
    }

    @Override
    public boolean supports(URI input) {
        return BandcampPageReader.supports(input);
    }

    @Override
    public RadioSourceProgram resolveProgram(URI input, AudioResolveContext context)
            throws RadioSourceException {
        Objects.requireNonNull(input, "input");
        Objects.requireNonNull(context, "context");
        if (!this.supports(input)) {
            throw failure(RadioFailure.Code.INVALID_URL,
                    "Bandcamp radio sources must use an HTTP(S) bandcamp.com URL without user info", null);
        }

        ParsedPage page = this.fetchPage(input, context);
        List<RadioSourceProgram.Track> descriptors = new ArrayList<>(page.tracks().size());
        for (ParsedTrack track : page.tracks()) {
            descriptors.add(new RadioSourceProgram.Track(track.source(), track.title(),
                    next -> this.openTrack(input, track, next)));
        }
        return new RadioSourceProgram(RadioSourceProgram.Kind.SERVICE_TRACKS, input, descriptors);
    }

    private RadioResolvedSource openTrack(URI servicePage, ParsedTrack track,
                                          AudioResolveContext context) throws RadioSourceException {
        context.cancellation().throwIfCancelled();
        try {
            return this.direct.resolve(track.media(), context);
        } catch (RadioSourceException exception) {
            if (!isExpiredMediaStatus(exception)) {
                throw exception;
            }
        }

        ParsedPage refreshed = this.fetchPage(servicePage, context);
        ParsedTrack refreshedTrack = refreshed.tracks().stream()
                .filter(candidate -> track.identity() != null
                        ? track.identity().equals(candidate.identity())
                        : candidate.index() == track.index())
                .findFirst()
                .orElseThrow(() -> failure(RadioFailure.Code.UNSUPPORTED_AUDIO,
                        "The refreshed Bandcamp page no longer contains this playable track", null));
        return this.direct.resolve(refreshedTrack.media(), context);
    }

    private ParsedPage fetchPage(URI servicePage, AudioResolveContext context)
            throws RadioSourceException {
        context.cancellation().throwIfCancelled();
        context.budget().consumeSteps(1);
        AudioHttpRequest request = AudioHttpRequest.resource(servicePage)
                .withMaxRedirects(context.budget().remainingSteps());
        AudioHttpResponse response;
        try {
            response = context.transport().execute(request, context.cancellation());
        } catch (RadioTransportException exception) {
            context.budget().consumeSteps(exception.redirectCount());
            if (exception.code() == RadioFailure.Code.TOO_MANY_REDIRECTS
                    && context.budget().remainingSteps() == 0) {
                throw failure(RadioFailure.Code.RESOURCE_LIMIT,
                        "Bandcamp resolution exceeded the configured step limit", exception);
            }
            throw RadioSourceException.fromTransport(exception);
        }

        try (response) {
            context.budget().consumeSteps(response.redirectCount());
            if (!this.supports(response.uri())) {
                throw failure(RadioFailure.Code.INVALID_URL,
                        "Bandcamp redirected the service page outside bandcamp.com", null);
            }
            RadioHttpStatus.requireSuccess(response, "Bandcamp");
            JsonObject data;
            try {
                data = BandcampPageReader.read(response, context.cancellation(), context.limits().maxPlaylistBytes());
            } catch (RadioTransportException exception) {
                throw RadioSourceException.fromTransport(exception);
            } catch (IOException exception) {
                throw failure(RadioFailure.Code.UNKNOWN, "Could not read the Bandcamp page", exception);
            }
            return parsePage(response.uri(), data, context);
        }
    }

    private static ParsedPage parsePage(URI pageUri, JsonObject root, AudioResolveContext context)
            throws RadioSourceException {
        try {
            JsonObject current = requiredObject(root, "current");
            String type = requiredString(current, "type");
            if (!type.equals("track") && !type.equals("album")) {
                throw new JsonParseException("current.type is not track or album");
            }

            JsonArray trackInfo = root.getAsJsonArray("trackinfo");
            if (trackInfo == null) {
                throw new JsonParseException("trackinfo is missing");
            }
            context.budget().consumeEntries(trackInfo.size());
            List<ParsedTrack> tracks = new ArrayList<>(trackInfo.size());
            for (int i = 0; i < trackInfo.size(); i++) {
                JsonObject entry = trackInfo.get(i).getAsJsonObject();
                JsonObject files = entry.getAsJsonObject("file");
                String mediaValue = optionalString(files, "mp3-128");
                if (mediaValue == null) {
                    continue;
                }
                URI media = absoluteHttpUri(mediaValue, "Bandcamp mp3-128 URL");
                URI source = type.equals("album")
                        ? bandcampTrackUri(pageUri, requiredString(entry, "title_link")) : pageUri;
                tracks.add(new ParsedTrack(i, trackIdentity(entry), source,
                        optionalString(entry, "title"), media));
            }
            if (tracks.isEmpty()) {
                throw new JsonParseException("trackinfo contains no mp3-128 files");
            }
            return new ParsedPage(List.copyOf(tracks));
        } catch (JsonParseException | IllegalStateException | IllegalArgumentException exception) {
            throw failure(RadioFailure.Code.UNSUPPORTED_AUDIO,
                    "Bandcamp page contains invalid track data", exception);
        }
    }

    private static URI bandcampTrackUri(URI pageUri, String value) {
        URI track = pageUri.resolve(value);
        if (track.getRawUserInfo() != null || track.getHost() == null) {
            throw new IllegalArgumentException("Bandcamp track URL is invalid");
        }
        String scheme = track.getScheme();
        String host = track.getHost().toLowerCase(Locale.ROOT);
        if (scheme == null || !scheme.equalsIgnoreCase("http") && !scheme.equalsIgnoreCase("https")
                || !host.equals("bandcamp.com") && !host.endsWith(".bandcamp.com")) {
            throw new IllegalArgumentException("Bandcamp track URL leaves bandcamp.com");
        }
        return track;
    }

    private static URI absoluteHttpUri(String value, String description) {
        URI uri = URI.create(value);
        String scheme = uri.getScheme();
        if (!uri.isAbsolute() || uri.getHost() == null || uri.getRawUserInfo() != null
                || scheme == null || !scheme.equalsIgnoreCase("http") && !scheme.equalsIgnoreCase("https")) {
            throw new IllegalArgumentException(description + " is invalid");
        }
        return uri;
    }

    private static JsonObject requiredObject(JsonObject object, String name) {
        JsonObject value = object.getAsJsonObject(name);
        if (value == null) {
            throw new JsonParseException(name + " is missing");
        }
        return value;
    }

    private static String requiredString(JsonObject object, String name) {
        String value = optionalString(object, name);
        if (value == null) {
            throw new JsonParseException(name + " is missing");
        }
        return value;
    }

    private static String optionalString(JsonObject object, String name) {
        if (object == null) {
            return null;
        }
        JsonElement value = object.get(name);
        return value == null || value.isJsonNull() ? null : value.getAsString();
    }

    private static String trackIdentity(JsonObject track) {
        String identity = optionalString(track, "track_id");
        return identity != null ? identity : optionalString(track, "id");
    }

    private static boolean isExpiredMediaStatus(RadioSourceException exception) {
        return exception.code() == RadioFailure.Code.HTTP_STATUS
                && (exception.httpStatus() == 401 || exception.httpStatus() == 403
                || exception.httpStatus() == 404);
    }

    private static RadioSourceException failure(RadioFailure.Code code, String message,
                                                Throwable cause) {
        return new RadioSourceException(code, false, message, cause);
    }

    private record ParsedPage(List<ParsedTrack> tracks) {
    }

    private record ParsedTrack(int index, String identity, URI source, String title, URI media) {
    }
}
