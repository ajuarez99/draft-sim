package com.ballknowers.draftsim.engine;

import com.ballknowers.draftsim.domain.Sport;
import com.ballknowers.draftsim.ingest.AuctionFixtures;
import com.ballknowers.draftsim.ingest.DraftOrderMapper;
import com.ballknowers.draftsim.ingest.LeagueIngestService;
import com.ballknowers.draftsim.ingest.PickMapper;
import com.ballknowers.draftsim.ingest.SleeperClient;
import com.ballknowers.draftsim.store.DraftRepository;
import com.ballknowers.draftsim.store.LeagueRepository;
import com.ballknowers.draftsim.store.ManagerRepository;
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
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.when;

/**
 * Spec 046 T031 / SC-002 / F14: the recorded $200 auction replayed ONE PICK AT A TIME through the real
 * {@code draft_pick} table and {@link AdpCompareService}. After each of the 230 picks every one of the ten slots'
 * {@code left} / {@code spotsLeft} / {@code maxBid} is compared with a value the test computes on its own from
 * the raw pick list (2,300 checks). Independence matters: the expected side reads the Sleeper JSON, not the
 * service's own arithmetic, so a wrong rule can't agree with itself.
 *
 * <p>SKIPS when Postgres is unreachable, so the caller must read the skip count. Fenced like
 * {@link com.ballknowers.draftsim.ingest.AuctionIngestIT}: every id is rewritten by {@link AuctionFixtures}.
 */
@SpringBootTest
class AuctionBudgetReplayIT {

    private static final String PREFIX = "it046-replay";
    private static final String LEAGUE = PREFIX + "-league";
    private static final String DRAFT = PREFIX + "-draft";
    private static final String THROWAWAY = "it046-replay-player";

    @BeforeAll
    static void requiresPostgres() {
        String url = System.getenv().getOrDefault("DB_URL", "jdbc:postgresql://localhost:5433/draftsim");
        boolean reachable;
        try (Connection c = DriverManager.getConnection(url, "draftsim", "draftsim")) {
            reachable = true;
        } catch (SQLException e) {
            reachable = false;
        }
        Assumptions.assumeTrue(reachable, "no Postgres reachable at " + url + " -- skipping auction replay IT");
    }

    @MockitoBean private SleeperClient sleeper;
    @Autowired private LeagueIngestService ingest;
    @Autowired private AdpCompareService compare;
    @Autowired private LeagueRepository leagues;
    @Autowired private DraftRepository drafts;
    @Autowired private ManagerRepository managers;
    @Autowired private JdbcTemplate jdbc;

    private final AuctionFixtures fixtures = new AuctionFixtures(PREFIX);

    @BeforeEach
    @AfterEach
    void clean() {
        jdbc.update("delete from league where sleeper_id = ?", LEAGUE);
        jdbc.update("delete from manager where sleeper_user_id like ?", PREFIX + "-%");
        jdbc.update("delete from player where sleeper_id = ?", THROWAWAY);
    }

    /** Ingests the league and draft with NO picks, so the test owns what lands in draft_pick. */
    private DraftRepository.DraftRow ingestEmptyDraft() {
        jdbc.update("insert into player (sport, sleeper_id, name) values ('nba', ?, 'IT Player')", THROWAWAY);
        when(sleeper.leagueChain(LEAGUE)).thenReturn(List.of(fixtures.league(LEAGUE)));
        when(sleeper.leagueUsers(LEAGUE)).thenReturn(fixtures.users());
        when(sleeper.drafts(LEAGUE)).thenReturn(List.of(fixtures.draft()));
        when(sleeper.draftPicks(DRAFT)).thenReturn(List.of());
        ingest.ingestChain(Sport.NBA, LEAGUE);
        return drafts.bySleeperId(DRAFT).orElseThrow();
    }

    private List<DraftRepository.PickRow> rows(DraftRepository.DraftRow draft, List<Map<String, Object>> raw,
                                               Map<String, Long> playerIds) {
        Map<String, Long> byUser = new HashMap<>();
        managers.idsBySleeperUserId().forEach((u, id) -> { if (u.startsWith(PREFIX + "-")) byUser.put(u, id); });
        Map<Integer, Long> slotLookup = DraftOrderMapper.slotLookup(DraftOrderMapper.normalize(draft.slotToManager()));
        List<DraftRepository.PickRow> out = new ArrayList<>();
        for (Map<String, Object> p : raw) out.add(PickMapper.toPickRow(draft.id(), p, byUser, slotLookup, playerIds));
        return out;
    }

    private AdpCompareService.Draft read() {
        LeagueRepository.LeagueRow league = leagues.bySleeperId(LEAGUE).orElseThrow();
        return compare.forLeague(league).orElseThrow().draft();
    }

    @SuppressWarnings("unchecked")
    private static Integer amountOf(Map<String, Object> pick) {
        Object a = ((Map<String, Object>) pick.get("metadata")).get("amount");
        return a == null ? null : Integer.valueOf(a.toString());
    }

