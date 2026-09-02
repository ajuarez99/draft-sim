package com.ballknowers.draftsim.engine;

import com.ballknowers.draftsim.config.ScoringProperties;
import com.ballknowers.draftsim.domain.*;
import com.ballknowers.draftsim.profile.ManagerProfile;
import com.ballknowers.draftsim.profile.Provenance;
import com.ballknowers.draftsim.profile.PositionalPriors;
import com.ballknowers.draftsim.sport.FootballRules;
import org.junit.jupiter.api.Test;

import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

class DraftSimulatorTest {

    private static final List<String> SLOTS = List.of(
            "QB", "RB", "RB", "WR", "WR", "TE", "FLEX", "FLEX", "K", "DEF",
            "BN", "BN", "BN", "BN", "BN");

    private static final ScoringProperties.Sport CFG = new ScoringProperties.Sport(
            new ScoringProperties.Weights(1.0, 0.35, 0.5, 0.25),
            12.0, 3.0, 60.0, 0.15, 6, 0.85,
            Map.of("K", 3, "DEF", 4), 1.0, 30);

    /** A synthetic board with a realistic positional mix, ordered 1..n. */
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

    private static DraftContext ctx(int teams, int rounds, Map<Integer, Long> completed) {
        LeagueSettings settings = new LeagueSettings(teams, rounds, SLOTS, 1.0);
        Map<Integer, ManagerProfile> profiles = new HashMap<>();
        for (int s = 1; s <= teams; s++) profiles.put(s, ManagerProfile.neutral(s, "seat " + s));
        return new DraftContext(
                board(400), settings, profiles, PositionalPriors.uniform(),
                new FootballRules(new ScoringProperties(CFG)), CFG,
                completed.keySet().stream().sorted().toList(), completed);
    }

    @Test
    void aFullDraftFillsEveryPickWithoutRepeatingAPlayer() {
        DraftContext c = ctx(14, 15, Map.of());
        var sim = new DraftSimulator(c, new PickScorer(CFG, c.rules(), c.priors()), 1.0, 42L);
        var result = sim.run(DraftSlot.picksForSlot(11, 14, 15), 75);

        Set<Long> seen = new HashSet<>();
        for (int p = 1; p <= 210; p++) {
            long id = result.picked()[p];
            assertNotEquals(0L, id, "pick " + p + " was never made");
            assertTrue(seen.add(id), "player " + id + " drafted twice (pick " + p + ")");
        }
    }

    @Test
    void kickersAndDefensesDoNotGoEarly() {
        DraftContext c = ctx(14, 15, Map.of());
        var sim = new DraftSimulator(c, new PickScorer(CFG, c.rules(), c.priors()), 1.0, 7L);
        var result = sim.run(new int[]{11}, 10);

        Map<Long, BoardEntry> byId = new HashMap<>();
        for (BoardEntry e : c.board()) byId.put(e.player().id(), e);

        for (int p = 1; p <= 14 * 11; p++) {   // through round 11
            Position pos = byId.get(result.picked()[p]).position();
            assertNotEquals(Position.K, pos, "kicker taken at pick " + p);
            assertNotEquals(Position.DEF, pos, "defense taken at pick " + p);
        }
    }

    @Test
    void completedPicksAreReplayedExactly() {
        Map<Integer, Long> completed = Map.of(1, 50L, 2, 12L, 3, 200L);
        DraftContext c = ctx(14, 15, completed);
        var sim = new DraftSimulator(c, new PickScorer(CFG, c.rules(), c.priors()), 1.0, 1L);
        var result = sim.run(new int[]{11}, 10);

        assertEquals(50L, result.picked()[1]);
        assertEquals(12L, result.picked()[2]);
        assertEquals(200L, result.picked()[3]);
        // and none of them show up again later
        for (int p = 4; p <= 210; p++) {
            assertNotEquals(50L, result.picked()[p]);
            assertNotEquals(12L, result.picked()[p]);
            assertNotEquals(200L, result.picked()[p]);
        }
    }

