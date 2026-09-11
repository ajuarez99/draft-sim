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

    private static final ScoringProperties.SportScoring CFG = new ScoringProperties.SportScoring(
            new ScoringProperties.Weights(1.0, 0.35, 0.5, 0.25),
            12.0, 3.0, 60.0, 0.15, 6, 0.85,
            Map.of("K", 3, "DEF", 4), 1.0, 30);

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

    private static Map<Integer, ManagerProfile> profilesFor(int teams) {
        Map<Integer, ManagerProfile> profiles = new HashMap<>();
        for (int s = 1; s <= teams; s++) profiles.put(s, ManagerProfile.neutral(s, "seat " + s));
        return profiles;
    }

    private static DraftContext ctx(int teams, int rounds) {
        LeagueSettings settings = new LeagueSettings(Sport.NFL, teams, rounds, SLOTS, 1.0);
        return new DraftContext(
                board(400), settings, profilesFor(teams), PositionalPriors.uniform(Sport.NFL),
                new FootballRules(new ScoringProperties(CFG, null)), CFG,
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

    /**
     * multi-sport-and-rebrand.md's acceptance criterion 1 ("capture a baseline,
     * re-run and diff, no number moves") only works if the same seed is truly
     * deterministic end to end. Board building is seeded per-iteration off the
     * base seed passed in here, so pinning that base seed must pin every
     * downstream random draw -- this is the guarantee SimulationService's new
     * optional seed field exists to expose.
     */
    @Test
    void sameSeedReproducesIdenticalResult() {
        DraftContext c1 = ctx(14, 15);
        DraftContext c2 = ctx(14, 15);
        SimulationResult first = new MonteCarloRunner()
                .run(c1, 11, 100, 1.0, 42L, CONFIDENCE, null);
        SimulationResult second = new MonteCarloRunner()
                .run(c2, 11, 100, 1.0, 42L, CONFIDENCE, null);

        assertEquals(first, second, "identical seed and inputs must produce a byte-for-byte identical result");
    }

    /**
     * A locked (startState) pick is a real, already-decided fact -- but it's
     * only removed from `available` WITHIN each iteration, at the point that
     * iteration's own sequential loop reaches it. In whichever minority of
     * iterations an earlier, unlocked slot independently drafts the same
     * player first, DraftSimulator's own duplicate guard then skips him at his
     * real locked pick for THAT iteration -- so if he's common enough in that
     * minority to also win the earlier slot's modal vote, the aggregate board
     * used to show him twice. MonteCarloRunner.aggregate() now scrubs every
     * locked player out of every other slot's tally before picking a winner.
     */
    @Test
    void lockedPickNeverAlsoAppearsAsAnEarlierPrediction() {
        int teams = 12, rounds = 14;
        // Board's own #1 overall (lowest adp) -- the player every iteration
        // would draft almost immediately if he weren't locked away, so this
        // maximizes the chance an early slot's modal vote lands on him too.
        long topPlayerId = 1L;
        int lockedAt = 20; // round 2, nowhere near where he'd naturally go
        DraftContext locked = new DraftContext(
                board(400), new LeagueSettings(Sport.NFL, teams, rounds, SLOTS, 1.0),
                profilesFor(teams), PositionalPriors.uniform(Sport.NFL),
                new FootballRules(new ScoringProperties(CFG, null)), CFG,
                List.of(lockedAt), Map.of(lockedAt, topPlayerId));

        SimulationResult result = new MonteCarloRunner()
                .run(locked, 11, 400, 1.0, 7L, CONFIDENCE, null);

        List<Integer> picksNamingHim = result.board().stream()
                .filter(p -> p.player() != null && p.player().id() == topPlayerId)
                .map(SimulationResult.PredictedPick::pickNo)
                .toList();
        assertEquals(List.of(lockedAt), picksNamingHim,
                "a locked player must appear on the board at his real pick only, not also as an earlier prediction");
    }

    @Test
    void differentSeedsProduceDifferentResults() {
        DraftContext c1 = ctx(14, 15);
        DraftContext c2 = ctx(14, 15);
        SimulationResult first = new MonteCarloRunner()
                .run(c1, 11, 100, 1.0, 42L, CONFIDENCE, null);
        SimulationResult second = new MonteCarloRunner()
                .run(c2, 11, 100, 1.0, 43L, CONFIDENCE, null);

        assertNotEquals(first, second, "different seeds should not coincidentally produce the same result");
    }
}
