package gg.moonflower.etched.common.audio.net;

import gg.moonflower.etched.common.audio.AudioCancellation;
import gg.moonflower.etched.common.audio.AudioContentProbe;
import gg.moonflower.etched.common.audio.RadioFailure;
import gg.moonflower.etched.core.Etched;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
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
import java.time.Duration;
import java.util.List;
import java.util.Map;

/** Exercises the common transport in transformed Forge server code without live networking. */
@GameTestHolder(Etched.MOD_ID)
@PrefixGameTestTemplate(false)
public final class AudioTransportGameTests {

    private AudioTransportGameTests() {
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

        private boolean disconnected;

        private FixtureConnection(URI uri) throws IOException {
            super(uri.toURL());
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
            return Map.of("Content-Length", List.of("1"));
        }

        @Override
        public InputStream getInputStream() {
            return new ByteArrayInputStream(new byte[]{42});
        }
    }
}
