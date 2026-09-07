package com.ballknowers.draftsim.store;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.util.List;

/**
 * One row per (league, roster) per season -- standings, as Sleeper reports
 * them directly (claude/league-suite.md: "standings are a read, not a
 * computation"). Never written from anything the simulator reads.
 */
@Repository
public class RosterSeasonRepository {

    private final JdbcClient db;

    public RosterSeasonRepository(JdbcClient db) {
        this.db = db;
    }

    public record Upsert(long leagueId, Long managerId, int rosterId, Integer wins, Integer losses,
                         Integer ties, Double pointsFor, Double pointsAgainst, Double pointsPossible,
                         Integer finalPlacement) {}

    public void upsertAll(List<Upsert> rows) {
        if (rows.isEmpty()) return;
        for (Upsert r : rows) {
            db.sql("""
                    insert into roster_season (league_id, manager_id, roster_id, wins, losses, ties,
                                               points_for, points_against, points_possible, final_placement)
                    values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                    on conflict (league_id, roster_id) do update set
                        manager_id = excluded.manager_id,
                        wins = excluded.wins,
                        losses = excluded.losses,
                        ties = excluded.ties,
                        points_for = excluded.points_for,
                        points_against = excluded.points_against,
                        points_possible = excluded.points_possible,
                        final_placement = excluded.final_placement
                    """)
                    .params(r.leagueId(), r.managerId(), r.rosterId(), r.wins(), r.losses(), r.ties(),
                            r.pointsFor(), r.pointsAgainst(), r.pointsPossible(), r.finalPlacement())
                    .update();
        }
    }

    /**
     * season/sleeperLeagueId are null from {@link #forLeague} (the caller
     * already knows both -- it asked for this one league) and populated from
     * {@link #forManager}, which spans several leagues/seasons and has no
     * other way to tell its rows apart or link back to one.
     */
    public record StandingRow(long leagueId, int rosterId, Long managerId, String managerName,
                              Integer wins, Integer losses, Integer ties, Double pointsFor,
                              Double pointsAgainst, Integer finalPlacement, Integer season,
                              String sleeperLeagueId) {}

    /** One league's standings, best placement (or most wins, if no bracket yet) first. */
    public List<StandingRow> forLeague(long leagueId) {
        return db.sql("""
                select rs.league_id, rs.roster_id, rs.manager_id, m.display_name,
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

    /** One manager's record across every ingested season, newest first. */
    public List<StandingRow> forManager(long managerId) {
        return db.sql("""
                select rs.league_id, rs.roster_id, rs.manager_id, m.display_name,
                       rs.wins, rs.losses, rs.ties, rs.points_for, rs.points_against, rs.final_placement,
                       l.season, l.sleeper_id
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
                rs.getObject(3) == null ? null : rs.getLong(3), rs.getString(4),
                rs.getObject(5) == null ? null : rs.getInt(5),
                rs.getObject(6) == null ? null : rs.getInt(6),
                rs.getObject(7) == null ? null : rs.getInt(7),
                rs.getObject(8) == null ? null : rs.getDouble(8),
                rs.getObject(9) == null ? null : rs.getDouble(9),
                rs.getObject(10) == null ? null : rs.getInt(10),
                withSeason ? rs.getInt(11) : null,
                withSeason ? rs.getString(12) : null);
    }
}
