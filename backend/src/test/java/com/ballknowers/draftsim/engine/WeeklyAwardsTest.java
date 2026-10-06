package com.ballknowers.draftsim.engine;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

class WeeklyAwardsTest {

    private static final List<Double> SCORES = List.of(150.0, 120.5, 120.5, 99.0, 80.0);

    @Test
    void ranksAreOneBasedFromTheTop() {
        assertEquals(1, WeeklyAwards.competitionRank(SCORES, 150.0));
        assertEquals(4, WeeklyAwards.competitionRank(SCORES, 99.0));
        assertEquals(5, WeeklyAwards.competitionRank(SCORES, 80.0));
    }

    @Test
    void tiedScoresShareTheHigherRankAndTheNextRankIsSkipped() {
        assertEquals(2, WeeklyAwards.competitionRank(SCORES, 120.5));
        // 99.0 is 4th, not 3rd: the tie occupies ranks 2 and 3.
        assertEquals(4, WeeklyAwards.competitionRank(SCORES, 99.0));
    }
}
