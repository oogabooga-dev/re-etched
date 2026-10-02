package gg.moonflower.etched.common.audio.net;

import gg.moonflower.etched.api.record.PlayableRecord;
import gg.moonflower.etched.api.record.TrackData;
import gg.moonflower.etched.common.audio.AudioCancellation;
import gg.moonflower.etched.common.audio.AudioContentProbe;
import gg.moonflower.etched.common.audio.RadioFailure;
import gg.moonflower.etched.common.audio.provider.BandcampMetadataResolver;
import gg.moonflower.etched.common.audio.provider.SoundCloudMetadataResolver;
import gg.moonflower.etched.common.item.EtchedMusicDiscItem;
import gg.moonflower.etched.core.Etched;
import gg.moonflower.etched.core.registry.EtchedItems;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.world.item.ItemStack;
import net.minecraftforge.gametest.GameTestHolder;
import net.minecraftforge.gametest.PrefixGameTestTemplate;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.Authenticator;
import java.net.HttpURLConnection;
import java.net.InetAddress;
import java.net.Proxy;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.ArrayList;
import java.util.Map;

/** Exercises the common transport in transformed Forge server code without live networking. */
@GameTestHolder(Etched.MOD_ID)
@PrefixGameTestTemplate(false)
public final class AudioTransportGameTests {

    private AudioTransportGameTests() {
    }

    @GameTest(template = "empty", timeoutTicks = 20)
    public static void providerCoverMetadataIsServerSafeAndDoesNotOpenImages(GameTestHelper helper) throws IOException {
        List<FixtureConnection> connections = new ArrayList<>();
        Proxy proxy = helper.getLevel().getServer().getProxy();
        var transport = new RadioHttpTransportImpl(proxy, destination -> {}, Duration.ofSeconds(1),
                Duration.ofSeconds(1), 5, (destination, configuredProxy) -> {
            helper.assertTrue(configuredProxy == proxy, "Cover metadata lost the configured proxy");
            String body;
            if (destination.getHost().equals("artist.bandcamp.com")) {
                body = "<div data-tralbum='{\"current\":{\"type\":\"album\",\"art_id\":123}}'></div>";
            } else if (destination.getHost().equals("soundcloud.com") && destination.getPath().equals("/")) {
                body = "<script src='/app.js'></script>";
            } else if (destination.getHost().equals("soundcloud.com") && destination.getPath().equals("/app.js")) {
                body = "client_id:'cover-client'";
            } else {
                helper.assertTrue(destination.getHost().equals("api-v2.soundcloud.com")
                        && destination.getPath().equals("/resolve"), "Cover metadata tried to download an image");
                body = "{\"kind\":\"track\",\"streamable\":true,\"artwork_url\":\"https://images.example/cover.jpg\"}";
            }
            var connection = new FixtureConnection(destination, body.getBytes(StandardCharsets.UTF_8));
            connections.add(connection);
            return connection;
        });
        AudioNetworkPolicy policy = destination -> helper.assertTrue(
                connections.stream().allMatch(connection -> connection.disconnected),
                "Provider page remained open during cover destination validation");
        URI bandcamp = new BandcampMetadataResolver(transport, policy, BandcampMetadataResolver.Limits.DEFAULT)
                .resolveAlbumCover(URI.create("https://artist.bandcamp.com/album/test"), new AudioCancellation()).orElseThrow();
        URI soundcloud = new SoundCloudMetadataResolver(transport, policy,
                URI.create("https://soundcloud.com/"), URI.create("https://api-v2.soundcloud.com/resolve"),
                SoundCloudMetadataResolver.Limits.DEFAULT)
                .resolveAlbumCover(URI.create("https://soundcloud.com/artist/track"), new AudioCancellation()).orElseThrow();
        helper.assertTrue(bandcamp.toString().equals("https://f4.bcbits.com/img/a123_1.jpg"), "Bandcamp lost the cover ID");
        helper.assertTrue(soundcloud.toString().equals("https://images.example/cover.jpg"), "SoundCloud lost the cover URL");
        helper.assertTrue(connections.size() == 4, "Cover metadata unexpectedly opened an image or repeated discovery");
        helper.succeed();
    }

