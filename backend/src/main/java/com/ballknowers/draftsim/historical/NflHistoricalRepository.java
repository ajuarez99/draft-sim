package com.ballknowers.draftsim.historical;

import com.ballknowers.draftsim.historical.HistoricalBoardBuilder.Candidate;
import com.ballknowers.draftsim.historical.HistoricalBoardBuilder.RookiePick;
import com.ballknowers.draftsim.historical.HistoricalDataRepository.SeasonRow;
import com.ballknowers.draftsim.historical.NflverseSource.DraftPickRow;
import com.ballknowers.draftsim.historical.NflverseSource.ParsedSeason;
import com.ballknowers.draftsim.historical.NflverseSource.SeasonAgg;
import com.ballknowers.draftsim.historical.NflverseSource.WeekRow;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.sql.Array;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Types;
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The football half of the historical reference data (spec 056, V46): {@code historical_nfl_player_week},
 * {@code historical_nfl_player_season} and {@code historical_nfl_draft_pick}. Players and season metadata live in
 * the shared tables and go through {@link HistoricalDataRepository}.
 *
 * <p>Same rules as that class: {@code text[]} is bound through {@code createArrayOf}, nothing is built with
 * {@code Map.of} where a value can be null, and the caller owns the transaction (the import runs one per season).
 */
@Repository
public class NflHistoricalRepository {

    private static final int BATCH = 1000;
    /** The 13 stat columns, in {@link NflverseSource#KEYS} order. The column names ARE the keys. */
    private static final List<String> KEYS = NflverseSource.KEYS;
    private static final String STAT_COLS = String.join(", ", KEYS);
    private static final String STAT_MARKS = String.join(", ", java.util.Collections.nCopies(KEYS.size(), "?"));

    private final JdbcClient db;
    private final JdbcTemplate jdbc;
    private final HistoricalDataRepository data;

    public NflHistoricalRepository(JdbcClient db, JdbcTemplate jdbc, HistoricalDataRepository data) {
        this.db = db;
        this.jdbc = jdbc;
        this.data = data;
    }

    // ---- the two line shapes ---------------------------------------------------------------------------

    /** One player's REG season, summed from his weeks (R5). {@code age} is always null: the files carry none. */
    public record NflSeasonLine(long playerId, int seasonStart, String team, int games, List<String> positions,
                                Map<String, Double> totals) implements StatLine {
        @Override public Integer age() { return null; }

        @Override
        public Double stat(String key) {
            if (!KEYS.contains(key)) throw new IllegalArgumentException("not a football counting stat: " + key);
            return totals.get(key);
        }
    }

    /** One player's REG game. {@code gameDate} is null: the nflverse files carry no dates (R4). */
    public record NflWeekLine(long playerId, int season, int week, String team, Map<String, Double> stats)
            implements WeekStatLine {
        @Override public java.time.LocalDate gameDate() { return null; }

        @Override public Double stat(String key) { return stats.get(key); }
    }

    // ---- writes ----------------------------------------------------------------------------------------

    /**
     * Replaces one season's week rows and season lines with {@code p}'s, upserting every player first (source
     * {@code NFLVERSE}, key = the GSIS id; never deleted, 053 F9) and then the season row, last, so a row means the
     * season committed. The caller wraps this in one transaction.
     *
     * @param seasonRow the metadata to write; it is stored as given (the caller computed counts and the sha)
     */
    public void replaceSeason(ParsedSeason p, SeasonRow seasonRow) {
        Map<String, Long> ids = new HashMap<>();
        Map<String, String> names = new LinkedHashMap<>();
        for (WeekRow w : p.weeks()) names.put(w.gsisId(), w.name());
        for (SeasonAgg a : p.seasonLines()) names.putIfAbsent(a.gsisId(), a.name());
        for (Map.Entry<String, String> e : names.entrySet()) {
            ids.put(e.getKey(), data.upsertPlayer("NFLVERSE", e.getKey(), e.getValue()));
        }

        jdbc.update("delete from historical_nfl_player_week where season = ?", p.season());
        jdbc.update("delete from historical_nfl_player_season where season = ?", p.season());

        jdbc.batchUpdate("insert into historical_nfl_player_week (player_id, season, week, game_id, team, opponent,"
                        + " position, " + STAT_COLS + ", src_ppr) values (?, ?, ?, ?, ?, ?, ?, " + STAT_MARKS + ", ?)",
                p.weeks(), BATCH, (ps, w) -> {
                    ps.setLong(1, ids.get(w.gsisId()));
                    ps.setInt(2, p.season());
                    ps.setInt(3, w.week());
                    ps.setString(4, w.gameId());
                    ps.setString(5, w.team());
                    ps.setString(6, w.opponent());
                    ps.setString(7, w.position());
                    for (int i = 0; i < KEYS.size(); i++) ps.setDouble(8 + i, w.stats()[i]);
                    int last = 8 + KEYS.size();
                    if (w.srcPpr() == null) ps.setNull(last, Types.NUMERIC);
                    else ps.setDouble(last, w.srcPpr());
                });

        jdbc.batchUpdate("insert into historical_nfl_player_season (player_id, season, team, games, positions, "
                        + STAT_COLS + ") values (?, ?, ?, ?, ?, " + STAT_MARKS + ")",
                p.seasonLines(), BATCH, (ps, a) -> {
                    ps.setLong(1, ids.get(a.gsisId()));
                    ps.setInt(2, p.season());
                    ps.setString(3, a.team());
                    ps.setInt(4, a.games());
                    ps.setArray(5, ps.getConnection().createArrayOf("text", new String[]{a.position()}));
                    for (int i = 0; i < KEYS.size(); i++) ps.setDouble(6 + i, a.totals()[i]);
                });

        data.upsertSeason(seasonRow);
    }

