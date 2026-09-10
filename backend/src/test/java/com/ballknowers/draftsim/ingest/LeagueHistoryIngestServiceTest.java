package com.ballknowers.draftsim.ingest;

import com.ballknowers.draftsim.domain.Sport;
import com.ballknowers.draftsim.store.LeagueMemberRepository;
import com.ballknowers.draftsim.store.LeagueRepository;
import com.ballknowers.draftsim.store.ManagerRepository;
import com.ballknowers.draftsim.store.RosterSeasonRepository;
import com.ballknowers.draftsim.store.RosterWeekPointsRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.*;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/**
 * claude/league-suite.md Phase A. Finding 2/5 of claude/plan-review-league-suite.md
 * are both regression-shaped bugs (loop past last_scored_leg; refetch everything
 * every time) -- pinned here rather than only caught by eye against a live league.
 */
@ExtendWith(MockitoExtension.class)
class LeagueHistoryIngestServiceTest {

    @Mock private SleeperClient sleeper;
    @Mock private LeagueRepository leagues;
    @Mock private ManagerRepository managers;
    @Mock private LeagueMemberRepository leagueMembers;
    @Mock private RosterSeasonRepository rosterSeasons;
    @Mock private RosterWeekPointsRepository weekPoints;

    private LeagueHistoryIngestService service;

    @BeforeEach
    void setUp() {
        service = new LeagueHistoryIngestService(sleeper, leagues, managers, leagueMembers, rosterSeasons, weekPoints);
        lenient().when(leagues.upsert(any(), anyInt(), any(), any(), any(), anyInt(), any(), any(), any()))
                .thenReturn(55L);
    }

    private static Map<String, Object> leagueObject(String leagueId, int season, Map<String, Object> settings,
                                                     Map<String, Object> metadata) {
        Map<String, Object> l = new HashMap<>();
        l.put("league_id", leagueId);
        l.put("season", season);
        l.put("previous_league_id", null);
        l.put("settings", settings);
        l.put("metadata", metadata);
        return l;
    }

    private static Map<String, Object> rosterObject(int rosterId, String ownerId, int wins, int losses,
                                                     int fpts, int fptsDecimal) {
        Map<String, Object> settings = new HashMap<>();
        settings.put("wins", wins);
        settings.put("losses", losses);
        settings.put("fpts", fpts);
        settings.put("fpts_decimal", fptsDecimal);
        Map<String, Object> r = new HashMap<>();
        r.put("roster_id", rosterId);
        r.put("owner_id", ownerId);
        r.put("settings", settings);
        return r;
    }

    @Test
    void standingsCombineWholeAndDecimalPointsAndFlagTheChampion() {
        Map<String, Object> metadata = Map.of("latest_league_winner_roster_id", "2");
        Map<String, Object> league = leagueObject("L1", 2025, Map.of("last_scored_leg", 0), metadata);
        when(sleeper.leagueChain("L1")).thenReturn(List.of(league));
        when(sleeper.leagueUsers("L1")).thenReturn(List.of(Map.of("user_id", "u1", "display_name", "Alice"),
                Map.of("user_id", "u2", "display_name", "Bob")));
        when(managers.upsert("u1", "Alice")).thenReturn(101L);
        when(managers.upsert("u2", "Bob")).thenReturn(102L);
        when(sleeper.rosters("L1")).thenReturn(List.of(
                rosterObject(1, "u1", 8, 6, 1500, 42),
                rosterObject(2, "u2", 10, 4, 1600, 5)));

        service.ingestChain(Sport.NFL, "L1");

        ArgumentCaptor<List<RosterSeasonRepository.Upsert>> captor = ArgumentCaptor.forClass(List.class);
        verify(rosterSeasons).upsertAll(captor.capture());
        List<RosterSeasonRepository.Upsert> rows = captor.getValue();
        assertEquals(2, rows.size());

        RosterSeasonRepository.Upsert roster1 = rows.stream().filter(r -> r.rosterId() == 1).findFirst().orElseThrow();
        assertEquals(101L, roster1.managerId());
        assertEquals(1500.42, roster1.pointsFor(), 1e-9);
        assertNull(roster1.finalPlacement(), "roster 1 is not the champion");

        RosterSeasonRepository.Upsert roster2 = rows.stream().filter(r -> r.rosterId() == 2).findFirst().orElseThrow();
        assertEquals(1600.05, roster2.pointsFor(), 1e-9);
        assertEquals(1, roster2.finalPlacement(), "metadata.latest_league_winner_roster_id says roster 2 won it");
    }

