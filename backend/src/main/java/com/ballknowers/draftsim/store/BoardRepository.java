package com.ballknowers.draftsim.store;

import com.ballknowers.draftsim.domain.Sport;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

@Repository
public class BoardRepository {

    public static final String SOURCE_SEARCH_RANK = "sleeper_search_rank";
    public static final String SOURCE_BLEND = "blend";
    public static final String SOURCE_FFC = "ffc";

    private final JdbcClient db;
    private final JdbcTemplate jdbc;

    public BoardRepository(JdbcClient db, JdbcTemplate jdbc) {
        this.db = db;
        this.jdbc = jdbc;
    }

    public record Row(long playerId, double adp, Integer positionalRank) {}

    public void save(Sport sport, String source, LocalDate capturedOn, List<Row> rows) {
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

    public void saveDetailed(Sport sport, String source, LocalDate capturedOn, List<SourceRow> rows) {
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
}
