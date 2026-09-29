package com.ballknowers.draftsim.store;

import com.ballknowers.draftsim.domain.Sport;
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
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Real-Postgres coverage for the V24 refresh-state repositories
 * (specs/009-auto-data-refresh, T009). Same convention as
 * {@code PlayerGameRepositoryIT}: SKIPS when the local Postgres is unreachable,
 * so the caller must check the skip count.
 *
 * <p>The "never reverts" rules are properties of the {@code on conflict}
 * clauses, which only the database can be asked about.
 */
@SpringBootTest
class RefreshRepositoriesIT {

    private static final String JDBC_URL = "jdbc:postgresql://localhost:5433/draftsim";
    private static final String USER = "draftsim";
    private static final String PASSWORD = "draftsim";

    private static final String LEAGUE_SLEEPER_ID = "it-009-league";
    /** A season no real data uses, so a stray row is obvious and cleanup is exact. */
    private static final int SEASON = 1999;

    @BeforeAll
    static void requiresLocalPostgres() {
        boolean reachable;
        try (Connection c = DriverManager.getConnection(JDBC_URL, USER, PASSWORD)) {
            reachable = true;
        } catch (SQLException e) {
            reachable = false;
        }
        Assumptions.assumeTrue(reachable,
                "no local Postgres reachable at " + JDBC_URL + " -- skipping refresh repository integration test");
    }

    @Autowired private LeagueRefreshRepository refresh;
    @Autowired private SportWeekStatsRepository weekStats;
    @Autowired private DailyCaptureRepository captures;
    @Autowired private LeagueWeekFetchRepository weekFetches;
    @Autowired private LeagueRepository leagues;
    @Autowired private JdbcTemplate jdbc;

    private long leagueId;

    @BeforeEach
    void setUp() {
        clean();
        leagueId = leagues.upsert(Sport.NBA, SEASON, LEAGUE_SLEEPER_ID, null, "IT 009", 12,
                "{}", "{}", List.of("PG"), "in_season");
    }

    @AfterEach
    void clean() {
        // league_refresh cascades from league.
        jdbc.update("delete from league where sleeper_id = ?", LEAGUE_SLEEPER_ID);
        jdbc.update("delete from sport_week_stats where season = ?", SEASON);
        jdbc.update("delete from daily_capture where capture_date = date '1999-01-01'");
    }

    @Test
    void loadedCompleteNeverFlipsBackToFalse() {
        Instant t1 = Instant.parse("2026-09-28T10:00:00Z");
        Instant t2 = Instant.parse("2026-09-28T11:00:00Z");

        refresh.recordSuccess(leagueId, t1, true);
        refresh.recordSuccess(leagueId, t2, false);

        LeagueRefreshRepository.Row row = refresh.find(leagueId).orElseThrow();
        assertTrue(row.loadedComplete(), "a later success passing false must not undo loaded_complete");
        assertEquals(t2, row.lastSuccessAt(), "the timestamp itself still advances");
    }

    @Test
    void loadedCompleteFlipsToTrueAndAFailureLeavesItAlone() {
        refresh.recordSuccess(leagueId, Instant.parse("2026-09-28T10:00:00Z"), false);
        assertFalse(refresh.find(leagueId).orElseThrow().loadedComplete());

        refresh.recordSuccess(leagueId, Instant.parse("2026-09-28T11:00:00Z"), true);
        refresh.recordFailure(leagueId, Instant.parse("2026-09-28T12:00:00Z"), "sleeper 503");

        LeagueRefreshRepository.Row row = refresh.find(leagueId).orElseThrow();
        assertTrue(row.loadedComplete());
        assertEquals("sleeper 503", row.lastFailure());
        assertEquals(Instant.parse("2026-09-28T12:00:00Z"), row.lastFailureAt());
        assertEquals(Instant.parse("2026-09-28T11:00:00Z"), row.lastSuccessAt(),
                "a failure must not overwrite the last success");
    }

    @Test
    void aLeagueNeverRefreshedHasNoRow() {
        assertTrue(refresh.find(leagueId).isEmpty());
    }