    @Test
    void weeklyIngestNeverFetchesPastLastScoredLeg() {
        Map<String, Object> league = leagueObject("L2", 2025, Map.of("last_scored_leg", 3), Map.of());
        when(sleeper.leagueChain("L2")).thenReturn(List.of(league));
        when(sleeper.leagueUsers("L2")).thenReturn(List.of());
        when(sleeper.rosters("L2")).thenReturn(List.of());
        when(weekPoints.storedWeeks(55L)).thenReturn(Set.of());
        when(sleeper.matchups(eq("L2"), anyInt())).thenReturn(List.of());

        service.ingestChain(Sport.NFL, "L2");

        verify(sleeper, times(1)).matchups("L2", 1);
        verify(sleeper, times(1)).matchups("L2", 2);
        verify(sleeper, times(1)).matchups("L2", 3);
        verify(sleeper, never()).matchups(eq("L2"), intThat(w -> w > 3));
    }

    @Test
    void reRunningSkipsAlreadyStoredWeeksExceptTheMostRecentlyScoredOne() {
        Map<String, Object> league = leagueObject("L3", 2025, Map.of("last_scored_leg", 4), Map.of());
        when(sleeper.leagueChain("L3")).thenReturn(List.of(league));
        when(sleeper.leagueUsers("L3")).thenReturn(List.of());
        when(sleeper.rosters("L3")).thenReturn(List.of());
        // Weeks 1-3 already cached; week 4 is the newest scored week and must be re-fetched.
        when(weekPoints.storedWeeks(55L)).thenReturn(Set.of(1, 2, 3));
        when(sleeper.matchups(eq("L3"), anyInt())).thenReturn(List.of());

        service.ingestChain(Sport.NFL, "L3");

        verify(sleeper, never()).matchups("L3", 1);
        verify(sleeper, never()).matchups("L3", 2);
        verify(sleeper, never()).matchups("L3", 3);
        verify(sleeper, times(1)).matchups("L3", 4);
    }

    @Test
    void weeklyPointsAreStoredPerRosterFromThePointsField() {
        Map<String, Object> league = leagueObject("L4", 2025, Map.of("last_scored_leg", 1), Map.of());
        when(sleeper.leagueChain("L4")).thenReturn(List.of(league));
        when(sleeper.leagueUsers("L4")).thenReturn(List.of());
        when(sleeper.rosters("L4")).thenReturn(List.of());
        when(weekPoints.storedWeeks(55L)).thenReturn(Set.of());
        when(sleeper.matchups("L4", 1)).thenReturn(List.of(
                matchup(1, 101.5), matchup(2, 88.25)));

        service.ingestChain(Sport.NFL, "L4");

        ArgumentCaptor<RosterWeekPointsRepository.Row> captor = ArgumentCaptor.forClass(RosterWeekPointsRepository.Row.class);
        verify(weekPoints, times(2)).upsert(captor.capture());
        List<RosterWeekPointsRepository.Row> rows = captor.getAllValues();
        assertEquals(101.5, rows.stream().filter(r -> r.rosterId() == 1).findFirst().orElseThrow().startersPoints());
        assertEquals(88.25, rows.stream().filter(r -> r.rosterId() == 2).findFirst().orElseThrow().startersPoints());
    }

    private static Map<String, Object> matchup(int rosterId, double points) {
        Map<String, Object> m = new HashMap<>();
        m.put("roster_id", rosterId);
        // "points" is the roster's scored total for the week; starters_points is a
        // per-slot array, not a scalar -- see the ingest comment for how this was found.
        m.put("points", points);
        m.put("starters_points", List.of());
        m.put("players_points", Map.of());
        return m;
    }
}
