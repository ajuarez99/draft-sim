package com.ballknowers.draftsim.api;

import com.ballknowers.draftsim.domain.Sport;
import com.ballknowers.draftsim.engine.PlayoffOddsService;
import com.ballknowers.draftsim.engine.PowerRankingService;
import com.ballknowers.draftsim.engine.ScoredWeeks;
import com.ballknowers.draftsim.store.LeagueMembership;
import com.ballknowers.draftsim.store.LeagueRepository;
import com.ballknowers.draftsim.store.PowerRankingRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.databind.JsonNode;

import java.util.List;
import java.util.Optional;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

/**
 * claude/audit-2026-09-28/10: {@code POST /power/compute} refuses to snapshot an in-progress
 * week itself, whatever week the caller sends. The finality rules (no fetch rows, loaded_complete)
 * live in {@code ScoredWeeks} and are pinned against Postgres in {@code ScoredWeeksIT}; here the
 * snapshot they produce is fed in, and what the endpoint does with it is asserted.
 */
class PowerComputeFinalityTest {

    private static final String LEAGUE = "L1";
    private static final long ID = 7L;

    private PowerRankingService power;
    private PlayoffOddsService odds;
    private ScoredWeeks scoredWeeks;
    private LeagueHistoryController controller;

    @BeforeEach
    void wire() {
        power = mock(PowerRankingService.class);
        odds = mock(PlayoffOddsService.class);
        scoredWeeks = mock(ScoredWeeks.class);
        LeagueMembership membership = mock(LeagueMembership.class);
        var row = new LeagueRepository.LeagueRow(ID, Sport.NBA, LEAGUE, "fixture", 2026, 12,
                List.of(), 1.0, null, "in_season");
        when(membership.visibleLeague(LEAGUE, "commish")).thenReturn(Optional.of(row));
        when(membership.canCommission(ID, "commish")).thenReturn(true);
        when(power.computeWeek0IfMissing(anyLong(), any(), anyInt()))
                .thenReturn(new PowerRankingService.Week0Result(new PowerRankingRepository.Entry[0], null));
        when(power.computeRealized(anyLong(), anyInt(), anyInt()))
                .thenReturn(new PowerRankingRepository.Entry[12]);

        controller = new LeagueHistoryController(null, null, power, null, membership, null, null, null,
                null, null, null, odds, null, null, scoredWeeks);
    }

    private static <T> T any() {
        return org.mockito.ArgumentMatchers.any();
    }

    /** The body as JSON, so these assertions hold whether the controller builds a map or a
     *  response record (spec 021 moved off the cast to Map). */
    private JsonNode compute(int week) {
        return GoldenJson.MAPPER.valueToTree(controller.compute(LEAGUE, 2026, week, "commish").getBody());
    }

    private static ScoredWeeks.Snapshot snap(int stored, int latestFinal, Integer... finals) {
        return new ScoredWeeks.Snapshot(stored, latestFinal, Set.of(finals));
    }

    /** Week 3 stored but in progress: odds run through week 2, and week 3 gets no realized ranking. */
    @Test
    void anInProgressWeekIsNotSnapshottedAndOddsRunThroughThePreviousFinalWeek() {
        when(scoredWeeks.of(ID)).thenReturn(snap(3, 2, 1, 2));

        JsonNode body = compute(3);

        verify(odds).compute(ID, 2026, 2);
        verify(power, never()).computeRealized(anyLong(), anyInt(), anyInt());
        assertEquals(2, body.get("playoffOddsThroughWeek").asInt());
        assertEquals(0, body.get("realized").asInt());
        assertTrue(body.get("realizedSkipped").asText().contains("week 3 is not final"),
                "the response says why nothing was saved: " + body.get("realizedSkipped"));
    }

    @Test
    void aFinalWeekIsSnapshottedAndOddsRunThroughIt() {
        when(scoredWeeks.of(ID)).thenReturn(snap(3, 2, 1, 2));

        JsonNode body = compute(2);

        verify(power).computeRealized(ID, 2026, 2);
        verify(odds).compute(ID, 2026, 2);
        assertEquals(2, body.get("playoffOddsThroughWeek").asInt());
        assertEquals(12, body.get("realized").asInt());
        assertFalse(body.has("realizedSkipped"));
    }

    /** An earlier final week is honoured as asked: the clamp never widens a request. */
    @Test
    void anEarlierFinalWeekIsNotWidenedToTheLatestFinal() {
        when(scoredWeeks.of(ID)).thenReturn(snap(4, 3, 1, 2, 3));

        assertEquals(1, compute(1).get("playoffOddsThroughWeek").asInt());
        verify(odds).compute(ID, 2026, 1);
    }

    /** No fetch rows (or loaded_complete): ScoredWeeks reports every stored week final, so nothing is clamped. */
    @Test
    void whenEveryStoredWeekIsFinalTheRequestPassesThrough() {
        when(scoredWeeks.of(ID)).thenReturn(snap(3, 3, 1, 2, 3));

        JsonNode body = compute(3);

        verify(power).computeRealized(ID, 2026, 3);
        verify(odds).compute(ID, 2026, 3);
        assertEquals(3, body.get("playoffOddsThroughWeek").asInt());
    }

    @Test
    void whenNothingIsFinalYetNeitherSnapshotIsWrittenAndTheReasonIsGiven() {
        when(scoredWeeks.of(ID)).thenReturn(snap(1, 0));

        JsonNode body = compute(1);

        verify(odds, never()).compute(anyLong(), anyInt(), anyInt());
        verify(power, never()).computeRealized(anyLong(), anyInt(), anyInt());
        assertFalse(body.has("playoffOddsThroughWeek"));
        assertTrue(body.hasNonNull("playoffOddsSkipped"));
        assertTrue(body.hasNonNull("realizedSkipped"));
    }

    /** The preseason baseline is independent of the requested week and of finality. */
    @Test
    void theWeekZeroBaselineIsStillAttemptedWhateverTheWeekIsFinal() {
        when(scoredWeeks.of(ID)).thenReturn(snap(1, 0));

        compute(1);

        verify(power).computeWeek0IfMissing(eq(ID), eq(LEAGUE), eq(2026));
    }
}
