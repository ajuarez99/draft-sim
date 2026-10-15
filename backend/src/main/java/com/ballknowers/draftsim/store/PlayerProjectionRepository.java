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

    /**
     * @param stats the projected stat line minus {@code adp_*} / {@code pos_adp_*} keys (spec 036 US3);
     *              null for a row written before V36 or one whose payload carried no line
     */
    public record Row(String sport, int season, int week, String sleeperPlayerId,
                      Double ptsPpr, Double ptsHalfPpr, Double ptsStd, String source,
                      Map<String, Object> stats) {}

    public void upsertAll(List<Row> rows) {
        if (rows.isEmpty()) return;
        batch.batchUpdate("""
                insert into player_projection
                    (sport, season, week, sleeper_player_id, pts_ppr, pts_half_ppr, pts_std, source, stats, fetched_at)
                values (?, ?, ?, ?, ?, ?, ?, ?, ?::jsonb, now())
                on conflict (sport, season, week, sleeper_player_id) do update set
                    pts_ppr = excluded.pts_ppr,
                    pts_half_ppr = excluded.pts_half_ppr,
                    pts_std = excluded.pts_std,
                    source = excluded.source,
                    stats = excluded.stats,
                    fetched_at = now()
                """,
                rows.stream()
                        .map(r -> new Object[]{r.sport(), r.season(), r.week(), r.sleeperPlayerId(),
                                r.ptsPpr(), r.ptsHalfPpr(), r.ptsStd(), r.source(),
                                r.stats() == null ? null : JsonUtil.write(r.stats())})
                        .toList());
    }

    /**
     * One stored projection row for a window read (spec 036 US3).
     *
     * @param stats null when the row predates V36 or had no line; the exact-scoring path then falls back to the
     *              column pick for this row, so a half-refreshed window still produces a number
     */
    public record WeekLine(int week, String sleeperPlayerId, Double ptsPpr, Double ptsHalfPpr, Double ptsStd,
                           Map<String, Object> stats) {

        /** The value the column pick would give (null column counts as 0, as in {@code coalesce(col, 0)}). */
        public double column(ScoringKey key) {
            Double v = switch (key) {
                case PPR -> ptsPpr;
                case HALF_PPR -> ptsHalfPpr;
                case STANDARD -> ptsStd;
            };
            return v == null ? 0.0 : v;
        }
    }

    /** Every stored row in {@code [fromWeek, toWeek]}, with its stat line. */
    public List<WeekLine> linesInWindow(String sport, int season, int fromWeek, int toWeek) {
        return db.sql("""
                select week, sleeper_player_id, pts_ppr, pts_half_ppr, pts_std, stats::text
                from player_projection
                where sport = ? and season = ? and week between ? and ?
                order by week
                """)
                .params(sport, season, fromWeek, toWeek)
                .query((rs, i) -> new WeekLine(rs.getInt(1), rs.getString(2),
                        dbl(rs.getBigDecimal(3)), dbl(rs.getBigDecimal(4)), dbl(rs.getBigDecimal(5)),
                        rs.getString(6) == null ? null : JsonUtil.readMap(rs.getString(6))))
                .list();
    }

    private static Double dbl(java.math.BigDecimal v) {
        return v == null ? null : v.doubleValue();
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

    /** Who produced the numbers in a window and when the newest of them was fetched. */
    public record WindowProvenance(String source, Instant fetchedAt) {}

    /**
     * Provenance for the window {@code [fromWeek, toWeek]} (specs/030, so the analysis page can say where
     * football's numbers came from the way it does for basketball's): the source and the newest
     * {@code fetched_at} over the window. Empty when nothing is stored there.
     */
    public Optional<WindowProvenance> provenance(String sport, int season, int fromWeek, int toWeek) {
        return db.sql("""
                select max(source), max(fetched_at), count(*)
                from player_projection
                where sport = ? and season = ? and week between ? and ?
                """)
                .params(sport, season, fromWeek, toWeek)
                .query((rs, i) -> {
                    if (rs.getInt(3) == 0) return Optional.<WindowProvenance>empty();
                    java.time.OffsetDateTime at = rs.getObject(2, java.time.OffsetDateTime.class);
                    return Optional.of(new WindowProvenance(rs.getString(1), at == null ? null : at.toInstant()));
                })
                .single();
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
