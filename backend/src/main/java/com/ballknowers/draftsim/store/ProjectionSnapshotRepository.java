package com.ballknowers.draftsim.store;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.Statement;
import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Frozen pre-game projections (specs/036-projection-foundation data-model E1,
 * {@code V36__projection_snapshot.sql}).
 *
 * <p>The freeze lives in the table, not here: {@code check (fetched_at < cutoff_at)} rejects a late
 * insert and a {@code BEFORE UPDATE} trigger skips any update once {@code now() >= cutoff_at}
 * (plan amendment B2). This class only makes the ordinary path well-behaved:
 * <ul>
 *   <li>the batch is deduplicated by (sport, season, player, game_id) first, because two identical
 *       keys in one {@code insert ... on conflict} statement throw (review N1: per-position calls
 *       return duplicates);</li>
 *   <li>a row the trigger or the {@code where} clause leaves alone reports an update count of 0, which
 *       is what separates {@code written} from {@code frozenSkipped};</li>
 *   <li>a row whose {@code fetched_at} is not before its cutoff is NOT handled here -- the check
 *       constraint runs before {@code on conflict} is considered and aborts the whole batch, so
 *       {@code ProjectionSnapshotService} drops such rows before calling.</li>
 * </ul>
 * {@code stats} binds as {@code ?::jsonb} from a JSON string and timestamps as
 * {@link OffsetDateTime}, never {@code java.sql.Timestamp} (AGENTS.md bug class 3).
 */
@Repository
public class ProjectionSnapshotRepository {

    private final JdbcTemplate jdbc;

    public ProjectionSnapshotRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * @param stats          the projected line, adp keys already stripped
     * @param seasonAvgStats NBA only (research R6); null for football and for a player with no season line
     */
    public record Row(String sport, int season, int week, String sleeperPlayerId, String gameId,
                      LocalDate gameDate, String opponent, String team,
                      Map<String, Object> stats, Map<String, Object> seasonAvgStats,
                      String source, Instant sourceModifiedAt, Instant fetchedAt, Instant cutoffAt) {

        String key() {
            return sport + '\u0000' + season + '\u0000' + sleeperPlayerId + '\u0000' + gameId;
        }
    }

    /** @param written rows inserted or refreshed; @param frozenSkipped rows the table refused to change */
    public record UpsertResult(int written, int frozenSkipped) {}

    public UpsertResult upsertPreGame(List<Row> rows) {
        if (rows.isEmpty()) return new UpsertResult(0, 0);
        // Last one wins; LinkedHashMap keeps first-seen order for a stable batch.
        Map<String, Row> deduped = new LinkedHashMap<>();
        for (Row r : rows) deduped.put(r.key(), r);
        List<Row> batchRows = List.copyOf(deduped.values());

        int[][] perBatch = jdbc.batchUpdate("""
                insert into projection_snapshot
                    (sport, season, week, sleeper_player_id, game_id, game_date, opponent, team,
                     stats, season_avg_stats, source, source_modified_at, fetched_at, cutoff_at)
                values (?, ?, ?, ?, ?, ?, ?, ?, ?::jsonb, ?::jsonb, ?, ?, ?, ?)
                on conflict (sport, season, sleeper_player_id, game_id) do update set
                    week = excluded.week,
                    game_date = excluded.game_date,
                    opponent = excluded.opponent,
                    team = excluded.team,
                    stats = excluded.stats,
                    season_avg_stats = excluded.season_avg_stats,
                    source = excluded.source,
                    source_modified_at = excluded.source_modified_at,
                    fetched_at = excluded.fetched_at
                where excluded.fetched_at < projection_snapshot.cutoff_at
                """,
                batchRows, 500,
                (ps, r) -> {
                    ps.setString(1, r.sport());
                    ps.setInt(2, r.season());
                    ps.setInt(3, r.week());
                    ps.setString(4, r.sleeperPlayerId());
                    ps.setString(5, r.gameId());
                    ps.setObject(6, r.gameDate());
                    ps.setString(7, r.opponent());
                    ps.setString(8, r.team());
                    ps.setString(9, JsonUtil.write(r.stats()));
                    ps.setString(10, r.seasonAvgStats() == null ? null : JsonUtil.write(r.seasonAvgStats()));
                    ps.setString(11, r.source());
                    ps.setObject(12, utc(r.sourceModifiedAt()));
                    ps.setObject(13, utc(r.fetchedAt()));
                    ps.setObject(14, utc(r.cutoffAt()));
                });
        int[] counts = java.util.Arrays.stream(perBatch).flatMapToInt(java.util.Arrays::stream).toArray();

        int written = 0;
        for (int c : counts) {
            // SUCCESS_NO_INFO means the driver would not say; the statement ran without error, so count it written.
            if (c > 0 || c == Statement.SUCCESS_NO_INFO) written++;
        }
        return new UpsertResult(written, batchRows.size() - written);
    }

