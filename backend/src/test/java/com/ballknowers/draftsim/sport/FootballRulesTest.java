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
                    Map.of("K", 13, "DEF", 12), 1.0, 30)));

    private static BoardEntry entry(long id, String name, Position pos, double adp) {
        return new BoardEntry(new Player(id, Sport.NFL, "s" + id, name, List.of(pos),
                "FA", "Active", null, null, null), adp, 1);
    }

    @Test
    void anEmptyRosterNeedsEverythingAtFullValue() {
        RosterState empty = new RosterState();
        assertEquals(1.0, rules.rosterNeed(empty, entry(1, "RB1", Position.RB, 3), SETTINGS), 1e-6);
    }

    @Test
    void depthBehindFilledStartersScoresLowerThanAnUnfilledSlot() {
        RosterState r = new RosterState();
        r.add(entry(1, "RB1", Position.RB, 3));
        r.add(entry(2, "RB2", Position.RB, 15));
        r.add(entry(3, "RB3", Position.RB, 30));
        r.add(entry(4, "RB4", Position.RB, 40));   // fills RB, RB, FLEX, FLEX

        double fifthRb = rules.rosterNeed(r, entry(5, "RB5", Position.RB, 90), SETTINGS);
        double firstQb = rules.rosterNeed(r, entry(6, "QB1", Position.QB, 90), SETTINGS);
        assertTrue(firstQb > fifthRb,
                "an unfilled QB slot should outrank a fifth RB (" + firstQb + " vs " + fifthRb + ")");
    }

    @Test
    void benchDepthNeverScoresZero() {
        RosterState r = new RosterState();
        for (int i = 0; i < 4; i++) r.add(entry(i, "K" + i, Position.K, 200 + i));
        double need = rules.rosterNeed(r, entry(99, "K5", Position.K, 250), SETTINGS);
        assertTrue(need >= 0.15 - 1e-9, "benchFloor should apply, got " + need);
    }

    @Test
    void kickersAndDefensesAreGatedEarly() {
        assertFalse(rules.isDraftable(entry(1, "K", Position.K, 200), 5));
        assertTrue(rules.isDraftable(entry(1, "K", Position.K, 200), 13));
        assertFalse(rules.isDraftable(entry(2, "DEF", Position.DEF, 190), 11));
        assertTrue(rules.isDraftable(entry(2, "DEF", Position.DEF, 190), 12));
        assertTrue(rules.isDraftable(entry(3, "WR", Position.WR, 1), 1));
    }

    @Test
    void flexAbsorbsTheThirdRunningBack() {
        RosterState twoRb = new RosterState();
        twoRb.add(entry(1, "RB1", Position.RB, 3));
        twoRb.add(entry(2, "RB2", Position.RB, 15));
        double third = rules.rosterNeed(twoRb, entry(3, "RB3", Position.RB, 25), SETTINGS);
        assertTrue(third > 0.9, "third RB should still be a starter via FLEX, got " + third);
    }
}
