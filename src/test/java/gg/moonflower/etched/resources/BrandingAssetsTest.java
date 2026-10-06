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

class BrandingAssetsTest {

    @Test
    void provenancePinsBothConfirmedManualBrandingImages() throws Exception {
        var manifest = JsonParser.parseString(Files.readString(Path.of("assets-src/branding/manifest.json"))).getAsJsonObject();
        assertEquals(1, manifest.get("schema").getAsInt());
        assertEquals("GPL-3.0-only", manifest.get("license").getAsString());
        assertEquals("Re-Etched maintainer", manifest.get("author").getAsString());
        Set<String> paths = new HashSet<>();
        for (var value : manifest.getAsJsonArray("files")) {
            var record = value.getAsJsonObject();
            String path = record.get("path").getAsString();
            assertTrue(paths.add(path));
            assertEquals(record.get("sha256").getAsString(), HexFormat.of().formatHex(
                    MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(Path.of(path)))));
            var image = ImageIO.read(Path.of(path).toFile());
            assertNotNull(image);
            assertEquals(record.getAsJsonArray("size").get(0).getAsInt(), image.getWidth());
            assertEquals(record.getAsJsonArray("size").get(1).getAsInt(), image.getHeight());
        }
        assertEquals(Set.of("src/main/resources/re-etched.icon.png", "src/main/resources/re-etched.logo.png"), paths);
        String metadata = Files.readString(Path.of("src/main/resources/META-INF/mods.toml"));
        assertTrue(metadata.contains("logoFile = \"re-etched.logo.png\""));
        assertTrue(metadata.contains("catalogueImageIcon = \"re-etched.icon.png\""));
    }
}
