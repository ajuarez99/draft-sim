package com.ballknowers.draftsim.engine;

import com.ballknowers.draftsim.config.LockInProperties;
import com.ballknowers.draftsim.domain.Platform;
import com.ballknowers.draftsim.domain.Sport;
import com.ballknowers.draftsim.engine.WeeklyPotService.On;
import com.ballknowers.draftsim.engine.WeeklyPotService.Status;
import com.ballknowers.draftsim.store.LeagueMemberRepository;
import com.ballknowers.draftsim.store.LeagueMembership;
import com.ballknowers.draftsim.store.LeagueRepository.LeagueRow;
import com.ballknowers.draftsim.store.LeagueWeeklyPotRepository;
import com.ballknowers.draftsim.store.ManagerRepository;
import com.ballknowers.draftsim.store.PlayerGameRepository;
import com.ballknowers.draftsim.store.RosterSeasonRepository;
import com.ballknowers.draftsim.store.RosterSeasonRepository.StandingRow;
import com.ballknowers.draftsim.store.RosterWeekPointsRepository;
import com.ballknowers.draftsim.store.RosterWeekPointsRepository.ScoreRow;
import com.ballknowers.draftsim.store.WeekBound;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Spec 048 T020: {@link WeeklyPotService}'s week walk and live-leader rules, with the repositories mocked and
 * {@link LiveWeekSource} real over a stubbed Sleeper reader (its package-private test seam). The live cache is
 * warmed with {@code current} because the service reads {@code peek}, which never waits on Sleeper.
 */
class WeeklyPotServiceTest {

    private static final LockInProperties PROPS = new LockInProperties(10, 15, 5, List.of(0.0, 0.1), 3, 4, 60, 15);
    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-10-25T12:00:00Z"), ZoneOffset.UTC);
    private static final long LEAGUE_ID = 77L;

    private final LeagueWeeklyPotRepository settings = mock(LeagueWeeklyPotRepository.class);
    private final LeagueMembership membership = mock(LeagueMembership.class);
    private final LeagueMemberRepository leagueMembers = mock(LeagueMemberRepository.class);
    private final ExpectedWinsService expectedWins = mock(ExpectedWinsService.class);
    private final ScoredWeeks scoredWeeks = mock(ScoredWeeks.class);
    private final RosterWeekPointsRepository weekPoints = mock(RosterWeekPointsRepository.class);
    private final RosterSeasonRepository rosterSeasons = mock(RosterSeasonRepository.class);
    private final PlayerGameRepository playerGames = mock(PlayerGameRepository.class);
    private final ManagerRepository managers = mock(ManagerRepository.class);
    private final LiveWeekSource.Reader sleeper = mock(LiveWeekSource.Reader.class);
    private final LiveWeekSource liveWeeks = new LiveWeekSource(sleeper, PROPS, CLOCK, 400);
    private final WeeklyPotService service = new WeeklyPotService(settings, membership, leagueMembers,
            expectedWins, scoredWeeks, weekPoints, rosterSeasons, liveWeeks, playerGames, managers);

    private LeagueRow row(String status) {
        return new LeagueRow(LEAGUE_ID, Sport.NFL, "L48", "n", 2026, 4, List.of(), 0, null, status);
    }

    private static ScoreRow score(int week, int roster, String points) {
        return new ScoreRow(2026, week, roster, (long) roster, "m" + roster, null, new BigDecimal(points));
    }

    private static StandingRow standing(int roster) {
        return new StandingRow(LEAGUE_ID, roster, (long) roster, "m" + roster, null, null, null, null, null, null,
                null, 2026, "L48", Sport.NFL, "n", false);
    }

    /** Every roster 1..4 scores {@code base + roster} in each of {@code weeks} (roster 4 wins). */
    private static List<ScoreRow> weeks(int... weeks) {
        List<ScoreRow> out = new ArrayList<>();
        for (int w : weeks) for (int r = 1; r <= 4; r++) out.add(score(w, r, String.valueOf(100 + r)));
        return out;
    }

