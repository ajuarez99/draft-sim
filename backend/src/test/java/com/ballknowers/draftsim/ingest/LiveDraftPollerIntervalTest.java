package com.ballknowers.draftsim.ingest;

import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Spec 029: the poll loop's sleep. Fast only while drafting with nothing failing;
 * every other case is the pre-029 10s interval and its backoff, unchanged.
 */
class LiveDraftPollerIntervalTest {

    private static final Duration SLOW = Duration.ofSeconds(10);
    private static final Duration FAST = Duration.ofSeconds(2);

    @Test
    void draftingWithNoFailuresUsesTheFastInterval() {
        assertThat(LiveDraftPoller.sleepFor("drafting", 0, SLOW, FAST)).isEqualTo(FAST);
    }

    @Test
    void everyOtherStatusKeepsTheSlowInterval() {
        assertThat(LiveDraftPoller.sleepFor("pre_draft", 0, SLOW, FAST)).isEqualTo(SLOW);
        assertThat(LiveDraftPoller.sleepFor("paused", 0, SLOW, FAST)).isEqualTo(SLOW);
        assertThat(LiveDraftPoller.sleepFor(null, 0, SLOW, FAST)).isEqualTo(SLOW);
    }

    @Test
    void aFailureMidDraftFallsBackToTheSlowBackoffNotTheFastInterval() {
        // A rate-limited Sleeper must never be polled harder because the draft is live.
        assertThat(LiveDraftPoller.sleepFor("drafting", 1, SLOW, FAST)).isEqualTo(Duration.ofSeconds(20));
        assertThat(LiveDraftPoller.sleepFor("drafting", 2, SLOW, FAST)).isEqualTo(Duration.ofSeconds(30));
        assertThat(LiveDraftPoller.sleepFor("drafting", 50, SLOW, FAST)).isEqualTo(Duration.ofSeconds(60));
    }

    @Test
    void backoffIsUnchangedForTheSlowStatuses() {
        assertThat(LiveDraftPoller.sleepFor("pre_draft", 1, SLOW, FAST)).isEqualTo(Duration.ofSeconds(20));
        assertThat(LiveDraftPoller.sleepFor("pre_draft", 9, SLOW, FAST)).isEqualTo(Duration.ofSeconds(60));
    }
}
