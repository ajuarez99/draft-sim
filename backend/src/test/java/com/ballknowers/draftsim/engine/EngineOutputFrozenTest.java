package com.ballknowers.draftsim.engine;

import com.ballknowers.draftsim.config.ScoringProperties;
import com.ballknowers.draftsim.domain.*;
import com.ballknowers.draftsim.profile.ManagerProfile;
import com.ballknowers.draftsim.profile.PositionalPriors;
import com.ballknowers.draftsim.profile.Provenance;
import com.ballknowers.draftsim.sport.FootballRules;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.*;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * claude/audit-2026-09-28/11-reach-bias-baseline.md: the room-relative reach display must not
 * change the engine. A fixed-seed simulation over seats with real, differing reach biases is
 * hashed; the golden value was captured on origin/main BEFORE the display change and must
 * still hold after it. A change here means engine output moved, which is not what that work
 * is allowed to do -- do not update the golden to make this pass unless an engine change is
 * intended and verified separately.
 */
class EngineOutputFrozenTest {

    private static final String GOLDEN_SHA256 = "b1b657fdfe8ec6366e9e03487cc886df3fd5a9763d9f490c7de5e7100f164a05";

    private static DraftContext ctx() {
        int teams = 12, rounds = 15;
        Map<Integer, ManagerProfile> profiles = new HashMap<>();
        for (int s = 1; s <= teams; s++) {
            profiles.put(s, new ManagerProfile(s, "seat " + s, (s - 6) * 2.5,
                    Map.of(Position.RB, 1.0 + (s % 3) * 0.1), 0.8 + (s % 4) * 0.15, null, 1, 15,
                    Provenance.FITTED, null));
        }
        ScoringProperties.SportScoring cfg = new ScoringProperties.SportScoring(
                new ScoringProperties.Weights(1.0, 0.35, 0.5, 0.25),
                12.0, 3.0, 60.0, 0.15, 6, 0.85,
                Map.of("K", 3, "DEF", 4), 1.0, 30);
        LeagueSettings settings = new LeagueSettings(Sport.NFL, teams, rounds, List.of(
                "QB", "RB", "RB", "WR", "WR", "TE", "FLEX", "FLEX", "K", "DEF",
                "BN", "BN", "BN", "BN", "BN"), 1.0);
        return new DraftContext(MonteCarloRunnerTest.board(400), settings, profiles,
                PositionalPriors.uniform(Sport.NFL), new FootballRules(new ScoringProperties(cfg, null)), cfg,
                List.of(), Map.of());
    }

    private static String run() throws Exception {
        SimulationResult r = new MonteCarloRunner().run(ctx(), 4, 400, 1.0, 20260929L,
                MonteCarloRunnerTest.CONFIDENCE, null);
        byte[] d = MessageDigest.getInstance("SHA-256").digest(r.toString().getBytes(StandardCharsets.UTF_8));
        return HexFormat.of().formatHex(d);
    }

    @Test
    void sameSeedSameOutputInProcess() throws Exception {
        assertEquals(run(), run());
    }

    @Test
    void seededOutputMatchesTheValueCapturedBeforeTheDisplayChange() throws Exception {
        assertEquals(GOLDEN_SHA256, run());
    }
}
