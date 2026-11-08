package com.ballknowers.draftsim.historical;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.sql.Array;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Types;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.Collection;
import java.util.List;
import java.util.Optional;

/**
 * The historical draft game's REFERENCE data (spec 053, V43): players, per-season card lines, per-game box
 * lines, season metadata and import jobs. Written by the import, read by play. The game tables live in
 * {@link HistoricalGameRepository}.
 *
 * <p>Plain JDBC. Three rules from this repo's bug classes apply to every statement here:
 * <ul>
 *   <li>{@code text[]} is bound through {@code Connection.createArrayOf("text", ...)}, never a raw
 *       {@code String[]};
 *   <li>timestamps are {@link OffsetDateTime}, never {@code java.sql.Timestamp}, for {@code timestamptz};
 *   <li>{@code "to"} (turnovers) is a reserved word and is quoted in every statement.
 * </ul>
 *
 * <p>Players are upserted on {@code (source, source_key)} and NEVER deleted (plan F9): a saved game's picks and
 * board snapshot point at their ids, so a re-import must keep them stable.
 */
@Repository
public class HistoricalDataRepository {

    private static final int BATCH = 1000;

    private final JdbcClient db;
    private final JdbcTemplate jdbc;

    public HistoricalDataRepository(JdbcClient db, JdbcTemplate jdbc) {
        this.db = db;
        this.jdbc = jdbc;
    }

    // ---- rows ----------------------------------------------------------------------------------------

    /** One real person. */
    public record PlayerRow(long id, String source, String sourceKey, String name) {}

    /**
     * One player-season card line. Stat fields are null when unrecorded (NOT zero); {@code age} is null where
     * the source has none.
     */
    public record SeasonLine(long playerId, int seasonStart, String team, Integer age, int games,
                             List<String> positions, Double pts, Double reb, Double ast, Double stl, Double blk,
                             Double to, Double tpm, Double fgm, Double fga, Double ftm, Double fta)
            implements StatLine {

        /** The season total of a counting stat; null when unrecorded. (Moved verbatim from the board builder, 056 T005.) */
        @Override
        public Double stat(String key) {
            return switch (key) {
                case "pts" -> pts();
                case "reb" -> reb();
                case "ast" -> ast();
                case "stl" -> stl();
                case "blk" -> blk();
                case "to" -> to();
                case "tpm" -> tpm();
                default -> throw new IllegalArgumentException("not a counting stat: " + key);
            };
        }
    }

    /** A season line with the player's display name, for building a board. */
    public record NamedSeasonLine(String name, SeasonLine line) {}

    /**
     * One played game. Stat fields are null when unrecorded; the {@code dd..bonusAst15} flags are null when
     * not computable that season.
     */
    public record GameLine(long playerId, long gameId, int seasonStart, LocalDate gameDate, int week, String team,
                           Integer pts, Integer reb, Integer ast, Integer stl, Integer blk, Integer to,
                           Integer tpm, Integer fgm, Integer fga, Integer ftm, Integer fta, Integer tf,
                           Integer ff, Integer dd, Integer td, Integer bonusPt40, Integer bonusPt50,
                           Integer bonusReb20, Integer bonusAst15) implements WeekStatLine {

        /** One stat of this game, null when unrecorded. (Moved verbatim from {@code SeasonReplay.statMap}, 056 T005.) */
        @Override
        public Double stat(String key) {
            Integer v = switch (key) {
                case "pts" -> pts();
                case "reb" -> reb();
                case "ast" -> ast();
                case "stl" -> stl();
                case "blk" -> blk();
                case "to" -> to();
                case "tpm" -> tpm();
                case "tf" -> tf();
                case "ff" -> ff();
                case "dd" -> dd();
                case "td" -> td();
                case "bonus_pt_40p" -> bonusPt40();
                case "bonus_pt_50p" -> bonusPt50();
                case "bonus_reb_20p" -> bonusReb20();
                case "bonus_ast_15p" -> bonusAst15();
                default -> null;
            };
            return v == null ? null : v.doubleValue();
        }
    }

    /**
     * The per-season metadata row; the jsonb columns are carried as JSON text. {@code sport} is "NBA" or "NFL"
     * (V46; no default, spec 056 F8). {@code openingNight}/{@code lastCountedDate} are null for football.
     * {@code expectedGameCount} and {@code sourceSha256} are null for basketball.
     */
    public record SeasonRow(String sport, int seasonStart, String label, String source, LocalDate openingNight,
                            LocalDate lastCountedDate, int weekCount, int gameCount, List<String> countedStats,
                            String excludedStatsJson, String statCoverageJson, String rankingSource,
                            OffsetDateTime importedAt, Integer expectedGameCount, int droppedRows,
                            String sourceSha256) {}

