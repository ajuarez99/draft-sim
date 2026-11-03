package com.ballknowers.draftsim.engine;

import com.ballknowers.draftsim.store.LeagueMatchupRepository.PairedGame;
import com.ballknowers.draftsim.store.LeagueRepository.PlayoffFormat;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

/**
 * {@link WeeklyReportService#recordsThrough}: the weekly report's W-L counts the median game when the
 * league plays one (claude/nba-2026-score-drift.md). Four rosters, 1 v 2 and 3 v 4 every week, so each
 * week's median is the mean of the middle two scores.
 */
class WeeklyReportRecordTest {

    private static PairedGame game(int season, int week, int a, double ap, int b, double bp) {
        return new PairedGame(season, week, a, null, null, null, BigDecimal.valueOf(ap),
                b, null, null, null, BigDecimal.valueOf(bp));
    }

    private static Optional<PlayoffFormat> format(boolean median, int playoffWeekStart) {
        return Optional.of(new PlayoffFormat(2, playoffWeekStart, 0, false, median, 0));
    }

    // week 1: 1=120 beats 2=100, 3=90 beats 4=80 -> median 95: 1 and 2 above, 3 and 4 below
    // week 2: 1=130 beats 2=60,  3=110 beats 4=70 -> median 90: 1 and 3 above, 2 and 4 below
    private static final List<PairedGame> TWO_WEEKS = List.of(
            game(2026, 1, 1, 120, 2, 100), game(2026, 1, 3, 90, 4, 80),
            game(2026, 2, 1, 130, 2, 60), game(2026, 2, 3, 110, 4, 70));

    @Test
    void medianOn_eachWeekIsTwoGames() {
        Map<Integer, String> r = WeeklyReportService.recordsThrough(TWO_WEEKS, 2026, 2, format(true, 20));
        assertEquals("4-0", r.get(1));
        assertEquals("1-3", r.get(2), "lost both h2h, beat the median once");
        assertEquals("3-1", r.get(3), "won both h2h, under the median in week 1");
        assertEquals("0-4", r.get(4));
    }

    @Test
    void medianOff_headToHeadOnly() {
        Map<Integer, String> r = WeeklyReportService.recordsThrough(TWO_WEEKS, 2026, 2, format(false, 20));
        assertEquals("2-0", r.get(1));
        assertEquals("0-2", r.get(2));
        assertEquals("2-0", r.get(3));
        assertEquals("0-2", r.get(4));
    }

    @Test
    void noFormatStored_headToHeadOnly() {
        assertEquals("2-0", WeeklyReportService.recordsThrough(TWO_WEEKS, 2026, 2, Optional.empty()).get(1));
    }

    @Test
    void onlyWeeksThroughTheRequestedOne_andOnlyThisSeason() {
        List<PairedGame> games = new java.util.ArrayList<>(TWO_WEEKS);
        games.add(game(2025, 1, 1, 10, 2, 200));
        Map<Integer, String> r = WeeklyReportService.recordsThrough(games, 2026, 1, format(true, 20));
        assertEquals("2-0", r.get(1));
        assertEquals("1-1", r.get(2), "week 1 only: lost h2h, beat the median");
    }

    @Test
    void playoffWeeksHaveNoMedianGame() {
        // playoffs start week 2: week 2 counts head-to-head only
        Map<Integer, String> r = WeeklyReportService.recordsThrough(TWO_WEEKS, 2026, 2, format(true, 2));
        assertEquals("3-0", r.get(1));
        assertEquals("1-2", r.get(2));
        assertEquals("2-1", r.get(3));
        assertEquals("0-3", r.get(4));
    }

    @Test
    void tieAtTheMedianIsATie() {
        // 1=100 v 2=100 (h2h tie), 3=100 v 4=50 -> median 100: 1, 2, 3 tie it, 4 under
        List<PairedGame> games = List.of(game(2026, 1, 1, 100, 2, 100), game(2026, 1, 3, 100, 4, 50));
        Map<Integer, String> r = WeeklyReportService.recordsThrough(games, 2026, 1, format(true, 20));
        assertEquals("0-0-2", r.get(1));
        assertEquals("1-0-1", r.get(3));
        assertEquals("0-2", r.get(4));
    }
}
