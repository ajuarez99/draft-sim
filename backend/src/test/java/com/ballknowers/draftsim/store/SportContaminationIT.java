package com.ballknowers.draftsim.store;

import com.ballknowers.draftsim.domain.Sport;
import com.ballknowers.draftsim.profile.ProfileService;
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
 * claude/multi-sport-and-rebrand.md Phase 2 -- same convention as
 * {@link LeagueHistoryContaminationIT} and {@link MockDraftContaminationIT},
 * for the bug those two don't cover: {@code DraftRepository.allCompletedPicks()}
 * joins {@code draft_pick -> draft} with no join to {@code league} and no
 * sport filter.
 *
 * Priors and tilt are already safe from this by accident -- {@code posById} is
 * built from {@code players.findAll(sport)}, so an NBA pick's player never
 * resolves to a {@code Position} and the loop {@code continue}s. The damage is
 * confined to {@code ProfileService.draftsByManager}, which counts an NBA
 * draft id against a manager unconditionally, inflating {@code observed} --
 * the shrinkage N -- even when that NBA draft contributes zero scoreable
 * picks. Uses a manager id that is BOTH a real NFL manager with fitted
 * history AND a manager on a completed NBA draft, since {@code manager} is
 * deliberately not sport-scoped (a Sleeper user is one identity across
 * sports) -- that is exactly what lets this bug bite.
 */
@SpringBootTest
class SportContaminationIT {

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
                "no local Postgres reachable at " + JDBC_URL + " -- skipping sport contamination test");
    }

    @Autowired private DraftRepository drafts;
    @Autowired private ProfileService profiles;
    @Autowired private JdbcTemplate jdbc;

    private long nflLeagueId;
    private long nbaLeagueId;
    private long managerId;
    private long otherManagerId;
    private long player1Id;
    private long player2Id;
    private long nflDraftId;
    private long nbaDraftId;

    @BeforeEach
    void setUp() {
        jdbc.update("delete from league where sleeper_id in (?, ?)",
                "it-league-sport-contamination-nfl", "it-league-sport-contamination-nba");
        jdbc.update("delete from manager where sleeper_user_id in (?, ?)",
                "it-user-sport-contamination", "it-user-sport-contamination-other");
        jdbc.update("delete from player where sport = 'nfl' and sleeper_id in (?, ?)",
                "it-player-sport-contamination-1", "it-player-sport-contamination-2");

        managerId = jdbc.queryForObject(
                "insert into manager (sleeper_user_id, display_name) values (?, ?) returning id",
                Long.class, "it-user-sport-contamination", "IT Manager");
        otherManagerId = jdbc.queryForObject(
                "insert into manager (sleeper_user_id, display_name) values (?, ?) returning id",
                Long.class, "it-user-sport-contamination-other", "IT Other Manager");

        nflLeagueId = jdbc.queryForObject(
                "insert into league (sport, season, sleeper_id, name, total_rosters) values (?, ?, ?, ?, ?) returning id",
                Long.class, "nfl", 2026, "it-league-sport-contamination-nfl", "IT NFL League", 8);
        nbaLeagueId = jdbc.queryForObject(
                "insert into league (sport, season, sleeper_id, name, total_rosters) values (?, ?, ?, ?, ?) returning id",
                Long.class, "nba", 2026, "it-league-sport-contamination-nba", "IT NBA League", 8);

        player1Id = jdbc.queryForObject(
                "insert into player (sport, sleeper_id, name, positions) values ('nfl', ?, ?, '{RB}') returning id",
                Long.class, "it-player-sport-contamination-1", "IT Player 1");
        player2Id = jdbc.queryForObject(
                "insert into player (sport, sleeper_id, name, positions) values ('nfl', ?, ?, '{RB}') returning id",
                Long.class, "it-player-sport-contamination-2", "IT Player 2");

        // The NFL history this manager and one leaguemate already have. The
        // target manager reaches (adpAtTime - pickNo = +4); the leaguemate does
        // not (0) -- a nonzero league mean is what makes shrinkage-toward-target
        // move visibly once observed changes, which is the whole point.
        nflDraftId = drafts.upsert(nflLeagueId, "it-draft-sport-contamination-nfl", 2026, 15, 8,
                "snake", "complete", null, "{}");
        drafts.upsertPicks(nflDraftId, List.of(
                new DraftRepository.PickRow(nflDraftId, 1, 1, 1, managerId, player1Id, 5.0),
                new DraftRepository.PickRow(nflDraftId, 2, 1, 2, otherManagerId, player2Id, 2.0)));
    }

    @AfterEach
    void tearDown() {
        jdbc.update("delete from league where id in (?, ?)", nflLeagueId, nbaLeagueId); // cascades draft, draft_pick
        jdbc.update("delete from manager where id in (?, ?)", managerId, otherManagerId);
        jdbc.update("delete from player where id in (?, ?)", player1Id, player2Id);
    }

    @Test
    void anNbaDraftForTheSameManagerDoesNotChangeHisFittedNflProfile() {
        ProfileService.Fit before = profiles.fit(Sport.NFL);
        var beforeProfile = before.profiles().get(managerId);

        // Seed a completed NBA draft with a pick for the SAME manager id. No
        // adp_at_time (no contemporaneous NBA board), so this contributes zero
        // scoreable picks and, if allCompletedPicks() were sport-filtered,
        // zero effect of any kind on an nfl-scoped fit.
        nbaDraftId = drafts.upsert(nbaLeagueId, "it-draft-sport-contamination-nba", 2026, 14, 8,
                "snake", "complete", null, "{}");
        drafts.upsertPicks(nbaDraftId, List.of(
                new DraftRepository.PickRow(nbaDraftId, 1, 1, 1, managerId, null, null)));

        ProfileService.Fit after = profiles.fit(Sport.NFL);
        var afterProfile = after.profiles().get(managerId);

        assertEquals(beforeProfile.draftsObserved(), afterProfile.draftsObserved(),
                "an NBA draft must not count toward this manager's NFL draftsObserved (the shrinkage N)");
        assertEquals(beforeProfile.picksScored(), afterProfile.picksScored(),
                "an NBA draft with no contemporaneous board contributes no scoreable picks");
        assertEquals(beforeProfile.reachBias(), afterProfile.reachBias(), 1e-9,
                "this manager's NFL reachBias must be unaffected by an NBA draft under the same manager id");

        long leaked = drafts.allCompletedPicks(Sport.NFL).stream()
                .filter(p -> p.draftId() == nbaDraftId)
                .count();
        assertEquals(0, leaked, "allCompletedPicks(NFL) must not surface picks from an NBA draft");
    }
}
