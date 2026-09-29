package com.ballknowers.draftsim.store;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.Optional;

/**
 * Refresh state per league-season (specs/009-auto-data-refresh, research R3/R5,
 * {@code V24__data_refresh.sql}). Written at the end of each refresh attempt only.
 */
@Repository
public class LeagueRefreshRepository {

    private final JdbcClient db;

    public LeagueRefreshRepository(JdbcClient db) {
        this.db = db;
    }

    public record Row(long leagueId, Instant lastSuccessAt, Instant lastFailureAt, String lastFailure,
                      boolean loadedComplete) {}

    public Optional<Row> find(long leagueId) {
        return db.sql("""
                select league_id, last_success_at, last_failure_at, last_failure, loaded_complete
                from league_refresh where league_id = ?
                """)
                .params(leagueId)
                .query((rs, n) -> {
                    Timestamp ok = rs.getTimestamp("last_success_at");
                    Timestamp bad = rs.getTimestamp("last_failure_at");
                    return new Row(rs.getLong("league_id"),
                            ok == null ? null : ok.toInstant(),
                            bad == null ? null : bad.toInstant(),
                            rs.getString("last_failure"),
                            rs.getBoolean("loaded_complete"));
                })
                .optional();
    }

    /**
     * Never flips {@code loaded_complete} from true back to false: once a season
     * is fully loaded nothing fetches it again (FR-011), so a later success that
     * happened to pass {@code false} must not undo that.
     */
    public void recordSuccess(long leagueId, Instant at, boolean loadedComplete) {
        db.sql("""
                insert into league_refresh (league_id, last_success_at, loaded_complete)
                values (?, ?, ?)
                on conflict (league_id) do update set
                    last_success_at = excluded.last_success_at,
                    loaded_complete = league_refresh.loaded_complete or excluded.loaded_complete
                """)
                .params(leagueId, Timestamp.from(at), loadedComplete)
                .update();
    }

    public void recordFailure(long leagueId, Instant at, String reason) {
        db.sql("""
                insert into league_refresh (league_id, last_failure_at, last_failure)
                values (?, ?, ?)
                on conflict (league_id) do update set
                    last_failure_at = excluded.last_failure_at,
                    last_failure = excluded.last_failure
                """)
                .params(leagueId, Timestamp.from(at), reason)
                .update();
    }
}
