package com.ballknowers.draftsim.api;

import com.ballknowers.draftsim.engine.PlayoffOddsService;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The forecast payload's staleness fields (claude/audit-2026-09-28/10), asserted
 * against the map the controller builds, without a database.
 *
 * <p>{@code week} is the week the stored snapshot was taken at; {@code latestScoredWeek}
 * is how far the league has actually scored. The page compares the two, so both must
 * be on the wire, and {@code latestScoredWeek} must survive every refusal too.
 */
class SeasonForecastShapeTest {

    private static PlayoffOddsService.Forecast available(int snapshotWeek, int latest) {
        return new PlayoffOddsService.Forecast(true, null, 2026, null, snapshotWeek, 10000,
                "shrunk-normal-v1", null, List.of(), latest, latest);
    }

    @Test
    void anAvailableForecastCarriesTheSnapshotWeekAndTheLatestScoredWeek() {
        Map<String, Object> body = SeasonForecastController.body(available(1, 2), false);
        assertEquals(1, body.get("week"));
        assertEquals(2, body.get("latestScoredWeek"));
    }

    @Test
    void aRefusalStillCarriesTheLatestScoredWeek() {
        var f = new PlayoffOddsService.Forecast(false, PlayoffOddsService.Unavailable.NOT_COMPUTED,
                2026, null, 0, 0, null, null, List.of(), 3, 2);
        Map<String, Object> body = SeasonForecastController.body(f, true);
        assertEquals("NOT_COMPUTED", body.get("reason"));
        assertEquals(3, body.get("latestScoredWeek"));
        assertEquals(2, body.get("latestFinalWeek"), "final can trail stored: week 3 is in progress");
    }

    /** Display flag only; the route's own gates (admin token, identity) are tested elsewhere. */
    @Test
    void canCommissionIsStatedRatherThanInferred() {
        assertEquals(true, SeasonForecastController.body(available(2, 2), true).get("canCommission"));
        assertEquals(false, SeasonForecastController.body(available(2, 2), false).get("canCommission"));
    }
}
