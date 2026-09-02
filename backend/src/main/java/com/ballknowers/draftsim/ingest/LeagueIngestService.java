package com.ballknowers.draftsim.ingest;

import com.ballknowers.draftsim.domain.Sport;
import com.ballknowers.draftsim.store.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

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
    private final BoardService boards;
    private final TransactionTemplate tx;

    public LeagueIngestService(SleeperClient sleeper, LeagueRepository leagues,
                               ManagerRepository managers, DraftRepository drafts,
                               PlayerRepository players, BoardService boards,
                               PlatformTransactionManager txManager) {
        this.sleeper = sleeper;
        this.leagues = leagues;
        this.managers = managers;
        this.drafts = drafts;
        this.players = players;
        this.boards = boards;
        this.tx = new TransactionTemplate(txManager);
    }

    public record Result(int seasons, int draftsIngested, int picksIngested, int adpBackfilled) {}

    /**
     * Two transactions on purpose: the ingest, then the adp_at_time backfill.
     *
     * The backfill is a single global {@code UPDATE draft_pick} with no draft
     * filter. Run inside the ingest transaction it takes row write-locks on every
     * pick in the database -- including tonight's live draft -- and holds them
     * until commit, which is many seconds because {@code ingestChainTx} makes
     * Sleeper HTTP calls inside its own transaction. LiveDraftPoller upserts those
     * same rows every 10s in autocommit, so one {@code POST /api/ingest/all} during
     * the draft would have stalled live ingest for the length of the ingest.
     *
     * Explicitly NOT {@code REQUIRES_NEW} on the backfill: that suspends the outer
     * transaction but does not release the locks it already holds on this league's
     * freshly-written picks, so the new transaction would block on its own caller.
     * A self-deadlock is worse than the thing it was meant to fix.
     *
     * A TransactionTemplate rather than splitting the method in two, because Spring
     * self-invocation does not go through the proxy -- a private {@code @Transactional}
     * helper called from here would silently run with no transaction at all.
     */
    public Result ingestChain(Sport sport, String currentLeagueId) {
        Result ingested = tx.execute(status -> ingestChainTx(sport, currentLeagueId));
        Objects.requireNonNull(ingested, "TransactionTemplate.execute returned null");

        // Second half of HANDOFF's "Known live bug". replacePicks now coalesces
        // adp_at_time rather than nulling it, which protects picks that already
        // had one; this covers the other case -- picks that are genuinely new to
        // this ingest and have never been scored against a board. Without it a
        // league ingest still left its own fresh picks unscoreable until someone
        // remembered to POST /api/ingest/board, and nothing said so. The backfill
        // is a no-op when no board snapshot is in range, so it is safe to run on
        // every ingest.
        int backfilled = boards.backfillAdpAtTime(sport);

        log.info("ingested {} seasons, {} drafts, {} picks, backfilled adp_at_time on {}",
                ingested.seasons(), ingested.draftsIngested(), ingested.picksIngested(), backfilled);
        return new Result(ingested.seasons(), ingested.draftsIngested(), ingested.picksIngested(),
                backfilled);
    }

    private Result ingestChainTx(Sport sport, String currentLeagueId) {
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

        // adpBackfilled is filled in by the caller, after this transaction commits.
        return new Result(seasons, draftCount, pickCount, 0);
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
        // Shared with LiveDraftPoller, which has to redo this on every tick — see
        // DraftOrderMapper for why it is a helper rather than duplicated here.
        Map<String, Long> slotToManager = DraftOrderMapper.slotToManager(draft, managerByUserId);
        Map<Integer, Long> slotLookup = DraftOrderMapper.slotLookup(slotToManager);
        List<String> unmapped = DraftOrderMapper.unmappedUserIds(draft, managerByUserId);
        if (!unmapped.isEmpty()) {
            log.warn("draft {} has {} draft_order user(s) with no manager row: {}",
                    draftId, unmapped.size(), unmapped);
        }

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
