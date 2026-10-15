package com.ballknowers.draftsim.api;

import com.ballknowers.draftsim.TestAdmin;
import com.ballknowers.draftsim.ingest.espn.EspnIT;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import java.util.List;
import java.util.function.Supplier;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Spec 035 plan F4 / T037. With an ESPN league and draft stored and a Sleeper client that must never
 * be called, every Sleeper-only route answers 422 {@code PLATFORM_NOT_SUPPORTED}, and the in-scope
 * league routes never reach Sleeper.
 */
class EspnPlatformGuardIT extends EspnIT {

    private MockHttpServletRequestBuilder asAllan(MockHttpServletRequestBuilder b) {
        return b.header("X-Sleeper-User", ALLAN).header("X-Admin-Token", TestAdmin.TOKEN).contentType(MediaType.APPLICATION_JSON);
    }

    @Test
    void everySleeperOnlyRouteRefusesAnEspnKey() throws Exception {
        add(ALLAN, 1, false).andExpect(status().isOk());

        String d = DRAFT_KEY;
        String l = LEAGUE_KEY;
        List<Supplier<MockHttpServletRequestBuilder>> routes = List.of(
                () -> get("/api/drafts/" + d + "/pool"),
                () -> get("/api/drafts/" + d + "/seats"),
                () -> get("/api/drafts/" + d + "/board"),
                () -> get("/api/drafts/" + d + "/grades"),
                () -> post("/api/drafts/" + d + "/track"),
                () -> get("/api/drafts/" + d + "/live-stream"),
                () -> put("/api/drafts/" + d + "/reversal-round").content("{}"),
                () -> post("/api/drafts/" + d + "/picks").content("{}"),
                () -> post("/api/mocks/from-draft/" + d),
                () -> post("/api/sims").content("{\"draftSleeperId\":\"" + d + "\",\"mySlot\":1,\"iterations\":10}"),
                () -> post("/api/setup/league/" + l),
                () -> post("/api/setup/league-history/" + l),
                () -> post("/api/ingest/league/" + l),
                () -> post("/api/ingest/league-history/" + l),
                () -> post("/api/ingest/player-games/" + l),
                () -> post("/api/ingest/transactions/" + l),
                () -> post("/api/ingest/all/" + l),
                () -> post("/api/ingest/brackets/" + l));
        for (Supplier<MockHttpServletRequestBuilder> route : routes) {
            mvc.perform(asAllan(route.get())).andExpect(status().isUnprocessableEntity())
                    .andExpect(jsonPath("$.error").value("PLATFORM_NOT_SUPPORTED"));
        }
        verifyNoInteractions(sleeper);
    }

    @Test
    void aStrangerGetsTheSame404AsAnyOtherLeagueNotAnEspnSpecificAnswer() throws Exception {
        add(ALLAN, 1, false).andExpect(status().isOk());
        // Not a member: the guard must not reveal that this key is a real ESPN draft.
        int espn = mvc.perform(get("/api/drafts/" + DRAFT_KEY + "/pool").header("X-Sleeper-User", STRANGER))
                .andReturn().getResponse().getStatus();
        int nothing = mvc.perform(get("/api/drafts/no-such-draft/pool").header("X-Sleeper-User", STRANGER))
                .andReturn().getResponse().getStatus();
        assertEquals(nothing, espn);
        assertEquals(404, espn);
    }

    @Test
    void theInScopeLeagueRoutesAnswerWithoutCallingSleeper() throws Exception {
        add(ALLAN, 1, false).andExpect(status().isOk());
        for (String path : List.of("history", "power", "analysis", "roster-management", "transactions")) {
            mvc.perform(get("/api/leagues/" + LEAGUE_KEY + "/" + path).header("X-Sleeper-User", ALLAN))
                    .andExpect(status().isOk());
        }
        verifyNoInteractions(sleeper);
    }

    @Test
    void aSleeperKeyIsNeverRefused() throws Exception {
        // The guard only acts on a stored ESPN row: an unknown or Sleeper key passes straight through to
        // the route's own handling (here a 404 for an unknown draft).
        mvc.perform(get("/api/drafts/some-sleeper-draft/pool").header("X-Sleeper-User", ALLAN)).andExpect(status().isNotFound());
    }
}