    @Test
    void completedPicksOverlappingMyPicksProducesAWellFormedSnapshot() {
        // Previously completedPicks was always *other* teams' picks; reactive
        // resimulation (claude/reactive-resimulation.md) makes it reachable for
        // the viewing user's own pick too. The snapshot for a myPicks pick
        // number is taken before the completedPicks short-circuit
        // (DraftSimulator.run()'s ordering: snapshot first, then check
        // `already`), so this pins that the ordering stays harmless under the
        // new combination rather than corrupting or emptying the snapshot.
        Map<Integer, Long> completed = Map.of(1, 50L);
        DraftContext c = ctx(14, 15, completed);
        var sim = new DraftSimulator(c, new PickScorer(CFG, c.rules(), c.priors()), 1.0, 3L);
        var result = sim.run(new int[]{1, 25}, 10);

        assertEquals(50L, result.picked()[1], "the completed pick should still be replayed exactly");

        long[] snapshot = result.availableAtMyPicks().get(1);
        assertNotNull(snapshot, "no snapshot recorded for a pick that is both completed and mine");
        assertTrue(snapshot.length > 0, "snapshot at pick 1 came back empty");
        Set<Long> seen = new HashSet<>();
        for (long id : snapshot) {
            assertNotEquals(0L, id, "snapshot contains a zero/placeholder id");
            assertTrue(seen.add(id), "snapshot contains duplicate id " + id);
        }

        // pick 25 is untouched by this overlap and should behave exactly as before.
        assertNotNull(result.availableAtMyPicks().get(25));
    }

    @Test
    void zeroTemperatureIsDeterministic() {
        DraftContext c = ctx(12, 15, Map.of());
        var a = new DraftSimulator(c, new PickScorer(CFG, c.rules(), c.priors()), 0.0, 1L)
                .run(new int[]{5}, 10);
        var b = new DraftSimulator(c, new PickScorer(CFG, c.rules(), c.priors()), 0.0, 999L)
                .run(new int[]{5}, 10);
        assertArrayEquals(a.picked(), b.picked(), "T=0 should ignore the seed entirely");
    }

    @Test
    void higherTemperatureProducesMoreVariedBoards() {
        DraftContext c = ctx(12, 15, Map.of());
        double hi = averageDeviationFromModal(c, 3.0);
        double lo = averageDeviationFromModal(c, 0.2);
        assertTrue(hi > lo,
                "chaos mode should deviate from the modal board more than a near-modal run");
    }

    /**
     * "Distinct board count out of N trials" saturates at N the moment every
     * trial differs from every other, which happens well before T=0.2 at this
     * pool size — it cannot tell "a little varied" from "very varied" once both
     * hit the ceiling. Average Hamming distance from the T=0 modal board keeps
     * discriminating past that point.
     */
    private double averageDeviationFromModal(DraftContext c, double temperature) {
        var modal = new DraftSimulator(c, new PickScorer(CFG, c.rules(), c.priors()), 0.0, 1L)
                .run(new int[]{5}, 5);
        int trials = 25;
        int totalDiff = 0;
        for (int i = 0; i < trials; i++) {
            var r = new DraftSimulator(c, new PickScorer(CFG, c.rules(), c.priors()), temperature, i)
                    .run(new int[]{5}, 5);
            for (int p = 1; p <= 12; p++) if (r.picked()[p] != modal.picked()[p]) totalDiff++;
        }
        return (double) totalDiff / trials;
    }

    /**
     * Structural tests all pass with the value term inverted, so this is the one
     * that actually pins down that the model prefers good players.
     */
    @Test
    void theModalBoardStartsWithTheBestPlayerAndStaysNearTheTop() {
        DraftContext c = ctx(14, 15, Map.of());
        var modal = new DraftSimulator(c, new PickScorer(CFG, c.rules(), c.priors()), 0.0, 1L)
                .run(new int[]{11}, 5);

        Map<Long, BoardEntry> byId = new HashMap<>();
        for (BoardEntry e : c.board()) byId.put(e.player().id(), e);

        assertEquals(1L, modal.picked()[1], "1.01 at T=0 should be the #1 player on the board");

        double mean = 0;
        for (int p = 1; p <= 12; p++) mean += byId.get(modal.picked()[p]).adp();
        assertTrue(mean / 12 < 20, "the first 12 picks should come off the top of the board, got " + mean / 12);
    }

