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

    /**
     * Whether this caller may record a pick for {@code slot} of this draft --
     * "only the person whose seat it is gets to fill it in".
     *
     * Deliberately NOT {@link #resolve} with a comparison: that method falls
     * back to the configured {@code draftsim.owner.sleeper-user-id} when no
     * header is sent, which is right for "which seat should this page
     * pre-select" and exactly wrong here. It would mean that on a deploy with an
     * owner configured, a header-less curl could only ever write Allan's own
     * seat -- turning the draft-night escape hatch into the narrowest possible
     * version of itself at the moment it is most needed.
     *
     * Three cases, and the last two are the ones worth being explicit about:
     * <ul>
     *   <li>No {@code X-Sleeper-User} at all: allowed. Same pre-identity
     *       contract every other route keeps (see
     *       {@code LeagueMembership.canSee}), and what DEPLOY.md's curl escape
     *       hatches run on.</li>
     *   <li>The seat maps to no manager: allowed, by necessity. Sleeper returns
     *       a null {@code draft_order} until the commissioner sets it, and
     *       {@code LiveDraftPoller.refreshSeatMap} exists precisely because that
     *       can still be true minutes before the first pick. Refusing here would
     *       make the seats nobody can be shown to own also the seats nobody can
     *       repair -- a lockout in exactly the state the escape hatch is for.</li>
     *   <li>Otherwise: the seat's manager must be this caller's manager.</li>
     * </ul>
     *
     * <p><b>Scoping, not security</b>, like everything else keyed on this header:
     * {@code X-Sleeper-User} is an unverified claim. What this buys is that two
     * people watching the same draft cannot silently overwrite each other's
     * picks, which was a real way to corrupt a live board.
     */
    public static boolean mayActAsSlot(DraftRepository.DraftRow draft, ManagerRepository managers,
                                       String sleeperUserId, int slot) {
        if (sleeperUserId == null || sleeperUserId.isBlank()) return true;

        Object seat = draft.slotToManager().get(String.valueOf(slot));
        if (seat == null) return true;

        Long callerManagerId = managers.idsBySleeperUserId().get(sleeperUserId);
        if (callerManagerId == null) return false;
        return ((Number) seat).longValue() == callerManagerId;
    }
}
