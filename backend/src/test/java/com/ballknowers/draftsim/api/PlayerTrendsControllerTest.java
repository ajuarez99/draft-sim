package com.ballknowers.draftsim.api;

import com.ballknowers.draftsim.config.PlayerTrendsProperties;
import com.ballknowers.draftsim.domain.Sport;
import com.ballknowers.draftsim.engine.PlayerTrendsService;
import com.ballknowers.draftsim.engine.PlayerTrendsService.PlayerTrends;
import com.ballknowers.draftsim.engine.ScheduleGridService;
import com.ballknowers.draftsim.sport.SportRules;
import com.ballknowers.draftsim.sport.SportRulesRegistry;
import com.ballknowers.draftsim.store.LeagueMemberRepository;
import com.ballknowers.draftsim.store.LeagueMembership;
import com.ballknowers.draftsim.store.LeagueRepository;
import com.ballknowers.draftsim.store.LeagueRepository.LeagueRow;
import com.ballknowers.draftsim.store.ManagerRepository;
import com.ballknowers.draftsim.store.PlayerAbsenceRepository;
import com.ballknowers.draftsim.store.PlayerGameRepository;
import com.ballknowers.draftsim.store.PlayerRepository;
import com.ballknowers.draftsim.store.RosterSeasonRepository;
import com.ballknowers.draftsim.store.RosterWeekPointsRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.ResponseEntity;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * specs/019-minutes-streaming T010: the state check order of data-model "States" (config, then sport,
 * then no games), with mocked collaborators, plus the controller's scoping 404.
 */
@ExtendWith(MockitoExtension.class)
class PlayerTrendsControllerTest {

    private static final PlayerTrendsProperties LOADED = new PlayerTrendsProperties(6, 5, 5, 3, 14, 10, 20, 0.9);
    private static final PlayerTrendsProperties ABSENT =
            new PlayerTrendsProperties(null, null, null, null, null, null, null, null);

    @Mock private PlayerGameRepository playerGames;
    @Mock private PlayerAbsenceRepository absences;
    @Mock private PlayerRepository players;
    @Mock private LeagueRepository leagues;
    @Mock private ScheduleGridService grid;
    @Mock private RosterSeasonRepository rosterSeasons;
    @Mock private LeagueMemberRepository members;
    @Mock private ManagerRepository managers;
    @Mock private RosterWeekPointsRepository weekPoints;
    @Mock private SportRulesRegistry rules;
    @Mock private SportRules nbaRules;
    @Mock private SportRules nflRules;
    @Mock private LeagueMembership membership;

    private static LeagueRow league(Sport sport) {
        return new LeagueRow(7L, sport, "sl-1", "L", 2026, 12, List.of(), 1.0, null, "pre_draft");
    }

    @BeforeEach
    void rules() {
        lenient().when(rules.get(Sport.NBA)).thenReturn(nbaRules);
        lenient().when(rules.get(Sport.NFL)).thenReturn(nflRules);
        lenient().when(nbaRules.playsMultipleGamesPerScoringPeriod()).thenReturn(true);
        lenient().when(nflRules.playsMultipleGamesPerScoringPeriod()).thenReturn(false);
        lenient().when(playerGames.seasonPlayerGames(any(), anyInt())).thenReturn(List.of());
        lenient().when(playerGames.seasonTeamGames(any(), anyInt())).thenReturn(List.of());
        lenient().when(absences.forSeason(any(), anyInt())).thenReturn(List.of());
        lenient().when(leagues.scoringOf(7L)).thenReturn(Map.of("pts", 1.0));
        lenient().when(rosterSeasons.rosteredPlayers(7L)).thenReturn(Optional.empty());
        lenient().when(weekPoints.breakdownsFor(7L, 2026)).thenReturn(List.of());
        lenient().when(grid.forLeague("sl-1")).thenReturn(Optional.empty());
    }

    private PlayerTrendsService service(PlayerTrendsProperties props) {
        return new PlayerTrendsService(props, new com.ballknowers.draftsim.engine.SeasonBoxCache(playerGames, com.ballknowers.draftsim.config.NbaGameProperties.none()), absences, players, leagues, grid, rosterSeasons,
                members, managers, weekPoints, rules);
    }

    private static void assertUnavailable(PlayerTrends t, String reason) {
        assertFalse(t.available());
        assertEquals(reason, t.reason());
        assertTrue(t.risers().isEmpty());
        assertTrue(t.fallers().isEmpty());
        assertTrue(t.streaming().isEmpty());
        assertNull(t.oneGameCredit());
        assertNotNull(t.windows(), "the wire type's windows is never null");
    }

    @Test
    void notConfiguredWinsBeforeAnythingIsRead() {
        PlayerTrends t = service(ABSENT).read(league(Sport.NBA), "u1");
        assertUnavailable(t, "NOT_CONFIGURED");
        assertNull(t.roleThresholdMinutes());
        assertEquals("nba", t.sport());
        assertEquals(2026, t.season());
        verifyNoInteractions(playerGames);
    }

    @Test
    void aFootballLeagueIsNotBasketball() {
        PlayerTrends t = service(LOADED).read(league(Sport.NFL), "u1");
        assertUnavailable(t, "NOT_BASKETBALL");
        assertEquals("nfl", t.sport());
        verifyNoInteractions(playerGames);
    }

    @Test
    void noPlayerGamesInEitherSeasonIsNoGames() {
        PlayerTrends t = service(LOADED).read(league(Sport.NBA), "u1");
        assertUnavailable(t, "NO_GAMES");
        assertEquals(6, t.roleThresholdMinutes());
    }

    @Test
    void anInvisibleLeagueIs404AndNothingIsRead() {
        when(membership.visibleLeague("sl-1", "stranger")).thenReturn(Optional.empty());
        PlayerTrendsController c = new PlayerTrendsController(service(LOADED), membership);
        ResponseEntity<PlayerTrends> r = c.playerTrends("sl-1", "stranger");
        assertEquals(404, r.getStatusCode().value());
        verifyNoInteractions(playerGames);
    }

    @Test
    void aVisibleLeagueIs200() {
        when(membership.visibleLeague("sl-1", "u1")).thenReturn(Optional.of(league(Sport.NFL)));
        PlayerTrendsController c = new PlayerTrendsController(service(LOADED), membership);
        ResponseEntity<PlayerTrends> r = c.playerTrends("sl-1", "u1");
        assertEquals(200, r.getStatusCode().value());
        assertEquals("NOT_BASKETBALL", r.getBody().reason());
    }
}