    public int count(String sport, int season) {
        Integer n = jdbc.queryForObject(
                "select count(*) from projection_snapshot where sport = ? and season = ?", Integer.class, sport, season);
        return n == null ? 0 : n;
    }

    /**
     * One snapshot with everything the accuracy read needs to classify and grade it (spec 036 T018).
     *
     * @param actual         the player's realized {@code player_game} line for this game_id; null when none is stored
     * @param scheduleStatus {@code sport_schedule.status} for this game_id; null when the game isn't in the schedule
     * @param position       first entry of {@code player.positions}; null for a player the app never ingested
     */
    public record Sample(String sleeperPlayerId, String gameId, LocalDate gameDate, String position,
                         Map<String, Object> stats, Map<String, Object> seasonAvgStats,
                         Map<String, Object> actual, String scheduleStatus, String source) {}

    /**
     * Every snapshot of the sport-season, left-joined to the realized game and the schedule row by
     * {@code game_id} (plan amendments S4/S5). One statement. {@code player_game} is unique on
     * (player, game_id) and {@code sport_schedule} on (sport, season, game_id), so the joins cannot
     * multiply rows; {@code player} is unique on (sport, sleeper_id).
     */
    public List<Sample> forAccuracy(String sport, int season) {
        return jdbc.query("""
                select s.sleeper_player_id, s.game_id, s.game_date, p.positions[1] as position,
                       s.stats::text as stats, s.season_avg_stats::text as season_avg_stats,
                       pg.stats::text as actual, sc.status as schedule_status, s.source
                from projection_snapshot s
                left join player_game pg
                       on pg.sleeper_player_id = s.sleeper_player_id and pg.game_id = s.game_id
                left join sport_schedule sc
                       on sc.sport = s.sport and sc.season = s.season and sc.game_id = s.game_id
                left join player p
                       on p.sport = s.sport and p.sleeper_id = s.sleeper_player_id
                where s.sport = ? and s.season = ?
                """,
                (rs, n) -> new Sample(
                        rs.getString("sleeper_player_id"), rs.getString("game_id"),
                        rs.getObject("game_date", LocalDate.class), rs.getString("position"),
                        JsonUtil.readMap(rs.getString("stats")),
                        rs.getString("season_avg_stats") == null ? null : JsonUtil.readMap(rs.getString("season_avg_stats")),
                        rs.getString("actual") == null ? null : JsonUtil.readMap(rs.getString("actual")),
                        rs.getString("schedule_status"), rs.getString("source")),
                sport, season);
    }

    /**
     * A cheap fingerprint of everything {@link #forAccuracy} reads for the sport-season: when and how many
     * snapshots, realized games and schedule rows there are. Two equal stamps mean the same accuracy inputs
     * (plan N3: the full read measured ~1.4 s at a season's 29k samples, the stamp ~30 ms). Position changes
     * are not stamped; they are rare and a stale position label lasts until the next data change.
     */
    public String stamp(String sport, int season) {
        return jdbc.queryForObject("""
                select concat_ws('|',
                    (select max(fetched_at) from projection_snapshot where sport = ? and season = ?),
                    (select count(*) from projection_snapshot where sport = ? and season = ?),
                    (select max(fetched_at) from player_game where sport = ? and season = ?),
                    (select count(*) from player_game where sport = ? and season = ?),
                    (select max(fetched_at) from sport_schedule where sport = ? and season = ?))
                """, String.class,
                sport, season, sport, season, sport, season, sport, season, sport, season);
    }

    private static OffsetDateTime utc(Instant i) {
        return i == null ? null : OffsetDateTime.ofInstant(i, ZoneOffset.UTC);
    }
}
