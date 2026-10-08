package com.ballknowers.draftsim.engine;

import com.ballknowers.draftsim.engine.AdvancedStats.Rate;
import com.ballknowers.draftsim.engine.NbaGameLines.Line;
import com.ballknowers.draftsim.store.PlayerGameRepository.TeamGame;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Direct tests of {@link AdvancedStats#usage} on hand-computed values (spec 022 T010). Trends own usage
 * assertions stay in PlayerTrendsServiceTest as its guard (N12).
 */
class AdvancedStatsUsageTest {

    private static final LocalDate D = LocalDate.parse("2025-11-01");

    private static TeamGame team(double sp, double fga, double fta, double to) {
        return new TeamGame("AAA", "g", D, "BBB", Map.of("sp", sp, "fga", fga, "fta", fta, "to", to));
    }

    private static Line line(double minutes, double fga, double fta, double to, TeamGame teamRow) {
        return new Line("g", D, 1, teamRow == null ? null : "AAA", "BBB", true, minutes,
                Map.of("fga", fga, "fta", fta, "to", to), teamRow, null);
    }

    @Test
    void oneGameMatchesTheHandComputedFormula() {
        // num = (10 + 0.44*5 + 2) * (240/5) = 14.2 * 48 = 681.6
        // den = 30 * (90 + 0.44*20 + 10) = 30 * 108.8 = 3264
        Rate r = AdvancedStats.usage(List.of(line(30, 10, 5, 2, team(14400, 90, 20, 10))));
        assertNull(r.reason());
        assertEquals(100.0 * 681.6 / 3264.0, r.value(), 1e-9);
        assertEquals(20.882352941, r.value(), 1e-6);
    }

    @Test
    void twoGamesArePooledNotAveraged() {
        // game 2: 10 min, fga 2, fta 0, to 0; team 240 min, fga 80, fta 0, to 0
        //   num2 = 2 * 48 = 96 ; den2 = 10 * 80 = 800
        // pooled = 100 * (681.6 + 96) / (3264 + 800) = 19.1338...
        Rate r = AdvancedStats.usage(List.of(
                line(30, 10, 5, 2, team(14400, 90, 20, 10)),
                line(10, 2, 0, 0, team(14400, 80, 0, 0))));
        assertEquals(100.0 * 777.6 / 4064.0, r.value(), 1e-9);
        double average = (100.0 * 681.6 / 3264.0 + 100.0 * 96 / 800) / 2;
        assertTrue(Math.abs(average - r.value()) > 1e-3, "pooled and the mean of per-game rates differ");
    }

    @Test
    void aGameWithoutATeamRowContributesNothingToTheSums() {
        Rate with = AdvancedStats.usage(List.of(
                line(30, 10, 5, 2, team(14400, 90, 20, 10)),
                line(40, 25, 9, 4, null)));
        Rate only = AdvancedStats.usage(List.of(line(30, 10, 5, 2, team(14400, 90, 20, 10))));
        assertEquals(only.value(), with.value(), 0.0);
    }

    @Test
    void anEmptyWindowOrNoTeamRowIsNoTeamRow() {
        assertEquals(AdvancedStats.NO_TEAM_ROW, AdvancedStats.usage(List.of()).reason());
        Rate r = AdvancedStats.usage(List.of(line(30, 10, 5, 2, null)));
        assertNull(r.value());
        assertEquals(AdvancedStats.NO_TEAM_ROW, r.reason());
    }

    @Test
    void noMinutesIsNoMinutes() {
        Rate r = AdvancedStats.usage(List.of(line(0, 10, 5, 2, team(14400, 90, 20, 10))));
        assertNull(r.value());
        assertEquals(AdvancedStats.NO_MINUTES, r.reason());
    }

    @Test
    void minutesButATeamThatUsedNoPossessionsIsNoAttempts() {
        Rate r = AdvancedStats.usage(List.of(line(30, 10, 5, 2, team(14400, 0, 0, 0))));
        assertNull(r.value());
        assertEquals(AdvancedStats.NO_ATTEMPTS, r.reason());
    }

    @Test
    void aRateHasExactlyOneOfValueAndReason() {
        assertThrows(IllegalArgumentException.class, () -> new Rate(null, null));
        assertThrows(IllegalArgumentException.class, () -> new Rate(1.0, "NO_MINUTES"));
        assertDoesNotThrow(() -> Rate.of(0.0), "a true zero is a value, not an absence");
    }
}
