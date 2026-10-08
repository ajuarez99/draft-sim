package com.ballknowers.draftsim.engine;

import com.ballknowers.draftsim.domain.Sport;
import com.ballknowers.draftsim.engine.DraftAndAdpJoin.Joined;
import com.ballknowers.draftsim.engine.DraftGradesService.DraftGrades;
import com.ballknowers.draftsim.engine.DraftGradesService.PickGrade;
import com.ballknowers.draftsim.store.BoardRepository;
import com.ballknowers.draftsim.store.DraftRepository;
import com.ballknowers.draftsim.store.DraftRepository.DraftRow;
import com.ballknowers.draftsim.store.DraftRepository.PickRow;
import com.ballknowers.draftsim.store.LeagueRepository.LeagueRow;
import com.ballknowers.draftsim.store.PlayerGameRepository.SeasonToken;
import com.ballknowers.draftsim.store.LeagueRepository;
import com.ballknowers.draftsim.store.PlayerRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Spec 022 T043 (F1, F12): the draft state is read from {@code draft.status}, a pick's manager is named by
 * Draft Grades' rule, ADP is the blend as of the draft's scheduled date (never the search rank), and
 * {@code draftValue} is the pick's {@code valueOverSlot} and null whenever Draft Grades is not available.
 */
class DraftAndAdpJoinTest {

    private static final Instant START = Instant.parse("2025-10-21T23:30:00Z");
    private static final SeasonToken TOKEN = new SeasonToken(100, OffsetDateTime.parse("2026-04-13T11:00:00Z"));

    private DraftRepository drafts;
    private BoardRepository board;
    private DraftGradesService grades;
    private PlayerRepository players;
    private LeagueRepository leagues;
    private DraftAndAdpJoin join;

    /** An in-season league (league.status) whose draft is read by draft.status, not by it (F1). */
    private final LeagueRow league = new LeagueRow(7L, Sport.NBA, "L7", "Ball Knowers", 2025, 12,
            List.of("PG", "BN"), 0.0, null, "in_season");

    @BeforeEach
    void setUp() {
        drafts = mock(DraftRepository.class);
        board = mock(BoardRepository.class);
        grades = mock(DraftGradesService.class);
        players = mock(PlayerRepository.class);
        leagues = mock(LeagueRepository.class);
        when(leagues.scoringOf(7L)).thenReturn(Map.of("pts", 1.0));
        join = new DraftAndAdpJoin(drafts, board, grades, players, leagues);
        when(players.idsBySleeperId(Sport.NBA)).thenReturn(Map.of("S1", 1L, "S2", 2L, "S3", 3L));
    }

    private static DraftRow draft(String status, Instant start) {
        return new DraftRow(70L, 7L, "D70", 2025, 3, 12, status, Map.of(), 0, start);
    }

    private void givenDraft(String status, Instant start) {
        DraftRow d = draft(status, start);
        when(drafts.forLeague(7L)).thenReturn(Optional.of(d));
        when(drafts.picks(70L)).thenReturn(List.of(
                new PickRow(70L, 1, 1, 1, 11L, 1L, null),
                new PickRow(70L, 14, 2, 2, 12L, 2L, null)));
        when(grades.managerNamesBySlot(any(), any())).thenReturn(Map.of(1, "Alice", 2, "Bob"));
    }

    private static DraftGrades grades(boolean available, String reason, boolean early, int weeks, PickGrade... picks) {
        return new DraftGrades("D70", Sport.NBA, 2025, available, reason, DraftGradesService.ProductionBasis.WEEKLY_AVERAGE_GAME,
                List.of(), weeks, List.of(), early, 3, 4, 0, 0, 0, null, List.of(picks), List.of(), List.of(), List.of());
    }

    private static PickGrade pick(int pickNo, Double valueOverSlot) {
        return new PickGrade(pickNo, 1, 1, "S", "N", "PG", 10.0, 5, 8.0, valueOverSlot, null, null, null, null, null,
                null);
    }

    // ---------------------------------------------------------------- draft state (F1)

    @Test
    void anInSeasonLeagueWithACompleteDraftIsComplete() {
        givenDraft("complete", START);
        when(grades.read(any())).thenReturn(grades(true, null, false, 18, pick(1, 3.5)));
        when(board.latestBefore(any(), any(), any())).thenReturn(Optional.empty());

        Joined j = join.join(league, TOKEN);   // league.status is "in_season"
        assertEquals("COMPLETE", j.draft().state());
        assertEquals("D70", j.draft().draftId());
        assertEquals(2025, j.draft().draftSeason());   // the season of the league the draft was read from
    }

