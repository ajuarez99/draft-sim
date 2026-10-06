package com.ballknowers.draftsim.api;

import com.ballknowers.draftsim.engine.NextMatchupService;
import com.ballknowers.draftsim.engine.ScheduleGridService;
import com.ballknowers.draftsim.store.LeagueMembership;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.util.Optional;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * specs/017-nba-schedule-grid T021/T037: both routes are scoped like every league route. A request
 * with no {@code X-Sleeper-User} (or a league the caller can't see) is a 404 and never reaches the
 * service. Runs without Postgres; the JSON shape is pinned by {@link ScheduleControllersMvcIT}.
 */
class ScheduleControllersTest {

    private final LeagueMembership membership = mock(LeagueMembership.class);
    private final ScheduleGridService grid = mock(ScheduleGridService.class);
    private final NextMatchupService next = mock(NextMatchupService.class);
    private final MockMvc mvc = MockMvcBuilders
            .standaloneSetup(new ScheduleController(grid, membership), new NextMatchupController(next, membership))
            .build();

    @Test
    void bothRoutesAre404WithNoHeaderAndNeverCallTheService() throws Exception {
        when(membership.visibleLeague(any(), any())).thenReturn(Optional.empty());
        mvc.perform(get("/api/leagues/L1/schedule")).andExpect(status().isNotFound());
        mvc.perform(get("/api/leagues/L1/next-matchup")).andExpect(status().isNotFound());
        mvc.perform(get("/api/leagues/L1/schedule").header("X-Sleeper-User", "stranger"))
                .andExpect(status().isNotFound());
        mvc.perform(get("/api/leagues/L1/next-matchup").header("X-Sleeper-User", "stranger"))
                .andExpect(status().isNotFound());
        verifyNoInteractions(grid, next);
    }
}
