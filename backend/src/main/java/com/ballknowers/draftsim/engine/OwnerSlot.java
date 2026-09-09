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
        return resolve(draft, managers, owner, (String) null);
    }

    /**
     * @param sleeperUserId identity from the {@code X-Sleeper-User} request
     *                      header (claude/user-identity-and-onboarding.md
     *                      §4c), or null when the header is absent. Takes
     *                      precedence over {@code owner} so a visiting user's
     *                      own seat wins over Allan's configured default; the
     *                      configured owner stays the fallback so this app's
     *                      own deploy behaves identically with an empty
     *                      localStorage, and every caller that never sends the
     *                      header keeps resolving exactly as before.
     */
    public static Integer resolve(DraftRepository.DraftRow draft, ManagerRepository managers,
                                  OwnerProperties owner, String sleeperUserId) {
        String effectiveId = sleeperUserId != null && !sleeperUserId.isBlank()
                ? sleeperUserId
                : owner.configured() ? owner.sleeperUserId() : null;
        if (effectiveId == null) return null;

        Long managerId = managers.idsBySleeperUserId().get(effectiveId);
        if (managerId == null) return null;

        for (var entry : draft.slotToManager().entrySet()) {
            long id = ((Number) entry.getValue()).longValue();
            if (id == managerId) return Integer.parseInt(entry.getKey());
        }
        return null;
    }
}
