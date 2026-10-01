package gg.moonflower.etched.common.audio.net;

import gg.moonflower.etched.common.audio.AudioCancellation;
import gg.moonflower.etched.common.audio.RadioFailure;
import gg.moonflower.etched.common.audio.provider.SoundCloudMetadataResolver;
import org.junit.jupiter.api.Test;

import java.net.InetAddress;
import java.net.Proxy;
import java.net.URI;
import java.time.Duration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class SoundCloudMetadataPolicyTest {

    @Test
    void validatesTheSubmittedPagesDnsBeforeDiscovery() {
        var policy = new DefaultRadioNetworkPolicy(() -> false,
                host -> new InetAddress[]{InetAddress.getByAddress(new byte[]{127, 0, 0, 1})});
        var transport = new RadioHttpTransportImpl(Proxy.NO_PROXY, policy, Duration.ofSeconds(1),
                Duration.ofSeconds(1), 5, (uri, proxy) -> {
            throw new AssertionError("A blocked SoundCloud page started discovery");
        });
        var resolver = new SoundCloudMetadataResolver(transport, policy,
                URI.create("https://soundcloud.com/"), URI.create("https://api-v2.soundcloud.com/resolve"),
                SoundCloudMetadataResolver.Limits.DEFAULT);
        assertEquals(RadioFailure.Code.BLOCKED_ADDRESS, assertThrows(RadioTransportException.class,
                () -> resolver.resolveTracks(URI.create("https://soundcloud.com/a/track"), new AudioCancellation())).code());
    }
}
