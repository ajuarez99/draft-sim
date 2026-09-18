package com.ballknowers.draftsim.api;

import com.ballknowers.draftsim.domain.Sport;
import com.ballknowers.draftsim.engine.ExpectedWinsService;
import com.ballknowers.draftsim.engine.RosterManagementService;
import com.ballknowers.draftsim.engine.TransactionAnalysisService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.ResponseEntity;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.when;

/**
 * The response shapes contracts/league-analytics-api.md promises
 * (specs/004-ffwrapped-feature-parity, T024/T026/T045/T098).
 *
 * <p>These mock the SERVICE, not a stack of repositories -- which is the
 * distinction {@code LeagueAnalysisServiceTest}'s note is about. Mocking five
 * repositories to re-test a service's arithmetic proves only that the mocks
 * were wired; mocking one service to pin what a controller puts on the wire is
 * the thing being tested. {@code IngestControllerTest} already works this way.
 *
 * <p>What these protect is narrow and real: a field rename or a dropped key
 * silently breaks a page that reads it, and nothing else in the suite would
 * notice.
 */
@ExtendWith(MockitoExtension.class)
class LeagueAnalyticsContractTest {

    @Mock private RosterManagementService rosterManagement;
    @Mock private TransactionAnalysisService transactionAnalysis;
    @Mock private ExpectedWinsService expectedWins;

    private static final String LEAGUE = "L1";

    @SuppressWarnings("unchecked")
    private static Map<String, Object> bodyOf(ResponseEntity<Map<String, Object>> r) {
        assertEquals(200, r.getStatusCode().value());
        return (Map<String, Object>) assertDoesNotThrow(r::getBody);
    }

    // ------------------------------------------------- roster management

    @Test
    void rosterManagementCarriesEveryFieldThePageReads() {
        when(rosterManagement.forLeague(LEAGUE)).thenReturn(Optional.of(
                new RosterManagementService.Result(true, null, 2026, null, Sport.NFL, 1, List.of(
                        new RosterManagementService.TeamRow(4, 17L, "Master Bates", "abc",
                                164.96, 174.16, 0.947, 1, List.of(2))))));

        Map<String, Object> body = bodyOf(
                new RosterManagementController(rosterManagement, transactionAnalysis)
                        .rosterManagement(LEAGUE));

        assertEquals(true, body.get("available"));
        assertEquals(2026, body.get("season"));
        assertEquals("nfl", body.get("sport"));
        assertEquals(1, body.get("weeksScored"));

        @SuppressWarnings("unchecked")
        Map<String, Object> team = ((List<Map<String, Object>>) body.get("teams")).getFirst();
        assertEquals(4, team.get("rosterId"));
        assertEquals(17L, team.get("managerId"));
        assertEquals("Master Bates", team.get("teamName"));
        assertEquals("abc", team.get("avatarId"));
        assertEquals(164.96, team.get("totalPoints"));
        assertEquals(174.16, team.get("potentialPoints"));
        assertEquals(0.947, team.get("efficiency"));
        assertEquals(1, team.get("weeksCounted"));
        assertEquals(List.of(2), team.get("weeksExcluded"));
    }

    /**
     * US2.4 on the wire. The key must be PRESENT and null, not absent: a page
     * that reads a missing key gets undefined and can render it as anything,
     * including 100%.
     */
    @Test
    void anEfficiencyOfNullIsCarriedAsAnExplicitNull() {
        when(rosterManagement.forLeague(LEAGUE)).thenReturn(Optional.of(
                new RosterManagementService.Result(true, null, 2026, null, Sport.NFL, 1, List.of(
                        new RosterManagementService.TeamRow(4, null, "Roster 4", null,
                                0, 0, null, 0, List.of())))));

        @SuppressWarnings("unchecked")
        Map<String, Object> team = ((List<Map<String, Object>>) bodyOf(
                new RosterManagementController(rosterManagement, transactionAnalysis)
                        .rosterManagement(LEAGUE)).get("teams")).getFirst();

        assertTrue(team.containsKey("efficiency"), "the key must exist even when the value is null");
        assertNull(team.get("efficiency"));
    }

