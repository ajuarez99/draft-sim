package com.ballknowers.draftsim.api;

import com.ballknowers.draftsim.domain.Sport;
import com.ballknowers.draftsim.engine.WeeklyReportService;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The response shape from contracts/weekly-report-sections.md, asserted against
 * the map the controller actually builds
 * (specs/005-daily-weekly-top-players, T023/T046).
 *
 * <p><b>Why this class exists, specifically.</b> The service returns a record
 * with null for the inapplicable side, and that record carried a
 * {@code @JsonInclude(NON_NULL)} annotation which looked like it produced the
 * contract's "absent, not empty" rule. It did not: this controller hand-builds
 * a {@code LinkedHashMap}, so the annotation was decorative, the new fields
 * were never emitted at all, and the football response silently lost
 * {@code playersPlayMultiplePerPeriod}. Worse, the basketball path would have
 * thrown, because the old code looped over a null {@code topPerformers}.
 *
 * <p>Every test in the suite passed throughout. Only a request to a running
 * server found it. These assertions are that request, made cheap enough to run
 * every time and without a database.
 */
class WeeklyReportShapeTest {

    private static WeeklyReportService.Result football() {
        return new WeeklyReportService.Result(
                true, null, 2026, null, 1, Sport.NFL, false,
                List.of(),
                List.of(new WeeklyReportService.Performer("4034", "Caleb Williams", "QB", "Team", 37.26)),
                null, null, null, null,
                List.of(), List.of());
    }

    private static WeeklyReportService.Result basketball() {
        return new WeeklyReportService.Result(
                true, null, 2025, null, 5, Sport.NBA, true,
                List.of(),
                null,
                List.of(new WeeklyReportService.NightPerformance("1658", "Nikola Jokic", "C",
                        "FentMachines5:SoFkingOver", 58.5, LocalDate.parse("2025-11-17"), "CHI", false)),
                List.of(new WeeklyReportService.PlayerWeek("1658", "Nikola Jokic", "C",
                        "FentMachines5:SoFkingOver", 182.0, 4)),
                WeeklyReportService.BASIS_ALL_GAMES,
                List.of(),
                List.of(), List.of());
    }

    /** Contract assertion 1: football's shape is unchanged (SC-004). */
    @Test
    void footballCarriesTopPerformersAndNeitherNewSection() {
        Map<String, Object> body = WeeklyReportController.body(football());

        assertTrue(body.containsKey("topPerformers"));
        assertFalse(body.containsKey("bestNights"), "bestNights must be ABSENT for football, not empty");
        assertFalse(body.containsKey("bestWeek"), "bestWeek must be ABSENT for football, not empty");
        assertFalse(body.containsKey("basis"));
        assertEquals(false, body.get("playersPlayMultiplePerPeriod"));
    }

    /** Contract assertion 2: basketball gets the pair and loses the single list. */
    @Test
    void basketballCarriesThePairAndNotTopPerformers() {
        Map<String, Object> body = WeeklyReportController.body(basketball());

        assertTrue(body.containsKey("bestNights"));
        assertTrue(body.containsKey("bestWeek"));
        assertFalse(body.containsKey("topPerformers"),
                "three overlapping rankings of one week is not a richer page");
        assertEquals(true, body.get("playersPlayMultiplePerPeriod"));
    }

    /**
     * The field whose absence started this class. Stated on both shapes so the
     * client renders from a fact rather than inferring the sport's rules from
     * which arrays happen to be populated.
     */
    @Test
    void theCadenceFlagIsAlwaysPresent() {
        assertTrue(WeeklyReportController.body(football()).containsKey("playersPlayMultiplePerPeriod"));
        assertTrue(WeeklyReportController.body(basketball()).containsKey("playersPlayMultiplePerPeriod"));
    }

    /** Contract assertion 5: basis is required on the pair, never defaulted. */
    @Test
    void basisIsStatedOnThePairShape() {
        assertEquals("ALL_GAMES_PLAYED", WeeklyReportController.body(basketball()).get("basis"));
    }

    /** A night carries the night: date and opponent, or an honest null. */
    @SuppressWarnings("unchecked")
    @Test
    void aNightCarriesItsDateAndOpponent() {
        var nights = (List<Map<String, Object>>) WeeklyReportController.body(basketball()).get("bestNights");
        assertEquals(1, nights.size());
        assertEquals("2025-11-17", nights.get(0).get("date"));
        assertEquals("CHI", nights.get(0).get("opponent"));
        assertEquals(58.5, (Double) nights.get(0).get("points"), 0.001);
        assertEquals(false, nights.get(0).get("isAway"));
    }

    /** FR-002: a week total never travels without the games it covers. */
    @SuppressWarnings("unchecked")
    @Test
    void aWeekTotalCarriesItsGameCount() {
        var weeks = (List<Map<String, Object>>) WeeklyReportController.body(basketball()).get("bestWeek");
        assertEquals(182.0, (Double) weeks.get(0).get("totalPoints"), 0.001);
        assertEquals(4, weeks.get(0).get("gamesPlayed"));
    }

    /** An unavailable section names itself and its reason, as a discriminator. */
    @SuppressWarnings("unchecked")
    @Test
    void anUnavailableSectionNamesItselfAndWhy() {
        WeeklyReportService.Result r = new WeeklyReportService.Result(
                true, null, 2025, null, 5, Sport.NBA, true, List.of(), null,
                List.of(), List.of(), WeeklyReportService.BASIS_ALL_GAMES,
                List.of(new WeeklyReportService.SectionUnavailable("BEST_WEEK", "PER_GAME_DETAIL_MISSING")),
                List.of(), List.of());

        var gaps = (List<Map<String, Object>>) WeeklyReportController.body(r).get("sectionsUnavailable");
        assertEquals(1, gaps.size());
        assertEquals("BEST_WEEK", gaps.get(0).get("section"));
        assertEquals("PER_GAME_DETAIL_MISSING", gaps.get(0).get("reason"));
    }

    /** An unscored week still answers, and still states the cadence. */
    @Test
    void anUnavailableWeekStillStatesTheCadence() {
        // Built directly rather than via Result.unavailable, which is
        // package-private to the engine -- the shape is what matters here.
        Map<String, Object> body = WeeklyReportController.body(new WeeklyReportService.Result(
                false, "week 9 has not been scored", 2025, null, 9, Sport.NBA, false,
                List.of(), List.of(), null, null, null, null, List.of(), List.of()));

        assertEquals(false, body.get("available"));
        assertTrue(body.containsKey("reason"));
        assertTrue(body.containsKey("playersPlayMultiplePerPeriod"));
    }
}
