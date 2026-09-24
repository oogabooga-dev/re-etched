package gg.moonflower.etched.client.radio.net;

import gg.moonflower.etched.client.radio.PlaybackSession;
import gg.moonflower.etched.client.radio.source.AudioResolveContext;
import gg.moonflower.etched.client.radio.source.DirectRadioSourceResolver;
import gg.moonflower.etched.client.radio.source.RadioResolveLimits;
import gg.moonflower.etched.client.radio.source.RadioResolvedSource;
import gg.moonflower.etched.client.radio.source.RadioSourceException;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.Authenticator;
import java.net.HttpURLConnection;
import java.net.Proxy;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DirectRadioSourceResolverOwnershipTest {

    private static final AudioNetworkPolicy ALLOW_ALL = uri -> {
    };

    @Test
    void closesPlaylistAndFailedFallbackBeforeTransferringTheSelectedResponse() throws Exception {
        URI playlistUri = URI.create("http://radio.example/stations.m3u");
        URI firstUri = URI.create("http://radio.example/first");
        URI secondUri = URI.create("http://radio.example/second");
        TrackingConnection playlist = connection(200, "audio/x-mpegurl",
                firstUri + "\n" + secondUri + "\n");
        TrackingConnection first = connection(503, "text/plain", "unavailable");
        TrackingConnection second = connection(200, "audio/mpeg", "ID3-audio");
        RadioHttpTransportImpl transport = transport((uri, proxy) -> {
            if (uri.equals(playlistUri)) {
                return playlist;
            }
            if (uri.equals(firstUri)) {
                assertTrue(playlist.disconnected);
                return first;
            }
            if (uri.equals(secondUri)) {
                assertTrue(first.disconnected);
                return second;
            }
            throw new IOException("Unexpected URI: " + uri);
        });

        RadioResolvedSource source = new DirectRadioSourceResolver().resolve(
                playlistUri, context(transport));
        InputStream body = source.body();

        assertEquals(secondUri, source.uri());
        source.close();
        assertTrue(second.disconnected);
        assertThrows(IOException.class, body::read);
    }

    @Test
    void closesTheResponseWhenPlaylistParsingFails() throws Exception {
        URI playlistUri = URI.create("http://radio.example/stations.m3u");
        TrackingConnection playlist = connection(200, "audio/x-mpegurl", "file:///etc/passwd\n");
        RadioHttpTransportImpl transport = transport((uri, proxy) -> playlist);

        assertThrows(RadioSourceException.class,
                () -> new DirectRadioSourceResolver().resolve(playlistUri, context(transport)));

        assertTrue(playlist.disconnected);
    }

    private static AudioResolveContext context(AudioHttpTransport transport) {
        return new AudioResolveContext(transport, ALLOW_ALL,
                new PlaybackSession().start("http://radio.example/live").cancellation(),
                new RadioResolveLimits(16, 1024, 10, 256, 2, 20));
    }

    private static RadioHttpTransportImpl transport(
            RadioHttpTransportImpl.ConnectionFactory factory) {
        return new RadioHttpTransportImpl(
                Proxy.NO_PROXY, ALLOW_ALL, Duration.ofSeconds(1), Duration.ofSeconds(1), 2, factory);
    }

    private static TrackingConnection connection(int status, String contentType, String body)
            throws IOException {
        return new TrackingConnection(status, contentType, body.getBytes(StandardCharsets.UTF_8));
    }

    private static final class TrackingConnection extends HttpURLConnection {

        private final int status;
        private final String contentType;
        private final byte[] bytes;
        private boolean disconnected;

        private TrackingConnection(int status, String contentType, byte[] bytes) throws IOException {
            super(URI.create("http://radio.example/live").toURL());
            this.status = status;
            this.contentType = contentType;
            this.bytes = bytes;
        }

        @Override
        public void disconnect() {
            this.disconnected = true;
        }

        @Override
        public boolean usingProxy() {
            return false;
        }

        @Override
        public void connect() {
            this.connected = true;
        }

        @Override
        public int getResponseCode() {
            return this.status;
        }

        @Override
        public void setAuthenticator(Authenticator authenticator) {
        }

        @Override
        public InputStream getInputStream() {
            return new ByteArrayInputStream(this.bytes);
        }

        @Override
        public InputStream getErrorStream() {
            return new ByteArrayInputStream(this.bytes);
        }

        @Override
        public Map<String, List<String>> getHeaderFields() {
            return Map.of(
                    "Content-Type", List.of(this.contentType),
                    "Content-Length", List.of(Integer.toString(this.bytes.length)));
        }
    }
}
