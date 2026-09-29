package com.ballknowers.draftsim.engine;

import com.ballknowers.draftsim.config.ScoringProperties;
import com.ballknowers.draftsim.domain.*;
import com.ballknowers.draftsim.profile.ManagerProfile;
import com.ballknowers.draftsim.profile.PositionalPriors;
import com.ballknowers.draftsim.sport.FootballRules;
import org.junit.jupiter.api.Test;

import java.util.*;

/** Timing harness for audit 05: prints wall-clock for the UI's normal sizes. Asserts nothing. */
class SimulationTimingHarnessTest {

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

    /** Timing harness for audit 05 (prints only). */
    @Test
    void printTimings() {
        for (int n : new int[] {500, 2000, 500, 2000}) {
            DraftContext c = ctx(8, 15);
            long t = System.nanoTime();
            new MonteCarloRunner().run(c, 3, n, 1.0, 5L, CONFIDENCE, null);
            System.out.println("TIMING " + n + " iterations: " + (System.nanoTime() - t) / 1_000_000 + " ms");
        }
    }

}
