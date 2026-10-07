package com.ballknowers.draftsim.api;

import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * Proves the oracle catches every failure mode spec 021's plan review named
 * (plan-review.md findings 1, 3, 6 and 7). If one of these assertions ever passed
 * when it should fail, every characterization test built on {@link GoldenJson}
 * would pass vacuously.
 */
class GoldenJsonTest {

    private static JsonNode json(String s) throws IOException {
        return GoldenJson.MAPPER.readTree(s);
    }

    @Test
    void reorderedObjectKeysStillMatch() throws IOException {
        assertDoesNotThrow(() -> GoldenJson.assertTreesMatch(
                json("{\"a\":1,\"b\":[1,2],\"c\":null}"),
                json("{\"c\":null,\"b\":[1,2],\"a\":1}")));
    }

    @Test
    void integerVersusDoubleFails() throws IOException {
        JsonNode exp = json("{\"a\":1}");
        JsonNode act = json("{\"a\":1.0}");
        assertThrows(AssertionError.class, () -> GoldenJson.assertTreesMatch(exp, act));
    }

    @Test
    void nullVersusAbsentFails() throws IOException {
        JsonNode exp = json("{\"a\":1,\"makesPlayoffsPct\":null}");
        JsonNode act = json("{\"a\":1}");
        assertThrows(AssertionError.class, () -> GoldenJson.assertTreesMatch(exp, act));
    }

    @Test
    void extraKeyFromARecordHelperMethodFails() throws IOException {
        record Row(String label) {
            public boolean isEmpty() { return label.isEmpty(); } // leaks "empty" into JSON
        }
        JsonNode exp = json("{\"label\":\"x\"}");
        JsonNode act = GoldenJson.MAPPER.valueToTree(new Row("x"));
        assertThrows(AssertionError.class, () -> GoldenJson.assertTreesMatch(exp, act));
    }

    @Test
    void reorderInsideAnOrderSensitivePathFails() throws IOException {
        JsonNode exp = json("{\"rows\":[{\"positionalTilt\":{\"QB\":1.0,\"RB\":2.0}}]}");
        JsonNode act = json("{\"rows\":[{\"positionalTilt\":{\"RB\":2.0,\"QB\":1.0}}]}");
        // Equal as unordered objects...
        assertDoesNotThrow(() -> GoldenJson.assertTreesMatch(exp, act));
        // ...but not once the dynamic-key map is declared order-sensitive.
        assertThrows(AssertionError.class,
                () -> GoldenJson.assertTreesMatch(exp, act, "/rows/*/positionalTilt"));
    }

    @Test
    void linkedHashMapAndEquivalentRecordSerializeIdentically() {
        record Entry(Integer rosterId, Double makesPlayoffsPct, boolean isMe) {}
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("rosterId", 3);
        m.put("makesPlayoffsPct", null);
        m.put("isMe", true);
        assertDoesNotThrow(() -> GoldenJson.assertTreesMatch(
                GoldenJson.MAPPER.valueToTree(m),
                GoldenJson.MAPPER.valueToTree(new Entry(3, null, true)),
                "/"));
    }
}
