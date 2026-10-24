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
import java.time.Instant;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Spec 046 C12: {@code forLeague} picks the newest season, then the latest start time, then the newest id --
 * two drafts in one league-season (a commissioner re-creating the draft) must not be a coin flip. Real
 * Postgres; SKIPS when it is unreachable, so read the skip count.
 */
@SpringBootTest
class DraftRepositoryForLeagueTieBreakIT {

    @BeforeAll
    static void requiresPostgres() {
        String url = System.getenv().getOrDefault("DB_URL", "jdbc:postgresql://localhost:5433/draftsim");
        boolean reachable;
        try (Connection c = DriverManager.getConnection(url, "draftsim", "draftsim")) {
            reachable = true;
        } catch (SQLException e) {
            reachable = false;
        }
        Assumptions.assumeTrue(reachable, "no Postgres reachable at " + url + " -- skipping forLeague tie-break IT");
    }

    @Autowired private DraftRepository drafts;
    @Autowired private JdbcTemplate jdbc;

    private long leagueId;

    @BeforeEach
    void seed() {
        jdbc.update("delete from league where sleeper_id = 'it046-forleague'");
        leagueId = jdbc.queryForObject(
                "insert into league (sport, season, sleeper_id, name, total_rosters) values ('nba', 2091, 'it046-forleague', 'IT forLeague', 12) returning id",
                Long.class);
    }

    @AfterEach
    void clean() {
        jdbc.update("delete from league where id = ?", leagueId);
    }

    @Test
    void theLaterStartTimeWinsWhenItWasInsertedSecond() {
        // Heap order would return the first-inserted (earlier) row; the tie-break must not.
        long earlier = DraftFixtures.upsert(drafts, leagueId, "it046-fl-earlier2", 2091, 23, 12, "snake", "pre_draft",
                Instant.parse("2091-10-01T00:00:00Z"), "{}");
        long later = DraftFixtures.upsert(drafts, leagueId, "it046-fl-later2", 2091, 23, 12, "auction", "complete",
                Instant.parse("2091-10-02T00:00:00Z"), "{}");
        assertEquals(true, later > earlier);

        assertEquals(later, drafts.forLeague(leagueId).orElseThrow().id());
    }

    @Test
    void twoDraftsInOneSeasonResolveToTheLaterStartTimeEvenWithALowerId() {
        long later = DraftFixtures.upsert(drafts, leagueId, "it046-fl-later", 2091, 23, 12, "auction", "complete",
                Instant.parse("2091-10-02T00:00:00Z"), "{}");
        long earlier = DraftFixtures.upsert(drafts, leagueId, "it046-fl-earlier", 2091, 23, 12, "snake", "pre_draft",
                Instant.parse("2091-10-01T00:00:00Z"), "{}");
        // `earlier` has the higher id, so a bare id tie-break (or heap order) would pick it.
        assertEquals(true, earlier > later);

        assertEquals(later, drafts.forLeague(leagueId).orElseThrow().id());
    }

    @Test
    void aMissingStartTimeSortsLastAndTheNewestIdBreaksAFullTie() {
        long noTime = DraftFixtures.upsert(drafts, leagueId, "it046-fl-notime", 2091, 23, 12, "snake", "complete", null, "{}");
        long timed = DraftFixtures.upsert(drafts, leagueId, "it046-fl-timed", 2091, 23, 12, "snake", "complete",
                Instant.parse("2091-10-01T00:00:00Z"), "{}");
        assertEquals(timed, drafts.forLeague(leagueId).orElseThrow().id());

        jdbc.update("update draft set start_time = null where id = ?", timed);
        assertEquals(timed, drafts.forLeague(leagueId).orElseThrow().id(), "equal (null) times: the newer id");
        assertEquals(true, timed > noTime);
    }
}
