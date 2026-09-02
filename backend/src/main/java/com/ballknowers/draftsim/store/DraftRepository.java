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

    /**
     * Makes {@code draft_pick} match {@code picks} exactly, without destroying
     * adp_at_time.
     *
     * This used to be a literal delete-then-insert, and the insert bound
     * {@code PickRow.adpAtTime()}, which {@code PickMapper} hardcodes to null. So
     * any league ingest run after a board rebuild silently zeroed the
     * contemporaneous board position on every pick in that league, which zeroed
     * every fitted manager profile behind it — HANDOFF's "Known live bug", and
     * one click of the UI's own "Add a draft" button was enough to trigger it.
     *
     * The fix is a shape change, not an {@code on conflict} clause: line-one's
     * delete makes any conflict unreachable. Prune only the picks that are
     * genuinely gone, then hand the rest to {@link #upsertPicks}, whose
     * {@code coalesce} already gets this right. Coalescing a stale value is safe
     * because {@code BoardService.backfillAdpAtTime} overwrites unconditionally
     * rather than only filling nulls.
     */
    public void replacePicks(long draftId, List<PickRow> picks) {
        if (picks == null || picks.isEmpty()) {
            jdbc.update("delete from draft_pick where draft_id = ?", draftId);
            return;
        }
        Integer[] keep = picks.stream().map(PickRow::pickNo).toArray(Integer[]::new);
        // createArrayOf rather than binding a bare array and letting pgjdbc infer
        // the SQL type — same reasoning as LeagueRepository's text[] binding
        // (claude/lessons.md #4): nothing left to infer.
        jdbc.update(con -> {
            var ps = con.prepareStatement(
                    "delete from draft_pick where draft_id = ? and not (pick_no = any (?))");
            ps.setLong(1, draftId);
            ps.setArray(2, con.createArrayOf("integer", keep));
            return ps;
        });
        upsertPicks(draftId, picks);
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

    /**
     * Flips only the seat map, same shape and same reason as {@link #updateStatus}:
     * {@link #upsert} would demand league/season/rounds/teams/type/startTime that
     * LiveDraftPoller does not carry, and its {@code on conflict} sets start_time
     * from the incoming row — so reusing it would null out a start time the poller
     * never had.
     */
    public void updateSlotToManager(long draftId, String slotToManagerJson) {
        jdbc.update("update draft set slot_to_manager = ?::jsonb where id = ?", slotToManagerJson, draftId);
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
    /**
     * A completed pick together with the shape of the draft it came from.
     *
     * The shape is the whole point: pick 30 is a different fraction of a
     * 12-team draft than of a 14-team one, and profile fitting has to normalize
     * on that before it can pool picks across leagues of different sizes. It is
     * joined on rather than stored, since it is a property of the draft, not of
     * the pick.
     */
    public record CompletedPick(long draftId, int pickNo, int round, int draftSlot,
                                Long managerId, Long playerId, Double adpAtTime,
                                int teams, int rounds) {

        public int totalPicks() {
            return teams * rounds;
        }
    }

    public List<CompletedPick> allCompletedPicks() {
        return db.sql("""
                select p.draft_id, p.pick_no, p.round, p.draft_slot, p.manager_id, p.player_id,
                       p.adp_at_time, d.teams, d.rounds
                from draft_pick p join draft d on d.id = p.draft_id
                where d.status = 'complete' and p.manager_id is not null
                order by p.draft_id, p.pick_no
                """)
                .query((rs, i) -> new CompletedPick(rs.getLong(1), rs.getInt(2), rs.getInt(3), rs.getInt(4),
                        rs.getObject(5) == null ? null : rs.getLong(5),
                        rs.getObject(6) == null ? null : rs.getLong(6),
                        rs.getObject(7) == null ? null : rs.getDouble(7),
                        rs.getInt(8), rs.getInt(9)))
                .list();
    }
}
