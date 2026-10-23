package com.ballknowers.draftsim.engine;

import com.ballknowers.draftsim.domain.Sport;
import com.ballknowers.draftsim.engine.LeagueSeasonResolver.Rule;
import com.ballknowers.draftsim.engine.WeeklyReportService.Result;
import com.ballknowers.draftsim.sport.SportRules;
import com.ballknowers.draftsim.sport.SportRulesRegistry;
import com.ballknowers.draftsim.store.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Spec 045 T016 (FR-008): which week {@link WeeklyReportService#forWeek} answers and from where, with the live
 * source stubbed. The opening-week case is the regression for the bug itself: games stored, the league
 * {@code in_season} at leg 1, nothing scored, and Matchups answering 2025 instead of 2026.
 */
class WeeklyReportServiceLiveWeekTest {

    private static final long ID = 210L;

    private final LeagueRepository leagues = mock(LeagueRepository.class);
    private final RosterWeekPointsRepository weekPoints = mock(RosterWeekPointsRepository.class);
    private final LeagueMatchupRepository matchups = mock(LeagueMatchupRepository.class);
    private final RosterSeasonRepository rosterSeasons = mock(RosterSeasonRepository.class);
    private final LeagueMemberRepository members = mock(LeagueMemberRepository.class);
    private final PlayerRepository players = mock(PlayerRepository.class);
    private final SportRulesRegistry registry = mock(SportRulesRegistry.class);
    private final SportRules rules = mock(SportRules.class);
    private final RealizedLineupService realized = mock(RealizedLineupService.class);
    private final LeagueSeasonResolver seasons = mock(LeagueSeasonResolver.class);
    private final PlayerGameRepository playerGames = mock(PlayerGameRepository.class);
    private final ScoredWeeks scoredWeeks = mock(ScoredWeeks.class);
    private final ManagerRepository managers = mock(ManagerRepository.class);
    private final LiveWeekSource liveWeeks = mock(LiveWeekSource.class);
    private final LeagueRepository.LeagueRow league =
            new LeagueRepository.LeagueRow(ID, Sport.NBA, "L26", "n", 2026, 12, List.of(), 0, "L25", "in_season");
    private WeeklyReportService service;

    @BeforeEach
    void setUp() {
        service = new WeeklyReportService(leagues, weekPoints, matchups, rosterSeasons, members, players, registry,
                realized, seasons, playerGames, new GameScoringService(), scoredWeeks, managers, liveWeeks);
        when(registry.get(Sport.NBA)).thenReturn(rules);
        when(rules.playsMultipleGamesPerScoringPeriod()).thenReturn(true);
        when(seasons.resolve("L26", Rule.LIVE_WEEK))
                .thenReturn(Optional.of(new LeagueSeasonResolver.Resolved(league, null)));
        when(leagues.currentLeg(ID)).thenReturn(OptionalInt.of(1));
        when(weekPoints.storedWeeks(ID)).thenReturn(Set.of());
        when(scoredWeeks.of(ID)).thenReturn(new ScoredWeeks.Snapshot(0, 0, Set.of()));
        when(weekPoints.breakdownsFor(anyLong(), anyInt())).thenReturn(List.of());
        when(members.forLeague(ID)).thenReturn(List.of());
        when(matchups.pairedWithScores(anyCollection(), any())).thenReturn(List.of());
        when(playerGames.forWeek(any(), anyInt(), anyInt())).thenReturn(List.of());
        List<RosterSeasonRepository.StandingRow> standings = new ArrayList<>();
        for (int r = 1; r <= 12; r++) {
            standings.add(new RosterSeasonRepository.StandingRow(ID, r, (long) r, "user" + r, "av" + r, 0, 0, 0,
                    0.0, 0.0, null, 2026, "L26", Sport.NBA, "n", false));
        }
        when(rosterSeasons.forLeague(ID)).thenReturn(standings);
    }

    private static LiveWeekSource.Live ok(int week, double pointsOfRoster1) {
        List<LiveWeekSource.Row> rows = new ArrayList<>();
        // 12 rosters, 6 matchups: roster r and r+6 share matchup_id r
        for (int r = 1; r <= 6; r++) {
            rows.add(new LiveWeekSource.Row(r, r, r == 1 ? pointsOfRoster1 : 100.0 + r));
            rows.add(new LiveWeekSource.Row(r + 6, r, 90.0 + r));
        }
        return new LiveWeekSource.Live(week, LiveWeekSource.Status.OK, rows, Instant.parse("2026-10-23T12:00:00Z"));
    }

    private static LiveWeekSource.Live failed() {
        return new LiveWeekSource.Live(0, LiveWeekSource.Status.FAILED, List.of(), Instant.parse("2026-10-23T12:00:00Z"));
    }

    /** What the live source answers, to both the blocking read and the cache-only peek. */
    private void live(Optional<LiveWeekSource.Live> answer) {
        when(liveWeeks.current(league)).thenReturn(answer);
        when(liveWeeks.peek(league)).thenReturn(answer);
    }

    private void stored(int latestStored, int latestFinal, Integer... storedWeeks) {
        Set<Integer> st = Set.of(storedWeeks);
        Set<Integer> fin = new java.util.HashSet<>();
        for (int w : st) if (w <= latestFinal) fin.add(w);
        when(weekPoints.storedWeeks(ID)).thenReturn(st);
        when(scoredWeeks.of(ID)).thenReturn(new ScoredWeeks.Snapshot(latestStored, latestFinal, fin));
    }

    // ---- opening week: the bug ----------------------------------------------------------------------

    @Test
    void openingWeek_showsTheCurrentSeasonLive_notLastSeason() {
        live(Optional.of(ok(1, 201.5)));

        Result r = service.forWeek("L26", 0, null, Rule.LIVE_WEEK).orElseThrow();

        assertTrue(r.available());
        assertTrue(r.live());
        assertEquals(2026, r.season());
        assertNull(r.requestedSeason(), "the season shown is the one asked for: no fallback note");
        assertEquals(1, r.week());
        assertEquals(1, r.liveWeek());
        assertEquals("L26", r.sleeperLeagueId());
        assertFalse(r.weekFinal());
        assertEquals(6, r.matchups().size());
        var first = r.matchups().getFirst();
        assertEquals(1, first.home().rosterId());
        assertEquals(7, first.away().rosterId(), "the lower roster id is home, as in the stored pairing");
        assertEquals(201.5, first.home().points());
        assertEquals("0-0", first.home().record(), "the record BEFORE this week");
        assertEquals("user1", first.home().username());
        // rankings and awards are empty, never a decided week's
        assertEquals(List.of(), r.bestNights());
        assertEquals(List.of(), r.bestWeek());
        assertNull(r.topPerformers());
        assertEquals(List.of(), r.awards());
        assertEquals(List.of(), r.awardsOmitted());
        assertEquals(0, r.latestScoredWeek());
    }

    @Test
    void openingWeek_aNullMatchupIdIsLeftOut_notAZeroZeroGame() {
        List<LiveWeekSource.Row> rows = new ArrayList<>(ok(1, 150).rows());
        rows.add(new LiveWeekSource.Row(99, null, 0.0));
        live(Optional.of(
                new LiveWeekSource.Live(1, LiveWeekSource.Status.OK, rows, Instant.EPOCH)));
        Result r = service.forWeek("L26", 0, null, Rule.LIVE_WEEK).orElseThrow();
        assertEquals(6, r.matchups().size());
    }

    @Test
    void openingWeek_anExplicitRequestForTheLiveWeekIsLiveToo() {
        live(Optional.of(ok(1, 201.5)));
        assertTrue(service.forWeek("L26", 1, null, Rule.LIVE_WEEK).orElseThrow().live());
    }

    // ---- mid-season -----------------------------------------------------------------------------------

    @Test
    void midSeason_theDefaultStaysTheLatestFinalWeek_withTheLiveWeekOffered() {
        stored(3, 3, 1, 2, 3);
        live(Optional.of(ok(4, 120)));
        when(weekPoints.breakdownsFor(ID, 2026)).thenReturn(List.of(
                new RosterWeekPointsRepository.WeekBreakdown(3, 1, 150, "{}", null),
                new RosterWeekPointsRepository.WeekBreakdown(3, 2, 140, "{}", null)));
        when(realized.bestLineup(any(), any(), any(), any()))
                .thenReturn(new RealizedLineupService.WeekLineup(false, 0, List.of(), "x"));

        Result r = service.forWeek("L26", 0, null, Rule.LIVE_WEEK).orElseThrow();

        assertTrue(r.available());
        assertFalse(r.live());
        assertEquals(3, r.week());
        assertEquals(4, r.liveWeek());
        assertTrue(r.weekFinal());
        assertEquals("L26", r.sleeperLeagueId());
    }

    @Test
    void midSeason_anExplicitRequestForTheLiveWeekIsLive() {
        stored(3, 3, 1, 2, 3);
        live(Optional.of(ok(4, 120)));
        Result r = service.forWeek("L26", 4, null, Rule.LIVE_WEEK).orElseThrow();
        assertTrue(r.live());
        assertEquals(4, r.week());
        assertEquals(3, r.latestScoredWeek());
        assertEquals(3, r.latestFinalWeek());
    }

    @Test
    void aStoredPartialCurrentWeek_keepsTodaysStoredPath_notLive() {
        // NBA's current week stored but not final (the R14 case): week 4 stored, only 1..3 final.
        when(weekPoints.storedWeeks(ID)).thenReturn(Set.of(1, 2, 3, 4));
        when(scoredWeeks.of(ID)).thenReturn(new ScoredWeeks.Snapshot(4, 3, Set.of(1, 2, 3)));
        live(Optional.of(ok(4, 120)));
        when(weekPoints.breakdownsFor(ID, 2026)).thenReturn(List.of(
                new RosterWeekPointsRepository.WeekBreakdown(4, 1, 150, "{}", null)));
        when(realized.bestLineup(any(), any(), any(), any()))
                .thenReturn(new RealizedLineupService.WeekLineup(false, 0, List.of(), "x"));

        Result r = service.forWeek("L26", 4, null, Rule.LIVE_WEEK).orElseThrow();

        assertFalse(r.live(), "a stored week never shows two different in-progress scores");
        assertNull(r.liveWeek(), "liveWeek is the UNSTORED live week; week 4 is stored");
        assertFalse(r.weekFinal());
        assertEquals(4, r.week());
    }

    // ---- not started (A11 / FR-017) ---------------------------------------------------------------------

    @Test
    void anUnstartedLiveWeek_isNotOffered_andAnExplicitRequestSaysItHasNotStarted() {
        stored(3, 3, 1, 2, 3);
        live(Optional.of(ok(4, 0.0)));
        // every live row is 0.0 only if roster 1 is 0 AND the others are 0: zero them
        List<LiveWeekSource.Row> zeros = ok(4, 0).rows().stream()
                .map(x -> new LiveWeekSource.Row(x.rosterId(), x.matchupId(), 0.0)).toList();
        live(Optional.of(
                new LiveWeekSource.Live(4, LiveWeekSource.Status.OK, zeros, Instant.EPOCH)));
        when(weekPoints.breakdownsFor(ID, 2026)).thenReturn(List.of(
                new RosterWeekPointsRepository.WeekBreakdown(3, 1, 150, "{}", null)));
        when(realized.bestLineup(any(), any(), any(), any()))
                .thenReturn(new RealizedLineupService.WeekLineup(false, 0, List.of(), "x"));

        Result dflt = service.forWeek("L26", 0, null, Rule.LIVE_WEEK).orElseThrow();
        assertNull(dflt.liveWeek(), "an unstarted week is not offered, only refused when asked for");
        assertFalse(dflt.live());
        assertEquals(3, dflt.week());

        Result explicit = service.forWeek("L26", 4, null, Rule.LIVE_WEEK).orElseThrow();
        assertFalse(explicit.available());
        assertEquals("week 4 hasn't started", explicit.reason());
        assertEquals(4, explicit.liveWeek(), "B2: the refused week rides on the refusal so the page can reach it");
        assertFalse(explicit.live());
    }

    @Test
    void aWeekWithAStoredGameRowCountsAsStarted_evenAtZeroPoints() {
        List<LiveWeekSource.Row> zeros = ok(1, 0).rows().stream()
                .map(x -> new LiveWeekSource.Row(x.rosterId(), x.matchupId(), 0.0)).toList();
        live(Optional.of(
                new LiveWeekSource.Live(1, LiveWeekSource.Status.OK, zeros, Instant.EPOCH)));
        when(playerGames.forWeek(Sport.NBA, 2026, 1)).thenReturn(List.of(new PlayerGameRepository.Row(
                Sport.NBA, 2026, 1, "p1", "g1", LocalDate.of(2026, 10, 20), "BOS", false, "{}")));

        assertTrue(service.forWeek("L26", 0, null, Rule.LIVE_WEEK).orElseThrow().live());
    }

    // ---- live read failed (FR-008) ----------------------------------------------------------------------

    @Test
    void aFailedLiveRead_isUnavailableForTheCurrentSeason_neverLastSeason() {
        live(Optional.of(failed()));

        Result r = service.forWeek("L26", 0, null, Rule.LIVE_WEEK).orElseThrow();

        assertFalse(r.available());
        assertEquals("live scores unavailable from Sleeper right now", r.reason());
        assertEquals(2026, r.season());
        assertNull(r.requestedSeason());
        assertFalse(r.live());
        assertEquals("L26", r.sleeperLeagueId());
        assertEquals(1, r.liveWeek(), "B2: the refused week (the stored leg, 1) rides on the refusal");
    }

    @Test
    void aFailedLiveRead_midSeason_leavesTheStoredDefaultAlone() {
        stored(3, 3, 1, 2, 3);
        live(Optional.of(failed()));
        when(weekPoints.breakdownsFor(ID, 2026)).thenReturn(List.of(
                new RosterWeekPointsRepository.WeekBreakdown(3, 1, 150, "{}", null)));
        when(realized.bestLineup(any(), any(), any(), any()))
                .thenReturn(new RealizedLineupService.WeekLineup(false, 0, List.of(), "x"));

        Result r = service.forWeek("L26", 0, null, Rule.LIVE_WEEK).orElseThrow();
        assertTrue(r.available());
        assertEquals(3, r.week());
    }

    // ---- only LIVE_WEEK can be live ---------------------------------------------------------------------

    @Test
    void underPlayedWeeks_theLiveSourceIsNeverConsulted() {
        when(seasons.resolve("L26", Rule.PLAYED_WEEKS))
                .thenReturn(Optional.of(new LeagueSeasonResolver.Resolved(league, null)));

        Result r = service.forWeek("L26", 0, null, Rule.PLAYED_WEEKS).orElseThrow();

        assertFalse(r.available());
        assertFalse(r.live());
        assertEquals("no week has been scored for this league yet", r.reason());
        verify(liveWeeks, never()).current(any());
    }

    // ---- offseason --------------------------------------------------------------------------------------

    @Test
    void offseason_noLiveWeek_fallsBackAsToday() {
        live(Optional.empty());
        Result r = service.forWeek("L26", 0, null, Rule.LIVE_WEEK).orElseThrow();
        assertFalse(r.available());
        assertFalse(r.live());
        assertNull(r.liveWeek());
        assertEquals("no week has been scored for this league yet", r.reason());
    }

    // ---- B2: a refusal for the live week names that week ----------------------------------------------

    @Test
    void b2_aFailedLiveRead_midSeason_refusesTheExplicitLiveWeek_andCarriesIt() {
        stored(3, 3, 1, 2, 3);
        when(leagues.currentLeg(ID)).thenReturn(OptionalInt.of(4));
        live(Optional.of(failed()));

        Result r = service.forWeek("L26", 4, null, Rule.LIVE_WEEK).orElseThrow();

        assertFalse(r.available());
        assertEquals("live scores unavailable from Sleeper right now", r.reason());
        assertEquals(4, r.week());
        assertEquals(4, r.liveWeek(), "the page's top bound includes the refused week, so no clamp to 3");
        assertEquals(3, r.latestScoredWeek());
    }

    // ---- S4: wait on Sleeper only when the answer depends on it ---------------------------------------------

    @Test
    void s4_aStoredWeekRequest_neverWaitsOnSleeper() {
        stored(3, 3, 1, 2, 3);
        live(Optional.of(ok(4, 120)));
        when(weekPoints.breakdownsFor(ID, 2026)).thenReturn(List.of(
                new RosterWeekPointsRepository.WeekBreakdown(3, 1, 150, "{}", null)));
        when(realized.bestLineup(any(), any(), any(), any()))
                .thenReturn(new RealizedLineupService.WeekLineup(false, 0, List.of(), "x"));

        Result r = service.forWeek("L26", 3, null, Rule.LIVE_WEEK).orElseThrow();

        assertEquals(3, r.week());
        assertEquals(4, r.liveWeek(), "a warm cache still offers the live week");
        verify(liveWeeks, never()).current(any());
        verify(liveWeeks).peek(league);
    }

    @Test
    void s4_theMidSeasonDefault_neverWaits_andLiveWeekIsAbsentOnAColdFirstView() {
        stored(3, 3, 1, 2, 3);
        when(liveWeeks.peek(league)).thenReturn(Optional.empty());     // nothing cached yet
        when(weekPoints.breakdownsFor(ID, 2026)).thenReturn(List.of(
                new RosterWeekPointsRepository.WeekBreakdown(3, 1, 150, "{}", null)));
        when(realized.bestLineup(any(), any(), any(), any()))
                .thenReturn(new RealizedLineupService.WeekLineup(false, 0, List.of(), "x"));

        Result r = service.forWeek("L26", 0, null, Rule.LIVE_WEEK).orElseThrow();

        assertTrue(r.available());
        assertEquals(3, r.week());
        assertNull(r.liveWeek(), "accepted: absent on the very first cold view");
        verify(liveWeeks, never()).current(any());
    }

    @Test
    void s4_anUnstoredExplicitWeek_andTheNothingStoredDefault_doWait() {
        stored(3, 3, 1, 2, 3);
        live(Optional.of(ok(4, 120)));
        service.forWeek("L26", 4, null, Rule.LIVE_WEEK);
        verify(liveWeeks).current(league);
        verify(liveWeeks, never()).peek(any());
    }

    @Test
    void s4_openingWeekDefault_waits() {
        live(Optional.of(ok(1, 201.5)));
        service.forWeek("L26", 0, null, Rule.LIVE_WEEK);
        verify(liveWeeks).current(league);
        verify(liveWeeks, never()).peek(any());
    }
}
