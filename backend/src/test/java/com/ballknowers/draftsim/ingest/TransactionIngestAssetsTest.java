package com.ballknowers.draftsim.ingest;

import com.ballknowers.draftsim.domain.Sport;
import com.ballknowers.draftsim.store.JsonUtil;
import com.ballknowers.draftsim.store.LeagueRepository;
import com.ballknowers.draftsim.store.LeagueTransactionRepository;
import com.fasterxml.jackson.core.type.TypeReference;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/** Spec 033 T006: toRow stores draft_picks / waiver_budget verbatim, and [] when absent. */
class TransactionIngestAssetsTest {

    private static final LeagueRepository.LeagueRow LEAGUE = new LeagueRepository.LeagueRow(
            7L, Sport.NFL, "sl", "L", 2026, 12, List.of(), 1.0, null, "in_season");

    private static Map<String, Object> pick(int round, int roster, int owner, int prev) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("round", round);
        m.put("season", "2026");
        m.put("roster_id", roster);
        m.put("owner_id", owner);
        m.put("previous_owner_id", prev);
        return m;
    }

    private static LeagueTransactionRepository.Row toRow(Map<String, Object> t) {
        return TransactionIngestService.toRow(LEAGUE, 1, t, Map.of());
    }

    @Test
    void picksTradeStoresAllFourPicksAndEmptyBudget() {
        Map<String, Object> t = new LinkedHashMap<>();
        t.put("transaction_id", "1346741394630250496");
        t.put("type", "trade");
        t.put("status", "complete");
        t.put("roster_ids", List.of(8, 11));
        t.put("adds", null);
        t.put("drops", null);
        t.put("draft_picks", List.of(pick(1, 11, 8, 11), pick(2, 11, 8, 11), pick(9, 11, 8, 11), pick(1, 8, 11, 8)));
        t.put("waiver_budget", List.of());
        t.put("leg", 1);
        t.put("status_updated", 1775722286572L);

        LeagueTransactionRepository.Row r = toRow(t);

        assertEquals("TRADE", r.type());
        assertEquals("{}", r.addsJson());
        assertEquals("[]", r.waiverBudgetJson());
        List<Map<String, Object>> picks = JsonUtil.read(r.draftPicksJson(),
                new TypeReference<List<Map<String, Object>>>() {});
        assertEquals(4, picks.size());
        assertEquals(1, picks.get(3).get("round"));
        assertEquals(11, picks.get(3).get("owner_id"));
        assertEquals(8, picks.get(3).get("previous_owner_id"));
    }

    @Test
    void faabTradeStoresBudgetAndEmptyPicks() {
        Map<String, Object> t = new LinkedHashMap<>();
        t.put("transaction_id", "766");
        t.put("type", "trade");
        t.put("status", "complete");
        t.put("roster_ids", List.of(1, 8));
        t.put("adds", Map.of("2334", 8));
        t.put("drops", Map.of("2334", 1));
        t.put("draft_picks", List.of());
        Map<String, Object> bud = new LinkedHashMap<>();
        bud.put("sender", 8);
        bud.put("receiver", 1);
        bud.put("amount", 67);
        t.put("waiver_budget", List.of(bud));

        LeagueTransactionRepository.Row r = toRow(t);

        assertEquals("[]", r.draftPicksJson());
        assertEquals("[{\"sender\":8,\"receiver\":1,\"amount\":67}]", r.waiverBudgetJson());
    }

    @Test
    void absentKeysWriteEmptyArrays() {
        Map<String, Object> t = new LinkedHashMap<>();
        t.put("transaction_id", "9");
        t.put("type", "waiver");
        t.put("status", "complete");
        t.put("roster_ids", List.of(3));

        LeagueTransactionRepository.Row r = toRow(t);

        assertEquals("[]", r.draftPicksJson());
        assertEquals("[]", r.waiverBudgetJson());
    }
}
