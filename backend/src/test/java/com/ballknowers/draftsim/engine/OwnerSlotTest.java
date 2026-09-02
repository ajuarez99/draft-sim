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
}
