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

    /**
     * Spec 049: the player page's JSON carries {@code thisWeek} and {@code windowQualification}, and an absent
     * {@code thisWeek} is {@code null} on the wire, not missing (the client reads missing as "older backend").
     */
    @Test
    void thePlayerPageJsonCarriesBothNewFieldsAndAnAbsentThisWeekIsNull() throws Exception {
        var wq = new java.util.EnumMap<WindowKind, PlayerStatsService.WindowQualification>(WindowKind.class);
        wq.put(WindowKind.SEASON, new PlayerStatsService.WindowQualification(true, null, null, false));
        wq.put(WindowKind.LAST_10, new PlayerStatsService.WindowQualification(false, "NOT_QUALIFIED", 10, true));
        when(stats.read(any(), eq("live"), any())).thenReturn(Optional.of(page(new PlayerStatsService.ThisWeek(2, 1, 3, true), wq)));
        when(stats.read(any(), eq("none"), any())).thenReturn(Optional.of(
                page(null, new java.util.EnumMap<WindowKind, PlayerStatsService.WindowQualification>(WindowKind.class))));
        var mapper = com.fasterxml.jackson.databind.json.JsonMapper.builder().findAndAddModules().build();
        var mvc = org.springframework.test.web.servlet.setup.MockMvcBuilders.standaloneSetup(controller)
                .setMessageConverters(new org.springframework.http.converter.json.MappingJackson2HttpMessageConverter(mapper))
                .build();

        var l = mapper.readTree(mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                        .get("/api/leagues/L1/players/live").header("X-Sleeper-User", "me"))
                .andReturn().getResponse().getContentAsString());
        assertEquals(2, l.get("thisWeek").get("week").asInt());
        assertEquals(1, l.get("thisWeek").get("played").asInt());
        assertEquals(3, l.get("thisWeek").get("remaining").asInt());
        assertEquals(10, l.get("windowQualification").get("LAST_10").get("lastN").asInt());
        assertEquals("NOT_QUALIFIED", l.get("windowQualification").get("LAST_10").get("reason").asText());
        assertTrue(l.get("windowQualification").get("LAST_10").get("stale").asBoolean());
        assertFalse(l.get("windowQualification").get("SEASON").get("stale").asBoolean());
        assertTrue(l.get("thisWeek").get("scheduleStored").asBoolean());
        assertTrue(l.get("windowQualification").get("SEASON").get("reason").isNull());

        var n = mapper.readTree(mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                        .get("/api/leagues/L1/players/none").header("X-Sleeper-User", "me"))
                .andReturn().getResponse().getContentAsString());
        assertTrue(n.has("thisWeek"), "present");
        assertTrue(n.get("thisWeek").isNull(), "and null, not absent");
        assertTrue(n.get("windowQualification").isObject());
        assertEquals(0, n.get("windowQualification").size());
    }

    private static PlayerStatsService.PlayerStatsPage page(PlayerStatsService.ThisWeek tw,
            java.util.Map<WindowKind, PlayerStatsService.WindowQualification> wq) {
        return new PlayerStatsService.PlayerStatsPage("nba", 2025, null, true, null, null, List.of(), null, null,
                List.of(), 0, null, new java.util.EnumMap<>(WindowKind.class), null,
                new java.util.EnumMap<>(WindowKind.class), List.of(), null, null, null, null, List.of(), null, tw, wq);
    }
}
