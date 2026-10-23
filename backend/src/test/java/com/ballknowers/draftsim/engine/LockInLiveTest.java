package com.ballknowers.draftsim.engine;

import com.ballknowers.draftsim.config.LockInProperties;
import com.ballknowers.draftsim.config.PlayerTrendsProperties;
import com.ballknowers.draftsim.domain.Player;
import com.ballknowers.draftsim.domain.Position;
import com.ballknowers.draftsim.domain.Sport;
import com.ballknowers.draftsim.engine.LockInService.LiveCall;
import com.ballknowers.draftsim.engine.LockInService.LiveCalls;
import com.ballknowers.draftsim.engine.LockInService.LockInThresholds;
import com.ballknowers.draftsim.engine.LockInService.ThresholdPoint;
import com.ballknowers.draftsim.ingest.SleeperClient;
import com.ballknowers.draftsim.ingest.SportSchedule;
import com.ballknowers.draftsim.store.LeagueMemberRepository;
import com.ballknowers.draftsim.store.LeagueRepository;
import com.ballknowers.draftsim.store.LeagueRepository.LeagueRow;
import com.ballknowers.draftsim.store.PlayerGameRepository.SeasonGame;
import com.ballknowers.draftsim.store.PlayerGameRepository.TeamGame;
import com.ballknowers.draftsim.store.PlayerRepository;
import com.ballknowers.draftsim.store.RosterSeasonRepository;
import com.ballknowers.draftsim.store.SportScheduleRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.OptionalInt;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Spec 044 US4 (T028): the threshold pooling and the live Lock/Wait calls, against mocked repositories and a
 * hand-built week. Scoring is {@code pts} x 1, so every figure is hand arithmetic.
 *
 * <p>The fixture is week 3 of 2026. Every player has a stand-in of exactly 10 (five earlier games of 10) unless a
 * test says otherwise, so a latest game of 12.5 is exactly +25%, the 2-left threshold.
 */
class LockInLiveTest {

    private static final LocalDate D0 = LocalDate.of(2026, 10, 10);
    private static final LocalDate DAY20 = D0.plusDays(10);
    private static final LocalDate DAY21 = D0.plusDays(11);
    private static final Map<String, Double> PTS = Map.of("pts", 1.0);
    private static final LockInThresholds TH = new LockInThresholds("OK", List.of(2024, 2025), 0.03, List.of(
            new ThresholdPoint(1, 0.07, 100), new ThresholdPoint(2, 0.25, 100),
            new ThresholdPoint(3, 0.36, 100), new ThresholdPoint(4, 0.44, 100)));

    private final LockInProperties props = new LockInProperties(2, 15, 5, List.of(0.0, 0.10, 0.25, 0.50), 3, 4, 60, 15);
    private final PlayerTrendsProperties trends = new PlayerTrendsProperties(6, 5, 5, 3, 14, 10, 20, 0.9);

    private LeagueRepository leagues;
    private SeasonBoxCache box;
    private SleeperClient sleeper;
    private RosterSeasonRepository rosterSeasons;
    private LeagueMemberRepository members;
    private PlayerRepository players;
    private SportScheduleRepository schedules;
    private LeagueRow league;
    private LockInService service;
    private int seq;
    private final MutableClock clock = new MutableClock(Instant.parse("2026-10-21T16:00:00Z"));

    /** A clock a test can move, to step past a cache TTL. */
    private static final class MutableClock extends Clock {
        private Instant now;
        MutableClock(Instant now) { this.now = now; }
        void advanceSeconds(long s) { now = now.plusSeconds(s); }
        @Override public java.time.ZoneId getZone() { return ZoneOffset.UTC; }
        @Override public Clock withZone(java.time.ZoneId zone) { return this; }
        @Override public Instant instant() { return now; }
    }