    public record ImportJobRow(long id, String part, String status, OffsetDateTime startedAt,
                               OffsetDateTime finishedAt, String reportJson) {}

    // ---- players -------------------------------------------------------------------------------------

    /**
     * Insert the player, or keep his id and refresh his display name. Never deletes; the id is stable across
     * any number of imports.
     */
    public long upsertPlayer(String source, String sourceKey, String name) {
        return db.sql("""
                insert into historical_player (source, source_key, name) values (?, ?, ?)
                on conflict (source, source_key) do update set name = excluded.name
                returning id
                """)
                .params(source, sourceKey, name)
                .query(Long.class)
                .single();
    }

    public List<PlayerRow> playersByIds(Collection<Long> ids) {
        if (ids.isEmpty()) return List.of();
        return jdbc.query(con -> {
            PreparedStatement ps = con.prepareStatement(
                    "select id, source, source_key, name from historical_player where id = any(?) order by id");
            ps.setArray(1, con.createArrayOf("bigint", ids.toArray()));
            return ps;
        }, (rs, i) -> new PlayerRow(rs.getLong("id"), rs.getString("source"),
                rs.getString("source_key"), rs.getString("name")));
    }

    // ---- per-season replace --------------------------------------------------------------------------

    /**
     * Replace one season's card lines: delete then insert, so a re-import cannot leave a stale row. The caller
     * owns the transaction (the import runs one per season).
     */
    public void replaceSeasonLines(int seasonStart, List<SeasonLine> lines) {
        jdbc.update("delete from historical_player_season where season_start = ?", seasonStart);
        insertSeasonLines(lines);
    }

    /**
     * Replaces only the lines belonging to players of one source. Season 2016 has Kaggle lines (the box-score
     * source's own drafted-season line) AND Sleeper lines (the cards 2017 draws), so the Kaggle and Sleeper
     * imports must not delete each other's rows.
     */
    public void replaceSeasonLines(int seasonStart, String source, List<SeasonLine> lines) {
        jdbc.update("""
                delete from historical_player_season
                where season_start = ? and player_id in (select id from historical_player where source = ?)
                """, seasonStart, source);
        insertSeasonLines(lines);
    }

    private void insertSeasonLines(List<SeasonLine> lines) {
        jdbc.batchUpdate("""
                insert into historical_player_season (player_id, season_start, team, age, games, positions,
                    pts, reb, ast, stl, blk, "to", tpm, fgm, fga, ftm, fta)
                values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """, lines, BATCH, (ps, l) -> {
            ps.setLong(1, l.playerId());
            ps.setInt(2, l.seasonStart());
            ps.setString(3, l.team());
            setInt(ps, 4, l.age());
            ps.setInt(5, l.games());
            ps.setArray(6, ps.getConnection().createArrayOf("text", l.positions().toArray()));
            setNum(ps, 7, l.pts());
            setNum(ps, 8, l.reb());
            setNum(ps, 9, l.ast());
            setNum(ps, 10, l.stl());
            setNum(ps, 11, l.blk());
            setNum(ps, 12, l.to());
            setNum(ps, 13, l.tpm());
            setNum(ps, 14, l.fgm());
            setNum(ps, 15, l.fga());
            setNum(ps, 16, l.ftm());
            setNum(ps, 17, l.fta());
        });
    }

    /** Replace one season's played games, in batches of {@value #BATCH}. */
    public void replaceSeasonGames(int seasonStart, List<GameLine> games) {
        jdbc.update("delete from historical_player_game where season_start = ?", seasonStart);
        jdbc.batchUpdate("""
                insert into historical_player_game (player_id, game_id, season_start, game_date, week, team,
                    pts, reb, ast, stl, blk, "to", tpm, fgm, fga, ftm, fta, tf, ff,
                    dd, td, bonus_pt_40p, bonus_pt_50p, bonus_reb_20p, bonus_ast_15p)
                values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """, games, BATCH, (ps, g) -> {
            ps.setLong(1, g.playerId());
            ps.setLong(2, g.gameId());
            ps.setInt(3, g.seasonStart());
            ps.setObject(4, g.gameDate());
            ps.setInt(5, g.week());
            ps.setString(6, g.team());
            setSmall(ps, 7, g.pts());
            setSmall(ps, 8, g.reb());
            setSmall(ps, 9, g.ast());
            setSmall(ps, 10, g.stl());
            setSmall(ps, 11, g.blk());
            setSmall(ps, 12, g.to());
            setSmall(ps, 13, g.tpm());
            setSmall(ps, 14, g.fgm());
            setSmall(ps, 15, g.fga());
            setSmall(ps, 16, g.ftm());
            setSmall(ps, 17, g.fta());
            setSmall(ps, 18, g.tf());
            setSmall(ps, 19, g.ff());
            setSmall(ps, 20, g.dd());
            setSmall(ps, 21, g.td());
            setSmall(ps, 22, g.bonusPt40());
            setSmall(ps, 23, g.bonusPt50());
            setSmall(ps, 24, g.bonusReb20());
            setSmall(ps, 25, g.bonusAst15());
        });
    }

