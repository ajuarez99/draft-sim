package com.ballknowers.draftsim.engine;

import com.ballknowers.draftsim.engine.ProjectionAccuracyService.Cell;
import com.ballknowers.draftsim.engine.ProjectionAccuracyService.ProjectionAccuracy;
import com.ballknowers.draftsim.engine.ProjectionAccuracyService.Series;
import com.ballknowers.draftsim.store.LeagueRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.time.LocalDate;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * specs/036 T015 / SC-003: the whole accuracy read against real Postgres, the SQL of
 * {@code ProjectionSnapshotRepository.forAccuracy} included (AGENTS.md bug class 2), with every figure
 * checked against a hand computation written out below. Seeds season 2020 of nba (no real data lives
 * there) under its own league, and deletes it all. SKIPS when Postgres is unreachable: read the skip count.
 *
 * <p>The thresholds are lowered (min-samples 3, min-cell 2) so a six-row fixture can reach MEASURED; the
 * shipped values (500 / 50) are exercised by the {@code TOO_EARLY} unit test, not here.
 *
 * <pre>
 * League scoring: pts 1.0, ast 1.0, bonus_pt_40p 2.0. No snapshot line carries bonus_pt_40p, so it is
 * graded on neither side (B3). Points below are pts + ast.
 *
 *   player  pos  game  status     projection  season-avg  realized          GAME err   AVG err
 *   p1      PG   gA    complete   20+5  = 25  18+4  = 22  22+6        = 28   25-28=-3   22-28=-6
 *   p2      PG   gA    complete   30+10 = 40  28+8  = 36  38+7 (+bonus) = 45   40-45=-5   36-45=-9
 *   p3      PG   gB    complete   10+2  = 12  12+3  = 15  5+1         = 6    12-6 =+6   15-6 =+9
 *   p4      C    gB    complete   15+1  = 16  15+1  = 16  19+2        = 21   16-21=-5   16-21=-5
 *   p5      C    gA    complete   (no player_game row)                    -> did-not-play
 *   p6      PG   gC    pre_game                                           -> pending
 *   p7      C    gD    postponed                                          -> voided
 *   p8      PG   gA    complete   (no season-average line)                -> withoutSeasonAverage
 *
 * GAME series, n=4: errors -3 -5 +6 -5. lean = -7/4 = -1.75, MAE = 19/4 = 4.75.
 *   PG n=3 (-3 -5 +6): lean -2/3 = -0.67, MAE 14/3 = 4.67.   C n=1 (< min-cell 2): no figures.
 *   by projected value: 0-20: p3 (12) +6, p4 (16) -5 -> n=2, lean +0.5, MAE 5.5;
 *                       20-35: p1 (25) n=1; 35-50: p2 (40) n=1; 50+: n=0   (all below min-cell: no figures)
 * SEASON_AVERAGE series, n=4: errors -6 -9 +9 -5. lean = -11/4 = -2.75, MAE = 29/4 = 7.25.
 *   by the season-average value: 0-20: p3 (15) +9, p4 (16) -5 -> n=2, lean +2.0, MAE 7.0
 * didNotPlay 1, pending 1 in both series; voided 1; withoutSeasonAverage 1.
 * scoredThrough = gB's date, snapshotsFrom = gA's date. Summary: NBA reads SEASON_AVERAGE.
 * </pre>
 */
@SpringBootTest(properties = {
        "draftsim.projection-accuracy.min-samples.nba=3",
        "draftsim.projection-accuracy.min-cell=2"})
class ProjectionAccuracyReadIT {

    private static final int SEASON = 2020;
    private static final String LEAGUE = "it-036-accuracy-league";

    @BeforeAll
    static void requiresPostgres() {
        String url = System.getenv().getOrDefault("DB_URL", "jdbc:postgresql://localhost:5433/draftsim");
        boolean reachable;
        try (Connection c = DriverManager.getConnection(url, "draftsim", "draftsim")) {
            reachable = true;
        } catch (SQLException e) {
            reachable = false;
        }
        Assumptions.assumeTrue(reachable, "no Postgres reachable at " + url + " -- skipping accuracy read IT");
    }

    @Autowired private JdbcTemplate jdbc;
    @Autowired private ProjectionAccuracyService service;
    @Autowired private LeagueRepository leagues;

    @BeforeEach
    @AfterEach
    void clean() {
        jdbc.update("delete from projection_snapshot where sport = 'nba' and season = ?", SEASON);
        jdbc.update("delete from player_game where sport = 'nba' and season = ?", SEASON);
        jdbc.update("delete from sport_schedule where sport = 'nba' and season = ?", SEASON);
        jdbc.update("delete from player where sport = 'nba' and sleeper_id like 'it036-%'");
        jdbc.update("delete from league where sleeper_id = ?", LEAGUE);
    }