    @Test
    void nearModalTemperatureConcentratesTheFirstPick() {
        DraftContext c = ctx(14, 15, Map.of());
        var scorer = new PickScorer(CFG, c.rules(), c.priors());
        Set<Long> cold = new HashSet<>(), hot = new HashSet<>();
        for (int i = 0; i < 40; i++) {
            cold.add(new DraftSimulator(c, scorer, 0.15, i).run(new int[]{11}, 5).picked()[1]);
            hot.add(new DraftSimulator(c, scorer, 3.0, i).run(new int[]{11}, 5).picked()[1]);
        }
        assertTrue(hot.size() > cold.size(), "chaos should spread the 1.01 pick");
        // Not 1: adjacent board slots are ~0.08 apart in valueDelta at the default
        // adpScale, so even a cold run does not pin a single player. That is a
        // property of adpScale, not a bug -- turn adpScale down to sharpen it.
        assertTrue(cold.size() <= 10, "cold runs should concentrate, got " + cold.size());
    }

    private static DraftContext ctxWithChaosSeat(int chaosSlot, double multiplier) {
        LeagueSettings settings = new LeagueSettings(14, 15, SLOTS, 1.0);
        Map<Integer, ManagerProfile> profiles = new HashMap<>();
        for (int s = 1; s <= 14; s++) {
            profiles.put(s, s == chaosSlot
                    ? new ManagerProfile(s, "chaos", 0.0, Map.of(), multiplier, null, 0, 0, Provenance.STATED)
                    : ManagerProfile.neutral(s, "seat " + s));
        }
        return new DraftContext(board(400), settings, profiles, PositionalPriors.uniform(),
                new FootballRules(new ScoringProperties(CFG)), CFG, List.of(), Map.of());
    }

    private static int distinctPickAt(DraftContext c, double t, int pickNo) {
        var scorer = new PickScorer(CFG, c.rules(), c.priors());
        Set<Long> seen = new HashSet<>();
        for (int i = 0; i < 60; i++) {
            seen.add(new DraftSimulator(c, scorer, t, i).run(new int[]{11}, 5).picked()[pickNo]);
        }
        return seen.size();
    }

    @Test
    void anUnpredictableSeatVariesMoreAtItsOwnPick() {
        int calm = distinctPickAt(ctxWithChaosSeat(11, 1.0), 0.4, 11);
        int wild = distinctPickAt(ctxWithChaosSeat(11, 4.0), 0.4, 11);
        assertTrue(wild > calm, "chaos seat should spread its own pick (" + wild + " vs " + calm + ")");
    }

    @Test
    void oneChaoticSeatDoesNotDisturbTheOthers() {
        // pick 1 belongs to slot 1; slot 11's multiplier must not reach it
        assertEquals(distinctPickAt(ctxWithChaosSeat(11, 1.0), 0.4, 1),
                distinctPickAt(ctxWithChaosSeat(11, 4.0), 0.4, 1));
    }

    /**
     * The whole reason unpredictability is a multiplier rather than an absolute:
     * the global chaos slider has to keep its meaning at both ends.
     */
    @Test
    void temperatureZeroStaysModalEvenForAChaosSeat() {
        assertEquals(1, distinctPickAt(ctxWithChaosSeat(11, 5.0), 0.0, 11));
    }

    @Test
    void softmaxFavoursHigherScoresWithoutBeingDeterministic() {
        DraftContext c = ctx(12, 15, Map.of());
        var sim = new DraftSimulator(c, new PickScorer(CFG, c.rules(), c.priors()), 1.0, 5L);
        double[] scores = {0.0, 2.0};
        int second = 0;
        for (int i = 0; i < 2000; i++) if (sim.sample(scores) == 1) second++;
        // exp(2)/(exp(2)+1) ~= 0.881
        assertTrue(second > 1600 && second < 1900, "expected ~88% on the better option, got " + second / 20.0 + "%");
    }
}
