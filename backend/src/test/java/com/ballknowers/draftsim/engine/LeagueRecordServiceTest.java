package com.ballknowers.draftsim.engine;

import com.ballknowers.draftsim.store.LeagueMatchupRepository;
import com.ballknowers.draftsim.store.RosterWeekPointsRepository;
import com.ballknowers.draftsim.store.WeekBound;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * specs/002-league-history-record-book, data-model rules R1-R4.
 *
 * <p>Every case here is one where the service would happily return a list and
 * the list would be wrong rather than absent -- a dropped record, a silent
 * zero, two lists of different lengths. None of them throws, so nothing but a
 * test catches them.
 */
@ExtendWith(MockitoExtension.class)
class LeagueRecordServiceTest {

    @Mock private RosterWeekPointsRepository weekPoints;
    @Mock private LeagueMatchupRepository fixtures;

    private LeagueRecordService service() {
        return new LeagueRecordService(weekPoints, fixtures);
    }

    private static RosterWeekPointsRepository.ScoreRow row(int season, int week, int rosterId,
                                                           Long managerId, String manager, String points) {
        return new RosterWeekPointsRepository.ScoreRow(season, week, rosterId, managerId, manager,
                managerId == null ? null : "avatar" + managerId, new BigDecimal(points));
    }

    /**
     * R1 -- an unowned roster-season still produces a record.
     *
     * <p>Sleeper leaves a roster unowned when someone leaves mid-season, and
     * roster_season.manager_id is then null. Dropping the row would silently
     * edit the record book: the highest week in league history could simply
     * vanish because the person who posted it quit.
     */
    @Test
    void unownedRosterStillProducesARecord() {
        when(weekPoints.extremes(anySet(), eq(true), anyInt(), any()))
                .thenReturn(List.of(row(2025, 8, 7, null, null, "205.04")));

        List<LeagueRecordService.WeeklyScoreRecord> out = service().highestWeeks(Set.of(5L), 10, WeekBound.ALL_WEEKS);

        assertEquals(1, out.size(), "an unowned roster-season must not be dropped");
        assertEquals(7, out.get(0).rosterId(), "rosterId is always present, so the client can render something");
        assertNull(out.get(0).managerId());
        assertNull(out.get(0).manager());
        assertNull(out.get(0).avatarId());
        assertEquals(new BigDecimal("205.04"), out.get(0).points());
    }

    /**
     * R3 -- a season with no stored weeks contributes nothing, and in
     * particular never contributes a 0.00.
     *
     * <p>This DB holds exactly that case: the NBA 2026 league is ingested and
     * has zero roster_week_points rows. A record book that invented a 0.00 low
     * score for it would be reporting a game nobody played. The standings table
     * on this same page already learned this lesson for a season with no
     * standings; the record book must not relearn it.
     */
    @Test
    void seasonWithNoStoredWeeksContributesNothingRatherThanAZero() {
        when(weekPoints.extremes(anySet(), anyBoolean(), anyInt(), any())).thenReturn(List.of());

        LeagueRecordService.RecordBook book = service().forChain(Set.of(210L), WeekBound.ALL_WEEKS);

        assertTrue(book.highestWeeks().isEmpty());
        assertTrue(book.lowestWeeks().isEmpty(), "an empty season must not produce a 0.00 low record");
    }

    /**
     * R4 -- the high list and the low list are bounded by the same limit.
     *
     * <p>Two lists shown side by side that quietly stop at different depths
     * invite the reader to compare a top-10 against a top-5.
     */
    @Test
    void bothScoreListsAreBoundedByTheSameLimit() {
        service().forChain(Set.of(5L, 4L), 7, WeekBound.ALL_WEEKS);

        verify(weekPoints).extremes(anySet(), eq(true), eq(7), any());
        verify(weekPoints).extremes(anySet(), eq(false), eq(7), any());
    }

    /**
     * The chain, not the head. league.id is a league-SEASON in this schema, so
     * asking with one id answers "this season" -- the question the page could
     * already answer. FR-001 is about every season.
     */
    @Test
    void queriesTheWholeChainRatherThanOneSeason() {
        service().forChain(List.of(4L, 5L), 10, WeekBound.ALL_WEEKS);

        verify(weekPoints).extremes(argThat(ids -> ids.containsAll(List.of(4L, 5L))), eq(true), anyInt(), any());
    }