    /** T024: a league with no scored weeks answers with a reason, not zeros. */
    @Test
    void aLeagueWithNoScoredWeeksCarriesAReasonAndNoTeams() {
        when(rosterManagement.forLeague(LEAGUE)).thenReturn(Optional.of(
                new RosterManagementService.Result(false, "no scored weeks yet for this league",
                        2026, null, Sport.NBA, 0, List.of())));

        Map<String, Object> body = bodyOf(
                new RosterManagementController(rosterManagement, transactionAnalysis)
                        .rosterManagement(LEAGUE));

        assertEquals(false, body.get("available"));
        assertEquals("no scored weeks yet for this league", body.get("reason"));
        assertEquals(List.of(), body.get("teams"));
        assertEquals("nba", body.get("sport"), "the sport is still known when the answer is not");
    }

    /**
     * The season-fallback signal. When the rail links a league page at a season
     * that has not been played, the page answers about an earlier one -- and
     * has to SAY so. Present-and-null when it did not move.
     */
    @Test
    void aSeasonFallbackIsCarriedSoThePageCanAnnounceIt() {
        when(rosterManagement.forLeague(LEAGUE)).thenReturn(Optional.of(
                new RosterManagementService.Result(true, null, 2025, 2026, Sport.NBA, 21, List.of(
                        new RosterManagementService.TeamRow(1, 3L, "Fat Slovenian Revenge", null,
                                5339.5, 5547.0, 0.963, 21, List.of())))));

        Map<String, Object> body = bodyOf(
                new RosterManagementController(rosterManagement, transactionAnalysis)
                        .rosterManagement(LEAGUE));

        assertEquals(2025, body.get("season"), "the season actually answered about");
        assertEquals(2026, body.get("requestedSeason"), "the season the reader asked for");
    }

    @Test
    void noFallbackCarriesAnExplicitNullRatherThanAMissingKey() {
        when(rosterManagement.forLeague(LEAGUE)).thenReturn(Optional.of(
                new RosterManagementService.Result(true, null, 2026, null, Sport.NFL, 1, List.of())));

        Map<String, Object> body = bodyOf(
                new RosterManagementController(rosterManagement, transactionAnalysis)
                        .rosterManagement(LEAGUE));

        assertTrue(body.containsKey("requestedSeason"));
        assertNull(body.get("requestedSeason"));
    }

    @Test
    void anUnknownLeagueIs404RatherThanAnEmptyBody() {
        when(rosterManagement.forLeague("nope")).thenReturn(Optional.empty());
        assertEquals(404, new RosterManagementController(rosterManagement, transactionAnalysis)
                .rosterManagement("nope").getStatusCode().value());
    }

    // ------------------------------------------------------ expected wins

    @Test
    void expectedWinsCarriesTheLuckDiscriminatorAndItsWeeks() {
        when(expectedWins.forLeague(LEAGUE)).thenReturn(Optional.of(
                new ExpectedWinsService.Result(true, null, 2026, null, Sport.NFL, 1, 130.1, List.of(
                        new ExpectedWinsService.TeamRow(6, 9L, "jpelwell", null,
                                0.45, 1.0, 0.55, -12.4,
                                ExpectedWinsService.LuckSource.SWING_WEEKS,
                                List.of(new ExpectedWinsService.SwingWeek(1, true, 146.16, 7, "She Hocken")))))));

        Map<String, Object> body = bodyOf(new ExpectedWinsController(expectedWins).expectedWins(LEAGUE));
        assertEquals(130.1, body.get("leagueAveragePpg"));

        @SuppressWarnings("unchecked")
        Map<String, Object> team = ((List<Map<String, Object>>) body.get("teams")).getFirst();
        assertEquals(0.45, team.get("expectedWins"));
        assertEquals(1.0, team.get("actualWins"));
        assertEquals(0.55, team.get("winsAboveExpected"));
        assertEquals(-12.4, team.get("strengthOfSchedule"));
        assertEquals("SWING_WEEKS", team.get("luckSource"));

        @SuppressWarnings("unchecked")
        Map<String, Object> swing = ((List<Map<String, Object>>) team.get("swingWeeks")).getFirst();
        assertEquals(1, swing.get("week"));
        // WON/LOST on the wire rather than a bare boolean: the page prints the
        // word, and a boolean named `won` would make "lost" the absence of a
        // value rather than a value.
        assertEquals("WON", swing.get("result"));
        assertEquals(7, swing.get("weeklyRank"));
        assertEquals("She Hocken", swing.get("opponent"));
    }