    /**
     * Replaces the draft picks of every draft year present in {@code picks} (F14): only players that already exist as
     * NFLVERSE {@code historical_player} rows get a row, so run it after the season imports. A full file replaces every
     * year it covers; a partial one (a test fixture, a one-season re-import) touches only its own years and never
     * wipes the rest. Returns how many were stored.
     */
    public int replaceDraftPicks(List<DraftPickRow> picks) {
        Integer[] years = picks.stream().map(DraftPickRow::season).distinct().toArray(Integer[]::new);
        jdbc.update(con -> {
            PreparedStatement ps = con.prepareStatement("delete from historical_nfl_draft_pick where season = any(?)");
            ps.setArray(1, con.createArrayOf("integer", years));
            return ps;
        });
        int[][] counts = jdbc.batchUpdate("""
                insert into historical_nfl_draft_pick (player_id, season, round, pick, position)
                select p.id, ?, ?, ?, ? from historical_player p where p.source = 'NFLVERSE' and p.source_key = ?
                """, picks, BATCH, (ps, d) -> {
            ps.setInt(1, d.season());
            ps.setInt(2, d.round());
            ps.setInt(3, d.pick());
            ps.setString(4, d.position());
            ps.setString(5, d.gsisId());
        });
        int stored = 0;
        for (int[] batch : counts) for (int c : batch) if (c > 0) stored += c;
        // A driver may report SUCCESS_NO_INFO (-2) for a batch; trust the table in that case.
        if (stored == 0 && !picks.isEmpty()) {
            Integer n = jdbc.query(con -> {
                PreparedStatement ps = con.prepareStatement(
                        "select count(*) from historical_nfl_draft_pick where season = any(?)");
                ps.setArray(1, con.createArrayOf("integer", years));
                return ps;
            }, rs -> rs.next() ? rs.getInt(1) : 0);
            stored = n == null ? 0 : n;
        }
        return stored;
    }

    // ---- reads for play --------------------------------------------------------------------------------

    /** Every player with at least one REG game row in the season (the board's candidates), by id. */
    public List<Candidate> candidates(int season) {
        return db.sql("""
                select distinct p.id, p.name
                from historical_nfl_player_week w join historical_player p on p.id = w.player_id
                where w.season = ?
                order by p.id
                """)
                .param(season)
                .query((rs, i) -> new Candidate(rs.getLong("id"), rs.getString("name")))
                .list();
    }

    /** Every player's line for one season, by player id. An unimported season is just empty. */
    public Map<Long, NflSeasonLine> lines(int season) {
        Map<Long, NflSeasonLine> out = new HashMap<>();
        db.sql("select player_id, season, team, games, positions, " + STAT_COLS
                        + " from historical_nfl_player_season where season = ?")
                .param(season)
                .query((rs, i) -> {
                    NflSeasonLine l = new NflSeasonLine(rs.getLong("player_id"), rs.getInt("season"),
                            rs.getString("team"), rs.getInt("games"), textArray(rs, "positions"), stats(rs));
                    out.put(l.playerId(), l);
                    return 0;
                }).list();
        return out;
    }

    /** The REG games of one season for the given players, ordered (week, player) so a replay is deterministic. */
    public List<NflWeekLine> playedWeeks(int season, Collection<Long> playerIds) {
        if (playerIds.isEmpty()) return List.of();
        return jdbc.query(con -> {
            PreparedStatement ps = con.prepareStatement("select player_id, season, week, team, " + STAT_COLS
                    + " from historical_nfl_player_week where season = ? and player_id = any(?)"
                    + " order by week, player_id");
            ps.setInt(1, season);
            ps.setArray(2, con.createArrayOf("bigint", playerIds.toArray()));
            return ps;
        }, (rs, i) -> new NflWeekLine(rs.getLong("player_id"), rs.getInt("season"), rs.getInt("week"),
                rs.getString("team"), stats(rs)));
    }

    /** The players drafted INTO the NFL in this season, by player id (F14). */
    public Map<Long, RookiePick> draftPicks(int season) {
        Map<Long, RookiePick> out = new LinkedHashMap<>();
        db.sql("select player_id, round, pick from historical_nfl_draft_pick where season = ? order by pick")
                .param(season)
                .query((rs, i) -> {
                    out.put(rs.getLong("player_id"), new RookiePick(rs.getInt("round"), rs.getInt("pick")));
                    return 0;
                }).list();
        return out;
    }

    // ---- helpers ---------------------------------------------------------------------------------------

    /** The 13 stat columns as a key-ordered map. Never null-valued: the columns are not null, default 0. */
    private static Map<String, Double> stats(ResultSet rs) throws SQLException {
        Map<String, Double> m = new LinkedHashMap<>();
        for (String k : KEYS) m.put(k, rs.getDouble(k));
        return m;
    }

    private static List<String> textArray(ResultSet rs, String col) throws SQLException {
        Array a = rs.getArray(col);
        return a == null ? List.of() : List.of((String[]) a.getArray());
    }

    /** For tests: how many rows each of the two season tables holds for a season. */
    public int[] rowCounts(int season) {
        Integer w = jdbc.queryForObject("select count(*) from historical_nfl_player_week where season = ?",
                Integer.class, season);
        Integer s = jdbc.queryForObject("select count(*) from historical_nfl_player_season where season = ?",
                Integer.class, season);
        return new int[]{w == null ? 0 : w, s == null ? 0 : s};
    }
}