    /**
     * FR-010 -- an empty margins panel carries a reason, and a populated one
     * does not. The client renders whichever is present, so exactly one of them
     * must be.
     */
    @Test
    void emptyMarginsCarryAReasonAndPopulatedMarginsDoNot() {
        when(weekPoints.extremes(anySet(), anyBoolean(), anyInt(), any())).thenReturn(List.of());
        when(fixtures.pairedWithScores(anySet(), any())).thenReturn(List.of());

        LeagueRecordService.RecordBook empty = service().forChain(Set.of(5L), WeekBound.ALL_WEEKS);
        assertNotNull(empty.marginsUnavailableReason(), "an empty panel that says nothing reads as broken");

        when(fixtures.pairedWithScores(anySet(), any())).thenReturn(List.of(
                new LeagueMatchupRepository.PairedGame(2025, 17, 3, 12L, "a", null, new BigDecimal("132.58"),
                        9, 18L, "b", null, new BigDecimal("132.42"))));

        LeagueRecordService.RecordBook populated = service().forChain(Set.of(5L), WeekBound.ALL_WEEKS);
        assertFalse(populated.closestMatchups().isEmpty());
        assertNull(populated.marginsUnavailableReason());
    }

    private static LeagueMatchupRepository.PairedGame game(int season, int week, int ra, String pa,
                                                          int rb, String pb) {
        return new LeagueMatchupRepository.PairedGame(season, week,
                ra, (long) ra, "m" + ra, null, new BigDecimal(pa),
                rb, (long) rb, "m" + rb, null, new BigDecimal(pb));
    }

    /**
     * Winner and loser are decided by the two SCORES, not by
     * roster_season.wins. A season's win column is the whole year; this is one
     * game, and the higher score won it.
     */
    @Test
    void winnerIsTheHigherScoreRegardlessOfWhichSideTheRowPutFirst() {
        // Side A is the LOWER score here, so a mapper that trusted row order
        // would report the loser as the winner.
        when(fixtures.pairedWithScores(anySet(), any())).thenReturn(List.of(game(2025, 8, 3, "86.18", 7, "205.04")));

        LeagueRecordService.MarginRecord m = service().closestMatchups(Set.of(5L), 10, WeekBound.ALL_WEEKS).get(0);

        assertEquals(7, m.winner().rosterId());
        assertEquals(3, m.loser().rosterId());
        assertEquals(new BigDecimal("118.86"), m.margin(), "margin is |a - b|, never signed");
    }

    /** Closest ascends, blowouts descend -- not the same list ordered once. */
    @Test
    void closestAscendsAndBlowoutsDescend() {
        when(fixtures.pairedWithScores(anySet(), any())).thenReturn(List.of(
                game(2025, 4, 1, "118.12", 2, "117.76"),
                game(2025, 8, 3, "205.04", 4, "86.18"),
                game(2025, 6, 5, "176.58", 6, "88.30")));

        assertEquals(new BigDecimal("0.36"), service().closestMatchups(Set.of(5L), 10, WeekBound.ALL_WEEKS).get(0).margin());
        assertEquals(new BigDecimal("118.86"), service().biggestBlowouts(Set.of(5L), 10, WeekBound.ALL_WEEKS).get(0).margin());
    }

    /**
     * R2-equivalent for margins: two games with the SAME margin must order
     * deterministically, or the page reshuffles on reload and -- at the
     * boundary of the limit -- shows a different game each time. This DB
     * contains a real instance: two 2.46 margins in 2025.
     */
    @Test
    void tiedMarginsOrderDeterministically() {
        when(fixtures.pairedWithScores(anySet(), any())).thenReturn(List.of(
                game(2025, 14, 1, "130.20", 2, "127.74"),
                game(2025, 9, 3, "134.86", 4, "132.40")));

        List<LeagueRecordService.MarginRecord> a = service().closestMatchups(Set.of(5L), 10, WeekBound.ALL_WEEKS);
        List<LeagueRecordService.MarginRecord> b = service().closestMatchups(Set.of(5L), 10, WeekBound.ALL_WEEKS);
        assertEquals(a, b);
        assertEquals(9, a.get(0).week(), "earlier week wins the tie within a season");
    }

    /** The limit bounds margins too, not just the score lists. */
    @Test
    void marginListsHonourTheLimit() {
        when(fixtures.pairedWithScores(anySet(), any())).thenReturn(List.of(
                game(2025, 1, 1, "100.00", 2, "90.00"),
                game(2025, 2, 3, "100.00", 4, "80.00"),
                game(2025, 3, 5, "100.00", 6, "70.00")));

        assertEquals(2, service().closestMatchups(Set.of(5L), 2, WeekBound.ALL_WEEKS).size());
    }

    // ------------------------------------------------------------- T058 (US5, streaks)

