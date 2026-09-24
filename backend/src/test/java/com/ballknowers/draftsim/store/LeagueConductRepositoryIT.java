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

import static org.junit.jupiter.api.Assertions.*;

/**
 * Real-Postgres coverage for {@link LeagueConductRepository#upsert}
 * (specs/008-season-superlatives, coordinator follow-up 2026-09-23, item 8):
 * editing an existing entry must keep the ORIGINAL adder, never overwrite it
 * with whoever made the edit. Same convention as
 * {@code DraftRepositoryAllWithLeagueForIT} -- SKIP (not fail) when
 * localhost:5433 isn't reachable.
 */
@SpringBootTest
class LeagueConductRepositoryIT {

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
        Assumptions.assumeTrue(reachable, "no local Postgres reachable -- skipping LeagueConductRepositoryIT");
    }

    @Autowired private LeagueConductRepository repo;
    @Autowired private JdbcTemplate jdbc;

    private long leagueId;
    private long originalAdderId;
    private long editorId;

    private static final String LEAGUE_SLEEPER_ID = "it-league-conduct-repo";
    private static final String ORIGINAL_USER = "it-user-conduct-repo-original";
    private static final String EDITOR_USER = "it-user-conduct-repo-editor";

    @BeforeEach
    void setUp() {
        jdbc.update("delete from league where sleeper_id = ?", LEAGUE_SLEEPER_ID);
        jdbc.update("delete from manager where sleeper_user_id in (?, ?)", ORIGINAL_USER, EDITOR_USER);

        leagueId = jdbc.queryForObject(
                "insert into league (sport, season, sleeper_id, name, total_rosters) values (?, ?, ?, ?, ?) returning id",
                Long.class, "nfl", 2026, LEAGUE_SLEEPER_ID, "IT Conduct Repo League", 10);
        originalAdderId = jdbc.queryForObject(
                "insert into manager (sleeper_user_id, display_name) values (?, ?) returning id",
                Long.class, ORIGINAL_USER, "Original Adder");
        editorId = jdbc.queryForObject(
                "insert into manager (sleeper_user_id, display_name) values (?, ?) returning id",
                Long.class, EDITOR_USER, "Editor");
    }

    @AfterEach
    void tearDown() {
        jdbc.update("delete from league where id = ?", leagueId);
        jdbc.update("delete from manager where id in (?, ?)", originalAdderId, editorId);
    }

    @Test
    void editingAnEntryKeepsTheOriginalAdderRatherThanOverwritingIt() {
        LeagueConductRepository.Entry created =
                repo.upsert(leagueId, "4034", "first reason", 1, originalAdderId);
        assertEquals(originalAdderId, created.addedByManagerId());

        LeagueConductRepository.Entry edited =
                repo.upsert(leagueId, "4034", "edited reason", 3, editorId);

        assertEquals(originalAdderId, edited.addedByManagerId(),
                "the original adder must survive an edit by someone else");
        assertEquals("edited reason", edited.reason(), "the reason and week DO update");
        assertEquals(3, edited.appliesFromWeek());
    }

    @Test
    void aNewEntryRecordsWhoAddedIt() {
        LeagueConductRepository.Entry created =
                repo.upsert(leagueId, "4034", "reason", 1, originalAdderId);
        assertEquals(originalAdderId, created.addedByManagerId());
    }
}
