package com.ballknowers.draftsim.store;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.math.BigDecimal;
import java.util.Collection;
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
                      String playersPointsJson, String startersJson) {

        /** Callers with no starters list to store (pre-V18 shape). */
        public Row(long leagueId, int season, int week, int rosterId, double startersPoints,
                   String playersPointsJson) {
            this(leagueId, season, week, rosterId, startersPoints, playersPointsJson, null);
        }
    }

    public void upsert(Row r) {
        db.sql("""
                insert into roster_week_points (league_id, season, week, roster_id, starters_points,
                                                players_points, starters)
                values (?, ?, ?, ?, ?, ?::jsonb, ?::jsonb)
                on conflict (league_id, week, roster_id) do update set
                    season = excluded.season,
                    starters_points = excluded.starters_points,
                    players_points = excluded.players_points,
                    -- coalesce, not a plain overwrite: a re-ingest that cannot
                    -- see the starters (Sleeper no longer serving that week)
                    -- must not erase a list an earlier run did capture.
                    starters = coalesce(excluded.starters, roster_week_points.starters)
                """)
                .params(r.leagueId(), r.season(), r.week(), r.rosterId(), r.startersPoints(),
                        r.playersPointsJson(), r.startersJson())
                .update();
    }

    /**
     * Weeks whose starters list is populated for EVERY stored roster.
     *
     * <p>The ingest skip gate needs this because it is otherwise keyed on rows
     * existing rather than on columns being filled: adding a per-week column
     * and re-running ingest would skip every settled week and leave the column
     * null forever. league_matchup hit exactly that on 2026-09-14
     * (specs/004-ffwrapped-feature-parity research R6), and the fix there was
     * the same shape -- widen the gate to ask about the new thing too.
     *
     * <p>"every stored roster", not "any": one roster with starters does not
     * make the week done.
     */
    public Set<Integer> weeksWithStarters(long leagueId) {
        return db.sql("""
                select week from roster_week_points
                where league_id = ?
                group by week
                having count(*) filter (where starters is not null) = count(*)
                """)
                .param(leagueId)
                .query(Integer.class)
                .list()
                .stream().collect(Collectors.toSet());
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

    /**
     * One roster's scored week WITH its per-player breakdown.
     *
     * <p>{@code playersPointsJson} is Sleeper's {@code players_points} as
     * stored: {@code sleeper_player_id -> points} for every player on the
     * roster that week, bench included. It is the input to every
     * backward-looking view in specs/004-ffwrapped-feature-parity -- potential
     * points, weekly awards and top performers all read it, and none of them
     * needs a new ingest because V5 has been storing it since the beginning.
     *
     * <p>Can be {@code "{}"} for a week Sleeper returned no breakdown for.
     * Callers must treat that as "no answer for this week" rather than zero
     * (FR-007); {@code RealizedLineupService} does.
     */
    public record WeekBreakdown(int week, int rosterId, double startersPoints, String playersPointsJson,
                                String startersJson) {}

    /**
     * Every stored week of one league-season, with per-player points.
     *
     * <p>One query for the whole page: a roster-management view reads every
     * roster's every week, and doing that per roster would be a dozen round
     * trips for a page that is already fully cached in this table.
     */
    public List<WeekBreakdown> breakdownsFor(long leagueId, int season) {
        return db.sql("""
                select week, roster_id, starters_points, players_points::text, starters::text
                from roster_week_points
                where league_id = ? and season = ?
                order by roster_id, week
                """)
                .params(leagueId, season)
                .query((rs, i) -> new WeekBreakdown(rs.getInt(1), rs.getInt(2), rs.getDouble(3),
                        rs.getString(4), rs.getString(5)))
                .list();
    }

    /**
     * One roster's scored week, with the manager who owned that roster THAT
     * season. specs/002-league-history-record-book.
     *
     * <p>manager fields are null for an unowned roster-season, which Sleeper
     * produces when someone leaves mid-season. rosterId is never null, so a
     * caller always has something to render (data-model R1).
     */
    public record ScoreRow(int season, int week, int rosterId, Long managerId, String manager,
                           String avatarId, BigDecimal points) {}

    /**
     * The top or bottom {@code limit} weekly scores across a SET of league ids.
     *
     * <p>A set, not one id: league.id identifies a league-SEASON in this schema
     * (Sleeper mints a new id each year and links it through
     * previous_league_id), so the chain is the unit of "league history". One id
     * answers "this season".
     *
     * <p>Ordered by points, then by (season, week, roster_id) so repeat calls
     * agree. Without the tiebreak, Postgres is free to return ties in any order
     * and the page reshuffles on reload -- and at the boundary of the limit,
     * which row is shown would change between loads (data-model R2).
     *
     * <p>LEFT JOIN to roster_season and manager on purpose: an unowned
     * roster-season still produced the score, and dropping it would silently
     * edit the record book.
     */
    public List<ScoreRow> extremes(Collection<Long> leagueIds, boolean highest, int limit) {
        if (leagueIds == null || leagueIds.isEmpty()) return List.of();
        String direction = highest ? "desc" : "asc";
        String sql = """
                select w.season, w.week, w.roster_id, rs.manager_id, m.display_name, m.avatar_id, w.starters_points
                from roster_week_points w
                left join roster_season rs on rs.league_id = w.league_id and rs.roster_id = w.roster_id
                left join manager m on m.id = rs.manager_id
                where w.league_id in (%s)
                order by w.starters_points %s, w.season desc, w.week asc, w.roster_id asc
                limit %d
                """.formatted(inClause(leagueIds), direction, Math.max(0, limit));
        return db.sql(sql)
                .params(List.copyOf(leagueIds))
                .query((rs, i) -> new ScoreRow(rs.getInt(1), rs.getInt(2), rs.getInt(3),
                        (Long) rs.getObject(4), rs.getString(5), rs.getString(6), rs.getBigDecimal(7)))
                .list();
    }

    /** {@code ?, ?, ?} for an IN list -- the ids are still bound, never inlined. */
    static String inClause(Collection<Long> ids) {
        return String.join(", ", java.util.Collections.nCopies(ids.size(), "?"));
    }
}
