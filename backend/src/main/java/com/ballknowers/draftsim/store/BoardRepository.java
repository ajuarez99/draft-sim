package com.ballknowers.draftsim.store;

import com.ballknowers.draftsim.domain.Sport;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

@Repository
public class BoardRepository {

    public static final String SOURCE_SEARCH_RANK = "sleeper_search_rank";
    public static final String SOURCE_BLEND = "blend";
    public static final String SOURCE_FFC = "ffc";
    // Spec 046: the three sources the ADP compare page shows. Every board reader filters by source, so
    // none of these can leak into the blend (research R3).
    public static final String SOURCE_SLEEPER_DYNASTY = "sleeper_dynasty";
    public static final String SOURCE_SLEEPER_REDRAFT = "sleeper_redraft";
    public static final String SOURCE_YAHOO = "yahoo";

    private final JdbcClient db;
    private final JdbcTemplate jdbc;

    public BoardRepository(JdbcClient db, JdbcTemplate jdbc) {
        this.db = db;
        this.jdbc = jdbc;
    }

    public record Row(long playerId, double adp, Integer positionalRank) {}

    /**
     * A snapshot is the complete row set for its (sport, source, captured_on),
     * so writing one replaces it rather than merging into it.
     *
     * The upsert alone is not enough: it can only add or update, so a rebuild
     * that drops players — which BoardService.dropOffRoster now does on every
     * run — left the dropped rows sitting in the snapshot at their previous
     * ranks. Re-ingesting on a day already ingested therefore appeared to do
     * nothing: 830 clean rows landed on top of 2007 stale ones and Todd Gurley
     * stayed at board 33. The delete makes the write authoritative; the upsert
     * stays because the delete and insert share a transaction and a retry must
     * not trip the unique constraint.
     */
    @Transactional
    public void save(Sport sport, String source, LocalDate capturedOn, List<Row> rows) {
        clear(sport, source, capturedOn);
        jdbc.batchUpdate("""
                insert into adp_snapshot (player_id, sport, source, captured_on, adp, positional_rank)
                values (?, ?, ?, ?, ?, ?)
                on conflict (player_id, source, captured_on) do update set
                    adp = excluded.adp,
                    positional_rank = excluded.positional_rank
                """,
                rows, 500,
                (ps, r) -> {
                    ps.setLong(1, r.playerId());
                    ps.setString(2, sport.code());
                    ps.setString(3, source);
                    ps.setObject(4, capturedOn);
                    ps.setDouble(5, r.adp());
                    if (r.positionalRank() == null) ps.setNull(6, java.sql.Types.INTEGER);
                    else ps.setInt(6, r.positionalRank());
                });
    }

    /**
     * A source row that carries the provenance metadata search_rank/observed
     * never needed: sample size, the league shape it was actually served for,
     * and whether it was substituted rather than native. See
     * claude/adp-sources.md #2-3; not yet read back by {@link #load}, which
     * only the blend needs today — this is stored so a future stdev-sampling
     * pass or a UI staleness note does not need a second migration.
     */
    public record SourceRow(long playerId, double adp, Integer positionalRank,
                            Double stdev, Integer sourceTeams, String sourceScoring,
                            Integer sampleDrafts, boolean derived, String derivation) {}

    /** Same replace-the-whole-snapshot contract as {@link #save}. */
    @Transactional
    public void saveDetailed(Sport sport, String source, LocalDate capturedOn, List<SourceRow> rows) {
        clear(sport, source, capturedOn);
        jdbc.batchUpdate("""
                insert into adp_snapshot (player_id, sport, source, captured_on, adp, positional_rank,
                                          stdev, source_teams, source_scoring, sample_drafts, derived, derivation)
                values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                on conflict (player_id, source, captured_on) do update set
                    adp = excluded.adp,
                    positional_rank = excluded.positional_rank,
                    stdev = excluded.stdev,
                    source_teams = excluded.source_teams,
                    source_scoring = excluded.source_scoring,
                    sample_drafts = excluded.sample_drafts,
                    derived = excluded.derived,
                    derivation = excluded.derivation
                """,
                rows, 500,
                (ps, r) -> {
                    ps.setLong(1, r.playerId());
                    ps.setString(2, sport.code());
                    ps.setString(3, source);
                    ps.setObject(4, capturedOn);
                    ps.setDouble(5, r.adp());
                    if (r.positionalRank() == null) ps.setNull(6, java.sql.Types.INTEGER);
                    else ps.setInt(6, r.positionalRank());
                    if (r.stdev() == null) ps.setNull(7, java.sql.Types.NUMERIC); else ps.setDouble(7, r.stdev());
                    if (r.sourceTeams() == null) ps.setNull(8, java.sql.Types.INTEGER); else ps.setInt(8, r.sourceTeams());
                    ps.setString(9, r.sourceScoring());
                    if (r.sampleDrafts() == null) ps.setNull(10, java.sql.Types.INTEGER); else ps.setInt(10, r.sampleDrafts());
                    ps.setBoolean(11, r.derived());
                    ps.setString(12, r.derivation());
                });
    }

