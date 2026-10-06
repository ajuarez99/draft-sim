package com.ballknowers.draftsim.engine;

import java.util.List;

/**
 * Rules shared by the weekly report and anything that restates it (the AI recap's input builder),
 * so the two cannot compute the same figure two ways (specs/020-ai-weekly-recap, review F6).
 */
public final class WeeklyAwards {

    private WeeklyAwards() {}

    /**
     * Competition rank ("1224"): the 1-based position of {@code points} in {@code scoresDesc},
     * where tied scores share the higher (better) rank. {@code scoresDesc} must be sorted
     * descending and contain {@code points}.
     */
    public static int competitionRank(List<Double> scoresDesc, double points) {
        return scoresDesc.indexOf(points) + 1;
    }
}
