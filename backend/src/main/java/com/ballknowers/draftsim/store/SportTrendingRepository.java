package com.ballknowers.draftsim.store;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * Sleeper's trending-adds list per sport and the state of the fetch that produced it
 * (specs/014-home-player-spotlight, data-model.md, {@code V26__sport_trending.sql}).
 *
 * <p>A successful fetch replaces the list wholesale, in one transaction, together with the
 * fetch state. A failed fetch touches only the failure columns, so the previous list stays
 * readable with its true age. Timestamps bind as {@link OffsetDateTime} and the start date as
 * {@link LocalDate}, never {@code java.sql.Timestamp} (AGENTS.md lesson class 3).
 *
 * <p>Spec 051: every successful fetch also appends one row to {@code sport_trending_snapshot} (and
 * its entries), in the same transaction, so a pickup can later be judged against what was trending
 * at the time. History is never pruned and has no backfill ({@code V42__sport_trending_snapshot.sql}).
 */
@Repository
public class SportTrendingRepository {

    /** Fallback window stored on a first-ever failure, before any success has said what it was. */
    static final int DEFAULT_LOOKBACK_HOURS = 24;

    private final JdbcTemplate jdbc;

    public SportTrendingRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** One list row; {@code rank} is its position (1-based) in the list. */
    public record Entry(String sleeperPlayerId, int addCount) {}

    /** {@code fetchedAt}, {@code leagueSeason}, {@code seasonStartDate}, failure fields: nullable. */
    public record Snapshot(OffsetDateTime fetchedAt, int lookbackHours, Integer leagueSeason,
                           LocalDate seasonStartDate, OffsetDateTime lastFailureAt, String lastFailure,
                           List<Entry> entries) {}

    /** Spec 051: one stored snapshot's header. {@code entryCount == 0} means Sleeper sent an empty list. */
    public record TrendingSnapshotHeader(long id, OffsetDateTime fetchedAt, int lookbackHours, int entryCount) {}

    /** Spec 051: one player's place in one stored snapshot. */
    public record TrendingSnapshotRank(long snapshotId, String playerId, int rank, int addCount) {}

    @Transactional
    public void replace(String sport, int lookbackHours, Integer leagueSeason, LocalDate seasonStartDate,
                        List<Entry> entries, OffsetDateTime fetchedAt) {
        jdbc.update("""
                insert into sport_trending_fetch (sport, fetched_at, lookback_hours, league_season, season_start_date)
                values (?, ?, ?, ?, ?)
                on conflict (sport) do update set
                    fetched_at = excluded.fetched_at,
                    lookback_hours = excluded.lookback_hours,
                    league_season = excluded.league_season,
                    season_start_date = excluded.season_start_date
                """,
                ps -> {
                    ps.setString(1, sport);
                    ps.setObject(2, fetchedAt);
                    ps.setInt(3, lookbackHours);
                    if (leagueSeason == null) ps.setNull(4, java.sql.Types.INTEGER);
                    else ps.setInt(4, leagueSeason);
                    if (seasonStartDate == null) ps.setNull(5, java.sql.Types.DATE);
                    else ps.setObject(5, seasonStartDate);
                });
        jdbc.update("delete from sport_trending where sport = ?", sport);
        jdbc.batchUpdate("""
                insert into sport_trending (sport, rank, sleeper_player_id, add_count)
                values (?, ?, ?, ?)
                """,
                java.util.stream.IntStream.range(0, entries.size()).boxed().toList(), 100,
                (ps, i) -> {
                    Entry e = entries.get(i);
                    ps.setString(1, sport);
                    ps.setInt(2, i + 1);
                    ps.setString(3, e.sleeperPlayerId());
                    ps.setInt(4, e.addCount());
                });
        // Spec 051 data-model.md: one history row per successful fetch, same fetchedAt as the current row.
        Long snapshotId = jdbc.queryForObject("""
                insert into sport_trending_snapshot (sport, fetched_at, lookback_hours, entry_count)
                values (?, ?, ?, ?) returning id
                """,
                Long.class, sport, fetchedAt, lookbackHours, entries.size());
        jdbc.batchUpdate("""
                insert into sport_trending_snapshot_entry (snapshot_id, rank, sleeper_player_id, add_count)
                values (?, ?, ?, ?)
                """,
                java.util.stream.IntStream.range(0, entries.size()).boxed().toList(), 100,
                (ps, i) -> {
                    Entry e = entries.get(i);
                    ps.setLong(1, snapshotId);
                    ps.setInt(2, i + 1);
                    ps.setString(3, e.sleeperPlayerId());
                    ps.setInt(4, e.addCount());
                });
    }

