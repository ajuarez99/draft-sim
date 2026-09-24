package com.ballknowers.draftsim.engine;

import com.ballknowers.draftsim.engine.WaiverPickupAttribution.PlayerContribution;
import com.ballknowers.draftsim.engine.WaiverPickupAttribution.RosterTotal;
import com.ballknowers.draftsim.engine.WaiverPickupAttribution.StarterWeek;
import com.ballknowers.draftsim.store.LeagueTransactionRepository.Row;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/**
 * WAIVER_WIRE_WARRIOR rule tests (specs/008-season-superlatives T037), pure
 * and in-memory -- no Postgres. Each test is one case from research R8 and
 * the task list: only a starter whose most recent arrival on his roster was
 * a completed WAIVER or FREE_AGENT add counts.
 */
class WaiverPickupAttributionTest {

    private static final long LEAGUE = 1L;
    private static final int SEASON = 2026;

    private static Row tx(int week, String txId, String type, String status, Integer rosterId,
                          String addsJson, Instant createdAt) {
        return new Row(LEAGUE, SEASON, week, txId, type, status, rosterId, null, addsJson, "{}", null, createdAt);
    }

    private static StarterWeek starters(int week, int rosterId, Map<String, Double> playersPoints,
                                        String... starterIds) {
        return new StarterWeek(week, rosterId, Set.of(starterIds), playersPoints);
    }

    // ---------------------------------------------------------------- cases

    @Test
    void draftedPlayerStartedIsNotCounted() {
        // No add transaction at all for player "100" on roster 1 -- he was drafted.
        List<Row> txs = List.of();
        List<StarterWeek> weeks = List.of(starters(1, 1, Map.of("100", 20.0), "100"));

        Map<Integer, RosterTotal> result = WaiverPickupAttribution.attribute(txs, weeks);

        assertTrue(result.isEmpty(), "no counted pickup, so no roster total at all");
    }

    @Test
    void waiverAddThenStartedIsCounted() {
        List<Row> txs = List.of(
                tx(1, "t1", "WAIVER", "complete", 1, "{\"100\":1}", Instant.parse("2026-09-01T00:00:00Z")));
        List<StarterWeek> weeks = List.of(starters(2, 1, Map.of("100", 15.0), "100"));

        Map<Integer, RosterTotal> result = WaiverPickupAttribution.attribute(txs, weeks);

        RosterTotal total = result.get(1);
        assertNotNull(total);
        assertEquals(15.0, total.totalPoints(), 1e-9);
        assertEquals(1, total.contributions().size());
        PlayerContribution pc = total.contributions().get(0);
        assertEquals("100", pc.playerId());
        assertEquals(1, pc.addedWeek());
        assertEquals("WAIVER", pc.addType());
        assertEquals(List.of(2), pc.startedWeeks());
    }

    @Test
    void freeAgentAddThenStartedIsCounted() {
        List<Row> txs = List.of(
                tx(1, "t1", "FREE_AGENT", "complete", 1, "{\"100\":1}", Instant.parse("2026-09-01T00:00:00Z")));
        List<StarterWeek> weeks = List.of(starters(1, 1, Map.of("100", 12.5), "100"));

        Map<Integer, RosterTotal> result = WaiverPickupAttribution.attribute(txs, weeks);

        assertEquals(12.5, result.get(1).totalPoints(), 1e-9);
        assertEquals("FREE_AGENT", result.get(1).contributions().get(0).addType());
    }

    @Test
    void waiverAddLaterTradedAndStartedElsewhereIsNotCountedForEitherTeam() {
        List<Row> txs = List.of(
                tx(1, "t1", "WAIVER", "complete", 1, "{\"100\":1}", Instant.parse("2026-09-01T00:00:00Z")),
                tx(3, "t2", "TRADE", "complete", null, "{\"100\":2}", Instant.parse("2026-09-15T00:00:00Z")));
        // Player started by roster 2 (the new team) in week 4, after the trade.
        List<StarterWeek> weeks = List.of(starters(4, 2, Map.of("100", 18.0), "100"));

        Map<Integer, RosterTotal> result = WaiverPickupAttribution.attribute(txs, weeks);

        assertTrue(result.isEmpty(), "the most recent arrival on roster 2 is a TRADE, which never counts");
    }