    private void given(Integer boundWeek, int latestStored, Set<Integer> finals, List<ScoreRow> rows) {
        when(settings.find(LEAGUE_ID)).thenReturn(Optional.of(new LeagueWeeklyPotRepository.Setting(true, 100)));
        when(membership.canCommission(anyLong(), any())).thenReturn(false);
        when(leagueMembers.anyCommissioner(LEAGUE_ID)).thenReturn(true);
        when(leagueMembers.forLeague(LEAGUE_ID)).thenReturn(List.of());
        when(expectedWins.regularSeasonBound(any())).thenReturn(
                boundWeek == null ? WeekBound.ALL_WEEKS : WeekBound.through(boundWeek));
        int latestFinal = finals.stream().mapToInt(Integer::intValue).max().orElse(0);
        when(scoredWeeks.of(LEAGUE_ID)).thenReturn(new ScoredWeeks.Snapshot(latestStored, latestFinal, finals));
        when(weekPoints.allScores(any(), any())).thenReturn(rows);
        when(rosterSeasons.forLeague(LEAGUE_ID)).thenReturn(List.of(standing(1), standing(2), standing(3), standing(4)));
        when(playerGames.forWeek(any(), anyInt(), anyInt())).thenReturn(List.of());
    }

    private static Map<String, Object> sleeperLeague(int leg) {
        return Map.of("status", "in_season", "settings", Map.of("leg", leg));
    }

    private static Map<String, Object> live(int roster, double points) {
        Map<String, Object> m = new HashMap<>();
        m.put("roster_id", roster);
        m.put("matchup_id", 1);
        m.put("points", points);
        return m;
    }

    private void liveWeek(int leg, double... points) {
        when(sleeper.league("L48")).thenReturn(sleeperLeague(leg));
        List<Map<String, Object>> rows = new ArrayList<>();
        for (int i = 0; i < points.length; i++) rows.add(live(i + 1, points[i]));
        when(sleeper.matchups("L48", leg)).thenReturn(rows);
    }

    /** Fills the live cache, then returns the view as the service would read it with a warm cache. */
    private On viewWarm(LeagueRow row) {
        liveWeeks.current(row);
        return (On) service.view(row, null);
    }

    // ------------------------------------------------------------------ the live line (US4)

    @Test
    void aStartedLiveWeekNamesTheLeaderAndTiesIncludeEveryone() {
        given(14, 7, Set.of(1, 2, 3, 4, 5, 6, 7), weeks(1, 2, 3, 4, 5, 6, 7));
        liveWeek(8, 88.2, 88.2, 50.0, 10.0);
        On on = viewWarm(row("in_season"));
        assertNotNull(on.liveLeader());
        assertEquals(8, on.liveLeader().week());
        assertEquals(List.of(1, 2), on.liveLeader().rosterIds());
        assertEquals(88.2, on.liveLeader().points());
        assertFalse(on.liveUnavailable());
        // the live week moves no money: balances are exactly the seven decided weeks
        long net4 = on.balances().stream().filter(b -> b.rosterId() == 4).findFirst().orElseThrow().netCents();
        assertEquals(7 * 300L, net4);
    }

    @Test
    void aLiveWeekWhereNobodyHasScoredAndNoGamesArePlayedHasNoLeader() {
        given(14, 7, Set.of(1, 2, 3, 4, 5, 6, 7), weeks(1, 2, 3, 4, 5, 6, 7));
        liveWeek(8, 0.0, 0.0, 0.0, 0.0);
        assertNull(viewWarm(row("in_season")).liveLeader());
    }

    @Test
    void storedPlayerGamesStartTheWeekButAnAllZeroTopLineIsStillNoLeader() {
        given(14, 7, Set.of(1, 2, 3, 4, 5, 6, 7), weeks(1, 2, 3, 4, 5, 6, 7));
        when(playerGames.forWeek(any(), anyInt(), anyInt())).thenReturn(List.of(mock(PlayerGameRepository.Row.class)));
        liveWeek(8, 0.0, 0.0, 0.0, 0.0);
        assertNull(viewWarm(row("in_season")).liveLeader());
    }

    @Test
    void aNegativeRosterWithTheRestAtZeroIsNoLeader() {
        given(14, 7, Set.of(1, 2, 3, 4, 5, 6, 7), weeks(1, 2, 3, 4, 5, 6, 7));
        when(playerGames.forWeek(any(), anyInt(), anyInt())).thenReturn(List.of(mock(PlayerGameRepository.Row.class)));
        liveWeek(8, -2.0, 0.0, 0.0, 0.0);
        assertNull(viewWarm(row("in_season")).liveLeader());
    }