    /** US3.4: the other branch carries no swing weeks at all, never an empty heading. */
    @Test
    void consistentOpponentScoringCarriesNoSwingWeeks() {
        when(expectedWins.forLeague(LEAGUE)).thenReturn(Optional.of(
                new ExpectedWinsService.Result(true, null, 2026, null, Sport.NBA, 3, 228.0, List.of(
                        new ExpectedWinsService.TeamRow(2, 3L, "Hoop Dreams", null,
                                2.4, 2.0, -0.4, 5.1,
                                ExpectedWinsService.LuckSource.CONSISTENT_OPPONENT_SCORING,
                                List.of())))));

        @SuppressWarnings("unchecked")
        Map<String, Object> team = ((List<Map<String, Object>>) bodyOf(
                new ExpectedWinsController(expectedWins).expectedWins(LEAGUE)).get("teams")).getFirst();

        assertEquals("CONSISTENT_OPPONENT_SCORING", team.get("luckSource"));
        assertEquals(List.of(), team.get("swingWeeks"));
    }

    // -------------------------------------------------------- transactions

    @Test
    void transactionsCarryCountsGradesAndTheRankDirection() {
        when(transactionAnalysis.forLeague(LEAGUE)).thenReturn(Optional.of(
                new TransactionAnalysisService.Result(true, null, 2026, Sport.NFL,
                        List.of(new TransactionAnalysisService.ManagerCounts(
                                17L, "Master Bates", Map.of("WAIVER", 2, "FREE_AGENT", 1), 3)),
                        List.of(),
                        List.of(new TransactionAnalysisService.Add(1, "Torta Pounder with Cheese",
                                new TransactionAnalysisService.MovedPlayer("12711", "Tyler Loop", "K", 2.0, 1),
                                new TransactionAnalysisService.MovedPlayer("2", "Najee Harris", "RB", null, 0),
                                0, "WAIVER", "complete")),
                        "LOWER_IS_BETTER")));

        Map<String, Object> body = bodyOf(
                new RosterManagementController(rosterManagement, transactionAnalysis)
                        .transactions(LEAGUE));

        // Stated on the wire, because a 2 beside a name is otherwise ambiguous.
        assertEquals("LOWER_IS_BETTER", body.get("rankDirection"));
        assertEquals(List.of(), body.get("trades"), "no trades is an empty list, not a missing key");

        @SuppressWarnings("unchecked")
        Map<String, Object> manager = ((List<Map<String, Object>>) body.get("byManager")).getFirst();
        assertEquals("Master Bates", manager.get("teamName"));
        assertEquals(3, manager.get("total"));
        assertEquals(Map.of("WAIVER", 2, "FREE_AGENT", 1), manager.get("counts"));

        @SuppressWarnings("unchecked")
        Map<String, Object> add = ((List<Map<String, Object>>) body.get("adds")).getFirst();
        assertEquals(1, add.get("week"));
        assertEquals(0, add.get("faabBid"), "a bid of zero is a real bid, distinct from null");

        @SuppressWarnings("unchecked")
        Map<String, Object> added = (Map<String, Object>) add.get("added");
        assertEquals("Tyler Loop", added.get("playerName"));
        assertEquals("K", added.get("position"));
        assertEquals(2.0, added.get("postMovePositionalRank"));
        assertEquals(1, added.get("weeksCounted"));

        // An ungraded player carries the key with null, so the page can tell
        // "no week played yet" from "ranked first".
        @SuppressWarnings("unchecked")
        Map<String, Object> dropped = (Map<String, Object>) add.get("dropped");
        assertTrue(dropped.containsKey("postMovePositionalRank"));
        assertNull(dropped.get("postMovePositionalRank"));
    }

    /** The refusal still states the rank direction: the page renders its legend either way. */
    @Test
    void transactionsWithNoneIngestedStillCarryTheRankDirection() {
        when(transactionAnalysis.forLeague(LEAGUE)).thenReturn(Optional.of(
                TransactionAnalysisServiceResults.unavailable()));

        Map<String, Object> body = bodyOf(
                new RosterManagementController(rosterManagement, transactionAnalysis)
                        .transactions(LEAGUE));

        assertEquals(false, body.get("available"));
        assertEquals("LOWER_IS_BETTER", body.get("rankDirection"));
        assertEquals(List.of(), body.get("adds"));
    }

    /** The package-private factory is not reachable from here; build the record directly. */
    private static final class TransactionAnalysisServiceResults {
        static TransactionAnalysisService.Result unavailable() {
            return new TransactionAnalysisService.Result(false, "no transactions ingested for this league yet",
                    2026, Sport.NFL, List.of(), List.of(), List.of(), "LOWER_IS_BETTER");
        }
    }
}