    @GameTest(template = "empty", timeoutTicks = 20)
    public static void soundCloudDiscoveryAndMetadataWorkOnTheServer(GameTestHelper helper) throws IOException {
        URI input = URI.create("https://soundcloud.com/artist/album");
        String json = """
                {"kind":"playlist","is_album":true,"title":"Album","user":{"username":"Artist"},
                 "tracks":[{"permalink_url":"https://soundcloud.com/artist/one","title":"One"},
                           {"permalink_url":"https://soundcloud.com/artist/two","title":"Two"}]}
                """;
        List<FixtureConnection> connections = new ArrayList<>();
        Proxy proxy = helper.getLevel().getServer().getProxy();
        var transport = new RadioHttpTransportImpl(proxy, destination -> {}, Duration.ofSeconds(1),
                Duration.ofSeconds(1), 5, (destination, configuredProxy) -> {
            helper.assertTrue(configuredProxy == proxy, "SoundCloud discovery lost the server proxy");
            String body;
            if (destination.getPath().equals("/")) {
                body = "<script src='/app.js'></script>";
            } else if (destination.getPath().equals("/app.js")) {
                body = "client_id:'server-client'";
            } else {
                helper.assertTrue(destination.getHost().equals("api-v2.soundcloud.com")
                        && destination.getPath().equals("/resolve"), "Metadata tried to open audio or another endpoint");
                helper.assertTrue(destination.getRawQuery().contains("client_id=server-client"),
                        "SoundCloud API did not receive the discovered client ID");
                body = json;
            }
            var connection = new FixtureConnection(destination, body.getBytes(StandardCharsets.UTF_8));
            connections.add(connection);
            return connection;
        });
        var tracks = new SoundCloudMetadataResolver(transport, destination -> {},
                URI.create("https://soundcloud.com/"), URI.create("https://api-v2.soundcloud.com/resolve"),
                SoundCloudMetadataResolver.Limits.DEFAULT).resolveTracks(input, new AudioCancellation());
        ItemStack disc = new ItemStack(EtchedItems.ETCHED_MUSIC_DISC.get());
        EtchedMusicDiscItem.setMusic(disc, tracks.toArray(TrackData[]::new));
        helper.assertTrue(PlayableRecord.getStackAlbum(disc).orElseThrow().url().equals(input.toString()),
                "SoundCloud metadata lost the album descriptor");
        TrackData[] music = PlayableRecord.getStackMusic(disc).orElseThrow();
        helper.assertTrue(music.length == 2 && music[0].title().getString().equals("One")
                && music[1].title().getString().equals("Two"), "SoundCloud metadata lost disc track order");
        helper.assertTrue(connections.size() == 3 && connections.stream().allMatch(connection -> connection.disconnected),
                "SoundCloud discovery or metadata leaked an HTTP response");
        helper.succeed();
    }

    @GameTest(template = "empty", timeoutTicks = 20)
    public static void bandcampMetadataKeepsAlbumAndOrderedDiscTracksOnTheServer(GameTestHelper helper)
            throws IOException {
        URI uri = URI.create("https://artist.bandcamp.com/album/example");
        byte[] html = """
                <div data-tralbum='{"artist":"Artist", "current":{"type":"album","title":"Album"},
                "trackinfo":[{"title_link":"/track/one","title":"One"},
                             {"title_link":"/track/two","title":"Two"}]}'></div>
                """.getBytes(StandardCharsets.UTF_8);
        FixtureConnection connection = new FixtureConnection(uri, html);
        Proxy proxy = helper.getLevel().getServer().getProxy();
        RadioHttpTransportImpl transport = new RadioHttpTransportImpl(proxy, destination -> {},
                Duration.ofSeconds(1), Duration.ofSeconds(1), 5, (destination, configuredProxy) -> {
            helper.assertTrue(destination.equals(uri), "Metadata tried to open another destination");
            helper.assertTrue(configuredProxy == proxy, "Metadata lost the server proxy");
            return connection;
        });
        var tracks = new BandcampMetadataResolver(transport, destination -> {}, BandcampMetadataResolver.Limits.DEFAULT)
                .resolveTracks(uri, new AudioCancellation());
        ItemStack disc = new ItemStack(EtchedItems.ETCHED_MUSIC_DISC.get());
        EtchedMusicDiscItem.setMusic(disc, tracks.toArray(TrackData[]::new));
        helper.assertTrue(PlayableRecord.getStackAlbum(disc).orElseThrow().title().getString().equals("Album"),
                "Bandcamp metadata lost the album descriptor");
        TrackData[] music = PlayableRecord.getStackMusic(disc).orElseThrow();
        helper.assertTrue(music.length == 2 && music[0].title().getString().equals("One")
                && music[1].title().getString().equals("Two"), "Bandcamp metadata lost disc track order");
        helper.assertTrue(connection.disconnected, "Bandcamp metadata leaked its page response");
        helper.succeed();
    }