    @BeforeEach
    void setUp() {
        leagues = mock(LeagueRepository.class);
        box = mock(SeasonBoxCache.class);
        sleeper = mock(SleeperClient.class);
        rosterSeasons = mock(RosterSeasonRepository.class);
        members = mock(LeagueMemberRepository.class);
        players = mock(PlayerRepository.class);
        schedules = mock(SportScheduleRepository.class);
        league = new LeagueRow(1L, Sport.NBA, "sleeper-1", "L", 2026, 2, List.of(), 0.0, "sleeper-0", "in_season");
        when(leagues.currentLeg(1L)).thenReturn(OptionalInt.of(3));
        when(leagues.gameMode(1L)).thenReturn(OptionalInt.of(1));
        when(leagues.scoringOf(1L)).thenReturn(PTS);
        when(leagues.chainBySleeperId("sleeper-1")).thenReturn(List.of(league,
                new LeagueRow(0L, Sport.NBA, "sleeper-0", "L", 2025, 2, List.of(), 0.0, null, "complete")));
        when(rosterSeasons.forLeague(1L)).thenReturn(List.of(
                new RosterSeasonRepository.StandingRow(1L, 1, 100L, "Alice", null, 0, 0, 0, 0.0, 0.0, null, 2026,
                        "sleeper-1", Sport.NBA, "L", false),
                new RosterSeasonRepository.StandingRow(1L, 2, 200L, "Bob", null, 0, 0, 0, 0.0, 0.0, null, 2026,
                        "sleeper-1", Sport.NBA, "L", false)));
        service = spy(new LockInService(box, null, null, leagues, rosterSeasons, members, players, null,
                new GameScoringService(), props, trends, schedules, sleeper,
                clock));
        doReturn(TH).when(service).thresholds(any());
    }

    // ---- fixture helpers ----

    private final List<SeasonGame> games2026 = new ArrayList<>();
    private final List<SeasonGame> games2025 = new ArrayList<>();
    private final Map<String, Player> roster = new HashMap<>();

    private static final List<TeamGame> TEAMS = List.of(
            new TeamGame("LAL", "t1", D0, "BOS", Map.of()), new TeamGame("BOS", "t1", D0, "LAL", Map.of()));

    private void game(List<SeasonGame> into, String pid, int week, LocalDate day, double pts, String opp) {
        Map<String, Object> stats = new LinkedHashMap<>();
        stats.put("pts", pts);
        stats.put("sp", 1800.0);
        into.add(new SeasonGame(pid, "g" + (++seq), day, opp, stats, week, true));
    }

    /** {@code prior} earlier games of 10, then this week's latest game. */
    private void player(String pid, String team, int prior, Double latest) {
        for (int i = 0; i < prior; i++) game(games2026, pid, 1, D0.plusDays(i), 10.0, "BOS");
        if (latest != null) game(games2026, pid, 3, DAY20, latest, "BOS");
        roster.put(pid, new Player(pid.hashCode(), Sport.NBA, pid, "Player " + pid, List.of(Position.SF), team,
                "Active", null, 25, 3));
    }

    private void schedule() {
        List<SportSchedule.Game> g = new ArrayList<>();
        g.add(new SportSchedule.Game("s1", 3, DAY20, "complete", "LAL", "BOS"));
        g.add(new SportSchedule.Game("s2", 3, DAY21, "pre_game", "LAL", "BOS"));
        g.add(new SportSchedule.Game("s3", 3, D0.plusDays(12), "pre_game", "BOS", "LAL"));
        when(schedules.forSeason("nba", 2026)).thenReturn(g);
    }

    private void rosters(String... startersByRoster) {
        List<Map<String, Object>> out = new ArrayList<>();
        for (int i = 0; i < startersByRoster.length; i++) {
            Map<String, Object> r = new LinkedHashMap<>();
            r.put("roster_id", i + 1);
            r.put("starters", List.of(startersByRoster[i].split(",")));
            out.add(r);
        }
        when(sleeper.rosters("sleeper-1")).thenReturn(out);
    }

