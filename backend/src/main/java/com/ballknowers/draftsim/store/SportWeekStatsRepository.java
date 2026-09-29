package com.ballknowers.draftsim.store;

import com.ballknowers.draftsim.domain.Sport;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;

/**
 * Which per-game weeks of a sport-season have been fetched, and which are final
 * (specs/009-auto-data-refresh, research R6, {@code V24__data_refresh.sql}).
 * Shared across leagues, like {@code player_game}.
 */
@Repository
public class SportWeekStatsRepository {

    private final JdbcClient db;

    public SportWeekStatsRepository(JdbcClient db) {
        this.db = db;
    }

    /** {@code fin} rather than {@code final}, which is a Java keyword. */
    public record Row(Sport sport, int season, int week, Instant fetchedAt, boolean fin) {}

    public List<Row> forSeason(Sport sport, int season) {
        return db.sql("""
                select sport, season, week, fetched_at, final
                from sport_week_stats where sport = ? and season = ? order by week
                """)
                .params(sport.code(), season)
                .query((rs, n) -> new Row(
                        Sport.fromCode(rs.getString("sport")), rs.getInt("season"), rs.getInt("week"),
                        rs.getTimestamp("fetched_at").toInstant(), rs.getBoolean("final")))
                .list();
    }

    /** Never flips {@code final} back to false. */
    public void upsert(Row r) {
        db.sql("""
                insert into sport_week_stats (sport, season, week, fetched_at, final)
                values (?, ?, ?, ?, ?)
                on conflict (sport, season, week) do update set
                    fetched_at = excluded.fetched_at,
                    final = sport_week_stats.final or excluded.final
                """)
                .params(r.sport().code(), r.season(), r.week(), Timestamp.from(r.fetchedAt()), r.fin())
                .update();
    }
}
