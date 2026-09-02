package com.ballknowers.draftsim.store;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.sql.Array;
import java.sql.Types;
import java.util.List;
import java.util.Optional;

/**
 * Feature C's own tables (claude/next-features-roadmap.md §2a, Phase 3) --
 * deliberately separate from {@link DraftRepository}, with no FK or shared id
 * space between them, so a mock session can never be picked up by
 * {@link DraftRepository#allCompletedPicks()} / profile fitting.
 */
@Repository
public class MockDraftRepository {

    private final JdbcClient db;
    private final JdbcTemplate jdbc;

    public MockDraftRepository(JdbcClient db, JdbcTemplate jdbc) {
        this.db = db;
        this.jdbc = jdbc;
    }

    public record SessionRow(long id, String status, int teams, int rounds, List<String> rosterPositions,
                             double pointsPerReception, String seatsJson, int userSlot, long rngSeed,
                             int currentPickNo, Long sourceDraftId, Integer forkedAtPickNo) {}

    /** An ordinary from-scratch mock: no real draft behind it. */
    public long createSession(int teams, int rounds, List<String> rosterPositions, double ppr,
                              String seatsJson, int userSlot, long rngSeed) {
        return createSession(teams, rounds, rosterPositions, ppr, seatsJson, userSlot, rngSeed, null, null);
    }

    /**
     * @param sourceDraftId  the real draft this session was forked from
     *                       (mock/MockDraftService#createSessionFromDraft), or
     *                       null for an ordinary from-scratch mock.
     * @param forkedAtPickNo the first pick this session hadn't yet decided at
     *                       fork time -- meaningless (and null) when
     *                       sourceDraftId is null.
     */
    public long createSession(int teams, int rounds, List<String> rosterPositions, double ppr,
                              String seatsJson, int userSlot, long rngSeed,
                              Long sourceDraftId, Integer forkedAtPickNo) {
        return jdbc.execute((java.sql.Connection con) -> {
            Array slots = con.createArrayOf("text", rosterPositions.toArray());
            var ps = con.prepareStatement("""
                    insert into mock_draft_session
                        (teams, rounds, roster_positions, points_per_reception, seats_json, user_slot, rng_seed,
                         source_draft_id, forked_at_pick_no)
                    values (?, ?, ?, ?, ?::jsonb, ?, ?, ?, ?)
                    returning id
                    """);
            ps.setInt(1, teams);
            ps.setInt(2, rounds);
            ps.setArray(3, slots);
            ps.setDouble(4, ppr);
            ps.setString(5, seatsJson);
            ps.setInt(6, userSlot);
            ps.setLong(7, rngSeed);
            if (sourceDraftId == null) ps.setNull(8, Types.BIGINT); else ps.setLong(8, sourceDraftId);
            if (forkedAtPickNo == null) ps.setNull(9, Types.INTEGER); else ps.setInt(9, forkedAtPickNo);
            try (var rs = ps.executeQuery()) {
                return rs.next() ? rs.getLong(1) : null;
            }
        });
    }

    public Optional<SessionRow> find(long id) {
        return db.sql("""
                select id, status, teams, rounds, roster_positions, points_per_reception,
                       seats_json::text, user_slot, rng_seed, current_pick_no,
                       source_draft_id, forked_at_pick_no
                from mock_draft_session where id = ?
                """)
                .param(id)
                .query(this::mapSession)
                .optional();
    }

    /**
     * Locks the session row for the duration of the caller's transaction.
     * Every mutating operation (session creation's own bot-advance, and
     * submitting a user pick) opens with this, so two concurrent requests
     * against the same session serialize rather than racing to decide the
     * same pick twice.
     *
     * Deliberately NOT annotated {@code @Transactional} itself: that would
     * only join an already-open transaction if one exists, and silently start
     * and immediately commit (releasing the lock right back) a new one-call
     * transaction if not -- worse than no annotation at all, since it would
     * look safe while giving no actual guarantee. The caller
     * ({@link com.ballknowers.draftsim.mock.MockDraftService}'s own
     * {@code @Transactional} methods) owns the transaction boundary; this
     * call joins it via ordinary JdbcTemplate/JdbcClient thread-bound
     * connection sharing.
     */
    public Optional<SessionRow> lockForUpdate(long id) {
        return db.sql("""
                select id, status, teams, rounds, roster_positions, points_per_reception,
                       seats_json::text, user_slot, rng_seed, current_pick_no,
                       source_draft_id, forked_at_pick_no
                from mock_draft_session where id = ? for update
                """)
                .param(id)
                .query(this::mapSession)
                .optional();
    }