    private LiveCalls run() { return run(DAY21); }

    private LiveCalls run(LocalDate asOf) {
        when(box.get(Sport.NBA, 2026)).thenReturn(new SeasonBoxCache.Season(null, games2026, TEAMS, null));
        when(box.get(Sport.NBA, 2025)).thenReturn(new SeasonBoxCache.Season(null, games2025, TEAMS, null));
        when(players.byIds(any(), anyCollection())).thenReturn(roster);
        return service.liveCalls(league, asOf);
    }

    private void schedule(SportSchedule.Game... rows) {
        when(schedules.forSeason("nba", 2026)).thenReturn(List.of(rows));
    }

    private static SportSchedule.Game row(String id, int week, LocalDate day, String status, String home, String away) {
        return new SportSchedule.Game(id, week, day, status, home, away);
    }

    private static LiveCall of(LiveCalls lc, String pid) {
        return lc.calls().stream().filter(c -> c.playerId().equals(pid)).findFirst().orElse(null);
    }

    // ---- the calls ----

    @Test
    void aGameAtExactlyTheThresholdLocksAndJustBelowWaits() {
        schedule();
        player("pa", "LAL", 5, 12.5);     // +25.0% with 2 left (days 21 and 22): the 2-left threshold exactly
        player("pb", "LAL", 5, 12.4);     // +24%
        player("pc", "BOS", 5, 10.7);     // +7%; BOS also has 2 left, so the bar is +25%
        rosters("pa,0", "pb,pc");
        LiveCalls lc = run();

        assertEquals("OK", lc.status());
        assertEquals(3, lc.week());
        LiveCall a = of(lc, "pa");
        assertEquals("LOCK", a.call(), "margin 0.25 == threshold 0.25 is a lock");
        assertEquals(0.25, a.margin(), 1e-12);
        assertEquals(0.25, a.threshold(), 1e-12);
        assertEquals(2, a.gamesLeft());
        assertEquals(10.0, a.baseline(), 1e-12);
        assertEquals("STAND_IN", a.baselineKind());
        assertEquals(12.5, a.latestGame().points(), 1e-12);
        assertEquals(DAY20, a.latestGame().gameDate());
        assertEquals(100L, a.managerId());
        assertEquals("Alice", a.manager());
        assertEquals("LAL", a.team());
        assertEquals("WAIT", of(lc, "pb").call(), "+24% is under +25%");
        assertEquals("Bob", of(lc, "pb").manager());
        assertEquals("WAIT", of(lc, "pc").call());
        assertEquals(List.of("pa", "pb", "pc"), lc.calls().stream().map(LiveCall::playerId).toList(),
                "best margin first");
    }

    @Test
    void theThresholdFollowsGamesLeftAndCapsAtTheThresholdCap() {
        // Only the day-21 game is left for LAL when the third game is already settled: 1 left -> threshold 0.07.
        List<SportSchedule.Game> g = new ArrayList<>();
        g.add(new SportSchedule.Game("s1", 3, DAY20, "complete", "LAL", "BOS"));
        g.add(new SportSchedule.Game("s2", 3, DAY21, "pre_game", "LAL", "BOS"));
        when(schedules.forSeason("nba", 2026)).thenReturn(g);
        player("pa", "LAL", 5, 10.8);     // +8% with 1 left, over the 1-left 0.07 (off the boundary: 10.7 is 0.0699999...)
        rosters("pa");
        LiveCall a = of(run(), "pa");
        assertEquals(1, a.gamesLeft());
        assertEquals(0.07, a.threshold(), 1e-12);
        assertEquals("LOCK", a.call());
    }

