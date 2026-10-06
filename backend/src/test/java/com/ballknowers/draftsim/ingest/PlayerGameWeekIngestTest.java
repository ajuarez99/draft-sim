package com.ballknowers.draftsim.ingest;

import com.ballknowers.draftsim.domain.Sport;
import com.ballknowers.draftsim.refresh.RefreshProperties;
import com.ballknowers.draftsim.sport.SportRules;
import com.ballknowers.draftsim.sport.SportRulesRegistry;
import com.ballknowers.draftsim.store.LeagueRepository;
import com.ballknowers.draftsim.store.PlayerAbsenceRepository;
import com.ballknowers.draftsim.store.PlayerGameRepository;
import com.ballknowers.draftsim.store.RosterWeekPointsRepository;
import com.ballknowers.draftsim.store.SportScheduleRepository;
import com.ballknowers.draftsim.store.SportWeekStatsRepository;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;

import java.io.InputStream;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

/**
 * The per-week rebuild of the per-game ingest (specs/009-auto-data-refresh,
 * research R6, T012). Fixture-driven: the entries and schedule rows under
 * {@code src/test/resources/sleeper/} were fetched from Sleeper on 2026-09-28 and
 * trimmed, not written by hand. The repositories and the upstream client are
 * mocks; what is asserted is what the service asks them to write.
 *
 * <p>The old walk's own rules (played routing, the future-or-today guard, the
 * stale-row clean-ups) are kept and re-asserted here, because the rebuild is
 * only allowed to change where entries come from, not what is done with them.
 */
class PlayerGameWeekIngestTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private record Fixture(List<Map<String, Object>> stats, List<Map<String, Object>> schedule) {}

    private static Fixture fixture(String name) throws Exception {
        try (InputStream in = PlayerGameWeekIngestTest.class.getResourceAsStream("/sleeper/" + name)) {
            assertNotNull(in, "missing fixture " + name);
            Map<String, List<Map<String, Object>>> m = MAPPER.readValue(in, new TypeReference<>() {});
            return new Fixture(m.get("stats"), m.get("schedule"));
        }
    }

    /** Basketball's played signal: a non-empty box score (spec 008 research R9). */
    private static SportRules basketballRules() {
        SportRules rules = mock(SportRules.class);
        when(rules.playedIn(any())).thenAnswer(inv -> {
            Map<?, ?> stats = inv.getArgument(0);
            return stats != null && !stats.isEmpty();
        });
        return rules;
    }

    /** Football's played signal: {@code gp > 0}. */
    private static SportRules footballRules() {
        SportRules rules = mock(SportRules.class);
        when(rules.playedIn(any())).thenAnswer(inv -> {
            Map<?, ?> stats = inv.getArgument(0);
            Object gp = stats == null ? null : stats.get("gp");
            return gp instanceof Number n && n.doubleValue() > 0;
        });
        return rules;
    }

    /** All collaborators, mocked, with one league of {@code sport} rostering {@code rostered}. */
    private static final class Rig {
        final SleeperPlayerStatsClient stats = mock(SleeperPlayerStatsClient.class);
        final LeagueRepository leagues = mock(LeagueRepository.class);
        final RosterWeekPointsRepository weekPoints = mock(RosterWeekPointsRepository.class);
        final PlayerGameRepository games = mock(PlayerGameRepository.class);
        final PlayerAbsenceRepository absences = mock(PlayerAbsenceRepository.class);
        final SportWeekStatsRepository weekStats = mock(SportWeekStatsRepository.class);
        final SportRulesRegistry registry = mock(SportRulesRegistry.class);
        final SportScheduleRepository scheduleRepository = mock(SportScheduleRepository.class);
        final Sport sport;
        final int season;

        Rig(Sport sport, SportRules rules, int season, List<String> rostered, Fixture f, int fixtureWeek) {
            this.sport = sport;
            this.season = season;
            when(registry.get(sport)).thenReturn(rules);
            when(leagues.all()).thenReturn(List.of(
                    new LeagueRepository.LeagueRow(7L, sport, "L1", "Ball Knowers", season, 12,
                            List.of(), 0.0, null, null)));
            StringBuilder json = new StringBuilder("{");
            for (int i = 0; i < rostered.size(); i++) {
                json.append(i == 0 ? "" : ",").append('"').append(rostered.get(i)).append("\":1.0");
            }
            json.append("}");
            when(weekPoints.breakdownsFor(7L, season)).thenReturn(List.of(
                    new RosterWeekPointsRepository.WeekBreakdown(fixtureWeek, 1, 0.0, json.toString(), null)));
            when(stats.schedule(sport.code(), season)).thenReturn(f.schedule());
            // Other weeks: one inert entry (no stats, nobody rostered), not an empty list --
            // an empty payload for a week with a complete game now counts as a failed fetch.
            when(stats.week(eq(sport.code()), eq(season), anyInt())).thenAnswer(inv ->
                    List.of(Map.<String, Object>of("player_id", "FILLER", "week", inv.<Integer>getArgument(2))));
            when(stats.week(sport.code(), season, fixtureWeek)).thenReturn(f.stats());
        }

        PlayerGameIngestService service() {
            return new PlayerGameIngestService(stats, leagues, weekPoints, games, absences, weekStats, registry, scheduleRepository);
        }

        List<PlayerAbsenceRepository.Row> absenceUpserts() {
            ArgumentCaptor<PlayerAbsenceRepository.Row> cap = ArgumentCaptor.forClass(PlayerAbsenceRepository.Row.class);
            verify(absences, atLeast(0)).upsert(cap.capture());
            return cap.getAllValues();
        }

        List<PlayerGameRepository.Row> gameUpserts() {
            ArgumentCaptor<PlayerGameRepository.Row> cap = ArgumentCaptor.forClass(PlayerGameRepository.Row.class);
            verify(games, atLeast(0)).upsert(cap.capture());
            return cap.getAllValues();
        }
    }

    private static final Instant AFTER_NBA_WEEK_10 = Instant.parse("2026-01-15T12:00:00Z");
    private static final Instant AFTER_NFL_WEEK_5 = Instant.parse("2025-10-20T12:00:00Z");

    private static Rig nba(List<String> rostered) throws Exception {
        return new Rig(Sport.NBA, basketballRules(), 2025, rostered, fixture("nba-2025-w10.json"), 10);
    }

    private static Rig nfl(List<String> rostered) throws Exception {
        return new Rig(Sport.NFL, footballRules(), 2025, rostered, fixture("nfl-2025-w5.json"), 5);
    }

    // ------------------------------------------------------------ played entries

    /**
     * A played entry becomes a player_game row whose is_away comes from the
     * schedule. LaRavia (2444) was LAL, away at PHX, in the first game.
     */
    @Test
    void aPlayedEntryBecomesARowWhoseIsAwayComesFromTheSchedule() throws Exception {
        Rig rig = nba(List.of("2444"));

        PlayerGameIngestService.Result r = rig.service().refreshSportSeason(Sport.NBA, 2025, AFTER_NBA_WEEK_10);

        assertEquals(3, r.gamesStored());
        Map<String, PlayerGameRepository.Row> byGame = new HashMap<>();
        for (PlayerGameRepository.Row g : rig.gameUpserts()) byGame.put(g.gameId(), g);
        assertEquals(3, byGame.size());
        assertEquals(Boolean.TRUE, byGame.get("1261814783951241216").isAway(), "LAL away at PHX");
        assertEquals("PHX", byGame.get("1261814783951241216").opponent());
        assertEquals(Boolean.FALSE, byGame.get("1261029465123725312").isAway(), "LAL home vs HOU");
        assertEquals(Boolean.FALSE, byGame.get("1261814802817220608").isAway(), "LAL home vs SAC");
        assertEquals(10, byGame.get("1261814783951241216").week());
    }

    /** No matching schedule game means unknown, never defaulted to home. */
    @Test
    void isAwayIsUnknownWhenTheScheduleDoesNotHaveTheGame() throws Exception {
        Fixture f = fixture("nba-2025-w10.json");
        List<Map<String, Object>> trimmedSchedule = new ArrayList<>(f.schedule());
        trimmedSchedule.removeIf(g -> "1261814783951241216".equals(g.get("game_id")));
        Rig rig = new Rig(Sport.NBA, basketballRules(), 2025, List.of("2444"),
                new Fixture(f.stats(), trimmedSchedule), 10);

        rig.service().refreshSportSeason(Sport.NBA, 2025, AFTER_NBA_WEEK_10);

        PlayerGameRepository.Row row = rig.gameUpserts().stream()
                .filter(g -> g.gameId().equals("1261814783951241216")).findFirst().orElseThrow();
        assertNull(row.isAway());
    }

    /** Rows are written for every player in the payload, not only rostered ones. */
    @Test
    void rowsAreWrittenForPlayersNobodyRosters() throws Exception {
        Rig rig = nba(List.of("not-in-the-fixture"));

        PlayerGameIngestService.Result r = rig.service().refreshSportSeason(Sport.NBA, 2025, AFTER_NBA_WEEK_10);

        assertEquals(3, r.gamesStored(), "LaRavia is not rostered here and his three games are still stored");
    }

    // ----------------------------------------------------------- empty-stats entries

    /** An empty-stats entry dated in the past is an ENTRY_WITHOUT_PLAY absence, with the game's id and team. */
    @Test
    void anEmptyStatsEntryInThePastBecomesAnEntryWithoutPlayAbsence() throws Exception {
        Rig rig = nba(List.of());

        PlayerGameIngestService.Result r = rig.service().refreshSportSeason(Sport.NBA, 2025, AFTER_NBA_WEEK_10);

        assertEquals(3, r.absencesStored());
        List<PlayerAbsenceRepository.Row> ups = rig.absenceUpserts();
        assertEquals(3, ups.size());
        assertTrue(ups.stream().allMatch(a -> a.basis().equals("ENTRY_WITHOUT_PLAY")));
        PlayerAbsenceRepository.Row lal = ups.stream().filter(a -> a.playerId().equals("2637")).findFirst().orElseThrow();
        assertEquals("1261814783951241216", lal.gameId());
        assertEquals("LAL", lal.team());
        assertEquals(LocalDate.parse("2025-12-23"), lal.gameDate());
        assertEquals(10, lal.week());
    }

    /** Dated today or later, an empty-stats entry is a scheduled game, not a missed one: nothing is written. */
    @Test
    void anEmptyStatsEntryDatedTodayWritesNothing() throws Exception {
        Rig rig = nba(List.of());
        // "Today" is the game's own date, 2025-12-23: the three empty entries are dated today.
        Instant onTheDay = Instant.parse("2025-12-23T15:00:00Z");

        PlayerGameIngestService.Result r = rig.service().refreshSportSeason(Sport.NBA, 2025, onTheDay);

        assertEquals(0, r.absencesStored());
        verify(rig.absences, never()).upsert(any());
        verify(rig.games, never()).deleteByGame(any(), anyInt(), anyString(), anyString());
    }

    // -------------------------------------------------------------------- football

    @Test
    void aFootballEntryWithNoGpIsAnAbsenceNotAGameAndTheScheduleGivesIsAway() throws Exception {
        Rig rig = nfl(List.of());

        PlayerGameIngestService.Result r = rig.service().refreshSportSeason(Sport.NFL, 2025, AFTER_NFL_WEEK_5);

        assertEquals(1, r.gamesStored(), "McCaffrey's played game");
        assertEquals(1, r.absencesStored(), "the DNP entry (no gp)");
        PlayerGameRepository.Row mccaffrey = rig.gameUpserts().get(0);
        assertEquals("4034", mccaffrey.sleeperPlayerId());
        assertEquals("202510532", mccaffrey.gameId());
        assertEquals(Boolean.TRUE, mccaffrey.isAway(), "SF away at LAR, from a schedule whose sides are bare strings");
        assertEquals("ENTRY_WITHOUT_PLAY", rig.absenceUpserts().get(0).basis());
    }

    /** A rostered player with no entry in a week his team played: TEAM_PLAYED_NO_ENTRY. */
    @Test
    void aRosteredPlayerWithNoEntryInAWeekHisTeamPlayedGetsTeamPlayedNoEntry() throws Exception {
        Rig rig = nfl(List.of("P1"));
        // P1's only stored game: week 4, ARI vs SEA, he faced ARI so he is SEA. SEA plays in week 5.
        when(rig.games.forPlayers(eq(Sport.NFL), eq(2025), any())).thenReturn(List.of(
                new PlayerGameRepository.Row(Sport.NFL, 2025, 4, "P1", "202510401",
                        LocalDate.parse("2025-09-25"), "ARI", null, "{\"gp\":1.0}")));

        rig.service().refreshSportSeason(Sport.NFL, 2025, AFTER_NFL_WEEK_5);

        List<PlayerAbsenceRepository.Row> mine = rig.absenceUpserts().stream()
                .filter(a -> a.playerId().equals("P1")).toList();
        // Weeks 1-3 and 5 have no entry. Only week 5 is a week SEA played per the (fixture's) schedule.
        assertEquals(1, mine.size());
        assertEquals("TEAM_PLAYED_NO_ENTRY", mine.get(0).basis());
        assertEquals(5, mine.get(0).week());
        assertEquals("SEA", mine.get(0).team());
        assertNull(mine.get(0).gameId());
    }

    /** ATL is on a bye in week 5 (no scheduled game): a missing entry is not an absence. */
    @Test
    void aByeWeekWritesNothing() throws Exception {
        Rig rig = nfl(List.of("P2"));
        // week 4, ATL vs WAS; he faced WAS so he is ATL.
        when(rig.games.forPlayers(eq(Sport.NFL), eq(2025), any())).thenReturn(List.of(
                new PlayerGameRepository.Row(Sport.NFL, 2025, 4, "P2", "202510402",
                        LocalDate.parse("2025-09-28"), "WAS", null, "{\"gp\":1.0}")));

        PlayerGameIngestService.Result r = rig.service().refreshSportSeason(Sport.NFL, 2025, AFTER_NFL_WEEK_5);

        assertTrue(rig.absenceUpserts().stream().noneMatch(a -> a.playerId().equals("P2")),
                "ATL has no game in week 5: no TEAM_PLAYED_NO_ENTRY, and nothing invented for weeks 1-3");
        assertEquals(0, r.weeksUnclassified());
    }

    /** No stored entry anywhere, so no team: UNCLASSIFIED for every loaded week, not a guess. */
    @Test
    void aPlayerWhoseTeamIsUnknownIsUnclassified() throws Exception {
        Rig rig = nfl(List.of("P3"));

        PlayerGameIngestService.Result r = rig.service().refreshSportSeason(Sport.NFL, 2025, AFTER_NFL_WEEK_5);

        List<PlayerAbsenceRepository.Row> mine = rig.absenceUpserts().stream()
                .filter(a -> a.playerId().equals("P3")).toList();
        assertEquals(5, mine.size(), "weeks 1..5");
        assertTrue(mine.stream().allMatch(a -> a.basis().equals("UNCLASSIFIED")
                && a.gameId() == null && a.gameDate() == null && a.team() == null));
        assertEquals(5, r.weeksUnclassified());
    }

    /** A player rostered in no league of the sport-season never gets a no-entry row. */
    @Test
    void aPlayerNobodyRostersNeverGetsANoEntryRow() throws Exception {
        Rig rig = nfl(List.of("someone-else"));

        rig.service().refreshSportSeason(Sport.NFL, 2025, AFTER_NFL_WEEK_5);

        // 4034 and 11065 have entries in the fixture and are not rostered: their only absence
        // row is the ENTRY_WITHOUT_PLAY the payload itself carries.
        for (PlayerAbsenceRepository.Row a : rig.absenceUpserts()) {
            if (a.playerId().equals("someone-else")) continue;
            assertEquals("ENTRY_WITHOUT_PLAY", a.basis(), "unexpected row for " + a.playerId());
        }
    }

    // ------------------------------------------------------- weeks and failure handling

    @Test
    void finalWeeksAreNotRefetched() throws Exception {
        Rig rig = nba(List.of());
        List<SportWeekStatsRepository.Row> done = new ArrayList<>();
        for (int w = 1; w <= 9; w++) {
            done.add(new SportWeekStatsRepository.Row(Sport.NBA, 2025, w, AFTER_NBA_WEEK_10, true));
        }
        when(rig.weekStats.forSeason(Sport.NBA, 2025)).thenReturn(done);

        PlayerGameIngestService.Result r = rig.service().refreshSportSeason(Sport.NBA, 2025, AFTER_NBA_WEEK_10);

        assertEquals(1, r.weeksFetched());
        verify(rig.stats, never()).week(anyString(), anyInt(), eq(1));
        verify(rig.stats, times(1)).week("nba", 2025, 10);
    }

    @Test
    void anUpToDateSeasonFetchesNoWeeksAtAll() throws Exception {
        Rig rig = nba(List.of());
        List<SportWeekStatsRepository.Row> done = new ArrayList<>();
        for (int w = 1; w <= 10; w++) {
            done.add(new SportWeekStatsRepository.Row(Sport.NBA, 2025, w, AFTER_NBA_WEEK_10, true));
        }
        when(rig.weekStats.forSeason(Sport.NBA, 2025)).thenReturn(done);

        PlayerGameIngestService.Result r = rig.service().refreshSportSeason(Sport.NBA, 2025, AFTER_NBA_WEEK_10);

        assertEquals(0, r.weeksFetched());
        verify(rig.stats, never()).week(anyString(), anyInt(), anyInt());
        verify(rig.weekStats, never()).upsert(any());
    }

    /** Weeks after the last started game are not fetched: the schedule bounds the walk. */
    @Test
    void weeksAfterTheLastStartedGameAreNotFetched() throws Exception {
        Rig rig = nba(List.of());

        rig.service().refreshSportSeason(Sport.NBA, 2025, AFTER_NBA_WEEK_10);

        verify(rig.stats, never()).week(anyString(), anyInt(), eq(11));
        verify(rig.stats, times(1)).week("nba", 2025, 10);
    }

    /** A failed week is counted, not marked fetched, and nobody is judged missing from data we don't have. */
    @Test
    void aFailedWeekIsCountedLeftUnmarkedAndNotUsedToJudgeAbsences() throws Exception {
        Rig rig = nfl(List.of("P3"));
        when(rig.stats.week("nfl", 2025, 3)).thenThrow(new RuntimeException("upstream said no"));

        PlayerGameIngestService.Result r = rig.service().refreshSportSeason(Sport.NFL, 2025, AFTER_NFL_WEEK_5);

        assertEquals(1, r.weeksFailed());
        assertEquals(4, r.weeksFetched());
        ArgumentCaptor<SportWeekStatsRepository.Row> cap = ArgumentCaptor.forClass(SportWeekStatsRepository.Row.class);
        verify(rig.weekStats, atLeast(1)).upsert(cap.capture());
        assertTrue(cap.getAllValues().stream().noneMatch(x -> x.week() == 3), "week 3 must stay un-marked");
        assertTrue(rig.absenceUpserts().stream().noneMatch(a -> a.playerId().equals("P3") && a.week() == 3));
        assertEquals(4, r.weeksUnclassified(), "P3 is unclassified in the four weeks that loaded");
    }

    @Test
    void anEmptyPayloadForAWeekTheScheduleSaysWasPlayedIsAFailedWeekNotAFetchedOne() throws Exception {
        // Review fix 2026-09-28: Sleeper answering [] (or a non-list, which mapsOf turns
        // into []) for a week with a complete game must not be marked final or judged.
        Rig rig = nfl(List.of("P1"));
        when(rig.stats.week("nfl", 2025, 5)).thenReturn(List.of());
        when(rig.games.forPlayers(eq(Sport.NFL), eq(2025), any())).thenReturn(List.of(
                new PlayerGameRepository.Row(Sport.NFL, 2025, 4, "P1", "202510401",
                        LocalDate.parse("2025-09-25"), "ARI", null, "{\"gp\":1.0}")));

        PlayerGameIngestService.Result r = rig.service().refreshSportSeason(Sport.NFL, 2025, AFTER_NFL_WEEK_5);

        assertTrue(r.weeksFailed() >= 1, "week 5 has a complete game in the schedule but no entries");
        ArgumentCaptor<SportWeekStatsRepository.Row> cap = ArgumentCaptor.forClass(SportWeekStatsRepository.Row.class);
        verify(rig.weekStats, atLeast(0)).upsert(cap.capture());
        assertTrue(cap.getAllValues().stream().noneMatch(x -> x.week() == 5), "week 5 must stay un-marked");
        assertTrue(rig.absenceUpserts().stream().noneMatch(a -> a.week() == 5),
                "no TEAM_PLAYED_NO_ENTRY may be written from a payload that is probably wrong");
    }

    // ------------------------------------------------------ the old walk's clean-up rules

    /** A game stored as an absence and now played deletes the stale absence. */
    @Test
    void aGameStoredAsAnAbsenceAndNowPlayedDeletesTheStaleAbsence() throws Exception {
        Rig rig = nba(List.of());
        when(rig.absences.forSeason(Sport.NBA, 2025)).thenReturn(List.of(
                new PlayerAbsenceRepository.Row(Sport.NBA, 2025, 10, "2444", "1261814783951241216",
                        LocalDate.parse("2025-12-23"), "LAL", "ENTRY_WITHOUT_PLAY")));

        rig.service().refreshSportSeason(Sport.NBA, 2025, AFTER_NBA_WEEK_10);

        verify(rig.absences, times(1)).deleteByGame(Sport.NBA, 2025, "2444", "1261814783951241216");
    }

    /** A game stored as played and now a DNP deletes the stale player_game row. */
    @Test
    void aGameStoredAsPlayedAndNowDnpDeletesTheStalePlayerGameRow() throws Exception {
        Rig rig = nba(List.of());
        when(rig.games.forWeek(Sport.NBA, 2025, 10)).thenReturn(List.of(
                new PlayerGameRepository.Row(Sport.NBA, 2025, 10, "2637", "1261814783951241216",
                        LocalDate.parse("2025-12-23"), "PHX", null, "{\"pts\":2.0}")));

        rig.service().refreshSportSeason(Sport.NBA, 2025, AFTER_NBA_WEEK_10);

        verify(rig.games, times(1)).deleteByGame(Sport.NBA, 2025, "2637", "1261814783951241216");
    }

    /** A week-level neighbour-team row is cleared once a real entry for that week exists. */
    @Test
    void aWeekLevelRowIsClearedWhenARealEntryAppears() throws Exception {
        Rig rig = nba(List.of());
        when(rig.absences.forSeason(Sport.NBA, 2025)).thenReturn(List.of(
                new PlayerAbsenceRepository.Row(Sport.NBA, 2025, 10, "2444", null, null, "LAL",
                        "TEAM_PLAYED_NO_ENTRY")));

        rig.service().refreshSportSeason(Sport.NBA, 2025, AFTER_NBA_WEEK_10);

        verify(rig.absences, times(1)).deleteWeeklyBasis(Sport.NBA, 2025, "2444", 10);
    }

    // ------------------------------------------------------------------- finality

    private static SportSchedule schedule(String... statusAndDate) {
        List<Map<String, Object>> raw = new ArrayList<>();
        for (int i = 0; i < statusAndDate.length; i += 2) {
            Map<String, Object> g = new HashMap<>();
            g.put("game_id", "g" + i);
            g.put("week", 3);
            g.put("status", statusAndDate[i]);
            g.put("date", statusAndDate[i + 1]);
            g.put("home", "AAA");
            g.put("away", "BBB");
            raw.add(g);
        }
        return SportSchedule.parse(raw);
    }

    /** The end of the week's last game date (UTC), from which the 48 h window is measured. */
    private static final Instant WEEK_ENDED = LocalDate.parse("2025-11-10").plusDays(1)
            .atStartOfDay(ZoneOffset.UTC).toInstant();

    @Test
    void aWeekIsFinalOnlyOnceFetchedAtLeastFortyEightHoursAfterItsLastGame() {
        var s = schedule("complete", "2025-11-08", "complete", "2025-11-10");
        Instant exactly = WEEK_ENDED.plus(RefreshProperties.WEEK_FINAL_AFTER);

        assertFalse(s.isFinal(3, exactly.minusSeconds(1)));
        assertTrue(s.isFinal(3, exactly));
        assertTrue(s.isFinal(3, exactly.plusSeconds(3600)));
        assertFalse(s.isFinal(3, Instant.parse("2025-11-09T00:00:00Z")), "fetched mid-week");
    }

    @Test
    void aWeekWithAnUnsettledGameIsNeverFinal() {
        var s = schedule("complete", "2025-11-08", "scheduled", "2025-11-10");

        assertFalse(s.isFinal(3, Instant.parse("2030-01-01T00:00:00Z")));
    }

    /** Postponed and canceled games sit in the schedule forever; they must not hold a week open. */
    @Test
    void aPostponedOrCanceledGameDoesNotKeepAWeekOpen() {
        var s = schedule("complete", "2025-11-08", "postponed", "2025-11-09", "canceled", "2025-11-10");

        assertTrue(s.isFinal(3, Instant.parse("2030-01-01T00:00:00Z")));
    }

    @Test
    void aWeekTheScheduleDoesNotKnowIsNotFinal() {
        var s = schedule("complete", "2025-11-08");

        assertFalse(s.isFinal(4, Instant.parse("2030-01-01T00:00:00Z")));
    }

    @Test
    void theServiceMarksAWeekFinalOnlyWhenTheFetchWasLateEnough() throws Exception {
        Rig early = nba(List.of());
        early.service().refreshSportSeason(Sport.NBA, 2025, Instant.parse("2025-12-29T12:00:00Z"));
        ArgumentCaptor<SportWeekStatsRepository.Row> e = ArgumentCaptor.forClass(SportWeekStatsRepository.Row.class);
        verify(early.weekStats, atLeast(1)).upsert(e.capture());
        assertFalse(e.getAllValues().stream().filter(x -> x.week() == 10).findFirst().orElseThrow().fin(),
                "the last game was 2025-12-28; a fetch the next day is inside the window");

        Rig late = nba(List.of());
        late.service().refreshSportSeason(Sport.NBA, 2025, AFTER_NBA_WEEK_10);
        ArgumentCaptor<SportWeekStatsRepository.Row> l = ArgumentCaptor.forClass(SportWeekStatsRepository.Row.class);
        verify(late.weekStats, atLeast(1)).upsert(l.capture());
        assertTrue(l.getAllValues().stream().filter(x -> x.week() == 10).findFirst().orElseThrow().fin());
    }

    // ------------------------------------------------------------ schedule shape

    @Test
    void theHomeAndAwayTeamAreResolvedFromEitherPayloadShape() {
        assertEquals("LAL", SportSchedule.sideTeam(Map.of("team", "LAL", "points", 3)));
        assertEquals("ATL", SportSchedule.sideTeam("ATL"));
        assertNull(SportSchedule.sideTeam(null));
        assertNull(SportSchedule.sideTeam(new HashMap<String, Object>()));
    }

    // ---------------------------------------------------------------- single flight

    /** Two concurrent callers for one sport-season share one run: one schedule fetch, equal results. */
    @Test
    void concurrentRefreshesOfOneSportSeasonShareOneRun() throws Exception {
        Rig rig = nba(List.of());
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        Fixture f = fixture("nba-2025-w10.json");
        when(rig.stats.schedule("nba", 2025)).thenAnswer(inv -> {
            entered.countDown();
            assertTrue(release.await(10, TimeUnit.SECONDS));
            return f.schedule();
        });
        PlayerGameIngestService service = rig.service();

        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            Future<PlayerGameIngestService.Result> first =
                    pool.submit(() -> service.refreshSportSeason(Sport.NBA, 2025, AFTER_NBA_WEEK_10));
            assertTrue(entered.await(10, TimeUnit.SECONDS));
            Future<PlayerGameIngestService.Result> second =
                    pool.submit(() -> service.refreshSportSeason(Sport.NBA, 2025, AFTER_NBA_WEEK_10));
            Thread.sleep(300); // let the second caller reach the in-flight entry
            release.countDown();

            assertEquals(first.get(10, TimeUnit.SECONDS), second.get(10, TimeUnit.SECONDS));
            verify(rig.stats, times(1)).schedule("nba", 2025);
        } finally {
            pool.shutdownNow();
        }
    }

    /** Once a run ends the key is free again: a later call runs for real. */
    @Test
    void aFinishedRunDoesNotBlockTheNextOne() throws Exception {
        Rig rig = nba(List.of());
        PlayerGameIngestService service = rig.service();

        service.refreshSportSeason(Sport.NBA, 2025, AFTER_NBA_WEEK_10);
        service.refreshSportSeason(Sport.NBA, 2025, AFTER_NBA_WEEK_10);

        verify(rig.stats, times(2)).schedule("nba", 2025);
    }

    /** A failed run must not poison the key either. */
    @Test
    void aFailedRunFreesTheKeyAndPropagates() throws Exception {
        Rig rig = nba(List.of());
        when(rig.stats.schedule("nba", 2025)).thenThrow(new IllegalStateException("no schedule"))
                .thenReturn(fixture("nba-2025-w10.json").schedule());
        PlayerGameIngestService service = rig.service();

        assertThrows(IllegalStateException.class,
                () -> service.refreshSportSeason(Sport.NBA, 2025, AFTER_NBA_WEEK_10));
        assertEquals(3, service.refreshSportSeason(Sport.NBA, 2025, AFTER_NBA_WEEK_10).absencesStored());
    }

    // ------------------------------------------------- stored schedule (specs/017 T014)

    /** (a) The parsed schedule is stored, and only after every week has been fetched. */
    @Test
    @SuppressWarnings("unchecked")
    void aRefreshStoresTheParsedScheduleAfterTheWeeks() throws Exception {
        Rig rig = nba(List.of("2444"));

        PlayerGameIngestService.Result r = rig.service().refreshSportSeason(Sport.NBA, 2025, AFTER_NBA_WEEK_10);

        assertFalse(r.scheduleStoreFailed());
        ArgumentCaptor<List<SportSchedule.Game>> stored = ArgumentCaptor.forClass(List.class);
        InOrder order = inOrder(rig.stats, rig.scheduleRepository);
        order.verify(rig.stats).week(Sport.NBA.code(), 2025, 10);
        order.verify(rig.scheduleRepository).replaceSeason(eq("nba"), eq(2025), stored.capture(),
                eq(java.time.OffsetDateTime.ofInstant(AFTER_NBA_WEEK_10, ZoneOffset.UTC)));
        assertEquals(SportSchedule.parse(rig.stats.schedule("nba", 2025)).games().size(), stored.getValue().size());
        assertFalse(stored.getValue().isEmpty());
    }

    /** (b) A schedule the client returns empty is passed on as an empty list, which the repository ignores. */
    @Test
    void anEmptyScheduleIsHandedToTheRepositoryAsAnEmptyList() throws Exception {
        Rig rig = nba(List.of());
        when(rig.stats.schedule("nba", 2025)).thenReturn(List.of());

        PlayerGameIngestService.Result r = rig.service().refreshSportSeason(Sport.NBA, 2025, AFTER_NBA_WEEK_10);

        assertFalse(r.scheduleStoreFailed());
        verify(rig.scheduleRepository).replaceSeason(eq("nba"), eq(2025), eq(List.of()), any());
    }

    /** (c) A storage failure is reported, never thrown, and the per-game rows still land. */
    @Test
    void aFailedScheduleWriteStillStoresPerGameRowsAndSaysSo() throws Exception {
        Rig rig = nba(List.of("2444"));
        doThrow(new RuntimeException("boom")).when(rig.scheduleRepository).replaceSeason(any(), anyInt(), any(), any());

        PlayerGameIngestService.Result r = rig.service().refreshSportSeason(Sport.NBA, 2025, AFTER_NBA_WEEK_10);

        assertTrue(r.scheduleStoreFailed());
        assertEquals(3, r.gamesStored());
        assertEquals(3, rig.gameUpserts().size());
    }

    // --------------------------------------------------------------- manual entry point

    @Test
    void anUnknownLeagueDoesNothing() throws Exception {
        Rig rig = nba(List.of());
        when(rig.leagues.bySleeperId("nope")).thenReturn(java.util.Optional.empty());

        var r = rig.service().ingest("nope", 2025);

        assertEquals(new PlayerGameIngestService.Result(0, 0, 0, 0, 0, false), r);
        verifyNoInteractions(rig.stats, rig.games, rig.absences, rig.weekStats);
    }

    @Test
    void theManualEndpointResolvesTheLeaguesSportAndSeason() throws Exception {
        Rig rig = nba(List.of());
        when(rig.leagues.bySleeperId("L1")).thenReturn(java.util.Optional.of(
                new LeagueRepository.LeagueRow(7L, Sport.NBA, "L1", "Ball Knowers", 2025, 12,
                        List.of(), 0.0, null, null)));

        // now = the real clock, so every fixture game is in the past.
        var r = rig.service().ingest("L1", null);

        assertEquals(3, r.gamesStored());
        verify(rig.stats).schedule("nba", 2025);
    }
}
