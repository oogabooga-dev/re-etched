package gg.moonflower.etched.common.sound.download;

import gg.moonflower.etched.client.radio.net.TestHttpServer;
import org.junit.jupiter.api.Test;

import java.net.Proxy;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.*;

class SoundCloudIdTrackerTest {
    @Test
    void discoversRelativeScriptsAndFallsBackPastScriptsWithoutId() throws Exception {
        net.minecraft.SharedConstants.tryDetectVersion();
        try (var server = new TestHttpServer()) {
            server.handle("/", exchange -> {
                byte[] html = "<script src='/new-app.js'></script><script src='/other.js'></script>".getBytes(StandardCharsets.UTF_8);
                exchange.sendResponseHeaders(200, html.length);
                try (exchange; var output = exchange.getResponseBody()) { output.write(html); }
            });
            server.handle("/other.js", exchange -> {
                exchange.sendResponseHeaders(404, -1);
                exchange.close();
            });
            server.handle("/new-app.js", exchange -> {
                byte[] script = "window.app={'client_id': 'current-id_42'};".getBytes(StandardCharsets.UTF_8);
                exchange.sendResponseHeaders(200, script.length);
                try (exchange; var output = exchange.getResponseBody()) { output.write(script); }
            });
            assertEquals("current-id_42", SoundCloudIdTracker.discover(server.uri("/"), Proxy.NO_PROXY));
        }
    }

    @Test
    void rejectsHostSuffixImpersonation() {
        var source = new SoundCloudSource();
        assertTrue(source.isValidUrl("https://soundcloud.com/a/track"));
        assertTrue(source.isValidUrl("https://m.soundcloud.com/a/track"));
        assertFalse(source.isValidUrl("https://notsoundcloud.com/a/track"));
    }
}
