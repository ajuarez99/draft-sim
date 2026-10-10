package com.ballknowers.draftsim.store;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/**
 * A person's stated draft targets (spec 024, V30 {@code draft_target}). One ordered list per
 * owner and draft: either a real Sleeper draft (shared by its live and projection rooms) or a
 * mock session. The list is written as a whole ({@link #replace}), never edited in place.
 */
@Repository
public class DraftTargetRepository {

    /** Where a list lives. Exactly one of the two, mirroring the table's check constraint. */
    public sealed interface TargetScope permits DraftScope, MockScope {}

    /** A real Sleeper draft, keyed by the text id (deliberately not an FK: see V30). */
    public record DraftScope(String sleeperDraftId) implements TargetScope {}

    public record MockScope(long mockSessionId) implements TargetScope {}

    /** One target, with the player's identity joined in so callers need no second lookup. */
    public record Target(long playerId, String sleeperId, String name) {}

    private final JdbcClient db;
    private final JdbcTemplate jdbc;

    public DraftTargetRepository(JdbcClient db, JdbcTemplate jdbc) {
        this.db = db;
        this.jdbc = jdbc;
    }

    /** The owner's list for this scope, in rank order. */
    public List<Target> list(String owner, TargetScope scope) {
        String filter = switch (scope) {
            case DraftScope d -> "t.sleeper_draft_id = ?";
            case MockScope m -> "t.mock_session_id = ?";
        };
        var q = db.sql("""
                select t.player_id, p.sleeper_id, p.name
                from draft_target t join player p on p.id = t.player_id
                where t.owner_sleeper_user_id = ?""" + " and " + filter + " order by t.rank")
                .param(owner);
        q = switch (scope) {
            case DraftScope d -> q.param(d.sleeperDraftId());
            case MockScope m -> q.param(m.mockSessionId());
        };
        return q.query((rs, i) -> new Target(rs.getLong(1), rs.getString(2), rs.getString(3))).list();
    }

    /**
     * Replaces the owner's whole list for this scope; {@code playerIds} order is rank 0..n-1.
     *
     * <p>The advisory lock is what makes two simultaneous saves safe (research A8): without it both
     * would delete, then both insert, and the second insert trips the partial unique index (a 500).
     * With it the saves serialize and the last one wins, which is what the spec allows.
     */
    @Transactional
    public void replace(String owner, TargetScope scope, List<Long> playerIds) {
        jdbc.queryForList("select pg_advisory_xact_lock(hashtext(?))", lockKey(owner, scope));
        switch (scope) {
            case DraftScope d -> {
                jdbc.update("delete from draft_target where owner_sleeper_user_id = ? and sleeper_draft_id = ?",
                        owner, d.sleeperDraftId());
                insert("""
                        insert into draft_target (owner_sleeper_user_id, sleeper_draft_id, player_id, rank)
                        values (?, ?, ?, ?)""", owner, d.sleeperDraftId(), playerIds);
            }
            case MockScope m -> {
                jdbc.update("delete from draft_target where owner_sleeper_user_id = ? and mock_session_id = ?",
                        owner, m.mockSessionId());
                insert("""
                        insert into draft_target (owner_sleeper_user_id, mock_session_id, player_id, rank)
                        values (?, ?, ?, ?)""", owner, m.mockSessionId(), playerIds);
            }
        }
    }

    private void insert(String sql, String owner, Object scopeValue, List<Long> playerIds) {
        if (playerIds.isEmpty()) return;
        jdbc.batchUpdate(sql, java.util.stream.IntStream.range(0, playerIds.size()).boxed().toList(), 500,
                (ps, i) -> {
                    ps.setString(1, owner);
                    if (scopeValue instanceof Long l) ps.setLong(2, l); else ps.setString(2, (String) scopeValue);
                    ps.setLong(3, playerIds.get(i));
                    ps.setInt(4, i);
                });
    }

    /**
     * Copies the owner's list for a real draft onto a new mock (the fork). Call it inside the
     * transaction that created the mock; the mock id is new, so there is nothing to lock against.
     */
    @Transactional
    public void copyDraftToMock(String owner, String sleeperDraftId, long mockSessionId) {
        jdbc.update("""
                insert into draft_target (owner_sleeper_user_id, mock_session_id, player_id, rank)
                select owner_sleeper_user_id, ?, player_id, rank
                from draft_target where owner_sleeper_user_id = ? and sleeper_draft_id = ?""",
                mockSessionId, owner, sleeperDraftId);
    }

    private static String lockKey(String owner, TargetScope scope) {
        return owner + ":" + switch (scope) {
            case DraftScope d -> "draft:" + d.sleeperDraftId();
            case MockScope m -> "mock:" + m.mockSessionId();
        };
    }
}
