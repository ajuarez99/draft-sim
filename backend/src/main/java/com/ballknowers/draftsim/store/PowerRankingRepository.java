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

    public void save(long leagueId, int season, int week, String kind, List<Entry> entries) {
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
