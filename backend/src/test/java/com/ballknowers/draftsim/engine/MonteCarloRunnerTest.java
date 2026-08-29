package com.ballknowers.draftsim.engine;

import com.ballknowers.draftsim.config.ScoringProperties;
import com.ballknowers.draftsim.domain.*;
import com.ballknowers.draftsim.profile.ManagerProfile;
import com.ballknowers.draftsim.profile.PositionalPriors;
import com.ballknowers.draftsim.sport.FootballRules;
import org.junit.jupiter.api.Test;

import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Aggregation-level invariants for {@link MonteCarloRunner}, run against a
 * live simulation rather than asserted from inspection. HANDOFF flagged this
 * as the highest-risk untested piece of the aggregation: availability rows
 * only cover the top 75 available at each pick, and nothing pinned down that
 * survival probability actually falls as you approach a player's own ADP.
 */
class MonteCarloRunnerTest {

    private static final List<String> SLOTS = List.of(
            "QB", "RB", "RB", "WR", "WR", "TE", "FLEX", "FLEX", "K", "DEF",
            "BN", "BN", "BN", "BN", "BN");

    private static final ScoringProperties.Sport CFG = new ScoringProperties.Sport(
            new ScoringProperties.Weights(1.0, 0.35, 0.5, 0.25),
            12.0, 3.0, 60.0, 0.15, 6, 0.85,
            Map.of("K", 13, "DEF", 12), 1.0, 30);

    private static List<BoardEntry> board(int n) {
        Position[] cycle = {
                Position.RB, Position.WR, Position.WR, Position.RB, Position.TE,
                Position.WR, Position.RB, Position.QB, Position.WR, Position.RB};
        List<BoardEntry> out = new ArrayList<>(n);
        for (int i = 0; i < n; i++) {
            Position pos = (i > n - 40) ? (i % 2 == 0 ? Position.K : Position.DEF) : cycle[i % cycle.length];
            out.add(new BoardEntry(new Player(i + 1L, Sport.NFL, "s" + i, "Player " + i,
                    List.of(pos), "FA", "Active", null, null, null), i + 1.0, i / 6 + 1));
        }
        return out;
    }

    private static DraftContext ctx(int teams, int rounds) {
        LeagueSettings settings = new LeagueSettings(teams, rounds, SLOTS, 1.0);
        Map<Integer, ManagerProfile> profiles = new HashMap<>();
        for (int s = 1; s <= teams; s++) profiles.put(s, ManagerProfile.neutral(s, "seat " + s));
        return new DraftContext(
                board(400), settings, profiles, PositionalPriors.uniform(),
                new FootballRules(new ScoringProperties(CFG)), CFG,
                List.of(), Map.of());
    }

    private static final SimulationResult.Confidence CONFIDENCE = new SimulationResult.Confidence(
            0, 0, 0, 0, 14, 14, "test", List.of());

    @Test
    void survivalProbabilityDecreasesMonotonicallyAcrossMyPicks() {
        DraftContext c = ctx(14, 15);
        SimulationResult result = new MonteCarloRunner()
                .run(c, 11, 300, 1.0, 1L, CONFIDENCE, null);

        int[] myPicks = DraftSlot.picksForSlot(11, 14, 15);
        int violations = 0;
        for (SimulationResult.AvailabilityRow row : result.availability()) {
            Double previous = null;
            for (int pick : myPicks) {
                Double p = row.survivalByPick().get(pick);
                if (p == null) continue;
                if (previous != null) {
                    assertTrue(p <= previous + 1e-9,
                            row.player().name() + ": survival rose from " + previous
                                    + " to " + p + " between successive picks");
                }
                previous = p;
            }
        }
    }

    @Test
    void aggregationCoversEveryRequestedPick() {
        DraftContext c = ctx(14, 15);
        SimulationResult result = new MonteCarloRunner()
                .run(c, 11, 100, 1.0, 2L, CONFIDENCE, null);

        int[] myPicks = DraftSlot.picksForSlot(11, 14, 15);
        assertEquals(myPicks.length, result.bestAvailable().size());
        for (int pick : myPicks) {
            assertTrue(result.bestAvailable().containsKey(pick), "no bestAvailable entry for pick " + pick);
            assertFalse(result.bestAvailable().get(pick).isEmpty(), "empty bestAvailable at pick " + pick);
        }
    }
}
