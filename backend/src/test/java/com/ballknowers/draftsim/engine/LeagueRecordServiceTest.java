package com.ballknowers.draftsim.engine;

import com.ballknowers.draftsim.store.LeagueMatchupRepository;
import com.ballknowers.draftsim.store.RosterWeekPointsRepository;
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
        when(weekPoints.extremes(anySet(), eq(true), anyInt()))
                .thenReturn(List.of(row(2025, 8, 7, null, null, "205.04")));

        List<LeagueRecordService.WeeklyScoreRecord> out = service().highestWeeks(Set.of(5L), 10);

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
        when(weekPoints.extremes(anySet(), anyBoolean(), anyInt())).thenReturn(List.of());

        LeagueRecordService.RecordBook book = service().forChain(Set.of(210L));

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
        service().forChain(Set.of(5L, 4L), 7);

        verify(weekPoints).extremes(anySet(), eq(true), eq(7));
        verify(weekPoints).extremes(anySet(), eq(false), eq(7));
    }

    /**
     * The chain, not the head. league.id is a league-SEASON in this schema, so
     * asking with one id answers "this season" -- the question the page could
     * already answer. FR-001 is about every season.
     */
    @Test
    void queriesTheWholeChainRatherThanOneSeason() {
        service().forChain(List.of(4L, 5L), 10);

        verify(weekPoints).extremes(argThat(ids -> ids.containsAll(List.of(4L, 5L))), eq(true), anyInt());
    }

    /**
     * FR-010 -- an empty margins panel carries a reason, and a populated one
     * does not. The client renders whichever is present, so exactly one of them
     * must be.
     */
    @Test
    void emptyMarginsCarryAReasonAndPopulatedMarginsDoNot() {
        when(weekPoints.extremes(anySet(), anyBoolean(), anyInt())).thenReturn(List.of());
        when(fixtures.pairedWithScores(anySet())).thenReturn(List.of());

        LeagueRecordService.RecordBook empty = service().forChain(Set.of(5L));
        assertNotNull(empty.marginsUnavailableReason(), "an empty panel that says nothing reads as broken");

        when(fixtures.pairedWithScores(anySet())).thenReturn(List.of(
                new LeagueMatchupRepository.PairedGame(2025, 17, 3, 12L, "a", null, new BigDecimal("132.58"),
                        9, 18L, "b", null, new BigDecimal("132.42"))));

        LeagueRecordService.RecordBook populated = service().forChain(Set.of(5L));
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
        when(fixtures.pairedWithScores(anySet())).thenReturn(List.of(game(2025, 8, 3, "86.18", 7, "205.04")));

        LeagueRecordService.MarginRecord m = service().closestMatchups(Set.of(5L), 10).get(0);

        assertEquals(7, m.winner().rosterId());
        assertEquals(3, m.loser().rosterId());
        assertEquals(new BigDecimal("118.86"), m.margin(), "margin is |a - b|, never signed");
    }

    /** Closest ascends, blowouts descend -- not the same list ordered once. */
    @Test
    void closestAscendsAndBlowoutsDescend() {
        when(fixtures.pairedWithScores(anySet())).thenReturn(List.of(
                game(2025, 4, 1, "118.12", 2, "117.76"),
                game(2025, 8, 3, "205.04", 4, "86.18"),
                game(2025, 6, 5, "176.58", 6, "88.30")));

        assertEquals(new BigDecimal("0.36"), service().closestMatchups(Set.of(5L), 10).get(0).margin());
        assertEquals(new BigDecimal("118.86"), service().biggestBlowouts(Set.of(5L), 10).get(0).margin());
    }

    /**
     * R2-equivalent for margins: two games with the SAME margin must order
     * deterministically, or the page reshuffles on reload and -- at the
     * boundary of the limit -- shows a different game each time. This DB
     * contains a real instance: two 2.46 margins in 2025.
     */
    @Test
    void tiedMarginsOrderDeterministically() {
        when(fixtures.pairedWithScores(anySet())).thenReturn(List.of(
                game(2025, 14, 1, "130.20", 2, "127.74"),
                game(2025, 9, 3, "134.86", 4, "132.40")));

        List<LeagueRecordService.MarginRecord> a = service().closestMatchups(Set.of(5L), 10);
        List<LeagueRecordService.MarginRecord> b = service().closestMatchups(Set.of(5L), 10);
        assertEquals(a, b);
        assertEquals(9, a.get(0).week(), "earlier week wins the tie within a season");
    }

    /** The limit bounds margins too, not just the score lists. */
    @Test
    void marginListsHonourTheLimit() {
        when(fixtures.pairedWithScores(anySet())).thenReturn(List.of(
                game(2025, 1, 1, "100.00", 2, "90.00"),
                game(2025, 2, 3, "100.00", 4, "80.00"),
                game(2025, 3, 5, "100.00", 6, "70.00")));

        assertEquals(2, service().closestMatchups(Set.of(5L), 2).size());
    }
}
