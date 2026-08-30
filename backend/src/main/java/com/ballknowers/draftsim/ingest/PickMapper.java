package com.ballknowers.draftsim.ingest;

import com.ballknowers.draftsim.store.DraftRepository;

import java.util.Map;

/**
 * Shared pick-mapping logic between {@link LeagueIngestService}'s batch replay and
 * {@link LiveDraftPoller}'s live ticks: picked_by preferred, falls back to draft_slot
 * on autopick, an unmatched Sleeper player id resolves to null (never guessed).
 * adp_at_time is always null from this path — BoardService backfills it later.
 */
public final class PickMapper {

    private PickMapper() {}

    public static DraftRepository.PickRow toPickRow(long draftId, Map<String, Object> rawPick,
            Map<String, Long> managerByUserId, Map<Integer, Long> slotLookup,
            Map<String, Long> playerIdsBySleeperId) {

        String sleeperPlayerId = str(rawPick.get("player_id"));
        int slot = asInt(rawPick.get("draft_slot"), 0);

        // picked_by is empty on autopicked picks; the slot is authoritative.
        String pickedBy = str(rawPick.get("picked_by"));
        Long managerId = (pickedBy != null && !pickedBy.isBlank())
                ? managerByUserId.get(pickedBy)
                : slotLookup.get(slot);
        if (managerId == null) managerId = slotLookup.get(slot);

        return new DraftRepository.PickRow(
                draftId,
                asInt(rawPick.get("pick_no"), 0),
                asInt(rawPick.get("round"), 0),
                slot,
                managerId,
                sleeperPlayerId == null ? null : playerIdsBySleeperId.get(sleeperPlayerId),
                null);   // adp_at_time filled in by BoardService after the board exists
    }

    private static String str(Object o) {
        return o == null ? null : o.toString();
    }

    private static int asInt(Object o, int fallback) {
        if (o == null) return fallback;
        if (o instanceof Number n) return n.intValue();
        try {
            return Integer.parseInt(o.toString());
        } catch (NumberFormatException e) {
            return fallback;
        }
    }
}
