package com.ballknowers.draftsim.store;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.sql.Types;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Optional;

@Repository
public class DraftRepository {

    private final JdbcClient db;
    private final JdbcTemplate jdbc;

    public DraftRepository(JdbcClient db, JdbcTemplate jdbc) {
        this.db = db;
        this.jdbc = jdbc;
    }

    public long upsert(long leagueId, String sleeperDraftId, int season, int rounds, int teams,
                       String type, String status, Instant startTime, String slotToManagerJson) {
        return db.sql("""
                insert into draft (league_id, sleeper_draft_id, season, rounds, teams,
                                   draft_type, status, start_time, slot_to_manager)
                values (?, ?, ?, ?, ?, ?, ?, ?, ?::jsonb)
                on conflict (sleeper_draft_id) do update set
                    rounds = excluded.rounds,
                    teams = excluded.teams,
                    status = excluded.status,
                    start_time = excluded.start_time,
                    slot_to_manager = excluded.slot_to_manager
                returning id
                """)
                .param(1, leagueId).param(2, sleeperDraftId).param(3, season).param(4, rounds)
                .param(5, teams).param(6, type).param(7, status)
                .param(8, startTime == null ? null : OffsetDateTime.ofInstant(startTime, ZoneOffset.UTC), Types.TIMESTAMP_WITH_TIMEZONE)
                .param(9, slotToManagerJson)
                .query(Long.class)
                .single();
    }

    public record PickRow(long draftId, int pickNo, int round, int draftSlot,
                          Long managerId, Long playerId, Double adpAtTime) {}

    public void replacePicks(long draftId, List<PickRow> picks) {
        jdbc.update("delete from draft_pick where draft_id = ?", draftId);
        jdbc.batchUpdate("""
                insert into draft_pick (draft_id, pick_no, round, draft_slot, manager_id, player_id, adp_at_time)
                values (?, ?, ?, ?, ?, ?, ?)
                """,
                picks, 500, DraftRepository::bindPickRow);
    }

    /**
     * Re-upserts the full pick list every call — safe to call every poll tick.
     * adp_at_time is coalesced, not overwritten: a freshly-observed pick always
     * carries adp_at_time = null (BoardService backfills it later), and a naive
     * `= excluded.adp_at_time` would null out that backfill on every subsequent
     * tick for the rest of the draft.
     */
    public void upsertPicks(long draftId, List<PickRow> picks) {
        if (picks == null || picks.isEmpty()) return;
        jdbc.batchUpdate("""
                insert into draft_pick (draft_id, pick_no, round, draft_slot, manager_id, player_id, adp_at_time)
                values (?, ?, ?, ?, ?, ?, ?)
                on conflict (draft_id, pick_no) do update set
                    round = excluded.round,
                    draft_slot = excluded.draft_slot,
                    manager_id = excluded.manager_id,
                    player_id = excluded.player_id,
                    adp_at_time = coalesce(draft_pick.adp_at_time, excluded.adp_at_time)
                """,
                picks, 500, DraftRepository::bindPickRow);
    }

    private static void bindPickRow(java.sql.PreparedStatement ps, PickRow p) throws java.sql.SQLException {
        ps.setLong(1, p.draftId());
        ps.setInt(2, p.pickNo());
        ps.setInt(3, p.round());
        ps.setInt(4, p.draftSlot());
        if (p.managerId() == null) ps.setNull(5, Types.BIGINT); else ps.setLong(5, p.managerId());
        if (p.playerId() == null) ps.setNull(6, Types.BIGINT); else ps.setLong(6, p.playerId());
        if (p.adpAtTime() == null) ps.setNull(7, Types.NUMERIC); else ps.setDouble(7, p.adpAtTime());
    }

    /** Flips only status, without needing the full row this poller doesn't have on hand. */
    public void updateStatus(long draftId, String status) {
        jdbc.update("update draft set status = ? where id = ?", status, draftId);
    }

    public record DraftRow(long id, long leagueId, String sleeperDraftId, int season,
                           int rounds, int teams, String status, Map<String, Object> slotToManager) {}

    public Optional<DraftRow> bySleeperId(String sleeperDraftId) {
        return db.sql("""
                select id, league_id, sleeper_draft_id, season, rounds, teams, status, slot_to_manager::text
                from draft where sleeper_draft_id = ?
                """)
                .param(sleeperDraftId)
                .query((rs, i) -> new DraftRow(rs.getLong(1), rs.getLong(2), rs.getString(3),
                        rs.getInt(4), rs.getInt(5), rs.getInt(6), rs.getString(7),
                        JsonUtil.readMap(rs.getString(8))))
                .optional();
    }

    /** Completed picks for a draft, ordered. Used both for profiles and for resume-from-state. */
    public List<PickRow> picks(long draftId) {
        return db.sql("""
                select draft_id, pick_no, round, draft_slot, manager_id, player_id, adp_at_time
                from draft_pick where draft_id = ? order by pick_no
                """)
                .param(draftId)
                .query((rs, i) -> new PickRow(rs.getLong(1), rs.getInt(2), rs.getInt(3), rs.getInt(4),
                        rs.getObject(5) == null ? null : rs.getLong(5),
                        rs.getObject(6) == null ? null : rs.getLong(6),
                        rs.getObject(7) == null ? null : rs.getDouble(7)))
                .list();
    }

    public record DraftSummary(long id, String sleeperDraftId, long leagueId, String leagueName,
                               int season, int teams, int rounds, String status, Instant startTime) {}

    /** Every draft in the DB, joined to its league, newest first. Backs the app-shell picker screen. */
    public List<DraftSummary> allWithLeague() {
        return db.sql("""
                select d.id, d.sleeper_draft_id, d.league_id, l.name, d.season, d.teams, d.rounds,
                       d.status, d.start_time
                from draft d join league l on l.id = d.league_id
                order by d.start_time desc nulls last, d.season desc, d.id desc
                """)
                .query((rs, i) -> new DraftSummary(rs.getLong(1), rs.getString(2), rs.getLong(3),
                        rs.getString(4), rs.getInt(5), rs.getInt(6), rs.getInt(7), rs.getString(8),
                        rs.getTimestamp(9) == null ? null : rs.getTimestamp(9).toInstant()))
                .list();
    }

    /** Every completed pick across every ingested draft, for profile fitting. */
    public List<PickRow> allCompletedPicks() {
        return db.sql("""
                select p.draft_id, p.pick_no, p.round, p.draft_slot, p.manager_id, p.player_id, p.adp_at_time
                from draft_pick p join draft d on d.id = p.draft_id
                where d.status = 'complete' and p.manager_id is not null
                order by p.draft_id, p.pick_no
                """)
                .query((rs, i) -> new PickRow(rs.getLong(1), rs.getInt(2), rs.getInt(3), rs.getInt(4),
                        rs.getObject(5) == null ? null : rs.getLong(5),
                        rs.getObject(6) == null ? null : rs.getLong(6),
                        rs.getObject(7) == null ? null : rs.getDouble(7)))
                .list();
    }
}
