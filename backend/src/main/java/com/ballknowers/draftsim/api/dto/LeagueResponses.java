package com.ballknowers.draftsim.api.dto;

import com.ballknowers.draftsim.domain.Position;
import com.ballknowers.draftsim.domain.Sport;
import com.ballknowers.draftsim.engine.SimulationResult;

import java.util.List;
import java.util.Map;

/**
 * Draft-room REST responses (LeagueController, specs/021-codebase-cleanup). Mirrored by
 * {@code SeatsResponse}, {@code RealBoard}/{@code RealPick} and the track and manual-pick
 * helpers in web/src/api.ts.
 *
 * <p>The live-stream {@code state} frame is an SSE payload and stays a map. Its
 * {@code recentPicks} are {@link RealPick}s, the same rows the REST board sends.
 */
public final class LeagueResponses {

    private LeagueResponses() {}

    /**
     * {@code mySlot} is null by default: unset config, or a configured owner who isn't
     * in this league. {@code status} is null for a draft whose status column is, never
     * the string "null" (claude/lessons.md #12). The reversal-round trio is always
     * present, and its two numbers differ only when someone has overridden.
     */
    public record SeatsResponse(String draftId, int teams, int rounds, String status, List<SeatRow> seats,
                                Integer mySlot, List<String> rosterPositions, Sport sport, int reversalRound,
                                int reversalRoundFromSleeper, boolean reversalRoundOverridden,
                                boolean canCommission) {}

    /**
     * {@code draftsObserved} is here so the UI can be honest about thin data. The
     * relative-reach pair is display-only and null with too few scoreable picks.
     * {@code positionalTilt} stays a map, passed through as the profile's own instance.
     */
    public record SeatRow(int slot, long managerId, String manager, String avatarId, String provenance,
                          double reachBias, Double relativeReachBias, Double relativeReachStdErr,
                          double unpredictability, Map<Position, Double> positionalTilt, String note,
                          int draftsObserved, int picksScored) {}

    public record ReversalRoundResponse(String draftId, int reversalRound, int reversalRoundFromSleeper,
                                        boolean reversalRoundOverridden) {}

    public record RealBoardResponse(String draftId, int teams, int rounds, String status, List<RealPick> picks) {}

    /**
     * One pick that actually happened. {@code manager} falls back to "Slot N" for an
     * unmapped seat, and {@code avatarId} is then null. {@code adpAtDraft} is the board
     * as it stood when the pick was made (spec 013 T077): null, with the key present,
     * when it was never captured.
     */
    public record RealPick(int pickNo, int round, int slot, String manager, String avatarId,
                           SimulationResult.PlayerRef player, Double adpAtDraft) {}

    /**
     * {@code tracking}/{@code alreadyTracking} report what the poller is actually
     * doing. {@code observed} false means {@code status} is the stored value, not
     * Sleeper's. {@code status} is nullable.
     */
    public record TrackResponse(String draftId, boolean tracking, boolean alreadyTracking, String status,
                                boolean observed, int seatsMapped, int teams) {}

    /** {@code managerId} is null when the draft order leaves this seat unmapped: an unattributed pick. */
    public record ManualPickResponse(String draftId, int pickNo, int round, int draftSlot, Long managerId,
                                     long playerId) {}

    /** {@code GET /api/board}: what the engine is valuing against. */
    public record EngineBoardResponse(String capturedOn, int picksWithContemporaneousBoard,
                                      List<EngineBoardRow> entries) {}

    /** {@code team} is null for a free agent or a retired player. */
    public record EngineBoardRow(double adp, String name, String position, String team, int positionalRank) {}
}