    private SessionRow mapSession(java.sql.ResultSet rs, int i) throws java.sql.SQLException {
        Array a = rs.getArray("roster_positions");
        List<String> slots = List.of((String[]) a.getArray());
        long sourceDraftId = rs.getLong("source_draft_id");
        Long sourceDraftIdBoxed = rs.wasNull() ? null : sourceDraftId;
        int forkedAtPickNo = rs.getInt("forked_at_pick_no");
        Integer forkedAtPickNoBoxed = rs.wasNull() ? null : forkedAtPickNo;
        return new SessionRow(rs.getLong("id"), rs.getString("status"), rs.getInt("teams"),
                rs.getInt("rounds"), slots, rs.getDouble("points_per_reception"),
                rs.getString("seats_json"), rs.getInt("user_slot"), rs.getLong("rng_seed"),
                rs.getInt("current_pick_no"), sourceDraftIdBoxed, forkedAtPickNoBoxed);
    }

    public void advanceCurrentPick(long id, int currentPickNo, String status) {
        jdbc.update("""
                update mock_draft_session set current_pick_no = ?, status = ?, updated_at = now()
                where id = ?
                """, currentPickNo, status, id);
    }

    public record PickRow(long sessionId, int pickNo, int round, int draftSlot, String seatType,
                          Long managerId, long playerId, String source) {}

    public void insertPicks(long sessionId, List<PickRow> picks) {
        if (picks == null || picks.isEmpty()) return;
        jdbc.batchUpdate("""
                insert into mock_draft_pick
                    (session_id, pick_no, round, draft_slot, seat_type, manager_id, player_id, source)
                values (?, ?, ?, ?, ?, ?, ?, ?)
                """,
                picks, 500, MockDraftRepository::bindPickRow);
    }

    private static void bindPickRow(java.sql.PreparedStatement ps, PickRow p) throws java.sql.SQLException {
        ps.setLong(1, p.sessionId());
        ps.setInt(2, p.pickNo());
        ps.setInt(3, p.round());
        ps.setInt(4, p.draftSlot());
        ps.setString(5, p.seatType());
        if (p.managerId() == null) ps.setNull(6, Types.BIGINT); else ps.setLong(6, p.managerId());
        ps.setLong(7, p.playerId());
        ps.setString(8, p.source());
    }

    public List<PickRow> picks(long sessionId) {
        return db.sql("""
                select session_id, pick_no, round, draft_slot, seat_type, manager_id, player_id, source
                from mock_draft_pick where session_id = ? order by pick_no
                """)
                .param(sessionId)
                .query((rs, i) -> new PickRow(rs.getLong(1), rs.getInt(2), rs.getInt(3), rs.getInt(4),
                        rs.getString(5), rs.getObject(6) == null ? null : rs.getLong(6),
                        rs.getLong(7), rs.getString(8)))
                .list();
    }

    public record SessionSummary(long id, String status, int teams, int rounds, int userSlot,
                                 int currentPickNo, java.time.Instant createdAt) {}

    /** Every mock session, newest first. Backs the picker screen's "Mock drafts" list. */
    public List<SessionSummary> allSessions() {
        return db.sql("""
                select id, status, teams, rounds, user_slot, current_pick_no, created_at
                from mock_draft_session order by created_at desc
                """)
                .query((rs, i) -> new SessionSummary(rs.getLong(1), rs.getString(2), rs.getInt(3),
                        rs.getInt(4), rs.getInt(5), rs.getInt(6), rs.getTimestamp(7).toInstant()))
                .list();
    }
}
