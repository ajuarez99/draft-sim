package com.ballknowers.draftsim.engine;

import com.ballknowers.draftsim.domain.*;
import com.ballknowers.draftsim.ingest.BoardService;
import com.ballknowers.draftsim.ingest.SleeperClient;
import com.ballknowers.draftsim.sport.SportRules;
import com.ballknowers.draftsim.store.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.*;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * claude/plan-review-league-suite.md finding 4 (a tie rule, decided once) and
 * Phase A acceptance criterion 4 (an OUT/Doubtful starter must not silently
 * score as though he played) are both regression-shaped, not eyeball-shaped --
 * pinned here rather than only checked by looking at a real league's numbers.
 */
@ExtendWith(MockitoExtension.class)
class PowerRankingServiceTest {

    @Mock private SleeperClient sleeper;
    @Mock private BoardService boards;
    @Mock private PlayerRepository players;
    @Mock private LeagueRepository leagues;
    @Mock private ManagerRepository managers;
    @Mock private RosterSeasonRepository rosterSeasons;
    @Mock private RosterWeekPointsRepository weekPoints;
    @Mock private PowerRankingRepository rankings;
    @Mock private SportRules rules;

    private PowerRankingService service;

    @BeforeEach
    void setUp() {
        service = new PowerRankingService(sleeper, boards, players, leagues, managers,
                rosterSeasons, weekPoints, rankings, rules);
    }

    // ---- rankDescending (via computeRealized, its simplest caller) ----

    @Test
    void tiedScoresShareTheLowerRankAndTheNextRankIsSkipped() {
        when(rosterSeasons.forLeague(1L)).thenReturn(List.of());
        when(weekPoints.through(1L, 3)).thenReturn(List.of(
                new RosterWeekPointsRepository.WeekPoint(1, 10, 100.0),
                new RosterWeekPointsRepository.WeekPoint(1, 20, 100.0),   // ties roster 10
                new RosterWeekPointsRepository.WeekPoint(1, 30, 50.0)));

        var result = service.computeRealized(1L, 2025, 3);

        Map<Integer, Integer> rankByRoster = new HashMap<>();
        for (var e : result) rankByRoster.put(e.rosterId(), e.rank());
        assertEquals(1, rankByRoster.get(10));
        assertEquals(1, rankByRoster.get(20), "tied score must share rank 1, not break arbitrarily");
        assertEquals(3, rankByRoster.get(30), "rank 2 is skipped after a two-way tie for 1st");
    }

    @Test
    void realizedIsTheAverageAcrossStoredWeeksNotTheSum() {
        when(rosterSeasons.forLeague(1L)).thenReturn(List.of());
        when(weekPoints.through(1L, 2)).thenReturn(List.of(
                new RosterWeekPointsRepository.WeekPoint(1, 10, 80.0),
                new RosterWeekPointsRepository.WeekPoint(2, 10, 120.0)));

        var result = service.computeRealized(1L, 2025, 2);

        assertEquals(1, result.length);
        assertEquals(100.0, result[0].score(), 1e-9, "average of 80 and 120, not their sum");
    }

    // ---- market value: injury exclusion (Phase A AC4) ----

    private static Player player(long id, String sleeperId, Position pos, String injuryStatus) {
        return new Player(id, Sport.NFL, sleeperId, "Player " + id, List.of(pos), "KC",
                "Active", injuryStatus, 25, 3);
    }

    @Test
    void anOutStarterIsExcludedFromMarketValueNotScoredAsThoughHePlayed() {
        LeagueRepository.LeagueRow leagueRow = new LeagueRepository.LeagueRow(
                1L, "sleeper-league", "Test League", 2025, 2, List.of("QB", "BN"), 0.5, null);
        when(leagues.byId(1L)).thenReturn(Optional.of(leagueRow));

        Player healthy = player(1L, "sp1", Position.RB, null);
        Player out = player(2L, "sp2", Position.WR, "Out");
        when(players.idsBySleeperId(Sport.NFL)).thenReturn(Map.of("sp1", 1L, "sp2", 2L));
        when(boards.currentBoard(Sport.NFL)).thenReturn(List.of(
                new BoardEntry(healthy, 10.0, 1),
                new BoardEntry(out, 20.0, 1)));
        when(managers.idsBySleeperUserId()).thenReturn(Map.of("u1", 900L));

        Map<String, Object> roster = new HashMap<>();
        roster.put("roster_id", 5);
        roster.put("owner_id", "u1");
        roster.put("players", List.of("sp1", "sp2"));
        when(sleeper.rosters("SL")).thenReturn(List.of(roster));

        ArgumentCaptor<RosterState> stateCaptor = ArgumentCaptor.forClass(RosterState.class);
        when(rules.startingLineupValue(stateCaptor.capture(), any())).thenReturn(42.0);

        var result = service.computeMarketValue(1L, "SL", 2025, 1);

        RosterState builtState = stateCaptor.getValue();
        assertEquals(1, builtState.size(), "the OUT player must not be in the lineup fed to the engine");
        assertEquals(1, builtState.count(Position.RB));
        assertEquals(0, builtState.count(Position.WR), "the OUT WR was excluded, not silently scored");

        assertEquals(1, result.length);
        assertEquals(900L, result[0].managerId());
        assertTrue(result[0].note().contains("OUT/Doubtful"), "the exclusion must be stated, not silent");
    }

    @Test
    void aPlayerNotOnTheBoardIsExcludedAndNotedRatherThanCrashing() {
        LeagueRepository.LeagueRow leagueRow = new LeagueRepository.LeagueRow(
                1L, "sleeper-league", "Test League", 2025, 1, List.of("QB", "BN"), 0.5, null);
        when(leagues.byId(1L)).thenReturn(Optional.of(leagueRow));
        when(players.idsBySleeperId(Sport.NFL)).thenReturn(Map.of());
        when(boards.currentBoard(Sport.NFL)).thenReturn(List.of());
        when(managers.idsBySleeperUserId()).thenReturn(Map.of());

        Map<String, Object> roster = new HashMap<>();
        roster.put("roster_id", 7);
        roster.put("owner_id", "u-unknown");
        roster.put("players", List.of("sp-unrostered-on-board"));
        when(sleeper.rosters("SL")).thenReturn(List.of(roster));
        when(rules.startingLineupValue(any(), any())).thenReturn(0.0);

        var result = service.computeMarketValue(1L, "SL", 2025, 1);

        assertEquals(1, result.length);
        assertTrue(result[0].note().contains("not on the board"));
    }
}
