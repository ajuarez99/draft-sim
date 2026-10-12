package com.ballknowers.draftsim.store;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * Whole-season per-game projection lines (specs/030-post-draft-season-view data-model E1,
 * {@code V31__player_season_projection.sql}), keyed by Sleeper player id.
 *
 * <p>Unlike {@link PlayerProjectionRepository} this stores the raw stat line, not a precomputed
 * total, because basketball is scored per league at read time through
 * {@code GameScoringService}. {@code stats} binds as {@code ?::jsonb} from a JSON string, the
 * same bind the sibling repositories use (AGENTS.md bug class 3: a bare String against a jsonb
 * column compiles and fails at runtime).
 *
 * <p>{@link #replaceAll} is one transaction: upsert every returned row and delete the rest of
 * the (sport, season), so a player who drops out of the source stops counting instead of keeping
 * a stale line. It must be called through the Spring proxy (a self-call would skip the
 * transaction).
 */
@Repository
public class PlayerSeasonProjectionRepository {

    private final JdbcClient db;
    private final JdbcTemplate batch;

    public PlayerSeasonProjectionRepository(JdbcClient db, JdbcTemplate batch) {
        this.db = db;
        this.batch = batch;
    }

    /** {@code statsJson} is the per-game stat line as a JSON object string. */
    public record Row(String sleeperPlayerId, String statsJson, String basis, String source,
                      Instant sourceModifiedAt) {}

    /** @param rowsDeleted rows of that (sport, season) that were not in the new set. */
    public record ReplaceResult(int rowsStored, int rowsDeleted) {}

    public record Provenance(String source, Instant sourceModifiedAt, Instant fetchedAt, int rows) {}

    @Transactional
    public ReplaceResult replaceAll(String sport, int season, List<Row> rows) {
        batch.batchUpdate("""
                insert into player_season_projection
                    (sport, season, sleeper_player_id, stats, basis, source, source_modified_at, fetched_at)
                values (?, ?, ?, ?::jsonb, ?, ?, ?, now())
                on conflict (sport, season, sleeper_player_id) do update set
                    stats = excluded.stats,
                    basis = excluded.basis,
                    source = excluded.source,
                    source_modified_at = excluded.source_modified_at,
                    fetched_at = now()
                """,
                rows, 500,
                (ps, r) -> {
                    ps.setString(1, sport);
                    ps.setInt(2, season);
                    ps.setString(3, r.sleeperPlayerId());
                    ps.setString(4, r.statsJson());
                    ps.setString(5, r.basis());
                    ps.setString(6, r.source());
                    // OffsetDateTime, never java.sql.Timestamp (bug class 3).
                    ps.setObject(7, r.sourceModifiedAt() == null ? null
                            : OffsetDateTime.ofInstant(r.sourceModifiedAt(), ZoneOffset.UTC));
                });

        Set<String> keep = new HashSet<>();
        rows.forEach(r -> keep.add(r.sleeperPlayerId()));
        List<String> stored = db.sql(
                        "select sleeper_player_id from player_season_projection where sport = ? and season = ?")
                .params(sport, season)
                .query(String.class)
                .list();
        int deleted = 0;
        for (String id : stored) {
            if (keep.contains(id)) continue;
            deleted += batch.update(
                    "delete from player_season_projection where sport = ? and season = ? and sleeper_player_id = ?",
                    sport, season, id);
        }
        return new ReplaceResult(rows.size(), deleted);
    }

    /** Empty when nothing is stored for the (sport, season). Timestamps are the newest across rows. */
    public Optional<Provenance> provenance(String sport, int season) {
        return db.sql("""
                select count(*), max(source), max(source_modified_at), max(fetched_at)
                from player_season_projection where sport = ? and season = ?
                """)
                .params(sport, season)
                .query((rs, i) -> {
                    int n = rs.getInt(1);
                    if (n == 0) return Optional.<Provenance>empty();
                    OffsetDateTime mod = rs.getObject(3, OffsetDateTime.class);
                    OffsetDateTime fetched = rs.getObject(4, OffsetDateTime.class);
                    return Optional.of(new Provenance(rs.getString(2),
                            mod == null ? null : mod.toInstant(),
                            fetched == null ? null : fetched.toInstant(), n));
                })
                .single();
    }

    /** Sleeper player id to stat key to per-game value. Non-numeric values are dropped, never zeroed. */
    public Map<String, Map<String, Double>> statsByPlayer(String sport, int season) {
        Map<String, Map<String, Double>> out = new LinkedHashMap<>();
        db.sql("select sleeper_player_id, stats::text from player_season_projection where sport = ? and season = ?")
                .params(sport, season)
                .query((rs, i) -> {
                    Map<String, Double> line = new HashMap<>();
                    for (Map.Entry<String, Object> e : JsonUtil.readMap(rs.getString(2)).entrySet()) {
                        if (e.getValue() instanceof Number n) line.put(e.getKey(), n.doubleValue());
                    }
                    out.put(rs.getString(1), line);
                    return null;
                })
                .list();
        return out;
    }
}
