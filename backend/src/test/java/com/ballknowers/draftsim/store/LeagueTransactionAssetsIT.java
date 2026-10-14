package com.ballknowers.draftsim.store;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Spec 033 T005: V32's draft_picks / waiver_budget columns through the real JDBC binds and SQL
 * (AGENTS.md bug classes 2 and 3). Uses a throwaway league row and removes it afterwards.
 * READ THE SKIP COUNT, not just BUILD SUCCESSFUL.
 */
@SpringBootTest
class LeagueTransactionAssetsIT {

    private static final String JDBC_URL = "jdbc:postgresql://localhost:5433/draftsim";
    private static final String SLEEPER_ID = "it-033-assets-league";

    @Autowired private LeagueTransactionRepository repo;
    @Autowired private JdbcTemplate jdbc;

    private long leagueId;

    private static boolean reachable() {
        try (Connection c = DriverManager.getConnection(JDBC_URL, "draftsim", "draftsim")) {
            return true;
        } catch (SQLException e) {
            return false;
        }
    }

    @BeforeEach
    void setUp() {
        Assumptions.assumeTrue(reachable(), "no local Postgres at " + JDBC_URL + " -- skipping");
        jdbc.update("delete from league where sleeper_id = ?", SLEEPER_ID);
        leagueId = jdbc.queryForObject(
                "insert into league (sport, season, sleeper_id, name) values ('NFL', 2099, ?, 'IT 033') returning id",
                Long.class, SLEEPER_ID);
    }

    @AfterEach
    void tearDown() {
        if (reachable()) jdbc.update("delete from league where sleeper_id = ?", SLEEPER_ID);
    }

    private LeagueTransactionRepository.Row row(int week, String id, Instant at, String picks, String budget) {
        return new LeagueTransactionRepository.Row(leagueId, 2099, week, id, "TRADE", "complete", null, null,
                "{}", "{}", null, at, picks, budget);
    }

    @Test
    void v32ColumnsExist() {
        Integer n = jdbc.queryForObject("""
                select count(*) from information_schema.columns
                where table_name = 'league_transaction' and column_name in ('draft_picks', 'waiver_budget')
                  and data_type = 'jsonb'""", Integer.class);
        assertEquals(2, n);
    }

    @Test
    void upsertRoundTripsNonNullEmptyAndNull() {
        String picks = "[{\"round\":1,\"season\":\"2026\",\"roster_id\":11,\"owner_id\":8,\"previous_owner_id\":11}]";
        String budget = "[{\"sender\":8,\"receiver\":1,\"amount\":67}]";
        repo.upsert(row(1, "a", Instant.parse("2099-01-01T00:00:00Z"), picks, budget));
        repo.upsert(row(2, "b", Instant.parse("2099-01-02T00:00:00Z"), "[]", "[]"));
        repo.upsert(row(3, "c", Instant.parse("2099-01-03T00:00:00Z"), null, null));

        List<LeagueTransactionRepository.Row> rows = repo.forSeason(leagueId, 2099);
        assertEquals(3, rows.size());
        assertTrue(rows.get(0).draftPicksJson().contains("\"previous_owner_id\""));
        assertTrue(rows.get(0).waiverBudgetJson().contains("\"amount\""));
        assertEquals("[]", rows.get(1).draftPicksJson());
        assertEquals("[]", rows.get(1).waiverBudgetJson());
        assertNull(rows.get(2).draftPicksJson());
        assertNull(rows.get(2).waiverBudgetJson());

        // the on-conflict update writes the new columns too
        repo.upsert(row(3, "c", Instant.parse("2099-01-03T00:00:00Z"), "[]", "[]"));
        assertEquals("[]", repo.forSeason(leagueId, 2099).get(2).draftPicksJson());
    }

    @Test
    void tradeWeeksMissingAssetsFindsOnlyNullTrades() {
        repo.upsert(row(4, "n", Instant.parse("2099-01-01T00:00:00Z"), null, null));
        repo.upsert(row(5, "e", Instant.parse("2099-01-02T00:00:00Z"), "[]", "[]"));
        assertEquals(List.of(4), repo.tradeWeeksMissingAssets(leagueId));
    }

    @Test
    void forSeasonOrdersByCreatedAtEvenWhenIdsAreReversed() {
        // Inserted first (lower id) but accepted later, like trade 1720 vs 1753 (lessons #42).
        repo.upsert(row(1, "late", Instant.parse("2099-02-01T00:00:00Z"), "[]", "[]"));
        repo.upsert(row(1, "early", Instant.parse("2099-01-01T00:00:00Z"), "[]", "[]"));
        List<LeagueTransactionRepository.Row> rows = repo.forSeason(leagueId, 2099);
        assertEquals(List.of("early", "late"),
                rows.stream().map(LeagueTransactionRepository.Row::sleeperTransactionId).toList());
    }
}
