package com.ballknowers.draftsim.engine;

import com.ballknowers.draftsim.domain.Player;
import com.ballknowers.draftsim.domain.Position;
import com.ballknowers.draftsim.domain.Sport;
import com.ballknowers.draftsim.engine.AdvancedStats.WindowKind;
import com.ballknowers.draftsim.ingest.SleeperClient;
import com.ballknowers.draftsim.ingest.SportSchedule;
import com.ballknowers.draftsim.sport.SportRules;
import com.ballknowers.draftsim.sport.SportRulesRegistry;
import com.ballknowers.draftsim.store.LeagueRepository;
import com.ballknowers.draftsim.store.LeagueRepository.LeagueRow;
import com.ballknowers.draftsim.store.ManagerRepository;
import com.ballknowers.draftsim.store.PlayerRepository;
import com.ballknowers.draftsim.store.RosterSeasonRepository;
import com.ballknowers.draftsim.store.RosterSeasonRepository.StandingRow;
import com.ballknowers.draftsim.store.SportScheduleRepository;
import com.ballknowers.draftsim.store.StatusCaptureRepository;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalInt;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * specs/042 T014, with mocks: which roster is "mine", a failed Sleeper call, the gate, the playoff weeks, and the
 * one-leaderboard-read rule. The flags themselves are {@link LineupCheckTest}'s.
 */
class LineupCheckServiceTest {

    private static final Clock NOW = Clock.fixed(Instant.parse("2026-10-21T15:00:00Z"), ZoneOffset.UTC);

    private final LeagueRepository leagues = mock(LeagueRepository.class);
    private final RosterSeasonRepository rosterSeasons = mock(RosterSeasonRepository.class);
    private final ManagerRepository managers = mock(ManagerRepository.class);
    private final PlayerRepository players = mock(PlayerRepository.class);
    private final SportRulesRegistry registry = mock(SportRulesRegistry.class);
    private final SportRules rules = mock(SportRules.class);
    private final SportScheduleRepository schedules = mock(SportScheduleRepository.class);
    private final StatusCaptureRepository statusCaptures = mock(StatusCaptureRepository.class);
    private final PlayerStatsService stats = mock(PlayerStatsService.class);
    private final SleeperClient sleeper = mock(SleeperClient.class);

    private static LeagueRow league(Sport sport, String status) {
        return new LeagueRow(5L, sport, "L1", "Ball Knowers", 2026, 12, List.of("PG", "SG", "BN"), 0.0, null, status);
    }

    private static StandingRow standing(int rosterId, Long managerId) {
        return new StandingRow(5L, rosterId, managerId, "m" + rosterId, null, 0, 0, 0, 0.0, 0.0, null, 2026, "L1",
                Sport.NBA, "Ball Knowers", false);
    }

    private static Player player(String id, String name, Position pos, String injury) {
        return new Player(id.hashCode(), Sport.NBA, id, name, List.of(pos), "ATL", "ACT", injury, null, null);
    }

    private static Map<String, Object> roster(int id, List<String> starters, List<String> all) {
        return Map.of("roster_id", id, "owner_id", "u" + id, "starters", starters, "players", all, "reserve", List.of());
    }

    private LineupCheckService service(boolean basketball) {
        when(registry.get(any())).thenReturn(rules);
        when(rules.playsMultipleGamesPerScoringPeriod()).thenReturn(basketball);
        when(rules.sport()).thenReturn(basketball ? Sport.NBA : Sport.NFL);
        when(rules.isEligible(any(), anyString())).thenAnswer(inv -> {
            Player p = inv.getArgument(0);
            String slot = inv.getArgument(1);
            return "BN".equals(slot) || p.positions().stream().anyMatch(x -> x.name().equals(slot));
        });
        when(managers.idsBySleeperUserId()).thenReturn(Map.of("me", 7L));
        when(leagues.currentLeg(anyLong())).thenReturn(OptionalInt.of(1));
        when(statusCaptures.statusAsOf(any(Sport.class), org.mockito.ArgumentMatchers.anyInt())).thenReturn(Optional.empty());
        when(schedules.forSeason(anyString(), org.mockito.ArgumentMatchers.anyInt())).thenReturn(List.of(
                new SportSchedule.Game("g1", 1, LocalDate.parse("2026-10-22"), "pre_game", "ATL", "BOS")));
        return new LineupCheckService(leagues, rosterSeasons, managers, players, registry, schedules, statusCaptures,
                stats, sleeper, NOW);
    }

