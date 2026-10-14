package com.ballknowers.draftsim.api;

import com.ballknowers.draftsim.engine.RivalryService;
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
 * specs/034 T017: the route is scoped like every league route -- no identity, or a league the caller can't
 * see, is a 404 that never reaches the service. Runs without Postgres; the JSON shape and the 200 path are
 * pinned by {@code RivalryParityIT}.
 */
class RivalryControllerTest {

    private final LeagueMembership membership = mock(LeagueMembership.class);
    private final RivalryService service = mock(RivalryService.class);
    private final MockMvc mvc = MockMvcBuilders.standaloneSetup(new RivalryController(service, membership)).build();

    @Test
    void aStrangerOrNoHeaderIs404AndNeverCallsTheService() throws Exception {
        when(membership.visibleLeague(any(), any())).thenReturn(Optional.empty());
        mvc.perform(get("/api/leagues/L1/rivalries")).andExpect(status().isNotFound());
        mvc.perform(get("/api/leagues/L1/rivalries").header("X-Sleeper-User", "stranger"))
                .andExpect(status().isNotFound());
        verifyNoInteractions(service);
    }
}
