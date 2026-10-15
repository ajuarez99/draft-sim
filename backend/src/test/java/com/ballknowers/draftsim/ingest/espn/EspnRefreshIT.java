package com.ballknowers.draftsim.ingest.espn;

import com.ballknowers.draftsim.store.LeagueRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anySet;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNotNull;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Spec 035 T046/T047: refreshing a stored ESPN league re-reads the CURRENT season only, with the stored
 * credential; a failure stores a CODE (never message text), leaves the data and the credential's
 * {@code last_worked_at} alone, and a success stamps the credential.
 */
class EspnRefreshIT extends EspnIT {

    /** The only strings {@code league_refresh.last_failure} may ever hold for an ESPN league. */
    static final Set<String> FIXED_CODES = Set.of("ESPN_ACCESS_DENIED", "ESPN_NOT_FOUND", "ESPN_HTTP_503",
            "ESPN_SHAPE_CHANGED", "UNSUPPORTED_SCORING", EspnLeagueIngestService.REFRESH_ERROR);

    @Autowired EspnLeagueIngestService espn;
    @Autowired LeagueRepository leagues;
    @Autowired com.ballknowers.draftsim.store.LeagueRefreshRepository refreshRepo;

    private LeagueRepository.LeagueRow current() {
        return leagues.bySleeperId(LEAGUE_KEY).orElseThrow();
    }

    private String lastFailure() {
        return jdbc.queryForObject("select last_failure from league_refresh r join league l on l.id = r.league_id where l.sleeper_id = ?",
                String.class, LEAGUE_KEY);
    }

    private Object lastWorked() {
        return jdbc.queryForObject("select last_worked_at from league_credential where platform_league_id = ?", Object.class, LEAGUE_ID);
    }

    private int[] snapshot() {
        return new int[] {count("select count(*) from roster_season rs join league l on l.id = rs.league_id where l.platform_league_id = ?", LEAGUE_ID),
                count("select count(*) from league_transaction t join league l on l.id = t.league_id where l.platform_league_id = ?", LEAGUE_ID),
                count("select count(*) from roster_week_points t join league l on l.id = t.league_id where l.platform_league_id = ?", LEAGUE_ID),
                count("select count(*) from draft_pick where draft_id in (select id from draft where sleeper_draft_id like ?)", "espn-fba-" + LEAGUE_ID + "-%")};
    }

    @Test
    void refreshReadsOnlyTheCurrentSeasonWithTheStoredCredentialAndStampsIt() throws Exception {
        privateLeague = true;
        add(ALLAN, 1, true).andExpect(status().isOk());
        assertNull(lastWorked(), "an add that sent the cookies has not yet 'used the stored ones'");
        clearInvocations(client);

        assertTrue(espn.refreshCurrent(current()));

        verify(client, times(1)).league(eq("fba"), eq(LEAGUE_ID), eq(2027), anySet(), isNotNull());
        verify(client, times(0)).league(eq("fba"), eq(LEAGUE_ID), eq(2026), anySet(), any());
        verify(client, times(0)).leagueHistory(anyString(), anyString(), anyInt(), anySet(), any());
        assertNotNull(lastWorked(), "the stored cookies worked, so last_worked_at moves");
        assertNull(lastFailure());
        verifyNoInteractions(sleeper);
    }

    @Test
    void everyFailureStoresACodeAndNothingElseAndLeavesTheDataAndTheCredentialStamp() throws Exception {
        privateLeague = true;
        add(ALLAN, 1, true).andExpect(status().isOk());
        assertTrue(espn.refreshCurrent(current()));
        Object stampBefore = lastWorked();
        int[] before = snapshot();

        List<RuntimeException> failures = List.of(new EspnAccessDenied(), new EspnNotFound(), new EspnHttpError(503),
                new EspnShapeChanged("settings.name"), new EspnUnsupported("UNSUPPORTED_SCORING", "H2H_CATEGORY"),
                // Anything that is not ESPN's own: its message could hold anything, and must not be stored.
                new IllegalStateException("boom " + SENTINEL_S2 + " " + SENTINEL_SWID));
        for (RuntimeException failure : failures) {
            doThrow(failure).when(client).league(eq("fba"), eq(LEAGUE_ID), eq(2027), anySet(), any());
            assertFalse(espn.refreshCurrent(current()), failure.getClass().getSimpleName());
            String stored = lastFailure();
            assertTrue(FIXED_CODES.contains(stored), "last_failure is a fixed code, not text: " + stored);
            assertFalse(stored.contains("SENTINEL"), stored);
            assertEquals(java.util.Arrays.toString(before), java.util.Arrays.toString(snapshot()), "failed refresh leaves the data as it was");
            assertEquals(stampBefore, lastWorked(), "a failed read never counts as the cookies working");
        }
        assertEquals(EspnLeagueIngestService.REFRESH_ERROR, lastFailure(), "the last one was the non-ESPN exception");
    }