    private void twoRostersForMe() {
        // The caller holds rosters 9 and 3; 3 is the lowest id, so it is "mine" (NextMatchupService's rule).
        when(rosterSeasons.forLeague(5L)).thenReturn(List.of(standing(9, 7L), standing(3, 7L), standing(4, 8L)));
        Player out = player("p-out", "Out Guy", Position.PG, "Out");
        Player ok = player("p-ok", "Ok Guy", Position.SG, null);
        Player bench = player("p-bench", "Bench Guy", Position.PG, null);
        when(players.byIds(eq(Sport.NBA), any())).thenReturn(Map.of("p-out", out, "p-ok", ok, "p-bench", bench));
        when(sleeper.rosters("L1")).thenReturn(List.of(
                roster(9, List.of("p-ok", "p-ok"), List.of("p-ok")),
                roster(3, List.of("p-out", "p-ok"), List.of("p-out", "p-ok", "p-bench"))));
    }

    @Test
    void aManagerWithTwoRostersIsTheLowestRosterId() {
        LineupCheckService svc = service(true);
        twoRostersForMe();
        when(stats.readLeaderboard(any(), any(), any())).thenReturn(null);

        LineupCheck check = svc.forLeague(league(Sport.NBA, "in_season"), "me");

        assertTrue(check.available());
        assertEquals(1, check.flags().size(), "roster 3's Out starter, not roster 9's lineup");
        assertEquals("p-out", check.flags().get(0).player().sleeperPlayerId());
        assertEquals(List.of("p-bench"), check.flags().get(0).options().stream().map(o -> o.player().sleeperPlayerId()).toList());
        assertTrue(check.gameCheck());
        assertEquals(1, check.week());
        assertNull(check.statusAsOf(), "no capture is unknown, not fresh");
    }

    @Test
    void aSleeperFailureIsRostersFailedNotAnException() {
        LineupCheckService svc = service(true);
        when(rosterSeasons.forLeague(5L)).thenReturn(List.of(standing(3, 7L)));
        when(sleeper.rosters("L1")).thenThrow(new IllegalStateException("503"));

        LineupCheck check = svc.forLeague(league(Sport.NBA, "in_season"), "me");

        assertFalse(check.available());
        assertEquals(LineupCheck.ROSTERS_FAILED, check.reason());
        assertTrue(check.flags().isEmpty());
    }

    @Test
    void aPlayoffWeekIsStillChecked() {
        LineupCheckService svc = service(true);
        twoRostersForMe();
        when(leagues.currentLeg(anyLong())).thenReturn(OptionalInt.of(21));
        when(stats.readLeaderboard(any(), any(), any())).thenReturn(null);

        LineupCheck check = svc.forLeague(league(Sport.NBA, "in_season"), "me");

        assertTrue(check.available());
        assertEquals(21, check.week());
        // The regular-season stop NextMatchupService applies reads the playoff format; this gate must not.
        verify(leagues, never()).playoffFormat(anyLong());
    }

    @Test
    void aCompleteOrUndraftedLeagueIsNotInSeasonAndNeverAsksSleeper() {
        LineupCheckService svc = service(true);
        for (String status : List.of("complete", "pre_draft", "drafting")) {
            LineupCheck check = svc.forLeague(league(Sport.NBA, status), "me");
            assertFalse(check.available(), status);
            assertEquals(LineupCheck.NOT_IN_SEASON, check.reason(), status);
        }
        verifyNoInteractions(sleeper);
    }

    @Test
    void anonymousOrWithoutARosterThisSeasonIsNotMember() {
        LineupCheckService svc = service(true);
        when(rosterSeasons.forLeague(5L)).thenReturn(List.of(standing(4, 8L)));

        assertEquals(LineupCheck.NOT_MEMBER, svc.forLeague(league(Sport.NBA, "in_season"), null).reason());
        assertEquals(LineupCheck.NOT_MEMBER, svc.forLeague(league(Sport.NBA, "in_season"), "me").reason());
        verifyNoInteractions(sleeper);
    }

