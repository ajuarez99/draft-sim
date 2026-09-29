package com.ballknowers.draftsim.refresh;

import com.ballknowers.draftsim.refresh.RefreshDecision.Decision;
import com.ballknowers.draftsim.store.LeagueRefreshRepository.Row;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;

import static org.junit.jupiter.api.Assertions.*;

class LeagueRefreshRulesTest {

    private static final Instant NOW = Instant.parse("2026-09-28T12:00:00Z");

    private static Instant ago(Duration d) {
        return NOW.minus(d);
    }

    private static Row row(Instant success, Instant failure, boolean complete) {
        return new Row(1L, success, failure, failure == null ? null : "boom", complete);
    }

    @Test
    void noRowStarts() {
        assertEquals(Decision.START, RefreshDecision.decide(null, "in_season", NOW));
        assertEquals(Decision.START, RefreshDecision.decide(null, "complete", NOW));
    }

    @Test
    void loadedCompleteSkipsEvenWithAVeryOldSuccess() {
        Row r = row(ago(Duration.ofDays(400)), null, true);
        assertEquals(Decision.SKIP_COMPLETE, RefreshDecision.decide(r, "complete", NOW));
    }

    @Test
    void successFiftyNineMinutesOldIsFreshAndSixtyOneIsStale() {
        assertEquals(Decision.SKIP_FRESH,
                RefreshDecision.decide(row(ago(Duration.ofMinutes(59)), null, false), "in_season", NOW));
        assertEquals(Decision.START,
                RefreshDecision.decide(row(ago(Duration.ofMinutes(61)), null, false), "in_season", NOW));
    }

    @Test
    void recentFailureNewerThanTheLastSuccessSkipsUntilTenMinutesHavePassed() {
        Instant success = ago(Duration.ofHours(3));
        assertEquals(Decision.SKIP_RECENT_FAILURE,
                RefreshDecision.decide(row(success, ago(Duration.ofMinutes(9)), false), "in_season", NOW));
        assertEquals(Decision.START,
                RefreshDecision.decide(row(success, ago(Duration.ofMinutes(11)), false), "in_season", NOW));
    }

    @Test
    void aFailureWithNoSuccessAtAllStillBacksOff() {
        assertEquals(Decision.SKIP_RECENT_FAILURE,
                RefreshDecision.decide(row(null, ago(Duration.ofMinutes(2)), false), "in_season", NOW));
    }

    @Test
    void aFailureOlderThanTheLastSuccessIsNotAFailureState() {
        // success 5 min ago, failure 30 min ago: the season is fresh, not backing off.
        Row r = row(ago(Duration.ofMinutes(5)), ago(Duration.ofMinutes(30)), false);
        assertFalse(RefreshDecision.failed(r));
        assertEquals(Decision.SKIP_FRESH, RefreshDecision.decide(r, "in_season", NOW));
    }

    @Test
    void nullStatusIsNeverTreatedAsComplete() {
        Row fresh = row(ago(Duration.ofMinutes(5)), null, false);
        assertEquals(Decision.SKIP_FRESH, RefreshDecision.decide(fresh, null, NOW),
                "a null status must read like in_season, not trigger a completed-season load");
        assertFalse(RefreshDecision.completeAfter(null, true, true));
    }

    @Test
    void aCompleteSeasonThatHasNeverSucceededStartsWithoutWaitingForTheHour() {
        // Amended after review: with a success on record the normal stale rule applies.
        Row r = row(null, null, false);
        assertEquals(Decision.START, RefreshDecision.decide(r, "complete", NOW));
    }

    @Test
    void aCompleteButNotLoadedSeasonFollowsTheNormalStaleRuleOnceItHasSucceeded() {
        // Review fix 2026-09-28: complete-not-loaded must not START on every visit.
        assertEquals(Decision.SKIP_FRESH,
                RefreshDecision.decide(row(ago(Duration.ofMinutes(5)), null, false), "complete", NOW));
        assertEquals(Decision.START,
                RefreshDecision.decide(row(ago(Duration.ofMinutes(61)), null, false), "complete", NOW));
        assertEquals(Decision.START,
                RefreshDecision.decide(row(null, null, false), "complete", NOW));
    }

    @Test
    void completeAfterIsTrueOnlyForCompleteStatusAndSuccess() {
        assertTrue(RefreshDecision.completeAfter("complete", true, true));
        assertFalse(RefreshDecision.completeAfter("complete", false, true));
        assertFalse(RefreshDecision.completeAfter("in_season", true, true));
        assertFalse(RefreshDecision.completeAfter("pre_draft", true, true));
        // Review fix 2026-09-28: a complete league whose per-game weeks aren't all
        // final yet (inside WEEK_FINAL_AFTER) must not be frozen as loaded.
        assertFalse(RefreshDecision.completeAfter("complete", true, false));
    }
}
