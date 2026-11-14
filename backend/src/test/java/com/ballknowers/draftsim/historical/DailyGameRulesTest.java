package com.ballknowers.draftsim.historical;

import com.ballknowers.draftsim.historical.HistoricalDataRepository.SeasonRow;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Spec 071: the three pure rules behind the game of the day. No Spring, no database.
 */
class DailyGameRulesTest {

    // ---- DraftScore: the same vectors as web/src/historicalShare.test.ts --------------------------------

    @Test
    void theDraftScoreIsYourShareOfHindsightRoundedDown() {
        // 2479.0 / 3108.7 = 79.74%: the web says 79, and so does this.
        assertEquals(79, DraftScore.of(2479.0, 3108.7));
    }

    @Test
    void aBetterDraftScoresHigherAndBeatingHindsightGoesPastAHundred() {
        assertTrue(DraftScore.of(2700.0, 3000.0) > DraftScore.of(1500.0, 3000.0),
                "more points against the same hindsight total must be the higher score");
        assertEquals(110, DraftScore.of(3300.0, 3000.0));
    }

    @Test
    void thereIsNoDraftScoreWithoutAHindsightTotalToDivideBy() {
        assertNull(DraftScore.of(100.0, 0.0));
        assertNull(DraftScore.of(100.0, null));
        assertNull(DraftScore.of(null, 3000.0));
    }

    // ---- DailyStreak ------------------------------------------------------------------------------------

    private static final LocalDate TODAY = LocalDate.of(2026, 11, 14);

    private static List<LocalDate> daysAgo(int... n) {
        List<LocalDate> out = new ArrayList<>();
        for (int d : n) out.add(TODAY.minusDays(d));
        return out;
    }

    @Test
    void aRunEndingTodayCountsEveryDayInIt() {
        assertEquals(new DailyStreak(3, 3), DailyStreak.of(daysAgo(0, 1, 2), TODAY));
    }

    @Test
    void notHavingPlayedYetTodayDoesNotBreakARunThatReachedYesterday() {
        assertEquals(new DailyStreak(2, 2), DailyStreak.of(daysAgo(1, 2), TODAY));
    }

    @Test
    void aMissedDayEndsTheRunButTheBestRunIsKept() {
        // Played 6, 5, 4, 3 days ago, missed 2 days ago, played yesterday and today.
        assertEquals(new DailyStreak(2, 4), DailyStreak.of(daysAgo(0, 1, 3, 4, 5, 6), TODAY));
        // Last finished two days ago: the run is over.
        assertEquals(new DailyStreak(0, 2), DailyStreak.of(daysAgo(2, 3), TODAY));
    }

    @Test
    void noFinishedGamesIsNoStreakAndRepeatsOfADayCountOnce() {
        assertEquals(DailyStreak.NONE, DailyStreak.of(List.of(), TODAY));
        assertEquals(new DailyStreak(1, 1), DailyStreak.of(List.of(TODAY, TODAY), TODAY));
    }

    // ---- DailyPuzzle ------------------------------------------------------------------------------------

    private static SeasonRow season(int start) {
        return new SeasonRow("NFL", start, String.valueOf(start), "NFLVERSE", LocalDate.of(start, 9, 1),
                LocalDate.of(start + 1, 1, 1), 17, 100, List.of("pts"), "{}", "{}", "PRIOR_SEASON_POINTS",
                OffsetDateTime.parse("2026-11-02T12:00:00Z"), null, 0, null);
    }

    private static List<SeasonRow> seasons(int from, int to) {
        List<SeasonRow> out = new ArrayList<>();
        for (int s = from; s <= to; s++) out.add(season(s));
        return out;
    }

    @Test
    void theSameSportAndDayAlwaysDealTheSameSeasonAndSeed() {
        List<SeasonRow> offered = seasons(2000, 2025);
        DailyPuzzle.Deal a = DailyPuzzle.deal("NFL", TODAY, offered, SeasonSkew.NONE, List.of());
        DailyPuzzle.Deal b = DailyPuzzle.deal("NFL", TODAY, offered, SeasonSkew.NONE, List.of());
        assertEquals(a, b);
    }

    @Test
    void differentDaysAndDifferentSportsDealDifferentSeeds() {
        List<SeasonRow> offered = seasons(2000, 2025);
        Set<Long> seeds = new HashSet<>();
        Set<Integer> dealt = new HashSet<>();
        for (int d = 0; d < 60; d++) {
            DailyPuzzle.Deal deal = DailyPuzzle.deal("NFL", TODAY.plusDays(d), offered, SeasonSkew.NONE, List.of());
            seeds.add(deal.seed());
            dealt.add(deal.seasonStart());
        }
        assertEquals(60, seeds.size(), "sixty days, sixty seeds");
        assertTrue(dealt.size() > 10, "sixty days over 26 seasons landed on only " + dealt.size() + " of them");
        assertNotEquals(DailyPuzzle.deal("NFL", TODAY, offered, SeasonSkew.NONE, List.of()).seed(),
                DailyPuzzle.deal("NBA", TODAY, offered, SeasonSkew.NONE, List.of()).seed());
    }

    @Test
    void aSeasonUsedOnARecentDayIsSkippedWhileAnotherIsLeft() {
        List<SeasonRow> offered = seasons(2000, 2003);
        for (int d = 0; d < 40; d++) {
            DailyPuzzle.Deal deal = DailyPuzzle.deal("NFL", TODAY.plusDays(d), offered, SeasonSkew.NONE,
                    List.of(2000, 2001, 2002));
            assertEquals(2003, deal.seasonStart(), "the only season not used recently");
        }
    }

    @Test
    void whenEverySeasonWasUsedRecentlyOneIsDealtAnyway() {
        List<SeasonRow> offered = seasons(2000, 2001);
        DailyPuzzle.Deal deal = DailyPuzzle.deal("NFL", TODAY, offered, SeasonSkew.NONE, List.of(2000, 2001));
        assertTrue(deal.seasonStart() == 2000 || deal.seasonStart() == 2001);
    }
}
