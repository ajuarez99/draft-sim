package com.ballknowers.draftsim.store;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Commissioner-entered team-week scores (spec 057, V47). Read by {@link WeekScoreResolver}. */
@Repository
public class WeekScoreOverrideRepository {

    public record Row(long leagueId, int week, int rosterId, BigDecimal points, Long enteredBy,
                      OffsetDateTime enteredAt) {}

    private final JdbcClient db;

    public WeekScoreOverrideRepository(JdbcClient db) {
        this.db = db;
    }

    public List<Row> forWeek(long leagueId, int week) {
        return db.sql("""
                select league_id, week, roster_id, points, entered_by, entered_at
                from week_score_override where league_id = ? and week = ? order by roster_id
                """)
                .params(leagueId, week)
                .query((rs, i) -> new Row(rs.getLong(1), rs.getInt(2), rs.getInt(3), rs.getBigDecimal(4),
                        (Long) rs.getObject(5), rs.getObject(6, OffsetDateTime.class)))
                .list();
    }

    /** Every override row for the given league-seasons. */
    public List<Row> byLeagues(Collection<Long> leagueIds) {
        if (leagueIds == null || leagueIds.isEmpty()) return List.of();
        return db.sql("""
                select league_id, week, roster_id, points, entered_by, entered_at
                from week_score_override where league_id in (%s) order by league_id, week, roster_id
                """.formatted(RosterWeekPointsRepository.inClause(leagueIds)))
                .params(List.copyOf(leagueIds))
                .query((rs, i) -> new Row(rs.getLong(1), rs.getInt(2), rs.getInt(3), rs.getBigDecimal(4),
                        (Long) rs.getObject(5), rs.getObject(6, OffsetDateTime.class)))
                .list();
    }

    /** Replaces the whole week: delete then insert, one transaction, so a week never holds a mix of two saves. */
    @Transactional
    public void replaceWeek(long leagueId, int week, Map<Integer, BigDecimal> pointsByRoster, Long enteredBy) {
        db.sql("delete from week_score_override where league_id = ? and week = ?").params(leagueId, week).update();
        for (Map.Entry<Integer, BigDecimal> e : new LinkedHashMap<>(pointsByRoster).entrySet()) {
            db.sql("""
                    insert into week_score_override (league_id, week, roster_id, points, entered_by)
                    values (?, ?, ?, ?, ?)
                    on conflict (league_id, week, roster_id)
                    do update set points = excluded.points, entered_by = excluded.entered_by, entered_at = now()
                    """)
                    .params(leagueId, week, e.getKey(), e.getValue(), enteredBy)
                    .update();
        }
    }

    public int clearWeek(long leagueId, int week) {
        return db.sql("delete from week_score_override where league_id = ? and week = ?")
                .params(leagueId, week).update();
    }
}
