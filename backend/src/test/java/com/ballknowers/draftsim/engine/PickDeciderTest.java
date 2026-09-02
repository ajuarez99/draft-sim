package com.ballknowers.draftsim.engine;

import com.ballknowers.draftsim.config.ScoringProperties;
import com.ballknowers.draftsim.domain.*;
import com.ballknowers.draftsim.profile.ManagerProfile;
import com.ballknowers.draftsim.profile.PositionalPriors;
import com.ballknowers.draftsim.sport.FootballRules;
import org.junit.jupiter.api.Test;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.SplittableRandom;

import static org.junit.jupiter.api.Assertions.*;

/**
 * PickDecider was extracted from DraftSimulator's former private choose()/sample()
 * (claude/next-features-roadmap.md §4, Phase 3) so the mock draft room's
 * MockDraftEngine can share it. DraftSimulatorTest already pins the extraction's
 * safety end-to-end (same test file, unchanged, still green): this file tests
 * the unit on its own, including the one behavior DraftSimulator's own tests
 * never had a reason to exercise directly -- being driven by a caller-supplied
 * RNG across independent calls rather than one long-lived instance field.
 */
class PickDeciderTest {

    private static final ScoringProperties.Sport CFG = new ScoringProperties.Sport(
            new ScoringProperties.Weights(1.0, 0.35, 0.5, 0.25),
            12.0, 3.0, 60.0, 0.15, 6, 0.85,
            Map.of("K", 3, "DEF", 4), 1.0, 30);

    private static List<BoardEntry> board(int n) {
        Position[] cycle = {Position.RB, Position.WR, Position.WR, Position.RB, Position.TE, Position.QB};
        List<BoardEntry> out = new ArrayList<>(n);
        for (int i = 0; i < n; i++) {
            out.add(new BoardEntry(new Player(i + 1L, Sport.NFL, "s" + i, "Player " + i,
                    List.of(cycle[i % cycle.length]), "FA", "Active", null, null, null), i + 1.0, i / 6 + 1));
        }
        return out;
    }

    private static DraftContext ctx(int teams, int rounds) {
        LeagueSettings settings = new LeagueSettings(teams, rounds,
                List.of("QB", "RB", "RB", "WR", "WR", "TE", "FLEX", "FLEX", "K", "DEF", "BN", "BN"), 1.0);
        Map<Integer, ManagerProfile> profiles = new HashMap<>();
        for (int s = 1; s <= teams; s++) profiles.put(s, ManagerProfile.neutral(s, "seat " + s));
        return new DraftContext(board(400), settings, profiles, PositionalPriors.uniform(),
                new FootballRules(new ScoringProperties(CFG)), CFG, List.of(), Map.of());
    }

    @Test
    void decideAndApplyRemovesTheChoiceFromAvailableAndAppliesItToRosterAndRecent() {
        DraftContext c = ctx(12, 15);
        PickDecider decider = new PickDecider(c, new PickScorer(CFG, c.rules(), c.priors()), 0.0);
        List<BoardEntry> available = new ArrayList<>(c.board());
        int sizeBefore = available.size();
        RosterState roster = new RosterState();
        Deque<Position> recent = new ArrayDeque<>();

        BoardEntry choice = decider.decideAndApply(
                available, 1, 1, 15, 180, 1, roster, recent, new SplittableRandom(1L));

        assertNotNull(choice);
        assertEquals(sizeBefore - 1, available.size(), "the chosen entry must be removed from available");
        assertFalse(available.contains(choice), "the same entry must not still be in available");
        assertEquals(1, roster.size(), "the choice must land on the roster");
        assertSame(choice, roster.picks().get(0));
        assertEquals(choice.position(), recent.peekFirst(), "the choice's position must be pushed onto recent");
    }

    @Test
    void decideAndApplyReturnsNullWhenAvailableIsEmpty() {
        DraftContext c = ctx(8, 15);
        PickDecider decider = new PickDecider(c, new PickScorer(CFG, c.rules(), c.priors()), 1.0);
        List<BoardEntry> available = new ArrayList<>();

        BoardEntry choice = decider.decideAndApply(
                available, 1, 1, 15, 120, 1, new RosterState(), new ArrayDeque<>(), new SplittableRandom(1L));

        assertNull(choice, "no candidates left means nothing to decide");
    }

    /**
     * Two independent SplittableRandom instances derived from the same seed the
     * way MockDraftEngine spreads one session seed across picks (`seed +
     * pickNo*GOLDEN`) -- confirms decideAndApply is genuinely driven by the
     * caller's rng parameter rather than any hidden instance state.
     */
    @Test
    void sameRngSeedProducesTheSameChoiceAcrossIndependentCalls() {
        DraftContext c = ctx(10, 15);
        PickScorer scorer = new PickScorer(CFG, c.rules(), c.priors());

        List<BoardEntry> availableA = new ArrayList<>(c.board());
        BoardEntry a = new PickDecider(c, scorer, 1.5)
                .decideAndApply(availableA, 5, 1, 15, 150, 3, new RosterState(), new ArrayDeque<>(),
                        new SplittableRandom(42L));

        List<BoardEntry> availableB = new ArrayList<>(c.board());
        BoardEntry b = new PickDecider(c, scorer, 1.5)
                .decideAndApply(availableB, 5, 1, 15, 150, 3, new RosterState(), new ArrayDeque<>(),
                        new SplittableRandom(42L));

        assertEquals(a.player().id(), b.player().id(), "identical seed and state must produce identical choice");
    }

    @Test
    void zeroTemperatureIsDeterministicRegardlessOfRngSeed() {
        DraftContext c = ctx(12, 15);
        PickScorer scorer = new PickScorer(CFG, c.rules(), c.priors());

        List<BoardEntry> availableA = new ArrayList<>(c.board());
        BoardEntry a = new PickDecider(c, scorer, 0.0)
                .decideAndApply(availableA, 1, 1, 15, 180, 1, new RosterState(), new ArrayDeque<>(),
                        new SplittableRandom(1L));
        List<BoardEntry> availableB = new ArrayList<>(c.board());
        BoardEntry b = new PickDecider(c, scorer, 0.0)
                .decideAndApply(availableB, 1, 1, 15, 180, 1, new RosterState(), new ArrayDeque<>(),
                        new SplittableRandom(999L));

        assertEquals(a.player().id(), b.player().id(), "T=0 should ignore the rng seed entirely");
        assertEquals(1L, a.player().id(), "1.01 at T=0 should be the #1 player on the board");
    }
}
