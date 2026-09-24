package com.ballknowers.draftsim.api;

import com.ballknowers.draftsim.domain.Player;
import com.ballknowers.draftsim.domain.Position;
import com.ballknowers.draftsim.domain.Sport;
import com.ballknowers.draftsim.engine.SeasonSuperlativesService;
import com.ballknowers.draftsim.store.LeagueConductRepository;
import com.ballknowers.draftsim.store.LeagueMemberRepository;
import com.ballknowers.draftsim.store.LeagueMembership;
import com.ballknowers.draftsim.store.LeagueRepository;
import com.ballknowers.draftsim.store.ManagerRepository;
import com.ballknowers.draftsim.store.PlayerRepository;
import org.junit.jupiter.api.Test;
import org.springframework.http.ResponseEntity;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anySet;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

/**
 * Coordinator follow-up 2026-09-23, item 7 (bug-hunting review of
 * specs/008-season-superlatives): {@code conductEntryRow} used to call
 * {@code players.bySleeperId} once per entry -- an N+1 that grows with the
 * conduct list. The GET endpoint must batch it into one lookup.
 */
class SuperlativesControllerConductListBatchTest {

    @Test
    void conductListGetBatchesPlayerNameLookupsIntoOneCall() {
        SeasonSuperlativesService superlatives = mock(SeasonSuperlativesService.class);
        LeagueMembership membership = mock(LeagueMembership.class);
        LeagueConductRepository conduct = mock(LeagueConductRepository.class);
        LeagueMemberRepository leagueMembers = mock(LeagueMemberRepository.class);
        PlayerRepository players = mock(PlayerRepository.class);
        ManagerRepository managers = mock(ManagerRepository.class);

        LeagueRepository.LeagueRow row = new LeagueRepository.LeagueRow(
                1L, Sport.NFL, "L1", "Ball Knowers", 2025, 12, List.of(), 0.0, null, null);
        when(membership.visibleLeague("L1", "user")).thenReturn(Optional.of(row));
        when(membership.canCommission(1L, "user")).thenReturn(false);
        when(leagueMembers.anyCommissioner(1L)).thenReturn(true);

        List<LeagueConductRepository.Entry> entries = List.of(
                new LeagueConductRepository.Entry(1L, 1L, "100", "reason1", 1, null, Instant.now()),
                new LeagueConductRepository.Entry(2L, 1L, "200", "reason2", 1, null, Instant.now()),
                new LeagueConductRepository.Entry(3L, 1L, "300", "reason3", 1, null, Instant.now()));
        when(conduct.forLeague(1L)).thenReturn(entries);
        when(players.byIds(eq(Sport.NFL), anySet())).thenReturn(Map.of(
                "100", new Player(1L, Sport.NFL, "100", "Player A", List.of(Position.RB), null, null, null, null, null),
                "200", new Player(2L, Sport.NFL, "200", "Player B", List.of(Position.WR), null, null, null, null, null)));
        // 300 is deliberately absent -- an unknown player must still render, not throw.

        SuperlativesController controller =
                new SuperlativesController(superlatives, membership, conduct, leagueMembers, players, managers);

        ResponseEntity<?> response = controller.conductList("L1", "user");

        assertEquals(200, response.getStatusCode().value());
        verify(players, times(1)).byIds(eq(Sport.NFL), anySet());
        verify(players, never()).bySleeperId(any(), any());

        @SuppressWarnings("unchecked")
        Map<String, Object> body = (Map<String, Object>) response.getBody();
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> entryRows = (List<Map<String, Object>>) body.get("entries");
        assertEquals(3, entryRows.size());
        assertEquals("Player A", entryRows.get(0).get("playerName"));
        assertEquals("Player B", entryRows.get(1).get("playerName"));
        assertEquals("Unknown player", entryRows.get(2).get("playerName"));
    }
}
