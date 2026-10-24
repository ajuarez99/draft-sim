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

    // ---- Spec 046: auction price, Sleeper name, raw player id -------------------------------

    private static DraftRepository.PickRow mapWithMetadata(Map<String, Object> metadata) {
        Map<String, Object> raw = rawPick("p1", "u1", 3, 15, 2);
        if (metadata != null) raw.put("metadata", metadata);
        return PickMapper.toPickRow(9L, raw, Map.of("u1", 100L), Map.of(3, 200L), Map.of("p1", 1L));
    }

    private static Map<String, Object> metadata(Object amount) {
        Map<String, Object> m = new HashMap<>();
        m.put("first_name", "Trae");
        m.put("last_name", "Young");
        if (amount != null) m.put("amount", amount);
        return m;
    }

    @Test
    void amountIsAStringOnTheWireAndParsesToAnInt() {
        assertEquals(22, mapWithMetadata(metadata("22")).amount());
        assertEquals(0, mapWithMetadata(metadata("0")).amount(), "a $0 sale is a real price, not a missing one");
    }

    @Test
    void aMissingBlankOrGarbageAmountIsNullNeverZero() {
        assertNull(mapWithMetadata(metadata(null)).amount());
        assertNull(mapWithMetadata(metadata("")).amount());
        assertNull(mapWithMetadata(metadata("  ")).amount());
        assertNull(mapWithMetadata(metadata("abc")).amount());
        assertNull(mapWithMetadata(null).amount(), "a snake pick has no metadata at all");
    }

    @Test
    void aNegativeAmountIsNull() {
        assertNull(mapWithMetadata(metadata("-5")).amount());
    }

    @Test
    void nameIsBuiltFromTheMetadataAndTheRawPlayerIdIsKept() {
        DraftRepository.PickRow row = mapWithMetadata(metadata("22"));
        assertEquals("Trae Young", row.playerName());
        assertEquals("p1", row.sleeperPlayerId());
    }

    @Test
    void aSingleNameIsTrimmedAndABlankOneIsNull() {
        Map<String, Object> only = new HashMap<>();
        only.put("first_name", "Nene");
        assertEquals("Nene", mapWithMetadata(only).playerName());

        Map<String, Object> blank = new HashMap<>();
        blank.put("first_name", " ");
        blank.put("last_name", "");
        assertNull(mapWithMetadata(blank).playerName());
        assertNull(mapWithMetadata(null).playerName());
    }

    @Test
    void anUnresolvedPlayerStillKeepsItsNameAndSleeperId() {
        Map<String, Object> raw = rawPick("unknown-player", "u1", 3, 15, 2);
        raw.put("metadata", metadata("7"));
        DraftRepository.PickRow row = PickMapper.toPickRow(9L, raw, Map.of("u1", 100L), Map.of(3, 200L), Map.of("p1", 1L));
        assertNull(row.playerId());
        assertEquals("Trae Young", row.playerName());
        assertEquals("unknown-player", row.sleeperPlayerId());
        assertEquals(7, row.amount());
    }
}
