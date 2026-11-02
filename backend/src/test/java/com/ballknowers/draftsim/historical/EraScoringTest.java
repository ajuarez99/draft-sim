package com.ballknowers.draftsim.historical;

import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/** Spec 053 T006: research R2's outcome table, as the era rule must reproduce it. */
class EraScoringTest {

    private static final double THRESHOLD = 0.90;
    private static final Set<String> ALL_TRACKED = Set.of("pts", "reb", "ast", "stl", "blk", "to", "tpm");

    /** Legacy Ball Knowers scoring (application.yml historical.scoring). */
    private static Map<String, Double> base() {
        Map<String, Double> m = new LinkedHashMap<>();
        m.put("pts", 0.5); m.put("reb", 1.0); m.put("ast", 1.0); m.put("stl", 2.0); m.put("blk", 2.0);
        m.put("to", -1.0); m.put("tpm", 0.5); m.put("dd", 1.0); m.put("td", 2.0);
        m.put("bonus_pt_40p", 2.0); m.put("bonus_pt_50p", 2.0); m.put("bonus_reb_20p", 1.0);
        m.put("bonus_ast_15p", 1.0); m.put("tf", -2.0); m.put("ff", -2.0);
        return m;
    }

    private static Map<String, Double> cov(double pts, double reb, double ast, double stl, double blk,
                                           double to, double tpm) {
        Map<String, Double> m = new LinkedHashMap<>();
        m.put("pts", pts); m.put("reb", reb); m.put("ast", ast); m.put("stl", stl); m.put("blk", blk);
        m.put("to", to); m.put("tpm", tpm);
        return m;
    }

    private static EraScoring.Era kaggle(Map<String, Double> coverage, Set<String> tracked) {
        return EraScoring.resolve(coverage, tracked, EraScoring.KAGGLE, base(), THRESHOLD);
    }

    @Test
    void season197071CountsPointsReboundsAssistsOnly() {
        EraScoring.Era e = kaggle(cov(1.0, 0.96, 0.94, 0, 0, 0, 0), Set.of("pts", "reb", "ast"));
        // pts/reb/ast and the bonuses that follow them.
        assertEquals(Set.of("pts", "reb", "ast", "bonus_pt_40p", "bonus_pt_50p", "bonus_reb_20p",
                "bonus_ast_15p"), e.scoring().keySet());
        assertTrue(e.excluded().containsKey("stl"));
        assertTrue(e.excluded().containsKey("tpm"));
        assertTrue(e.excluded().containsKey("dd"), "dd needs all five of pts/reb/ast/stl/blk");
        assertTrue(e.excluded().containsKey("td"));
        assertEquals("not recorded in historical box scores", e.excluded().get("tf"));
        assertEquals("not recorded in historical box scores", e.excluded().get("ff"));
    }

    @Test
    void ddAndTdBefore198384ExplainThemselvesInPlainWords() {
        // 1975-76: stl/blk only 61-63% recorded.
        EraScoring.Era e = kaggle(cov(1.0, 0.99, 0.99, 0.615, 0.633, 0, 0), Set.of("pts", "reb", "ast", "stl", "blk"));
        String expected = "double-doubles need steals and blocks, which weren't fully recorded";
        assertEquals(expected, e.excluded().get("dd"));
        assertEquals(expected, e.excluded().get("td"));
    }

    @Test
    void season197980AddsThreePointers() {
        EraScoring.Era e = kaggle(cov(1.0, 0.98, 0.98, 0.71, 0.81, 0.65, 0.99), ALL_TRACKED);
        assertEquals(Set.of("pts", "reb", "ast", "tpm", "bonus_pt_40p", "bonus_pt_50p", "bonus_reb_20p",
                "bonus_ast_15p"), e.scoring().keySet());
        assertTrue(e.excluded().containsKey("stl"));
        assertTrue(e.excluded().containsKey("blk"));
        assertTrue(e.excluded().containsKey("to"));
    }

    @Test
    void season198283AddsBlocksButNotStealsOrTurnovers() {
        EraScoring.Era e = kaggle(cov(1.0, 0.99, 0.99, 0.828, 0.916, 0.812, 0.99), ALL_TRACKED);
        assertTrue(e.scoring().containsKey("blk"));
        assertTrue(e.scoring().containsKey("tpm"));
        assertFalse(e.scoring().containsKey("stl"));
        assertFalse(e.scoring().containsKey("to"));
        assertFalse(e.scoring().containsKey("dd"), "stl is missing, so dd/td still do not count");
        assertFalse(e.scoring().containsKey("td"));
    }

    @Test
    void season198384CountsEverythingExceptNonBoxKeys() {
        EraScoring.Era e = kaggle(cov(1.0, 1.0, 1.0, 0.99, 0.99, 0.99, 1.0), ALL_TRACKED);
        assertEquals(13, e.scoring().size());
        assertTrue(e.scoring().containsKey("dd"));
        assertTrue(e.scoring().containsKey("td"));
        assertEquals(Set.of("tf", "ff"), e.excluded().keySet());
    }

    @Test
    void assistsAtEightyNinePointNineAreExcludedAndAtNinetyCount() {
        EraScoring.Era below = kaggle(cov(1.0, 1.0, 0.899, 0, 0, 0, 0), Set.of("pts", "reb", "ast"));
        assertFalse(below.scoring().containsKey("ast"));
        assertFalse(below.scoring().containsKey("bonus_ast_15p"), "the assist bonus follows ast");
        EraScoring.Era at = kaggle(cov(1.0, 1.0, 0.90, 0, 0, 0, 0), Set.of("pts", "reb", "ast"));
        assertTrue(at.scoring().containsKey("ast"));
        assertTrue(at.scoring().containsKey("bonus_ast_15p"));
    }

    @Test
    void trackedButUnmeasuredCoverageDoesNotCount() {
        EraScoring.Era e = kaggle(Map.of("pts", 1.0), Set.of("pts", "reb"));
        assertFalse(e.scoring().containsKey("reb"), "no measured coverage is not full coverage");
    }

    @Test
    void sleeperSeasonsCountEveryKey() {
        EraScoring.Era e = EraScoring.resolve(Map.of(), Set.of(), EraScoring.SLEEPER, base(), THRESHOLD);
        assertEquals(base(), e.scoring());
        assertTrue(e.excluded().isEmpty());
    }
}
