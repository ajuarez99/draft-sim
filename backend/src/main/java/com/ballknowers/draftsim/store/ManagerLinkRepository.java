package com.ballknowers.draftsim.store;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

/**
 * A signed-in (Sleeper) manager's claim on an ESPN member (spec 035, plan F3/F18).
 *
 * <p>One ESPN manager can be claimed by at most one signed-in manager (unique on
 * {@code linked_manager_id}). A link is per ESPN member, not per league: it reaches every stored
 * ESPN league that member plays in, which is correct because that member is the user.
 * {@code league_id} is provenance only (the season the claim was made in).
 */
@Repository
public class ManagerLinkRepository {

    /** The ESPN manager is already linked to a different signed-in manager. */
    public static final class AlreadyClaimed extends RuntimeException {
        public AlreadyClaimed() {
            super("ALREADY_CLAIMED");
        }
    }

    private final JdbcClient db;

    public ManagerLinkRepository(JdbcClient db) {
        this.db = db;
    }

    /**
     * Idempotent for the same pair. Throws {@link AlreadyClaimed} if {@code linkedManagerId} is
     * held by any other {@code managerId}.
     */
    public void link(long managerId, long linkedManagerId, long leagueId) {
        Optional<Long> holder = holderOf(linkedManagerId);
        if (holder.isPresent() && holder.get() != managerId) throw new AlreadyClaimed();
        try {
            db.sql("""
                    insert into manager_link (manager_id, linked_manager_id, league_id)
                    values (?, ?, ?)
                    on conflict (manager_id, linked_manager_id) do nothing
                    """)
                    .params(managerId, linkedManagerId, leagueId)
                    .update();
        } catch (org.springframework.dao.DuplicateKeyException e) {
            // Lost a race with a concurrent claim of the same ESPN member (unique on linked_manager_id).
            throw new AlreadyClaimed();
        }
    }

    /**
     * Of one team's owners (primary first, then co-owners), the one {@code managerId} may link to: an owner
     * they already hold, else the first owner nobody holds. Empty only when EVERY owner is held by someone
     * else (spec 035 code review S7: a co-owner can claim a team whose primary owner is already claimed).
     */
    public Optional<Long> claimable(long managerId, List<Long> owners) {
        for (Long c : owners) {
            Optional<Long> h = holderOf(c);
            if (h.isPresent() && h.get() == managerId) return Optional.of(c);
        }
        for (Long c : owners) {
            if (holderOf(c).isEmpty()) return Optional.of(c);
        }
        return Optional.empty();
    }

    /** Who (if anyone) holds the claim on this ESPN manager. */
    public Optional<Long> holderOf(long linkedManagerId) {
        return db.sql("select manager_id from manager_link where linked_manager_id = ?")
                .param(linkedManagerId)
                .query(Long.class)
                .optional();
    }

    /**
     * Removes this manager's claims whose provenance league is {@code leagueId}. Used when the
     * same user re-claims a different team in the same league: only that league's member link is
     * replaced, never a claim made from another league (plan F3).
     */
    public int unlinkInLeague(long managerId, long leagueId) {
        return db.sql("delete from manager_link where manager_id = ? and league_id = ?")
                .params(managerId, leagueId)
                .update();
    }

    public List<Long> linkedOf(long managerId) {
        return db.sql("select linked_manager_id from manager_link where manager_id = ? order by linked_manager_id")
                .param(managerId)
                .query(Long.class)
                .list();
    }
}