    @Test
    void aTeamWithNoGameLeftThisWeekGetsNoEntryAndNeitherDoesAPlayerWhoHasNotPlayed() {
        schedule();
        player("pa", "LAL", 5, 15.0);
        player("pe", "NYK", 5, 15.0);     // his team has no scheduled game left: nothing to wait for
        player("pf", "LAL", 5, null);     // no game this week yet
        rosters("pa,pe,pf");
        assertEquals(List.of("pa"), run().calls().stream().map(LiveCall::playerId).toList());
    }

    @Test
    void aPlayerWithTooFewGamesThisSeasonUsesLastSeasonsAverageAndSaysSo() {
        schedule();
        player("pc", "BOS", 2, 30.0);                                          // 2 games this season (< 5)
        for (int i = 0; i < 6; i++) game(games2025, "pc", 1, D0.minusDays(300 + i), 20.0, "LAL");   // 6 last season at 20
        rosters("pc");
        LiveCall c = of(run(), "pc");
        assertEquals("LAST_SEASON", c.baselineKind(), "labelled, never passed off as this season's average");
        assertEquals(20.0, c.baseline(), 1e-12);
        assertEquals(0.5, c.margin(), 1e-12);
        assertEquals("LOCK", c.call(), "+50% >= the 2-left +25%");
    }

    @Test
    void withNeitherEnoughGamesThisSeasonNorLastTheCallIsTooEarly() {
        schedule();
        player("pd", "BOS", 2, 30.0);
        game(games2025, "pd", 1, D0.minusDays(300), 20.0, "LAL");              // 1 game last season (< 5)
        rosters("pd");
        LiveCall d = of(run(), "pd");
        assertEquals("TOO_EARLY", d.call());
        assertNull(d.baseline());
        assertNull(d.margin());
        assertNull(d.threshold());
        assertNull(d.baselineKind());
        assertEquals(2, d.gamesLeft());
    }

    @Test
    void aFailedRosterReadAnswersRostersFailedAndNeverThrows() {
        schedule();
        when(sleeper.rosters("sleeper-1")).thenThrow(new RuntimeException("sleeper down"));
        LiveCalls lc = run();
        assertEquals("ROSTERS_FAILED", lc.status());
        assertTrue(lc.calls().isEmpty());
        assertEquals(3, lc.week());
    }

    @Test
    void aSecondCallWithinTheTtlDoesNotAskSleeperAgain() {
        schedule();
        player("pa", "LAL", 5, 12.5);
        rosters("pa");
        LiveCalls first = run();
        LiveCalls second = service.liveCalls(league, DAY21);
        assertSame(first, second);
        verify(sleeper, times(1)).rosters("sleeper-1");
    }

    @Test
    void aFailedReadIsCachedBrieflySoAnOutageIsNotOneSleeperCallPerRequestThenRetries() {
        schedule();
        when(sleeper.rosters("sleeper-1")).thenThrow(new RuntimeException("sleeper down"));
        run();
        service.liveCalls(league, DAY21);
        verify(sleeper, times(1)).rosters("sleeper-1");               // within the 15 s negative TTL: no second call
        clock.advanceSeconds(16);
        service.liveCalls(league, DAY21);
        verify(sleeper, times(2)).rosters("sleeper-1");               // past it: the next request retries
    }

    // ---- fix pass 2026-10-22 (code-review B1, B2, S1, S4) ----

    @Test
    void aGameStillInProgressIsNeverJudged() {
        schedule(row("s1", 3, DAY20, "in_game", "LAL", "BOS"), row("s2", 3, DAY21, "pre_game", "LAL", "BOS"));
        player("pa", "LAL", 5, 3.0);      // a quarter of a game
        rosters("pa");
        assertNull(of(run(DAY20), "pa"), "a partial box score of a live game is not a game to lock");
    }

