package com.ballknowers.draftsim.engine;

import com.ballknowers.draftsim.engine.PlayoffOddsSimulator.Fixture;
import com.ballknowers.draftsim.engine.PlayoffOddsSimulator.Odds;
import com.ballknowers.draftsim.engine.PlayoffOddsSimulator.TeamState;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The distributions US4 surfaces are the ones the simulator was already
 * computing and discarding (specs/004-ffwrapped-feature-parity, research R8).
 * These pin that they agree with the summary numbers alongside them -- if the
 * seed distribution and {@code madePct} can disagree, then the forecast view
 * and the Record cell can show different answers for the same league, which is
 * exactly what FR-008 forbids.
 */
class SeasonForecastDistributionTest {

    private static final int ITERATIONS = 2_000;

    /** Six teams, level on record, differing only in scoring strength. */
    private static List<TeamState> sixTeams() {
        List<TeamState> teams = new ArrayList<>();
        for (int i = 0; i < 6; i++) {
            teams.add(new TeamState(i + 1, 0, 0, 0, 110 + i * 6, 20));
        }
        return teams;
    }

    /** Three weeks of round-robin-ish pairings. */
    private static List<Fixture> threeWeeks() {
        List<Fixture> f = new ArrayList<>();
        int[][][] weeks = {
                {{1, 2}, {3, 4}, {5, 6}},
                {{1, 3}, {2, 5}, {4, 6}},
                {{1, 4}, {2, 6}, {3, 5}},
        };
        for (int w = 0; w < weeks.length; w++) {
            int matchup = 1;
            for (int[] pair : weeks[w]) {
                f.add(new Fixture(w + 1, pair[0], matchup));
                f.add(new Fixture(w + 1, pair[1], matchup));
                matchup++;
            }
        }
        return f;
    }

    private static List<Odds> run() {
        return PlayoffOddsSimulator.run(sixTeams(), threeWeeks(), 3, false, ITERATIONS, 20260918L);
    }

    /** Every simulated season assigns every team exactly one seed. */
    @Test
    void eachTeamsSeedCountsSumToTheIterationCount() {
        for (Odds o : run()) {
            int total = 0;
            for (int c : o.seedCounts()) total += c;
            assertEquals(ITERATIONS, total,
                    "roster " + o.rosterId() + " must land on exactly one seed per simulated season");
        }
    }

    /** And exactly one win total. */
    @Test
    void eachTeamsWinCountsSumToTheIterationCount() {
        for (Odds o : run()) {
            int total = 0;
            for (int c : o.winCounts()) total += c;
            assertEquals(ITERATIONS, total, "roster " + o.rosterId());
        }
    }

    /** Across the league, each seed is handed out exactly once per season. */
    @Test
    void everySeedIsClaimedOncePerSimulatedSeason() {
        List<Odds> all = run();
        int n = all.size();
        for (int seed = 0; seed < n; seed++) {
            int claims = 0;
            for (Odds o : all) claims += o.seedCounts().get(seed);
            assertEquals(ITERATIONS, claims, "seed " + (seed + 1) + " must belong to someone every season");
        }
    }

    /**
     * FR-008 / SC-005. The published playoff-odds figure must be reproducible
     * from the seed distribution: making the top three seeds IS making the
     * playoffs in a six-team, three-spot league. If these can drift, the
     * forecast view and the Record cell can disagree.
     */
    @Test
    void theSeedDistributionReproducesThePublishedPlayoffOdds() {
        for (Odds o : run()) {
            int topThree = o.seedCounts().get(0) + o.seedCounts().get(1) + o.seedCounts().get(2);
            double fromSeeds = topThree * 100.0 / ITERATIONS;
            assertEquals(o.madePct(), fromSeeds, 0.01,
                    "roster " + o.rosterId() + ": madePct and the seed distribution must be the same fact");
        }
    }

    /** The seed-one share is likewise the same fact as the first seed bucket. */
    @Test
    void theFirstSeedBucketReproducesSeedOnePct() {
        for (Odds o : run()) {
            assertEquals(o.seedOnePct(), o.seedCounts().get(0) * 100.0 / ITERATIONS, 0.01,
                    "roster " + o.rosterId());
        }
    }

    /** The win histogram's mean is the projected-wins number reported beside it. */
    @Test
    void theWinHistogramMeanMatchesProjectedWins() {
        for (Odds o : run()) {
            long weighted = 0, total = 0;
            for (int w = 0; w < o.winCounts().size(); w++) {
                weighted += (long) w * o.winCounts().get(w);
                total += o.winCounts().get(w);
            }
            double mean = (double) weighted / total;
            // Rounding into whole-win buckets costs up to half a win of
            // resolution, which is why the range is reported in whole wins.
            assertEquals(o.projWins(), mean, 0.5, "roster " + o.rosterId());
        }
    }

    /** The better-scoring team must not have the worse average seed. */
    @Test
    void strongerTeamsGetBetterAverageSeeds() {
        Map<Integer, Odds> byRoster = new java.util.HashMap<>();
        for (Odds o : run()) byRoster.put(o.rosterId(), o);
        double weakest = averageSeed(byRoster.get(1));
        double strongest = averageSeed(byRoster.get(6));
        assertTrue(strongest < weakest,
                "roster 6 scores most and should seed better: " + strongest + " vs " + weakest);
    }

    private static double averageSeed(Odds o) {
        double sum = 0;
        int total = 0;
        for (int i = 0; i < o.seedCounts().size(); i++) {
            sum += (i + 1.0) * o.seedCounts().get(i);
            total += o.seedCounts().get(i);
        }
        return sum / total;
    }

    // ---- the derivations PlayoffOddsService does over a stored snapshot ----

    @Test
    void percentilesWalkTheCumulativeCounts() {
        // 10 seasons: 1 at 4 wins, 8 at 5, 1 at 6.
        Map<Integer, Integer> counts = Map.of(4, 1, 5, 8, 6, 1);
        assertEquals(4, PlayoffOddsService.percentile(counts, 0.10));
        assertEquals(5, PlayoffOddsService.percentile(counts, 0.50));
        assertEquals(5, PlayoffOddsService.percentile(counts, 0.90));
    }

    /**
     * A snapshot taken before V17 has no distribution. That is not a range of
     * zero, and every derivation must say so rather than invent one.
     */
    @Test
    void anAbsentDistributionHasNoRangeRatherThanAZeroRange() {
        assertNull(PlayoffOddsService.percentile(Map.of(), 0.10));
        assertNull(PlayoffOddsService.averageKey(Map.of()));
        assertTrue(PlayoffOddsService.share(Map.of()).isEmpty());
        assertTrue(PlayoffOddsService.parseCounts(null).isEmpty());
        assertTrue(PlayoffOddsService.parseCounts("").isEmpty());
    }

    @Test
    void countsRoundTripThroughJson() {
        String json = PlayoffOddsService.countsJson(List.of(0, 5, 3, 0), 1);
        Map<Integer, Integer> back = PlayoffOddsService.parseCounts(json);
        // Zero buckets are dropped; a missing key reads as zero, which it is.
        assertEquals(Map.of(2, 5, 3, 3), back);
        // (2*5 + 3*3) / 8 = 2.375, reported to two decimals like every other
        // figure on this page.
        assertEquals(2.38, PlayoffOddsService.averageKey(back), 1e-9);
    }

    @Test
    void sharesAreFractionsOfTheWhole() {
        Map<Integer, Double> share = PlayoffOddsService.share(Map.of(1, 250, 2, 750));
        assertEquals(0.25, share.get(1), 1e-9);
        assertEquals(0.75, share.get(2), 1e-9);
    }
}