    @Test
    void aSingleRosterAboveZeroLeadsAlone() {
        given(14, 7, Set.of(1, 2, 3, 4, 5, 6, 7), weeks(1, 2, 3, 4, 5, 6, 7));
        when(playerGames.forWeek(any(), anyInt(), anyInt())).thenReturn(List.of(mock(PlayerGameRepository.Row.class)));
        liveWeek(8, 0.0, 0.5, 0.0, 0.0);
        On on = viewWarm(row("in_season"));
        assertNotNull(on.liveLeader());
        assertEquals(List.of(2), on.liveLeader().rosterIds());
    }

    @Test
    void theLiveCacheNeverChangesTheWeekList() {
        given(14, 7, Set.of(1, 2, 3, 4, 5, 6, 7), weeks(1, 2, 3, 4, 5, 6, 7));
        liveWeek(8, 88.2, 70.0, 50.0, 10.0);
        assertEquals(7, viewWarm(row("in_season")).weeks().size());
        assertEquals(7, ((On) service.view(row("in_season"), null)).weeks().size());
    }

    @Test
    void aGapBelowTheLatestDecidedWeekKeepsThroughWeekButReportsLatestDecided() {
        // week 1 never ingested, weeks 2-6 decided
        given(14, 6, Set.of(2, 3, 4, 5, 6), weeks(2, 3, 4, 5, 6));
        when(sleeper.league("L48")).thenReturn(null);
        On on = viewWarm(row("in_season"));
        assertEquals(0, on.throughWeek());
        assertEquals(6, on.latestDecidedWeek());
        assertFalse(on.seasonFinal());
        assertEquals(5 * 300L, on.balances().stream().filter(b -> b.rosterId() == 4).findFirst().orElseThrow().netCents());
    }

    @Test
    void aLiveWeekThatIsAlreadyFinalHasNoLeader() {
        given(14, 8, Set.of(1, 2, 3, 4, 5, 6, 7, 8), weeks(1, 2, 3, 4, 5, 6, 7, 8));
        liveWeek(8, 88.2, 70.0, 50.0, 10.0);
        assertNull(viewWarm(row("in_season")).liveLeader());
    }

    @Test
    void aLiveWeekBeyondTheRegularSeasonHasNoLeader() {
        given(14, 14, Set.of(1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12, 13, 14),
                weeks(1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12, 13, 14));
        liveWeek(15, 88.2, 70.0, 50.0, 10.0);
        On on = viewWarm(row("in_season"));
        assertNull(on.liveLeader());
        assertTrue(on.seasonFinal());
    }

    @Test
    void aFailedLiveReadIsReportedNotHidden() {
        given(14, 7, Set.of(1, 2, 3, 4, 5, 6, 7), weeks(1, 2, 3, 4, 5, 6, 7));
        when(sleeper.league("L48")).thenThrow(new IllegalStateException("sleeper down"));
        On on = viewWarm(row("in_season"));
        assertTrue(on.liveUnavailable());
        assertNull(on.liveLeader());
        assertEquals(7, on.throughWeek());
    }

    @Test
    void aColdCacheJustOmitsTheLineWithoutWaiting() {
        given(14, 7, Set.of(1, 2, 3, 4, 5, 6, 7), weeks(1, 2, 3, 4, 5, 6, 7));
        liveWeek(8, 88.2, 70.0, 50.0, 10.0);
        On on = (On) service.view(row("in_season"), null);
        assertNull(on.liveLeader());
        assertFalse(on.liveUnavailable());
    }

    // ------------------------------------------------------------------ the week walk

    @Test
    void aStoredButNotFinalWeekMovesNoMoneyAndCapsThroughWeek() {
        // 1 and 3 final, 2 stored but still being played, 4 never stored, 5 final
        given(14, 5, Set.of(1, 3, 5), weeks(1, 2, 3, 5));
        when(sleeper.league("L48")).thenReturn(null);
        On on = viewWarm(row("in_season"));
        List<Status> statuses = on.weeks().stream().map(WeeklyPotService.Week::status).toList();
        assertEquals(List.of(Status.DECIDED, Status.NOT_FINAL, Status.DECIDED, Status.MISSING, Status.DECIDED), statuses);
        assertEquals(1, on.throughWeek());
        assertEquals(5, on.latestDecidedWeek());
        assertFalse(on.seasonFinal());
        WeeklyPotService.Week notFinal = on.weeks().get(1);
        assertTrue(notFinal.winners().isEmpty());
        assertNull(notFinal.winningPoints());
        assertNull(notFinal.runnerUpPoints());
        assertEquals(0L, notFinal.potCents());
        // three decided weeks of 300c to roster 4, none for weeks 2 and 4
        assertEquals(3 * 300L, on.balances().stream().filter(b -> b.rosterId() == 4).findFirst().orElseThrow().netCents());
    }