    @Test
    void aPlayerWhoseTeamHasSincePlayedAnotherGameIsNotJudgedOnTheEarlierOne() {
        // Scenario A (review B2): pa has a box row only for day 20; LAL played day 21 and he did not (DNP / not ingested).
        schedule(row("s1", 3, DAY20, "complete", "LAL", "BOS"), row("s2", 3, DAY21, "complete", "LAL", "BOS"),
                row("s3", 3, D0.plusDays(12), "pre_game", "LAL", "BOS"), row("s4", 3, D0.plusDays(13), "pre_game", "LAL", "BOS"));
        player("pa", "LAL", 5, 20.0);
        rosters("pa");
        assertNull(of(run(D0.plusDays(12)), "pa"), "the day-20 game can no longer be locked");
    }

    @Test
    void aGameDatedBeforeAsOfCountsAsStartedEvenIfItsStoredStatusIsStillPreGame() {
        // P4: nothing refreshes schedule status reliably; day 21 is long past on asOf = day 12 + D0 but still "pre_game".
        schedule(row("s1", 3, DAY20, "complete", "LAL", "BOS"), row("s2", 3, DAY21, "pre_game", "LAL", "BOS"),
                row("s3", 3, D0.plusDays(12), "pre_game", "LAL", "BOS"), row("s4", 3, D0.plusDays(13), "pre_game", "LAL", "BOS"));
        player("pa", "LAL", 5, 20.0);
        rosters("pa");
        assertNull(of(run(D0.plusDays(12)), "pa"), "the stale pre_game row is a game that has been and gone");
    }

    @Test
    void aStalePreGameRowDoesNotCountAsAGameLeft() {
        // P4(b): same fixture judged on day 21 itself still has day 21 + later games left (date >= asOf is not stale).
        schedule(row("s1", 3, DAY20, "complete", "LAL", "BOS"), row("s2", 3, DAY21, "pre_game", "LAL", "BOS"),
                row("s3", 3, D0.plusDays(12), "pre_game", "LAL", "BOS"));
        player("pa", "LAL", 5, 20.0);
        rosters("pa");
        assertEquals(2, of(run(DAY21), "pa").gamesLeft());
    }

    @Test
    void aNextGameInProgressSupersedesTheEarlierOneAndIsNotCountedAsLeft() {
        // Scenario B (review B2): day 21 is in_game on asOf day 21.
        schedule(row("s1", 3, DAY20, "complete", "LAL", "BOS"), row("s2", 3, DAY21, "in_game", "LAL", "BOS"),
                row("s3", 3, D0.plusDays(12), "pre_game", "LAL", "BOS"), row("s4", 3, D0.plusDays(13), "pre_game", "LAL", "BOS"));
        player("pa", "LAL", 5, 20.0);
        rosters("pa");
        assertNull(of(run(DAY21), "pa"));
    }

    @Test
    void gamesLeftCountsOnlyGamesNotYetStarted() {
        schedule(row("s1", 3, DAY20, "complete", "LAL", "BOS"), row("s2", 3, DAY21, "in_game", "LAL", "BOS"),
                row("s3", 3, D0.plusDays(12), "pre_game", "LAL", "BOS"));
        player("pa", "LAL", 5, 20.0);
        rosters("pa");
        assertEquals(1, of(run(DAY20), "pa").gamesLeft(), "the in-progress game is not one to wait for");
    }

    @Test
    void withAPlayerFilterTheLiveBlockCarriesOnlyThatPlayersCall() {
        schedule();
        player("pa", "LAL", 5, 12.5);
        player("pb", "LAL", 5, 12.4);
        rosters("pa,pb");
        LockInService.LockInReport r = reportWithLive("pa");
        assertEquals(List.of("pa"), r.live().calls().stream().map(LiveCall::playerId).toList());
    }