    /**
     * The independent test named by tasks.md's Phase 7: "the longest streak's
     * weeks are contiguous in the fixture data, and its start and end weeks
     * are named." Roster 1 wins weeks 3, 4 and 5 (beating rosters 2, 4 and 6
     * respectively); the streak returned must span exactly those three weeks,
     * not merely report a length of 3.
     */
    @Test
    void aWinStreakIsContiguousAndNamesItsStartAndEndWeek() {
        when(fixtures.pairedWithScores(anySet(), any())).thenReturn(List.of(
                game(2025, 3, 1, "110.00", 2, "90.00"),
                game(2025, 4, 1, "115.00", 4, "95.00"),
                game(2025, 5, 1, "120.00", 6, "100.00")));

        LeagueRecordService.StreakRecord streak = service().winStreaks(Set.of(5L), 10, WeekBound.ALL_WEEKS).get(0);

        assertEquals(3, streak.length());
        assertEquals(3, streak.startWeek());
        assertEquals(5, streak.endWeek());
        assertEquals(streak.length(), streak.endWeek() - streak.startWeek() + 1,
                "a 3-game streak spanning weeks 3-5 must be contiguous, not merely 3 wins somewhere");
        assertEquals(List.of(2025), streak.spanSeasons());
        assertTrue(streak.withinSeasonOnly(), "streaks are computed within a season by default (research R7)");
    }

    /**
     * A missing week (a bye, or simply no fixture stored) breaks a streak
     * rather than being silently skipped over -- otherwise a roster with wins
     * in weeks 1, 2, then 6, 7 would report one 4-game streak instead of two
     * 2-game streaks, and "contiguous" would stop meaning anything.
     */
    @Test
    void aGapInTheScheduleBreaksAStreakRatherThanBeingSkipped() {
        when(fixtures.pairedWithScores(anySet(), any())).thenReturn(List.of(
                game(2025, 1, 1, "110.00", 2, "90.00"),
                game(2025, 2, 1, "112.00", 3, "90.00"),
                // no game for roster 1 in week 3, 4 or 5
                game(2025, 6, 1, "118.00", 4, "90.00"),
                game(2025, 7, 1, "119.00", 5, "90.00")));

        List<LeagueRecordService.StreakRecord> streaks = service().winStreaks(Set.of(5L), 10, WeekBound.ALL_WEEKS);

        assertEquals(2, streaks.size(), "the gap must split one long run into two separate streaks");
        assertEquals(2, streaks.get(0).length());
        assertEquals(2, streaks.get(1).length());
    }

    /**
     * US4.5's rule for head-to-head ("a tie is counted as a tie, never folded
     * into either side's losses") applies to streaks too: a tie must break a
     * win streak in progress without itself starting -- or extending -- a
     * loss streak.
     */
    @Test
    void aTieBreaksAWinStreakWithoutBecomingALoss() {
        when(fixtures.pairedWithScores(anySet(), any())).thenReturn(List.of(
                game(2025, 1, 1, "100.00", 2, "80.00"),   // roster 1 wins
                game(2025, 2, 1, "90.00", 3, "90.00"),    // tie -- breaks the streak
                game(2025, 3, 1, "100.00", 4, "80.00")));  // roster 1 wins again

        List<LeagueRecordService.StreakRecord> wins = service().winStreaks(Set.of(5L), 10, WeekBound.ALL_WEEKS);
        List<LeagueRecordService.StreakRecord> losses = service().lossStreaks(Set.of(5L), 10, WeekBound.ALL_WEEKS);

        assertEquals(2, wins.size(), "the tie must split the streak into two length-1 runs, not one length-2 run");
        assertTrue(wins.stream().allMatch(s -> s.length() == 1));
        assertTrue(losses.stream().noneMatch(s -> s.rosterId() == 1),
                "a tie is not a loss, so roster 1 must not appear in the loss-streak list");
    }

    /** Longer streaks sort first, mirroring the margin lists' own ordering rule. */
    @Test
    void longerStreaksSortFirst() {
        when(fixtures.pairedWithScores(anySet(), any())).thenReturn(List.of(
                game(2025, 1, 1, "100.00", 2, "80.00"),
                game(2025, 2, 3, "100.00", 4, "80.00"),
                game(2025, 3, 3, "100.00", 5, "80.00")));

        LeagueRecordService.StreakRecord longest = service().winStreaks(Set.of(5L), 10, WeekBound.ALL_WEEKS).get(0);
        assertEquals(3, longest.rosterId(), "roster 3 has the 2-game streak (weeks 2-3), roster 1 only 1 game");
        assertEquals(2, longest.length());
    }

    /** An unowned roster still produces a streak -- R1's rule, restated for the new lists (T062). */
    @Test
    void unownedRosterStillProducesAStreak() {
        LeagueMatchupRepository.PairedGame g1 = new LeagueMatchupRepository.PairedGame(
                2025, 1, 7, null, null, null, new BigDecimal("110.00"),
                2, 2L, "m2", null, new BigDecimal("90.00"));
        when(fixtures.pairedWithScores(anySet(), any())).thenReturn(List.of(g1));

        LeagueRecordService.StreakRecord streak = service().winStreaks(Set.of(5L), 10, WeekBound.ALL_WEEKS).get(0);
        assertEquals(7, streak.rosterId());
        assertNull(streak.managerId());
        assertNull(streak.manager());
    }

