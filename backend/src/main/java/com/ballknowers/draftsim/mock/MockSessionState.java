package com.ballknowers.draftsim.mock;

import com.ballknowers.draftsim.domain.Sport;
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
        /**
         * The sport this session drafts in. The room reads it to pick the right
         * position filters, name abbreviations and pick-run detector -- all of
         * which were already sport-keyed on the frontend and were being handed a
         * hardcoded {@code "nfl"} for want of this field.
         */
        Sport sport,
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
        boolean isUsersTurn,
        /** The real draft this session was forked from, or null for an ordinary from-scratch mock. */
        Long sourceDraftId,
        /** The first pick this session hadn't yet decided at fork time. Null when sourceDraftId is null. */
        Integer forkedAtPickNo,
        /**
         * The round from which snake parity flips; 0 is plain snake (V14).
         * The board grid needs this to draw the same pick order {@link #myPicks}
         * was computed against -- without it a reversed session renders a
         * plain-snake grid, and the user's own highlighted picks land in another
         * seat's column.
         */
        int reversalRound,
        /**
         * The Sleeper league this mock borrowed its settings from (V16), or
         * null when it was started with no league in mind -- and null for
         * every session created before V16, which stored only the league's
         * display name and cannot be backfilled from it.
         *
         * Here so the rail can show league context inside a mock room: a mock
         * seeded from a league is exactly where you want the real room one
         * click away, and before this the page had no way to name its league.
         */
        String sourceSleeperLeagueId
) {
    public record SeatView(int slot, SeatSpec.Type type, Long managerId, String manager, String avatarId) {}

    public record PickView(int pickNo, int round, int draftSlot, SeatSpec.Type seatType,
                           String source, SimulationResult.PlayerRef player) {}
}
