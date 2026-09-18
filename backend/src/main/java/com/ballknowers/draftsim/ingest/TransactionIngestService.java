package com.ballknowers.draftsim.ingest;

import com.ballknowers.draftsim.store.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.*;

/**
 * Stores a league's roster moves (specs/004-ffwrapped-feature-parity, US6).
 *
 * <p>{@link SleeperClient#transactions} has existed since the client was
 * written and had zero callers -- the method was there, nothing kept what it
 * returned. This is the caller.
 *
 * <p>Walks weeks 1..{@code last_scored_leg} the way
 * {@link LeagueHistoryIngestService} walks results, with the same discipline:
 * bounded by a real league setting rather than looping until Sleeper answers
 * empty, and skipping weeks already stored. The skip gate is keyed on
 * <b>this</b> table's own weeks -- reusing the results gate would be research
 * R6's bug in a new place, since a week can have scores and no transactions.
 */
@Service
public class TransactionIngestService {

    private static final Logger log = LoggerFactory.getLogger(TransactionIngestService.class);

    private final SleeperClient sleeper;
    private final LeagueRepository leagues;
    private final LeagueTransactionRepository transactions;
    private final ManagerRepository managers;
    private final RosterSeasonRepository rosterSeasons;

    public TransactionIngestService(SleeperClient sleeper, LeagueRepository leagues,
                                    LeagueTransactionRepository transactions,
                                    ManagerRepository managers,
                                    RosterSeasonRepository rosterSeasons) {
        this.sleeper = sleeper;
        this.leagues = leagues;
        this.transactions = transactions;
        this.rosterSeasons = rosterSeasons;
        this.managers = managers;
    }

    /** @return how many transactions were stored */
    public int ingest(String sleeperLeagueId) {
        Optional<LeagueRepository.LeagueRow> found = leagues.bySleeperId(sleeperLeagueId);
        if (found.isEmpty()) return 0;
        LeagueRepository.LeagueRow league = found.get();

        Map<String, Object> raw = sleeper.league(sleeperLeagueId);
        Map<String, Object> settings = asMap(raw == null ? null : raw.get("settings"));
        int lastScoredLeg = asInt(settings.get("last_scored_leg"), 0);
        if (lastScoredLeg < 1) return 0;

        Map<Integer, Long> managerByRoster = new HashMap<>();
        for (RosterSeasonRepository.StandingRow s : rosterSeasons.forLeague(league.id())) {
            if (s.managerId() != null) managerByRoster.put(s.rosterId(), s.managerId());
        }

        Set<Integer> stored = transactions.storedWeeks(league.id(), league.season());
        int count = 0;
        for (int week = 1; week <= lastScoredLeg; week++) {
            // The most recent scored week is always refetched: a waiver run may
            // still have been settling when it was first ingested. Same rule
            // the results walk applies, for the same reason.
            if (stored.contains(week) && week != lastScoredLeg) continue;
            List<Map<String, Object>> payload = sleeper.transactions(sleeperLeagueId, week);
            if (payload == null) continue;
            for (Map<String, Object> t : payload) {
                LeagueTransactionRepository.Row row = toRow(league, week, t, managerByRoster);
                if (row == null) continue;
                transactions.upsert(row);
                count++;
            }
        }
        log.info("transactions: league {} season {} -- {} stored through week {}",
                league.id(), league.season(), count, lastScoredLeg);
        return count;
    }

    /** Null for a payload with no usable id or an unrecognised type. */
    private static LeagueTransactionRepository.Row toRow(LeagueRepository.LeagueRow league, int week,
                                                        Map<String, Object> t,
                                                        Map<Integer, Long> managerByRoster) {
        String id = t.get("transaction_id") == null ? null : String.valueOf(t.get("transaction_id"));
        if (id == null || id.isBlank()) return null;
        String type = normaliseType(String.valueOf(t.get("type")));
        if (type == null) return null;

        // roster_ids is the list of participants. A single-actor move has one;
        // a trade has two or more and gets no single acting roster.
        Integer rosterId = null;
        Object rosterIds = t.get("roster_ids");
        if (rosterIds instanceof List<?> l && l.size() == 1) {
            rosterId = asInt(l.getFirst(), -1);
            if (rosterId < 0) rosterId = null;
        }

        Map<String, Object> settings = asMap(t.get("settings"));
        Integer faab = settings.containsKey("waiver_bid") ? asIntOrNull(settings.get("waiver_bid")) : null;

        // Sleeper reports status_updated in epoch MILLIS. Reading it as seconds
        // would place every move in 1970 and make "weeks since the trade"
        // nonsense.
        Long updated = asLongOrNull(t.get("status_updated"));
        Instant createdAt = updated == null ? null : Instant.ofEpochMilli(updated);

        return new LeagueTransactionRepository.Row(
                league.id(), league.season(), week, id, type,
                t.get("status") == null ? null : String.valueOf(t.get("status")),
                rosterId,
                rosterId == null ? null : managerByRoster.get(rosterId),
                JsonUtil.write(t.get("adds") == null ? Map.of() : t.get("adds")),
                JsonUtil.write(t.get("drops") == null ? Map.of() : t.get("drops")),
                faab, createdAt);
    }

    /**
     * Sleeper's type strings to this app's checked enum. An unrecognised type
     * is dropped rather than coerced: a new Sleeper transaction kind is not a
     * waiver just because waiver is the commonest value.
     */
    static String normaliseType(String sleeperType) {
        if (sleeperType == null) return null;
        return switch (sleeperType.trim().toLowerCase()) {
            case "waiver" -> "WAIVER";
            case "free_agent" -> "FREE_AGENT";
            case "trade" -> "TRADE";
            case "commissioner" -> "COMMISSIONER";
            default -> null;
        };
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> asMap(Object o) {
        return o instanceof Map<?, ?> m ? (Map<String, Object>) m : Map.of();
    }

    private static int asInt(Object o, int fallback) {
        return o instanceof Number n ? n.intValue() : fallback;
    }

    private static Integer asIntOrNull(Object o) {
        return o instanceof Number n ? n.intValue() : null;
    }

    private static Long asLongOrNull(Object o) {
        return o instanceof Number n ? n.longValue() : null;
    }
}
