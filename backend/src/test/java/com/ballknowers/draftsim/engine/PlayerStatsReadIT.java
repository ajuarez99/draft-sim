package com.ballknowers.draftsim.engine;

import com.ballknowers.draftsim.engine.AdvancedStats.Counting;
import com.ballknowers.draftsim.engine.AdvancedStats.WindowKind;
import com.ballknowers.draftsim.engine.PlayerStatsService.PlayerStatsPage;
import com.ballknowers.draftsim.store.LeagueRepository;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Spec 022 T026 (SC-002): the player page's real-basketball averages over the real 2025 rows, against a
 * direct SQL recomputation that excludes {@code TEAM_} rows, {@code sp <= 0} rows and the All-Star game
 * (an opponent that is not a season team code). SKIPS (with a message) when the local Postgres is
 * unreachable or the leagues are not in it, so the caller must read the skip count.
 *
 * <p>Precision: every per-game average and every shooting percentage is compared as a raw double at
 * 1e-9, which is far tighter than the one-decimal display; the one-decimal rounding is asserted as well.
 */
@SpringBootTest
class PlayerStatsReadIT {

    private static final String JDBC_URL = "jdbc:postgresql://localhost:5433/draftsim";
    private static final String NBA_2024 = "1141438340626231296";
    private static final String NBA_2025 = "1229352720222134272";
    private static final String MEMBER = "1122386008709910528";

    @BeforeAll
    static void requiresLocalPostgres() {
        boolean reachable;
        try (Connection c = DriverManager.getConnection(JDBC_URL, "draftsim", "draftsim")) {
            reachable = true;
        } catch (SQLException e) {
            reachable = false;
        }
        Assumptions.assumeTrue(reachable, "no local Postgres reachable at " + JDBC_URL + " -- skipping PlayerStatsReadIT");
    }

    @Autowired private PlayerStatsService service;
    @Autowired private LeagueRepository leagues;
    @Autowired private JdbcTemplate jdbc;

    private LeagueRepository.LeagueRow league(String sleeperId) {
        Optional<LeagueRepository.LeagueRow> l = leagues.bySleeperId(sleeperId);
        Assumptions.assumeTrue(l.isPresent(), "league " + sleeperId + " is not in the local database");
        return l.get();
    }

    private static final String SEASON_CODES = """
            select substr(sleeper_player_id, 6) from player_game
            where sport = 'nba' and season = 2025 and sleeper_player_id like 'TEAM\\_%' and length(sleeper_player_id) > 5
            group by 1
            """;

    /** The most-games players of 2025, plus one who played for two teams (a team row in the game that is not the opponent). */
    private List<String> pickPlayers() {
        List<String> traded = jdbc.queryForList("""
                with codes as (%s),
                pg as (
                    select p.sleeper_player_id pid, substr(t.sleeper_player_id, 6) tm
                    from player_game p
                    join player_game t on t.sport = p.sport and t.season = p.season and t.game_id = p.game_id
                         and t.sleeper_player_id like 'TEAM\\_%%' and length(t.sleeper_player_id) > 5
                         and substr(t.sleeper_player_id, 6) <> p.opponent
                    where p.sport = 'nba' and p.season = 2025 and p.sleeper_player_id not like 'TEAM\\_%%'
                      and (p.stats->>'sp')::float > 0 and p.opponent in (select * from codes))
                select pid from pg group by pid having count(distinct tm) > 1 and count(*) >= 40
                order by count(*) desc, pid limit 1
                """.formatted(SEASON_CODES), String.class);
        Assumptions.assumeFalse(traded.isEmpty(), "no player with two 2025 teams in the local database");
        Set<String> picks = new LinkedHashSet<>(traded);
        picks.addAll(jdbc.queryForList("""
                select sleeper_player_id from player_game
                where sport = 'nba' and season = 2025 and sleeper_player_id not like 'TEAM\\_%'
                group by sleeper_player_id order by count(*) desc, sleeper_player_id limit 12
                """, String.class));
        List<String> out = new ArrayList<>(picks);
        return out.subList(0, 10);
    }

    private static void near(double expected, double actual, String what) {
        assertEquals(expected, actual, 1e-9, what);
        assertEquals(Math.round(expected * 10), Math.round(actual * 10), what + " at one decimal");
    }

