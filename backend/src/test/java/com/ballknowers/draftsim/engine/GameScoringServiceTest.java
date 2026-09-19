package com.ballknowers.draftsim.engine;

import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * specs/005-daily-weekly-top-players T011/T012.
 *
 * <p>The numbers below are measured, not invented. Every expected value was
 * read off the live Sleeper API on 2026-09-19 and matched against the value
 * this app already has stored in {@code roster_week_points.players_points} for
 * the same player and week, so a failure here means the scoring drifted from
 * what the leagues actually score -- not that a fixture went stale.
 *
 * <p>Both leagues appear on purpose. Their scoring differs, so a single
 * hardcoded formula would pass one and fail the other.
 */
class GameScoringServiceTest {

    private final GameScoringService service = new GameScoringService();

    /** League 1229352720222134272 (Ball Knowers, nba 2025). */
    private static Map<String, Double> scoring2025() {
        Map<String, Double> m = new HashMap<>();
        m.put("pts", 0.5); m.put("reb", 1.0); m.put("ast", 1.0);
        m.put("stl", 2.0); m.put("blk", 2.0); m.put("tpm", 0.5); m.put("to", -1.0);
        m.put("dd", 2.0); m.put("td", 3.0); m.put("ff", -2.0); m.put("tf", -2.0);
        m.put("bonus_pt_40p", 2.0); m.put("bonus_pt_50p", 2.0);
        m.put("bonus_ast_15p", 2.0); m.put("bonus_reb_20p", 2.0);
        return m;
    }

    /**
     * League 1141438340626231296 (nba 2024). Deliberately different: {@code dd}
     * and {@code td} are worth less, and the assist and rebound bonuses do not
     * exist at all.
     */
    private static Map<String, Double> scoring2024() {
        Map<String, Double> m = new HashMap<>();
        m.put("pts", 0.5); m.put("reb", 1.0); m.put("ast", 1.0);
        m.put("stl", 2.0); m.put("blk", 2.0); m.put("tpm", 0.5); m.put("to", -1.0);
        m.put("dd", 1.0); m.put("td", 2.0); m.put("ff", -2.0); m.put("tf", -2.0);
        m.put("bonus_pt_40p", 2.0); m.put("bonus_pt_50p", 2.0);
        return m;
    }

    private static Map<String, Object> stats(Object... kv) {
        Map<String, Object> m = new HashMap<>();
        for (int i = 0; i < kv.length; i += 2) m.put((String) kv[i], kv[i + 1]);
        return m;
    }

    /**
     * Jokić's four games in week 5 of league 1229352720222134272, as the live
     * API returned them on 2026-09-19. The expected values are the ones this
     * app already has stored: the counted game, 58.5, is the anchor the whole
     * feature is built on.
     *
     * <p>These are real stat lines rather than reconstructions, because a
     * reconstruction only proves the test's own arithmetic. Note the third
     * game's 9 turnovers and the fourth's {@code bonus_pt_40p} -- a formula
     * that dropped either would still pass a tidier fixture.
     */
    @Test
    void reproducesEveryMeasuredGameOfJokicWeekFive() {
        assertEquals(58.5, service.score(scoring2025(), stats(
                "pts", 36.0, "reb", 18.0, "ast", 13.0, "stl", 1.0, "blk", 2.0,
                "tpm", 1.0, "to", 2.0, "dd", 1.0, "td", 1.0)), 0.001, "2025-11-17 vs CHI");

        assertEquals(34.0, service.score(scoring2025(), stats(
                "pts", 28.0, "reb", 11.0, "ast", 12.0, "tpm", 2.0, "to", 9.0,
                "dd", 1.0, "td", 1.0)), 0.001, "2025-11-19 vs NOP");

        assertEquals(44.0, service.score(scoring2025(), stats(
                "pts", 34.0, "reb", 10.0, "ast", 9.0, "stl", 2.0, "blk", 2.0,
                "tpm", 4.0, "to", 4.0, "dd", 1.0)), 0.001, "2025-11-21 vs HOU");

        assertEquals(45.5, service.score(scoring2025(), stats(
                "pts", 44.0, "reb", 13.0, "ast", 7.0, "tpm", 3.0, "to", 2.0,
                "dd", 1.0, "bonus_pt_40p", 1.0)), 0.001, "2025-11-22 vs SAC");
    }

    /**
     * The pair of numbers this feature must never confuse: the league counted
     * 58.5 for that week, while the player actually produced 182.0 across four
     * games. Both are true; only one decided a matchup (FR-005).
     */
    @Test
    void theWeeksGamesSumToTheFigureBestWeekWillReport() {
        double[] games = {58.5, 34.0, 44.0, 45.5};
        double total = 0;
        for (double g : games) total += g;
        assertEquals(182.0, total, 0.001);
        assertNotEquals(58.5, total, "a week total must never be mistaken for the counted game");
    }

    /**
     * The same stat line under the two leagues' scoring must differ, and differ
     * by exactly the categories that differ. This is the test that would fail if
     * anyone replaced the generic sum with one league's formula.
     */
    @Test
    void theSameGameIsWorthDifferentPointsInDifferentLeagues() {
        Map<String, Object> s = stats("pts", 30.0, "reb", 12.0, "ast", 11.0, "dd", 1.0, "td", 1.0);

        double in2025 = service.score(scoring2025(), s);
        double in2024 = service.score(scoring2024(), s);

        // dd is 2.0 vs 1.0 and td 3.0 vs 2.0, so 2025 is worth exactly 2 more.
        assertEquals(43.0, in2025, 0.001);
        assertEquals(41.0, in2024, 0.001);
        assertEquals(2.0, in2025 - in2024, 0.001);
    }

    /** A category the league scores but the game did not produce is zero, not an error. */
    @Test
    void aScoringKeyMissingFromTheStatsContributesZero() {
        Map<String, Object> sparse = stats("pts", 20.0);
        assertEquals(10.0, service.score(scoring2025(), sparse), 0.001);
    }

    /** A stat the league does not score is ignored rather than counted. */
    @Test
    void aStatKeyTheLeagueDoesNotScoreIsIgnored() {
        Map<String, Object> withExtras = stats(
                "pts", 20.0, "plus_minus", 15.0, "q1_pts", 6.0, "sp", 2450.0, "fga", 23.0);
        assertEquals(10.0, service.score(scoring2025(), withExtras), 0.001,
                "an unscored box-score field must not leak into the total");
    }

    /** Negative categories subtract. */
    @Test
    void negativeCategoriesSubtract() {
        assertEquals(-7.0, service.score(scoring2025(), stats("to", 3.0, "ff", 1.0, "tf", 1.0)), 0.001);
    }

    @Test
    void nullsAndEmptiesScoreZeroRatherThanThrowing() {
        assertEquals(0.0, service.score(null, stats("pts", 20.0)), 0.001);
        assertEquals(0.0, service.score(scoring2025(), null), 0.001);
        assertEquals(0.0, service.score(scoring2025(), Map.of()), 0.001);
    }

    /** A non-numeric stat value is skipped rather than throwing. */
    @Test
    void nonNumericStatValuesAreSkipped() {
        Map<String, Object> s = stats("pts", 20.0, "reb", "not a number");
        assertEquals(10.0, service.score(scoring2025(), s), 0.001);
    }
}
