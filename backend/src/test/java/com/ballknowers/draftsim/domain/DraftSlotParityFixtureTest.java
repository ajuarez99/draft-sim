package com.ballknowers.draftsim.domain;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Spec 063 T020 (backend half). Freezes {@link DraftSlot#slot(int, int, int)} for every pick of a 12-team,
 * 15-round draft at reversal round 0 (plain snake) and 3, into {@code web/src/__fixtures__/draft-slot-parity.json}.
 * The web's {@code slotForPick} replays the same picks and must produce the same slot: the pick rail shows upcoming
 * picks without the server, and this is what keeps its snake arithmetic from drifting from the one real rule.
 *
 * <p>Same pattern as {@link com.ballknowers.draftsim.sport.NbaLineupParityFixtureTest}: regenerate deliberately with
 * {@code gradlew test --tests *DraftSlotParityFixtureTest* -DregenFixture=true}, never to make a red run green.
 * Compared as parsed JSON, not bytes (core.autocrlf=true checks the file out with CRLF).
 */
class DraftSlotParityFixtureTest {

    private static final int TEAMS = 12, ROUNDS = 15;
    private static final int[] REVERSALS = {0, 3};

    private static Map<String, Object> generate() {
        List<Object> cases = new ArrayList<>();
        for (int reversal : REVERSALS) {
            List<Integer> slots = new ArrayList<>();
            for (int pickNo = 1; pickNo <= TEAMS * ROUNDS; pickNo++) {
                slots.add(DraftSlot.slot(pickNo, TEAMS, reversal));   // slots[i] is the slot of pick i + 1
            }
            Map<String, Object> c = new LinkedHashMap<>();
            c.put("reversalRound", reversal);
            c.put("slots", slots);
            cases.add(c);
        }
        Map<String, Object> root = new LinkedHashMap<>();
        root.put("teams", TEAMS);
        root.put("rounds", ROUNDS);
        root.put("cases", cases);
        return root;
    }

    private static Path fixturePath() {
        // gradle runs tests from backend/; tolerate the repo root too.
        Path fromBackend = Path.of("..", "web", "src", "__fixtures__", "draft-slot-parity.json");
        Path fromRoot = Path.of("web", "src", "__fixtures__", "draft-slot-parity.json");
        return Files.isDirectory(Path.of("backend")) ? fromRoot : fromBackend;
    }

    @Test
    void committedParityFixtureMatchesDraftSlot() throws IOException {
        ObjectMapper mapper = new ObjectMapper().enable(SerializationFeature.INDENT_OUTPUT);
        String generated = mapper.writeValueAsString(generate());
        Path path = fixturePath();

        if ("true".equals(System.getProperty("regenFixture"))) {
            Files.createDirectories(path.getParent());
            Files.writeString(path, generated + "\n", StandardCharsets.UTF_8);
            return;
        }

        assertTrue(Files.exists(path), "draft-slot-parity.json is missing: run gradlew test "
                + "--tests *DraftSlotParityFixtureTest* -DregenFixture=true");
        JsonNode committed = mapper.readTree(Files.readString(path, StandardCharsets.UTF_8));
        JsonNode fresh = mapper.readTree(generated);
        assertEquals(fresh, committed, "draft-slot-parity.json is stale: run gradlew test "
                + "--tests *DraftSlotParityFixtureTest* -DregenFixture=true");
    }

    @Test
    void theFixtureCoversEveryPickAndBothReversalRoundsAndTheyDiffer() {
        Map<String, Object> g = generate();
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> cases = (List<Map<String, Object>>) g.get("cases");
        assertEquals(2, cases.size());
        @SuppressWarnings("unchecked")
        List<Integer> plain = (List<Integer>) cases.get(0).get("slots");
        @SuppressWarnings("unchecked")
        List<Integer> flipped = (List<Integer>) cases.get(1).get("slots");
        assertEquals(TEAMS * ROUNDS, plain.size());
        assertEquals(TEAMS * ROUNDS, flipped.size());
        assertEquals(1, plain.get(0), "pick 1 is slot 1");
        assertEquals(12, plain.get(11));
        assertEquals(12, plain.get(12), "plain snake: round 2 starts at slot 12");
        // reversal 3: rounds 1-2 are plain, round 3 runs high to low where plain snake would run low to high
        assertEquals(plain.subList(0, 24), flipped.subList(0, 24));
        assertEquals(1, plain.get(24));
        assertEquals(12, flipped.get(24));
    }
}
