package com.ballknowers.draftsim.engine;

import com.ballknowers.draftsim.ingest.espn.EspnIT;
import org.junit.jupiter.api.Test;

import static org.mockito.Mockito.verifyNoInteractions;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Spec 035 F10 / T044. A player the ESPN matcher refused is stored as {@code espn:<id>} and must still
 * RENDER, by the name ESPN gave him, on a page that is in scope: the Transactions view used to print
 * "Unknown player" for any key it could not find in the player table. One reader is tested; the ones that
 * show no player names (roster management, history, power) read counts, not names.
 */
class UnmatchedPlayerRenderIT extends EspnIT {

    @Test
    void aRefusedPlayerOnTheTransactionsPageIsShownByNameAndMarkedUnmatched() throws Exception {
        add(ALLAN, 1, false).andExpect(status().isOk());

        mvc.perform(get("/api/leagues/" + LEAGUE_KEY + "/transactions").header("X-Sleeper-User", ALLAN))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.available").value(true))
                // 5004: a failed claim for a player whose ESPN position the app does not model (never matched).
                .andExpect(jsonPath("$.adds[?(@.added.playerName=='Zed Itcc One')].added.unmatched").value(true))
                .andExpect(jsonPath("$.adds[?(@.added.playerName=='Zed Itcc One')].added.playerId").value("espn:9990007"))
                // 5001: the player he dropped is the ghost nobody could match.
                .andExpect(jsonPath("$.adds[?(@.added.playerName=='Zed Itaa Two')].dropped.playerName").value("Zed Ghost"))
                .andExpect(jsonPath("$.adds[?(@.added.playerName=='Zed Itaa Two')].dropped.unmatched").value(true))
                // A matched player is rendered as before: no unmatched flag, no team key (FR-013 payload shape).
                .andExpect(jsonPath("$.adds[?(@.added.playerName=='Zed Itaa Two')].added.unmatched").isEmpty())
                .andExpect(jsonPath("$.adds[?(@.added.playerName=='Zed Itaa Two')].added.team").isEmpty())
                // And never the placeholder.
                .andExpect(jsonPath("$.adds[?(@.added.playerName=='Unknown player')]").isEmpty())
                .andExpect(jsonPath("$.adds[?(@.dropped.playerName=='Unknown player')]").isEmpty());
        verifyNoInteractions(sleeper);
    }
}
