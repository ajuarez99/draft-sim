package com.ballknowers.draftsim.api;

import com.ballknowers.draftsim.TestAdmin;
import com.ballknowers.draftsim.domain.Sport;
import com.ballknowers.draftsim.engine.LeagueAvailability;
import com.ballknowers.draftsim.engine.PowerRankingService;
import com.ballknowers.draftsim.ingest.espn.EspnIT;
import com.ballknowers.draftsim.store.LeagueRepository;
import com.ballknowers.draftsim.store.PowerRankingRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Spec 035 T040/T041. {@code LeagueAvailability} is the one answer to "can this league show page X": the
 * availability endpoint serializes it, and the server-side 422s read the same object. These tests tie the
 * two together (every governed route is refused with exactly the code the map reports) so a second,
 * drifting list cannot appear.
 */
class LeagueAvailabilityIT extends EspnIT {

    private static final String SLEEPER_KEY = "it035-sleeper-league";

    @Autowired LeagueRepository leagues;
    @Autowired LeagueAvailability availability;
    @Autowired PowerRankingService power;
    @Autowired PowerRankingRepository rankings;

    @AfterEach
    void dropSleeperLeague() {
        jdbc.update("delete from league where sleeper_id = ?", SLEEPER_KEY);
    }

    private MockHttpServletRequestBuilder asAllan(String path) {
        // The operator token lets the test see a league without building membership rows for it.
        return get(path).header("X-Sleeper-User", ALLAN).header("X-Admin-Token", TestAdmin.TOKEN);
    }

    /** No identity, only the operator token: sees any league, which is how a Sleeper league with no members is read. */
    private MockHttpServletRequestBuilder asOperator(String path) {
        return get(path).header("X-Admin-Token", TestAdmin.TOKEN);
    }

    private void espnLeague() throws Exception {
        add(ALLAN, 1, false).andExpect(status().isOk());
    }

