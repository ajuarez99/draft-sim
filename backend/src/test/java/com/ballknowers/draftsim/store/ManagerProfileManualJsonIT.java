package com.ballknowers.draftsim.store;

import com.ballknowers.draftsim.domain.Sport;
import com.ballknowers.draftsim.profile.ManualTendencies;
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
 * Real-Postgres coverage for manual_json's write semantics -- same
 * reused-real-schema/gated-skip convention as MockDraftRepositoryIT.
 *
 * These assertions are about a jsonb operator, not about Java, so a mocked
 * repository could not have caught the bug they pin: saveManual used to assign
 * excluded.manual_json, which destroyed every key Java does not model. Nothing in
 * the app writes such a key TODAY -- claude/player-affinity.md's note-reading
 * sidecar is the planned first one -- so this test exists to make the column safe
 * to build on before something depends on it, rather than after.
 */
@SpringBootTest
class ManagerProfileManualJsonIT {

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
                "no local Postgres reachable at " + JDBC_URL + " -- skipping manual_json integration test");
    }

    @Autowired private ManagerProfileRepository repo;
    @Autowired private JdbcTemplate jdbc;

    private long managerId;
    private static final String SLEEPER_ID = "manual-json-it-" + java.util.UUID.randomUUID();

    @BeforeEach
    void createManager() {
        managerId = jdbc.queryForObject(
                "insert into manager (sleeper_user_id, display_name) values (?, ?) returning id",
                Long.class, SLEEPER_ID, "manual_json IT");
    }

    @AfterEach
    void dropManager() {
        // manager_profile cascades on manager delete
        jdbc.update("delete from manager where id = ?", managerId);
    }

    private String manualJson() {
        return jdbc.queryForObject(
                "select manual_json::text from manager_profile where manager_id = ? and sport = ?",
                String.class, managerId, Sport.NFL.code());
    }

    @Test
    void saveManualPreservesKeysItDoesNotOwn() {
        repo.saveManual(managerId, Sport.NFL, new ManualTendencies(8.0, 1.6, "drafts his own Bengals"));

        // Something other than ManualTendencies writes a sibling key -- the shape
        // claude/player-affinity.md's stage 2 stores its extraction in.
        jdbc.update("""
                update manager_profile
                   set manual_json = manual_json || '{"noteReading": {"proposed": ["reachBias"]}}'::jsonb
                 where manager_id = ? and sport = ?
                """, managerId, Sport.NFL.code());

        // A perfectly ordinary save from either tendencies UI.
        repo.saveManual(managerId, Sport.NFL, new ManualTendencies(3.0, 1.6, "drafts his own Bengals"));

        String json = manualJson();
        assertTrue(json.contains("noteReading"),
                "a save from the tendencies UI destroyed a key it does not own: " + json);
        assertEquals(3.0, repo.manualFor(managerId, Sport.NFL).reachBias(),
                "the merge must still apply this save's own fields");
    }

    @Test
    void saveManualStillFullyReplacesItsOwnThreeFields() {
        repo.saveManual(managerId, Sport.NFL, new ManualTendencies(8.0, 1.6, "a note"));
        // Clearing every stated field is a normal PUT with an empty form, and it must
        // still clear -- a merge that preserved the old values here would be a
        // regression, not a feature.
        repo.saveManual(managerId, Sport.NFL, ManualTendencies.EMPTY);

        ManualTendencies after = repo.manualFor(managerId, Sport.NFL);
        assertNull(after.reachBias());
        assertNull(after.unpredictability());
        assertNull(after.note());
        assertTrue(after.isEmpty());
    }

    @Test
    void clearManualDropsEverythingIncludingUnknownKeys() {
        repo.saveManual(managerId, Sport.NFL, new ManualTendencies(8.0, 1.6, "a note"));
        jdbc.update("""
                update manager_profile
                   set manual_json = manual_json || '{"noteReading": {"proposed": ["reachBias"]}}'::jsonb
                 where manager_id = ? and sport = ?
                """, managerId, Sport.NFL.code());

        repo.clearManual(managerId, Sport.NFL);

        assertEquals("{}", manualJson(),
                "DELETE means forget this seat -- a reading of a note that no longer exists is garbage");
        assertTrue(repo.manualFor(managerId, Sport.NFL).isEmpty());
    }

    @Test
    void clearManualOnASeatThatNeverHadAProfileRowIsNotAnError() {
        repo.clearManual(managerId, Sport.NFL);
        assertEquals("{}", manualJson());
    }
}
