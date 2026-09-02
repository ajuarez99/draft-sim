package com.ballknowers.draftsim.mock;

import com.ballknowers.draftsim.engine.SeatSpec;
import com.ballknowers.draftsim.engine.SimulationResult;

import java.util.List;

/**
 * The full state of one mock draft session, returned by every {@code /api/mocks}
 * endpoint. Reuses {@link SimulationResult.PlayerRef} verbatim (§4 of
 * claude/next-features-roadmap.md, Phase 3) so the frontend needs zero new
 * player type.
 */
public record MockSessionState(
        long id,
        String status,
        int teams,
        int rounds,
        List<String> rosterPositions,
        int userSlot,
        /** This session's own snake-order pick numbers -- DraftSlot.picksForSlot(userSlot, teams, rounds),
         *  computed once here so the frontend doesn't need its own copy of the snake-order formula. */
        List<Integer> myPicks,
        List<SeatView> seats,
        List<PickView> picks,
        /** Every undrafted player on the board -- what the on-the-clock picker chooses from. */
        List<SimulationResult.PlayerRef> available,
        int currentPickNo,
        /** Null once the session is COMPLETE. */
        Integer onTheClockSlot,
        boolean isUsersTurn
) {
    public record SeatView(int slot, SeatSpec.Type type, Long managerId, String manager) {}

    public record PickView(int pickNo, int round, int draftSlot, SeatSpec.Type seatType,
                           String source, SimulationResult.PlayerRef player) {}
}
