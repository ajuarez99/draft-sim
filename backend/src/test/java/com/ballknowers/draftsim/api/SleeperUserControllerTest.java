package com.ballknowers.draftsim.api;

import com.ballknowers.draftsim.domain.Sport;
import com.ballknowers.draftsim.ingest.SleeperClient;
import com.ballknowers.draftsim.store.LeagueRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class SleeperUserControllerTest {

    @Mock private SleeperClient sleeper;
    @Mock private LeagueRepository leagues;

    private SleeperUserController controller() {
        return new SleeperUserController(sleeper, leagues);
    }

    @Test
    void userReturns200WithMappedFieldsForARealUsername() {
        when(sleeper.user("popsharky")).thenReturn(Map.of(
                "user_id", "1122386008709910528", "username", "popsharky",
                "display_name", "popsharky", "avatar", "e1d4ebf9ea0760f248119d4ec2ac5a63"));

        ResponseEntity<Map<String, Object>> resp = controller().user("popsharky");

        assertEquals(HttpStatus.OK, resp.getStatusCode());
        assertEquals("1122386008709910528", resp.getBody().get("sleeperUserId"));
        assertEquals("popsharky", resp.getBody().get("username"));
        assertEquals("e1d4ebf9ea0760f248119d4ec2ac5a63", resp.getBody().get("avatar"));
    }

    @Test
    void userReturns404WhenSleeperAnswersAJsonNullBody() {
        // Sleeper answers HTTP 200 with a literal `null` body for an unknown
        // username, not a 404 -- verified live against api.sleeper.app.
        when(sleeper.user("no-such-sleeper-user")).thenReturn(null);

        ResponseEntity<Map<String, Object>> resp = controller().user("no-such-sleeper-user");

        assertEquals(HttpStatus.NOT_FOUND, resp.getStatusCode());
    }

    @Test
    void leaguesFetchesBothSportsAndTagsEachRowWithItsSport() {
        lenient().when(sleeper.state("nfl")).thenReturn(Map.of("league_season", "2026"));
        lenient().when(sleeper.state("nba")).thenReturn(Map.of("league_season", "2026"));
        when(sleeper.leagues("42", "nfl", 2026)).thenReturn(List.of(
                Map.of("league_id", "111", "name", "Football League", "total_rosters", 12,
                        "draft_id", "d1", "status", "in_season", "previous_league_id", "999")));
        Map<String, Object> nbaLeague = new java.util.HashMap<>();
        nbaLeague.put("league_id", "222");
        nbaLeague.put("name", "Basketball League");
        nbaLeague.put("total_rosters", 10);
        nbaLeague.put("draft_id", "d2");
        nbaLeague.put("status", "drafting");
        nbaLeague.put("previous_league_id", null);
        when(sleeper.leagues("42", "nba", 2026)).thenReturn(List.of(nbaLeague));
        when(leagues.bySleeperId("111")).thenReturn(Optional.empty());
        when(leagues.bySleeperId("222")).thenReturn(Optional.of(
                new LeagueRepository.LeagueRow(1L, Sport.NBA, "222", "Basketball League", 2026, 10,
                        List.of(), 0.0, null)));

        List<Map<String, Object>> result = controller().leagues("42");

        assertEquals(2, result.size());
        assertEquals(Sport.NFL, result.get(0).get("sport"));
        assertEquals(false, result.get(0).get("ingested"), "not in league table -- not yet set up");
        assertEquals(Sport.NBA, result.get(1).get("sport"));
        assertEquals(true, result.get(1).get("ingested"), "already has a league row");
    }

    @Test
    void leaguesReadsTheOffseasonSeasonFromLeagueSeasonNotSeason() {
        // The whole point of state(sport).league_season over a hardcoded/
        // calendar-derived year: nfl and nba can disagree during the offseason.
        lenient().when(sleeper.state("nfl")).thenReturn(Map.of("season", "2025", "league_season", "2026"));
        lenient().when(sleeper.state("nba")).thenReturn(Map.of("season", "2026", "league_season", "2027"));
        when(sleeper.leagues("42", "nfl", 2026)).thenReturn(List.of());
        when(sleeper.leagues("42", "nba", 2027)).thenReturn(List.of());

        controller().leagues("42");
        // Mockito's strict stubbing on the exact (userId, sport, season) args above
        // is itself the assertion -- a wrong season would fail with an
        // UnnecessaryStubbingException / unmatched-call error rather than reach here.
    }
}