    @Test
    void theLeaderboardIsReadOnceWhateverTheFlagCountAndNotAtAllWhenNothingIsFlagged() {
        LineupCheckService svc = service(true);
        twoRostersForMe();
        when(stats.readLeaderboard(any(), any(), any())).thenReturn(null);

        svc.forLeague(league(Sport.NBA, "in_season"), "me");
        verify(stats, times(1)).readLeaderboard(any(), eq(WindowKind.SEASON), eq("me"));

        // Nobody flagged: the costly read is skipped.
        Player healthy = player("p-out", "Healthy Now", Position.PG, null);
        when(players.byIds(eq(Sport.NBA), any())).thenReturn(Map.of("p-out", healthy,
                "p-ok", player("p-ok", "Ok Guy", Position.SG, null)));
        svc.forLeague(league(Sport.NBA, "in_season"), "me");
        verify(stats, times(1)).readLeaderboard(any(), any(), any());
    }

    @Test
    void footballNeverReadsTheBasketballLeaderboard() {
        LineupCheckService svc = service(false);
        when(rosterSeasons.forLeague(5L)).thenReturn(List.of(standing(3, 7L)));
        Player out = new Player(1, Sport.NFL, "q1", "Qb One", List.of(Position.QB), "DAL", "Active", "Out", null, null);
        when(players.byIds(eq(Sport.NFL), any())).thenReturn(Map.of("q1", out));
        when(sleeper.rosters("L1")).thenReturn(List.of(roster(3, List.of("q1"), List.of("q1"))));
        LeagueRow nfl = new LeagueRow(5L, Sport.NFL, "L1", "Foot", 2026, 12, List.of("QB", "BN"), 1.0, null, "in_season");

        LineupCheck check = svc.forLeague(nfl, "me");

        assertEquals(1, check.flags().size());
        verifyNoInteractions(stats);
    }

    @Test
    void noStoredScheduleMeansNoGameCheckButStatusFlagsStillWork() {
        LineupCheckService svc = service(true);
        twoRostersForMe();
        when(schedules.forSeason(anyString(), org.mockito.ArgumentMatchers.anyInt())).thenReturn(List.of());
        when(stats.readLeaderboard(any(), any(), any())).thenReturn(null);

        LineupCheck check = svc.forLeague(league(Sport.NBA, "in_season"), "me");

        assertFalse(check.gameCheck());
        assertEquals(1, check.flags().size());
        assertNull(check.flags().get(0).options().get(0).gamesLeft());
    }

    // ------------------------------------------------------------------ fixes after code review 2026-10-20

    private static PlayerStatsService.LeaderboardRow row(String id, Double fp, Integer games) {
        AdvancedStats.Window window = games == null ? null
                : new AdvancedStats.Window(games, null, null, 0, 0, null, null, null, null, null, null, false, null);
        return new PlayerStatsService.LeaderboardRow(id, id, List.of("PG"), "ATL", null, null, true, null, window, fp,
                null, null, null, null, null, null, null, null, null);
    }

    private static PlayerStatsService.StatLeaderboard board(int season, Integer requested,
                                                            List<PlayerStatsService.LeaderboardRow> rows) {
        return new PlayerStatsService.StatLeaderboard("nba", season, requested, null, null, true, null, null, List.of(),
                WindowKind.SEASON, null, null, null, null, null, null, rows);
    }

    @Test
    void aFallbackBoardLabelsTheAveragesWithTheAnsweredSeasonAndItsGames() {
        LineupCheckService svc = service(true);
        twoRostersForMe();
        when(stats.readLeaderboard(any(), any(), any())).thenReturn(board(2025, 2026, List.of(
                row("p-bench", 31.4, 71), row("p-ok", 20.0, null))));

        LineupCheck.BenchOption o = svc.forLeague(league(Sport.NBA, "in_season"), "me")
                .flags().get(0).options().get(0);

        assertEquals(31.4, o.fpPerGame());
        assertEquals(2025, o.fpSeason(), "the answered season, not the requested 2026");
        assertEquals(71, o.fpGames());
    }

