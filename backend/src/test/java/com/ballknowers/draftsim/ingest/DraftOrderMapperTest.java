package com.ballknowers.draftsim.ingest;

import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The inversion LeagueIngestService and LiveDraftPoller now share. The cases that
 * matter are all "what Sleeper actually returns": a null draft_order before the
 * commissioner sets one, and user ids that have no manager row.
 */
class DraftOrderMapperTest {

    private static Map<String, Object> draft(Object draftOrder) {
        Map<String, Object> raw = new HashMap<>();
        raw.put("status", "pre_draft");
        raw.put("draft_order", draftOrder);
        return raw;
    }

    @Test
    void invertsSleeperUserToSlotIntoSlotToManager() {
        Map<String, Object> order = new LinkedHashMap<>();
        order.put("u1", 3);
        order.put("u2", 1);

        Map<String, Long> out = DraftOrderMapper.slotToManager(draft(order), Map.of("u1", 10L, "u2", 20L));

        assertEquals(Map.of("3", 10L, "1", 20L), out);
    }

    /**
     * The key type is load-bearing: it is what JsonUtil.write persists into
     * draft.slot_to_manager, and LeagueController.seats, SimulationService.seatsOf
     * and LiveDraftPoller all parse it back with Integer.parseInt.
     */
    @Test
    void slotKeysAreStringsSoThePersistedJsonShapeDoesNotChange() {
        Map<String, Long> out = DraftOrderMapper.slotToManager(draft(Map.of("u1", 5)), Map.of("u1", 10L));
        assertTrue(out.containsKey("5"));
    }

    /** Sleeper returns draft_order: null until the order is set -- verified live on West Coast FF 2026. */
    @Test
    void aNullDraftOrderYieldsAnEmptyMapRatherThanThrowing() {
        assertTrue(DraftOrderMapper.slotToManager(draft(null), Map.of("u1", 10L)).isEmpty());
        assertTrue(DraftOrderMapper.slotToManager(null, Map.of("u1", 10L)).isEmpty());
    }

    @Test
    void aUserWithNoManagerRowIsDroppedAndReportedRatherThanGuessedAt() {
        Map<String, Object> order = new LinkedHashMap<>();
        order.put("u1", 1);
        order.put("stranger", 2);
        Map<String, Object> raw = draft(order);

        assertEquals(Map.of("1", 10L), DraftOrderMapper.slotToManager(raw, Map.of("u1", 10L)));
        assertEquals(java.util.List.of("stranger"), DraftOrderMapper.unmappedUserIds(raw, Map.of("u1", 10L)));
    }

    @Test
    void slotLookupRekeysOnTheIntSlotPickMapperWants() {
        assertEquals(Map.of(3, 10L), DraftOrderMapper.slotLookup(Map.of("3", 10L)));
    }

    /**
     * The stored map comes back from Jackson with Integer values while everything
     * derived here holds Longs, and Integer.valueOf(5).equals(5L) is false -- a
     * change-detector built on the raw maps would fire on every single poll tick.
     */
    @Test
    void normalizeMakesAStoredIntegerValuedMapComparableToADerivedOne() {
        Map<String, Object> stored = Map.of("3", 10);           // as Jackson hands it back
        assertNotEquals(stored, Map.of("3", 10L));              // the trap, pinned
        assertEquals(Map.of("3", 10L), DraftOrderMapper.normalize(stored));
        assertTrue(DraftOrderMapper.normalize(null).isEmpty());
    }
}
