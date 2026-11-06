package gg.moonflower.etched.client.radio.source;

import gg.moonflower.etched.client.radio.RadioCancellation;
import gg.moonflower.etched.client.radio.RadioFailure;
import gg.moonflower.etched.client.radio.net.RadioHttpRequest;
import gg.moonflower.etched.client.radio.net.RadioHttpResponse;
import gg.moonflower.etched.client.radio.net.RadioTransportException;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** SoundCloud's finite, clear AAC/fMP4 media playlists; deliberately not a general live HLS client. */
final class SoundCloudHlsSource extends InputStream {
    static final int MAX_SEGMENTS = 4096;
    static final int MAX_INIT_BYTES = 1024 * 1024;
    static final int MAX_SEGMENT_BYTES = 4 * 1024 * 1024;
    private static final long MAX_MEDIA_BYTES = 512L * 1024 * 1024;
    private static final Pattern MAP = Pattern.compile("URI=\"([^\"]+)\"");
    private final RadioResolveContext context;
    private final RadioCancellation cancellation = new RadioCancellation();
    private RadioHttpResponse active;
    private volatile boolean closed;
    private List<URI> segments;
    private FragmentedMp4Aac demuxer;
    private ByteArrayInputStream pending = new ByteArrayInputStream(new byte[0]);
    private int index;
    private long bytesRead;

    private SoundCloudHlsSource(RadioResolveContext context) {
        this.context = context;
        context.cancellation().onCancel(cancellation::cancel);
    }

    static RadioResolvedSource open(URI uri, RadioResolveContext context) throws RadioSourceException {
        SoundCloudHlsSource body = new SoundCloudHlsSource(context);
        try {
            Resource manifest = body.fetch(uri, context.limits().maxPlaylistBytes(), true);
            Playlist playlist = parse(manifest.uri(), manifest.bytes(), context.limits().maxLineLength());
            body.segments = playlist.segments();
            body.demuxer = FragmentedMp4Aac.initialization(body.fetch(playlist.init(), MAX_INIT_BYTES, false).bytes());
            return new RadioResolvedSource(manifest.uri(), RadioResolvedSource.Format.AAC, body);
        } catch (RadioSourceException e) {
            body.close();
            throw e;
        } catch (IOException e) {
            body.close();
            throw new RadioSourceException(RadioFailure.Code.UNSUPPORTED_AUDIO, false,
                    "Unsupported SoundCloud AAC/MP4 stream", e);
        } catch (RuntimeException e) {
            body.close();
            throw e;
        }
    }

    static Playlist parse(URI base, byte[] bytes, int maxLineLength) throws IOException {
        String text = new String(bytes, StandardCharsets.UTF_8);
        String[] lines = text.split("\\r?\\n", -1);
        require(lines.length > 0 && lines[0].equals("#EXTM3U"), "Expected an HLS playlist");
        URI init = null;
        List<URI> segments = new ArrayList<>();
        boolean ended = false, durationPending = false;
        double totalDuration = 0;
        for (int i = 1; i < lines.length; i++) {
            String line = lines[i].trim();
            require(line.length() <= maxLineLength, "HLS line is too long");
            if (line.isEmpty()) continue;
            require(!ended, "Data after HLS end marker");
            if (line.startsWith("#EXT-X-MAP:")) {
                require(init == null && segments.isEmpty(), "Changing HLS initialization is unsupported");
                Matcher matcher = MAP.matcher(line.substring(11));
                require(matcher.matches(), "Only a complete, unencrypted MP4 initialization is supported");
                init = resolve(base, matcher.group(1));
            } else if (line.startsWith("#EXTINF:")) {
                require(!durationPending, "Missing HLS segment URI");
                String value = line.substring(8).split(",", 2)[0];
                double duration;
                try { duration = Double.parseDouble(value); }
                catch (NumberFormatException e) { throw new IOException("Invalid HLS duration", e); }
                require(Double.isFinite(duration) && duration > 0 && duration <= 60, "Unsupported HLS segment duration");
                totalDuration += duration;
                require(totalDuration <= 6 * 3600, "HLS track is too long");
                durationPending = true;
            } else if (line.equals("#EXT-X-ENDLIST")) {
                require(!durationPending, "Missing final HLS segment");
                ended = true;
            } else if (line.startsWith("#EXT-X-PLAYLIST-TYPE:")) {
                require(line.equals("#EXT-X-PLAYLIST-TYPE:VOD"), "Only finite VOD playlists are supported");
            } else if (line.startsWith("#EXT-X-VERSION:") || line.startsWith("#EXT-X-TARGETDURATION:")
                    || line.startsWith("#EXT-X-MEDIA-SEQUENCE:") || line.equals("#EXT-X-INDEPENDENT-SEGMENTS")) {
                // Informational; actual sample bounds and continuity are checked by the demuxer.
            } else if (line.startsWith("#EXT")) {
                throw new IOException("Unsupported HLS feature: " + line.split(":", 2)[0]);
            } else if (!line.startsWith("#")) {
                require(init != null && durationPending && segments.size() < MAX_SEGMENTS, "Invalid HLS segment list");
                segments.add(resolve(base, line));
                durationPending = false;
            }
        }
        require(ended && init != null && !segments.isEmpty(), "Expected a complete fMP4 VOD playlist");
        return new Playlist(init, List.copyOf(segments));
    }

