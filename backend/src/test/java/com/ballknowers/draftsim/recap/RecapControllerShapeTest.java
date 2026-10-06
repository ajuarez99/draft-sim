package com.ballknowers.draftsim.recap;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

/** The response always carries every key, null where it does not apply, and never failureDetail. */
class RecapControllerShapeTest {

    private static final List<String> KEYS = List.of("state", "season", "week", "model", "generatedAt", "revision",
            "revisionReason", "stale", "headline", "sections", "failureReason");

    @Test
    void everyStateCarriesEveryKeyInOrder() {
        for (RecapView.State s : RecapView.State.values()) {
            var body = RecapController.body(RecapView.bare(s, null, 3));
            assertEquals(KEYS, List.copyOf(body.keySet()), s.name());
            assertFalse(body.containsKey("failureDetail"));
        }
    }
}
