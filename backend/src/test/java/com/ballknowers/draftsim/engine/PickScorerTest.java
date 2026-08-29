package com.ballknowers.draftsim.engine;

import com.ballknowers.draftsim.config.ScoringProperties;
import com.ballknowers.draftsim.domain.*;
import com.ballknowers.draftsim.profile.PositionalPriors;
import com.ballknowers.draftsim.sport.FootballRules;
import org.junit.jupiter.api.Test;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class PickScorerTest {

    private static final ScoringProperties.Sport CFG = new ScoringProperties.Sport(
            new ScoringProperties.Weights(1.0, 0.35, 0.5, 0.25),
            12.0, 3.0, 60.0, 0.15, 6, 0.85,
            Map.of("K", 13, "DEF", 12), 1.0, 30);

    private final PickScorer scorer = new PickScorer(
            CFG, new FootballRules(new ScoringProperties(CFG)), PositionalPriors.uniform());

    private static BoardEntry at(double boardPosition) {
        return new BoardEntry(new Player(1, Sport.NFL, "s1", "P", List.of(Position.WR),
                "FA", "Active", null, null, null), boardPosition, 1);
    }

    /**
     * The sign of this term is the single easiest thing in the engine to get
     * backwards, and getting it backwards makes the model prefer the worst
     * player available while every structural test still passes.
     */
    @Test
    void aPlayerWhoFellPastHisBoardSlotIsValueAndReachingIsNot() {
        double fell = scorer.valueDelta(at(1), 30, 0);     // board #1 still there at pick 30
        double reach = scorer.valueDelta(at(60), 30, 0);   // board #60 taken at pick 30
        assertTrue(fell > 0, "a faller should score positive, got " + fell);
        assertTrue(reach < 0, "a reach should score negative, got " + reach);
    }

    @Test
    void reachBiasSoftensThePenaltyForTakingSomeoneEarly() {
        assertTrue(scorer.valueDelta(at(60), 30, 24) > scorer.valueDelta(at(60), 30, 0));
    }

    @Test
    void valueDeltaIsClamped() {
        assertTrue(Math.abs(scorer.valueDelta(at(400), 1, 0)) <= CFG.valueDeltaClamp() + 1e-9);
        assertTrue(Math.abs(scorer.valueDelta(at(1), 400, 0)) <= CFG.valueDeltaClamp() + 1e-9);
    }

    @Test
    void runPressureSeesTheRunItIsInAndNotTheOnesItIsNot() {
        Deque<Position> recent = new ArrayDeque<>();
        recent.addFirst(Position.TE);
        recent.addFirst(Position.TE);
        recent.addFirst(Position.RB);
        assertTrue(scorer.runPressure(recent, Position.TE) > 0.5);
        assertEquals(0.0, scorer.runPressure(recent, Position.QB));
        assertEquals(0.0, scorer.runPressure(new ArrayDeque<>(), Position.WR));
    }
}
