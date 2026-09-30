package com.ballknowers.draftsim.store;

import com.ballknowers.draftsim.domain.Sport;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;

import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;

import static org.junit.jupiter.api.Assertions.*;

/**
 * V25 against real Postgres: the manager_note table's keying and constraint, the repository's
 * per-author isolation, and the migration's strip of stated tendencies from manual_json.
 * SKIPS when the local Postgres is unreachable, so the caller must read the skip count.
 */
@SpringBootTest
class ManagerNoteRepositoryIT {

    private static final String JDBC_URL = "jdbc:postgresql://localhost:5433/draftsim";

    @BeforeAll
    static void requiresLocalPostgres() {
        boolean reachable;
        try (Connection c = DriverManager.getConnection(JDBC_URL, "draftsim", "draftsim")) {
            reachable = true;
        } catch (SQLException e) {
            reachable = false;
        }
        Assumptions.assumeTrue(reachable, "no local Postgres reachable at " + JDBC_URL + " -- skipping note IT");
    }

    @Autowired private ManagerNoteRepository notes;
    @Autowired private JdbcTemplate jdbc;

    private long managerId;
    private static final String ALICE = "it-note-alice";
    private static final String BOB = "it-note-bob";

    @BeforeEach
    void setUp() {
        tearDown();
        managerId = jdbc.queryForObject(
                "insert into manager (sleeper_user_id, display_name) values ('it-note-target', 'Note IT') returning id",
                Long.class);
    }

    @AfterEach
    void tearDown() {
        jdbc.update("delete from manager where sleeper_user_id = 'it-note-target'");
    }

    @Test
    void notesAreKeyedByAuthorManagerAndSportAndNeverCrossAuthors() {
        notes.save(ALICE, managerId, Sport.NFL, "alice nfl");
        notes.save(ALICE, managerId, Sport.NBA, "alice nba");
        notes.save(BOB, managerId, Sport.NFL, "bob nfl");

        assertEquals("alice nfl", notes.noteBy(ALICE, managerId, Sport.NFL).orElseThrow());
        assertEquals("alice nba", notes.noteBy(ALICE, managerId, Sport.NBA).orElseThrow());
        assertEquals("bob nfl", notes.noteBy(BOB, managerId, Sport.NFL).orElseThrow());
        assertEquals("alice nfl", notes.notesBy(ALICE, Sport.NFL).get(managerId));
        assertEquals("bob nfl", notes.notesBy(BOB, Sport.NFL).get(managerId));
        assertTrue(notes.noteBy("it-note-carol", managerId, Sport.NFL).isEmpty());
    }

    @Test
    void savingAgainReplacesAndDeleteRemovesOnlyTheCallersRow() {
        notes.save(ALICE, managerId, Sport.NFL, "first");
        notes.save(ALICE, managerId, Sport.NFL, "second");
        notes.save(BOB, managerId, Sport.NFL, "bob");
        assertEquals("second", notes.noteBy(ALICE, managerId, Sport.NFL).orElseThrow());
        assertEquals(2, jdbc.queryForObject("select count(*) from manager_note where manager_id = ?",
                Integer.class, managerId));

        notes.delete(ALICE, managerId, Sport.NFL);
        assertTrue(notes.noteBy(ALICE, managerId, Sport.NFL).isEmpty());
        assertEquals("bob", notes.noteBy(BOB, managerId, Sport.NFL).orElseThrow());
    }

    @Test
    void theTableItselfRefusesAnEmptyOrOverlongNoteAndCascadesWithTheManager() {
        assertThrows(DataIntegrityViolationException.class,
                () -> notes.save(ALICE, managerId, Sport.NFL, "x".repeat(141)));
        assertThrows(DataIntegrityViolationException.class, () -> notes.save(ALICE, managerId, Sport.NFL, ""));
        notes.save(ALICE, managerId, Sport.NFL, "x".repeat(140));
        jdbc.update("delete from manager where id = ?", managerId);
        assertEquals(0, jdbc.queryForObject("select count(*) from manager_note where manager_id = ?",
                Integer.class, managerId));
    }

    /**
     * Runs V25's own UPDATE statement (extracted from the file, so this cannot drift from what
     * Flyway ran) against a row shaped like the local data: the two stated numbers, a note, the stray
     * "empty" key, and one unmodelled key that must survive.
     */
    @Test
    void theMigrationStripsStatedTendenciesAndNotesButKeepsUnknownKeys() throws Exception {
        String sql;
        try (var in = getClass().getResourceAsStream("/db/migration/V25__manager_note_private.sql")) {
            String all = new String(in.readAllBytes(), StandardCharsets.UTF_8);
            sql = all.substring(all.indexOf("update manager_profile"));
        }
        jdbc.update("""
                insert into manager_profile (manager_id, sport, manual_json)
                values (?, 'nfl', '{"reachBias": 8.0, "unpredictability": 1.6, "note": "old", "empty": false, "affinity": {"x": 1}}'::jsonb)
                """, managerId);
        jdbc.update("""
                insert into manager_profile (manager_id, sport, manual_json)
                values (?, 'nba', '{"note": null, "empty": true, "reachBias": null, "unpredictability": null}'::jsonb)
                """, managerId);

        jdbc.update(sql);

        assertEquals("{\"affinity\": {\"x\": 1}}", jdbc.queryForObject(
                "select manual_json::text from manager_profile where manager_id = ? and sport = 'nfl'",
                String.class, managerId));
        assertEquals("{}", jdbc.queryForObject(
                "select manual_json::text from manager_profile where manager_id = ? and sport = 'nba'",
                String.class, managerId));
    }
}
