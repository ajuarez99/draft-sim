package com.ballknowers.draftsim.store;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Stored playoff-odds snapshots (claude/playoff-odds.md). One per (league,
 * season, week); re-running a week replaces that week and leaves the earlier
 * ones alone, because "62% last week" cannot be recomputed after the fact --
 * the records, the schedule and every team's scoring have all moved since.
 */
@Repository
public class PlayoffOddsRepository {

    private final JdbcClient db;

    public PlayoffOddsRepository(JdbcClient db) {
        this.db = db;
    }

    public record Entry(int rosterId, double madePct, Double byePct, Double seedOnePct,
                        double projWins, double projPoints) {}

    /** Null stays null: a bye percentage that was never computed is not 0%. */
    private static Double nullableDouble(java.math.BigDecimal v) {
        return v == null ? null : v.doubleValue();
    }

    public record Snapshot(int season, int week, int iterations, String model, List<Entry> entries) {}

    /**
     * Replaces this week's snapshot wholesale. The delete is the point: an
     * entry for a roster that has since left the league must not survive as a
     * ghost row inside a fresh snapshot.
     */
    @Transactional
    public void save(long leagueId, int season, int week, int iterations, String model, List<Entry> entries) {
        db.sql("delete from playoff_odds where league_id = ? and season = ? and week = ?")
                .params(leagueId, season, week)
                .update();
        Long oddsId = db.sql("""
                insert into playoff_odds (league_id, season, week, iterations, model)
                values (?, ?, ?, ?, ?)
                returning id
                """)
                .params(leagueId, season, week, iterations, model)
                .query(Long.class)
                .single();
        for (Entry e : entries) {
            db.sql("""
                    insert into playoff_odds_entry (odds_id, roster_id, made_pct, bye_pct, seed_one_pct,
                                                    proj_wins, proj_points)
                    values (?, ?, ?, ?, ?, ?, ?)
                    """)
                    .params(oddsId, e.rosterId(), e.madePct(), e.byePct(), e.seedOnePct(),
                            e.projWins(), e.projPoints())
                    .update();
        }
    }

    /** Removes one week's snapshot, if it has one. Entries cascade. */
    public void deleteWeek(long leagueId, int season, int week) {
        db.sql("delete from playoff_odds where league_id = ? and season = ? and week = ?")
                .params(leagueId, season, week)
                .update();
    }

    /** The snapshot for one week, or empty when that week was never computed (or was skipped on purpose). */
    public Optional<Snapshot> forWeek(long leagueId, int season, int week) {
        var head = db.sql("""
                select season, week, iterations, model from playoff_odds
                where league_id = ? and season = ? and week = ?
                """)
                .params(leagueId, season, week)
                .query((rs, i) -> new Snapshot(rs.getInt(1), rs.getInt(2), rs.getInt(3), rs.getString(4), List.of()))
                .optional();
        if (head.isEmpty()) return head;
        List<Entry> entries = db.sql("""
                select e.roster_id, e.made_pct, e.bye_pct, e.seed_one_pct, e.proj_wins, e.proj_points
                from playoff_odds_entry e
                join playoff_odds o on o.id = e.odds_id
                where o.league_id = ? and o.season = ? and o.week = ?
                order by e.made_pct desc, e.roster_id
                """)
                .params(leagueId, season, week)
                // getObject on a `numeric` column hands back a BigDecimal, not
                // a Double -- casting it blew up the whole power-rankings
                // endpoint the first time this ran against a real snapshot.
                .query((rs, i) -> new Entry(rs.getInt(1), rs.getDouble(2),
                        nullableDouble(rs.getBigDecimal(3)), nullableDouble(rs.getBigDecimal(4)),
                        rs.getDouble(5), rs.getDouble(6)))
                .list();
        Snapshot h = head.get();
        return Optional.of(new Snapshot(h.season(), h.week(), h.iterations(), h.model(), entries));
    }

    /**
     * Every stored week's made-playoffs percentages for one season, as
     * {@code week -> rosterId -> pct}. One query, because the power-rankings
     * payload renders every week's entries at once and the alternative is a
     * round trip per week on the page's hottest endpoint.
     */
    public Map<Integer, Map<Integer, Double>> madePctByWeek(long leagueId, int season) {
        Map<Integer, Map<Integer, Double>> out = new HashMap<>();
        db.sql("""
                select o.week, e.roster_id, e.made_pct
                from playoff_odds_entry e
                join playoff_odds o on o.id = e.odds_id
                where o.league_id = ? and o.season = ?
                """)
                .params(leagueId, season)
                .query((rs, i) -> new int[]{rs.getInt(1), rs.getInt(2), (int) Math.round(rs.getDouble(3) * 100)})
                .list()
                .forEach(r -> out.computeIfAbsent(r[0], w -> new HashMap<>()).put(r[1], r[2] / 100.0));
        return out;
    }

    /** The newest snapshot this league/season has, whatever week it is for. */
    public Optional<Snapshot> latest(long leagueId, int season) {
        return latestThrough(leagueId, season, Integer.MAX_VALUE);
    }

    /**
     * The newest snapshot at or before {@code week}. The power-rankings page
     * renders snapshots for weeks that may predate the odds feature entirely,
     * and a week with no odds of its own should show the most recent real
     * answer rather than a dash -- callers decide, this just finds it.
     */
    public Optional<Snapshot> latestThrough(long leagueId, int season, int week) {
        // "order by ... limit 1", not "max(week)": an aggregate over no rows
        // still returns a row, and that row's null is a second empty-shaped
        // thing to handle. This way no rows means no rows.
        Optional<Integer> w = db.sql("""
                select week from playoff_odds
                where league_id = ? and season = ? and week <= ?
                order by week desc limit 1
                """)
                .params(leagueId, season, week)
                .query(Integer.class)
                .optional();
        return w.map(latest -> forWeek(leagueId, season, latest).orElse(null))
                .map(Optional::of).orElseGet(Optional::empty);
    }
}