    @Test
    void anEspnLeagueListsEveryFixedAndDataDrivenGapWithCodeAndSentence() throws Exception {
        espnLeague();
        String json = mvc.perform(asAllan("/api/leagues/" + LEAGUE_KEY + "/availability"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.platform").value("ESPN"))
                .andExpect(jsonPath("$.leagueKey").value(LEAGUE_KEY))
                .andExpect(jsonPath("$.unavailable.draftRoom.code").value("PLATFORM_NOT_SUPPORTED"))
                .andExpect(jsonPath("$.unavailable.draftGrades.code").value("PLATFORM_NOT_SUPPORTED"))
                .andExpect(jsonPath("$.unavailable.schedule.code").value("PLATFORM_NOT_SUPPORTED"))
                .andExpect(jsonPath("$.unavailable.superlatives.code").value("PLATFORM_NOT_SUPPORTED"))
                .andExpect(jsonPath("$.unavailable.weeklyReport.code").value("PLATFORM_NOT_SUPPORTED"))
                .andExpect(jsonPath("$.unavailable.expectedWins.code").value("PLATFORM_NOT_SUPPORTED"))
                .andExpect(jsonPath("$.unavailable.forecast.code").value("PLATFORM_NOT_SUPPORTED"))
                .andExpect(jsonPath("$.unavailable.recap.code").value("PLATFORM_NOT_SUPPORTED"))
                // The synthetic scoring has statIds with no CONFIRMED translation, so the more specific reason wins.
                .andExpect(jsonPath("$.unavailable.stats.code").value("SCORING_UNTRANSLATED"))
                .andExpect(jsonPath("$.unavailable.powerBaseline.code").value("SCORING_UNTRANSLATED"))
                .andExpect(jsonPath("$.unavailable.lineups.code").value("NO_PLAYER_POINTS"))
                // Three refused players are on rosters: the ambiguous one, the ghost, the IDP-position one.
                .andExpect(jsonPath("$.unmatchedPlayers").value(3))
                .andExpect(jsonPath("$.unmatched.length()").value(3))   // FR-007: counted AND named
                .andExpect(jsonPath("$.unmatched[*].name").value(org.hamcrest.Matchers.hasItem("Zed Ambig")))
                .andExpect(jsonPath("$.lastRefresh.succeededAt").isNotEmpty())
                .andExpect(jsonPath("$.lastRefresh.failure").doesNotExist())
                .andExpect(jsonPath("$.canReplaceCookies").value(false))   // no cookies stored
                .andReturn().getResponse().getContentAsString();
        // The in-scope pages are never listed, and the synthetic format is one the odds model can seed.
        for (String inScope : List.of("analysis", "playoffOdds", "history", "power", "standings", "transactions")) {
            assertFalse(json.contains("\"" + inScope + "\":{"), inScope + " must not be unavailable: " + json);
        }
        verifyNoInteractions(sleeper);
    }

    @Test
    void onlyTheCookieOwnerIsToldTheyMayReplaceThem() throws Exception {
        privateLeague = true;
        add(ALLAN, 1, true).andExpect(status().isOk());
        add(STRANGER, 2, false).andExpect(status().isForbidden());   // a stranger cannot even read it
        // A second member who can SEE the league (claims team 2 with cookies of their own is a different path;
        // here the link makes ALLAN the owner, and the admin-token caller with no identity is never one).
        mvc.perform(asAllan("/api/leagues/" + LEAGUE_KEY + "/availability")).andExpect(jsonPath("$.canReplaceCookies").value(true));
        mvc.perform(asOperator("/api/leagues/" + LEAGUE_KEY + "/availability")).andExpect(jsonPath("$.canReplaceCookies").value(false));
    }

    @Test
    void aSleeperLeagueHasNothingUnavailableAndIsNeverRefused() throws Exception {
        leagues.upsert(Sport.NFL, 2099, SLEEPER_KEY, null, "It035 Sleeper", 4, "{}", "{}", List.of("QB"), "in_season");
        mvc.perform(asOperator("/api/leagues/" + SLEEPER_KEY + "/availability"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.platform").value("SLEEPER"))
                .andExpect(jsonPath("$.unavailable").isEmpty())
                .andExpect(jsonPath("$.unmatchedPlayers").value(0));
        // Whatever the route answers for a league with no data, it is not the platform refusal (FR-013).
        for (String route : routes()) {
            int status = mvc.perform(asOperator("/api/leagues/" + SLEEPER_KEY + "/" + route)).andReturn().getResponse().getStatus();
            assertNotEquals(422, status, route);
        }
    }

    /** One concrete path per governed route segment, taken from the same list the guard reads. */
    private static List<String> routes() {
        return LeagueAvailability.ROUTES.values().stream().flatMap(List::stream)
                .map(r -> r.equals("players") ? "players/it035-1" : r.equals("weekly-report") || r.equals("recap") ? r + "/1" : r)
                .toList();
    }

    @Test
    void everyGovernedRouteIsRefusedWithExactlyTheCodeTheMapReports() throws Exception {
        espnLeague();
        LeagueAvailability.View view = viewOf();
        int refused = 0;
        for (Map.Entry<String, List<String>> page : LeagueAvailability.ROUTES.entrySet()) {
            for (String segment : page.getValue()) {
                String path = segment.equals("players") ? "players/it035-1"
                        : segment.equals("weekly-report") || segment.equals("recap") ? segment + "/1" : segment;
                LeagueAvailability.Reason expected = view.unavailable().get(page.getKey());
                if (expected == null) {
                    int status = mvc.perform(asAllan("/api/leagues/" + LEAGUE_KEY + "/" + path)).andReturn().getResponse().getStatus();
                    assertNotEquals(422, status, path + " is available, so it must not be refused");
                    continue;
                }
                mvc.perform(asAllan("/api/leagues/" + LEAGUE_KEY + "/" + path))
                        .andExpect(status().isUnprocessableEntity())
                        .andExpect(jsonPath("$.error").value(expected.code()))
                        .andExpect(jsonPath("$.message").value(expected.message()));
                refused++;
            }
        }
        assertTrue(refused >= 10, "expected the stats, schedule, superlatives, weekly-report, expected-wins, forecast, recap routes: " + refused);
        verifyNoInteractions(sleeper);
    }

    private LeagueAvailability.View viewOf() {
        return availability.of(leagues.bySleeperId(LEAGUE_KEY).orElseThrow());
    }

    @Test
    void aDataDrivenGapIsRefusedToo() throws Exception {
        espnLeague();
        mvc.perform(asAllan("/api/leagues/" + LEAGUE_KEY + "/analysis")).andExpect(status().isOk());
        jdbc.update("update league set settings_json = settings_json || '{\"espn_multi_week_periods\": true}'::jsonb where sleeper_id = ?", LEAGUE_KEY);
        mvc.perform(asAllan("/api/leagues/" + LEAGUE_KEY + "/availability"))
                .andExpect(jsonPath("$.unavailable.analysis.code").value("MULTI_WEEK_PERIODS"));
        mvc.perform(asAllan("/api/leagues/" + LEAGUE_KEY + "/analysis"))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.error").value("MULTI_WEEK_PERIODS"));
        // The other in-scope pages are untouched by that one gap.
        mvc.perform(asAllan("/api/leagues/" + LEAGUE_KEY + "/history")).andExpect(status().isOk());
    }

    @Test
    void aPlayoffFormatTheOddsModelCannotSeedIsReportedNotGuessed() throws Exception {
        espnLeague();
        jdbc.update("update league set settings_json = settings_json || '{\"playoff_seed_type\": -1}'::jsonb where sleeper_id = ?", LEAGUE_KEY);
        mvc.perform(asAllan("/api/leagues/" + LEAGUE_KEY + "/availability"))
                .andExpect(jsonPath("$.unavailable.playoffOdds.code").value("PLAYOFF_FORMAT_UNSUPPORTED"));
    }

    @Test
    void aStrangerGetsTheSame404ForAnEspnPageAsForAnyLeagueThatIsNotTheirs() throws Exception {
        espnLeague();
        int espn = mvc.perform(get("/api/leagues/" + LEAGUE_KEY + "/stats").header("X-Sleeper-User", STRANGER)).andReturn().getResponse().getStatus();
        int nothing = mvc.perform(get("/api/leagues/no-such-league/stats").header("X-Sleeper-User", STRANGER)).andReturn().getResponse().getStatus();
        assertEquals(nothing, espn, "the guard is not an existence oracle");
        mvc.perform(get("/api/leagues/" + LEAGUE_KEY + "/availability").header("X-Sleeper-User", STRANGER)).andExpect(status().isNotFound());
    }

    @Test
    void theUntranslatedScoringBaselineIsRefusedAndNothingIsWritten() throws Exception {
        espnLeague();
        LeagueRepository.LeagueRow row = leagues.bySleeperId(LEAGUE_KEY).orElseThrow();
        PowerRankingService.Week0Result r = power.computeWeek0IfMissing(row.id(), LEAGUE_KEY, row.season());
        assertEquals(0, r.entries().length);
        assertTrue(r.skipped() != null && r.skipped().contains("can't translate"), String.valueOf(r.skipped()));
        assertFalse(rankings.exists(row.id(), row.season(), 0, "COMPUTED_REALIZED"), "a frozen baseline must not be written from untranslated scoring");
    }

    @Test
    void theUntranslatedReasonNamesItemsAndCapsTheList() {
        LeagueRepository.LeagueRow sleeper = new LeagueRepository.LeagueRow(1, Sport.NFL, "s", "n", 2026, 12, List.of("QB"), 0, null, "in_season");
        assertTrue(LeagueAvailability.scoringUntranslated(sleeper).isEmpty());
        var espn = new LeagueRepository.LeagueRow(2, Sport.NFL, "e", "n", 2026, 12, List.of("QB"), 0, null, "in_season",
                com.ballknowers.draftsim.domain.Platform.ESPN, "1", List.of("a", "b", "c", "d", "e", "f", "g", "h", "i", "j"), List.of());
        String message = LeagueAvailability.scoringUntranslated(espn).orElseThrow().message();
        assertTrue(message.contains("a, b, c, d, e, f, g, h") && message.contains("and 2 more"), message);
        assertEquals("SCORING_UNTRANSLATED", LeagueAvailability.scoringUntranslated(espn).orElseThrow().code());
    }
}
