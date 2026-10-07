package com.ballknowers.draftsim.recap;

import java.time.Instant;
import java.util.List;

/**
 * What {@code GET /api/leagues/{id}/recap/{week}} says (specs/020-ai-weekly-recap contracts/api.md).
 * Everything but {@code state}, {@code week} and {@code stale} is null when it does not apply.
 * Deliberately has no failure detail: that stays in the database.
 */
public record RecapView(
        State state, Integer season, int week,
        String model, Instant generatedAt, Integer revision, String revisionReason,
        boolean stale, String headline, List<RecapOutput.Section> sections, String failureReason) {

    /** Closed set; web/src/api/recap.ts {@code RecapState} mirrors it. */
    public enum State { FEATURE_OFF, NOT_ENTITLED, WEEK_NOT_FINAL, GENERATING, READY, FAILED, RATE_LIMITED }

    public static RecapView bare(State state, Integer season, int week) {
        return new RecapView(state, season, week, null, null, null, null, false, null, null, null);
    }
}
