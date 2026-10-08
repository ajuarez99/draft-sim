package com.ballknowers.draftsim.api;

import com.ballknowers.draftsim.domain.Sport;
import com.ballknowers.draftsim.engine.AdvancedStats.WindowKind;
import com.ballknowers.draftsim.engine.PlayerStatsService;
import com.ballknowers.draftsim.store.LeagueMembership;
import com.ballknowers.draftsim.store.LeagueRepository.LeagueRow;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Spec 022 T044: {@code window} on the leaderboard route is a rule and is required. A missing or unknown value
 * is a 400, matched exactly (no case folding, no default), and the scoping 404 still wins for a caller who
 * cannot see the league.
 */
class PlayerStatsControllerTest {

    private final PlayerStatsService stats = mock(PlayerStatsService.class);
    private final LeagueMembership membership = mock(LeagueMembership.class);
    private final PlayerStatsController controller = new PlayerStatsController(stats, membership);
    private final LeagueRow league = new LeagueRow(1L, Sport.NBA, "L1", "n", 2025, 12, List.of("PG"), 0.0, null,
            "complete");

    @BeforeEach
    void setUp() {
        when(membership.visibleLeague("L1", "me")).thenReturn(Optional.of(league));
        when(membership.visibleLeague(eq("L1"), eq("stranger"))).thenReturn(Optional.empty());
    }

    @Test
    void aMissingWindowIs400AndTheServiceIsNotCalled() {
        assertEquals(HttpStatus.BAD_REQUEST, controller.leaderboard("L1", null, "me").getStatusCode());
        verify(stats, never()).readLeaderboard(any(), any(), any());
    }

    @Test
    void anUnknownOrDifferentlyCasedWindowIs400() {
        for (String bad : List.of("", "LAST_7", "season", "Season", "LAST_10 ", "ALL")) {
            assertEquals(HttpStatus.BAD_REQUEST, controller.leaderboard("L1", bad, "me").getStatusCode(), bad);
        }
        verify(stats, never()).readLeaderboard(any(), any(), any());
    }

    @Test
    void everyKnownWindowIsPassedThrough() {
        for (WindowKind k : WindowKind.values()) {
            var res = controller.leaderboard("L1", k.name(), "me");
            assertEquals(HttpStatus.OK, res.getStatusCode(), k.name());
            verify(stats).readLeaderboard(league, k, "me");
        }
    }

    @Test
    void aCallerWhoCannotSeeTheLeagueGets404WhateverTheWindow() {
        assertEquals(HttpStatus.NOT_FOUND, controller.leaderboard("L1", null, "stranger").getStatusCode());
        assertEquals(HttpStatus.NOT_FOUND, controller.leaderboard("L1", "bogus", "stranger").getStatusCode());
        assertEquals(HttpStatus.NOT_FOUND, controller.leaderboard("L1", "SEASON", "stranger").getStatusCode());
        verify(stats, never()).readLeaderboard(any(), any(), any());
    }
}
