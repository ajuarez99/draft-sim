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

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Spec 046 US4 / T010: {@code BoardService.backfillAdpAtTime} stamps a snake draft's picks and leaves an
 * auction's alone. An auction pick's "position" is nomination order, so an {@code adp_at_time} on it would be
 * meaningless and would feed straight back into profile fitting.
 *
 * <p>Same fencing as {@link BackfillAdpAtTimeNearestSnapshotIT}: the fixture drafts sit in 2002, no real blend
 * snapshot is near them, and each call is limited to one draft row id.
 */
@SpringBootTest
class AuctionBackfillExclusionIT {

    private static final String SNAKE_LEAGUE = "it046-bf-snake-league";
    private static final String AUCTION_LEAGUE = "it046-bf-auction-league";

    @BeforeAll
    static void requiresPostgres() {
        String url = System.getenv().getOrDefault("DB_URL", "jdbc:postgresql://localhost:5433/draftsim");
        boolean reachable;
        try (Connection c = DriverManager.getConnection(url, "draftsim", "draftsim")) {
            reachable = true;
        } catch (SQLException e) {
            reachable = false;
        }
        Assumptions.assumeTrue(reachable, "no Postgres reachable at " + url + " -- skipping auction backfill IT");
    }

    @Autowired private BoardService boards;
    @Autowired private JdbcTemplate jdbc;

    private long snakeDraft;
    private long auctionDraft;

    @BeforeEach
    void setUp() {
        cleanUp();
        long player = jdbc.queryForObject(
                "insert into player (sport, sleeper_id, name) values ('nba', 'it046-bf-p', 'IT bf') returning id", Long.class);
        jdbc.update("insert into adp_snapshot (player_id, sport, source, captured_on, adp) values (?, 'nba', 'blend', date '2002-06-15', 12)",
                player);
        snakeDraft = draftWithOnePick(SNAKE_LEAGUE, "it046-bf-snake-draft", "snake", player);
        auctionDraft = draftWithOnePick(AUCTION_LEAGUE, "it046-bf-auction-draft", "auction", player);
    }

    @AfterEach
    void cleanUp() {
        jdbc.update("delete from league where sleeper_id in (?, ?)", SNAKE_LEAGUE, AUCTION_LEAGUE);
        jdbc.update("delete from player where sleeper_id = 'it046-bf-p'");
    }

    private long draftWithOnePick(String leagueSleeperId, String draftSleeperId, String type, long player) {
        long leagueId = jdbc.queryForObject(
                "insert into league (sport, season, sleeper_id, name, total_rosters) values ('nba', 2002, ?, 'IT', 2) returning id",
                Long.class, leagueSleeperId);
        long draftId = jdbc.queryForObject(
                "insert into draft (league_id, sleeper_draft_id, season, rounds, teams, draft_type, status, start_time, slot_to_manager)"
                        + " values (?, ?, 2002, 1, 2, ?, 'complete', timestamptz '2002-06-15 12:00:00+00', '{}'::jsonb) returning id",
                Long.class, leagueId, draftSleeperId, type);
        jdbc.update("insert into draft_pick (draft_id, pick_no, round, draft_slot, player_id) values (?, 1, 1, 1, ?)", draftId, player);
        return draftId;
    }

    private Double stamped(long draftId) {
        return jdbc.queryForObject("select adp_at_time from draft_pick where draft_id = ? and pick_no = 1", Double.class, draftId);
    }

    @Test
    void aSnakePickIsStampedAndAnAuctionPickIsNot() {
        boards.backfillAdpAtTime(Sport.NBA, snakeDraft);
        boards.backfillAdpAtTime(Sport.NBA, auctionDraft);

        assertEquals(12.0, stamped(snakeDraft), 1e-9, "control: the snake draft is stamped from the blend snapshot");
        assertNull(stamped(auctionDraft), "an auction pick has no board position");
    }
}