    // ---- season metadata -----------------------------------------------------------------------------

    /**
     * Insert or overwrite the season row, keyed on (sport, season_start) since V46 (spec 056 F3: the old
     * {@code on conflict (season_start)} is refused by Postgres once the primary key changes). Written last, in the
     * season's own transaction, so a row means it committed.
     */
    public void upsertSeason(SeasonRow s) {
        jdbc.update(con -> {
            PreparedStatement ps = con.prepareStatement("""
                    insert into historical_season (sport, season_start, label, source, opening_night,
                        last_counted_date, week_count, game_count, counted_stats, excluded_stats, stat_coverage,
                        ranking_source, imported_at, expected_game_count, dropped_rows, source_sha256)
                    values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?::jsonb, ?::jsonb, ?, ?, ?, ?, ?)
                    on conflict (sport, season_start) do update set label = excluded.label, source = excluded.source,
                        opening_night = excluded.opening_night, last_counted_date = excluded.last_counted_date,
                        week_count = excluded.week_count, game_count = excluded.game_count,
                        counted_stats = excluded.counted_stats, excluded_stats = excluded.excluded_stats,
                        stat_coverage = excluded.stat_coverage, ranking_source = excluded.ranking_source,
                        imported_at = excluded.imported_at, expected_game_count = excluded.expected_game_count,
                        dropped_rows = excluded.dropped_rows, source_sha256 = excluded.source_sha256
                    """);
            ps.setString(1, s.sport());
            ps.setInt(2, s.seasonStart());
            ps.setString(3, s.label());
            ps.setString(4, s.source());
            ps.setObject(5, s.openingNight());
            ps.setObject(6, s.lastCountedDate());
            ps.setInt(7, s.weekCount());
            ps.setInt(8, s.gameCount());
            ps.setArray(9, con.createArrayOf("text", s.countedStats().toArray()));
            ps.setString(10, s.excludedStatsJson());
            ps.setString(11, s.statCoverageJson());
            ps.setString(12, s.rankingSource());
            ps.setObject(13, s.importedAt());
            setInt(ps, 14, s.expectedGameCount());
            ps.setInt(15, s.droppedRows());
            ps.setString(16, s.sourceSha256());
            return ps;
        });
    }

    public Optional<SeasonRow> findSeason(String sport, int seasonStart) {
        return db.sql(SEASON_SELECT + " where s.sport = ? and s.season_start = ?")
                .params(sport, seasonStart)
                .query(this::mapSeason)
                .optional();
    }

    /**
     * The seasons a user may start in one sport: the season row has committed AND the prior season's card lines
     * exist (plan F14), so a half-imported season is never offered. The prior-lines check reads the sport's own
     * line table, so a basketball line can never make a football season look offered. Ascending.
     */
    public List<SeasonRow> offeredSeasons(String sport) {
        return switch (sport) {
            case "NBA" -> offeredSeasons(sport, NBA_FIRST_OFFERED, NBA_LAST_OFFERED);
            // Football's range is configuration (historical-nfl.first-offered / last-offered), read in exactly one
            // place, NflHistoricalSport. It is not repeated here (spec 056 T020).
            case "NFL" -> throw new IllegalArgumentException(
                    "football's offered range is configured: use HistoricalSports.get(\"NFL\").offeredSeasons()");
            default -> throw new IllegalArgumentException("unknown sport: " + sport);
        };
    }

    public static final int NBA_FIRST_OFFERED = 1970, NBA_LAST_OFFERED = 2025;

