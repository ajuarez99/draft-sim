package com.ballknowers.draftsim.api;

import com.ballknowers.draftsim.ingest.espn.EspnIT;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Spec 035 F1 / F13 / T045. ESPN managers are never fitted and never seatable in a mock draft (ESPN drafts
 * are never simulated), while their history and head-to-head pages work, per platform, without Sleeper.
 */
class EspnManagersAndMocksIT extends EspnIT {

    private long managerId(String espnMember) {
        return jdbc.queryForObject("select id from manager where sleeper_user_id = ?", Long.class, "espn:{" + espnMember + "}");
    }

    @Test
    void anEspnManagerIsNotListedAsAFittedManager() throws Exception {
        add(ALLAN, 1, false).andExpect(status().isOk());
        mvc.perform(get("/api/managers?sport=nba").header("X-Sleeper-User", ALLAN))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[?(@.manager=='Alpha Manager')]").isEmpty())
                .andExpect(jsonPath("$[?(@.manager=='Bravo Manager')]").isEmpty());
        verifyNoInteractions(sleeper);
    }

    @Test
    void anEspnManagerCannotBeSeatedInAMock() throws Exception {
        add(ALLAN, 1, false).andExpect(status().isOk());
        long espn = managerId("IT035-B");
        int before = jdbc.queryForObject("select count(*) from mock_draft_session", Integer.class);
        mvc.perform(post("/api/mocks").header("X-Sleeper-User", ALLAN).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"sport\":\"nba\",\"teams\":12,\"userSlot\":1,\"managerSeats\":{\"2\":" + espn + "}}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value(org.hamcrest.Matchers.containsString("cannot seat an ESPN manager")));
        assertEquals(before, jdbc.queryForObject("select count(*) from mock_draft_session", Integer.class), "nothing was created");
    }

    @Test
    void historyAndHeadToHeadWorkForEspnManagersWithoutAskingSleeper() throws Exception {
        add(ALLAN, 1, false).andExpect(status().isOk());
        long a = managerId("IT035-A");
        long b = managerId("IT035-B");
        mvc.perform(get("/api/managers/" + a + "/history").header("X-Sleeper-User", ALLAN))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.manager").value("Alpha Manager"));
        mvc.perform(get("/api/managers/" + a + "/versus/" + b).header("X-Sleeper-User", ALLAN))
                .andExpect(status().isOk());
        verifyNoInteractions(sleeper);
    }
}