    @Test
    void aDraftRowInAnyOtherStatusIsNotHappenedAndHasNoPicks() {
        givenDraft("pre_draft", START);
        when(grades.read(any())).thenReturn(grades(false, "DRAFT_NOT_COMPLETE", false, 0));
        when(board.latestBefore(any(), any(), any())).thenReturn(Optional.empty());

        Joined j = join.join(league, TOKEN);
        assertEquals("NOT_HAPPENED", j.draft().state());
        assertTrue(j.picks().isEmpty());
        assertTrue(j.draftValue().isEmpty());
        assertFalse(j.draftGrades().available());
        assertEquals("DRAFT_NOT_COMPLETE", j.draftGrades().reason());
        verify(drafts, never()).picks(70L);
    }

    @Test
    void noDraftRowIsNone() {
        when(drafts.forLeague(7L)).thenReturn(Optional.empty());
        Joined j = join.join(league, TOKEN);
        assertEquals("NONE", j.draft().state());
        assertNull(j.draft().draftId());
        assertEquals(DraftAndAdpJoin.NO_DRAFT, j.draftGrades().reason());
        assertFalse(j.draftGrades().available());
        assertEquals("NO_DRAFT_DATE", j.adp().reason());
        verify(board, never()).latestBefore(any(), any(), any());
    }

    // ---------------------------------------------------------------- picks and managers

    @Test
    void aDraftedPlayerGetsPickRoundAndTheDraftGradesManagerNameAndAnUndraftedOneGetsNothing() {
        givenDraft("complete", START);
        when(grades.read(any())).thenReturn(grades(true, null, false, 18, pick(1, 3.5), pick(14, -2.0)));
        when(board.latestBefore(any(), any(), any())).thenReturn(Optional.empty());

        Joined j = join.join(league, TOKEN);
        assertEquals(new DraftAndAdpJoin.DraftPick(1, 1, "Alice"), j.picks().get("S1"));
        assertEquals(new DraftAndAdpJoin.DraftPick(14, 2, "Bob"), j.picks().get("S2"));
        assertNull(j.picks().get("S3"));            // undrafted: no pick ...
        assertNull(j.draftValue().get("S3"));       // ... and no draft value (I8)
        // The names come from Draft Grades' rule, not from RosterOwners.
        verify(grades).managerNamesBySlot(any(), any());
    }

    // ---------------------------------------------------------------- ADP (F12)

    @Test
    void adpIsTheBlendsLatestCaptureOnOrBeforeTheDraftsScheduledUtcDate() {
        givenDraft("complete", START);
        when(grades.read(any())).thenReturn(grades(true, null, false, 18));
        LocalDate captured = LocalDate.of(2025, 10, 20);
        when(board.latestBefore(eq(Sport.NBA), eq("blend"), eq(LocalDate.of(2025, 10, 21))))
                .thenReturn(Optional.of(new BoardRepository.Capture(captured, List.of(
                        new BoardRepository.Row(1L, 4.5, 1), new BoardRepository.Row(99L, 7.0, 2)))));

        Joined j = join.join(league, TOKEN);
        assertEquals(new DraftAndAdpJoin.AdpState("blend", captured, null), j.adp());
        assertEquals(4.5, j.adpOf("S1"));
        assertNull(j.adpOf("S2"));                  // in no capture row
        assertEquals(1, j.adpBySleeperId().size()); // the row for an unknown local id (99) is dropped
        // The raw search rank is never read as ADP.
        verify(board, never()).latestBefore(any(), eq(BoardRepository.SOURCE_SEARCH_RANK), any());
        verify(board, never()).load(any(), eq(BoardRepository.SOURCE_SEARCH_RANK), any());
        verify(board, never()).asOf(any(), any(), any());
    }

    @Test
    void aNullStartTimeIsNoDraftDateAndTheBoardIsNotRead() {
        givenDraft("complete", null);
        when(grades.read(any())).thenReturn(grades(true, null, false, 18));

        Joined j = join.join(league, TOKEN);
        assertEquals(new DraftAndAdpJoin.AdpState("blend", null, "NO_DRAFT_DATE"), j.adp());
        verify(board, never()).latestBefore(any(), any(), any());
    }

    @Test
    void noCaptureOnOrBeforeTheDateIsNoAdpStored() {
        givenDraft("complete", START);
        when(grades.read(any())).thenReturn(grades(true, null, false, 18));
        when(board.latestBefore(any(), any(), any())).thenReturn(Optional.empty());

        Joined j = join.join(league, TOKEN);
        assertEquals(new DraftAndAdpJoin.AdpState("blend", null, "NO_ADP_STORED"), j.adp());
        assertTrue(j.adpBySleeperId().isEmpty());
    }

    // ---------------------------------------------------------------- Draft Grades