    @GameTest(template = "empty", timeoutTicks = 20)
    public static void commonContentProbeRecognizesAudioOnTheServer(GameTestHelper helper) throws IOException {
        byte[] tagged = {'I', 'D', '3', 4, 0, 0, 0, 0, 0, 0,
                (byte) 0xFF, (byte) 0xFB, (byte) 0x90, 0x64};
        byte[] prefix = AudioContentProbe.readPrefix(new ByteArrayInputStream(tagged), new AudioCancellation(),
                AudioContentProbe.DEFAULT_SNIFF_BYTES, AudioContentProbe.DEFAULT_MAX_ID3_PREFIX_BYTES);
        helper.assertTrue(AudioContentProbe.classify(prefix, AudioContentProbe.DEFAULT_MAX_ID3_PREFIX_BYTES)
                == AudioContentProbe.Format.MP3, "Server probe lost ID3/MPEG recognition");
        helper.assertTrue(AudioContentProbe.classify(new byte[]{'O', 'g', 'g', 'S'},
                AudioContentProbe.DEFAULT_MAX_ID3_PREFIX_BYTES) == AudioContentProbe.Format.UNKNOWN,
                "Server probe trusted an Ogg signature without Vorbis identification");
        helper.succeed();
    }

    @GameTest(template = "empty", timeoutTicks = 20)
    public static void commonTransportOwnsResponsesAndBlocksPrivateDestinations(GameTestHelper helper)
            throws IOException {
        URI uri = URI.create("https://media.example/track");
        FixtureConnection connection = new FixtureConnection(uri);
        Proxy serverProxy = helper.getLevel().getServer().getProxy();
        RadioHttpTransportImpl transport = new RadioHttpTransportImpl(serverProxy, destination -> {
            helper.assertTrue(destination.equals(uri), "Transport did not validate the destination");
        }, Duration.ofSeconds(1), Duration.ofSeconds(1), 2, (destination, proxy) -> {
            helper.assertTrue(proxy == serverProxy, "Transport lost the configured server proxy");
            return connection;
        });
        try (AudioHttpResponse response = transport.execute(
                AudioHttpRequest.resource(uri), new AudioCancellation())) {
            helper.assertTrue(response.statusCode() == 200, "Transport lost the response status");
            helper.assertTrue(response.body().read() == 42, "Transport did not expose the owned response");
            helper.assertFalse(connection.getInstanceFollowRedirects(), "Automatic redirects were enabled");
            helper.assertFalse(connection.disconnected, "Response was closed before ownership transfer");
        }
        helper.assertTrue(connection.disconnected, "Response close did not release the connection");

        DefaultRadioNetworkPolicy policy = new DefaultRadioNetworkPolicy(() -> false,
                host -> new InetAddress[]{InetAddress.getByAddress(new byte[]{127, 0, 0, 1})});
        RadioHttpTransportImpl blocked = new RadioHttpTransportImpl(serverProxy, policy,
                Duration.ofSeconds(1), Duration.ofSeconds(1), 2, (destination, proxy) -> {
            throw new AssertionError("A blocked destination reached connection setup");
        });
        try (AudioHttpResponse ignored = blocked.execute(
                AudioHttpRequest.resource(uri), new AudioCancellation())) {
            throw new AssertionError("Common transport accepted a loopback destination");
        } catch (RadioTransportException exception) {
            helper.assertTrue(exception.code() == RadioFailure.Code.BLOCKED_ADDRESS,
                    "Transport did not preserve the blocked-address classification");
        }
        helper.succeed();
    }

    private static final class FixtureConnection extends HttpURLConnection {

        private final byte[] body;
        private boolean disconnected;

        private FixtureConnection(URI uri) throws IOException {
            this(uri, new byte[]{42});
        }

        private FixtureConnection(URI uri, byte[] body) throws IOException {
            super(uri.toURL());
            this.body = body;
        }

        @Override
        public void connect() {
            this.connected = true;
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
        public void setAuthenticator(Authenticator authenticator) {
        }

        @Override
        public int getResponseCode() {
            return 200;
        }

        @Override
        public Map<String, List<String>> getHeaderFields() {
            return Map.of("Content-Length", List.of(Integer.toString(this.body.length)));
        }

        @Override
        public InputStream getInputStream() {
            return new ByteArrayInputStream(this.body);
        }
    }
}
