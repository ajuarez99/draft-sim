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
 * Real-Postgres coverage for DraftRepository.allWithLeague(), same convention as
 * DraftRepositoryUpsertPicksIT: no Testcontainers/embedded-DB harness exists in this repo,
 * so this reuses the real DraftRepository bean + Flyway-migrated schema against
 * application.yml's local-dev default (localhost:5433/draftsim). Gated so the suite SKIPS
 * (not fails) when that Postgres isn't reachable.
 */
@SpringBootTest
class DraftRepositoryAllWithLeagueIT {

    private static final String JDBC_URL = "jdbc:postgresql://localhost:5433/draftsim";
    private static final String USER = "draftsim";
    private static final String PASSWORD = "draftsim";

    @BeforeAll
    static void requiresLocalPostgres() {
        boolean reachable;
        try (Connection c = DriverManager.getConnection(JDBC_URL, USER, PASSWORD)) {
            reachable = true;
        } catch (SQLException e) {
            reachable = false;
        }
        Assumptions.assumeTrue(reachable,
                "no local Postgres reachable at " + JDBC_URL + " -- skipping DraftRepository integration test");
    }

    @Autowired private DraftRepository drafts;
    @Autowired private JdbcTemplate jdbc;

    private long leagueId;
    private long olderDraftId;
    private long newerDraftId;

    @BeforeEach
    void setUp() {
        // Fixture rows scoped under distinct "it-" sleeper ids; wiped up front in case a
        // previous run was interrupted before tearDown ran.
        jdbc.update("delete from league where sleeper_id = ?", "it-league-all-with-league");

        leagueId = jdbc.queryForObject(
                "insert into league (sport, season, sleeper_id, name, total_rosters) values (?, ?, ?, ?, ?) returning id",
                Long.class, "nfl", 2026, "it-league-all-with-league", "IT Summary League", 12);

        olderDraftId = drafts.upsert(leagueId, "it-draft-all-with-league-older", 2025, 15, 12,
                "snake", "complete", java.time.Instant.parse("2025-08-01T00:00:00Z"), "{}");
        newerDraftId = drafts.upsert(leagueId, "it-draft-all-with-league-newer", 2026, 15, 12,
                "snake", "pre_draft", java.time.Instant.parse("2026-08-01T00:00:00Z"), "{}");
    }

    @AfterEach
    void tearDown() {
        jdbc.update("delete from league where id = ?", leagueId); // cascades draft, draft_pick
    }

    @Test
    void allWithLeagueJoinsLeagueNameAndOrdersNewestFirst() {
        List<DraftRepository.DraftSummary> all = drafts.allWithLeague();

        List<DraftRepository.DraftSummary> ours = all.stream()
                .filter(s -> s.leagueId() == leagueId)
                .toList();
        assertEquals(2, ours.size(), "both seeded drafts should be returned");

        DraftRepository.DraftSummary newer = ours.get(0);
        DraftRepository.DraftSummary older = ours.get(1);
        assertEquals(newerDraftId, newer.id(), "the draft with the later start_time must come first");
        assertEquals(olderDraftId, older.id());

        assertEquals("IT Summary League", newer.leagueName());
        assertEquals("pre_draft", newer.status());
        assertEquals(12, newer.teams());
        assertEquals(15, newer.rounds());
        assertEquals(2026, newer.season());
    }
}