    /** The same rule over an explicit range (the integration test uses synthetic seasons outside the real one). */
    public List<SeasonRow> offeredSeasons(String sport, int firstSeason, int lastSeason) {
        // The table/column come from a closed switch, never from the caller's string.
        String priorLines = switch (sport) {
            case "NBA" -> "select 1 from historical_player_season p where p.season_start = s.season_start - 1";
            case "NFL" -> "select 1 from historical_nfl_player_season p where p.season = s.season_start - 1";
            default -> throw new IllegalArgumentException("unknown sport: " + sport);
        };
        return db.sql(SEASON_SELECT + " where s.sport = ? and s.season_start between ? and ? and exists ("
                + priorLines + ") order by s.season_start")
                .params(sport, firstSeason, lastSeason)
                .query(this::mapSeason)
                .list();
    }

    private static final String SEASON_SELECT = """
            select s.sport, s.season_start, s.label, s.source, s.opening_night, s.last_counted_date, s.week_count,
                   s.game_count, s.counted_stats, s.excluded_stats::text as excluded_stats,
                   s.stat_coverage::text as stat_coverage, s.ranking_source, s.imported_at,
                   s.expected_game_count, s.dropped_rows, s.source_sha256
            from historical_season s
            """;

    private SeasonRow mapSeason(ResultSet rs, int i) throws SQLException {
        return new SeasonRow(rs.getString("sport"), rs.getInt("season_start"), rs.getString("label"),
                rs.getString("source"), rs.getObject("opening_night", LocalDate.class),
                rs.getObject("last_counted_date", LocalDate.class),
                rs.getInt("week_count"), rs.getInt("game_count"), textArray(rs, "counted_stats"),
                rs.getString("excluded_stats"), rs.getString("stat_coverage"), rs.getString("ranking_source"),
                rs.getObject("imported_at", OffsetDateTime.class), nullableInt(rs, "expected_game_count"),
                rs.getInt("dropped_rows"), rs.getString("source_sha256"));
    }

    // ---- reads for play ------------------------------------------------------------------------------

    /** Every player's line for one season, with his name, ordered by player id (deterministic ties). */
    public List<NamedSeasonLine> seasonLines(int seasonStart) {
        return db.sql("""
                select p.name, l.player_id, l.season_start, l.team, l.age, l.games, l.positions,
                       l.pts, l.reb, l.ast, l.stl, l.blk, l."to" as tov, l.tpm, l.fgm, l.fga, l.ftm, l.fta
                from historical_player_season l join historical_player p on p.id = l.player_id
                where l.season_start = ?
                order by l.player_id
                """)
                .param(seasonStart)
                .query((rs, i) -> new NamedSeasonLine(rs.getString("name"), new SeasonLine(
                        rs.getLong("player_id"), rs.getInt("season_start"), rs.getString("team"),
                        nullableInt(rs, "age"), rs.getInt("games"), textArray(rs, "positions"),
                        nullableDouble(rs, "pts"), nullableDouble(rs, "reb"), nullableDouble(rs, "ast"),
                        nullableDouble(rs, "stl"), nullableDouble(rs, "blk"), nullableDouble(rs, "tov"),
                        nullableDouble(rs, "tpm"), nullableDouble(rs, "fgm"), nullableDouble(rs, "fga"),
                        nullableDouble(rs, "ftm"), nullableDouble(rs, "fta"))))
                .list();
    }

    /** Every player with at least one played game in the season (the board's candidates), by id. */
    public List<HistoricalBoardBuilder.Candidate> candidates(int seasonStart) {
        return db.sql("""
                select distinct p.id, p.name
                from historical_player_game g join historical_player p on p.id = g.player_id
                where g.season_start = ?
                order by p.id
                """)
                .param(seasonStart)
                .query((rs, i) -> new HistoricalBoardBuilder.Candidate(rs.getLong("id"), rs.getString("name")))
                .list();
    }

    /** The first game date seen in each fantasy week of a season, week -> date. */
    public java.util.Map<Integer, LocalDate> weekStarts(int seasonStart) {
        java.util.Map<Integer, LocalDate> out = new java.util.TreeMap<>();
        db.sql("select week, min(game_date) as d from historical_player_game where season_start = ? group by week")
                .param(seasonStart)
                .query((rs, i) -> {
                    out.put(rs.getInt("week"), rs.getObject("d", LocalDate.class));
                    return 0;
                })
                .list();
        return out;
    }