    private void player(String id, String position) {
        jdbc.update("insert into player (sport, sleeper_id, name, positions) values ('nba', ?, ?, ?)",
                id, id, new String[]{position});
    }

    private void game(String gameId, String status, String date) {
        jdbc.update("insert into sport_schedule (sport, season, game_id, week, game_date, status, fetched_at) "
                + "values ('nba', ?, ?, 1, ?::date, ?, now())", SEASON, gameId, date, status);
    }

    private void snapshot(String player, String game, String date, String stats, String avg) {
        jdbc.update("insert into projection_snapshot (sport, season, week, sleeper_player_id, game_id, game_date, "
                + "stats, season_avg_stats, source, fetched_at, cutoff_at) values ('nba', ?, 1, ?, ?, ?::date, "
                + "?::jsonb, ?::jsonb, 'rotowire', now() - interval '2 days', now() - interval '1 day')",
                SEASON, player, game, date, stats, avg);
    }

    private void realized(String player, String game, String date, String stats) {
        jdbc.update("insert into player_game (sport, season, week, sleeper_player_id, game_id, game_date, stats) "
                + "values ('nba', ?, 1, ?, ?, ?::date, ?::jsonb)", SEASON, player, game, date, stats);
    }

    private void seed() {
        jdbc.update("insert into league (sport, season, sleeper_id, name, total_rosters, scoring_json) "
                + "values ('nba', ?, ?, 'IT accuracy', 10, ?::jsonb)", SEASON, LEAGUE,
                "{\"pts\":1.0,\"ast\":1.0,\"bonus_pt_40p\":2.0}");
        for (String p : List.of("p1", "p2", "p3", "p6", "p8")) player("it036-" + p, "PG");
        for (String p : List.of("p4", "p5", "p7")) player("it036-" + p, "C");
        game("it036-gA", "complete", "2020-01-02");
        game("it036-gB", "complete", "2020-01-03");
        game("it036-gC", "pre_game", "2020-01-04");
        game("it036-gD", "postponed", "2020-01-04");

        snapshot("it036-p1", "it036-gA", "2020-01-02", "{\"pts\":20,\"ast\":5}", "{\"pts\":18,\"ast\":4}");
        snapshot("it036-p2", "it036-gA", "2020-01-02", "{\"pts\":30,\"ast\":10}", "{\"pts\":28,\"ast\":8}");
        snapshot("it036-p3", "it036-gB", "2020-01-03", "{\"pts\":10,\"ast\":2}", "{\"pts\":12,\"ast\":3}");
        snapshot("it036-p4", "it036-gB", "2020-01-03", "{\"pts\":15,\"ast\":1}", "{\"pts\":15,\"ast\":1}");
        snapshot("it036-p5", "it036-gA", "2020-01-02", "{\"pts\":9,\"ast\":1}", "{\"pts\":9,\"ast\":1}");
        snapshot("it036-p6", "it036-gC", "2020-01-04", "{\"pts\":9,\"ast\":1}", "{\"pts\":9,\"ast\":1}");
        snapshot("it036-p7", "it036-gD", "2020-01-04", "{\"pts\":9,\"ast\":1}", "{\"pts\":9,\"ast\":1}");
        jdbc.update("insert into projection_snapshot (sport, season, week, sleeper_player_id, game_id, game_date, "
                + "stats, season_avg_stats, source, fetched_at, cutoff_at) values ('nba', ?, 1, 'it036-p8', "
                + "'it036-gA', '2020-01-02', '{\"pts\":9,\"ast\":1}'::jsonb, null, 'rotowire', "
                + "now() - interval '2 days', now() - interval '1 day')", SEASON);

        realized("it036-p1", "it036-gA", "2020-01-02", "{\"pts\":22,\"ast\":6}");
        realized("it036-p2", "it036-gA", "2020-01-02", "{\"pts\":38,\"ast\":7,\"bonus_pt_40p\":1}");
        realized("it036-p3", "it036-gB", "2020-01-03", "{\"pts\":5,\"ast\":1}");
        realized("it036-p4", "it036-gB", "2020-01-03", "{\"pts\":19,\"ast\":2}");
        realized("it036-p8", "it036-gA", "2020-01-02", "{\"pts\":12,\"ast\":1}");
    }

    private static Cell cell(List<Cell> cells, String key) {
        return cells.stream().filter(c -> c.key().equals(key)).findFirst().orElseThrow();
    }

