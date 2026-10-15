package com.ballknowers.draftsim.store;

import com.ballknowers.draftsim.domain.Sport;
import com.ballknowers.draftsim.ingest.espn.EspnIT;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Spec 035 F1: an ESPN draft never reaches the draft model. */
class DraftRepositoryPlatformIT extends EspnIT {

    @Autowired DraftRepository drafts;
    @Autowired ManagerRepository managers;

    @Test
    void allCompletedPicksIgnoresACompleteEspnDraft() throws Exception {
        add(ALLAN, 1, false).andExpect(status().isOk());
        assertEquals("complete", jdbc.queryForObject("select status from draft where sleeper_draft_id = ?", String.class, DRAFT_KEY));
        assertEquals(3, jdbc.queryForObject(
                "select count(*) from draft_pick where draft_id = (select id from draft where sleeper_draft_id = ?)", Integer.class, DRAFT_KEY));

        long espnDraft = jdbc.queryForObject("select id from draft where sleeper_draft_id = ?", Long.class, DRAFT_KEY);
        assertTrue(drafts.allCompletedPicks(Sport.NBA).stream().noneMatch(p -> p.draftId() == espnDraft),
                "the ESPN draft's picks must not come back");
    }

    @Test
    void espnManagersAreListedSoFittingAndSeatingCanExcludeThem() throws Exception {
        add(ALLAN, 1, false).andExpect(status().isOk());
        long espnAlpha = managers.idsBySleeperUserId().get("espn:{IT035-A}");
        assertTrue(managers.espnManagerIds().contains(espnAlpha));
        assertFalse(managers.espnManagerIds().contains(managers.idsBySleeperUserId().get(ALLAN)));
    }

    @Test
    void anEspnDraftRowHasNoStartTimeAndNoReversalRound() throws Exception {
        add(ALLAN, 1, false).andExpect(status().isOk());
        assertEquals(0, jdbc.queryForObject("select count(*) from draft where sleeper_draft_id = ? and start_time is not null", Integer.class, DRAFT_KEY));
        assertEquals(0, jdbc.queryForObject("select reversal_round from draft where sleeper_draft_id = ?", Integer.class, DRAFT_KEY));
    }
}
