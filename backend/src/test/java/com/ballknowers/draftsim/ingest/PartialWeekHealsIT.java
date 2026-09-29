package com.ballknowers.draftsim.ingest;

import com.ballknowers.draftsim.domain.Sport;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/**
 * specs/009-auto-data-refresh T052 / FR-016 / research R14: a week ingested
 * while it was still Sleeper's {@code last_scored_leg} (NBA scores during the
 * week) is partial, and must be refetched once the league has moved on --
 * then never again.
 *
 * <p>Measured on production NBA 2025 before this feature: week 10 held 24 of
 * 53 moves and weeks 11-21 held none, because both walks skipped any week that
 * already had rows. Sleeper is mocked; Postgres is real. Skips (not fails) if
 * Postgres is down, so the caller must read the skip count.
 */
@SpringBootTest
class PartialWeekHealsIT {

    private static final String JDBC_URL = "jdbc:postgresql://localhost:5433/draftsim";
    private static final String USER = "draftsim";
    private static final String PASSWORD = "draftsim";

    private static final String LEAGUE = "it-009-heal";
    private static final int WEEK = 10;
    private static final int TOTAL_MOVES = 53;
    private static final int PARTIAL_MOVES = 24;

    @BeforeAll
    static void requiresLocalPostgres() {
        boolean reachable;
        try (Connection c = DriverManager.getConnection(JDBC_URL, USER, PASSWORD)) {
            reachable = true;
        } catch (SQLException e) {
            reachable = false;
        }
        Assumptions.assumeTrue(reachable,
                "no local Postgres reachable at " + JDBC_URL + " -- skipping partial-week heal test");
    }

    @MockitoBean private SleeperClient sleeper;
    @Autowired private LeagueHistoryIngestService history;
    @Autowired private JdbcTemplate jdbc;

    @BeforeEach
    void setUp() {
        clean();
    }

    @AfterEach
    void clean() {
        // league_transaction, roster_*, league_matchup and league_week_fetch cascade from league.
        jdbc.update("delete from league where sleeper_id = ?", LEAGUE);
        jdbc.update("delete from manager where sleeper_user_id in ('it-009-u1', 'it-009-u2')");
    }

    @Test
    void aWeekStoredAtTheLastScoredLegIsRefetchedOnceTheLeagueMovesOnThenLeftAlone() {
        stubLeague(10, "in_season");
        stubWeek10(PARTIAL_MOVES, 50.0);

        history.ingestChain(Sport.NBA, LEAGUE, Set.of());

        assertEquals(PARTIAL_MOVES, moves(), "first ingest stores the partial week");
        assertEquals(50.0, week10Points(1), 1e-9);
        assertFalse(week10Final("TRANSACTIONS"), "fetched while it was the last scored leg: not final");
        assertFalse(week10Final("POINTS"));

        // The league moves on to leg 11, and Sleeper now serves the whole of week 10.
        stubLeague(11, "in_season");
        stubWeek10(TOTAL_MOVES, 80.0);
        clearInvocations(sleeper);

        history.ingestChain(Sport.NBA, LEAGUE, Set.of());

        verify(sleeper).transactions(LEAGUE, WEEK);
        verify(sleeper).matchups(LEAGUE, WEEK);
        assertEquals(TOTAL_MOVES, moves(), "week 10 refetched: all 53 moves");
        assertEquals(80.0, week10Points(1), 1e-9, "week 10 refetched: full points");
        assertTrue(week10Final("TRANSACTIONS"), "fetched at leg 11: final");
        assertTrue(week10Final("POINTS"));

        // Third ingest: week 10 is final and settled, so Sleeper is not asked about it.
        clearInvocations(sleeper);
        history.ingestChain(Sport.NBA, LEAGUE, Set.of());

        verify(sleeper, never()).transactions(LEAGUE, WEEK);
        verify(sleeper, never()).matchups(LEAGUE, WEEK);
        assertEquals(TOTAL_MOVES, moves());
    }

