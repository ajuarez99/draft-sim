package com.ballknowers.draftsim.historical;

import com.ballknowers.draftsim.config.ScoringProperties;
import com.ballknowers.draftsim.domain.BoardEntry;
import com.ballknowers.draftsim.domain.LeagueSettings;
import com.ballknowers.draftsim.domain.Player;
import com.ballknowers.draftsim.domain.Position;
import com.ballknowers.draftsim.domain.Sport;
import com.ballknowers.draftsim.engine.DraftContext;
import com.ballknowers.draftsim.engine.MockDraftEngine;
import com.ballknowers.draftsim.engine.SeatSpec;
import com.ballknowers.draftsim.profile.ManagerProfile;
import com.ballknowers.draftsim.profile.PositionalPriors;
import com.ballknowers.draftsim.sport.FootballRules;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * claude/time-machine-bot-strength.md. The tuning is one number swapped into the shared scoring block, so the test
 * that matters is about DIRECTION (lessons: a scoring change can be well-formed and pointed backwards): a lower
 * temperature must make the bots follow the board more closely.
 */
class BotTuningTest {

    private static final ScoringProperties.SportScoring SHARED = new ScoringProperties.SportScoring(
            new ScoringProperties.Weights(1.0, 0.35, 0.5, 0.25),
            12.0, 3.0, 60.0, 0.15, 6, 0.85, Map.of("K", 3, "DEF", 4), 1.0, 30);

    private static final List<String> ROSTER =
            List.of("QB", "RB", "RB", "WR", "WR", "TE", "FLEX", "BN", "BN", "BN", "BN", "BN", "BN", "BN", "BN");

    private final MockDraftEngine engine = new MockDraftEngine();

    @Test
    void noneReturnsTheSharedBlockItself() {
        assertSame(SHARED, BotTuning.NONE.apply(SHARED), "no tuning must not even copy the shared block");
    }

    @Test
    void onlyTheTemperatureChanges() {
        ScoringProperties.SportScoring t = new BotTuning(0.25).apply(SHARED);
        assertEquals(0.25, t.temperature());
        assertEquals(SHARED.weights(), t.weights());
        assertEquals(SHARED.adpScale(), t.adpScale());
        assertEquals(SHARED.valueDeltaClamp(), t.valueDeltaClamp());
        assertEquals(SHARED.valueDecay(), t.valueDecay());
        assertEquals(SHARED.benchFloor(), t.benchFloor());
        assertEquals(SHARED.runWindow(), t.runWindow());
        assertEquals(SHARED.runRecencyDecay(), t.runRecencyDecay());
        assertEquals(SHARED.latestRounds(), t.latestRounds());
        assertEquals(SHARED.candidatePool(), t.candidatePool());
    }

    @Test
    void aNegativeOrUnreadableTemperatureIsRefused() {
        assertThrows(IllegalArgumentException.class, () -> new BotTuning(-0.1));
        assertThrows(IllegalArgumentException.class, () -> new BotTuning(Double.NaN));
    }

    /** At temperature 0 a bot takes the top of its own score every time, so the same draft comes out for any seed. */
    @Test
    void temperatureZeroIsDeterministicAcrossSeeds() {
        List<Long> a = draft(new BotTuning(0.0), 1).stream().map(d -> d.player().player().id()).toList();
        List<Long> b = draft(new BotTuning(0.0), 99).stream().map(d -> d.player().player().id()).toList();
        assertEquals(a, b);
    }

    /** Preference ordering: the colder the bots, the closer their first three rounds stay to the board. */
    @Test
    void aLowerTemperatureFollowsTheBoardMoreClosely() {
        double hot = meanEarlyReach(BotTuning.NONE);
        double warm = meanEarlyReach(new BotTuning(0.4));
        double cold = meanEarlyReach(new BotTuning(0.15));
        assertTrue(warm < hot, "T 0.4 must reach less than the shared T 1.0: " + warm + " vs " + hot);
        assertTrue(cold < warm, "T 0.15 must reach less than T 0.4: " + cold + " vs " + warm);
    }

    // ---- helpers -----------------------------------------------------------------------------------------

    /** Mean |board rank - pick number| over rounds 1-3 of an all-bot draft, across seeds. */
    private double meanEarlyReach(BotTuning tuning) {
        double sum = 0;
        int n = 0;
        for (long seed = 1; seed <= 40; seed++) {
            for (MockDraftEngine.Decision d : draft(tuning, seed)) {
                if (d.round() > 3) continue;
                sum += Math.abs(d.player().adp() - d.pickNo());
                n++;
            }
        }
        return sum / n;
    }

    /** A whole 12-team, 15-round draft with no user seat, under the tuned block. */
    private List<MockDraftEngine.Decision> draft(BotTuning tuning, long seed) {
        ScoringProperties.SportScoring cfg = tuning.apply(SHARED);
        LeagueSettings settings = new LeagueSettings(Sport.NFL, 12, 15, ROSTER, 0.0, 0);
        Map<Integer, ManagerProfile> profiles = new HashMap<>();
        for (int s = 1; s <= 12; s++) profiles.put(s, ManagerProfile.neutral(-1, "seat " + s));
        DraftContext ctx = new DraftContext(board(400), settings, profiles, PositionalPriors.uniform(Sport.NFL),
                new FootballRules(new ScoringProperties(SHARED, null)), cfg, List.of(), Map.of());
        MockDraftEngine.AdvanceResult r = engine.advanceUntilUserOrEnd(ctx, List.<SeatSpec>of(), seed);
        assertTrue(r.complete());
        return r.newPicks();
    }

    /** Rank 1 = best; positions in a fixed mix so every position is scarce somewhere on the board. */
    private static List<BoardEntry> board(int n) {
        Position[] cycle = {Position.RB, Position.WR, Position.WR, Position.RB, Position.TE, Position.QB,
                Position.WR, Position.RB};
        List<BoardEntry> out = new ArrayList<>(n);
        Map<Position, Integer> perPos = new HashMap<>();
        for (int i = 0; i < n; i++) {
            Position p = cycle[i % cycle.length];
            out.add(new BoardEntry(new Player(i + 1L, Sport.NFL, "h" + i, "Player " + i, List.of(p), "FA", null,
                    null, null, null), i + 1.0, perPos.merge(p, 1, Integer::sum)));
        }
        return out;
    }
}