    @Test
    void everyFigureMatchesTheHandComputationInTheClassComment() {
        seed();
        LeagueRepository.LeagueRow league = leagues.bySleeperId(LEAGUE).orElseThrow();
        ProjectionAccuracy a = service.forLeague(league);

        assertEquals("MEASURED", a.status());
        assertEquals("GAME", a.unit());
        assertEquals(3, a.minSamples());
        assertEquals(2, a.minCell());
        assertEquals(1, a.voided());
        assertEquals(1, a.withoutSeasonAverage());
        assertEquals(List.of("bonus_pt_40p"), a.excludedCategories());
        assertEquals(LocalDate.of(2020, 1, 3), a.scoredThrough());
        assertEquals(LocalDate.of(2020, 1, 2), a.snapshotsFrom());
        assertEquals("rotowire", a.source());
        assertEquals(2, a.series().size());

        Series game = a.series().get(0);
        assertEquals("GAME", game.kind());
        assertEquals(4, game.overall().n());
        assertEquals(4.75, game.overall().mae(), 1e-9);
        assertEquals(-1.75, game.overall().lean(), 1e-9);
        assertEquals(1, game.didNotPlay());
        assertEquals(1, game.pending());
        assertEquals(3, cell(game.byPosition(), "PG").n());
        assertEquals(4.67, cell(game.byPosition(), "PG").mae(), 1e-9);
        assertEquals(-0.67, cell(game.byPosition(), "PG").lean(), 1e-9);
        assertEquals(1, cell(game.byPosition(), "C").n());
        assertNull(cell(game.byPosition(), "C").mae());
        assertEquals(2, cell(game.byBand(), "0–20").n());
        assertEquals(5.5, cell(game.byBand(), "0–20").mae(), 1e-9);
        assertEquals(0.5, cell(game.byBand(), "0–20").lean(), 1e-9);
        assertEquals(1, cell(game.byBand(), "20–35").n());
        assertNull(cell(game.byBand(), "20–35").mae());
        assertEquals(0, cell(game.byBand(), "50+").n());

        Series avg = a.series().get(1);
        assertEquals("SEASON_AVERAGE", avg.kind());
        assertEquals(4, avg.overall().n());
        assertEquals(7.25, avg.overall().mae(), 1e-9);
        assertEquals(-2.75, avg.overall().lean(), 1e-9);
        assertEquals(2, cell(avg.byBand(), "0–20").n());
        assertEquals(7.0, cell(avg.byBand(), "0–20").mae(), 1e-9);
        assertEquals(2.0, cell(avg.byBand(), "0–20").lean(), 1e-9);
        assertEquals(1, avg.didNotPlay());
        assertEquals(1, avg.pending());
    }

    @Test
    void theSummaryIsTheSeasonAverageSeriesOfTheSameResult() {
        seed();
        LeagueRepository.LeagueRow league = leagues.bySleeperId(LEAGUE).orElseThrow();
        var s = service.summary(league);
        assertEquals("MEASURED", s.status());
        assertEquals("SEASON_AVERAGE", s.kind());
        assertEquals(4, s.n());
        assertEquals(7.25, s.mae(), 1e-9);
        assertEquals(-2.75, s.lean(), 1e-9);
    }

    @Test
    void aSecondReadIsServedFromTheCacheAndAChangedInputInvalidatesIt() {
        seed();
        LeagueRepository.LeagueRow league = leagues.bySleeperId(LEAGUE).orElseThrow();
        ProjectionAccuracy first = service.forLeague(league);
        assertSame(first, service.forLeague(league), "same inputs: the cached object");

        // A stat correction lands on p3's realized game: 5+1 -> 8+1, so the GAME error 12-6=+6 becomes 12-9=+3.
        jdbc.update("update player_game set stats = '{\"pts\":8,\"ast\":1}'::jsonb, fetched_at = now() "
                + "where sleeper_player_id = 'it036-p3' and game_id = 'it036-gB'");
        ProjectionAccuracy changed = service.forLeague(league);
        assertNotSame(first, changed);
        // errors -3 -5 +3 -5 -> lean -10/4 = -2.5
        assertEquals(-2.5, changed.series().get(0).overall().lean(), 1e-9);
    }

    @Test
    void aSeasonWithNoSnapshotsIsNoSnapshotsNotAnError() {
        jdbc.update("insert into league (sport, season, sleeper_id, name, total_rosters, scoring_json) "
                + "values ('nba', ?, ?, 'IT accuracy', 10, ?::jsonb)", SEASON, LEAGUE, "{\"pts\":1.0}");
        var a = service.forLeague(leagues.bySleeperId(LEAGUE).orElseThrow());
        assertEquals("NO_SNAPSHOTS", a.status());
        assertTrue(a.series().isEmpty());
    }
}
