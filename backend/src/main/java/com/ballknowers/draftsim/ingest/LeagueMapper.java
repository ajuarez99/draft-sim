package com.ballknowers.draftsim.ingest;

import com.ballknowers.draftsim.domain.Sport;
import com.ballknowers.draftsim.store.JsonUtil;
import com.ballknowers.draftsim.store.LeagueRepository;

import java.util.List;
import java.util.Map;

/**
 * The league-object-to-league-row mapping, shared by {@link LeagueIngestService}
 * (which discovers leagues while walking drafts) and {@link LeagueHistoryIngestService}
 * (which walks the same chain for standings/power-rankings and needs the same
 * league row to exist, independent of whether a draft has ever been ingested for it).
 */
final class LeagueMapper {

    private LeagueMapper() {}

    @SuppressWarnings("unchecked")
    static long upsert(LeagueRepository leagues, Sport sport, Map<String, Object> league) {
        List<String> rosterPositions = (List<String>) league.getOrDefault("roster_positions", List.of());
        return leagues.upsert(
                sport,
                Integer.parseInt(String.valueOf(league.get("season"))),
                String.valueOf(league.get("league_id")),
                league.get("previous_league_id") == null ? null : String.valueOf(league.get("previous_league_id")),
                league.get("name") == null ? null : String.valueOf(league.get("name")),
                asInt(league.get("total_rosters"), 0),
                JsonUtil.write(league.getOrDefault("settings", Map.of())),
                JsonUtil.write(league.getOrDefault("scoring_settings", Map.of())),
                rosterPositions);
    }

    static int asInt(Object o, int fallback) {
        if (o == null) return fallback;
        if (o instanceof Number n) return n.intValue();
        try {
            return Integer.parseInt(o.toString());
        } catch (NumberFormatException e) {
            return fallback;
        }
    }
}