    /** Spec 051: the sport's snapshot headers with {@code from <= fetched_at <= to}, oldest first. */
    public List<TrendingSnapshotHeader> snapshotHeaders(String sport, OffsetDateTime from, OffsetDateTime to) {
        return jdbc.query("""
                select id, fetched_at, lookback_hours, entry_count
                from sport_trending_snapshot
                where sport = ? and fetched_at >= ? and fetched_at <= ?
                order by fetched_at, id
                """,
                (rs, n) -> new TrendingSnapshotHeader(rs.getLong("id"),
                        rs.getObject("fetched_at", OffsetDateTime.class),
                        rs.getInt("lookback_hours"), rs.getInt("entry_count")),
                sport, from, to);
    }

    /**
     * Spec 051 F4: only the rows the judge can use -- the given snapshots, rank {@code <= topN}, the
     * given players -- never the whole entry table. Arrays bind via {@code createArrayOf}
     * (AGENTS.md lesson class 3); empty input returns without querying.
     */
    public List<TrendingSnapshotRank> snapshotRanks(List<Long> snapshotIds, int topN, Collection<String> playerIds) {
        if (snapshotIds.isEmpty() || playerIds.isEmpty()) return List.of();
        return jdbc.execute((java.sql.Connection con) -> {
            java.sql.Array ids = con.createArrayOf("bigint", snapshotIds.toArray());
            java.sql.Array players = con.createArrayOf("text", playerIds.toArray());
            try (java.sql.PreparedStatement ps = con.prepareStatement("""
                    select snapshot_id, sleeper_player_id, rank, add_count
                    from sport_trending_snapshot_entry
                    where snapshot_id = any(?) and rank <= ? and sleeper_player_id = any(?)
                    order by snapshot_id, rank
                    """)) {
                ps.setArray(1, ids);
                ps.setInt(2, topN);
                ps.setArray(3, players);
                List<TrendingSnapshotRank> out = new ArrayList<>();
                try (java.sql.ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        out.add(new TrendingSnapshotRank(rs.getLong(1), rs.getString(2), rs.getInt(3), rs.getInt(4)));
                    }
                }
                return out;
            } finally {
                ids.free();
                players.free();
            }
        });
    }

    /** Spec 051: when history began for the sport (any snapshot, even an empty one); empty if none. */
    public Optional<OffsetDateTime> earliestSnapshotAt(String sport) {
        return jdbc.query("select min(fetched_at) from sport_trending_snapshot where sport = ?",
                (rs, n) -> rs.getObject(1, OffsetDateTime.class), sport).stream()
                .filter(Objects::nonNull).findFirst();
    }

    /** Leaves {@code fetched_at} and the list alone; a first-ever failure inserts a row with null {@code fetched_at}. */
    public void recordFailure(String sport, OffsetDateTime at, String reason) {
        jdbc.update("""
                insert into sport_trending_fetch (sport, lookback_hours, last_failure_at, last_failure)
                values (?, ?, ?, ?)
                on conflict (sport) do update set
                    last_failure_at = excluded.last_failure_at,
                    last_failure = excluded.last_failure
                """,
                ps -> {
                    ps.setString(1, sport);
                    ps.setInt(2, DEFAULT_LOOKBACK_HOURS);
                    ps.setObject(3, at);
                    ps.setString(4, reason);
                });
    }

    /** One read transaction, so the list and the fetch row come from the same snapshot (via the Spring proxy only). */
    @Transactional(readOnly = true)
    public Optional<Snapshot> read(String sport) {
        List<Entry> entries = jdbc.query(
                "select sleeper_player_id, add_count from sport_trending where sport = ? order by rank",
                (rs, n) -> new Entry(rs.getString("sleeper_player_id"), rs.getInt("add_count")),
                sport);
        return jdbc.query("""
                select fetched_at, lookback_hours, league_season, season_start_date, last_failure_at, last_failure
                from sport_trending_fetch where sport = ?
                """,
                (rs, n) -> {
                    int season = rs.getInt("league_season");
                    Integer leagueSeason = rs.wasNull() ? null : season;
                    return new Snapshot(
                            rs.getObject("fetched_at", OffsetDateTime.class),
                            rs.getInt("lookback_hours"),
                            leagueSeason,
                            rs.getObject("season_start_date", LocalDate.class),
                            rs.getObject("last_failure_at", OffsetDateTime.class),
                            rs.getString("last_failure"),
                            entries);
                },
                sport).stream().findFirst();
    }
}
