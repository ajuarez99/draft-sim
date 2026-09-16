package com.ballknowers.draftsim.store;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Cached weekly projections (claude/league-analysis.md Phase 1), keyed by
 * Sleeper player id.
 *
 * Unlike {@link RosterWeekPointsRepository}, "already stored" is not the same
 * as "settled": a projection for a week nobody has played changes as the week
 * approaches, so the staleness question is answered by {@link #fetchedAt}
 * rather than by a set of weeks the caller may skip forever.
 */
@Repository
public class PlayerProjectionRepository {

    private final JdbcClient db;
    private final JdbcTemplate batch;

    /**
     * Two handles on the same DataSource, deliberately.
     *
     * The rest of this package reads and writes a row at a time through
     * {@link JdbcClient}, which is why it is here. But a rest-of-season refresh
     * is ~6,500 rows, and JdbcClient has no batch form -- the sibling
     * repositories' loop-and-upsert would be 6,500 round trips, which is
     * tolerable against a local Postgres and is not against a hosted one.
     * {@link JdbcTemplate#batchUpdate} is the only batching API Spring offers
     * here.
     */
    public PlayerProjectionRepository(JdbcClient db, JdbcTemplate batch) {
        this.db = db;
        this.batch = batch;
    }

    public record Row(String sport, int season, int week, String sleeperPlayerId,
                      Double ptsPpr, Double ptsHalfPpr, Double ptsStd, String source) {}

    public void upsertAll(List<Row> rows) {
        if (rows.isEmpty()) return;
        batch.batchUpdate("""
                insert into player_projection
                    (sport, season, week, sleeper_player_id, pts_ppr, pts_half_ppr, pts_std, source, fetched_at)
                values (?, ?, ?, ?, ?, ?, ?, ?, now())
                on conflict (sport, season, week, sleeper_player_id) do update set
                    pts_ppr = excluded.pts_ppr,
                    pts_half_ppr = excluded.pts_half_ppr,
                    pts_std = excluded.pts_std,
                    source = excluded.source,
                    fetched_at = now()
                """,
                rows.stream()
                        .map(r -> new Object[]{r.sport(), r.season(), r.week(), r.sleeperPlayerId(),
                                r.ptsPpr(), r.ptsHalfPpr(), r.ptsStd(), r.source()})
                        .toList());
    }

    /**
     * When this (sport, season, week) was last refreshed, or empty if it has
     * never been fetched. Empty and "fetched long ago" are different answers to
     * the caller -- one is a cold cache, the other is a stale one -- so this
     * does not collapse them into a boolean.
     */
    public Optional<Instant> fetchedAt(String sport, int season, int week) {
        return db.sql("""
                select max(fetched_at) from player_projection
                where sport = ? and season = ? and week = ?
                """)
                .params(sport, season, week)
                .query(Instant.class)
                .optional();
    }

    /**
     * Summed projected points per Sleeper player id over {@code [fromWeek,
     * toWeek]} inclusive, for one scoring key.
     *
     * The scoring column is chosen by the caller from the league's own
     * scoring_json and arrives here as a validated enum-ish string -- see
     * {@code ScoringKey}. It is interpolated into the SQL because a column name
     * cannot be a bind parameter; the value is never caller-supplied text.
     *
     * A player with no row for a given week simply does not contribute that
     * week, which is the intended reading: a projection Sleeper does not
     * publish (IR, Out, PUP -- 7 of 180 rostered players when this was measured)
     * is zero points, not a missing value to be filled in with something
     * friendlier.
     */
    public Map<String, Double> totalsByPlayer(String sport, int season, int fromWeek, int toWeek,
                                              ScoringKey key) {
        return db.sql("""
                select sleeper_player_id, coalesce(sum(%s), 0)
                from player_projection
                where sport = ? and season = ? and week between ? and ?
                group by sleeper_player_id
                """.formatted(key.column()))
                .params(sport, season, fromWeek, toWeek)
                .query((rs, i) -> Map.entry(rs.getString(1), rs.getDouble(2)))
                .list()
                .stream()
                .collect(java.util.stream.Collectors.toMap(Map.Entry::getKey, Map.Entry::getValue));
    }

    /**
     * The same points, kept week by week instead of summed
     * (claude/league-analysis-week-by-week.md).
     *
     * <p>One query, not one per week. The summed form above cannot answer this
     * -- a rest-of-season total says nothing about WHICH week a bye falls in,
     * which is the entire question a week-by-week view asks -- and a loop
     * calling it thirteen times would be thirteen round trips re-reading rows
     * this reads once.
     *
     * @return week -> (Sleeper player id -> points). A week with nothing stored
     *         is absent rather than present and empty, so the caller can tell
     *         "no projection published" from "projected zero".
     */
    public Map<Integer, Map<String, Double>> totalsByPlayerPerWeek(String sport, int season, int fromWeek,
                                                                  int toWeek, ScoringKey key) {
        Map<Integer, Map<String, Double>> byWeek = new java.util.LinkedHashMap<>();
        db.sql("""
                select week, sleeper_player_id, coalesce(%s, 0)
                from player_projection
                where sport = ? and season = ? and week between ? and ?
                order by week
                """.formatted(key.column()))
                .params(sport, season, fromWeek, toWeek)
                .query((rs, i) -> {
                    byWeek.computeIfAbsent(rs.getInt(1), w -> new java.util.HashMap<>())
                            .put(rs.getString(2), rs.getDouble(3));
                    return null;
                })
                .list();
        return byWeek;
    }

    /**
     * Which of Sleeper's three scoring totals a league actually plays under.
     *
     * A constant here would assert a league rule while looking like it reads a
     * value -- the shape of bug this repo has shipped three times (see
     * claude/lessons.md on defaulted parameters that encode rules). So the key
     * is derived from the league's own {@code scoring_json.rec} and carried in
     * the API response, where a reader can see which one was used.
     */
    public enum ScoringKey {
        PPR("pts_ppr"), HALF_PPR("pts_half_ppr"), STANDARD("pts_std");

        private final String column;

        ScoringKey(String column) {
            this.column = column;
        }

        public String column() {
            return column;
        }

        /** Sleeper's own convention: scoring_json.rec is points per reception. */
        public static ScoringKey forReceptionPoints(double rec) {
            if (rec >= 1.0) return PPR;
            if (rec >= 0.5) return HALF_PPR;
            return STANDARD;
        }
    }
}
