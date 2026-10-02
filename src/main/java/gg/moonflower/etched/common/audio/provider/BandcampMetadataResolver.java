package gg.moonflower.etched.common.audio.provider;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParseException;
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
import org.apache.commons.lang3.StringEscapeUtils;

import java.io.IOException;
import java.net.Proxy;
import java.net.URI;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.CancellationException;

/** Request-owned metadata for etching; no opened responses or futures are shared between menus. */
public final class BandcampMetadataResolver {

    public record Limits(int maxBodyBytes, int maxTracks, int maxFieldLength, int maxRedirects) {
        public static final Limits DEFAULT = new Limits(BandcampPageReader.DEFAULT_MAX_BODY_BYTES,
                AudioProgram.MAX_TRACKS, 8192, RadioHttpTransportImpl.DEFAULT_MAX_REDIRECTS);

        public Limits {
            if (maxBodyBytes < 1 || maxTracks < 1 || maxFieldLength < 1 || maxRedirects < 0) {
                throw new IllegalArgumentException("Invalid Bandcamp metadata limits");
            }
        }
    }

    private final AudioHttpTransport transport;
    private final AudioNetworkPolicy networkPolicy;
    private final Limits limits;

    public BandcampMetadataResolver(Proxy proxy) {
        this.networkPolicy = new DefaultRadioNetworkPolicy(() -> false);
        this.transport = new RadioHttpTransportImpl(proxy, this.networkPolicy,
                RadioHttpTransportImpl.DEFAULT_CONNECT_TIMEOUT, RadioHttpTransportImpl.DEFAULT_READ_TIMEOUT,
                RadioHttpTransportImpl.DEFAULT_MAX_REDIRECTS);
        this.limits = Limits.DEFAULT;
    }

    public BandcampMetadataResolver(AudioHttpTransport transport, AudioNetworkPolicy networkPolicy, Limits limits) {
        this.transport = Objects.requireNonNull(transport, "transport");
        this.networkPolicy = Objects.requireNonNull(networkPolicy, "networkPolicy");
        this.limits = Objects.requireNonNull(limits, "limits");
    }

    public List<TrackData> resolveTracks(URI input, AudioCancellation cancellation) throws IOException {
        Page page = this.fetchPage(input, cancellation);
        List<TrackData> tracks = this.parseTracks(input, page.uri(), page.data(), cancellation);
        // Release the page before potentially slow DNS checks on stored service-page destinations.
        for (TrackData track : tracks) {
            cancellation.throwIfCancelled();
            this.networkPolicy.check(URI.create(track.url()), cancellation);
        }
        cancellation.throwIfCancelled();
        return List.copyOf(tracks);
    }

    public Optional<URI> resolveAlbumCover(URI input, AudioCancellation cancellation) throws IOException {
        Page page = this.fetchPage(input, cancellation);
        URI cover;
        try {
            JsonObject current = page.data().getAsJsonObject("current");
            String type = field(current, "type");
            if (!type.equals("track") && !type.equals("album")) {
                throw new JsonParseException("current.type is not track or album");
            }
            if (!current.has("art_id") || current.get("art_id").isJsonNull()) {
                cancellation.throwIfCancelled();
                return Optional.empty();
            }
            if (!current.get("art_id").isJsonPrimitive()) {
                throw new JsonParseException("art_id is not an integer");
            }
            String id = current.get("art_id").getAsString();
            if (!id.matches("[1-9][0-9]{0,18}") || Long.parseLong(id) <= 0) {
                throw new JsonParseException("art_id is not a positive integer");
            }
            cover = URI.create("https://f4.bcbits.com/img/a" + id + "_1.jpg");
            requireLength(cover.toString());
        } catch (CancellationException exception) {
            throw exception;
        } catch (JsonParseException | IllegalStateException | IllegalArgumentException
                 | NullPointerException | ClassCastException exception) {
            throw failure(RadioFailure.Code.UNSUPPORTED_AUDIO, "Bandcamp page contains invalid cover metadata", exception);
        }
        this.networkPolicy.check(cover, cancellation);
        cancellation.throwIfCancelled();
        return Optional.of(cover);
    }

