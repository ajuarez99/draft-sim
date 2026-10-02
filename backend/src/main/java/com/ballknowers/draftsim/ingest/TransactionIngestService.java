package com.ballknowers.draftsim.ingest;

import com.ballknowers.draftsim.refresh.WeekFinality;
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
 *
 * <p><b>The skip gate is week finality, not "has rows"</b>
 * (specs/009-auto-data-refresh FR-016, research R14). A week is skipped only
 * once it is recorded in {@code league_week_fetch} as {@code final}: fetched
 * while {@code last_scored_leg} was already past it, or while the league was
 * {@code complete}. A week stored while it was still the last scored leg (NBA
 * scores during the week) is partial, and the old "has rows and is not the
 * last leg" gate froze it that way forever -- production NBA 2025 held 24 of
 * 53 moves for week 10 and none for weeks 11-21. A week with no fetch record
 * is fetched, which heals weeks stored before the record existed.
 *
 * <p><b>Bounded by {@code max(last_scored_leg, leg)}, not {@code last_scored_leg}</b>
 * (2026-10-02). In NFL {@code last_scored_leg} is the last <i>completed</i> week, so
 * the week being played -- whose free-agent moves Sleeper already lists (measured: 8
 * on (Foot) Ball Knowers 2026 week 4 on its Friday) -- was not stored until it
 * finished. {@code leg} is the week being played and never runs ahead of it; unlike
 * the results walk, an unplayed week's transactions come back empty, not as
 * real-looking rows. That week is never final, so it is refetched every refresh.
 */
@Service
public class TransactionIngestService {

    private static final Logger log = LoggerFactory.getLogger(TransactionIngestService.class);

    private final SleeperClient sleeper;
    private final LeagueRepository leagues;
    private final LeagueTransactionRepository transactions;
    private final ManagerRepository managers;
    private final RosterSeasonRepository rosterSeasons;
    private final LeagueWeekFetchRepository weekFetches;

    public TransactionIngestService(SleeperClient sleeper, LeagueRepository leagues,
                                    LeagueTransactionRepository transactions,
                                    ManagerRepository managers,
                                    RosterSeasonRepository rosterSeasons,
                                    LeagueWeekFetchRepository weekFetches) {
        this.sleeper = sleeper;
        this.leagues = leagues;
        this.transactions = transactions;
        this.rosterSeasons = rosterSeasons;
        this.managers = managers;
        this.weekFetches = weekFetches;
    }

    /** @return how many transactions were stored */
    public int ingest(String sleeperLeagueId) {
        Optional<LeagueRepository.LeagueRow> found = leagues.bySleeperId(sleeperLeagueId);
        if (found.isEmpty()) return 0;
        LeagueRepository.LeagueRow league = found.get();

        Map<String, Object> raw = sleeper.league(sleeperLeagueId);
        Map<String, Object> settings = asMap(raw == null ? null : raw.get("settings"));
        int lastScoredLeg = asInt(settings.get("last_scored_leg"), 0);
        int leg = asInt(settings.get("leg"), 0);
        int lastWeek = Math.max(lastScoredLeg, leg);
        if (lastWeek < 1) return 0;

        Map<Integer, Long> managerByRoster = new HashMap<>();
        for (RosterSeasonRepository.StandingRow s : rosterSeasons.forLeague(league.id())) {
            if (s.managerId() != null) managerByRoster.put(s.rosterId(), s.managerId());
        }

        Set<Integer> finalWeeks = weekFetches.finalWeeks(league.id(), LeagueWeekFetchRepository.TRANSACTIONS);
        int count = 0;
        for (int week = 1; week <= lastWeek; week++) {
            if (WeekFinality.mayStopRefetching(finalWeeks.contains(week), week, lastScoredLeg)) continue;
            List<Map<String, Object>> payload = sleeper.transactions(sleeperLeagueId, week);
            if (payload == null) continue;
            for (Map<String, Object> t : payload) {
                LeagueTransactionRepository.Row row = toRow(league, week, t, managerByRoster);
                if (row == null) continue;
                transactions.upsert(row);
                count++;
            }
            weekFetches.record(league.id(), LeagueWeekFetchRepository.TRANSACTIONS, week, Instant.now(),
                    WeekFinality.isFinal(week, lastScoredLeg, leg));
        }
        log.info("transactions: league {} season {} -- {} stored through week {}",
                league.id(), league.season(), count, lastWeek);
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