    @Test
    void aTransactionsFailureStoresItsCodeButTheLeagueDataStillRefreshes() throws Exception {
        add(ALLAN, 1, false).andExpect(status().isOk());
        doThrow(new EspnHttpError(503)).when(client).transactions(anyString(), anyString(), anyInt(), anyInt(), any());
        assertFalse(espn.refreshCurrent(current()), "the read was only partly successful");
        assertEquals("ESPN_HTTP_503", lastFailure());
        assertTrue(count("select count(*) from roster_season rs join league l on l.id = rs.league_id where l.sleeper_id = ?", LEAGUE_KEY) > 0);
    }

    @Test
    void aSuccessfulRefreshAfterAFailureClearsTheFailedState() throws Exception {
        add(ALLAN, 1, false).andExpect(status().isOk());
        doThrow(new EspnNotFound()).when(client).league(eq("fba"), eq(LEAGUE_ID), eq(2027), anySet(), any());
        assertFalse(espn.refreshCurrent(current()));
        assertEquals("ESPN_NOT_FOUND", lastFailure());
        org.mockito.Mockito.reset(client);
        // espnSetUp's stubs were just cleared; restore the one the refresh needs.
        org.mockito.Mockito.when(client.league(eq("fba"), eq(LEAGUE_ID), eq(2027), anySet(), any())).thenAnswer(inv -> EspnFixtures.nbaLeague());
        org.mockito.Mockito.when(client.players(anyString(), anyInt())).thenThrow(new EspnNotFound());
        org.mockito.Mockito.when(client.transactions(anyString(), anyString(), anyInt(), anyInt(), any())).thenAnswer(inv -> EspnFixtures.noTransactions());
        assertTrue(espn.refreshCurrent(current()));
        assertFalse(com.ballknowers.draftsim.refresh.RefreshDecision.failed(refreshRepo.find(current().id()).orElseThrow()));
    }

    @Test
    void replacingTheCookiesForgetsARefusalTheOldOnesCausedButNotAnotherFailure() throws Exception {
        privateLeague = true;
        add(ALLAN, 1, true).andExpect(status().isOk());
        doThrow(new EspnAccessDenied()).when(client).league(eq("fba"), eq(LEAGUE_ID), eq(2027), anySet(), any());
        assertFalse(espn.refreshCurrent(current()));
        assertEquals("ESPN_ACCESS_DENIED", lastFailure());

        org.mockito.Mockito.doReturn(EspnFixtures.nbaLeague()).when(client).league(eq("fba"), eq(LEAGUE_ID), eq(2027), anySet(), any());
        String put = "{\"espnS2\":\"" + SENTINEL_S2 + "\",\"swid\":\"" + SENTINEL_SWID + "\"}";
        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                        .put("/api/setup/espn/league/" + LEAGUE_KEY + "/credential").header("X-Sleeper-User", ALLAN)
                        .contentType("application/json").content(put))
                .andExpect(status().isNoContent());
        assertNull(lastFailure(), "the back-off from the old cookies' refusal is gone");

        doThrow(new EspnShapeChanged("settings.name")).when(client).league(eq("fba"), eq(LEAGUE_ID), eq(2027), anySet(), any());
        assertFalse(espn.refreshCurrent(current()));
        org.mockito.Mockito.doReturn(EspnFixtures.nbaLeague()).when(client).league(eq("fba"), eq(LEAGUE_ID), eq(2027), anySet(), any());
        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                        .put("/api/setup/espn/league/" + LEAGUE_KEY + "/credential").header("X-Sleeper-User", ALLAN)
                        .contentType("application/json").content(put))
                .andExpect(status().isNoContent());
        assertEquals("ESPN_SHAPE_CHANGED", lastFailure(), "new cookies say nothing about a changed ESPN shape");
    }
}
