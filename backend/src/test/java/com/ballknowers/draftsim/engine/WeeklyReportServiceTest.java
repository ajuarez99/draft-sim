package com.ballknowers.draftsim.engine;

import org.junit.jupiter.api.Test;

import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The pure half of the weekly report: reading Sleeper's starters list, which is
 * what decides whether an award can be computed at all
 * (specs/004-ffwrapped-feature-parity, US5). The repository walk is left to an
 * integration test, per the split {@code LeagueAnalysisServiceTest} describes.
 */
class WeeklyReportServiceTest {

    /**
     * US5.4. A week ingested before V18 has no starters list, and the empty set
     * is what makes the affected award omit itself with a reason instead of
     * guessing. "Not stored" and "nobody started" must not look alike here.
     */
    @Test
    void anAbsentStartersListReadsAsEmptyRatherThanThrowing() {
        assertTrue(WeeklyReportService.startersOf(null).isEmpty());
        assertTrue(WeeklyReportService.startersOf("").isEmpty());
        assertTrue(WeeklyReportService.startersOf("   ").isEmpty());
    }

    /** Malformed JSON is treated as absent, not as a crash on a page load. */
    @Test
    void malformedStartersJsonIsTreatedAsAbsent() {
        assertTrue(WeeklyReportService.startersOf("{not json").isEmpty());
    }

    @Test
    void aStartersListIsReadInOrder() {
        Set<String> s = WeeklyReportService.startersOf("[\"4034\",\"6794\",\"5849\"]");
        assertEquals(3, s.size());
        assertEquals(java.util.List.of("4034", "6794", "5849"), java.util.List.copyOf(s));
    }

    /**
     * Sleeper writes "0" for an empty starting slot -- a seat nobody was put
     * in. Counting it as a started player would put a phantom on the board and,
     * worse, let it win an award.
     */
    @Test
    void emptySlotsAreNotPlayers() {
        Set<String> s = WeeklyReportService.startersOf("[\"4034\",\"0\",\"5849\",\"0\"]");
        assertEquals(Set.of("4034", "5849"), s);
    }

    /** Numeric ids (Sleeper sends strings, but a fixture may not) still read. */
    @Test
    void numericIdsAreAccepted() {
        assertEquals(Set.of("4034", "5849"), WeeklyReportService.startersOf("[4034,5849]"));
    }

    /** The real NBA payload's nine starters, straight from the stored fixture. */
    @Test
    void theRealBasketballStartersListReadsAsNinePlayers() {
        String json = "[\"2084\",\"2285\",\"2144\",\"2836\",\"1380\",\"2480\",\"2733\",\"2455\",\"2259\"]";
        assertEquals(9, WeeklyReportService.startersOf(json).size(),
                "basketball starts nine, and this is the shape Sleeper actually sent");
    }

    // ---- specs/005-daily-weekly-top-players: the two ranking rules ----

    private static WeeklyReportService.NightPerformance night(String id, double pts, String date) {
        return new WeeklyReportService.NightPerformance(id, "P" + id, "C", "Team", pts,
                java.time.LocalDate.parse(date), "CHI", false);
    }

    private static WeeklyReportService.PlayerWeek pweek(String id, double total, int games) {
        return new WeeklyReportService.PlayerWeek(id, "P" + id, "C", "Team", total, games);
    }

    /** US1: highest single game first. */
    @Test
    void bestNightsRanksByPointsDescending() {
        var ranked = WeeklyReportService.rankNights(java.util.List.of(
                night("a", 34.0, "2025-11-19"),
                night("b", 58.5, "2025-11-17"),
                night("c", 44.0, "2025-11-21")), 5);

        assertEquals(java.util.List.of("b", "c", "a"),
                ranked.stream().map(WeeklyReportService.NightPerformance::playerId).toList());
    }

    /**
     * FR-009. Half-point scoring makes exact ties ordinary, and an unstable
     * order would reorder a finished week between two loads -- which reads as
     * the data changing under the reader.
     */
    @Test
    void bestNightsBreaksExactTiesDeterministically() {
        var input = java.util.List.of(
                night("zeta", 44.0, "2025-11-21"),
                night("alpha", 44.0, "2025-11-22"),
                night("alpha", 44.0, "2025-11-20"));

        var first = WeeklyReportService.rankNights(input, 5);
        var second = WeeklyReportService.rankNights(input, 5);

        assertEquals(first, second, "two rankings of one week must not disagree");
        // player id first, then date -- one player can hold two rows here.
        assertEquals(java.util.List.of("alpha", "alpha", "zeta"),
                first.stream().map(WeeklyReportService.NightPerformance::playerId).toList());
        assertEquals("2025-11-20", first.get(0).date().toString());
    }

    @Test
    void bestWeekRanksByTotalAndBreaksTiesByPlayer() {
        var ranked = WeeklyReportService.rankWeeks(java.util.List.of(
                pweek("zeta", 121.0, 4),
                pweek("alpha", 182.0, 4),
                pweek("mid", 121.0, 3)), 5);

        assertEquals(java.util.List.of("alpha", "mid", "zeta"),
                ranked.stream().map(WeeklyReportService.PlayerWeek::playerId).toList());
        assertEquals(WeeklyReportService.rankWeeks(java.util.List.of(
                pweek("zeta", 121.0, 4), pweek("alpha", 182.0, 4), pweek("mid", 121.0, 3)), 5), ranked);
    }

    /** Both rankings cut to the same length, so the pair stays visually balanced. */
    @Test
    void bothRankingsRespectTheLimit() {
        var many = new java.util.ArrayList<WeeklyReportService.NightPerformance>();
        for (int i = 0; i < 40; i++) many.add(night("p" + i, 60.0 - i, "2025-11-17"));
        assertEquals(5, WeeklyReportService.rankNights(many, 5).size());

        var manyWeeks = new java.util.ArrayList<WeeklyReportService.PlayerWeek>();
        for (int i = 0; i < 40; i++) manyWeeks.add(pweek("p" + i, 200.0 - i, 4));
        assertEquals(5, WeeklyReportService.rankWeeks(manyWeeks, 5).size());
    }

    /** An empty week ranks to empty rather than throwing on a page load. */
    @Test
    void emptyInputRanksToEmpty() {
        assertTrue(WeeklyReportService.rankNights(java.util.List.of(), 5).isEmpty());
        assertTrue(WeeklyReportService.rankWeeks(java.util.List.of(), 5).isEmpty());
    }
}
