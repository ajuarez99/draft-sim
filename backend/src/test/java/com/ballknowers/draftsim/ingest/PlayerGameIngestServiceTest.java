package com.ballknowers.draftsim.ingest;

import com.ballknowers.draftsim.domain.Sport;
import com.ballknowers.draftsim.store.PlayerGameRepository;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The pure half of the per-game ingest: turning one upstream entry into a row,
 * which is where every "we invented a fact" bug would live
 * (specs/005-daily-weekly-top-players, US1). The repository walk is left to an
 * integration test, per the split {@code WeeklyReportServiceTest} describes.
 */
class PlayerGameIngestServiceTest {

    private static Map<String, Object> entry(Object... kv) {
        Map<String, Object> m = new HashMap<>();
        for (int i = 0; i < kv.length; i += 2) m.put((String) kv[i], kv[i + 1]);
        return m;
    }

    private static Map<String, Object> fullEntry() {
        return entry(
                "game_id", "1261029460694540288",
                "date", "2025-11-17",
                "week", 5,
                "opponent", "CHI",
                "is_away_team", false,
                "stats", Map.of("pts", 36.0, "reb", 18.0, "ast", 13.0));
    }

    @Test
    void mapsAGameToARow() {
        PlayerGameRepository.Row r = PlayerGameIngestService.toRow(Sport.NBA, 2025, "1658", fullEntry());

        assertNotNull(r);
        assertEquals("1658", r.sleeperPlayerId());
        assertEquals("1261029460694540288", r.gameId());
        assertEquals("2025-11-17", r.gameDate().toString());
        assertEquals("CHI", r.opponent());
        assertEquals(Boolean.FALSE, r.isAway());
        assertEquals(5, r.week());
    }

    /**
     * Research R6, and the spec's postponed-game edge case in one assertion.
     *
     * <p>The week comes from the entry's own field. If it were derived from the
     * date, a game moved into another week would land in the week it was
     * scheduled rather than the week it was played -- and this service would
     * need to know about calendars, which it should not.
     */
    @Test
    void theWeekComesFromTheEntryNotTheDate() {
        Map<String, Object> postponed = fullEntry();
        postponed.put("week", 9);
        postponed.put("date", "2025-11-17");

        PlayerGameRepository.Row r = PlayerGameIngestService.toRow(Sport.NBA, 2025, "1658", postponed);

        assertEquals(9, r.week(), "the entry said week 9; the date must not override it");
        assertEquals("2025-11-17", r.gameDate().toString());
    }

    /**
     * A game with no id cannot be deduplicated, one with no date cannot be a
     * "night", and one with no week cannot be placed. Dropping it is honest;
     * inventing any of the three is not.
     */
    @Test
    void anEntryMissingAnIdentifyingFieldIsDroppedRatherThanInvented() {
        for (String missing : new String[] {"game_id", "date", "week"}) {
            Map<String, Object> e = fullEntry();
            e.remove(missing);
            assertNull(PlayerGameIngestService.toRow(Sport.NBA, 2025, "1658", e),
                    "an entry without " + missing + " must not become a row");
        }
    }

    /** A week that is not a number is missing, not zero. */
    @Test
    void aNonNumericWeekIsTreatedAsMissing() {
        Map<String, Object> e = fullEntry();
        e.put("week", "five");
        assertNull(PlayerGameIngestService.toRow(Sport.NBA, 2025, "1658", e));
    }

    /** An unparseable date is dropped rather than defaulted to today. */
    @Test
    void anUnparseableDateIsDropped() {
        Map<String, Object> e = fullEntry();
        e.put("date", "Nov 17, 2025");
        assertNull(PlayerGameIngestService.toRow(Sport.NBA, 2025, "1658", e));
    }

    /** A game with no stat line is not a performance worth ranking. */
    @Test
    void anEntryWithNoStatsIsDropped() {
        Map<String, Object> e = fullEntry();
        e.put("stats", Map.of());
        assertNull(PlayerGameIngestService.toRow(Sport.NBA, 2025, "1658", e));

        e.remove("stats");
        assertNull(PlayerGameIngestService.toRow(Sport.NBA, 2025, "1658", e));
    }

    /**
     * FR-006: a missing opponent still stores the game. The date is the fact
     * the section is built on; the opponent is the nicety, and a row without it
     * is better than no row or a guessed one.
     */
    @Test
    void aMissingOpponentStillStoresTheGame() {
        Map<String, Object> e = fullEntry();
        e.remove("opponent");
        e.remove("is_away_team");

        PlayerGameRepository.Row r = PlayerGameIngestService.toRow(Sport.NBA, 2025, "1658", e);

        assertNotNull(r);
        assertNull(r.opponent());
        assertNull(r.isAway(), "unknown must stay unknown rather than defaulting to home");
    }

    // ---- the walk itself, with the upstream client mocked (T017/T019) ----

