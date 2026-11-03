package com.ballknowers.draftsim.store;

import org.springframework.jdbc.core.RowCallbackHandler;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.math.BigDecimal;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

/**
 * Sleeper's official season totals, kept once per scored leg (claude/official-week-scores.md, V44). The
 * difference between two consecutive legs is that week's official score; the public /matchups feed is not
 * (claude/nba-2026-score-drift.md).
 */
@Repository
public class RosterFptsSnapshotRepository {

    public record Snapshot(int rosterId, BigDecimal fpts, BigDecimal fptsAgainst) {}

    private final JdbcClient db;

    public RosterFptsSnapshotRepository(JdbcClient db) {
        this.db = db;
    }

    /** Upsert: while the leg is current, the latest read replaces earlier ones (a correction heals). */
    public void upsertAll(long leagueId, int throughWeek, List<Snapshot> rows) {
        for (Snapshot r : rows) {
            db.sql("""
                    insert into roster_fpts_snapshot (league_id, roster_id, through_week, fpts, fpts_against)
                    values (?, ?, ?, ?, ?)
                    on conflict (league_id, roster_id, through_week) do update set
                        fpts = excluded.fpts,
                        fpts_against = excluded.fpts_against,
                        captured_at = now()
                    """)
                    .params(leagueId, r.rosterId(), throughWeek, r.fpts(), r.fptsAgainst())
                    .update();
        }
    }

    /** week -> rosterId -> fpts through that week. */
    public Map<Integer, Map<Integer, BigDecimal>> byWeek(long leagueId) {
        Map<Integer, Map<Integer, BigDecimal>> out = new TreeMap<>();
        db.sql("select through_week, roster_id, fpts from roster_fpts_snapshot where league_id = ?")
                .param(leagueId)
                .query((RowCallbackHandler) rs ->
                        out.computeIfAbsent(rs.getInt(1), w -> new HashMap<>()).put(rs.getInt(2), rs.getBigDecimal(3)));
        return out;
    }

    /**
     * Official week scores: week N = fpts(N) - fpts(N-1), with fpts(0) = 0. A week is returned only when every
     * roster in {@code rosterIds} has both snapshots; anything less is left to the feed, never half-filled.
     */
    public static Map<Integer, Map<Integer, BigDecimal>> officialWeekScores(
            Map<Integer, Map<Integer, BigDecimal>> byWeek, Set<Integer> rosterIds) {
        Map<Integer, Map<Integer, BigDecimal>> out = new TreeMap<>();
        if (rosterIds.isEmpty()) return out;
        for (Map.Entry<Integer, Map<Integer, BigDecimal>> e : byWeek.entrySet()) {
            int week = e.getKey();
            Map<Integer, BigDecimal> now = e.getValue();
            Map<Integer, BigDecimal> before = week == 1 ? null : byWeek.get(week - 1);
            if (week > 1 && before == null) continue;
            if (!now.keySet().containsAll(rosterIds)) continue;
            if (before != null && !before.keySet().containsAll(rosterIds)) continue;
            Map<Integer, BigDecimal> scores = new HashMap<>();
            for (int id : new HashSet<>(rosterIds)) {
                scores.put(id, before == null ? now.get(id) : now.get(id).subtract(before.get(id)));
            }
            out.put(week, scores);
        }
        return out;
    }
}
