package com.ballknowers.draftsim.ingest;

import com.ballknowers.draftsim.domain.Sport;
import com.ballknowers.draftsim.store.LeagueMatchupRepository;
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
 * specs/006-deeper-history-both-sports T021 (SC-003), pinning the
 * baseline.md T003 defect and its fix directly.
 *
 * <p>{@code metadata.latest_league_winner_roster_id} on an IN-PROGRESS league
 * names LAST season's winner, not this one's -- Sleeper carries the key on
 * the new season's league object too. Verified live:
 * {@code GET https://api.sleeper.app/v1/league/1346366555759341568} returned
 * {@code "status": "in_season"} with that metadata key set to {@code "1"},
 * which is exactly the shape reproduced below. The fix
 * ({@code LeagueHistoryIngestService#ingestStandings}) gates the champion
 * write on the league's own top-level {@code status} being
 * {@code "complete"} -- this test proves both halves of that gate with the
 * SAME input map, varying only {@code status}.
 */
@ExtendWith(MockitoExtension.class)
class ChampionOnlyWhenCompleteTest {

    @Mock private SleeperClient sleeper;
    @Mock private LeagueRepository leagues;
    @Mock private ManagerRepository managers;
    @Mock private LeagueMemberRepository leagueMembers;
    @Mock private RosterSeasonRepository rosterSeasons;
    @Mock private RosterWeekPointsRepository weekPoints;
    @Mock private LeagueMatchupRepository fixtures;
    @Mock private TransactionIngestService transactions;

    private LeagueHistoryIngestService service;

    @BeforeEach
    void setUp() {
        service = new LeagueHistoryIngestService(sleeper, leagues, managers, leagueMembers, rosterSeasons,
                weekPoints, fixtures, transactions);
        lenient().when(leagues.upsert(any(), anyInt(), any(), any(), any(), anyInt(), any(), any(), any(), any()))
                .thenReturn(55L);
        lenient().when(sleeper.leagueUsers(anyString())).thenReturn(List.of(
                Map.of("user_id", "u1", "display_name", "popsharky")));
        lenient().when(managers.upsert("u1", "popsharky", null)).thenReturn(101L);
        lenient().when(sleeper.rosters(anyString())).thenReturn(List.of(rosterObject(1, "u1")));
        lenient().when(weekPoints.storedWeeks(55L)).thenReturn(Set.of());
    }

    private static Map<String, Object> rosterObject(int rosterId, String ownerId) {
        Map<String, Object> settings = new HashMap<>();
        settings.put("wins", 1);
        settings.put("losses", 0);
        Map<String, Object> r = new HashMap<>();
        r.put("roster_id", rosterId);
        r.put("owner_id", ownerId);
        r.put("settings", settings);
        return r;
    }

    /**
     * Reproduces league {@code 1346366555759341568}'s own shape: roster 1
     * named as {@code latest_league_winner_roster_id}, varying only
     * {@code status}, the same field V21/{@link LeagueMapper#upsert} now
     * forwards and this ingest pass now gates on.
     */
    private static Map<String, Object> leagueObject(String leagueId, String status) {
        Map<String, Object> l = new HashMap<>();
        l.put("league_id", leagueId);
        l.put("season", 2026);
        l.put("previous_league_id", null);
        l.put("status", status);
        l.put("settings", Map.of("last_scored_leg", 0));
        l.put("metadata", Map.of("latest_league_winner_roster_id", "1"));
        return l;
    }

    private List<RosterSeasonRepository.Upsert> ingestAndCapture(Map<String, Object> league, String leagueId) {
        when(sleeper.leagueChain(leagueId)).thenReturn(List.of(league));
        service.ingestChain(Sport.NFL, leagueId);
        ArgumentCaptor<List<RosterSeasonRepository.Upsert>> captor = ArgumentCaptor.forClass(List.class);
        verify(rosterSeasons).upsertAll(captor.capture());
        return captor.getValue();
    }

    @Test
    void inSeasonLeagueCrownsNoOne() {
        List<RosterSeasonRepository.Upsert> rows = ingestAndCapture(leagueObject("L-in-season", "in_season"), "L-in-season");

        assertEquals(1, rows.size());
        assertNull(rows.get(0).finalPlacement(),
                "status: in_season -- latest_league_winner_roster_id names the PREVIOUS season's winner, not this one's");
    }

    @Test
    void completeLeagueCrownsTheStoredWinner() {
        List<RosterSeasonRepository.Upsert> rows = ingestAndCapture(leagueObject("L-complete", "complete"), "L-complete");

        assertEquals(1, rows.size());
        assertEquals(1, rows.get(0).finalPlacement(), "status: complete -- the metadata key is trustworthy again");
    }
}
