package com.ballknowers.draftsim.sport;

import com.ballknowers.draftsim.config.ScoringProperties;
import com.ballknowers.draftsim.domain.*;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class FootballRulesTest {

    private static final List<String> SLOTS = List.of(
            "QB", "RB", "RB", "WR", "WR", "TE", "FLEX", "FLEX", "K", "DEF",
            "BN", "BN", "BN", "BN", "BN");
    private static final LeagueSettings SETTINGS = new LeagueSettings(14, 15, SLOTS, 1.0);

    private final FootballRules rules = new FootballRules(new ScoringProperties(
            new ScoringProperties.Sport(
                    new ScoringProperties.Weights(1.0, 0.35, 0.5, 0.25),
                    12.0, 3.0, 60.0, 0.15, 6, 0.85,
                    Map.of("K", 3, "DEF", 4), 1.0, 30)));

    private static BoardEntry entry(long id, String name, Position pos, double adp) {
        return new BoardEntry(new Player(id, Sport.NFL, "s" + id, name, List.of(pos),
                "FA", "Active", null, null, null), adp, 1);
    }

    private double need(RosterState roster, BoardEntry candidate) {
        Object lineup = rules.prepareLineup(roster, SETTINGS, rules::value);
        return rules.rosterNeed(candidate, lineup);
    }

    @Test
    void anEmptyRosterNeedsEverythingAtFullValue() {
        RosterState empty = new RosterState();
        assertEquals(1.0, need(empty, entry(1, "RB1", Position.RB, 3)), 1e-6);
    }

    @Test
    void depthBehindFilledStartersScoresLowerThanAnUnfilledSlot() {
        RosterState r = new RosterState();
        r.add(entry(1, "RB1", Position.RB, 3));
        r.add(entry(2, "RB2", Position.RB, 15));
        r.add(entry(3, "RB3", Position.RB, 30));
        r.add(entry(4, "RB4", Position.RB, 40));   // fills RB, RB, FLEX, FLEX

        double fifthRb = need(r, entry(5, "RB5", Position.RB, 90));
        double firstQb = need(r, entry(6, "QB1", Position.QB, 90));
        assertTrue(firstQb > fifthRb,
                "an unfilled QB slot should outrank a fifth RB (" + firstQb + " vs " + fifthRb + ")");
    }

    @Test
    void benchDepthNeverScoresZero() {
        RosterState r = new RosterState();
        for (int i = 0; i < 4; i++) r.add(entry(i, "K" + i, Position.K, 200 + i));
        double n = need(r, entry(99, "K5", Position.K, 250));
        assertTrue(n >= 0.15 - 1e-9, "benchFloor should apply, got " + n);
    }

    @Test
    void kickersAndDefensesAreGatedEarly() {
        assertFalse(rules.isDraftable(entry(1, "K", Position.K, 200), 5, 15));
        assertTrue(rules.isDraftable(entry(1, "K", Position.K, 200), 13, 15));
        assertFalse(rules.isDraftable(entry(2, "DEF", Position.DEF, 190), 11, 15));
        assertTrue(rules.isDraftable(entry(2, "DEF", Position.DEF, 190), 12, 15));
        assertTrue(rules.isDraftable(entry(3, "WR", Position.WR, 1), 1, 15));
    }

    /**
     * The gate is rounds REMAINING, so it lands in the same place relative to
     * the end of the draft whatever the draft's length. The old round-number
     * form would have opened kickers in round 13 of an 18-round league — five
     * rounds early — and never opened them at all in a 12-round one.
     */
    @Test
    void theKickerGateFollowsTheEndOfTheDraftNotAFixedRoundNumber() {
        // 12 rounds: the last three are 10, 11, 12.
        assertFalse(rules.isDraftable(entry(1, "K", Position.K, 200), 9, 12));
        assertTrue(rules.isDraftable(entry(1, "K", Position.K, 200), 10, 12));

        // 18 rounds: the last three are 16, 17, 18 — round 13 is far too early.
        assertFalse(rules.isDraftable(entry(1, "K", Position.K, 200), 13, 18));
        assertTrue(rules.isDraftable(entry(1, "K", Position.K, 200), 16, 18));

        // A draft shorter than the window still lets them in, rather than
        // gating a position out of the draft entirely.
        assertTrue(rules.isDraftable(entry(1, "K", Position.K, 200), 1, 2));
    }

    @Test
    void flexAbsorbsTheThirdRunningBack() {
        RosterState twoRb = new RosterState();
        twoRb.add(entry(1, "RB1", Position.RB, 3));
        twoRb.add(entry(2, "RB2", Position.RB, 15));
        double third = need(twoRb, entry(3, "RB3", Position.RB, 25));
        assertTrue(third > 0.9, "third RB should still be a starter via FLEX, got " + third);
    }

    /**
     * Pins §B2's O(1) prepareLineup()/rosterNeed() against the O(n) full
     * recomputation (startingLineupValue(after) - startingLineupValue(before))
     * it replaced, across a mix of roster shapes: empty slots, full dedicated
     * slots, FLEX overflow, and a displacement (a better player bumping an
     * existing starter into FLEX contention).
     */
    @Test
    void rosterNeedMatchesBruteForceLineupDelta() {
        RosterState r = new RosterState();
        r.add(entry(1, "RB1", Position.RB, 3));
        r.add(entry(2, "RB2", Position.RB, 15));
        r.add(entry(3, "RB3", Position.RB, 30));   // RB overflow -> FLEX contention
        r.add(entry(4, "WR1", Position.WR, 5));
        r.add(entry(5, "TE1", Position.TE, 45));
        r.add(entry(6, "QB1", Position.QB, 20));

        BoardEntry[] candidates = {
                entry(100, "WR2", Position.WR, 8),          // second WR, empty slot
                entry(101, "RBbetter", Position.RB, 2),      // beats an existing starter -> displaces
                entry(102, "RBworse", Position.RB, 200),     // loses to weakest starter and to FLEX
                entry(103, "K1", Position.K, 210),           // non-flex-eligible overflow
                entry(104, "TEbetter", Position.TE, 1),      // beats the sole TE starter
        };

        double before = rules.startingLineupValue(r, SETTINGS);
        for (BoardEntry c : candidates) {
            RosterState after = r.copy();
            after.add(c);
            double bruteForce = rules.startingLineupValue(after, SETTINGS) - before;
            double own = rules.value(c);
            double expectedNeed = Math.max(0.0, Math.min(1.0, bruteForce / own)) * 0.85 + 0.15;

            double actual = need(r, c);
            assertEquals(expectedNeed, actual, 1e-9,
                    "rosterNeed mismatch for " + c.player().name());
        }
    }
}