    @Test
    void draftGradesIsCopiedAndDraftValueIsThePicksValueOverSlot() {
        givenDraft("complete", START);
        when(grades.read(any())).thenReturn(grades(true, null, true, 3, pick(1, 3.5), pick(14, null)));
        when(board.latestBefore(any(), any(), any())).thenReturn(Optional.empty());

        Joined j = join.join(league, TOKEN);
        assertEquals(new DraftAndAdpJoin.DraftGradesState(true, null, true, 3), j.draftGrades());
        assertEquals(3.5, j.draftValue().get("S1"));
        assertNull(j.draftValue().get("S2"));       // a pick Draft Grades gave no value
        assertNotNull(j.picks().get("S2"));         // ... is still a drafted player
    }

    @Test
    void draftValueIsNullForEveryoneWhenGradesAreUnavailableAndTheReasonSaysWhy() {
        givenDraft("complete", START);
        when(grades.read(any())).thenReturn(grades(false, "NO_SCORED_WEEKS", false, 0, pick(1, 3.5)));
        when(board.latestBefore(any(), any(), any())).thenReturn(Optional.empty());

        Joined j = join.join(league, TOKEN);
        assertEquals(new DraftAndAdpJoin.DraftGradesState(false, "NO_SCORED_WEEKS", false, 0), j.draftGrades());
        assertTrue(j.draftValue().isEmpty());
        assertEquals(2, j.picks().size());          // the picks themselves still show
    }

    @Test
    void theDraftGradesReadIsMemoisedOnTheSeasonTokenAndTheDraftStatus() {
        givenDraft("complete", START);
        when(grades.read(any())).thenReturn(grades(true, null, false, 18, pick(1, 3.5)));
        when(board.latestBefore(any(), any(), any())).thenReturn(Optional.empty());

        join.join(league, TOKEN);
        join.join(league, TOKEN);
        join.join(league, new SeasonToken(100, ZoneOffsetOf("2026-04-13T11:00:00Z")));   // equal token
        verify(grades, times(1)).read(any());

        join.join(league, new SeasonToken(101, ZoneOffsetOf("2026-04-14T11:00:00Z")));   // an ingest landed
        verify(grades, times(2)).read(any());
    }

    @Test
    void aScoringChangeInvalidatesTheMemo() {
        givenDraft("complete", START);
        when(grades.read(any())).thenReturn(grades(true, null, false, 18, pick(1, 3.5)));
        when(board.latestBefore(any(), any(), any())).thenReturn(Optional.empty());

        join.join(league, TOKEN);
        join.join(league, TOKEN);
        verify(grades, times(1)).read(any());

        when(leagues.scoringOf(7L)).thenReturn(Map.of("pts", 1.5));   // the commissioner changed scoring
        join.join(league, TOKEN);
        verify(grades, times(2)).read(any());
    }

    @Test
    void aFallbackReadsTheRequestedSeasonsDraftAndAdpAndLabelsItsSeason() {
        // The leaderboard passes the REQUESTED league (2026) while its stats are 2025's: the draft row, its start
        // date and the draft season are the requested season's.
        LeagueRow requested = new LeagueRow(8L, Sport.NBA, "L8", "Ball Knowers", 2026, 12,
                List.of("PG", "BN"), 0.0, null, "pre_draft");
        DraftRow d = new DraftRow(80L, 8L, "D80", 2026, 3, 12, "pre_draft", Map.of(), 0,
                Instant.parse("2026-10-10T19:15:00Z"));
        when(drafts.forLeague(8L)).thenReturn(Optional.of(d));
        when(leagues.scoringOf(8L)).thenReturn(Map.of("pts", 1.0));
        when(grades.read(any())).thenReturn(grades(false, "DRAFT_NOT_COMPLETE", false, 0));
        LocalDate captured = LocalDate.of(2026, 9, 28);
        when(board.latestBefore(eq(Sport.NBA), eq("blend"), eq(LocalDate.of(2026, 10, 10))))
                .thenReturn(Optional.of(new BoardRepository.Capture(captured, List.of(new BoardRepository.Row(1L, 4.5, 1)))));

        Joined j = join.join(requested, TOKEN);
        assertEquals(new DraftAndAdpJoin.DraftState("NOT_HAPPENED", "D80", 2026), j.draft());
        assertEquals(captured, j.adp().capturedOn());
        assertEquals(4.5, j.adpOf("S1"));
        assertTrue(j.picks().isEmpty());
        assertEquals("DRAFT_NOT_COMPLETE", j.draftGrades().reason());
        verify(drafts, never()).forLeague(7L);
    }

    private static OffsetDateTime ZoneOffsetOf(String iso) {
        return OffsetDateTime.parse(iso).withOffsetSameInstant(ZoneOffset.UTC);
    }
}
