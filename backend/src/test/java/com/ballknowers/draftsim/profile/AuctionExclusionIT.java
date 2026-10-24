package com.ballknowers.draftsim.profile;

import com.ballknowers.draftsim.domain.Position;
import com.ballknowers.draftsim.domain.Sport;
import com.ballknowers.draftsim.store.DraftRepository;
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
 * Spec 046 US4 / SC-004: an auction draft's pick numbers are nomination order, so no snake-order model may
 * read them. A league with one snake and one auction draft (each completed, each pick carrying a manager and
 * an {@code adp_at_time}) must fit EXACTLY the same profiles as the snake draft alone, field for field, and a
 * manager whose only history is the auction has {@code drafts_observed = 0} (US4 AS2).
 *
 * <p>The comparison is fit-with-the-auction versus fit-after-deleting-it, in the same database, so whatever
 * else the database holds cancels out. The auction's picks are deliberately loud (a board position of 90 on a
 * pick numbered 1-6) so that if they were read, every figure would move.
 *
 * <p>Seeds throwaway NBA 2096/2097 rows and deletes them. SKIPS when Postgres is unreachable, so the caller
 * must read the skip count.
 */
@SpringBootTest
class AuctionExclusionIT {

    private static final String SNAKE_LEAGUE = "it046-excl-snake-league";
    private static final String AUCTION_LEAGUE = "it046-excl-auction-league";
    private static final String SNAKE_DRAFT = "it046-excl-snake-draft";
    private static final String AUCTION_DRAFT = "it046-excl-auction-draft";

    @BeforeAll
    static void requiresPostgres() {
        String url = System.getenv().getOrDefault("DB_URL", "jdbc:postgresql://localhost:5433/draftsim");
        boolean reachable;
        try (Connection c = DriverManager.getConnection(url, "draftsim", "draftsim")) {
            reachable = true;
        } catch (SQLException e) {
            reachable = false;
        }
        Assumptions.assumeTrue(reachable, "no Postgres reachable at " + url + " -- skipping auction exclusion IT");
    }

    @Autowired private ProfileService profiles;
    @Autowired private DraftRepository drafts;
    @Autowired private JdbcTemplate jdbc;

    private long m1, m2, m3;

    @BeforeEach
    void seed() {
        clean();
        m1 = manager("m1");
        m2 = manager("m2");
        m3 = manager("m3");   // only ever in the auction
        long[] players = new long[4];
        String[] pos = {"PG", "SG", "SF", "C"};
        for (int i = 0; i < 4; i++) {
            players[i] = jdbc.queryForObject(
                    "insert into player (sport, sleeper_id, name, positions) values ('nba', ?, ?, ?::text[]) returning id",
                    Long.class, "it046-excl-p" + i, "IT Excl " + i, "{" + pos[i] + "}");
        }

        long snakeLeague = league(SNAKE_LEAGUE, 2096);
        long snake = draft(snakeLeague, SNAKE_DRAFT, 2096, 4, 2, "snake");
        // 4 rounds x 2 teams, all in the early window so positional tilt reads them too.
        for (int pick = 1; pick <= 8; pick++) {
            int slot = ((pick - 1) % 2) + 1;
            pick(snake, pick, (pick + 1) / 2, slot, slot == 1 ? m1 : m2, players[(pick - 1) % 4], pick + 2.0);
        }

        long auctionLeague = league(AUCTION_LEAGUE, 2097);
        long auction = draft(auctionLeague, AUCTION_DRAFT, 2097, 2, 3, "auction");
        for (int pick = 1; pick <= 6; pick++) {
            int slot = ((pick - 1) % 3) + 1;
            pick(auction, pick, (pick + 2) / 3, slot, slot == 1 ? m1 : slot == 2 ? m2 : m3,
                    players[(pick - 1) % 4], 90.0 + pick);
        }
    }

    @AfterEach
    void clean() {
        jdbc.update("delete from league where sleeper_id in (?, ?)", SNAKE_LEAGUE, AUCTION_LEAGUE);
        jdbc.update("delete from manager where sleeper_user_id like 'it046-excl-%'");
        jdbc.update("delete from player where sleeper_id like 'it046-excl-%'");
    }

    private long manager(String tag) {
        return jdbc.queryForObject(
                "insert into manager (sleeper_user_id, display_name) values (?, ?) returning id",
                Long.class, "it046-excl-" + tag, "IT " + tag);
    }

