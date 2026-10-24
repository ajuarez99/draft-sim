package com.ballknowers.draftsim.store;

import java.time.Instant;
import java.util.Map;

/**
 * Test-only constructors and upserts for the draft rows (spec 046 F12).
 *
 * <p>Main code deliberately has no defaulting overload for {@code budget}, {@code amount},
 * {@code draftType} and friends: a default there asserts a rule ("snake", "no budget") rather than a
 * value, and on conflict an overload that passed {@code null} would write it over a stored value. A test
 * that does not care about those columns says so here, once, instead of at ~45 call sites.
 */
public final class DraftFixtures {

    private DraftFixtures() {}

    // ---------------------------------------------------------------- DraftRow (snake, no budget)

    public static DraftRepository.DraftRow draftRow(long id, long leagueId, String sleeperDraftId, int season,
                                                    int rounds, int teams, String status,
                                                    Map<String, Object> slotToManager) {
        return draftRow(id, leagueId, sleeperDraftId, season, rounds, teams, status, slotToManager, 0, null);
    }

    public static DraftRepository.DraftRow draftRow(long id, long leagueId, String sleeperDraftId, int season,
                                                    int rounds, int teams, String status,
                                                    Map<String, Object> slotToManager, int reversalRound) {
        return draftRow(id, leagueId, sleeperDraftId, season, rounds, teams, status, slotToManager,
                reversalRound, null);
    }

    public static DraftRepository.DraftRow draftRow(long id, long leagueId, String sleeperDraftId, int season,
                                                    int rounds, int teams, String status,
                                                    Map<String, Object> slotToManager, int reversalRound,
                                                    Instant startTime) {
        return new DraftRepository.DraftRow(id, leagueId, sleeperDraftId, season, rounds, teams, status,
                slotToManager, reversalRound, startTime, "snake", null);
    }

    /** An auction draft: {@code draftType = "auction"} with the given budget. */
    public static DraftRepository.DraftRow auctionDraftRow(long id, long leagueId, String sleeperDraftId, int season,
                                                           int rounds, int teams, String status, int budget) {
        return new DraftRepository.DraftRow(id, leagueId, sleeperDraftId, season, rounds, teams, status,
                Map.of(), 0, null, "auction", budget);
    }

    // ---------------------------------------------------------------- PickRow (no price, no name)

    public static DraftRepository.PickRow pickRow(long draftId, int pickNo, int round, int draftSlot,
                                                  Long managerId, Long playerId, Double adpAtTime) {
        return new DraftRepository.PickRow(draftId, pickNo, round, draftSlot, managerId, playerId, adpAtTime,
                null, null, null);
    }

    // ---------------------------------------------------------------- upsert (snake, no timer, no budget)

    public static long upsert(DraftRepository drafts, long leagueId, String sleeperDraftId, int season, int rounds,
                              int teams, String type, String status, Instant startTime, String slotToManagerJson) {
        return upsert(drafts, leagueId, sleeperDraftId, season, rounds, teams, type, status, startTime,
                slotToManagerJson, 0);
    }

    public static long upsert(DraftRepository drafts, long leagueId, String sleeperDraftId, int season, int rounds,
                              int teams, String type, String status, Instant startTime, String slotToManagerJson,
                              int reversalRound) {
        return upsert(drafts, leagueId, sleeperDraftId, season, rounds, teams, type, status, startTime,
                slotToManagerJson, reversalRound, null);
    }

    /** On conflict this WRITES pick_timer_seconds = NULL and budget = NULL; fixture use only. */
    public static long upsert(DraftRepository drafts, long leagueId, String sleeperDraftId, int season, int rounds,
                              int teams, String type, String status, Instant startTime, String slotToManagerJson,
                              int reversalRound, Integer pickTimerSeconds) {
        return drafts.upsert(leagueId, sleeperDraftId, season, rounds, teams, type, status, startTime,
                slotToManagerJson, reversalRound, pickTimerSeconds, null);
    }
}
