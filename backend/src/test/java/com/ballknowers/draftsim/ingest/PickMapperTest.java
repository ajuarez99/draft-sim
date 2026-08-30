package com.ballknowers.draftsim.ingest;

import com.ballknowers.draftsim.store.DraftRepository;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Pins the pick-mapping logic extracted out of LeagueIngestService.ingestDraft
 * (claude/live-poller-plan.md decision 5) -- previously zero unit test coverage.
 */
class PickMapperTest {

    private static Map<String, Object> rawPick(String playerId, String pickedBy, int draftSlot,
                                                int pickNo, int round) {
        Map<String, Object> p = new HashMap<>();
        p.put("player_id", playerId);
        p.put("picked_by", pickedBy);
        p.put("draft_slot", draftSlot);
        p.put("pick_no", pickNo);
        p.put("round", round);
        return p;
    }

    @Test
    void blankPickedByFallsBackToDraftSlot() {
        Map<String, Object> raw = rawPick("p1", "", 3, 15, 2);
        Map<String, Long> managerByUserId = Map.of("u1", 100L);
        Map<Integer, Long> slotLookup = Map.of(3, 200L);
        Map<String, Long> playerIds = Map.of("p1", 1L);

        DraftRepository.PickRow row = PickMapper.toPickRow(9L, raw, managerByUserId, slotLookup, playerIds);

        assertEquals(200L, row.managerId());
        assertEquals(9L, row.draftId());
        assertEquals(15, row.pickNo());
        assertEquals(2, row.round());
        assertEquals(3, row.draftSlot());
        assertEquals(1L, row.playerId());
    }

    @Test
    void presentPickedByWinsOverSlot() {
        // A traded pick: the slot's "owner" (200L) is not who actually made the pick (100L).
        Map<String, Object> raw = rawPick("p1", "u1", 3, 15, 2);
        Map<String, Long> managerByUserId = Map.of("u1", 100L);
        Map<Integer, Long> slotLookup = Map.of(3, 200L);
        Map<String, Long> playerIds = Map.of("p1", 1L);

        DraftRepository.PickRow row = PickMapper.toPickRow(9L, raw, managerByUserId, slotLookup, playerIds);

        assertEquals(100L, row.managerId());
    }

    @Test
    void unmatchedSleeperPlayerIdResolvesToNullWithoutThrowing() {
        Map<String, Object> raw = rawPick("unknown-player", "u1", 3, 15, 2);
        Map<String, Long> managerByUserId = Map.of("u1", 100L);
        Map<Integer, Long> slotLookup = Map.of(3, 200L);
        Map<String, Long> playerIds = Map.of("p1", 1L);

        DraftRepository.PickRow row = assertDoesNotThrow(
                () -> PickMapper.toPickRow(9L, raw, managerByUserId, slotLookup, playerIds));

        assertNull(row.playerId());
    }

    @Test
    void adpAtTimeIsAlwaysNull() {
        Map<String, Object> raw = rawPick("p1", "u1", 3, 15, 2);
        Map<String, Long> managerByUserId = Map.of("u1", 100L);
        Map<Integer, Long> slotLookup = Map.of(3, 200L);
        Map<String, Long> playerIds = Map.of("p1", 1L);

        DraftRepository.PickRow row = PickMapper.toPickRow(9L, raw, managerByUserId, slotLookup, playerIds);

        assertNull(row.adpAtTime());
    }

    @Test
    void pickedByAbsentFromUnrecognizedUserAlsoFallsBackToSlot() {
        // picked_by present but not a manager we know about (e.g. mid-resolution) -- falls back to slot.
        Map<String, Object> raw = rawPick("p1", "some-unknown-user", 3, 15, 2);
        Map<String, Long> managerByUserId = Map.of("u1", 100L);
        Map<Integer, Long> slotLookup = Map.of(3, 200L);
        Map<String, Long> playerIds = Map.of("p1", 1L);

        DraftRepository.PickRow row = PickMapper.toPickRow(9L, raw, managerByUserId, slotLookup, playerIds);

        assertEquals(200L, row.managerId());
    }
}
