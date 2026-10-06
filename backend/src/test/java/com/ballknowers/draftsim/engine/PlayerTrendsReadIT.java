package com.ballknowers.draftsim.engine;

import com.ballknowers.draftsim.engine.PlayerTrendsService.PlayerTrends;
import com.ballknowers.draftsim.engine.PlayerTrendsService.TrendRow;
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
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Spec 019 T013: the real read path over the real database, checked against contract C1's invariants
 * with values read back. SKIPS (with a message) when the local Postgres is unreachable or the leagues are
 * not in it, so the caller must read the skip count. Prints what it measured as "REPORT" lines.
 */
@SpringBootTest
class PlayerTrendsReadIT {

    private static final String JDBC_URL = "jdbc:postgresql://localhost:5433/draftsim";
    private static final String NBA_2025 = "1229352720222134272";
    private static final String NBA_2026 = "1339351318115946496";
    private static final int RECENCY_DAYS = 14;

    @BeforeAll
    static void requiresLocalPostgres() {
        boolean reachable;
        try (Connection c = DriverManager.getConnection(JDBC_URL, "draftsim", "draftsim")) {
            reachable = true;
        } catch (SQLException e) {
            reachable = false;
        }
        Assumptions.assumeTrue(reachable, "no local Postgres reachable at " + JDBC_URL + " -- skipping PlayerTrendsReadIT");
    }

    @Autowired private PlayerTrendsService service;
    @Autowired private LeagueRepository leagues;
    @Autowired private ScheduleGridService grid;
    @Autowired private JdbcTemplate jdbc;

    private LeagueRepository.LeagueRow league(String sleeperId) {
        Optional<LeagueRepository.LeagueRow> l = leagues.bySleeperId(sleeperId);
        Assumptions.assumeTrue(l.isPresent(), "league " + sleeperId + " is not in the local database");
        return l.get();
    }

    private static List<TrendRow> all(PlayerTrends t) {
        List<TrendRow> rows = new ArrayList<>();
        rows.addAll(t.risers());
        rows.addAll(t.fallers());
        rows.addAll(t.streaming());
        return rows;
    }

    private LocalDate lastTeamGame(int season) {
        return jdbc.queryForObject("""
                select max(game_date) from player_game
                where sport = 'nba' and season = ? and left(sleeper_player_id, 5) = 'TEAM_'
                  and length(sleeper_player_id) > 5
                """, LocalDate.class, season);
    }

    private static void report(String label, List<TrendRow> rows, int n) {
        for (TrendRow r : rows.subList(0, Math.min(n, rows.size()))) {
            System.out.println("REPORT " + label + " " + r.name() + " (" + r.sleeperPlayerId() + ", " + r.team()
                    + ") recentMin=" + r.recentMin() + " seasonMin=" + r.seasonMin() + " delta=" + r.minDelta()
                    + " seasonPts=" + r.seasonPts() + " formPts=" + r.formPts() + " games=" + r.games()
                    + " missed=" + r.missedTeamGames());
        }
    }

    private void commonInvariants(PlayerTrends t, int season) {
        // 1: no TEAM_ id; a row's games are the team-row-confirmed ones (spot-checked against SQL)
        for (TrendRow r : all(t)) assertFalse(r.sleeperPlayerId().startsWith("TEAM_"), r.sleeperPlayerId());
        for (TrendRow r : all(t).subList(0, Math.min(15, all(t).size()))) {
            if (t.rolesSeason() == null || t.rolesSeason() != season) continue;
            Integer expected = jdbc.queryForObject("""
                    select count(*) from player_game pg
                    where pg.sport = 'nba' and pg.season = ? and pg.sleeper_player_id = ?
                      and (pg.stats->>'sp')::numeric > 0
                      and exists (select 1 from player_game t where t.sport = 'nba' and t.season = pg.season
                                  and left(t.sleeper_player_id, 5) = 'TEAM_' and length(t.sleeper_player_id) > 5
                                  and substring(t.sleeper_player_id from 6) = pg.opponent)
                    """, Integer.class, season, r.sleeperPlayerId());
            // streaming rows read the streaming season; only compare rows from the roles season
            if (t.streamingSeason() != null && t.streamingSeason() == season) {
                assertEquals(expected, r.games(), "games for " + r.name());
            }
        }
        // 3
        if (t.streamingReason() != null) {
            assertTrue(t.streaming().isEmpty());
            for (TrendRow r : all(t)) assertNull(r.rostered(), r.name());
        }
        // 4
        for (TrendRow r : t.risers()) assertEquals("RISER", r.role());
        for (TrendRow r : t.fallers()) assertEquals("FALLER", r.role());
        assertTrue(t.risers().size() <= 10 && t.risersTotal() >= t.risers().size());
        assertTrue(t.fallers().size() <= 10 && t.fallersTotal() >= t.fallers().size());
        // 5: nobody stale, nobody teamless
        for (TrendRow r : t.risers()) assertNotNull(r.team());
        for (TrendRow r : all(t)) assertNotNull(r.team(), r.name());
        // 8
        List<TrendRow> sorted = new ArrayList<>(t.streaming());
        sorted.sort(Comparator.comparing(TrendRow::seasonPts).reversed());
        for (int i = 0; i < sorted.size(); i++) {
            assertEquals(sorted.get(i).seasonPts(), t.streaming().get(i).seasonPts(), "streaming order at " + i);
        }
    }