    @Test
    void dropAndReAddBySameTeamCountsOncePerStartedWeekNeverTwice() {
        List<Row> txs = List.of(
                tx(1, "t1", "WAIVER", "complete", 1, "{\"100\":1}", Instant.parse("2026-09-01T00:00:00Z")),
                tx(2, "t2", "FREE_AGENT", "complete", null, "{}", Instant.parse("2026-09-08T00:00:00Z")), // the drop, no adds
                tx(3, "t3", "WAIVER", "complete", 1, "{\"100\":1}", Instant.parse("2026-09-15T00:00:00Z")));
        // Started once during the first stint (week 1) and once during the second (week 3).
        List<StarterWeek> weeks = List.of(
                starters(1, 1, Map.of("100", 10.0), "100"),
                starters(3, 1, Map.of("100", 20.0), "100"));

        Map<Integer, RosterTotal> result = WaiverPickupAttribution.attribute(txs, weeks);

        RosterTotal total = result.get(1);
        assertEquals(30.0, total.totalPoints(), 1e-9, "both stints' started weeks count, summed once each");
        assertEquals(1, total.contributions().size(), "one merged contribution row for this player/roster");
        assertEquals(List.of(1, 3), total.contributions().get(0).startedWeeks());
        assertEquals(1, total.contributions().get(0).addedWeek(), "the earliest counted stint's add week");
    }

    @Test
    void commissionerMoveIsNotCounted() {
        List<Row> txs = List.of(
                tx(1, "t1", "COMMISSIONER", "complete", 1, "{\"100\":1}", Instant.parse("2026-09-01T00:00:00Z")));
        List<StarterWeek> weeks = List.of(starters(1, 1, Map.of("100", 9.0), "100"));

        Map<Integer, RosterTotal> result = WaiverPickupAttribution.attribute(txs, weeks);

        assertTrue(result.isEmpty());
    }

    @Test
    void failedWaiverBidIsNotCounted() {
        List<Row> txs = List.of(
                tx(1, "t1", "WAIVER", "failed", 1, "{\"100\":1}", Instant.parse("2026-09-01T00:00:00Z")));
        List<StarterWeek> weeks = List.of(starters(1, 1, Map.of("100", 9.0), "100"));

        Map<Integer, RosterTotal> result = WaiverPickupAttribution.attribute(txs, weeks);

        assertTrue(result.isEmpty(), "status != complete never counts (research R8)");
    }

    @Test
    void addRecordedInWeekWAndStartedInWeekWIsCounted() {
        List<Row> txs = List.of(
                tx(3, "t1", "WAIVER", "complete", 1, "{\"100\":1}", Instant.parse("2026-09-20T00:00:00Z")));
        List<StarterWeek> weeks = List.of(starters(3, 1, Map.of("100", 22.0), "100"));

        Map<Integer, RosterTotal> result = WaiverPickupAttribution.attribute(txs, weeks);

        assertEquals(22.0, result.get(1).totalPoints(), 1e-9, "week <= w includes week == w");
    }

    // ------------------------------------------------------------- ordering

    @Test
    void theRosterWithMorePickupPointsHasTheHigherTotal() {
        List<Row> txs = List.of(
                tx(1, "t1", "WAIVER", "complete", 1, "{\"100\":1}", Instant.parse("2026-09-01T00:00:00Z")),
                tx(1, "t2", "WAIVER", "complete", 2, "{\"200\":2}", Instant.parse("2026-09-01T00:00:00Z")));
        List<StarterWeek> weeks = List.of(
                starters(2, 1, Map.of("100", 10.0), "100"),
                starters(2, 2, Map.of("200", 40.0), "200"));

        Map<Integer, RosterTotal> result = WaiverPickupAttribution.attribute(txs, weeks);

        assertTrue(result.get(2).totalPoints() > result.get(1).totalPoints());
    }
}