    @Test
    void aRowWithNoSeasonStatsIsSkippedAndTheMixedOptionsFallBackToNameOrder() {
        LineupCheckService svc = service(true);
        when(rosterSeasons.forLeague(5L)).thenReturn(List.of(standing(3, 7L)));
        Player out = player("p-out", "Out Guy", Position.PG, "Out");
        Player zed = player("p-zed", "Zed Bench", Position.PG, null);
        Player abe = player("p-abe", "Abe Bench", Position.PG, null);
        when(players.byIds(eq(Sport.NBA), any())).thenReturn(Map.of("p-out", out, "p-zed", zed, "p-abe", abe));
        when(sleeper.rosters("L1")).thenReturn(List.of(
                roster(3, List.of("p-out"), List.of("p-out", "p-zed", "p-abe"))));
        when(stats.readLeaderboard(any(), any(), any())).thenReturn(board(2026, null, List.of(
                row("p-zed", 31.4, 3), row("p-abe", 10.0, null))));

        LineupCheck.LineupFlag flag = svc.forLeague(league(Sport.NBA, "in_season"), "me").flags().get(0);

        assertEquals(LineupCheck.NAME, flag.optionsOrder());
        assertEquals(List.of("p-abe", "p-zed"), flag.options().stream().map(o -> o.player().sleeperPlayerId()).toList());
        assertNull(flag.options().get(0).fpPerGame());
    }

    @Test
    void theGameCheckReasonSaysWhyItDidNotRunAndAWeekMissingFromTheScheduleIsNotNoGames() {
        LineupCheckService svc = service(true);
        twoRostersForMe();
        when(stats.readLeaderboard(any(), any(), any())).thenReturn(null);

        // No stored schedule.
        when(schedules.forSeason(anyString(), org.mockito.ArgumentMatchers.anyInt())).thenReturn(List.of());
        LineupCheck none = svc.forLeague(league(Sport.NBA, "in_season"), "me");
        assertFalse(none.gameCheck());
        assertEquals(LineupCheck.NO_SCHEDULE, none.gameCheckReason());
        assertNull(none.scheduleAsOf());

        // A schedule that holds week 1 only, a league on week 30: not checked, and nobody is flagged "no game".
        when(schedules.forSeason(anyString(), org.mockito.ArgumentMatchers.anyInt())).thenReturn(List.of(
                new SportSchedule.Game("g1", 1, LocalDate.parse("2026-10-22"), "pre_game", "ATL", "BOS")));
        when(leagues.currentLeg(anyLong())).thenReturn(OptionalInt.of(30));
        LineupCheck missing = svc.forLeague(league(Sport.NBA, "in_season"), "me");
        assertFalse(missing.gameCheck());
        assertEquals(LineupCheck.WEEK_NOT_IN_SCHEDULE, missing.gameCheckReason());
        assertTrue(missing.flags().stream().noneMatch(f -> LineupCheck.NO_GAME_IN_WEEK.equals(f.reason())));

        // Sleeper has not said which week it is: the schedule may be fine.
        when(leagues.currentLeg(anyLong())).thenReturn(OptionalInt.empty());
        LineupCheck unknown = svc.forLeague(league(Sport.NBA, "in_season"), "me");
        assertFalse(unknown.gameCheck());
        assertEquals(LineupCheck.WEEK_UNKNOWN, unknown.gameCheckReason());

        // Week held: checked, no reason.
        when(leagues.currentLeg(anyLong())).thenReturn(OptionalInt.of(1));
        LineupCheck ok = svc.forLeague(league(Sport.NBA, "in_season"), "me");
        assertTrue(ok.gameCheck());
        assertNull(ok.gameCheckReason());
    }

    @Test
    void theSchedulesFetchedAtRidesOnTheCheck() {
        LineupCheckService svc = service(true);
        twoRostersForMe();
        when(stats.readLeaderboard(any(), any(), any())).thenReturn(null);
        java.time.OffsetDateTime at = java.time.OffsetDateTime.parse("2026-10-20T11:00:00Z");
        when(schedules.fetchedAt(anyString(), org.mockito.ArgumentMatchers.anyInt())).thenReturn(Optional.of(at));

        assertEquals(at, svc.forLeague(league(Sport.NBA, "in_season"), "me").scheduleAsOf());
    }
}