    /**
     * Spec 046: one compare-page capture row. {@code avgCost} / {@code percentDrafted} are Yahoo's and null for
     * every other source.
     */
    public record CaptureRow(long playerId, double adp, Double avgCost, Double percentDrafted) {}

    /** A source player no local player matched (spec 046 F5): kept so the page can show it, not dropped. */
    public record UnmatchedRow(String name, String team, String positions, Double adp, Double avgCost,
                               Double percentDrafted) {}

    /**
     * Replaces one {@code (sport, source, captured_on)} capture AND that day's {@code adp_unmatched} rows in a
     * single transaction, so a reader never sees the new matched rows beside the old unmatched ones. Same
     * replace-the-whole-snapshot contract as {@link #save}; {@code scoring} lands in {@code source_scoring}
     * ('redraft' / 'dynasty') so "Yahoo is redraft" is data, not a comment.
     */
    @Transactional
    public void saveCapture(Sport sport, String source, LocalDate capturedOn, String scoring,
                            List<CaptureRow> rows, List<UnmatchedRow> unmatched) {
        clear(sport, source, capturedOn);
        jdbc.update("delete from adp_unmatched where sport = ? and source = ? and captured_on = ?",
                sport.code(), source, capturedOn);
        jdbc.batchUpdate("""
                insert into adp_snapshot (player_id, sport, source, captured_on, adp, avg_cost, percent_drafted,
                                          source_scoring)
                values (?, ?, ?, ?, ?, ?, ?, ?)
                on conflict (player_id, source, captured_on) do update set
                    adp = excluded.adp,
                    avg_cost = excluded.avg_cost,
                    percent_drafted = excluded.percent_drafted,
                    source_scoring = excluded.source_scoring
                """,
                rows, 500,
                (ps, r) -> {
                    ps.setLong(1, r.playerId());
                    ps.setString(2, sport.code());
                    ps.setString(3, source);
                    ps.setObject(4, capturedOn);
                    ps.setDouble(5, r.adp());
                    setNullableDouble(ps, 6, r.avgCost());
                    setNullableDouble(ps, 7, r.percentDrafted());
                    ps.setString(8, scoring);
                });
        jdbc.batchUpdate("""
                insert into adp_unmatched (sport, source, captured_on, name, team, positions, adp, avg_cost,
                                           percent_drafted)
                values (?, ?, ?, ?, ?, ?, ?, ?, ?)
                """,
                unmatched, 500,
                (ps, u) -> {
                    ps.setString(1, sport.code());
                    ps.setString(2, source);
                    ps.setObject(3, capturedOn);
                    ps.setString(4, u.name());
                    ps.setString(5, u.team());
                    ps.setString(6, u.positions());
                    setNullableDouble(ps, 7, u.adp());
                    setNullableDouble(ps, 8, u.avgCost());
                    setNullableDouble(ps, 9, u.percentDrafted());
                });
    }

    private static void setNullableDouble(java.sql.PreparedStatement ps, int i, Double v) throws java.sql.SQLException {
        if (v == null) ps.setNull(i, java.sql.Types.NUMERIC); else ps.setDouble(i, v);
    }

    /** How many rows one capture holds -- the shrink guard's "currently stored" figure. */
    public int captureSize(Sport sport, String source, LocalDate capturedOn) {
        Integer n = jdbc.queryForObject(
                "select count(*) from adp_snapshot where sport = ? and source = ? and captured_on = ?",
                Integer.class, sport.code(), source, capturedOn);
        return n == null ? 0 : n;
    }

    /** The newest capture of a source with its date and its full compare rows; empty when there is none. */
    public record LatestCapture(LocalDate capturedOn, List<CaptureRow> rows, List<UnmatchedRow> unmatched) {}

