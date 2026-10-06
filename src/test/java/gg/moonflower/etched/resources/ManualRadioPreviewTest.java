package gg.moonflower.etched.resources;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import gg.moonflower.etched.client.radio.MinecraftTestBootstrap;
import gg.moonflower.etched.common.block.RadioBlock;
import net.minecraft.client.renderer.block.model.BlockModel;
import net.minecraft.world.phys.shapes.VoxelShape;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.Base64;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

class ManualRadioPreviewTest {

    private static final Path SOURCE = Path.of("assets-src/radio/manual/radio.json");
    private static final Path PREVIEW = Path.of(
            "src/clientSmoke/resources/assets/etched/models/block/manual_radio_preview.json");

    @Test
    @SuppressWarnings("deprecation") // Inspect the vanilla model's source elements, not a custom loader.
    void previewPreservesAuthoredGeometryAndEveryRetainedFace() throws IOException {
        var source = json(SOURCE);
        var expected = source.deepCopy();
        expected.remove("format_version");
        expected.addProperty("parent", "minecraft:block/block");
        var textures = new JsonObject();
        textures.addProperty("0", "etched:block/radio");
        textures.addProperty("particle", "etched:block/radio_particle");
        expected.add("textures", textures);
        Set<String> removed = new HashSet<>();
        for (var value : expected.getAsJsonArray("elements")) {
            var element = value.getAsJsonObject();
            var faces = element.getAsJsonObject("faces");
            for (String direction : Set.copyOf(faces.keySet())) {
                if (faces.getAsJsonObject(direction).get("texture").getAsString().equals("#missing")) {
                    assertTrue(covered(element, direction, expected), "Cannot remove a visible face");
                    assertTrue(removed.add(element.get("name").getAsString() + "/" + direction));
                    faces.remove(direction);
                }
            }
        }
        assertEquals(Set.of("panel/south", "panel/west", "sound/east", "tune/east"), removed);
        assertEquals(expected, json(PREVIEW), "Preview must not redesign geometry or retained UVs");
        assertEquals(4, BlockModel.fromString(Files.readString(PREVIEW)).getElements().size());
    }

    @Test
    void editableSourceMatchesExportAndContainsTheExactManualAtlas() throws IOException {
        var project = json(Path.of("assets-src/radio/manual/radio.bbmodel"));
        assertEquals("java_block", project.getAsJsonObject("meta").get("model_format").getAsString());
        var elements = project.getAsJsonArray("elements");
        var exported = json(SOURCE).getAsJsonArray("elements");
        assertEquals(exported.size(), elements.size());
        for (int i = 0; i < elements.size(); i++) {
            var editable = elements.get(i).getAsJsonObject();
            var output = exported.get(i).getAsJsonObject();
            for (String property : Set.of("name", "from", "to", "origin")) {
                assertEquals(editable.get(property), property.equals("origin")
                        ? output.getAsJsonObject("rotation").get("origin") : output.get(property));
            }
            for (var face : editable.getAsJsonObject("faces").entrySet()) {
                assertEquals(face.getValue().getAsJsonObject().get("uv"),
                        output.getAsJsonObject("faces").getAsJsonObject(face.getKey()).get("uv"));
            }
        }
        var textures = project.getAsJsonArray("textures");
        assertEquals(1, textures.size());
        String embedded = textures.get(0).getAsJsonObject().get("source").getAsString();
        String prefix = "data:image/png;base64,";
        assertTrue(embedded.startsWith(prefix));
        assertArrayEquals(Files.readAllBytes(Path.of("src/main/resources/assets/etched/textures/block/radio.png")),
                Base64.getDecoder().decode(embedded.substring(prefix.length())));
    }

    @Test
    void productionVariantsPreserveEveryAuthoredCoordinateAndRetainedUV() throws IOException {
        var preview = json(PREVIEW);
        for (String suffix : new String[]{"", "_225", "_45", "_675"}) {
            var expected = preview.deepCopy();
            double angle = switch (suffix) {
                case "_225" -> 22.5;
                case "_45" -> 45;
                case "_675" -> -22.5;
                default -> 0;
            };
            if (angle != 0) {
                for (var value : expected.getAsJsonArray("elements")) {
                    var transform = new JsonObject();
                    transform.add("origin", JsonParser.parseString("[8,0,8]"));
                    transform.addProperty("axis", "y");
                    transform.addProperty("angle", angle);
                    transform.addProperty("rescale", false);
                    value.getAsJsonObject().add("rotation", transform);
                }
            }
            assertEquals(expected, json(Path.of("src/main/resources/assets/etched/models/block/radio" + suffix + ".json")));
        }
    }