    @Test
    void everySlotsBudgetMatchesAnIndependentRecomputationAfterEveryPick() {
        DraftRepository.DraftRow draft = ingestEmptyDraft();
        List<Map<String, Object>> raw = fixtures.picks();
        assertEquals(AuctionFixtures.PICKS, raw.size());
        List<DraftRepository.PickRow> all = rows(draft, raw, Map.of());

        int checks = 0;
        for (int k = 1; k <= raw.size(); k++) {
            drafts.replacePicks(draft.id(), all.subList(0, k));
            AdpCompareService.Draft d = read();
            assertEquals(AuctionFixtures.TEAMS, d.teamsState().size());
            for (int slot = 1; slot <= AuctionFixtures.TEAMS; slot++) {
                int spent = 0, filled = 0;
                for (int i = 0; i < k; i++) {
                    Map<String, Object> pick = raw.get(i);
                    if (((Number) pick.get("draft_slot")).intValue() != slot) continue;
                    filled++;
                    spent += amountOf(pick);
                }
                int expectedLeft = AuctionFixtures.BUDGET - spent;
                int expectedSpots = AuctionFixtures.ROUNDS - filled;
                int expectedMax = expectedSpots > 0 ? Math.max(0, expectedLeft - (expectedSpots - 1)) : 0;

                AdpCompareService.TeamState t = d.teamsState().get(slot - 1);
                String at = "after pick " + k + ", slot " + slot;
                assertEquals(slot, t.slot(), at);
                assertEquals(expectedLeft, t.left(), at + " left");
                assertEquals(expectedSpots, t.spotsLeft(), at + " spotsLeft");
                assertEquals(expectedMax, t.maxBid(), at + " maxBid");
                assertEquals(0, t.priceMissing(), at);
                checks++;   // one slot-state: left, spotsLeft and maxBid together
            }
        }
        assertEquals(2_300, checks);

        int totalLeft = read().teamsState().stream().mapToInt(AdpCompareService.TeamState::left).sum();
        assertEquals(AuctionFixtures.TEAMS * AuctionFixtures.BUDGET - AuctionFixtures.SUM_OF_AMOUNTS, totalLeft);
        assertEquals(18, totalLeft, "the recorded auction left $18 unspent across the room");

        // The caller's own card is the only one marked mine; a signed-out caller owns none.
        LeagueRepository.LeagueRow league = leagues.bySleeperId(LEAGUE).orElseThrow();
        var viewer = compare.forLeague(league, fixtures.userIds().get(0)).orElseThrow().draft().teamsState();
        assertEquals(1, viewer.stream().filter(AdpCompareService.TeamState::mine).count());
        assertEquals(0, read().teamsState().stream().filter(AdpCompareService.TeamState::mine).count());
    }

    @Test
    void beforeAnyPickEverySlotIs200Dollars23SpotsAndAMaxBidOf178EvenWithNoManagersMapped() {
        DraftRepository.DraftRow draft = ingestEmptyDraft();
        jdbc.update("update draft set slot_to_manager = '{}'::jsonb where id = ?", draft.id());

        AdpCompareService.Draft d = read();

        assertEquals(AuctionFixtures.TEAMS, d.teamsState().size());
        for (AdpCompareService.TeamState t : d.teamsState()) {
            assertNull(t.managerId(), "slot " + t.slot() + " has no manager yet");
            assertNull(t.name());
            assertEquals(200, t.left());
            assertEquals(23, t.spotsLeft());
            assertEquals(178, t.maxBid());
        }
        assertEquals(List.of(), d.sales());
    }

    @Test
    void anUnknownPlayerIsNamedFromSleeperAndAPickWithNoPriceIsCountedButFlagged() {
        DraftRepository.DraftRow draft = ingestEmptyDraft();
        List<Map<String, Object>> raw = new ArrayList<>(fixtures.picks().subList(0, 4));
        @SuppressWarnings("unchecked")
        Map<String, Object> third = (Map<String, Object>) raw.get(2).get("metadata");
        raw.get(2).put("player_id", "no-such-player");
        third.put("first_name", "Mystery");
        third.put("last_name", "Guest");
        @SuppressWarnings("unchecked")
        Map<String, Object> fourth = (Map<String, Object>) raw.get(3).get("metadata");
        fourth.remove("amount");
        int slotOfFourth = ((Number) raw.get(3).get("draft_slot")).intValue();

        // Pick 1's Sleeper id resolves to the throwaway player, so the "named from the player" path runs too.
        Long throwawayId = jdbc.queryForObject("select id from player where sleeper_id = ?", Long.class, THROWAWAY);
        drafts.replacePicks(draft.id(), rows(draft, raw, Map.of("1945", throwawayId)));

        AdpCompareService.Draft d = read();

        assertEquals(List.of(4, 3, 2, 1), d.sales().stream().map(AdpCompareService.SaleRow::pickNo).toList(),
                "newest sale first");
        AdpCompareService.SaleRow unknown = d.sales().get(1);
        assertEquals("Mystery Guest", unknown.name());
        assertNull(unknown.playerId());
        assertEquals(amountOf(fixtures.picks().get(2)), unknown.amount());
        assertFalse(unknown.priceMissing());
        assertEquals("IT Player", d.sales().get(3).name(), "a resolved player is named by the player row");

        AdpCompareService.SaleRow unpriced = d.sales().get(0);
        assertNull(unpriced.amount());
        assertTrue(unpriced.priceMissing());
        AdpCompareService.TeamState t = d.teamsState().get(slotOfFourth - 1);
        // Picks 3 and 4 share a slot in the recording: one priced at $46, one without a price.
        assertEquals(2, t.filled(), "the unpriced pick still fills a spot");
        assertEquals(1, t.priceMissing());
        assertEquals(46, t.spent(), "and adds nothing to spent, which is why it is flagged");
        assertEquals(21, t.spotsLeft());
        assertEquals(154, t.left());
        assertEquals(134, t.maxBid());
    }
}
