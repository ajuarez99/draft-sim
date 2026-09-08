package com.ballknowers.draftsim.sport;

import com.ballknowers.draftsim.config.ScoringProperties;
import com.ballknowers.draftsim.domain.*;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * See claude/multi-sport-and-rebrand.md §3c/Phase 4 and
 * claude/scripts/nba-greedy-optimality.py, which this class's design comes
 * from and was checked against (168 real roster states, 4000 synthetic ones,
 * zero mismatches with a brute-force oracle).
 */
class BasketballRulesTest {

    private static final List<String> SLOTS = List.of(
            "PG", "SG", "G", "SF", "PF", "F", "C", "UTIL", "UTIL",
            "BN", "BN", "BN", "BN", "BN");
    private static final LeagueSettings SETTINGS = new LeagueSettings(Sport.NBA, 12, 14, SLOTS, 0.0);

    private final BasketballRules rules = new BasketballRules(new ScoringProperties(
            null,
            new ScoringProperties.SportScoring(
                    new ScoringProperties.Weights(1.0, 0.35, 0.5, 0.25),
                    12.0, 3.0, 60.0, 0.15, 6, 0.85,
                    Map.of(), 1.0, 30)));

    private static BoardEntry entry(long id, String name, List<Position> positions, double adp) {
        return new BoardEntry(new Player(id, Sport.NBA, "s" + id, name, positions,
                null, "Active", null, null, null), adp, 1);
    }

    private static BoardEntry entry(long id, String name, Position pos, double adp) {
        return entry(id, name, List.of(pos), adp);
    }

    private double need(RosterState roster, BoardEntry candidate) {
        Object lineup = rules.prepareLineup(roster, SETTINGS, rules::value);
        return rules.rosterNeed(candidate, lineup);
    }

    /**
     * The exact case claude/scripts/nba-greedy-optimality.py's oracle proof
     * calls out by name: "five pure centres is genuinely infeasible -- only
     * {@code C} plus the two {@code UTIL} slots accept them", so a roster of
     * five pure centres can seat at most three, and the matroid greedy
     * theorem says the kept three are exactly the three highest-valued (here,
     * lowest adp) of the five -- not merely "some" feasible three.
     */
    @Test
    void theGreedyLineupCapsFivePureCentresAtTheThreeSlotsThatAcceptThem() {
        RosterState roster = new RosterState();
        BoardEntry c1 = entry(1, "C1", Position.C, 1);
        BoardEntry c2 = entry(2, "C2", Position.C, 2);
        BoardEntry c3 = entry(3, "C3", Position.C, 3);
        BoardEntry c4 = entry(4, "C4", Position.C, 40);
        BoardEntry c5 = entry(5, "C5", Position.C, 50);
        for (BoardEntry c : List.of(c1, c2, c3, c4, c5)) roster.add(c);

        Object lineup = rules.prepareLineup(roster, SETTINGS, rules::value);
        double expected = rules.value(c1) + rules.value(c2) + rules.value(c3);
        assertEquals(expected, rules.lineupValue(lineup), 1e-9,
                "only the three best centres should be seated -- C, UTIL, UTIL");
    }

    /**
     * A brand-new position (PG slot untouched) lets a candidate start at his
     * full own value; a fourth pure centre, with all three centre-accepting
     * slots already occupied by better centres, cannot join at all -- his
     * rosterNeed must reflect an eviction, not a free start.
     */
    @Test
    void aCandidateWithAnOpenSlotStartsAtFullValueButOneWhoMustEvictDoesNot() {
        RosterState roster = new RosterState();
        BoardEntry c1 = entry(1, "C1", Position.C, 1);
        BoardEntry c2 = entry(2, "C2", Position.C, 2);
        BoardEntry c3 = entry(3, "C3", Position.C, 3);
        BoardEntry c4 = entry(4, "C4", Position.C, 40);   // will not fit -- benched
        for (BoardEntry c : List.of(c1, c2, c3, c4)) roster.add(c);

        BoardEntry pg = entry(10, "PG1", Position.PG, 90);
        double n = need(roster, pg);
        assertEquals(1.0, n, 1e-9, "PG slot is completely open -- should start at full value");

        BoardEntry fifthCentre = entry(11, "C5", Position.C, 95);
        double evictNeed = need(roster, fifthCentre);
        assertTrue(evictNeed < 1.0, "a fourth seated centre is not possible -- this must not be a free start");
    }

