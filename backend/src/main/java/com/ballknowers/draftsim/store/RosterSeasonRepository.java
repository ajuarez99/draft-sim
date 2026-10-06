package com.ballknowers.draftsim.store;

import com.ballknowers.draftsim.domain.Sport;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.sql.Array;
import java.sql.PreparedStatement;
import java.sql.Types;
import java.time.OffsetDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * One row per (league, roster) per season -- standings, as Sleeper reports
 * them directly (claude/league-suite.md: "standings are a read, not a
 * computation"). Never written from anything the simulator reads.
 */
@Repository
public class RosterSeasonRepository {

    private final JdbcClient db;
    private final JdbcTemplate jdbc;

    public RosterSeasonRepository(JdbcClient db, JdbcTemplate jdbc) {
        this.db = db;
        this.jdbc = jdbc;
    }

    /**
     * @param players          the roster's player ids (players, reserve and taxi, de-duplicated); null
     *                         means "not known", an empty list means "known to be empty"
     * @param playersFetchedAt when {@code players} was read from Sleeper; null iff players is null
     */
    public record Upsert(long leagueId, Long managerId, int rosterId, Integer wins, Integer losses,
                         Integer ties, Double pointsFor, Double pointsAgainst, Double pointsPossible,
                         Integer finalPlacement, List<String> players, OffsetDateTime playersFetchedAt) {}

    /**
     * T025 (specs/006-deeper-history-both-sports): confirmed this upsert
     * CLEARS {@code final_placement}, it does not skip the row. Every row in
     * {@code rows} is always written, unconditionally -- there is no branch
     * above that omits a roster whose computed placement is null -- and
     * {@code final_placement = excluded.final_placement} is a plain column
     * assignment with no {@code coalesce}, so a null {@code Upsert.finalPlacement()}
     * overwrites whatever was stored before, including the two wrong
     * champions research R2 found (popsharky and gregmullen, crowned after
     * one week of a new season). This matters because the fix in
     * {@code LeagueHistoryIngestService#ingestStandings} depends on it: T023
     * gates the champion write on the season being complete, and that gate
     * only repairs the two already-wrong rows if THIS method still writes
     * them when the gate says no -- the same lesson this repo already
     * learned twice, as {@code adp_at_time} and the {@code league_matchup}
     * fixture gate: a skip gate in front of a column never repairs it.
     */
    public void upsertAll(List<Upsert> rows) {
        if (rows.isEmpty()) return;
        // JdbcTemplate + createArrayOf, as LeagueRepository#upsert does: a bare List/String[] bind relies on
        // the driver inferring text[] (spec 019 N4). Null stays null (never fetched).
        String sql = """
                insert into roster_season (league_id, manager_id, roster_id, wins, losses, ties,
                                           points_for, points_against, points_possible, final_placement,
                                           players, players_fetched_at)
                values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                on conflict (league_id, roster_id) do update set
                    manager_id = excluded.manager_id,
                    wins = excluded.wins,
                    losses = excluded.losses,
                    ties = excluded.ties,
                    points_for = excluded.points_for,
                    points_against = excluded.points_against,
                    points_possible = excluded.points_possible,
                    final_placement = excluded.final_placement,
                    players = excluded.players,
                    players_fetched_at = excluded.players_fetched_at
                """;
        for (Upsert r : rows) {
            jdbc.execute(sql, (PreparedStatement ps) -> {
                ps.setLong(1, r.leagueId());
                setLongOrNull(ps, 2, r.managerId());
                ps.setInt(3, r.rosterId());
                setIntOrNull(ps, 4, r.wins());
                setIntOrNull(ps, 5, r.losses());
                setIntOrNull(ps, 6, r.ties());
                setDoubleOrNull(ps, 7, r.pointsFor());
                setDoubleOrNull(ps, 8, r.pointsAgainst());
                setDoubleOrNull(ps, 9, r.pointsPossible());
                setIntOrNull(ps, 10, r.finalPlacement());
                if (r.players() == null) {
                    ps.setNull(11, Types.ARRAY);
                } else {
                    Array a = ps.getConnection().createArrayOf("text", r.players().toArray());
                    ps.setArray(11, a);
                }
                if (r.playersFetchedAt() == null) ps.setNull(12, Types.TIMESTAMP_WITH_TIMEZONE);
                else ps.setObject(12, r.playersFetchedAt());
                return ps.executeUpdate();
            });
        }
    }

    private static void setLongOrNull(PreparedStatement ps, int i, Long v) throws java.sql.SQLException {
        if (v == null) ps.setNull(i, Types.BIGINT); else ps.setLong(i, v);
    }

    private static void setIntOrNull(PreparedStatement ps, int i, Integer v) throws java.sql.SQLException {
        if (v == null) ps.setNull(i, Types.INTEGER); else ps.setInt(i, v);
    }

    private static void setDoubleOrNull(PreparedStatement ps, int i, Double v) throws java.sql.SQLException {
        if (v == null) ps.setNull(i, Types.NUMERIC); else ps.setDouble(i, v);
    }

    /**
     * Who is on a roster, one league: player id to roster id, and when it was read
     * (spec 019 data-model "Rostered").
     *
     * @param fetchedAt the MINIMUM {@code players_fetched_at} across the league's rows (N9): the page
     *                  can only claim as much freshness as its stalest roster
     */
    public record Rostered(Map<String, Integer> byPlayer, OffsetDateTime fetchedAt) {}

    private record PlayersRow(int rosterId, String[] players, OffsetDateTime fetchedAt) {}

    /**
     * Empty when the league has no rows or ANY row's {@code players} is null (never fetched): a partial
     * map would read the missing roster's players as free agents. A player on two rosters maps to the
     * first by roster id.
     */
    public Optional<Rostered> rosteredPlayers(long leagueId) {
        List<PlayersRow> rows = db.sql("""
                select roster_id, players, players_fetched_at
                from roster_season where league_id = ? order by roster_id
                """)
                .param(leagueId)
                .query((rs, i) -> {
                    Array a = rs.getArray(2);
                    return new PlayersRow(rs.getInt(1), a == null ? null : (String[]) a.getArray(),
                            rs.getObject(3, OffsetDateTime.class));
                })
                .list();
        if (rows.isEmpty()) return Optional.empty();
        Map<String, Integer> by = new HashMap<>();
        OffsetDateTime min = null;
        for (PlayersRow r : rows) {
            if (r.players() == null) return Optional.empty();
            for (String p : r.players()) by.putIfAbsent(p, r.rosterId());
            if (r.fetchedAt() != null && (min == null || r.fetchedAt().isBefore(min))) min = r.fetchedAt();
        }
        return Optional.of(new Rostered(by, min));
    }

    /**
     * season/sleeperLeagueId/sport/leagueName/complete are null from
     * {@link #forLeague} (the caller already knows all of them -- it asked
     * for this one league) and populated from {@link #forManager}, which
     * spans several leagues/seasons/sports and has no other way to tell its
     * rows apart, link back to one, or say which sport it was
     * (specs/006-deeper-history-both-sports research R1: extending this one
     * record via the existing {@code withSeason} flag rather than adding a
     * second row type -- a second "manager's standings row" is the bug class
     * this repo has now shipped under four different names).
     */
    public record StandingRow(long leagueId, int rosterId, Long managerId, String managerName, String avatarId,
                              Integer wins, Integer losses, Integer ties, Double pointsFor,
                              Double pointsAgainst, Integer finalPlacement, Integer season,
                              String sleeperLeagueId, Sport sport, String leagueName, Boolean complete) {}

    /** One league's standings, best placement (or most wins, if no bracket yet) first. */
    public List<StandingRow> forLeague(long leagueId) {
        return db.sql("""
                select rs.league_id, rs.roster_id, rs.manager_id, m.display_name, m.avatar_id,
                       rs.wins, rs.losses, rs.ties, rs.points_for, rs.points_against, rs.final_placement
                from roster_season rs
                left join manager m on m.id = rs.manager_id
                where rs.league_id = ?
                order by (rs.final_placement is null), rs.final_placement,
                         rs.wins desc nulls last, rs.points_for desc nulls last
                """)
                .param(leagueId)
                .query((rs, i) -> mapRow(rs, false))
                .list();
    }

    /**
     * One manager's record across every ingested season, newest first, now
     * carrying the sport and league name each row belongs to
     * (specs/006-deeper-history-both-sports US1) -- without these two, a
     * caller spanning several leagues/sports has no way to tell an NFL
     * season from an NBA one, which is exactly the defect baseline.md's T002
     * pinned: six rows, three leagues, two sports, nothing to tell them
     * apart.
     */
    public List<StandingRow> forManager(long managerId) {
        return db.sql("""
                select rs.league_id, rs.roster_id, rs.manager_id, m.display_name, m.avatar_id,
                       rs.wins, rs.losses, rs.ties, rs.points_for, rs.points_against, rs.final_placement,
                       l.season, l.sleeper_id, l.sport, l.name, l.status
                from roster_season rs
                join league l on l.id = rs.league_id
                left join manager m on m.id = rs.manager_id
                where rs.manager_id = ?
                order by l.season desc
                """)
                .param(managerId)
                .query((rs, i) -> mapRow(rs, true))
                .list();
    }

    /**
     * numeric(8,2) columns come back from pgjdbc as BigDecimal, not Double --
     * {@code (Double) rs.getObject(...)} throws a ClassCastException at
     * runtime despite compiling fine, the same class of bug as
     * claude/lessons.md's JDBC-bind entries, just on the read side. rs.getDouble
     * coerces correctly and getObject's null check still guards the nullable case.
     */
    private static StandingRow mapRow(java.sql.ResultSet rs, boolean withSeason) throws java.sql.SQLException {
        return new StandingRow(rs.getLong(1), rs.getInt(2),
                rs.getObject(3) == null ? null : rs.getLong(3), rs.getString(4), rs.getString(5),
                rs.getObject(6) == null ? null : rs.getInt(6),
                rs.getObject(7) == null ? null : rs.getInt(7),
                rs.getObject(8) == null ? null : rs.getInt(8),
                rs.getObject(9) == null ? null : rs.getDouble(9),
                rs.getObject(10) == null ? null : rs.getDouble(10),
                rs.getObject(11) == null ? null : rs.getInt(11),
                withSeason ? rs.getInt(12) : null,
                withSeason ? rs.getString(13) : null,
                withSeason ? Sport.fromCode(rs.getString(14)) : null,
                withSeason ? rs.getString(15) : null,
                // CALLS LeagueRepository.LeagueRow#isComplete rather than
                // re-deriving the rule here: a null status means not yet known
                // and must never read as complete. See that method's javadoc
                // for why -- popsharky and gregmullen were both crowned
                // champions of a 2026 season one week old. This line used to
                // spell the rule out again under a comment claiming it
                // mirrored that method, which is how two implementations of
                // one rule start.
                withSeason ? LeagueRepository.LeagueRow.isComplete(rs.getString(16)) : null);
    }
}
