package com.ballknowers.draftsim.ingest.espn;

import com.ballknowers.draftsim.refresh.LeagueRefreshService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.TestPropertySource;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anySet;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Spec 035 T046: a visit to a stale ESPN league starts the ESPN refresh (not the Sleeper chain walk), and a
 * visit to a fresh one starts nothing. On-visit refresh is on here; every other test turns it off.
 */
@TestPropertySource(properties = "refresh.on-visit.enabled=true")
class EspnRefreshOnVisitIT extends EspnIT {

    @Autowired LeagueRefreshService refresh;

    @Test
    void aStaleEspnLeagueRefreshesItsCurrentSeasonOnVisitAndAFreshOneDoesNot() throws Exception {
        add(ALLAN, 1, false).andExpect(status().isOk());

        refresh.trigger(LEAGUE_KEY);   // just stored: fresh
        Thread.sleep(300);
        verify(client, times(1)).league(eq("fba"), eq(LEAGUE_ID), eq(2027), anySet(), any());

        jdbc.update("update league_refresh set last_success_at = now() - interval '2 hours' "
                + "where league_id = (select id from league where sleeper_id = ?)", LEAGUE_KEY);
        refresh.trigger(LEAGUE_KEY);   // stale: starts one run, on its own thread
        verify(client, timeout(5000).times(2)).league(eq("fba"), eq(LEAGUE_ID), eq(2027), anySet(), any());
        // The finished season was read once, at add time, and is never re-read.
        verify(client, times(1)).league(eq("fba"), eq(LEAGUE_ID), eq(2026), anySet(), any());
        verifyNoInteractions(sleeper);
    }
}