    @Test
    void nba2025IsAFinishedSeasonWithNoStreaming() {
        LeagueRepository.LeagueRow l = league(NBA_2025);
        long t0 = System.nanoTime();
        PlayerTrends t = service.read(l, null);
        long ms = (System.nanoTime() - t0) / 1_000_000;
        long t1 = System.nanoTime();
        service.read(l, null);
        long ms2 = (System.nanoTime() - t1) / 1_000_000;

        assertTrue(t.available());
        assertFalse(t.rolesFallback());
        assertEquals(2025, t.rolesSeason());
        assertEquals("SEASON_COMPLETE", t.streamingReason());
        commonInvariants(t, 2025);
        // 5 against the real last game of the season
        LocalDate last = lastTeamGame(2025);
        for (TrendRow r : all(t)) {
            assertFalse(r.lastGameDate().isBefore(last.minusDays(RECENCY_DAYS)), r.name() + " " + r.lastGameDate());
        }
        // 6: a complete season has no games this or next week
        for (TrendRow r : all(t)) {
            assertNull(r.gamesThisWeek());
            assertNull(r.gamesNextWeek());
        }
        assertFalse(t.risers().isEmpty());
        assertFalse(t.fallers().isEmpty());
        // B1: the missed-games run reads game-level absence rows, so some listed player has a non-zero run
        long withMissed = all(t).stream().filter(r -> r.missedTeamGames() > 0).count();
        System.out.println("REPORT 2025 listed rows with missedTeamGames > 0: " + withMissed + " of " + all(t).size());
        assertTrue(withMissed > 0, "missedTeamGames must not be dead code");

        System.out.println("REPORT 2025 read ms (first/second): " + ms + " / " + ms2);
        System.out.println("REPORT 2025 risersTotal=" + t.risersTotal() + " fallersTotal=" + t.fallersTotal()
                + " excludedStale=" + t.excludedStale() + " excludedNoTeam=" + t.excludedNoTeam()
                + " oneGameCredit=" + t.oneGameCredit() + " lastTeamGame=" + last);
        report("RISER", t.risers(), 5);
        report("FALLER", t.fallers(), 5);
        for (TrendRow r : all(t)) {
            if (r.sleeperPlayerId().equals("1658")) {
                System.out.println("REPORT Jokic in lists: " + r);
            }
        }
    }

    @Test
    void nba2026ReadsLastSeasonUntilItHasGames() {
        LeagueRepository.LeagueRow l = league(NBA_2026);
        PlayerTrends t = service.read(l, null);
        assertTrue(t.available());
        assertTrue(t.rolesFallback());
        assertTrue(t.streamingFallback());
        assertEquals(2025, t.rolesSeason());
        assertEquals(2025, t.streamingSeason());
        assertEquals("NOT_DRAFTED", t.streamingReason(), "the local league is pre_draft until 2026-10-10");
        assertNotNull(t.oneGameCredit(), "measured from the previous season's stored weeks");
        assertEquals(2025, t.oneGameCredit().seasonMeasured());
        commonInvariants(t, 2025);

        // 6: games this week equal the grid cell for that team at the current week NUMBER
        Optional<ScheduleGridService.Result> g = grid.forLeague(NBA_2026);
        if (g.isPresent() && g.get().available() && t.currentWeek() != null) {
            int idx = -1;
            for (int i = 0; i < g.get().weeks().size(); i++) {
                if (g.get().weeks().get(i).week() == t.currentWeek()) idx = i;
            }
            for (TrendRow r : all(t)) {
                Integer expected = null;
                if (idx >= 0) {
                    for (ScheduleGridService.Team tm : g.get().teams()) {
                        if (tm.team().equals(r.team())) expected = tm.games()[idx];
                    }
                }
                assertEquals(expected, r.gamesThisWeek(), r.name() + " " + r.team());
            }
        }
        System.out.println("REPORT 2026 streamingReason=" + t.streamingReason() + " rolesSeason=" + t.rolesSeason()
                + " streamingSeason=" + t.streamingSeason() + " oneGameCredit=" + t.oneGameCredit()
                + " currentWeek=" + t.currentWeek() + " rostersFetchedAt=" + t.rostersFetchedAt()
                + " risersTotal=" + t.risersTotal() + " excludedStale=" + t.excludedStale()
                + " excludedNoTeam=" + t.excludedNoTeam());
    }
}
