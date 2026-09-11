package com.ballknowers.draftsim.store;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.util.List;

/**
 * Snapshots, not a recomputed-on-read view (claude/league-suite.md's storage
 * sketch): "up 3 spots since last week" needs history, and the inputs
 * (rosters, the board, matchup results) move underneath you. Saving a
 * (league, season, week, kind) again replaces that snapshot -- overwriting is
 * exactly what re-running ingest for the still-moving current week wants.
 */
@Repository
public class PowerRankingRepository {

    private final JdbcClient db;

    public PowerRankingRepository(JdbcClient db) {
        this.db = db;
    }

    public record Entry(int rosterId, Long managerId, int rank, Double score, String note) {}

    /**
     * @return true if a snapshot was written, false if {@code entries} was empty
     *         and the call was refused.
     *
     * An empty snapshot is not a fact worth storing, and writing one was worse
     * than merely useless. The sequence below is insert-or-touch, delete every
     * entry, re-insert: with nothing to re-insert, a recompute that found no
     * data DELETED a previously good snapshot's entries and left a hollow
     * power_ranking row behind. So "we have no scoring for this week yet" could
     * silently destroy last week's real ranking.
     *
     * Refusing here rather than only at the call site because the damage is a
     * property of this method's shape, not of any one caller's judgment -- a
     * third compute mode added later would inherit the same trap.
     */
    public boolean save(long leagueId, int season, int week, String kind, List<Entry> entries) {
        if (entries == null || entries.isEmpty()) return false;
        Long rankingId = db.sql("""
                insert into power_ranking (league_id, season, week, kind)
                values (?, ?, ?, ?)
                on conflict (league_id, season, week, kind) do update set created_at = now()
                returning id
                """)
                .params(leagueId, season, week, kind)
                .query(Long.class)
                .single();

        db.sql("delete from power_ranking_entry where ranking_id = ?").param(rankingId).update();
        for (Entry e : entries) {
            db.sql("""
                    insert into power_ranking_entry (ranking_id, roster_id, manager_id, rank, score, note)
                    values (?, ?, ?, ?, ?, ?)
                    """)
                    .params(rankingId, e.rosterId(), e.managerId(), e.rank(), e.score(), e.note())
                    .update();
        }
        return true;
    }

    /**
     * Whether a snapshot already exists at this exact (league, season, week,
     * kind) -- used to seed week 0 (the preseason baseline) write-once rather
     * than recomputing and silently overwriting it on every later "Compute"
     * click, the way every other week's snapshot is meant to move.
     */
    public boolean exists(long leagueId, int season, int week, String kind) {
        return db.sql("select count(*) from power_ranking where league_id = ? and season = ? and week = ? and kind = ?")
                .params(leagueId, season, week, kind)
                .query(Integer.class)
                .single() > 0;
    }

    public record SnapshotRow(int season, int week, String kind, int rosterId, Long managerId,
                              String managerName, int rank, Double score, String note) {}

    /** Every snapshot for a league, across every season/week/kind ingested -- one payload, client-side toggle. */
    public List<SnapshotRow> forLeague(long leagueId) {
        return db.sql("""
                select pr.season, pr.week, pr.kind, e.roster_id, e.manager_id, m.display_name,
                       e.rank, e.score, e.note
                from power_ranking pr
                join power_ranking_entry e on e.ranking_id = pr.id
                left join manager m on m.id = e.manager_id
                where pr.league_id = ?
                order by pr.season, pr.week, pr.kind, e.rank
                """)
                .param(leagueId)
                .query((rs, i) -> new SnapshotRow(rs.getInt(1), rs.getInt(2), rs.getString(3),
                        rs.getInt(4), rs.getObject(5) == null ? null : rs.getLong(5), rs.getString(6),
                        rs.getInt(7), rs.getObject(8) == null ? null : rs.getDouble(8), rs.getString(9)))
                .list();
    }
}