    private static URI resolve(URI base, String value) throws IOException {
        try {
            URI uri = base.resolve(value);
            require(("http".equalsIgnoreCase(uri.getScheme()) || "https".equalsIgnoreCase(uri.getScheme()))
                    && uri.getHost() != null && uri.getUserInfo() == null && uri.getFragment() == null,
                    "Invalid HLS destination");
            return uri;
        } catch (IllegalArgumentException e) {
            throw new IOException("Invalid HLS URI", e);
        }
    }

    private Resource fetch(URI uri, int limit, boolean resolution) throws IOException {
        cancellation.throwIfCancelled();
        try { context.networkPolicy().check(uri, cancellation); }
        catch (RadioTransportException e) { throw RadioSourceException.fromTransport(e); }
        if (resolution) context.budget().consumeSteps(1);
        int redirects = resolution ? Math.min(5, context.budget().remainingSteps()) : 5;
        RadioHttpResponse response;
        try {
            response = context.transport().execute(RadioHttpRequest.resource(uri).withMaxRedirects(redirects), cancellation);
        } catch (RadioTransportException e) {
            if (resolution) context.budget().consumeSteps(e.redirectCount());
            throw RadioSourceException.fromTransport(e);
        }
        synchronized (this) {
            if (closed) {
                response.close();
                throw new IOException("HLS source is closed");
            }
            active = response;
        }
        try (response) {
            if (resolution) context.budget().consumeSteps(response.redirectCount());
            RadioHttpStatus.requireSuccess(response, "SoundCloud HLS");
            if (response.contentLength().isPresent()) require(response.contentLength().getAsLong() <= limit, "HLS resource is too large");
            byte[] bytes = response.body().readNBytes(limit + 1);
            cancellation.throwIfCancelled();
            require(bytes.length <= limit, "HLS resource is too large");
            bytesRead += bytes.length;
            require(bytesRead <= MAX_MEDIA_BYTES, "HLS track exceeds the byte limit");
            return new Resource(response.uri(), bytes);
        } catch (RadioTransportException e) {
            throw RadioSourceException.fromTransport(e);
        } finally {
            synchronized (this) { if (active == response) active = null; }
        }
    }

    @Override
    public int read(byte[] bytes, int offset, int length) throws IOException {
        Objects.checkFromIndexSize(offset, length, bytes.length);
        cancellation.throwIfCancelled();
        if (closed) throw new IOException("HLS source is closed");
        if (length == 0) return 0;
        try {
            if (pending.available() == 0) {
                if (index == segments.size()) return -1;
                byte[] fragment = fetch(segments.get(index++), MAX_SEGMENT_BYTES, false).bytes();
                pending = new ByteArrayInputStream(demuxer.fragment(fragment));
            }
            return pending.read(bytes, offset, length);
        } catch (IOException | RuntimeException e) {
            close();
            throw e;
        }
    }

    @Override
    public int read() throws IOException {
        byte[] one = new byte[1];
        return read(one, 0, 1) < 0 ? -1 : Byte.toUnsignedInt(one[0]);
    }

    @Override
    public void close() {
        RadioHttpResponse response;
        synchronized (this) {
            if (closed) return;
            closed = true;
            response = active;
            active = null;
        }
        cancellation.cancel();
        if (response != null) response.close();
    }

    private static void require(boolean valid, String message) throws IOException {
        if (!valid) throw new IOException(message);
    }

    record Playlist(URI init, List<URI> segments) { }
    private record Resource(URI uri, byte[] bytes) { }
}
