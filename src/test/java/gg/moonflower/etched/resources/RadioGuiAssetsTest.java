package gg.moonflower.etched.resources;

import com.google.gson.JsonParser;
import org.junit.jupiter.api.Test;

import javax.imageio.ImageIO;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

class RadioGuiAssetsTest {

    @Test
    void provenancePinsAllThreeConfirmedManualGuiImages() throws Exception {
        var manifest = JsonParser.parseString(Files.readString(Path.of("assets-src/radio/gui-artwork.json"))).getAsJsonObject();
        assertEquals(1, manifest.get("schema").getAsInt());
        assertEquals("GPL-3.0-only", manifest.get("license").getAsString());
        assertEquals("Re-Etched maintainer", manifest.get("author").getAsString());
        assertEquals("hand-drawn", manifest.get("origin").getAsString());
        Set<String> paths = new HashSet<>();
        for (var value : manifest.getAsJsonArray("files")) {
            var record = value.getAsJsonObject();
            String path = record.get("path").getAsString();
            assertTrue(paths.add(path));
            assertEquals(record.get("sha256").getAsString(), HexFormat.of().formatHex(
                    MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(Path.of(path)))));
            var image = ImageIO.read(Path.of(path).toFile());
            assertNotNull(image);
            int width = path.endsWith("/radio_background.png") ? 256 : 240;
            int height = path.endsWith("/radio_background.png") ? 180 : 14;
            assertEquals(width, record.getAsJsonArray("size").get(0).getAsInt());
            assertEquals(height, record.getAsJsonArray("size").get(1).getAsInt());
            assertEquals(width, image.getWidth());
            assertEquals(height, image.getHeight());
        }
        String base = "src/main/resources/assets/etched/textures/gui/";
        assertEquals(Set.of(base + "radio_background.png", base + "radio_url_active.png",
                base + "radio_url_inactive.png"), paths);
    }
}