    @Test
    void sportWeekFinalNeverReverts() {
        Instant t1 = Instant.parse("2026-09-28T10:00:00Z");
        Instant t2 = Instant.parse("2026-09-28T11:00:00Z");

        weekStats.upsert(new SportWeekStatsRepository.Row(Sport.NBA, SEASON, 3, t1, true));
        weekStats.upsert(new SportWeekStatsRepository.Row(Sport.NBA, SEASON, 3, t2, false));

        List<SportWeekStatsRepository.Row> rows = weekStats.forSeason(Sport.NBA, SEASON);
        assertEquals(1, rows.size());
        assertTrue(rows.get(0).fin(), "final must never revert to false");
        assertEquals(t2, rows.get(0).fetchedAt());
    }

    @Test
    void sportWeekStatsAreScopedToTheirSportAndSeason() {
        Instant t = Instant.parse("2026-09-28T10:00:00Z");
        weekStats.upsert(new SportWeekStatsRepository.Row(Sport.NBA, SEASON, 1, t, false));

        assertEquals(1, weekStats.forSeason(Sport.NBA, SEASON).size());
        assertTrue(weekStats.forSeason(Sport.NFL, SEASON).isEmpty());
        assertTrue(weekStats.forSeason(Sport.NBA, SEASON + 1).isEmpty());
    }

    @Test
    void dailyCaptureExistsOnlyForTheExactSportDateAndKind() {
        LocalDate day = LocalDate.parse("1999-01-01");
        assertFalse(captures.exists(Sport.NBA, day, "PLAYERS"));

        captures.record(Sport.NBA, day, "PLAYERS", Instant.parse("2026-09-28T10:00:00Z"), "playersWritten=1");

        assertTrue(captures.exists(Sport.NBA, day, "PLAYERS"));
        assertFalse(captures.exists(Sport.NFL, day, "PLAYERS"), "other sport");
        assertFalse(captures.exists(Sport.NBA, day.plusDays(1), "PLAYERS"), "other date");
        assertFalse(captures.exists(Sport.NBA, day, "BOARD"), "other kind");
    }

    @Test
    void dailyCaptureRecordIsAnUpsertOnItsKey() {
        LocalDate day = LocalDate.parse("1999-01-01");
        captures.record(Sport.NBA, day, "BOARD", Instant.parse("2026-09-28T10:00:00Z"), "first");
        captures.record(Sport.NBA, day, "BOARD", Instant.parse("2026-09-28T11:00:00Z"), null);

        Integer n = jdbc.queryForObject(
                "select count(*) from daily_capture where capture_date = date '1999-01-01' and kind = 'BOARD'",
                Integer.class);
        assertEquals(1, n);
    }

    @Test
    void leagueWeekFetchFinalNeverRevertsAndKindsAreSeparate() {
        Instant t1 = Instant.parse("2026-09-28T10:00:00Z");
        Instant t2 = Instant.parse("2026-09-28T11:00:00Z");

        weekFetches.record(leagueId, LeagueWeekFetchRepository.TRANSACTIONS, 4, t1, true);
        weekFetches.record(leagueId, LeagueWeekFetchRepository.TRANSACTIONS, 4, t2, false);
        weekFetches.record(leagueId, LeagueWeekFetchRepository.TRANSACTIONS, 5, t1, false);
        weekFetches.record(leagueId, LeagueWeekFetchRepository.POINTS, 6, t1, true);

        assertEquals(java.util.Set.of(4), weekFetches.finalWeeks(leagueId, LeagueWeekFetchRepository.TRANSACTIONS),
                "week 4 stays final after a non-final refetch; week 5 was never final");
        assertEquals(java.util.Set.of(6), weekFetches.finalWeeks(leagueId, LeagueWeekFetchRepository.POINTS));

        weekFetches.record(leagueId, LeagueWeekFetchRepository.TRANSACTIONS, 5, t2, true);
        assertEquals(java.util.Set.of(4, 5), weekFetches.finalWeeks(leagueId, LeagueWeekFetchRepository.TRANSACTIONS));
    }
}
