package com.ballknowers.draftsim.api;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import org.springframework.http.converter.json.Jackson2ObjectMapperBuilder;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * The JSON oracle for spec 021's "no behavior change" refactors
 * (contracts/C2-response-equivalence.md, as amended after review).
 *
 * <p>The comparison is {@link JsonNode#equals}. That ignores key order inside objects
 * but is strict about everything else: {@code 1} is not {@code 1.0}, and a key whose
 * value is null is not the same as a missing key. Those are exactly the two ways a
 * map-to-record conversion goes wrong without anyone noticing.
 *
 * <p>Key order is normally invisible, but not for dynamic-key maps that the UI
 * iterates without sorting. {@code positionalTilt} is rendered through
 * {@code Object.entries} in ManagerHistory.tsx, for example. Pass those locations as
 * {@code orderSensitivePaths}: JSON-pointer-like paths in which {@code *} matches every
 * array element or object field, e.g. {@code /rows/* /positionalTilt} (without the
 * space). Field order is then also asserted at each matching node.
 *
 * <p>The mapper comes from {@link Jackson2ObjectMapperBuilder}, which registers the
 * same well-known modules (JDK 8, java.time) that Spring Boot's mapper does. A bare
 * {@code new ObjectMapper()} does not, and the review found the existing seam tests
 * using it.
 *
 * <p>Golden files live under {@code src/test/resources/golden/}. Running with
 * {@code -Dgolden.write=true} (or {@code GOLDEN_WRITE=true} in the environment) writes them instead of asserting. That is only for the
 * characterization commit. A conversion commit that rewrites a golden file has
 * changed the oracle and proves nothing.
 */
public final class GoldenJson {

    public static final ObjectMapper MAPPER = Jackson2ObjectMapperBuilder.json().build()
            .enable(SerializationFeature.INDENT_OUTPUT);

    private static final Path ROOT = Path.of("src", "test", "resources", "golden");

    private GoldenJson() {}

    /** Serialize {@code body} and compare it with {@code golden/<resourcePath>.json}. */
    public static void assertMatchesGolden(Object body, String resourcePath, String... orderSensitivePaths) {
        JsonNode actual = MAPPER.valueToTree(body);
        assertTreeMatchesGolden(actual, resourcePath, orderSensitivePaths);
    }

    /**
     * A response body as the client receives it: serialized to JSON and read back as a
     * plain map. Tests that used to cast {@code getBody()} to {@code Map<String,Object>}
     * call this instead. It gives the same map whether the controller built a map or a
     * response record, so converting a controller to records never breaks them
     * (plan-review finding 3). Numbers come back as JSON reads them: a small long id
     * becomes an Integer.
     */
    @SuppressWarnings("unchecked")
    public static java.util.Map<String, Object> wire(Object body) {
        return MAPPER.convertValue(body, java.util.Map.class);
    }

    /** As {@link #assertMatchesGolden} but for a raw JSON string, such as a MockMvc response body. */
    public static void assertJsonMatchesGolden(String json, String resourcePath, String... orderSensitivePaths) {
        try {
            assertTreeMatchesGolden(MAPPER.readTree(json), resourcePath, orderSensitivePaths);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    static void assertTreeMatchesGolden(JsonNode actual, String resourcePath, String... orderSensitivePaths) {
        Path file = ROOT.resolve(resourcePath + ".json");
        try {
            if (writeMode()) {
                Files.createDirectories(file.getParent());
                Files.writeString(file, MAPPER.writeValueAsString(actual) + "\n");
                return;
            }
            if (!Files.exists(file)) {
                fail("No golden file at " + file.toAbsolutePath()
                        + "; run once with -Dgolden.write=true in the characterization commit");
            }
            JsonNode expected = MAPPER.readTree(Files.readString(file));
            assertTreesMatch(expected, onTheWire(actual), orderSensitivePaths);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /**
     * The tree a client would parse: written to JSON text and read back. A tree from
     * {@code valueToTree} keeps Java's numeric types, so a {@code long} id is a
     * {@code LongNode}, but the golden file is parsed text, where the same {@code 11}
     * is an {@code IntNode}, and {@link JsonNode#equals} calls those different. The
     * wire can't tell a long from an int, so neither should this. It still tells
     * {@code 1} from {@code 1.0}, because that difference is on the wire. Found when the
     * live-state-frame case failed against the very code its golden was written from.
     */
    static JsonNode onTheWire(JsonNode tree) throws IOException {
        return MAPPER.readTree(MAPPER.writeValueAsString(tree));
    }

    /**
     * {@code -Dgolden.write=true}, or the environment variable {@code GOLDEN_WRITE=true}.
     * Gradle forks the test JVM without forwarding {@code -D} flags, but the fork does
     * inherit the environment.
     */
    private static boolean writeMode() {
        return Boolean.getBoolean("golden.write") || "true".equalsIgnoreCase(System.getenv("GOLDEN_WRITE"));
    }

    /** The comparison itself, separated from file I/O so {@code GoldenJsonTest} can exercise it. */
    static void assertTreesMatch(JsonNode expected, JsonNode actual, String... orderSensitivePaths) {
        if (!expected.equals(actual)) {
            try {
                // assertEquals on the pretty strings gives a readable diff in the IDE and the report.
                assertEquals(MAPPER.writeValueAsString(expected), MAPPER.writeValueAsString(actual),
                        "JSON differs from golden (key order ignored; types and null-vs-absent are not)");
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
            fail("JSON differs from golden, but only in a way the string diff hides (e.g. 1 vs 1.0)");
        }
        for (String path : orderSensitivePaths) {
            List<JsonNode> exp = select(expected, path);
            List<JsonNode> act = select(actual, path);
            assertEquals(exp.size(), act.size(), "order-sensitive path " + path + " matched a different number of nodes");
            for (int i = 0; i < exp.size(); i++) {
                assertEquals(fieldNames(exp.get(i)), fieldNames(act.get(i)),
                        "key order differs at " + path + " (match " + i + ")");
            }
        }
    }

    private static List<String> fieldNames(JsonNode n) {
        List<String> names = new ArrayList<>();
        n.fieldNames().forEachRemaining(names::add);
        return names;
    }

    /** Resolve a JSON-pointer-like path with {@code *} wildcards. Missing segments match nothing. */
    static List<JsonNode> select(JsonNode root, String path) {
        List<JsonNode> current = List.of(root);
        for (String seg : path.split("/")) {
            if (seg.isEmpty()) continue;
            List<JsonNode> next = new ArrayList<>();
            for (JsonNode n : current) {
                if (seg.equals("*")) {
                    n.elements().forEachRemaining(next::add);
                } else if (n.isArray() && seg.chars().allMatch(Character::isDigit)) {
                    JsonNode c = n.get(Integer.parseInt(seg));
                    if (c != null) next.add(c);
                } else {
                    JsonNode c = n.get(seg);
                    if (c != null && !c.isNull()) next.add(c);
                }
            }
            current = next;
        }
        return current;
    }
}