    /**
     * The circuit a rejected candidate creates can name more than one seated
     * player (here, all three seated centres are reachable), and the
     * eviction value must be the CHEAPEST of them, not merely whichever one
     * the search happens to visit first.
     *
     * <p>The candidate's own value is chosen to sit strictly between c3 (the
     * weakest of the three kept centres) and c1/c2 (the two strongest) --
     * beating c3 but not the other two -- so a correct implementation and a
     * "first found" bug give DIFFERENT, distinguishable answers: comparing
     * against the true minimum (c3) yields a real positive captured value,
     * while comparing against c1 or c2 (either wrongly "first found," or
     * against c4, which was never seated at all) would wrongly floor to
     * captured = 0.
     */
    @Test
    void evictionPicksTheWeakestSeatedPlayerNotMerelyTheFirstOneFound() {
        RosterState roster = new RosterState();
        BoardEntry c1 = entry(1, "C1", Position.C, 1);
        BoardEntry c2 = entry(2, "C2", Position.C, 2);
        BoardEntry c3 = entry(3, "C3", Position.C, 50);   // weakest of the three kept -- the true eviction target
        BoardEntry c4 = entry(4, "C4", Position.C, 90);   // worse than all three kept -- never part of any circuit
        for (BoardEntry c : List.of(c1, c2, c3, c4)) roster.add(c);

        // Beats c3 (adp 50) but not c1/c2 (adp 1, 2).
        BoardEntry candidate = entry(11, "C5", Position.C, 30);
        double own = rules.value(candidate);
        double expectedDelta = Math.max(0.0, own - rules.value(c3));
        double expectedCaptured = Math.max(0.0, Math.min(1.0, expectedDelta / own));
        double expectedNeed = 0.15 + 0.85 * expectedCaptured;
        assertTrue(expectedCaptured > 0.0, "test setup check: candidate must actually beat c3");

        assertEquals(expectedNeed, need(roster, candidate), 1e-9,
                "must evict against c3 (the weakest SEATED centre), not c1/c2, and never c4 (never seated)");
    }

    /**
     * Mirrors FootballRulesTest's own lock test: every slot the player is
     * eligible for, bench included, is full -> undraftable; free a single
     * bench slot and the same player becomes draftable again. Nine players
     * eligible at every NBA position guarantee (by Hall's theorem on a
     * complete bipartite graph -- no per-slot tracing needed) that all nine
     * starting slots fill, whichever specific permutation the matcher picks.
     */
    @Test
    void aPlayerWhoseEveryEligibleSlotIsFullIsNotDraftableButTheSameWithAVacancyIs() {
        List<Position> anyNba = List.of(Position.PG, Position.SG, Position.SF, Position.PF, Position.C);

        RosterState full = new RosterState();
        for (int i = 1; i <= 9; i++) full.add(entry(i, "P" + i, anyNba, i));
        for (int i = 10; i <= 14; i++) full.add(entry(i, "BN" + i, anyNba, 100 + i));   // BN x5, all full

        BoardEntry extraGuard = entry(200, "Extra", List.of(Position.PG, Position.SG), 500);
        Object fullLineup = rules.prepareLineup(full, SETTINGS, rules::value);
        assertFalse(rules.isDraftable(extraGuard, fullLineup, 10, 14),
                "nine starters + five bench, all full -- nothing left he fits");

        RosterState oneBenchOpen = new RosterState();
        for (int i = 1; i <= 9; i++) oneBenchOpen.add(entry(i, "P" + i, anyNba, i));
        for (int i = 10; i <= 13; i++) oneBenchOpen.add(entry(i, "BN" + i, anyNba, 100 + i));   // only 4 of 5 BN

        Object vacancyLineup = rules.prepareLineup(oneBenchOpen, SETTINGS, rules::value);
        assertTrue(rules.isDraftable(extraGuard, vacancyLineup, 10, 14),
                "one bench slot is still open");
    }

    /**
     * Four pure point guards exactly fill the four slots point-guard
     * eligibility reaches ({@code PG, G, UTIL, UTIL}) -- a perfect matching by
     * the pigeonhole/Hall argument, so all four seat and none are left over.
     * A fifth pure PG has nowhere left: every slot he is eligible for is
     * occupied by another PG-only player who also has nowhere else to go. A
     * (PG,SG)-eligible candidate, though, reaches the empty SG slot -- which
     * no pure-PG player's search could ever discover -- and joins. This is
     * the concrete demonstration that multi-eligibility changes the answer.
     */
    @Test
    void multiEligibilityLetsAGuardInWhereAPurePointGuardCannotFit() {
        RosterState roster = new RosterState();
        roster.add(entry(1, "PG1", Position.PG, 1));
        roster.add(entry(2, "PG2", Position.PG, 2));
        roster.add(entry(3, "PG3", Position.PG, 3));
        roster.add(entry(4, "PG4", Position.PG, 4));
        // SG, SF, PF, F, C all remain completely empty.

        Object lineup = rules.prepareLineup(roster, SETTINGS, rules::value);
        assertEquals(rules.value(roster.picks().get(0)) + rules.value(roster.picks().get(1))
                        + rules.value(roster.picks().get(2)) + rules.value(roster.picks().get(3)),
                rules.lineupValue(lineup), 1e-9,
                "all four pure PGs should fit -- PG, G and both UTIL slots exactly accept them");

        BoardEntry purePg = entry(10, "PG5", Position.PG, 90);
        double pureNeed = rules.rosterNeed(purePg, lineup);

        BoardEntry guardEither = entry(11, "GuardEither", List.of(Position.PG, Position.SG), 90);
        double multiNeed = rules.rosterNeed(guardEither, lineup);

        assertTrue(multiNeed > pureNeed,
                "the (PG,SG) candidate reaches the empty SG slot; the pure PG cannot ("
                        + multiNeed + " vs " + pureNeed + ")");
        assertEquals(1.0, multiNeed, 1e-9, "SG is wide open -- he should start at full value");
    }
}
