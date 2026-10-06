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
    void ordinaryRadioLootHasNoConditionalReplacement() throws IOException {
        var loot = json(Path.of("src/main/resources/data/etched/loot_tables/blocks/radio.json"));
        var pools = loot.getAsJsonArray("pools");
        assertEquals(1, pools.size());
        var pool = pools.get(0).getAsJsonObject();
        assertEquals(1, pool.get("rolls").getAsInt());
        var entries = pool.getAsJsonArray("entries");
        assertEquals(1, entries.size());
        var entry = entries.get(0).getAsJsonObject();
        assertEquals(Set.of("type", "name"), entry.keySet());
        assertEquals("minecraft:item", entry.get("type").getAsString());
        assertEquals("etched:radio", entry.get("name").getAsString());
        assertEquals("minecraft:survives_explosion", pool.getAsJsonArray("conditions").get(0)
                .getAsJsonObject().get("condition").getAsString());
    }

    @Test
    void provenancePinsExactlyTheNineRetainedResources() throws Exception {
        var manifest = json(Path.of("assets-src/radio/manifest.json"));
        assertEquals(1, manifest.get("schema").getAsInt());
        assertEquals("GPL-3.0-only", manifest.get("license").getAsString());
        assertEquals("assets-src/radio/artwork.json", manifest.get("artwork_provenance").getAsString());
        Set<String> expected = new HashSet<>();
        expected.add(ASSETS.resolve("blockstates/radio.json").toString());
        for (String suffix : new String[]{"", "_225", "_45", "_675"}) {
            expected.add(ASSETS.resolve("models/block/radio" + suffix + ".json").toString());
        }
        expected.add(ASSETS.resolve("models/item/radio.json").toString());
        expected.add(ASSETS.resolve("textures/item/radio.png").toString());
        expected.add(ASSETS.resolve("textures/block/radio.png").toString());
        expected.add(ASSETS.resolve("textures/block/radio_particle.png").toString());
        Set<String> actual = new HashSet<>();
        for (var entry : manifest.getAsJsonArray("files")) {
            var file = entry.getAsJsonObject();
            String path = file.get("path").getAsString();
            assertTrue(actual.add(path), "Duplicate manifest entry " + path);
            String sha = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(Path.of(path))));
            assertEquals(file.get("sha256").getAsString(), sha, path);
        }
        assertEquals(9, actual.size());
        assertEquals(expected, actual);
    }

    @Test
    void allRotationAndPoweredStatesResolveToTheCorrectHeading() throws IOException {
        var variants = json(ASSETS.resolve("blockstates/radio.json")).getAsJsonObject("variants");
        assertEquals(16, variants.size());
        for (int rotation = 0; rotation < 16; rotation++) {
            for (boolean powered : new boolean[]{false, true}) {
                var state = java.util.Map.of("rotation", String.valueOf(rotation), "powered", String.valueOf(powered));
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
                assertTrue(id.startsWith("etched:block/radio"));
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

    @Test
    @SuppressWarnings("deprecation") // Inspect the vanilla JSON element list, not a Forge custom geometry loader.
    void minecraftParsesEveryModelAndAllTextureReferencesExist() throws IOException {
        for (String suffix : new String[]{"", "_225", "_45", "_675"}) {
            Path path = ASSETS.resolve("models/block/radio" + suffix + ".json");
            assertEquals(7, BlockModel.fromString(Files.readString(path)).getElements().size());
            var model = json(path);
            assertEquals("minecraft:block/block", model.get("parent").getAsString());
            for (var texture : model.getAsJsonObject("textures").entrySet()) {
                String id = texture.getValue().getAsString();
                assertTrue(id.startsWith("etched:block/"));
                assertTrue(Files.isRegularFile(ASSETS.resolve("textures/" + id.substring(7) + ".png")), id);
            }
        }
        var item = json(ASSETS.resolve("models/item/radio.json"));
        assertNotNull(BlockModel.fromString(item.toString()));
        assertEquals("minecraft:item/generated", item.get("parent").getAsString());
        assertEquals("etched:item/radio", item.getAsJsonObject("textures").get("layer0").getAsString());
    }

    @Test
    void geometryUsesLegalRotationsCompleteFacesAndFitsAllExistingShapeEnvelopes() throws IOException {
        for (String name : new String[]{"radio"}) {
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
    void texturesHaveExpectedSizesAndAlpha() throws IOException {
        for (String name : new String[]{"radio"}) {
            for (String path : new String[]{"block/" + name, "block/" + name + "_particle", "item/" + name}) {
                var image = ImageIO.read(ASSETS.resolve("textures/" + path + ".png").toFile());
                assertNotNull(image, path);
                int size = path.equals("block/" + name) ? 32 : 16;
                assertEquals(size, image.getWidth());
                assertEquals(size, image.getHeight());
                boolean transparent = false;
                boolean opaque = false;
                for (int y = 0; y < size; y++) {
                    for (int x = 0; x < size; x++) {
                        int color = image.getRGB(x, y);
                        int alpha = color >>> 24;
                        assertTrue(alpha == 0 || alpha == 255);
                        transparent |= alpha == 0;
                        opaque |= alpha == 255;
                    }
                }
                assertEquals(path.startsWith("item/"), transparent);
                assertTrue(opaque);
            }
        }
    }

    @Test
    void artworkRecordsHumanImagesSeparatelyFromMechanicalExtraction() throws Exception {
        var artwork = json(Path.of("assets-src/radio/artwork.json"));
        assertEquals(1, artwork.get("schema").getAsInt());
        assertEquals("Re-Etched maintainer", artwork.get("author").getAsString());
        assertEquals("GPL-3.0-only", artwork.get("license").getAsString());
        Set<String> actual = new HashSet<>();
        for (var value : artwork.getAsJsonArray("files")) {
            var record = value.getAsJsonObject();
            String path = record.get("path").getAsString();
            assertTrue(actual.add(path));
            assertEquals(record.get("sha256").getAsString(), HexFormat.of().formatHex(
                    MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(Path.of(path)))));
            var image = ImageIO.read(Path.of(path).toFile());
            assertNotNull(image);
            assertEquals(record.getAsJsonArray("size").get(0).getAsInt(), image.getWidth());
            assertEquals(record.getAsJsonArray("size").get(1).getAsInt(), image.getHeight());
            if (path.endsWith("/radio_particle.png")) {
                assertEquals("exact-crop", record.get("origin").getAsString());
                assertEquals(ASSETS.resolve("textures/block/radio.png").toString(), record.get("source").getAsString());
                assertEquals(JsonParser.parseString("[0,0,16,16]"), record.get("rectangle"));
            } else {
                assertEquals("hand-drawn", record.get("origin").getAsString());
                assertEquals("Aseprite", record.get("tool").getAsString());
            }
        }
        assertEquals(Set.of(ASSETS.resolve("textures/block/radio.png").toString(),
                ASSETS.resolve("textures/block/radio_particle.png").toString(),
                ASSETS.resolve("textures/item/radio.png").toString()), actual);
    }

    @Test
    void particlePixelsExactlyMatchTheManualShellTile() throws IOException {
        var atlas = ImageIO.read(ASSETS.resolve("textures/block/radio.png").toFile());
        var particle = ImageIO.read(ASSETS.resolve("textures/block/radio_particle.png").toFile());
        assertNotNull(atlas);
        assertNotNull(particle);
        assertEquals(16, particle.getWidth());
        assertEquals(16, particle.getHeight());
        for (int y = 0; y < 16; y++) {
            for (int x = 0; x < 16; x++) {
                assertEquals(atlas.getRGB(x, y), particle.getRGB(x, y), "Particle crop changed pixel " + x + "," + y);
            }
        }
    }

    private static JsonObject json(Path path) throws IOException {
        return JsonParser.parseString(Files.readString(path)).getAsJsonObject();
    }
}