    private LockInService.LockInReport reportWithLive(String playerFilter) {
        when(box.get(Sport.NBA, 2026)).thenReturn(new SeasonBoxCache.Season(null, games2026, TEAMS, null));
        when(box.get(Sport.NBA, 2025)).thenReturn(new SeasonBoxCache.Season(null, games2025, TEAMS, null));
        when(players.byIds(any(), anyCollection())).thenReturn(roster);
        LockInService.Computed c = new LockInService.Computed(new LockInService.LockInReport("nba", 2026, "NO_SCORED_WEEKS",
                null, 2, 15, List.of(), 3, 5, 0, null, List.of(), List.of(), Map.of(), List.of(), List.of(), List.of(),
                null, null), List.of());
        doReturn(c).when(service).computedOf(any());
        return service.report(league, playerFilter, true);
    }

    @Test
    void theLiveWeekIsTheScheduleWeekOfTheDateNotAStaleCurrentLeg() {
        when(leagues.currentLeg(1L)).thenReturn(OptionalInt.of(2));      // not refreshed since last week
        schedule();                                                       // the schedule's day 20 / 21 rows are week 3
        player("pa", "LAL", 5, 12.5);
        rosters("pa");
        LiveCalls lc = run();
        assertEquals(3, lc.week());
        assertEquals("LOCK", of(lc, "pa").call());
    }

    @Test
    void withoutACompletedSeasonToLearnFromThereAreNoCallsAndSleeperIsNotAsked() {
        doReturn(new LockInThresholds("NO_HISTORY", List.of(), null, List.of())).when(service).thresholds(any());
        schedule();
        LiveCalls lc = run();
        assertEquals("NO_HISTORY", lc.status());
        assertTrue(lc.calls().isEmpty());
        verify(sleeper, never()).rosters(any());
    }

    @Test
    void aCompleteLeagueIsNotLive() {
        LeagueRow done = new LeagueRow(1L, Sport.NBA, "sleeper-1", "L", 2026, 2, List.of(), 0.0, null, "complete");
        assertEquals("NOT_LIVE", service.liveCalls(done, DAY21).status());
        verify(sleeper, never()).rosters(any());
    }

    @Test
    void aLeagueNotInLockInModeIsNotALockInLeague() {
        when(leagues.gameMode(1L)).thenReturn(OptionalInt.of(0));
        assertFalse(service.isLockInLeague(league));
        assertEquals("NOT_LIVE", service.liveCalls(league, DAY21).status());
    }

    @Test
    void theDigestFormJudgesThroughTheNightAndCountsGamesLeftFromTheNextDay() {
        // pa played a second game on the 21st (the "next day"); judged through the 20th, his latest game is the 20th's.
        schedule();
        player("pa", "LAL", 5, 12.5);
        game(games2026, "pa", 3, DAY21, 30.0, "BOS");
        rosters("pa");
        when(box.get(Sport.NBA, 2026)).thenReturn(new SeasonBoxCache.Season(null, games2026, TEAMS, null));
        when(box.get(Sport.NBA, 2025)).thenReturn(new SeasonBoxCache.Season(null, games2025, TEAMS, null));
        when(players.byIds(any(), anyCollection())).thenReturn(roster);
        LiveCall a = of(service.liveCalls(league, DAY21, DAY20), "pa");
        assertEquals(DAY20, a.latestGame().gameDate());
        assertEquals(12.5, a.latestGame().points(), 1e-12);
        assertEquals(2, a.gamesLeft(), "the schedule rows on or after the 21st");
    }

    // ---- thresholds pooled over the chain ----

