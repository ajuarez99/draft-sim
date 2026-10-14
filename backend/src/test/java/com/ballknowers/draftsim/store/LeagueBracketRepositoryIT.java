package com.ballknowers.draftsim.store;

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
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * specs/034 T003: {@code league_bracket_match} (V33) against real Postgres. SKIPS when Postgres is
 * unreachable at the URL the app itself would use ({@code DB_URL}, default the local 5433), so read the
 * skip count.
 */
@SpringBootTest
class LeagueBracketRepositoryIT {

    static final String LEAGUE = "it-bracket-league";

    @BeforeAll
    static void requiresPostgres() {
        String url = System.getenv().getOrDefault("DB_URL", "jdbc:postgresql://localhost:5433/draftsim");
        boolean reachable;
        try (Connection c = DriverManager.getConnection(url, "draftsim", "draftsim")) {
            reachable = true;
        } catch (SQLException e) {
            reachable = false;
        }
        Assumptions.assumeTrue(reachable, "no Postgres reachable at " + url + " -- skipping bracket repository IT");
    }

    @Autowired private JdbcTemplate jdbc;
    @Autowired private LeagueBracketRepository brackets;

    private long leagueId;

    @BeforeEach
    void setUp() {
        tearDown();
        leagueId = jdbc.queryForObject("""
                insert into league (sport, season, sleeper_id, name, total_rosters, status, roster_positions)
                values ('nfl', 2025, ?, 'IT bracket', 12, 'complete', '{QB}') returning id
                """, Long.class, LEAGUE);
    }

    @AfterEach
    void tearDown() {
        jdbc.update("delete from league where sleeper_id = ?", LEAGUE);
    }

    private static LeagueBracketRepository.Match m(long league, int id, int round, Integer t1, Integer t2,
                                                   Integer w, Integer l, Integer p) {
        return new LeagueBracketRepository.Match(league, id, round, t1, t2, w, l, p);
    }

    @Test
    void replaceWritesAndASecondReplaceSwapsTheWholeSeason() {
        brackets.replaceForLeague(leagueId, List.of(m(leagueId, 1, 1, 3, 4, 3, 4, null), m(leagueId, 2, 1, 8, 9, 9, 8, null)));
        assertEquals(2, brackets.count(leagueId));

        brackets.replaceForLeague(leagueId, List.of(m(leagueId, 7, 3, 1, 3, 1, 3, 1)));
        List<LeagueBracketRepository.Match> now = brackets.forLeagues(List.of(leagueId));
        assertEquals(1, now.size(), "a re-ingest replaces the season, it never leaves a mix of two fetches");
        assertEquals(7, now.get(0).matchId());
        assertEquals(1, now.get(0).placement());
    }

    @Test
    void anEmptyWriteKeepsTheExistingRows() {
        brackets.replaceForLeague(leagueId, List.of(m(leagueId, 1, 1, 3, 4, 3, 4, null)));
        brackets.replaceForLeague(leagueId, List.of());
        brackets.replaceForLeague(leagueId, null);
        assertEquals(1, brackets.count(leagueId), "plan.md F5: a transient empty answer must not wipe a bracket");
    }

    @Test
    void nullableSlotsRoundTrip() {
        brackets.replaceForLeague(leagueId, List.of(m(leagueId, 3, 2, 10, null, null, null, null)));
        var row = brackets.forLeagues(List.of(leagueId)).get(0);
        assertEquals(10, row.t1RosterId());
        assertNull(row.t2RosterId());
        assertNull(row.winnerRosterId());
        assertNull(row.loserRosterId());
        assertNull(row.placement());
    }

    @Test
    void isCompleteNeedsRowsAndEveryWinner() {
        assertFalse(brackets.isComplete(leagueId), "no rows is not complete");
        brackets.replaceForLeague(leagueId, List.of(m(leagueId, 1, 1, 3, 4, 3, 4, null), m(leagueId, 2, 1, 8, 9, null, null, null)));
        assertFalse(brackets.isComplete(leagueId), "one unplayed match");
        brackets.replaceForLeague(leagueId, List.of(m(leagueId, 1, 1, 3, 4, 3, 4, null), m(leagueId, 2, 1, 8, 9, 9, 8, null)));
        assertTrue(brackets.isComplete(leagueId));
    }

    @Test
    void deletingTheLeagueCascades() {
        brackets.replaceForLeague(leagueId, List.of(m(leagueId, 1, 1, 3, 4, 3, 4, null)));
        jdbc.update("delete from league where id = ?", leagueId);
        assertEquals(0, jdbc.queryForObject("select count(*) from league_bracket_match where league_id = ?", Integer.class, leagueId));
    }
}
