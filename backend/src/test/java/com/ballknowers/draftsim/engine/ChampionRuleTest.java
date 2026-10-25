package com.ballknowers.draftsim.engine;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** specs/047-hall-of-fame T003: the one champion rule. */
class ChampionRuleTest {

    @Test
    void placementOneOnACompleteSeasonIsChampion() {
        assertTrue(ChampionRule.isChampion(1, true));
    }

    @Test
    void nullPlacementIsNeverChampion() {
        assertFalse(ChampionRule.isChampion(null, true));
        assertFalse(ChampionRule.isChampion(null, false));
    }

    @Test
    void otherPlacementsAreNotChampion() {
        assertFalse(ChampionRule.isChampion(2, true));
        assertFalse(ChampionRule.isChampion(12, true));
    }

    @Test
    void placementOneOnAnIncompleteSeasonIsNotChampion() {
        // A pre-V21 stale placement=1 next to an unfinished season must not resurface as a trophy.
        assertFalse(ChampionRule.isChampion(1, false));
    }
}
