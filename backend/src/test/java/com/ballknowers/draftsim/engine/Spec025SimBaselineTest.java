package com.ballknowers.draftsim.engine;

import com.ballknowers.draftsim.config.ScoringProperties;
import com.ballknowers.draftsim.domain.*;
import com.ballknowers.draftsim.profile.ManagerProfile;
import com.ballknowers.draftsim.profile.PositionalPriors;
import com.ballknowers.draftsim.sport.BasketballRules;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Spec 025 SC-004: the multi-position-eligibility work must not change
 * simulation output. This freezes the full 168-pick sequence of a fixed NBA
 * mock draft (12 teams, 14 rounds, NBA roster, synthetic multi-position board,
 * user at slot 5 always taking the best-ADP player left, fixed rng seed)
 * captured BEFORE any spec-025 code. Regenerate deliberately with
 * {@code -DregenBaseline=true}; never to make a red run green.
 */
class Spec025SimBaselineTest {

    private static final String RESOURCE = "spec025-sc004-baseline.json";
    private static final int TEAMS = 12, ROUNDS = 14, USER_SLOT = 5;
    private static final long RNG_SEED = 20260910L;

    private static final ScoringProperties.SportScoring NBA_CFG = new ScoringProperties.SportScoring(
            new ScoringProperties.Weights(1.0, 0.35, 0.5, 0.25),
            12.0, 3.0, 60.0, 0.15, 6, 0.85,
            Map.of(), 1.0, 30);

    private final MockDraftEngine engine = new MockDraftEngine();

    /** 240 players, hardcoded cycle of multi-position lists, monotone ADP, ids from 10_000. */
    private static List<BoardEntry> board() {
        List<List<Position>> cycle = List.of(
                List.of(Position.PG),
                List.of(Position.PG, Position.SG),
                List.of(Position.SG, Position.SF),
                List.of(Position.SF, Position.PF),
                List.of(Position.PF, Position.SF, Position.SG),
                List.of(Position.C),
                List.of(Position.C, Position.PF),
                List.of(Position.SG),
                List.of(Position.SF),
                List.of(Position.PF, Position.C),
                List.of(Position.PG, Position.SG, Position.SF),
                List.of(Position.PF));
        List<BoardEntry> out = new ArrayList<>();
        for (int i = 0; i < 240; i++) {
            double adp = 1.0 + i * 0.9 + (i % 3) * 0.1;
            out.add(new BoardEntry(new Player(10_000L + i, Sport.NBA, "nba-s" + i, "Hooper " + i,
                    cycle.get((i * 5 + i / 12) % cycle.size()), "LAL", "Active", null, null, null),
                    adp, i / TEAMS + 1));
        }
        return out;
    }

    private static DraftContext ctx(List<BoardEntry> board, Map<Integer, Long> completed) {
        LeagueSettings settings = new LeagueSettings(Sport.NBA, TEAMS, ROUNDS, LeagueShape.NBA_ROSTER, 0.0, 0);
        Map<Integer, ManagerProfile> profiles = new HashMap<>();
        for (int s = 1; s <= TEAMS; s++) profiles.put(s, ManagerProfile.neutral(s, "seat " + s));
        return new DraftContext(board, settings, profiles, PositionalPriors.uniform(Sport.NBA),
                new BasketballRules(new ScoringProperties(null, NBA_CFG)), NBA_CFG,
                completed.keySet().stream().sorted().toList(), completed);
    }

    /** pickNo -> player id, as a list indexed by pickNo-1. */
    private List<Long> computeSequence() {
        List<BoardEntry> board = board();
        List<SeatSpec> seats = List.of(SeatSpec.user(USER_SLOT, null));
        Map<Integer, Long> completed = new HashMap<>();
        Set<Long> drafted = new HashSet<>();

        MockDraftEngine.AdvanceResult r;
        do {
            r = engine.advanceUntilUserOrEnd(ctx(board, completed), seats, RNG_SEED);
            for (var d : r.newPicks()) {
                completed.put(d.pickNo(), d.player().player().id());
                assertTrue(drafted.add(d.player().player().id()));
            }
            if (!r.complete()) {
                BoardEntry best = board.stream()
                        .filter(e -> !drafted.contains(e.player().id()))
                        .findFirst().orElseThrow();
                completed.put(r.nextPickNo(), best.player().id());
                drafted.add(best.player().id());
            }
        } while (!r.complete());

        assertEquals(TEAMS * ROUNDS, completed.size());
        List<Long> seq = new ArrayList<>();
        for (int p = 1; p <= TEAMS * ROUNDS; p++) seq.add(completed.get(p));
        return seq;
    }

    @Test
    void simulationOutputMatchesTheSpec025Baseline() throws Exception {
        List<Long> actual = computeSequence();
        ObjectMapper mapper = new ObjectMapper();

        if ("true".equals(System.getProperty("regenBaseline"))) {
            Path out = Path.of("src", "test", "resources", RESOURCE);
            Files.createDirectories(out.getParent());
            Files.writeString(out, mapper.writerWithDefaultPrettyPrinter().writeValueAsString(actual) + "\n",
                    StandardCharsets.UTF_8);
            return;
        }

        List<Long> expected;
        try (InputStream in = getClass().getClassLoader().getResourceAsStream(RESOURCE)) {
            assertNotNull(in, "missing baseline resource " + RESOURCE + "; generate with -DregenBaseline=true");
            expected = mapper.readValue(in, mapper.getTypeFactory().constructCollectionType(List.class, Long.class));
        }
        assertEquals(expected, actual,
                "simulation output changed vs the spec-025 baseline; this spec must not change it (SC-004)");
    }
}
