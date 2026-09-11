package com.ballknowers.draftsim.ingest;

import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * roster_id -> manager.id off {@code sleeper.rosters()}'s own {@code owner_id},
 * read live rather than off {@code roster_season} (which needs league-history
 * ingest to exist at all). {@link com.ballknowers.draftsim.engine.PowerRankingService#computeWeek0IfMissing}
 * and {@code LeagueHistoryIngestService.ingestStandings} each already inline
 * this same three-line lookup; this is the version the ballot feature's new
 * code (member ballots, the ballot endpoint's own roster-id validation) shares
 * rather than adding a third or fourth copy of it -- claude/power-rankings-ballots.md's
 * whole "the read already happens twice, don't write a third" argument applies
 * exactly as much to this join as to {@code leagueUsers()} itself.
 */
public final class RosterOwnerMapper {

    private RosterOwnerMapper() {}

    /** A manager missing from {@code managerBySleeperUserId} (no ingested manager row yet) is dropped, not guessed at. */
    public static Map<Integer, Long> rosterToManager(List<Map<String, Object>> rosters,
                                                      Map<String, Long> managerBySleeperUserId) {
        Map<Integer, Long> out = new LinkedHashMap<>();
        for (Map<String, Object> roster : rosters) {
            int rosterId = asInt(roster.get("roster_id"), -1);
            if (rosterId < 0) continue;
            Object ownerId = roster.get("owner_id");
            Long managerId = ownerId == null ? null : managerBySleeperUserId.get(String.valueOf(ownerId));
            if (managerId != null) out.put(rosterId, managerId);
        }
        return out;
    }

    /** Every roster id this league currently has, Sleeper-side -- the set a submitted ballot must match exactly. */
    public static Set<Integer> rosterIds(List<Map<String, Object>> rosters) {
        Set<Integer> out = new LinkedHashSet<>();
        for (Map<String, Object> roster : rosters) {
            int rosterId = asInt(roster.get("roster_id"), -1);
            if (rosterId >= 0) out.add(rosterId);
        }
        return out;
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
