package com.ballknowers.draftsim.engine;

import com.ballknowers.draftsim.config.OwnerProperties;
import com.ballknowers.draftsim.store.DraftRepository;
import com.ballknowers.draftsim.store.ManagerRepository;

/**
 * Which slot of a real draft belongs to the configured app owner, or null if
 * that can't be determined -- {@code draftsim.owner.sleeper-user-id} is unset
 * (the local-dev default), or the owner isn't a manager mapped in this
 * particular draft's {@code slot_to_manager}.
 *
 * Extracted out of {@code LeagueController.seats()}'s own inline computation
 * (its {@code ownerManagerId}/{@code mySlotHolder} lambda) so the mock room's
 * "fork a live draft" path (claude/next-features-roadmap.md's Phase 3/4
 * bridge) can resolve the same default seat without a second copy of the
 * lookup.
 */
public final class OwnerSlot {

    private OwnerSlot() {}

    public static Integer resolve(DraftRepository.DraftRow draft, ManagerRepository managers, OwnerProperties owner) {
        if (!owner.configured()) return null;
        Long ownerManagerId = managers.idsBySleeperUserId().get(owner.sleeperUserId());
        if (ownerManagerId == null) return null;

        for (var entry : draft.slotToManager().entrySet()) {
            long id = ((Number) entry.getValue()).longValue();
            if (id == ownerManagerId) return Integer.parseInt(entry.getKey());
        }
        return null;
    }
}