    /**
     * The played games of one season, optionally only for the given players ({@code null} means everyone),
     * ordered by (week, game date, player id, game id) so a replay is deterministic.
     */
    public List<GameLine> playedGames(int seasonStart, Collection<Long> playerIds) {
        String sql = """
                select player_id, game_id, season_start, game_date, week, team, pts, reb, ast, stl, blk,
                       "to" as tov, tpm, fgm, fga, ftm, fta, tf, ff, dd, td, bonus_pt_40p, bonus_pt_50p,
                       bonus_reb_20p, bonus_ast_15p
                from historical_player_game
                where season_start = ?
                """ + (playerIds == null ? "" : " and player_id = any(?)")
                + " order by week, game_date, player_id, game_id";
        if (playerIds != null && playerIds.isEmpty()) return List.of();
        return jdbc.query(con -> {
            PreparedStatement ps = con.prepareStatement(sql);
            ps.setInt(1, seasonStart);
            if (playerIds != null) ps.setArray(2, con.createArrayOf("bigint", playerIds.toArray()));
            return ps;
        }, (rs, i) -> new GameLine(rs.getLong("player_id"), rs.getLong("game_id"),
                rs.getInt("season_start"), rs.getObject("game_date", LocalDate.class), rs.getInt("week"),
                rs.getString("team"), nullableInt(rs, "pts"), nullableInt(rs, "reb"), nullableInt(rs, "ast"),
                nullableInt(rs, "stl"), nullableInt(rs, "blk"), nullableInt(rs, "tov"), nullableInt(rs, "tpm"),
                nullableInt(rs, "fgm"), nullableInt(rs, "fga"), nullableInt(rs, "ftm"), nullableInt(rs, "fta"),
                nullableInt(rs, "tf"), nullableInt(rs, "ff"), nullableInt(rs, "dd"), nullableInt(rs, "td"),
                nullableInt(rs, "bonus_pt_40p"), nullableInt(rs, "bonus_pt_50p"),
                nullableInt(rs, "bonus_reb_20p"), nullableInt(rs, "bonus_ast_15p")));
    }

    // ---- import jobs and staging ---------------------------------------------------------------------

    public long createJob(String part, OffsetDateTime startedAt) {
        return db.sql("insert into historical_import_job (part, status, started_at) values (?, 'RUNNING', ?) returning id")
                .params(part, startedAt)
                .query(Long.class)
                .single();
    }

    /** Close a job as {@code DONE} or {@code FAILED} with its report. */
    public void finishJob(long id, String status, OffsetDateTime finishedAt, String reportJson) {
        db.sql("update historical_import_job set status = ?, finished_at = ?, report = ?::jsonb where id = ?")
                .params(status, finishedAt, reportJson, id)
                .update();
    }

    /** Refresh a running job's report so a poller sees progress. */
    public void updateJobReport(long id, String reportJson) {
        db.sql("update historical_import_job set report = ?::jsonb where id = ?")
                .params(reportJson, id)
                .update();
    }

    public Optional<ImportJobRow> findJob(long id) {
        return db.sql("select id, part, status, started_at, finished_at, report::text as report from historical_import_job where id = ?")
                .param(id)
                .query((rs, i) -> new ImportJobRow(rs.getLong("id"), rs.getString("part"), rs.getString("status"),
                        rs.getObject("started_at", OffsetDateTime.class),
                        rs.getObject("finished_at", OffsetDateTime.class), rs.getString("report")))
                .optional();
    }

    public void truncateStaging() {
        jdbc.execute("truncate table historical_kaggle_staging");
    }

    // ---- binding helpers -----------------------------------------------------------------------------

    private static void setInt(PreparedStatement ps, int idx, Integer v) throws SQLException {
        if (v == null) ps.setNull(idx, Types.INTEGER);
        else ps.setInt(idx, v);
    }

    private static void setSmall(PreparedStatement ps, int idx, Integer v) throws SQLException {
        if (v == null) ps.setNull(idx, Types.SMALLINT);
        else ps.setShort(idx, v.shortValue());
    }

    private static void setNum(PreparedStatement ps, int idx, Double v) throws SQLException {
        if (v == null) ps.setNull(idx, Types.NUMERIC);
        else ps.setDouble(idx, v);
    }

    private static List<String> textArray(ResultSet rs, String col) throws SQLException {
        Array a = rs.getArray(col);
        return a == null ? List.of() : List.of((String[]) a.getArray());
    }

    private static Integer nullableInt(ResultSet rs, String col) throws SQLException {
        int v = rs.getInt(col);
        return rs.wasNull() ? null : v;
    }

    private static Double nullableDouble(ResultSet rs, String col) throws SQLException {
        double v = rs.getDouble(col);
        return rs.wasNull() ? null : v;
    }
}
