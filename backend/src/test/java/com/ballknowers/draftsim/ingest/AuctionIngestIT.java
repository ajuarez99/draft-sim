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
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.when;

/**
 * Spec 046 T008 / SC-001: the recorded $200 auction replayed through the real league ingest into Postgres.
 * 230 rows, sum of amounts 1,982, {@code draft.budget = 200}, {@code draft_type = 'auction'}, a name on
 * every pick, and a re-ingest with one changed amount overwrites it (F12: {@code = excluded}, never
 * coalesce). SKIPS when Postgres is unreachable, so the caller must read the skip count.
 *
 * <p>Fenced: every id in the fixture is rewritten ({@link AuctionFixtures}) so no real draft or manager
 * row can be touched; the one throwaway player only exists to satisfy the ingest's "player table is empty"
 * guard, and the picks' real player ids may or may not resolve (the test asserts nothing about that).
 */
@SpringBootTest
class AuctionIngestIT {

    private static final String PREFIX = "it046-ingest";
    private static final String LEAGUE = PREFIX + "-league";
    private static final String DRAFT = PREFIX + "-draft";

    @BeforeAll
    static void requiresPostgres() {
        String url = System.getenv().getOrDefault("DB_URL", "jdbc:postgresql://localhost:5433/draftsim");
        boolean reachable;
        try (Connection c = DriverManager.getConnection(url, "draftsim", "draftsim")) {
            reachable = true;
        } catch (SQLException e) {
            reachable = false;
        }
        Assumptions.assumeTrue(reachable, "no Postgres reachable at " + url + " -- skipping auction ingest IT");
    }

    @MockitoBean private SleeperClient sleeper;
    @Autowired private LeagueIngestService ingest;
    @Autowired private JdbcTemplate jdbc;

    private final AuctionFixtures fixtures = new AuctionFixtures(PREFIX);

    @BeforeEach
    @AfterEach
    void clean() {
        jdbc.update("delete from league where sleeper_id = ?", LEAGUE);
        jdbc.update("delete from manager where sleeper_user_id like ?", PREFIX + "-%");
        jdbc.update("delete from player where sleeper_id = 'it046-ingest-player'");
    }

    private void stubSleeper(List<Map<String, Object>> picks) {
        jdbc.update("insert into player (sport, sleeper_id, name) values ('nba', 'it046-ingest-player', 'IT Player')");
        when(sleeper.leagueChain(LEAGUE)).thenReturn(List.of(fixtures.league(LEAGUE)));
        when(sleeper.leagueUsers(LEAGUE)).thenReturn(fixtures.users());
        when(sleeper.drafts(LEAGUE)).thenReturn(List.of(fixtures.draft()));
        when(sleeper.draftPicks(DRAFT)).thenReturn(picks);
    }

    private Integer num(String sql) {
        return jdbc.queryForObject(sql, Integer.class, DRAFT);
    }

    @Test
    void theRecordedAuctionLandsWithPricesNamesAndBudget() {
        stubSleeper(fixtures.picks());

        LeagueIngestService.Result result = ingest.ingestChain(Sport.NBA, LEAGUE);

        assertEquals(AuctionFixtures.PICKS, result.picksIngested());
        assertEquals(AuctionFixtures.PICKS, num(
                "select count(*) from draft_pick p join draft d on d.id = p.draft_id where d.sleeper_draft_id = ?"));
        assertEquals(AuctionFixtures.SUM_OF_AMOUNTS, num(
                "select sum(p.amount) from draft_pick p join draft d on d.id = p.draft_id where d.sleeper_draft_id = ?"));
        assertEquals(AuctionFixtures.BUDGET, num("select budget from draft where sleeper_draft_id = ?"));
        assertEquals("auction", jdbc.queryForObject(
                "select draft_type from draft where sleeper_draft_id = ?", String.class, DRAFT));
        assertEquals(0, num("""
                select count(*) from draft_pick p join draft d on d.id = p.draft_id
                where d.sleeper_draft_id = ? and (p.player_name is null or p.sleeper_player_id is null)"""),
                "every pick keeps what Sleeper called the player, resolved or not");
        assertEquals("Trae Young", jdbc.queryForObject("""
                select p.player_name from draft_pick p join draft d on d.id = p.draft_id
                where d.sleeper_draft_id = ? and p.pick_no = 1""", String.class, DRAFT));
    }

    @Test
    void aReIngestOverwritesAChangedAmountAndNeverCoalescesIt() {
        stubSleeper(fixtures.picks());
        ingest.ingestChain(Sport.NBA, LEAGUE);

        List<Map<String, Object>> changed = fixtures.picks();
        @SuppressWarnings("unchecked")
        Map<String, Object> first = (Map<String, Object>) changed.get(0).get("metadata");
        assertEquals("22", first.get("amount"), "fixture precondition: pick 1 sold for $22");
        first.put("amount", "30");
        // Pick 2's price disappears: Sleeper's value is the truth, so null must replace 44, not be coalesced away.
        @SuppressWarnings("unchecked")
        Map<String, Object> second = (Map<String, Object>) changed.get(1).get("metadata");
        assertEquals("44", second.get("amount"), "fixture precondition: pick 2 sold for $44");
        second.remove("amount");
        when(sleeper.draftPicks(DRAFT)).thenReturn(changed);

        ingest.ingestChain(Sport.NBA, LEAGUE);

        assertEquals(30, num("""
                select p.amount from draft_pick p join draft d on d.id = p.draft_id
                where d.sleeper_draft_id = ? and p.pick_no = 1"""));
        assertNull(jdbc.queryForObject("""
                select p.amount from draft_pick p join draft d on d.id = p.draft_id
                where d.sleeper_draft_id = ? and p.pick_no = 2""", Integer.class, DRAFT));
        assertEquals(AuctionFixtures.SUM_OF_AMOUNTS - 22 + 30 - 44, num(
                "select sum(p.amount) from draft_pick p join draft d on d.id = p.draft_id where d.sleeper_draft_id = ?"));
    }
}
