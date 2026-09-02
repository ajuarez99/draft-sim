package com.ballknowers.draftsim.ingest;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Shared draft_order-to-seat-map logic between {@link LeagueIngestService}'s batch
 * ingest and {@link LiveDraftPoller}'s live ticks, extracted the same way
 * {@link PickMapper} was and for the same reason: the poller needs the identical
 * inversion and a copy-paste would have been a second implementation to keep in
 * step.
 *
 * Sleeper's {@code draft_order} maps sleeper user id -> draft slot, and it is
 * {@code null} until the commissioner sets the order (verified live against
 * West Coast FF 2026 while it was still pre_draft). Everything downstream wants
 * the inverse — slot -> manager.id — so that is what this produces.
 *
 * The slot key stays a String. That is what {@code JsonUtil.write} persists into
 * {@code draft.slot_to_manager} today, and all three consumers
 * ({@code LeagueController.seats}, {@code SimulationService.seatsOf},
 * {@link LiveDraftPoller}) parse it back with {@code Integer.parseInt}. Changing
 * the key type here would silently change the persisted JSON shape.
 */
public final class DraftOrderMapper {

    private DraftOrderMapper() {}

    /**
     * slot -> manager.id, in draft_order's own iteration order. A user id with no
     * {@code manager} row is dropped rather than guessed at — see
     * {@link #unmappedUserIds} for surfacing that instead of swallowing it.
     */
    public static Map<String, Long> slotToManager(Map<String, Object> rawDraft,
                                                  Map<String, Long> managerByUserId) {
        Map<String, Long> out = new LinkedHashMap<>();
        asMap(rawDraft == null ? null : rawDraft.get("draft_order")).forEach((userId, slot) -> {
            Long managerId = managerByUserId.get(userId);
            if (managerId != null) {
                out.put(String.valueOf(asInt(slot, 0)), managerId);
            }
        });
        return out;
    }

    /** The same map keyed on an int slot, which {@link PickMapper} takes. */
    public static Map<Integer, Long> slotLookup(Map<String, Long> slotToManager) {
        Map<Integer, Long> out = new LinkedHashMap<>();
        slotToManager.forEach((slot, managerId) -> out.put(Integer.parseInt(slot), managerId));
        return out;
    }

    /**
     * Sleeper user ids in draft_order that have no manager row, so a caller can log
     * them. An unmapped user is a silently missing seat: that seat's picks land with
     * manager_id null, get filtered out of allCompletedPicks, and the seat simulates
     * as a league-average bot with nothing anywhere saying so.
     */
    public static List<String> unmappedUserIds(Map<String, Object> rawDraft,
                                               Map<String, Long> managerByUserId) {
        List<String> out = new ArrayList<>();
        asMap(rawDraft == null ? null : rawDraft.get("draft_order")).forEach((userId, slot) -> {
            if (!managerByUserId.containsKey(userId)) out.add(userId);
        });
        return out;
    }

    /**
     * Normalizes a slot map read back out of the DB (Jackson hands back
     * {@code Integer} values) to the {@code Long} this class produces, so the two
     * are actually comparable. {@code Integer.valueOf(5).equals(5L)} is false, and
     * a change-detector built on the raw maps would therefore fire forever.
     */
    public static Map<String, Long> normalize(Map<String, Object> stored) {
        Map<String, Long> out = new LinkedHashMap<>();
        if (stored != null) {
            stored.forEach((slot, managerId) -> {
                if (managerId instanceof Number n) out.put(slot, n.longValue());
            });
        }
        return out;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> asMap(Object o) {
        return o instanceof Map<?, ?> m ? (Map<String, Object>) m : Map.of();
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
