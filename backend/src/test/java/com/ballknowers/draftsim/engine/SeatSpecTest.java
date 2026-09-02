package com.ballknowers.draftsim.engine;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * fromDraftOrder is the extraction of what used to be
 * SimulationService.seatsOf's own private method body (engine/SimulationService.java)
 * -- both the real-draft sim path and the mock room's "fork a live draft" path
 * (claude/next-features-roadmap.md's Phase 3/4 bridge) call this same code now.
 */
class SeatSpecTest {

    @Test
    void everyMappedSlotBecomesAManagerSeatExceptMySlot() {
        Map<String, Object> slotToManager = Map.of("1", 501L, "2", 502L, "3", 503L);

        List<SeatSpec> seats = SeatSpec.fromDraftOrder(slotToManager, 2);

        assertEquals(3, seats.size());
        SeatSpec mine = seats.stream().filter(s -> s.slot() == 2).findFirst().orElseThrow();
        assertEquals(SeatSpec.Type.USER, mine.type());
        assertEquals(502L, mine.managerId(), "the USER seat still carries the manager id Sleeper mapped it to");

        for (SeatSpec s : seats) {
            if (s.slot() != 2) assertEquals(SeatSpec.Type.MANAGER, s.type());
        }
    }

    @Test
    void mySlotWithNoSleeperMappingIsAddedAsAUserSeatWithNoManagerId() {
        Map<String, Object> slotToManager = Map.of("1", 501L);

        List<SeatSpec> seats = SeatSpec.fromDraftOrder(slotToManager, 5);

        assertEquals(2, seats.size());
        SeatSpec mine = seats.stream().filter(s -> s.slot() == 5).findFirst().orElseThrow();
        assertEquals(SeatSpec.Type.USER, mine.type());
        assertNull(mine.managerId());
    }

    @Test
    void anEmptyDraftOrderYieldsOnlyTheUserSeat() {
        List<SeatSpec> seats = SeatSpec.fromDraftOrder(Map.of(), 4);

        assertEquals(1, seats.size());
        assertEquals(SeatSpec.Type.USER, seats.get(0).type());
        assertEquals(4, seats.get(0).slot());
    }

    @Test
    void aNonPositiveMySlotWithNoSleeperMappingAddsNoUserSeat() {
        // Same guard SimulationService.seatsOf always had: mySlot < 1 means
        // "no real slot," not "add a phantom seat at slot 0/-1."
        List<SeatSpec> seats = SeatSpec.fromDraftOrder(Map.of("1", 501L), -1);

        assertEquals(1, seats.size());
        assertEquals(SeatSpec.Type.MANAGER, seats.get(0).type());
    }
}
