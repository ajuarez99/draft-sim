package com.ballknowers.draftsim.ingest;

import com.ballknowers.draftsim.domain.Sport;
import com.ballknowers.draftsim.store.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.*;

/**
 * Walks previous_league_id backwards from a current league and ingests every
 * season, its managers, its drafts and every pick.
 *
 * Managers are keyed on Sleeper user id. Display names are stored but never
 * used as an identity — people rename their teams every season.
 */
@Service
public class LeagueIngestService {

    private static final Logger log = LoggerFactory.getLogger(LeagueIngestService.class);

    private final SleeperClient sleeper;
    private final LeagueRepository leagues;
    private final ManagerRepository managers;
    private final DraftRepository drafts;
    private final PlayerRepository players;

    public LeagueIngestService(SleeperClient sleeper, LeagueRepository leagues,
                               ManagerRepository managers, DraftRepository drafts,
                               PlayerRepository players) {
        this.sleeper = sleeper;
        this.leagues = leagues;
        this.managers = managers;
        this.drafts = drafts;
        this.players = players;
    }

    public record Result(int seasons, int draftsIngested, int picksIngested) {}

    @Transactional
    public Result ingestChain(Sport sport, String currentLeagueId) {
        Map<String, Long> playerIds = players.idsBySleeperId(sport);
        if (playerIds.isEmpty()) {
            throw new IllegalStateException("player table is empty — ingest players before leagues");
        }

        int seasons = 0, draftCount = 0, pickCount = 0;

        for (Map<String, Object> league : sleeper.leagueChain(currentLeagueId)) {
            seasons++;
            long leagueId = upsertLeague(sport, league);
            String sleeperLeagueId = str(league.get("league_id"));

            Map<String, Long> managerByUserId = upsertManagers(sleeperLeagueId);

            for (Map<String, Object> d : sleeper.drafts(sleeperLeagueId)) {
                draftCount++;
                pickCount += ingestDraft(sport, leagueId, d, managerByUserId, playerIds);
            }
        }

        log.info("ingested {} seasons, {} drafts, {} picks", seasons, draftCount, pickCount);
        return new Result(seasons, draftCount, pickCount);
    }

    private long upsertLeague(Sport sport, Map<String, Object> league) {
        @SuppressWarnings("unchecked")
        List<String> rosterPositions = (List<String>) league.getOrDefault("roster_positions", List.of());
        return leagues.upsert(
                sport,
                Integer.parseInt(str(league.get("season"))),
                str(league.get("league_id")),
                str(league.get("previous_league_id")),
                str(league.get("name")),
                asInt(league.get("total_rosters"), 0),
                JsonUtil.write(league.getOrDefault("settings", Map.of())),
                JsonUtil.write(league.getOrDefault("scoring_settings", Map.of())),
                rosterPositions);
    }

    private Map<String, Long> upsertManagers(String sleeperLeagueId) {
        Map<String, Long> out = new HashMap<>();
        for (Map<String, Object> u : sleeper.leagueUsers(sleeperLeagueId)) {
            String userId = str(u.get("user_id"));
            String display = str(u.get("display_name"));
            out.put(userId, managers.upsert(userId, display));
        }
        return out;
    }

    private int ingestDraft(Sport sport, long leagueId, Map<String, Object> draft,
                            Map<String, Long> managerByUserId, Map<String, Long> playerIds) {

        String draftId = str(draft.get("draft_id"));
        Map<String, Object> settings = asMap(draft.get("settings"));
        int rounds = asInt(settings.get("rounds"), 15);
        int teams = asInt(settings.get("teams"), 12);

        // draft_order maps sleeper user id -> slot. Invert it to slot -> manager.id.
        Map<String, Object> draftOrder = asMap(draft.get("draft_order"));
        Map<String, Long> slotToManager = new LinkedHashMap<>();
        Map<Integer, Long> slotLookup = new HashMap<>();
        draftOrder.forEach((userId, slot) -> {
            Long managerId = managerByUserId.get(userId);
            if (managerId != null) {
                int s = asInt(slot, 0);
                slotToManager.put(String.valueOf(s), managerId);
                slotLookup.put(s, managerId);
            }
        });

        Instant start = draft.get("start_time") == null
                ? null : Instant.ofEpochMilli(((Number) draft.get("start_time")).longValue());

        long id = drafts.upsert(leagueId, draftId,
                Integer.parseInt(str(draft.get("season"))), rounds, teams,
                str(draft.get("type")), str(draft.get("status")), start,
                JsonUtil.write(slotToManager));

        List<Map<String, Object>> raw = sleeper.draftPicks(draftId);
        if (raw == null || raw.isEmpty()) return 0;

        List<DraftRepository.PickRow> rows = new ArrayList<>(raw.size());
        for (Map<String, Object> p : raw) {
            rows.add(PickMapper.toPickRow(id, p, managerByUserId, slotLookup, playerIds));
        }
        drafts.replacePicks(id, rows);
        return rows.size();
    }

    private static String str(Object o) {
        return o == null ? null : o.toString();
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