    @Test
    void geometryProvenancePinsTheUnmodifiedEditableSourceAndExport() throws Exception {
        var provenance = json(Path.of("assets-src/radio/geometry.json"));
        assertEquals("GPL-3.0-only", provenance.get("license").getAsString());
        assertEquals("Re-Etched maintainer", provenance.get("author").getAsString());
        assertEquals("assets-src/radio/manual/radio.bbmodel", provenance.get("editable_source").getAsString());
        Set<String> paths = new HashSet<>();
        for (var value : provenance.getAsJsonArray("files")) {
            var record = value.getAsJsonObject();
            String path = record.get("path").getAsString();
            assertTrue(paths.add(path));
            assertEquals(record.get("sha256").getAsString(), HexFormat.of().formatHex(
                    MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(Path.of(path)))));
        }
        assertEquals(Set.of(SOURCE.toString(), "assets-src/radio/manual/radio.bbmodel"), paths);
    }

    @Test
    void selectionEnvelopesFollowAllAuthoredRotationBounds() throws Exception {
        MinecraftTestBootstrap.bootStrap();
        // Plain JUnit cannot construct new blocks after Forge's vanilla registry bootstrap.
        // Inspect the internal cached shapes here; transformed client/GameTests exercise the hooks.
        var field = RadioBlock.class.getDeclaredField("SHAPES");
        field.setAccessible(true);
        var shapes = (VoxelShape[]) field.get(null);
        assertEquals(16, shapes.length);
        var elements = json(SOURCE).getAsJsonArray("elements");
        for (int rotation = 0; rotation < 16; rotation++) {
            double[] min = {Double.POSITIVE_INFINITY, Double.POSITIVE_INFINITY, Double.POSITIVE_INFINITY};
            double[] max = {Double.NEGATIVE_INFINITY, Double.NEGATIVE_INFINITY, Double.NEGATIVE_INFINITY};
            double angle = Math.toRadians(180 + rotation * 22.5);
            for (var value : elements) {
                var element = value.getAsJsonObject();
                var from = element.getAsJsonArray("from");
                var to = element.getAsJsonArray("to");
                for (int vertex = 0; vertex < 8; vertex++) {
                    double x = ((vertex & 1) == 0 ? from : to).get(0).getAsDouble() - 8;
                    double y = ((vertex & 2) == 0 ? from : to).get(1).getAsDouble();
                    double z = ((vertex & 4) == 0 ? from : to).get(2).getAsDouble() - 8;
                    double[] point = {(8 + x * Math.cos(angle) - z * Math.sin(angle)) / 16, y / 16,
                            (8 + x * Math.sin(angle) + z * Math.cos(angle)) / 16};
                    for (int axis = 0; axis < 3; axis++) {
                        min[axis] = Math.min(min[axis], point[axis]);
                        max[axis] = Math.max(max[axis], point[axis]);
                    }
                }
            }
            var bounds = shapes[rotation].bounds();
            double[] actual = {bounds.minX, bounds.minY, bounds.minZ, bounds.maxX, bounds.maxY, bounds.maxZ};
            for (int axis = 0; axis < 3; axis++) {
                assertEquals(min[axis], actual[axis], 0.000001);
                assertEquals(max[axis], actual[axis + 3], 0.000001);
                assertTrue(actual[axis] >= 0 && actual[axis + 3] <= 1);
            }
        }
    }

    private static boolean covered(JsonObject element, String direction, JsonObject model) {
        int axis = switch (direction) {
            case "west", "east" -> 0;
            case "down", "up" -> 1;
            case "north", "south" -> 2;
            default -> throw new AssertionError("Unknown face " + direction);
        };
        boolean negative = Set.of("west", "down", "north").contains(direction);
        double plane = element.getAsJsonArray(negative ? "from" : "to").get(axis).getAsDouble();
        for (var value : model.getAsJsonArray("elements")) {
            var other = value.getAsJsonObject();
            if (other == element) {
                continue;
            }
            double from = other.getAsJsonArray("from").get(axis).getAsDouble();
            double to = other.getAsJsonArray("to").get(axis).getAsDouble();
            boolean covers = negative ? from < plane && plane <= to : from <= plane && plane < to;
            for (int a = 0; a < 3; a++) {
                if (a != axis) {
                    covers &= other.getAsJsonArray("from").get(a).getAsDouble()
                            <= element.getAsJsonArray("from").get(a).getAsDouble()
                            && other.getAsJsonArray("to").get(a).getAsDouble()
                            >= element.getAsJsonArray("to").get(a).getAsDouble();
                }
            }
            if (covers) {
                return true;
            }
        }
        return false;
    }

    private static JsonObject json(Path path) throws IOException {
        return JsonParser.parseString(Files.readString(path)).getAsJsonObject();
    }
}