    private Page fetchPage(URI input, AudioCancellation cancellation) throws IOException {
        cancellation.throwIfCancelled();
        requirePage(input);
        requireLength(input.toString());
        Page page;
        try (AudioHttpResponse response = this.transport.execute(
                AudioHttpRequest.resource(input).withMaxRedirects(this.limits.maxRedirects()), cancellation)) {
            requirePage(response.uri());
            if (response.statusCode() != 200) {
                throw failure(RadioFailure.Code.HTTP_STATUS,
                        "Bandcamp returned HTTP " + response.statusCode(), null);
            }
            page = new Page(response.uri(), BandcampPageReader.read(response, cancellation, this.limits.maxBodyBytes()));
        }
        cancellation.throwIfCancelled();
        return page;
    }

    private List<TrackData> parseTracks(URI input, URI pageUri, JsonObject page, AudioCancellation cancellation)
            throws IOException {
        try {
            JsonObject current = page.getAsJsonObject("current");
            String type = field(current, "type");
            String artist = field(page, "artist");
            String title = field(current, "title");
            if (type.equals("track")) {
                return List.of(new TrackData(input.toString(), artist, Component.literal(title)));
            }
            if (!type.equals("album")) {
                throw new JsonParseException("current.type is not track or album");
            }
            JsonArray entries = page.getAsJsonArray("trackinfo");
            if (entries == null || entries.isEmpty()) {
                throw new JsonParseException("trackinfo is missing or empty");
            }
            if (entries.size() > this.limits.maxTracks()) {
                throw failure(RadioFailure.Code.RESOURCE_LIMIT, "Bandcamp album exceeds the track limit", null);
            }
            // Legacy disc metadata puts the album descriptor before its ordered track descriptors.
            List<TrackData> tracks = new ArrayList<>(entries.size() + 1);
            tracks.add(new TrackData(input.toString(), artist, Component.literal(title)));
            for (int i = 0; i < entries.size(); i++) {
                cancellation.throwIfCancelled();
                JsonObject entry = entries.get(i).getAsJsonObject();
                URI trackUri = pageUri.resolve(field(entry, "title_link"));
                requirePage(trackUri);
                requireLength(trackUri.toString());
                String trackArtist = entry.has("artist") && !entry.get("artist").isJsonNull()
                        ? field(entry, "artist") : artist;
                tracks.add(new TrackData(trackUri.toString(), trackArtist, Component.literal(field(entry, "title"))));
            }
            return tracks;
        } catch (CancellationException exception) {
            throw exception;
        } catch (JsonParseException | IllegalStateException | IllegalArgumentException
                 | NullPointerException | ClassCastException exception) {
            throw failure(RadioFailure.Code.UNSUPPORTED_AUDIO, "Bandcamp page contains invalid metadata", exception);
        }
    }

    @SuppressWarnings("deprecation") // Preserve the legacy metadata's HTML entity decoding.
    private String field(JsonObject object, String name) throws IOException {
        if (object == null || !object.has(name) || object.get(name).isJsonNull()
                || !object.get(name).isJsonPrimitive() || !object.get(name).getAsJsonPrimitive().isString()) {
            throw new JsonParseException(name + " is missing or not a string");
        }
        String value = StringEscapeUtils.unescapeHtml4(object.get(name).getAsString());
        requireLength(value);
        return value;
    }

    private void requireLength(String value) throws RadioTransportException {
        if (value.length() > this.limits.maxFieldLength()) {
            throw failure(RadioFailure.Code.RESOURCE_LIMIT, "Bandcamp metadata field exceeds the length limit", null);
        }
    }

    private static void requirePage(URI uri) throws RadioTransportException {
        if (!BandcampPageReader.supports(uri)) {
            throw failure(RadioFailure.Code.INVALID_URL, "Bandcamp metadata URL leaves bandcamp.com", null);
        }
    }

    private static RadioTransportException failure(RadioFailure.Code code, String message, Throwable cause) {
        return new RadioTransportException(code, false, message, cause);
    }

    private record Page(URI uri, JsonObject data) {
    }
}