    private void stubLeague(int lastScoredLeg, String status) {
        Map<String, Object> league = new LinkedHashMap<>();
        league.put("league_id", LEAGUE);
        league.put("season", "1998");
        league.put("name", "IT 009 heal");
        league.put("total_rosters", 2);
        league.put("status", status);
        league.put("previous_league_id", null);
        Map<String, Object> settings = new LinkedHashMap<>();
        settings.put("last_scored_leg", lastScoredLeg);
        league.put("settings", settings);
        when(sleeper.leagueChain(LEAGUE)).thenReturn(List.of(league));
        when(sleeper.league(LEAGUE)).thenReturn(league);
        when(sleeper.leagueUsers(LEAGUE)).thenReturn(List.of(
                user("it-009-u1", "One"), user("it-009-u2", "Two")));
        when(sleeper.rosters(LEAGUE)).thenReturn(List.of(roster(1, "it-009-u1"), roster(2, "it-009-u2")));
    }

    private void stubWeek10(int moves, double rosterOnePoints) {
        List<Map<String, Object>> tx = new ArrayList<>();
        for (int i = 0; i < moves; i++) {
            Map<String, Object> t = new LinkedHashMap<>();
            t.put("transaction_id", "it-009-t" + i);
            t.put("type", "waiver");
            t.put("status", "complete");
            t.put("roster_ids", List.of(1));
            Map<String, Object> adds = new LinkedHashMap<>();
            adds.put("p" + i, 1);
            t.put("adds", adds);
            t.put("drops", null);
            t.put("settings", new LinkedHashMap<String, Object>());
            t.put("status_updated", 1_700_000_000_000L + i);
            tx.add(t);
        }
        when(sleeper.transactions(LEAGUE, WEEK)).thenReturn(tx);
        when(sleeper.matchups(LEAGUE, WEEK)).thenReturn(List.of(
                matchup(1, rosterOnePoints), matchup(2, 40.0)));
    }

    private static Map<String, Object> user(String id, String name) {
        Map<String, Object> u = new LinkedHashMap<>();
        u.put("user_id", id);
        u.put("display_name", name);
        return u;
    }

    private static Map<String, Object> roster(int id, String owner) {
        Map<String, Object> r = new LinkedHashMap<>();
        r.put("roster_id", id);
        r.put("owner_id", owner);
        r.put("settings", new LinkedHashMap<String, Object>());
        return r;
    }

    private static Map<String, Object> matchup(int rosterId, double points) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("roster_id", rosterId);
        m.put("matchup_id", 1);
        m.put("points", points);
        m.put("starters", List.of("s1", "s2"));
        Map<String, Object> pp = new LinkedHashMap<>();
        pp.put("s1", points / 2);
        pp.put("s2", points / 2);
        m.put("players_points", pp);
        return m;
    }

    private int moves() {
        Integer n = jdbc.queryForObject("""
                select count(*) from league_transaction t join league l on l.id = t.league_id
                where l.sleeper_id = ? and t.week = ?
                """, Integer.class, LEAGUE, WEEK);
        return n == null ? 0 : n;
    }

    private double week10Points(int rosterId) {
        Double d = jdbc.queryForObject("""
                select p.starters_points from roster_week_points p join league l on l.id = p.league_id
                where l.sleeper_id = ? and p.week = ? and p.roster_id = ?
                """, Double.class, LEAGUE, WEEK, rosterId);
        return d == null ? Double.NaN : d;
    }

    private boolean week10Final(String kind) {
        List<Boolean> r = jdbc.queryForList("""
                select f.final from league_week_fetch f join league l on l.id = f.league_id
                where l.sleeper_id = ? and f.kind = ? and f.week = ?
                """, Boolean.class, LEAGUE, kind, WEEK);
        return r.size() == 1 && r.get(0);
    }
}