    /**
     * T019. One player's failure must not cost the other 330, and must not pass
     * silently either -- a partial backfill that reports success is how feature
     * 004 ended up with five leagues holding nothing while the suite was green.
     */
    @Test
    void oneFailingPlayerIsCountedAndTheRestAreStillStored() {
        var stats = org.mockito.Mockito.mock(SleeperPlayerStatsClient.class);
        var leagues = org.mockito.Mockito.mock(com.ballknowers.draftsim.store.LeagueRepository.class);
        var weekPoints = org.mockito.Mockito.mock(
                com.ballknowers.draftsim.store.RosterWeekPointsRepository.class);
        var games = org.mockito.Mockito.mock(PlayerGameRepository.class);

        org.mockito.Mockito.when(leagues.bySleeperId("L1")).thenReturn(java.util.Optional.of(
                new com.ballknowers.draftsim.store.LeagueRepository.LeagueRow(
                        7L, Sport.NBA, "L1", "Ball Knowers", 2025, 12, List.of(), 0.0, null)));
        org.mockito.Mockito.when(weekPoints.breakdownsFor(7L, 2025)).thenReturn(List.of(
                new com.ballknowers.draftsim.store.RosterWeekPointsRepository.WeekBreakdown(
                        5, 3, 269.0, "{\"good\":58.5,\"bad\":10.0}", null)));

        org.mockito.Mockito.when(stats.seasonByWeek("nba", "good", 2025))
                .thenReturn(Map.of("5", List.of(fullEntry())));
        org.mockito.Mockito.when(stats.seasonByWeek("nba", "bad", 2025))
                .thenThrow(new RuntimeException("upstream said no"));

        var service = new PlayerGameIngestService(stats, leagues, weekPoints, games);
        PlayerGameIngestService.Result r = service.ingest("L1", 2025);

        assertEquals(2, r.playersWalked());
        assertEquals(1, r.gamesStored(), "the healthy player's game still landed");
        assertEquals(1, r.playersFailed(), "the failure is reported, not swallowed");
        org.mockito.Mockito.verify(games, org.mockito.Mockito.times(1))
                .upsert(org.mockito.ArgumentMatchers.any());
    }

    /**
     * T017's half that does not need a database: re-running the walk issues the
     * same upserts rather than accumulating. The natural key turning those into
     * one row is asserted against real Postgres in
     * {@code PlayerGameRepositoryIT}.
     */
    @Test
    void reRunningTheWalkIssuesTheSameUpsertsRatherThanMore() {
        var stats = org.mockito.Mockito.mock(SleeperPlayerStatsClient.class);
        var leagues = org.mockito.Mockito.mock(com.ballknowers.draftsim.store.LeagueRepository.class);
        var weekPoints = org.mockito.Mockito.mock(
                com.ballknowers.draftsim.store.RosterWeekPointsRepository.class);
        var games = org.mockito.Mockito.mock(PlayerGameRepository.class);

        org.mockito.Mockito.when(leagues.bySleeperId("L1")).thenReturn(java.util.Optional.of(
                new com.ballknowers.draftsim.store.LeagueRepository.LeagueRow(
                        7L, Sport.NBA, "L1", "Ball Knowers", 2025, 12, List.of(), 0.0, null)));
        org.mockito.Mockito.when(weekPoints.breakdownsFor(7L, 2025)).thenReturn(List.of(
                new com.ballknowers.draftsim.store.RosterWeekPointsRepository.WeekBreakdown(
                        5, 3, 269.0, "{\"1658\":58.5}", null)));
        org.mockito.Mockito.when(stats.seasonByWeek("nba", "1658", 2025))
                .thenReturn(Map.of("5", List.of(fullEntry())));

        var service = new PlayerGameIngestService(stats, leagues, weekPoints, games);
        PlayerGameIngestService.Result first = service.ingest("L1", 2025);
        PlayerGameIngestService.Result second = service.ingest("L1", 2025);

        assertEquals(first, second, "a second walk must report the same work, not more");
        assertEquals(1, first.gamesStored());
    }

    /** A league this app has never ingested is a no-op, not a crash. */
    @Test
    void anUnknownLeagueWalksNothing() {
        var stats = org.mockito.Mockito.mock(SleeperPlayerStatsClient.class);
        var leagues = org.mockito.Mockito.mock(com.ballknowers.draftsim.store.LeagueRepository.class);
        var weekPoints = org.mockito.Mockito.mock(
                com.ballknowers.draftsim.store.RosterWeekPointsRepository.class);
        var games = org.mockito.Mockito.mock(PlayerGameRepository.class);
        org.mockito.Mockito.when(leagues.bySleeperId("nope")).thenReturn(java.util.Optional.empty());

        var r = new PlayerGameIngestService(stats, leagues, weekPoints, games).ingest("nope", 2025);

        assertEquals(new PlayerGameIngestService.Result(0, 0, 0), r);
        org.mockito.Mockito.verifyNoInteractions(stats, games);
    }
}