    @Test
    void anAllZeroFinalWeekIsNoScoresAndMovesNoMoney() {
        List<ScoreRow> rows = new ArrayList<>(weeks(1, 3));
        for (int r = 1; r <= 4; r++) rows.add(score(2, r, "0.00"));
        given(14, 3, Set.of(1, 2, 3), rows);
        when(sleeper.league("L48")).thenReturn(null);
        On on = viewWarm(row("in_season"));
        assertEquals(Status.NO_SCORES, on.weeks().get(1).status());
        assertEquals(0L, on.weeks().get(1).potCents());
        assertEquals(3, on.throughWeek(), "a week nobody scored in does not stall the ledger");
        assertEquals(2 * 300L, on.balances().stream().filter(b -> b.rosterId() == 4).findFirst().orElseThrow().netCents());
    }

    @Test
    void aWeekWithANegativeScoreIsDecidedNotNoScores() {
        List<ScoreRow> rows = new ArrayList<>();
        rows.add(score(1, 1, "0"));
        rows.add(score(1, 2, "-4.5"));
        rows.add(score(1, 3, "0"));
        rows.add(score(1, 4, "0"));
        given(14, 1, Set.of(1), rows);
        when(sleeper.league("L48")).thenReturn(null);
        On on = viewWarm(row("in_season"));
        assertEquals(Status.DECIDED, on.weeks().get(0).status());
        // 0.0 is the top score here: rosters 1, 3, 4 tie, roster 2 pays
        assertEquals(List.of(1, 3, 4), on.weeks().get(0).winners());
        assertEquals(1, on.weeks().get(0).payers());
    }

    @Test
    void theSeasonIsFinalOnceEveryRegularSeasonWeekIsDecidedEvenIfTheLeagueRowIsNot() {
        given(3, 3, Set.of(1, 2, 3), weeks(1, 2, 3));
        when(sleeper.league("L48")).thenReturn(null);
        On on = viewWarm(row("in_season"));
        assertTrue(on.regularSeasonKnown());
        assertEquals(3, on.regularSeasonThroughWeek());
        assertEquals(3, on.throughWeek());
        assertTrue(on.seasonFinal());
    }

    @Test
    void withNoRecordedPlayoffStartFinalityFollowsTheLeagueRow() {
        given(null, 3, Set.of(1, 2, 3), weeks(1, 2, 3));
        when(sleeper.league("L48")).thenReturn(null);
        On live = viewWarm(row("in_season"));
        assertFalse(live.regularSeasonKnown());
        assertNull(live.regularSeasonThroughWeek());
        assertFalse(live.seasonFinal());
        On done = viewWarm(row("complete"));
        assertTrue(done.seasonFinal());
    }

    @Test
    void everyRosterAppearsEvenBeforeAnyWeekIsDecided() {
        given(14, 0, Set.of(), List.of());
        when(sleeper.league("L48")).thenReturn(null);
        On on = viewWarm(row("in_season"));
        assertEquals(4, on.balances().size());
        assertTrue(on.weeks().isEmpty());
        assertEquals(0, on.throughWeek());
        assertTrue(on.balances().stream().allMatch(b -> b.netCents() == 0L));
    }

    @Test
    void anOffLeagueReturnsOnlyTheSwitch() {
        when(settings.find(LEAGUE_ID)).thenReturn(Optional.empty());
        when(membership.canCommission(anyLong(), any())).thenReturn(true);
        when(leagueMembers.anyCommissioner(LEAGUE_ID)).thenReturn(true);
        WeeklyPotService.View v = service.view(row("in_season"), "u");
        assertTrue(v instanceof WeeklyPotService.Off);
        WeeklyPotService.Off off = (WeeklyPotService.Off) v;
        assertFalse(off.enabled());
        assertEquals(100, off.stakeCents());
        assertTrue(off.canEdit());
    }
}
