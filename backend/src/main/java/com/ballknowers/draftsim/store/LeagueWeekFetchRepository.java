package com.ballknowers.draftsim.store;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.HashSet;
import java.util.Set;

/**
 * When each league week was fetched and whether it is final
 * (specs/009-auto-data-refresh, FR-016, research R14, {@code league_week_fetch}
 * in {@code V24__data_refresh.sql}). Replaces the "has rows" skip in the
 * transaction and weekly-points walks: a week with no row here is fetched,
 * which is what heals weeks stored before this table existed.
 */
@Repository
public class LeagueWeekFetchRepository {

    /** {@code league_week_fetch.kind} for the transaction walk. */
    public static final String TRANSACTIONS = "TRANSACTIONS";
    /** {@code league_week_fetch.kind} for the weekly points/pairings walk. */
    public static final String POINTS = "POINTS";

    private final JdbcClient db;

    public LeagueWeekFetchRepository(JdbcClient db) {
        this.db = db;
    }

    public Set<Integer> finalWeeks(long leagueId, String kind) {
        return new HashSet<>(db.sql("""
                select week from league_week_fetch
                where league_id = ? and kind = ? and final
                """)
                .params(leagueId, kind)
                .query(Integer.class)
                .list());
    }

    /** Whether any week of this kind has ever been recorded for the league. */
    public boolean hasAny(long leagueId, String kind) {
        return Boolean.TRUE.equals(db.sql("select exists (select 1 from league_week_fetch where league_id = ? and kind = ?)")
                .params(leagueId, kind)
                .query(Boolean.class)
                .single());
    }

    /** Never flips {@code final} back to false. */
    public void record(long leagueId, String kind, int week, Instant fetchedAt, boolean fin) {
        db.sql("""
                insert into league_week_fetch (league_id, kind, week, fetched_at, final)
                values (?, ?, ?, ?, ?)
                on conflict (league_id, kind, week) do update set
                    fetched_at = excluded.fetched_at,
                    final = league_week_fetch.final or excluded.final
                """)
                .params(leagueId, kind, week, Timestamp.from(fetchedAt), fin)
                .update();
    }
}
