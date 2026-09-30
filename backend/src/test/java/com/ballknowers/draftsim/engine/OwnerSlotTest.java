package com.ballknowers.draftsim.engine;

import com.ballknowers.draftsim.config.OwnerProperties;
import com.ballknowers.draftsim.store.DraftRepository;
import com.ballknowers.draftsim.store.ManagerRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.lenient;

/**
 * Extracted out of LeagueController.seats()'s own inline ownerManagerId/
 * mySlotHolder lambda (api/LeagueController.java) so the mock room's "fork a
 * live draft" path (claude/next-features-roadmap.md's Phase 3/4 bridge) can
 * resolve the same default seat -- this is that logic's first direct test,
 * it had none while inline.
 */
@ExtendWith(MockitoExtension.class)
class OwnerSlotTest {

    @Mock private ManagerRepository managers;

    private static DraftRepository.DraftRow draft(Map<String, Object> slotToManager) {
        return new DraftRepository.DraftRow(1L, 1L, "sleeper-draft", 2026, 15, 8, "pre_draft", slotToManager);
    }

    @Test
    void unconfiguredOwnerResolvesToNull() {
        assertNull(OwnerSlot.resolve(draft(Map.of("1", 501L)), managers, new OwnerProperties(null)));
    }

    @Test
    void blankOwnerIdIsTreatedAsUnconfigured() {
        assertNull(OwnerSlot.resolve(draft(Map.of("1", 501L)), managers, new OwnerProperties("  ")));
    }

    @Test
    void configuredOwnerMappedInThisDraftResolvesToTheirSlot() {
        lenient().when(managers.idsBySleeperUserId()).thenReturn(Map.of("owner-user-id", 501L));
        OwnerProperties owner = new OwnerProperties("owner-user-id");

        Integer slot = OwnerSlot.resolve(draft(Map.of("1", 999L, "7", 501L)), managers, owner);

        assertEquals(7, slot);
    }

    @Test
    void configuredOwnerNotAManagerInThisDraftResolvesToNull() {
        lenient().when(managers.idsBySleeperUserId()).thenReturn(Map.of("owner-user-id", 501L));
        OwnerProperties owner = new OwnerProperties("owner-user-id");

        assertNull(OwnerSlot.resolve(draft(Map.of("1", 999L)), managers, owner));
    }

    @Test
    void configuredOwnerWithNoManagerRowAtAllResolvesToNull() {
        lenient().when(managers.idsBySleeperUserId()).thenReturn(Map.of());
        OwnerProperties owner = new OwnerProperties("owner-user-id");

        assertNull(OwnerSlot.resolve(draft(Map.of("1", 999L)), managers, owner));
    }

    // ---- 4-arg overload: X-Sleeper-User header vs. the configured owner fallback ----

    @Test
    void headerIdWinsOverTheConfiguredOwnerWhenBothResolve() {
        lenient().when(managers.idsBySleeperUserId())
                .thenReturn(Map.of("owner-user-id", 501L, "visitor-user-id", 999L));
        OwnerProperties owner = new OwnerProperties("owner-user-id");

        Integer slot = OwnerSlot.resolve(draft(Map.of("3", 999L, "7", 501L)), managers, owner, "visitor-user-id");

        assertEquals(3, slot, "the visiting header identity must win, not the configured owner");
    }

    @Test
    void configuredOwnerIsUsedWhenTheHeaderIsAbsent() {
        lenient().when(managers.idsBySleeperUserId()).thenReturn(Map.of("owner-user-id", 501L));
        OwnerProperties owner = new OwnerProperties("owner-user-id");

        Integer slot = OwnerSlot.resolve(draft(Map.of("7", 501L)), managers, owner, null);

        assertEquals(7, slot, "with no header, this must behave exactly like the 3-arg overload");
    }

    @Test
    void bothHeaderAndConfiguredOwnerAbsentResolvesToNull() {
        assertNull(OwnerSlot.resolve(draft(Map.of("1", 501L)), managers, new OwnerProperties(null), null));
    }

    @Test
    void blankHeaderFallsBackToTheConfiguredOwnerRatherThanBeingTreatedAsAnIdentity() {
        lenient().when(managers.idsBySleeperUserId()).thenReturn(Map.of("owner-user-id", 501L));
        OwnerProperties owner = new OwnerProperties("owner-user-id");

        Integer slot = OwnerSlot.resolve(draft(Map.of("7", 501L)), managers, owner, "  ");

        assertEquals(7, slot);
    }

    // --- mayActAsSlot (claude/audit-2026-09-28/01) --------------------------------

    /**
     * resolve() may still fall back to the configured owner for a blank header: that is the
     * seat PRESELECT, harmless. mayActAsSlot is the write gate, and it used to answer "allowed"
     * for a blank identity, so a header-less caller could write any seat. It must not.
     */
    @Test
    void aBlankIdentityMayNotActAsAnySeat() {
        DraftRepository.DraftRow d = draft(Map.of("1", 501L));
        assertFalse(OwnerSlot.mayActAsSlot(d, managers, null, 1));
        assertFalse(OwnerSlot.mayActAsSlot(d, managers, "", 1));
        assertFalse(OwnerSlot.mayActAsSlot(d, managers, "  ", 1));
        // An unmapped seat stays open to a NAMED caller only -- being unmapped is not a blank pass.
        assertFalse(OwnerSlot.mayActAsSlot(d, managers, null, 2));
    }

    @Test
    void aNamedCallerStillActsOnlyAsTheirOwnSeatOrAnUnmappedOne() {
        lenient().when(managers.idsBySleeperUserId()).thenReturn(Map.of("u-a", 501L, "u-b", 502L));
        DraftRepository.DraftRow d = draft(Map.of("1", 501L));

        assertTrue(OwnerSlot.mayActAsSlot(d, managers, "u-a", 1), "their own seat");
        assertFalse(OwnerSlot.mayActAsSlot(d, managers, "u-b", 1), "someone else's seat");
        assertTrue(OwnerSlot.mayActAsSlot(d, managers, "u-b", 2), "a seat Sleeper has not mapped yet");
        assertFalse(OwnerSlot.mayActAsSlot(d, managers, "u-unknown", 1), "no manager row");
    }
}
