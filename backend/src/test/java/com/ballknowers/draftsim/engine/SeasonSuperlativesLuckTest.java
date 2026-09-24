package com.ballknowers.draftsim.engine;

import com.ballknowers.draftsim.engine.SeasonSuperlativesService.BenchAgg;
import com.ballknowers.draftsim.engine.SeasonSuperlativesService.BenchWeekEntry;
import com.ballknowers.draftsim.engine.SeasonSuperlativesService.Kind;
import com.ballknowers.draftsim.engine.SeasonSuperlativesService.LuckDetail;
import com.ballknowers.draftsim.engine.SeasonSuperlativesService.Superlative;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Ordering tests for LUCKIEST/UNLUCKIEST and MOST_BENCH_POINTS
 * (specs/008-season-superlatives T031), run against in-memory fixtures so
 * they need no Postgres -- exactly the AGENTS.md recurring bug #1 class:
 * structural tests don't catch a sign inversion, only a test that asserts
 * preference ordering does.
 */
class SeasonSuperlativesLuckTest {

    private static ExpectedWinsService.TeamRow team(int rosterId, double winsAboveExpected) {
        return new ExpectedWinsService.TeamRow(rosterId, (long) rosterId, "Team " + rosterId, null,
                1.0, 1.0 + winsAboveExpected, winsAboveExpected, 0.0,
                ExpectedWinsService.LuckSource.CONSISTENT_OPPONENT_SCORING, List.of());
    }

    // ------------------------------------------------------------- (a)

    @Test
    void luckiestIsTheMaxAndUnluckiestIsTheMinNeverSwapped() {
        List<ExpectedWinsService.TeamRow> teams = List.of(
                team(1, -1.5), team(2, 0.2), team(3, 2.4));

        Superlative luckiest = SeasonSuperlativesService.luckSuperlative(Kind.LUCKIEST, teams, true, false, 6);
        Superlative unluckiest = SeasonSuperlativesService.luckSuperlative(Kind.UNLUCKIEST, teams, false, false, 6);

        assertEquals(2.4, luckiest.value(), 1e-9);
        assertEquals(1, luckiest.holders().size());
        assertEquals(3, luckiest.holders().get(0).rosterId(), "the max WAE team, not the min");

        assertEquals(-1.5, unluckiest.value(), 1e-9);
        assertEquals(1, unluckiest.holders().size());
        assertEquals(1, unluckiest.holders().get(0).rosterId(), "the min WAE team, not the max");
    }

    // ------------------------------------------------------------- (b)

    @Test
    void tiesAtTheExtremeNameAllHolders() {
        List<ExpectedWinsService.TeamRow> teams = List.of(
                team(1, 2.0), team(2, 0.0), team(3, 2.0));

        Superlative luckiest = SeasonSuperlativesService.luckSuperlative(Kind.LUCKIEST, teams, true, false, 6);

        assertEquals(2, luckiest.holders().size());
        assertEquals(List.of(1, 3), luckiest.holders().stream().map(h -> h.rosterId()).sorted().toList());
        assertEquals(2, luckiest.detail().size(), "each tied holder gets its own LUCK detail row");
        for (var d : luckiest.detail()) {
            assertInstanceOf(LuckDetail.class, d);
        }
    }

    // ------------------------------------------------------------- (c)

    @Test
    void mostBenchPointsPicksTheLargestSummedGap() {
        List<BenchWeekEntry> entries = List.of(
                new BenchWeekEntry(1, 10, true, 12.0),
                new BenchWeekEntry(2, 10, true, 9.0),   // roster 10 total: 21.0
                new BenchWeekEntry(1, 20, true, 5.0),
                new BenchWeekEntry(2, 20, true, 5.0));  // roster 20 total: 10.0

        Map<Integer, BenchAgg> byRoster = SeasonSuperlativesService.aggregateBench(entries);

        assertEquals(21.0, byRoster.get(10).pointsLeft(), 1e-9,
                "a team that left more points on the bench outranks one that left fewer");
        assertEquals(10.0, byRoster.get(20).pointsLeft(), 1e-9);
        assertTrue(byRoster.get(10).pointsLeft() > byRoster.get(20).pointsLeft());
    }

    // ------------------------------------------------------------- (d)

    @Test
    void anInvalidLineupWeekReportsFewerWeeksCountedThanWeeksScored() {
        List<BenchWeekEntry> entries = List.of(
                new BenchWeekEntry(1, 30, true, 4.0),
                new BenchWeekEntry(2, 30, false, 0.0),  // no usable breakdown this week
                new BenchWeekEntry(3, 30, true, 6.0));

        Map<Integer, BenchAgg> byRoster = SeasonSuperlativesService.aggregateBench(entries);

        int weeksScored = 3;
        assertTrue(byRoster.get(30).weeksCounted() < weeksScored,
                "the invalid week must not be counted as a scored week");
        assertEquals(2, byRoster.get(30).weeksCounted());
        assertEquals(10.0, byRoster.get(30).pointsLeft(), 1e-9, "the invalid week's 0.0 must not be summed in either");
    }
}
