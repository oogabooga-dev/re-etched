package gg.moonflower.etched.resources;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.minecraft.client.renderer.block.model.BlockModel;
import org.junit.jupiter.api.Test;

import javax.imageio.ImageIO;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

class RadioAssetsTest {

    private static final Path ASSETS = Path.of("src/main/resources/assets/etched");
    private static final Set<String> DIRECTIONS = Set.of("down", "up", "north", "south", "west", "east");

    @Test
    void provenancePinsExactlyTheSeventeenReplacedResources() throws Exception {
        var manifest = json(Path.of("assets-src/radio/manifest.json"));
        assertEquals(1, manifest.get("schema").getAsInt());
        assertEquals("GPL-3.0-only", manifest.get("license").getAsString());
        Set<String> expected = new HashSet<>();
        expected.add(ASSETS.resolve("blockstates/radio.json").toString());
        for (String name : new String[]{"radio", "portal_radio"}) {
            for (String suffix : new String[]{"", "_225", "_45", "_675"}) {
                expected.add(ASSETS.resolve("models/block/" + name + suffix + ".json").toString());
            }
            expected.add(ASSETS.resolve("models/item/" + name + ".json").toString());
            expected.add(ASSETS.resolve("textures/item/" + name + ".png").toString());
            expected.add(ASSETS.resolve("textures/block/" + name + ".png").toString());
            expected.add(ASSETS.resolve("textures/block/" + name + "_particle.png").toString());
        }
        Set<String> actual = new HashSet<>();
        for (var entry : manifest.getAsJsonArray("files")) {
            var file = entry.getAsJsonObject();
            String path = file.get("path").getAsString();
            assertTrue(actual.add(path), "Duplicate manifest entry " + path);
            String sha = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(Path.of(path))));
            assertEquals(file.get("sha256").getAsString(), sha, path);
        }
        assertEquals(17, actual.size());
        assertEquals(expected, actual);
    }

    @Test
    void allPortalRotationAndPoweredStatesResolveToTheCorrectHeading() throws IOException {
        var variants = json(ASSETS.resolve("blockstates/radio.json")).getAsJsonObject("variants");
        assertEquals(32, variants.size());
        for (boolean portal : new boolean[]{false, true}) {
            for (int rotation = 0; rotation < 16; rotation++) {
                for (boolean powered : new boolean[]{false, true}) {
                    var state = java.util.Map.of("portal", String.valueOf(portal),
                            "rotation", String.valueOf(rotation), "powered", String.valueOf(powered));
                    var matches = variants.entrySet().stream().filter(entry -> {
                        for (String property : entry.getKey().split(",")) {
                            String[] pair = property.split("=");
                            if (!pair[1].equals(state.get(pair[0]))) {
                                return false;
                            }
                        }
                        return true;
                    }).toList();
                    assertEquals(1, matches.size(), state.toString());
                    var variant = matches.get(0).getValue().getAsJsonObject();
                    String id = variant.get("model").getAsString();
                    assertTrue(id.startsWith("etched:block/" + (portal ? "portal_radio" : "radio")));
                    int y = variant.get("y").getAsInt();
                    assertTrue(Set.of(0, 90, 180, 270).contains(y));
                    var model = json(ASSETS.resolve("models/" + id.substring("etched:".length()) + ".json"));
                    for (var value : model.getAsJsonArray("elements")) {
                        var element = value.getAsJsonObject();
                        double angle = element.has("rotation") ? element.getAsJsonObject("rotation").get("angle").getAsDouble() : 0;
                        assertEquals((180 + rotation * 22.5) % 360, (y - angle + 360) % 360, 0.001);
                    }
                }
            }
        }
    }

    @Test
    @SuppressWarnings("deprecation") // Inspect the vanilla JSON element list, not a Forge custom geometry loader.
    void minecraftParsesEveryModelAndAllTextureReferencesExist() throws IOException {
        for (String name : new String[]{"radio", "portal_radio"}) {
            for (String suffix : new String[]{"", "_225", "_45", "_675"}) {
                Path path = ASSETS.resolve("models/block/" + name + suffix + ".json");
                assertEquals(7, BlockModel.fromString(Files.readString(path)).getElements().size());
                var model = json(path);
                assertEquals("minecraft:block/block", model.get("parent").getAsString());
                for (var texture : model.getAsJsonObject("textures").entrySet()) {
                    String id = texture.getValue().getAsString();
                    assertTrue(id.startsWith("etched:block/"));
                    assertTrue(Files.isRegularFile(ASSETS.resolve("textures/" + id.substring(7) + ".png")), id);
                }
            }
            var item = json(ASSETS.resolve("models/item/" + name + ".json"));
            assertNotNull(BlockModel.fromString(item.toString()));
            assertEquals("minecraft:item/generated", item.get("parent").getAsString());
            assertEquals("etched:item/" + name, item.getAsJsonObject("textures").get("layer0").getAsString());
        }
    }

    @Test
    void geometryUsesLegalRotationsCompleteFacesAndFitsAllExistingShapeEnvelopes() throws IOException {
        for (String name : new String[]{"radio", "portal_radio"}) {
            for (String suffix : new String[]{"", "_225", "_45", "_675"}) {
                var model = json(ASSETS.resolve("models/block/" + name + suffix + ".json"));
                for (var value : model.getAsJsonArray("elements")) {
                    var element = value.getAsJsonObject();
                    var from = element.getAsJsonArray("from");
                    var to = element.getAsJsonArray("to");
                    for (int axis = 0; axis < 3; axis++) {
                        assertTrue(from.get(axis).getAsDouble() < to.get(axis).getAsDouble());
                    }
                    assertTrue(from.get(1).getAsDouble() >= 0);
                    assertTrue(to.get(1).getAsDouble() <= 8);
                    if (element.has("rotation")) {
                        var transform = element.getAsJsonObject("rotation");
                        assertEquals("y", transform.get("axis").getAsString());
                        assertEquals(JsonParser.parseString("[8,0,8]"), transform.get("origin"));
                        assertFalse(transform.get("rescale").getAsBoolean());
                        assertTrue(Set.of(-22.5, 22.5, 45.0).contains(transform.get("angle").getAsDouble()));
                    }
                    // Cardinal shape: x=2..14,z=5..11; diagonal shape: x/z=3..13.
                    for (int rotation = 0; rotation < 16; rotation++) {
                        double angle = Math.toRadians(rotation * 22.5);
                        double xLimit = rotation % 8 == 0 ? 6 : rotation % 8 == 4 ? 3 : 5;
                        double zLimit = rotation % 8 == 0 ? 3 : rotation % 8 == 4 ? 6 : 5;
                        for (double x : new double[]{from.get(0).getAsDouble() - 8, to.get(0).getAsDouble() - 8}) {
                            for (double z : new double[]{from.get(2).getAsDouble() - 8, to.get(2).getAsDouble() - 8}) {
                                assertTrue(Math.abs(x * Math.cos(angle) - z * Math.sin(angle)) <= xLimit + 0.001);
                                assertTrue(Math.abs(x * Math.sin(angle) + z * Math.cos(angle)) <= zLimit + 0.001);
                            }
                        }
                    }
                    var faces = element.getAsJsonObject("faces");
                    assertEquals(DIRECTIONS, faces.keySet());
                    for (var face : faces.entrySet()) {
                        var data = face.getValue().getAsJsonObject();
                        assertEquals("#surface", data.get("texture").getAsString());
                        assertFalse(data.has("cullface"), "Small inset model faces must not be culled against neighbours");
                        var uv = data.getAsJsonArray("uv");
                        assertEquals(4, uv.size());
                        for (var coordinate : uv) {
                            assertTrue(coordinate.getAsDouble() >= 0 && coordinate.getAsDouble() <= 16);
                        }
                        assertTrue(uv.get(0).getAsDouble() < uv.get(2).getAsDouble());
                        assertTrue(uv.get(1).getAsDouble() < uv.get(3).getAsDouble());
                    }
                }
            }
        }
    }

    @Test
    void texturesHaveExpectedSizesAndAlphaWithoutReusingTheOtherFinish() throws IOException {
        for (String name : new String[]{"radio", "portal_radio"}) {
            for (String path : new String[]{"block/" + name, "block/" + name + "_particle", "item/" + name}) {
                var image = ImageIO.read(ASSETS.resolve("textures/" + path + ".png").toFile());
                assertNotNull(image, path);
                int size = path.equals("block/" + name) ? 32 : 16;
                assertEquals(size, image.getWidth());
                assertEquals(size, image.getHeight());
                boolean transparent = false;
                boolean opaque = false;
                Set<Integer> colors = new HashSet<>();
                for (int y = 0; y < size; y++) {
                    for (int x = 0; x < size; x++) {
                        int color = image.getRGB(x, y);
                        int alpha = color >>> 24;
                        assertTrue(alpha == 0 || alpha == 255);
                        transparent |= alpha == 0;
                        opaque |= alpha == 255;
                        colors.add(color);
                    }
                }
                assertEquals(path.startsWith("item/"), transparent);
                assertTrue(opaque);
                assertTrue(colors.size() >= 3);
            }
        }
        assertFalse(java.util.Arrays.equals(Files.readAllBytes(ASSETS.resolve("textures/block/radio.png")),
                Files.readAllBytes(ASSETS.resolve("textures/block/portal_radio.png"))));
    }

    private static JsonObject json(Path path) throws IOException {
        return JsonParser.parseString(Files.readString(path)).getAsJsonObject();
    }
}
