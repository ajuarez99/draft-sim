package com.ballknowers.draftsim.ingest;

import com.ballknowers.draftsim.domain.Player;
import com.ballknowers.draftsim.domain.Sport;
import com.ballknowers.draftsim.sport.SportRules;
import com.ballknowers.draftsim.sport.SportRulesRegistry;
import com.ballknowers.draftsim.store.BoardRepository;
import com.ballknowers.draftsim.store.PlayerRepository;
import com.ballknowers.draftsim.store.StatusCaptureRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

/**
 * specs/008-season-superlatives T053: {@code PlayerIngestService.ingest} now
 * also captures suspension tags after {@code players.upsertAll} (T052,
 * research R11). A failure of the {@code /state/{sport}} call must not fail
 * the player ingest itself -- that's the whole point of {@code
 * suspensionCaptured} being a flag on the result rather than a thrown
 * exception.
 */
@ExtendWith(MockitoExtension.class)
class PlayerIngestServiceSuspensionTest {

    @Mock private SleeperClient sleeper;
    @Mock private PlayerRepository players;
    @Mock private BoardRepository boards;
    @Mock private StatusCaptureRepository statusCaptures;
    @Mock private SportRulesRegistry rulesRegistry;
    @Mock private SportRules footballRules;

    private static Map<String, Object> suspendedPlayerRaw() {
        return Map.of(
                "full_name", "Suspended Guy",
                "fantasy_positions", List.of("RB"),
                "injury_status", "Sus");
    }

    /** {@code fantasy_positions} must be sport-real or {@code PlayerIngestService} drops the player entirely. */
    private static Map<String, Object> suspendedPlayerRaw(Sport sport) {
        return Map.of(
                "full_name", "Suspended Guy",
                "fantasy_positions", List.of(sport == Sport.NBA ? "PG" : "RB"),
                "injury_status", "Sus");
    }

    @Test
    void oneSuspendedPlayerRecordsACaptureAndOneSuspensionRow() {
        when(sleeper.allPlayers("nfl")).thenReturn(Map.of("999", suspendedPlayerRaw()));
        when(players.idsBySleeperId(Sport.NFL)).thenReturn(Map.of("999", 1L));
        when(sleeper.state("nfl")).thenReturn(
                Map.of("season", "2026", "week", 3, "leg", 3, "season_type", "regular"));
        when(rulesRegistry.get(Sport.NFL)).thenReturn(footballRules);
        when(footballRules.isSuspended(any(Player.class))).thenReturn(true);

        PlayerIngestService service =
                new PlayerIngestService(sleeper, players, boards, statusCaptures, rulesRegistry);
        PlayerIngestService.Result result = service.ingest(Sport.NFL);

        assertEquals(1, result.playersWritten());
        assertTrue(result.suspensionCaptured());
        assertEquals(1, result.suspendedCount());

        verify(statusCaptures).recordCapture(eq(Sport.NFL), eq(2026), eq(3), any(Instant.class));
        @SuppressWarnings("unchecked")
        ArgumentCaptor<Collection<String>> captor = ArgumentCaptor.forClass(Collection.class);
        verify(statusCaptures).recordSuspended(eq(Sport.NFL), eq(2026), eq(3), captor.capture());
        assertEquals(List.of("999"), List.copyOf(captor.getValue()));
    }

    /**
     * Coordinator follow-up 2026-09-23, item 5. Measured live against
     * {@code GET /v1/state/nba}: {@code {"week":1,"leg":0,"season_type":"pre",
     * "season":"2026",...}} -- the preseason reports {@code week: 1}, which
     * would previously have been recorded as a real observed week. A capture
     * must only ever be recorded when {@code season_type} is {@code
     * "regular"}.
     */
    @Test
    void preseasonStateDoesNotRecordACapture() {
        when(sleeper.allPlayers("nba")).thenReturn(Map.of("999", suspendedPlayerRaw(Sport.NBA)));
        when(players.idsBySleeperId(Sport.NBA)).thenReturn(Map.of("999", 1L));
        when(sleeper.state("nba")).thenReturn(
                Map.of("week", 1, "leg", 0, "season_type", "pre", "season", "2026"));

        PlayerIngestService service =
                new PlayerIngestService(sleeper, players, boards, statusCaptures, rulesRegistry);
        PlayerIngestService.Result result = service.ingest(Sport.NBA);

        assertEquals(1, result.playersWritten(), "the player ingest itself is unaffected");
        assertFalse(result.suspensionCaptured(), "a preseason state must not be recorded as a captured week");
        assertEquals(0, result.suspendedCount());
        verifyNoInteractions(statusCaptures);
    }

    /**
     * Coordinator follow-up 2026-09-23, item 5. Measured live against
     * {@code GET /v1/state/nfl}: {@code {"week":3,"leg":3,"season_type":
     * "regular",...}} -- {@code week} and {@code leg} happen to agree for
     * football today, so a synthetic case where they DIFFER is what actually
     * proves the fix reads {@code leg} (the fantasy scoring period) and not
     * {@code week} (which can, in general, differ from it -- {@code leg} is
     * Sleeper's own name for the value that advances fantasy scoring).
     */
    @Test
    void regularSeasonCaptureUsesLegNotWeekWhenTheyDiffer() {
        when(sleeper.allPlayers("nfl")).thenReturn(Map.of("999", suspendedPlayerRaw()));
        when(players.idsBySleeperId(Sport.NFL)).thenReturn(Map.of("999", 1L));
        when(sleeper.state("nfl")).thenReturn(
                Map.of("season", "2026", "week", 5, "leg", 4, "season_type", "regular"));
        when(rulesRegistry.get(Sport.NFL)).thenReturn(footballRules);
        when(footballRules.isSuspended(any(Player.class))).thenReturn(true);

        PlayerIngestService service =
                new PlayerIngestService(sleeper, players, boards, statusCaptures, rulesRegistry);
        PlayerIngestService.Result result = service.ingest(Sport.NFL);

        assertTrue(result.suspensionCaptured());
        verify(statusCaptures).recordCapture(eq(Sport.NFL), eq(2026), eq(4), any(Instant.class));
        verify(statusCaptures).recordSuspended(eq(Sport.NFL), eq(2026), eq(4), any());
    }

    @Test
    void stateCallFailureDoesNotFailTheIngest() {
        when(sleeper.allPlayers("nfl")).thenReturn(Map.of("999", suspendedPlayerRaw()));
        when(players.idsBySleeperId(Sport.NFL)).thenReturn(Map.of("999", 1L));
        when(sleeper.state("nfl")).thenThrow(new RuntimeException("sleeper unreachable"));

        PlayerIngestService service =
                new PlayerIngestService(sleeper, players, boards, statusCaptures, rulesRegistry);
        PlayerIngestService.Result result = service.ingest(Sport.NFL);

        assertEquals(1, result.playersWritten(), "the state-call failure must not touch the player ingest itself");
        assertFalse(result.suspensionCaptured());
        assertEquals(0, result.suspendedCount());
        verifyNoInteractions(statusCaptures);
    }
}