    public Optional<LatestCapture> latestCaptureDetail(Sport sport, String source) {
        // order by ... limit 1 rather than max(): max over no rows is one null row, which is not "no capture".
        Optional<LocalDate> date = db.sql("""
                select captured_on from adp_snapshot where sport = ? and source = ?
                order by captured_on desc limit 1
                """)
                .params(sport.code(), source)
                .query(LocalDate.class)
                .optional();
        return date.map(d -> new LatestCapture(d,
                db.sql("""
                        select player_id, adp, avg_cost, percent_drafted from adp_snapshot
                        where sport = ? and source = ? and captured_on = ? order by adp, player_id
                        """)
                        .params(sport.code(), source, d)
                        .query((rs, i) -> new CaptureRow(rs.getLong(1), rs.getDouble(2),
                                rs.getObject(3) == null ? null : rs.getDouble(3),
                                rs.getObject(4) == null ? null : rs.getDouble(4)))
                        .list(),
                db.sql("""
                        select name, team, positions, adp, avg_cost, percent_drafted from adp_unmatched
                        where sport = ? and source = ? and captured_on = ? order by adp, id
                        """)
                        .params(sport.code(), source, d)
                        .query((rs, i) -> new UnmatchedRow(rs.getString(1), rs.getString(2), rs.getString(3),
                                rs.getObject(4) == null ? null : rs.getDouble(4),
                                rs.getObject(5) == null ? null : rs.getDouble(5),
                                rs.getObject(6) == null ? null : rs.getDouble(6)))
                        .list()));
    }

    private void clear(Sport sport, String source, LocalDate capturedOn) {
        jdbc.update("delete from adp_snapshot where sport = ? and source = ? and captured_on = ?",
                sport.code(), source, capturedOn);
    }

    public Optional<LocalDate> latestCapture(Sport sport, String source) {
        return db.sql("select max(captured_on) from adp_snapshot where sport = ? and source = ?")
                .params(sport.code(), source)
                .query(LocalDate.class)
                .optional();
    }

    public List<Row> load(Sport sport, String source, LocalDate capturedOn) {
        return db.sql("""
                select player_id, adp, positional_rank from adp_snapshot
                where sport = ? and source = ? and captured_on = ?
                order by adp
                """)
                .params(sport.code(), source, capturedOn)
                .query((rs, i) -> new Row(rs.getLong(1), rs.getDouble(2),
                        rs.getObject(3) == null ? null : rs.getInt(3)))
                .list();
    }

    /** One player's place in a source's ranking: Sleeper's id and the rank (1 = best). */
    public record RankedPlayer(String sleeperPlayerId, int rank) {}

    /**
     * The top {@code limit} players of a source's NEWEST capture for a sport, best first
     * (specs/043, T005). Reads {@code adp} only as an ordering, so for {@link #SOURCE_SEARCH_RANK}
     * (a dense 1..N ordinal, no ties) the rank is the stored value. Joins {@code player} to give back
     * Sleeper's id, the key every per-game table uses; a snapshot row whose player is gone drops out.
     */
    public List<RankedPlayer> topRanked(Sport sport, String source, int limit) {
        return db.sql("""
                select p.sleeper_id, s.adp
                from adp_snapshot s
                join player p on p.id = s.player_id and p.sport = s.sport
                where s.sport = ? and s.source = ?
                  and s.captured_on = (select max(captured_on) from adp_snapshot
                                       where sport = ? and source = ?)
                order by s.adp, p.sleeper_id
                limit ?
                """)
                .params(sport.code(), source, sport.code(), source, limit)
                .query((rs, i) -> new RankedPlayer(rs.getString(1), (int) Math.round(rs.getDouble(2))))
                .list();
    }

    /**
     * The board as of a given date, for denormalizing adp_at_time onto historical
     * picks. Falls back to the closest earlier capture.
     */
    public List<Row> asOf(Sport sport, String source, LocalDate on) {
        Optional<LocalDate> d = db.sql("""
                select max(captured_on) from adp_snapshot
                where sport = ? and source = ? and captured_on <= ?
                """)
                .params(sport.code(), source, on)
                .query(LocalDate.class)
                .optional();
        return d.map(date -> load(sport, source, date)).orElse(List.of());
    }

    /** One capture: the date it was taken and its complete row set (best ADP first). */
    public record Capture(LocalDate capturedOn, List<Row> rows) {}

    /**
     * The latest capture of {@code source} whose {@code captured_on} is on or before {@code onOrBefore},
     * with its date and rows; empty when no capture is that early (spec 022 T048, F12).
     *
     * <p>Rows key on {@code player.id}, the local id, not the Sleeper id. The date comes from
     * {@code order by ... limit 1}, not {@code max()}: an aggregate over no rows is one row holding null, which
     * is not "no capture". {@link #asOf}, which has no callers, is deliberately left alone.
     */
    public Optional<Capture> latestBefore(Sport sport, String source, LocalDate onOrBefore) {
        Optional<LocalDate> date = db.sql("""
                select captured_on from adp_snapshot
                where sport = ? and source = ? and captured_on <= ?
                order by captured_on desc limit 1
                """)
                .params(sport.code(), source, onOrBefore)
                .query(LocalDate.class)
                .optional();
        return date.map(d -> new Capture(d, load(sport, source, d)));
    }
}
