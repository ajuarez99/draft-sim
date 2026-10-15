package com.ballknowers.draftsim.ingest;

import com.ballknowers.draftsim.config.ProjectionAccuracyProperties;
import com.ballknowers.draftsim.domain.Sport;
import com.ballknowers.draftsim.engine.SeasonStateService;
import com.ballknowers.draftsim.store.LeagueRepository;
import com.ballknowers.draftsim.store.PlayerSeasonProjectionRepository;
import com.ballknowers.draftsim.store.ProjectionSnapshotRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

/** specs/036 T008: the capture's guards, driven by an explicit {@code now} and stubbed clients. */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class ProjectionSnapshotServiceTest {

    @Mock private SleeperPlayerStatsClient statsClient;
    @Mock private SleeperProjectionClient projectionClient;
    @Mock private PlayerSeasonProjectionRepository seasonProjections;
    @Mock private ProjectionSnapshotRepository snapshots;
    @Mock private LeagueRepository leagues;
    @Mock private SeasonStateService seasonState;

    private ProjectionSnapshotService service;

    @BeforeEach
    void setUp() {
        var props = new ProjectionAccuracyProperties(
                new ProjectionAccuracyProperties.Accuracy(Map.of("nba", 500, "nfl", 300), 50,
                        Map.of("nba", List.of(20, 35, 50), "nfl", List.of(5, 10, 17))),
                new ProjectionAccuracyProperties.Snapshot(Map.of("nba", "12:00", "nfl", "09:00"), 7));
        service = new ProjectionSnapshotService(statsClient, projectionClient, seasonProjections, snapshots,
                leagues, seasonState, props);
        when(leagues.all()).thenReturn(List.of(league(Sport.NBA), league(Sport.NFL)));
        when(seasonState.stateOf(any())).thenReturn(SeasonStateService.SeasonState.DRAFTED_UNSCORED);
        when(snapshots.upsertPreGame(any())).thenAnswer(inv ->
                new ProjectionSnapshotRepository.UpsertResult(((List<?>) inv.getArgument(0)).size(), 0));
        when(seasonProjections.statsByPlayer(any(), anyInt())).thenReturn(Map.of());
    }

    private static LeagueRepository.LeagueRow league(Sport sport) {
        return new LeagueRepository.LeagueRow(1, sport, "L" + sport.code(), "Ball Knowers", 2026, 12,
                List.of("PG"), 0.0, null, "in_season");
    }

    private static Map<String, Object> game(String id, String status, String date, int week) {
        Map<String, Object> g = new LinkedHashMap<>();
        g.put("game_id", id);
        g.put("status", status);
        g.put("date", date);
        g.put("week", week);
        return g;
    }

    private static Map<String, Object> nbaRow(String player, String gameId, String date, Map<String, Object> stats) {
        Map<String, Object> r = new LinkedHashMap<>();
        r.put("player_id", player);
        r.put("game_id", gameId);
        r.put("date", date);
        r.put("opponent", "LAL");
        r.put("team", "GSW");
        r.put("company", "rotowire");
        r.put("last_modified", 1_760_000_000_000L);
        r.put("stats", stats);
        return r;
    }

    private static Map<String, Object> pts(double v) {
        return new LinkedHashMap<>(Map.of("pts", v, "gp", 1.0));
    }

    private List<ProjectionSnapshotRepository.Row> written() {
        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<ProjectionSnapshotRepository.Row>> c = ArgumentCaptor.forClass(List.class);
        verify(snapshots).upsertPreGame(c.capture());
        return c.getValue();
    }

    @Test
    void aPreGameGameTomorrowIsWrittenWithTheSportsCutoffAcrossTheDstBoundary() {
        // 2026-11-01 is the day US clocks fall back: noon on 10-31 is EDT (16:00Z), noon on 11-01 is EST (17:00Z).
        Instant now = Instant.parse("2026-10-31T11:00:00Z");
        when(statsClient.schedule("nba", 2026)).thenReturn(List.of(
                game("g1", "pre_game", "2026-10-31", 2), game("g2", "pre_game", "2026-11-01", 2)));
        when(projectionClient.week("nba", 2026, 2)).thenReturn(List.of(
                nbaRow("p1", "g1", "2026-10-31", pts(20)), nbaRow("p2", "g2", "2026-11-01", pts(30))));

        var res = service.capture(Sport.NBA, now);

        assertEquals(List.of(2), res.weeks());
        assertEquals(2, res.rowsWritten());
        assertEquals(0, res.rowsNotPreGame());
        var rows = written();
        assertEquals(Instant.parse("2026-10-31T16:00:00Z"), rows.get(0).cutoffAt());
        assertEquals(Instant.parse("2026-11-01T17:00:00Z"), rows.get(1).cutoffAt());
        assertEquals(now, rows.get(0).fetchedAt());
        assertTrue(rows.stream().allMatch(r -> r.fetchedAt().isBefore(r.cutoffAt())));
        assertEquals("rotowire", rows.get(0).source());
        assertEquals(Instant.ofEpochMilli(1_760_000_000_000L), rows.get(0).sourceModifiedAt());
    }

    @Test
    void footballUsesNineAmEasternAsItsCutoff() {
        Instant now = Instant.parse("2026-10-31T11:00:00Z");
        when(statsClient.schedule("nfl", 2026)).thenReturn(List.of(
                game("n1", "pre_game", "2026-10-31", 8), game("n2", "pre_game", "2026-11-01", 8)));
        when(projectionClient.week("nfl", 2026, 8)).thenReturn(List.of(
                nbaRow("p1", "n1", "2026-10-31", new LinkedHashMap<>(Map.of("pts_ppr", 14.2))),
                nbaRow("p2", "n2", "2026-11-01", new LinkedHashMap<>(Map.of("pts_ppr", 9.1)))));

        service.capture(Sport.NFL, now);

        var rows = written();
        assertEquals(Instant.parse("2026-10-31T13:00:00Z"), rows.get(0).cutoffAt(), "09:00 EDT");
        assertEquals(Instant.parse("2026-11-01T14:00:00Z"), rows.get(1).cutoffAt(), "09:00 EST");
    }

    @Test
    void aGameThatIsNotPreGameInTheScheduleIsCountedAndNeverPassedToTheRepository() {
        Instant now = Instant.parse("2026-10-31T11:00:00Z");
        // "anchor" is pre_game and has no projection rows: it only selects the week.
        when(statsClient.schedule("nba", 2026)).thenReturn(List.of(
                game("anchor", "pre_game", "2026-11-02", 2),
                game("c", "complete", "2026-10-31", 2), game("i", "in_game", "2026-10-31", 2),
                game("pp", "postponed", "2026-10-31", 2), game("cx", "canceled", "2026-10-31", 2)));
        when(projectionClient.week("nba", 2026, 2)).thenReturn(List.of(
                nbaRow("p1", "c", "2026-10-31", pts(1)), nbaRow("p2", "i", "2026-10-31", pts(1)),
                nbaRow("p3", "pp", "2026-10-31", pts(1)), nbaRow("p4", "cx", "2026-10-31", pts(1)),
                nbaRow("p5", "unknown-game", "2026-10-31", pts(1))));

        var res = service.capture(Sport.NBA, now);

        assertEquals(5, res.rowsNotPreGame(), "four non-pre_game statuses plus a game_id the schedule has never heard of");
        assertEquals(0, res.rowsWritten());
        assertTrue(written().isEmpty());
    }

    @Test
    void aPreGameGameWhoseCutoffHasPassedIsCountedAndNotPassed() {
        // 17:00Z is 13:00 EDT: past the 12:00 ET NBA cutoff for a game dated today, even though the schedule still says pre_game.
        Instant now = Instant.parse("2026-10-31T17:00:00Z");
        when(statsClient.schedule("nba", 2026)).thenReturn(List.of(
                game("today", "pre_game", "2026-10-31", 2), game("tomorrow", "pre_game", "2026-11-01", 2)));
        when(projectionClient.week("nba", 2026, 2)).thenReturn(List.of(
                nbaRow("p1", "today", "2026-10-31", pts(1)), nbaRow("p2", "tomorrow", "2026-11-01", pts(2))));

        var res = service.capture(Sport.NBA, now);

        assertEquals(1, res.rowsNotPreGame());
        assertEquals(1, res.rowsWritten());
        var rows = written();
        assertEquals(1, rows.size());
        assertEquals("tomorrow", rows.get(0).gameId());
        // Exactly at the cutoff counts as late too: the table's check is a strict "fetched before cutoff".
        assertTrue(rows.get(0).fetchedAt().isBefore(rows.get(0).cutoffAt()));
    }

    @Test
    void weekSelectionTakesOnlyPreGameWeeksInsideTheWindowAndAnAllStarGapIsNotAFailure() {
        Instant now = Instant.parse("2026-10-31T11:00:00Z");   // today ET = 2026-10-31, window to 11-07
        when(statsClient.schedule("nba", 2026)).thenReturn(List.of(
                game("a", "complete", "2026-10-31", 1),     // today but already complete: no
                game("b", "pre_game", "2026-11-03", 2),     // inside: yes
                game("c", "pre_game", "2026-11-07", 2),     // last day of the window: yes
                game("d", "pre_game", "2026-11-08", 3),     // one day past: no
                game("e", "pre_game", "2026-10-30", 0)));   // yesterday still marked pre_game: no
        when(projectionClient.week("nba", 2026, 2)).thenReturn(List.of());

        var res = service.capture(Sport.NBA, now);

        assertEquals(List.of(2), res.weeks());
        verify(projectionClient).week("nba", 2026, 2);
        verify(projectionClient, never()).week(eq("nba"), eq(2026), eq(3));
        verify(projectionClient, never()).week(eq("nba"), eq(2026), eq(1));

        // The All-Star gap: every remaining game is more than a week out.
        reset(projectionClient, snapshots);
        when(statsClient.schedule("nba", 2026)).thenReturn(List.of(game("z", "pre_game", "2027-02-25", 18)));
        var gap = service.capture(Sport.NBA, now);
        assertEquals(List.of(), gap.weeks());
        assertEquals(0, gap.rowsWritten());
        verifyNoInteractions(projectionClient);
        verify(snapshots, never()).upsertPreGame(any());
    }

    @Test
    void basketballCopiesTheSeasonAverageLineAndFootballNeverDoes() {
        Instant now = Instant.parse("2026-10-31T11:00:00Z");
        when(statsClient.schedule("nba", 2026)).thenReturn(List.of(game("g", "pre_game", "2026-11-01", 2)));
        when(projectionClient.week("nba", 2026, 2)).thenReturn(List.of(
                nbaRow("has-avg", "g", "2026-11-01", pts(20)), nbaRow("no-avg", "g", "2026-11-01", pts(10))));
        when(seasonProjections.statsByPlayer("nba", 2026)).thenReturn(Map.of("has-avg", Map.of("pts", 18.5, "reb", 4.0)));

        service.capture(Sport.NBA, now);

        var rows = written();
        var withAvg = rows.stream().filter(r -> r.sleeperPlayerId().equals("has-avg")).findFirst().orElseThrow();
        var without = rows.stream().filter(r -> r.sleeperPlayerId().equals("no-avg")).findFirst().orElseThrow();
        assertEquals(18.5, withAvg.seasonAvgStats().get("pts"));
        assertNull(without.seasonAvgStats(), "a player with no season line gets null, not an empty map");

        reset(snapshots);
        when(snapshots.upsertPreGame(any())).thenReturn(new ProjectionSnapshotRepository.UpsertResult(1, 0));
        when(statsClient.schedule("nfl", 2026)).thenReturn(List.of(game("n", "pre_game", "2026-11-01", 8)));
        when(projectionClient.week("nfl", 2026, 8)).thenReturn(List.of(
                nbaRow("qb", "n", "2026-11-01", new LinkedHashMap<>(Map.of("pts_ppr", 20.0)))));
        service.capture(Sport.NFL, now);
        var nfl = written();
        assertNull(nfl.get(0).seasonAvgStats());
        verify(seasonProjections, never()).statsByPlayer(eq("nfl"), anyInt());
    }

    @Test
    void stubRowsWithoutAProjectionAreSkippedAndNotCounted() {
        Instant now = Instant.parse("2026-10-31T11:00:00Z");
        when(statsClient.schedule("nba", 2026)).thenReturn(List.of(game("g", "pre_game", "2026-11-01", 2)));
        Map<String, Object> noStats = nbaRow("p0", "g", "2026-11-01", pts(1));
        noStats.remove("stats");
        when(projectionClient.week("nba", 2026, 2)).thenReturn(List.of(
                nbaRow("p1", "g", "2026-11-01", new LinkedHashMap<>(Map.of("adp_dd_ppr", 120.0))),   // ADP stub, no pts
                noStats,
                nbaRow("p2", "g", "2026-11-01", pts(12))));

        var res = service.capture(Sport.NBA, now);

        assertEquals(1, res.rowsWritten());
        assertEquals(0, res.rowsNotPreGame(), "a stub is not a late projection");
        assertEquals("p2", written().get(0).sleeperPlayerId());
        // The same filter for football keys on pts_ppr, not pts.
        reset(snapshots);
        when(snapshots.upsertPreGame(any())).thenReturn(new ProjectionSnapshotRepository.UpsertResult(0, 0));
        when(statsClient.schedule("nfl", 2026)).thenReturn(List.of(game("n", "pre_game", "2026-11-01", 8)));
        when(projectionClient.week("nfl", 2026, 8)).thenReturn(List.of(
                nbaRow("k", "n", "2026-11-01", new LinkedHashMap<>(Map.of("pts", 5.0)))));
        service.capture(Sport.NFL, now);
        assertTrue(written().isEmpty());
    }

    @Test
    void adpKeysAreStrippedFromTheStoredLine() {
        Instant now = Instant.parse("2026-10-31T11:00:00Z");
        when(statsClient.schedule("nba", 2026)).thenReturn(List.of(game("g", "pre_game", "2026-11-01", 2)));
        Map<String, Object> line = pts(12);
        line.put("adp_dd_ppr", 77.0);
        line.put("adp_2qb", 88.0);
        line.put("pos_adp_dd_ppr", 9.0);
        line.put("reb", 5.5);
        when(projectionClient.week("nba", 2026, 2)).thenReturn(List.of(nbaRow("p", "g", "2026-11-01", line)));

        service.capture(Sport.NBA, now);

        List<String> keys = new ArrayList<>(written().get(0).stats().keySet());
        assertTrue(keys.containsAll(List.of("pts", "gp", "reb")));
        assertTrue(keys.stream().noneMatch(k -> k.startsWith("adp_") || k.startsWith("pos_adp_")), keys.toString());
    }

    @Test
    void aSportWithNoCurrentSeasonLeagueFetchesNothing() {
        when(seasonState.stateOf(any())).thenReturn(SeasonStateService.SeasonState.COMPLETE);

        var res = service.capture(Sport.NBA, Instant.parse("2026-10-31T11:00:00Z"));

        assertNull(res.season());
        verifyNoInteractions(statsClient, projectionClient);
    }
}