    // ------------------------------------------------------------- T058 (US5, points leaders)

    /**
     * The all-time points leader sums across every season a manager appears
     * in the chain, grouped by manager -- not by roster id, which is scoped
     * to one league-season and can name a different person the next year.
     */
    @Test
    void pointsLeaderSumsAcrossSeasonsByManagerNotByRosterId() {
        when(weekPoints.allScores(anySet(), any())).thenReturn(List.of(
                row(2024, 10, 1, 9L, "popsharky", "100.00"),
                row(2025, 3, 4, 9L, "popsharky", "150.50")));

        LeagueRecordService.PointsLeaderRecord leader = service().pointsLeaders(Set.of(5L, 4L), 10, WeekBound.ALL_WEEKS).get(0);

        assertEquals(new BigDecimal("250.50"), leader.points());
        assertEquals(List.of(2024, 2025), leader.spanSeasons());
    }

    /**
     * Two different unowned roster-seasons that happen to share a roster id
     * across two seasons must NOT be merged into one "manager" -- roster ids
     * are scoped to a league-season (T062, mirroring R1's unowned-roster
     * rule for the existing score lists rather than inventing a second one).
     */
    @Test
    void unownedRosterSeasonsAreNeverMergedAcrossSeasons() {
        when(weekPoints.allScores(anySet(), any())).thenReturn(List.of(
                row(2024, 10, 3, null, null, "100.00"),
                row(2025, 3, 3, null, null, "150.00")));

        List<LeagueRecordService.PointsLeaderRecord> leaders = service().pointsLeaders(Set.of(5L, 4L), 10, WeekBound.ALL_WEEKS);

        assertEquals(2, leaders.size(), "two unowned roster-seasons, two different people -- must not be summed together");
    }

    /** The new lists are always present on the RecordBook, even when empty (T063). */
    @Test
    void recordBookAlwaysCarriesTheNewListsEvenWhenEmpty() {
        when(weekPoints.extremes(anySet(), anyBoolean(), anyInt(), any())).thenReturn(List.of());
        when(weekPoints.allScores(anySet(), any())).thenReturn(List.of());
        when(fixtures.pairedWithScores(anySet(), any())).thenReturn(List.of());

        LeagueRecordService.RecordBook book = service().forChain(Set.of(5L), WeekBound.ALL_WEEKS);

        assertNotNull(book.pointsLeaders());
        assertNotNull(book.winStreaks());
        assertNotNull(book.lossStreaks());
        assertTrue(book.pointsLeaders().isEmpty());
        assertTrue(book.winStreaks().isEmpty());
        assertTrue(book.lossStreaks().isEmpty());
    }

    // ------------------------------------------------------------- T012 (specs/008-season-superlatives)

    /**
     * A season with scores in weeks 1-16, bounded through(14), never returns a
     * week-15 or week-16 record. The mock stands in for the repository's own
     * SQL filter (exercised for real by RosterWeekPointsRepository /
     * LeagueMatchupRepository's own IT coverage) -- this test pins that the
     * SERVICE actually forwards the bound it was given rather than silently
     * reverting to ALL_WEEKS.
     */
    @Test
    void aWeekBoundExcludesWeeksAfterIt() {
        WeekBound through14 = WeekBound.through(14);
        when(weekPoints.extremes(anySet(), eq(true), anyInt(), eq(through14)))
                .thenReturn(List.of(row(2025, 14, 1, 1L, "m1", "150.00")));
        when(weekPoints.extremes(anySet(), eq(false), anyInt(), eq(through14)))
                .thenReturn(List.of(row(2025, 3, 2, 2L, "m2", "50.00")));
        when(fixtures.pairedWithScores(anySet(), eq(through14))).thenReturn(List.of(
                game(2025, 14, 1, "150.00", 2, "50.00")));
        when(weekPoints.allScores(anySet(), eq(through14))).thenReturn(List.of(
                row(2025, 14, 1, 1L, "m1", "150.00")));

        LeagueRecordService.RecordBook book = service().forChain(Set.of(5L), through14);

        assertTrue(book.highestWeeks().stream().allMatch(r -> r.week() <= 14));
        assertTrue(book.lowestWeeks().stream().allMatch(r -> r.week() <= 14));
        assertTrue(book.closestMatchups().stream().allMatch(r -> r.week() <= 14));
        assertTrue(book.biggestBlowouts().stream().allMatch(r -> r.week() <= 14));

        // The bound was actually forwarded to the repository, not swallowed.
        verify(weekPoints).extremes(anySet(), eq(true), anyInt(), eq(through14));
        verify(weekPoints, never()).extremes(anySet(), eq(true), anyInt(), eq(WeekBound.ALL_WEEKS));
    }
}
