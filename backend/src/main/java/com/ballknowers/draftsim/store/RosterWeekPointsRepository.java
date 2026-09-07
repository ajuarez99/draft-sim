package com.ballknowers.draftsim.store;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Cached per-week matchup results (claude/plan-review-league-suite.md finding
 * 5: fetch only weeks not already stored, bounded by last_scored_leg / the
 * current week). Backs the REALIZED power-ranking mode.
 */
@Repository
public class RosterWeekPointsRepository {

    private final JdbcClient db;

    public RosterWeekPointsRepository(JdbcClient db) {
        this.db = db;
    }

    public record Row(long leagueId, int season, int week, int rosterId, double startersPoints,
                      String playersPointsJson) {}

    public void upsert(Row r) {
        db.sql("""
                insert into roster_week_points (league_id, season, week, roster_id, starters_points, players_points)
                values (?, ?, ?, ?, ?, ?::jsonb)
                on conflict (league_id, week, roster_id) do update set
                    season = excluded.season,
                    starters_points = excluded.starters_points,
                    players_points = excluded.players_points
                """)
                .params(r.leagueId(), r.season(), r.week(), r.rosterId(), r.startersPoints(), r.playersPointsJson())
                .update();
    }

    /** Weeks already cached for this league -- ingest skips these except the current week. */
    public Set<Integer> storedWeeks(long leagueId) {
        return db.sql("select distinct week from roster_week_points where league_id = ?")
                .param(leagueId)
                .query(Integer.class)
                .list()
                .stream().collect(Collectors.toSet());
    }

    public record WeekPoint(int week, int rosterId, double startersPoints) {}

    /** Every stored week for a league, through weekThrough inclusive, roster_id then week order. */
    public List<WeekPoint> through(long leagueId, int weekThrough) {
        return db.sql("""
                select week, roster_id, starters_points
                from roster_week_points
                where league_id = ? and week <= ?
                order by roster_id, week
                """)
                .params(leagueId, weekThrough)
                .query((rs, i) -> new WeekPoint(rs.getInt(1), rs.getInt(2), rs.getDouble(3)))
                .list();
    }
}
