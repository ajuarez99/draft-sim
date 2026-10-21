package com.ballknowers.draftsim.store;

import com.ballknowers.draftsim.domain.Sport;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.sql.Timestamp;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/**
 * "We looked at suspension tags during this week" and the players tagged
 * suspended when we looked (specs/008-season-superlatives, data-model.md,
 * research R11). Written by {@code PlayerIngestService.ingest(sport)} on
 * every successful player ingest, T052.
 */
@Repository
public class StatusCaptureRepository {

    private final JdbcClient db;
    private final JdbcTemplate jdbc;

    public StatusCaptureRepository(JdbcClient db, JdbcTemplate jdbc) {
        this.db = db;
        this.jdbc = jdbc;
    }

    /** Upsert on (sport, season, week): the latest capture that week wins (data-model.md). */
    public void recordCapture(Sport sport, int season, int week, Instant at) {
        db.sql("""
                insert into status_capture (sport, season, week, captured_at)
                values (?, ?, ?, ?)
                on conflict (sport, season, week) do update set captured_at = excluded.captured_at
                """)
                .params(sport.code(), season, week, Timestamp.from(at))
                .update();
    }

    /**
     * Replaces the full suspended set for this captured week -- a fresh look
     * each ingest run, not an accumulation. A player no longer tagged
     * suspended must stop appearing here for a week that keeps getting
     * re-observed; {@code player_suspension} is "who was tagged the last time
     * we looked at this week", never a running union.
     *
     * <p>Requires a {@link #recordCapture} for the same (sport, season, week)
     * to already exist -- {@code player_suspension}'s FK depends on it
     * (V22), so callers record the capture first.
     */
    public void recordSuspended(Sport sport, int season, int week, Collection<String> playerIds) {
        db.sql("delete from player_suspension where sport = ? and season = ? and week = ?")
                .params(sport.code(), season, week)
                .update();
        if (playerIds == null || playerIds.isEmpty()) return;
        List<String> ids = new ArrayList<>(playerIds);
        jdbc.batchUpdate(
                "insert into player_suspension (sport, season, week, sleeper_player_id) values (?, ?, ?, ?) "
                        + "on conflict (sport, season, week, sleeper_player_id) do nothing",
                ids, ids.size(),
                (ps, id) -> {
                    ps.setString(1, sport.code());
                    ps.setInt(2, season);
                    ps.setInt(3, week);
                    ps.setString(4, id);
                });
    }

    /**
     * When this sport's player statuses (injury_status, status) were last refreshed, or empty when that
     * isn't known (specs/042 T004, review F2).
     *
     * <p>The greatest of the season's {@code status_capture.captured_at} (max across weeks) and the
     * daily job's {@code PLAYERS} {@code completed_at}. Neither alone is right: {@code status_capture} is
     * not written outside {@code season_type == regular} or when the state call fails, although the
     * player rows are rewritten regardless, and {@code daily_capture} is not written by a manual
     * {@code /api/ingest/players}. Empty means <i>unknown</i>, never "fresh"; the caller must say so.
     */
    public Optional<Instant> statusAsOf(Sport sport, int season) {
        return statusAsOf(sport.code(), season);
    }

    /** The code-keyed form, so the IT can isolate a throwaway sport key. */
    Optional<Instant> statusAsOf(String sportCode, int season) {
        return db.sql("""
                select greatest(
                    (select max(captured_at) from status_capture where sport = ? and season = ?),
                    (select max(completed_at) from daily_capture where sport = ? and kind = 'PLAYERS'))
                """)
                .params(sportCode, season, sportCode)
                .query((rs, i) -> rs.getObject(1, OffsetDateTime.class))
                .list().stream().filter(Objects::nonNull).findFirst()
                .map(OffsetDateTime::toInstant);
    }

    /** Every week this sport/season has a status_capture row for -- FR-019's "we actually looked" record. */
    public Set<Integer> capturedWeeks(Sport sport, int season) {
        return Set.copyOf(db.sql("select week from status_capture where sport = ? and season = ?")
                .params(sport.code(), season)
                .query(Integer.class)
                .list());
    }

    /** The suspended-player-id set per week, for every week this sport/season has captured rows. */
    public Map<Integer, Set<String>> suspendedIn(Sport sport, int season) {
        Map<Integer, Set<String>> out = new HashMap<>();
        db.sql("select week, sleeper_player_id from player_suspension where sport = ? and season = ?")
                .params(sport.code(), season)
                .query((rs, i) -> Map.entry(rs.getInt(1), rs.getString(2)))
                .list()
                .forEach(e -> out.computeIfAbsent(e.getKey(), k -> new HashSet<>()).add(e.getValue()));
        return out;
    }
}