    private long league(String sleeperId, int season) {
        return jdbc.queryForObject("""
                insert into league (sport, season, sleeper_id, name, total_rosters)
                values ('nba', ?, ?, 'IT exclusion', 3) returning id""", Long.class, season, sleeperId);
    }

    private long draft(long leagueId, String sleeperDraftId, int season, int rounds, int teams, String type) {
        return jdbc.queryForObject("""
                insert into draft (league_id, sleeper_draft_id, season, rounds, teams, draft_type, status, slot_to_manager)
                values (?, ?, ?, ?, ?, ?, 'complete', '{}'::jsonb) returning id""",
                Long.class, leagueId, sleeperDraftId, season, rounds, teams, type);
    }

    private void pick(long draftId, int pickNo, int round, int slot, long managerId, long playerId, double adpAtTime) {
        jdbc.update("""
                insert into draft_pick (draft_id, pick_no, round, draft_slot, manager_id, player_id, adp_at_time)
                values (?, ?, ?, ?, ?, ?, ?)""", draftId, pickNo, round, slot, managerId, playerId, adpAtTime);
    }

    @Test
    void theAuctionIsInertToEveryFittedFigure() {
        // Precondition: the auction really is a completed draft with scoreable, manager-attributed picks,
        // otherwise this test would pass for the wrong reason.
        assertEquals(6, jdbc.queryForObject("""
                select count(*) from draft_pick p join draft d on d.id = p.draft_id
                where d.sleeper_draft_id = ? and d.status = 'complete' and p.manager_id is not null
                  and p.adp_at_time is not null""", Integer.class, AUCTION_DRAFT));
        assertFalse(DraftRepository.modelsSnakeOrder("auction"));
        assertTrue(DraftRepository.modelsSnakeOrder("snake"));
        assertTrue(DraftRepository.modelsSnakeOrder("linear"), "linear is deliberately unchanged (research R5)");

        long auctionId = jdbc.queryForObject("select id from draft where sleeper_draft_id = ?", Long.class, AUCTION_DRAFT);
        long snakeId = jdbc.queryForObject("select id from draft where sleeper_draft_id = ?", Long.class, SNAKE_DRAFT);
        List<DraftRepository.CompletedPick> read = drafts.allCompletedPicks(Sport.NBA);
        assertTrue(read.stream().noneMatch(p -> p.draftId() == auctionId), "the auction's picks never reach profile fitting");
        assertEquals(8, read.stream().filter(p -> p.draftId() == snakeId).count(), "control: the snake draft is read");

        ProfileService.Fit withAuction = profiles.fit(Sport.NBA);

        jdbc.update("delete from draft where sleeper_draft_id = ?", AUCTION_DRAFT);
        ProfileService.Fit snakeOnly = profiles.fit(Sport.NBA);

        assertEquals(snakeOnly.profiles(), withAuction.profiles(), "every manager's fitted profile, field for field");
        assertEquals(snakeOnly.empiricalReachBias(), withAuction.empiricalReachBias());
        assertEquals(snakeOnly.relativeReachBias(), withAuction.relativeReachBias());
        assertEquals(snakeOnly.relativeReachStdErr(), withAuction.relativeReachStdErr());
        assertEquals(snakeOnly.scoreablePicks(), withAuction.scoreablePicks());
        assertEquals(snakeOnly.priors().observations(), withAuction.priors().observations());
        for (int bucket = 0; bucket < withAuction.priors().buckets(); bucket++) {
            for (Position pos : List.of(Position.PG, Position.SG, Position.SF, Position.PF, Position.C)) {
                assertEquals(snakeOnly.priors().probability(bucket, pos), withAuction.priors().probability(bucket, pos), 0.0);
            }
        }
    }

    @Test
    void aManagerWithOnlyAuctionHistoryHasNoObservedDrafts() {
        ManagerProfile onlyAuction = profiles.fit(Sport.NBA).profiles().get(m3);

        assertNotNull(onlyAuction, "every known manager still gets a profile");
        assertEquals(0, onlyAuction.draftsObserved());
        assertEquals(0, onlyAuction.picksScored());
        assertEquals(Provenance.NEUTRAL, onlyAuction.provenance());
        // The two who also drafted a snake draft are observed in exactly that one.
        assertEquals(1, profiles.fit(Sport.NBA).profiles().get(m1).draftsObserved());
        assertEquals(1, profiles.fit(Sport.NBA).profiles().get(m2).draftsObserved());
    }
}