    @Test
    void perGameAveragesAndShootingPercentagesMatchADirectSqlRecomputation() {
        var l2025 = league(NBA_2025);
        List<String> ids = pickPlayers();
        assertEquals(10, ids.size());
        int mismatches = 0;
        boolean sawTraded = false;
        for (String id : ids) {
            Map<String, Object> sql = jdbc.queryForMap("""
                    with codes as (%s)
                    select count(*) g,
                      avg(coalesce((stats->>'pts')::float, 0)) pts,  avg(coalesce((stats->>'reb')::float, 0)) reb,
                      avg(coalesce((stats->>'oreb')::float, 0)) oreb, avg(coalesce((stats->>'dreb')::float, 0)) dreb,
                      avg(coalesce((stats->>'ast')::float, 0)) ast,  avg(coalesce((stats->>'stl')::float, 0)) stl,
                      avg(coalesce((stats->>'blk')::float, 0)) blk,  avg(coalesce((stats->>'to')::float, 0)) tov,
                      avg(coalesce((stats->>'pf')::float, 0)) pf,    avg(coalesce((stats->>'fgm')::float, 0)) fgm,
                      avg(coalesce((stats->>'fga')::float, 0)) fga,  avg(coalesce((stats->>'tpm')::float, 0)) tpm,
                      avg(coalesce((stats->>'tpa')::float, 0)) tpa,  avg(coalesce((stats->>'ftm')::float, 0)) ftm,
                      avg(coalesce((stats->>'fta')::float, 0)) fta,
                      sum(coalesce((stats->>'fgm')::float, 0)) sfgm, sum(coalesce((stats->>'fga')::float, 0)) sfga,
                      sum(coalesce((stats->>'tpm')::float, 0)) stpm, sum(coalesce((stats->>'tpa')::float, 0)) stpa,
                      sum(coalesce((stats->>'ftm')::float, 0)) sftm, sum(coalesce((stats->>'fta')::float, 0)) sfta
                    from player_game
                    where sport = 'nba' and season = 2025 and sleeper_player_id = ?
                      and (stats->>'sp')::float > 0 and opponent in (select * from codes)
                    """.formatted(SEASON_CODES), id);

            PlayerStatsPage page = service.read(l2025, id, MEMBER).orElseThrow();
            assertTrue(page.available(), id);
            assertEquals(2025, page.season());
            assertNull(page.requestedSeason());
            var w = page.windows().get(WindowKind.SEASON);
            assertEquals(((Number) sql.get("g")).intValue(), w.games(), id + " games");
            Counting pg = w.perGame();
            String[] keys = {"pts", "reb", "oreb", "dreb", "ast", "stl", "blk", "tov", "pf", "fgm", "fga", "tpm",
                    "tpa", "ftm", "fta"};
            double[] got = {pg.pts(), pg.reb(), pg.oreb(), pg.dreb(), pg.ast(), pg.stl(), pg.blk(), pg.tov(),
                    pg.pf(), pg.fgm(), pg.fga(), pg.tpm(), pg.tpa(), pg.ftm(), pg.fta()};
            for (int i = 0; i < keys.length; i++) {
                double expected = ((Number) sql.get(keys[i])).doubleValue();
                try {
                    near(expected, got[i], id + " " + keys[i] + "/g");
                } catch (AssertionError e) {
                    mismatches++;
                    System.out.println("REPORT MISMATCH " + id + " " + keys[i] + " sql=" + expected + " service=" + got[i]);
                }
            }
            double[][] pct = {
                    {((Number) sql.get("sfgm")).doubleValue(), ((Number) sql.get("sfga")).doubleValue()},
                    {((Number) sql.get("stpm")).doubleValue(), ((Number) sql.get("stpa")).doubleValue()},
                    {((Number) sql.get("sftm")).doubleValue(), ((Number) sql.get("sfta")).doubleValue()}};
            AdvancedStats.Rate[] rates = {w.shooting().fgPct(), w.shooting().tpPct(), w.shooting().ftPct()};
            String[] names = {"fg%", "3p%", "ft%"};
            for (int i = 0; i < 3; i++) {
                if (pct[i][1] > 0) {
                    try {
                        near(100.0 * pct[i][0] / pct[i][1], rates[i].value(), id + " " + names[i]);
                    } catch (AssertionError e) {
                        mismatches++;
                        System.out.println("REPORT MISMATCH " + id + " " + names[i]);
                    }
                } else {
                    assertEquals(AdvancedStats.NO_ATTEMPTS, rates[i].reason(), id + " " + names[i]);
                }
            }
            if (page.teamsThisSeason().size() > 1) sawTraded = true;

            // I3 on the real rows: the breakdown sums to the season total
            double sum = page.fantasy().breakdown().stream().mapToDouble(PlayerStatsService.BreakdownRow::points).sum();
            assertEquals(page.fantasy().seasonTotal(), sum, 0.01 * w.games(), id + " breakdown");
            System.out.println("REPORT " + id + " " + page.player().name() + " teams=" + page.teamsThisSeason()
                    + " games=" + w.games() + " missed=" + page.teamGamesMissed() + " pts/g=" + pg.pts()
                    + " fp/g=" + page.fantasy().fpPerGame().get(WindowKind.SEASON)
                    + " rank=" + page.fantasy().ranks().leagueRank() + " ownership=" + page.ownership().state()
                    + " asOf=" + page.ownership().asOf());
        }
        assertTrue(sawTraded, "one of the ten played for two teams");
        assertEquals(0, mismatches, "SC-002: zero mismatches");
    }

    /**
     * Code-review B1: a season-ending injury is not lost. Jimmy Butler (1000), 2025, GSW all season: 38
     * games played of 82; 6 missed inside his first-to-last span, plus 38 later GSW games that carry a
     * game-level ENTRY_WITHOUT_PLAY row for him (SQL-computed 2026-10-08: 6 + 38 = 44 = 82 - 38).
     */
    @Test
    void aSeasonEndingInjuryCountsTheGamesAfterHisLastAppearance() {
        var l2025 = league(NBA_2025);
        PlayerStatsPage page = service.read(l2025, "1000", MEMBER).orElseThrow();
        assertEquals(38, page.windows().get(WindowKind.SEASON).games());
        assertEquals(44, page.teamGamesMissed());
    }

    @Test
    void seasonsFromTheOldestLeagueListEveryChainSeasonNewestFirst() {
        var l2024 = league(NBA_2024);
        PlayerStatsPage page = service.read(l2024, "1240", MEMBER).orElseThrow();
        assertEquals(List.of(2026, 2025, 2024),
                page.seasons().stream().map(LeagueSeasonResolver.SeasonOption::season).toList());
        assertTrue(page.seasons().get(1).hasGames());
        assertTrue(page.seasons().get(2).hasGames());
        assertEquals(2024, page.season(), "2024 has games, so the season asked for is the season answered");
    }

    @Test
    void anUnknownPlayerIdWithNoGamesIsEmpty() {
        assertTrue(service.read(league(NBA_2025), "no-such-player", MEMBER).isEmpty());
    }
}