    @Test
    void thresholdsPoolEveryCompletedMeasuredSeasonOfTheChainAndEqualTheFit() {
        LockInService real = spy(new LockInService(box, null, null, leagues, rosterSeasons, members, players, null,
                new GameScoringService(), props, trends, schedules, sleeper, Clock.systemUTC()));
        LeagueRow s2024 = new LeagueRow(212L, Sport.NBA, "s24", "L", 2024, 2, List.of(), 0.0, null, "complete");
        LeagueRow s2025 = new LeagueRow(211L, Sport.NBA, "s25", "L", 2025, 2, List.of(), 0.0, "s24", "complete");
        LeagueRow s2026 = new LeagueRow(210L, Sport.NBA, "s26", "L", 2026, 2, List.of(), 0.0, "s25", "in_season");
        LeagueRow notLockIn = new LeagueRow(213L, Sport.NBA, "s23", "L", 2023, 2, List.of(), 0.0, null, "complete");
        when(leagues.chainBySleeperId("s26")).thenReturn(List.of(s2026, s2025, s2024, notLockIn));
        double[] m24 = {0.5, -0.5, 0.2};
        double[] m25 = {0.1, 0.9};
        doReturn(measured(m24, 8, 2)).when(real).computedOf(s2024);
        doReturn(measured(m25, 12, 0)).when(real).computedOf(s2025);
        doReturn(refused()).when(real).computedOf(notLockIn);          // not a MEASURED season: contributes nothing
        doReturn(refused()).when(real).computedOf(s2026);              // in progress: never pooled

        LockInThresholds th = real.thresholds(s2026);
        assertEquals("OK", th.status());
        assertEquals(List.of(2024, 2025), th.from());
        assertEquals(2.0 / 22.0, th.pMissed(), 1e-15, "(2 missed) / (8 + 12 rode out + 2 missed)");
        double[] expected = LockThresholds.fit(new double[]{0.5, -0.5, 0.2, 0.1, 0.9}, 2.0 / 22.0, 4);
        assertEquals(4, th.byGamesLeft().size());
        for (int k = 1; k <= 4; k++) {
            assertEquals(k, th.byGamesLeft().get(k - 1).gamesLeft());
            assertEquals(expected[k], th.byGamesLeft().get(k - 1).threshold(), 1e-15);
            assertEquals(5, th.byGamesLeft().get(k - 1).n(), "the pooled margin count behind each threshold");
        }
        for (int k = 2; k <= 4; k++) {
            assertTrue(th.byGamesLeft().get(k - 1).threshold() >= th.byGamesLeft().get(k - 2).threshold(),
                    "more games left never lowers the bar");
        }
        // The league's own season counts when it is complete: league 2025's chain is 2025 + 2024.
        when(leagues.chainBySleeperId("s25")).thenReturn(List.of(s2025, s2024));
        assertEquals(List.of(2024, 2025), real.thresholds(s2025).from());
    }

    @Test
    void aChainWithNoCompletedMeasuredSeasonHasNoHistory() {
        LockInService real = spy(new LockInService(box, null, null, leagues, rosterSeasons, members, players, null,
                new GameScoringService(), props, trends, schedules, sleeper, Clock.systemUTC()));
        LeagueRow s2026 = new LeagueRow(210L, Sport.NBA, "s26", "L", 2026, 2, List.of(), 0.0, null, "in_season");
        when(leagues.chainBySleeperId("s26")).thenReturn(List.of(s2026));
        LockInThresholds th = real.thresholds(s2026);
        assertEquals("NO_HISTORY", th.status());
        assertTrue(th.from().isEmpty());
        assertTrue(th.byGamesLeft().isEmpty());
        assertNull(th.pMissed());
    }

    private LockInService.Computed measured(double[] margins, int rodeOut, int missed) {
        LockInService.LockInReport r = new LockInService.LockInReport("nba", 2025, "MEASURED", null, 2, 15, List.of(),
                3, 5, 1, null, List.of(), List.of(), Map.of(), List.of(), List.of(), List.of(), null, null);
        return new LockInService.Computed(r, List.of(), new LockInService.Pool(margins, rodeOut, missed));
    }

    private LockInService.Computed refused() {
        LockInService.LockInReport r = new LockInService.LockInReport("nba", 2025, "GAME_MODE", "no", 2, 15, List.of(),
                3, 5, 0, null, List.of(), List.of(), Map.of(), List.of(), List.of(), List.of(), null, null);
        return new LockInService.Computed(r, List.of());
    }
}
