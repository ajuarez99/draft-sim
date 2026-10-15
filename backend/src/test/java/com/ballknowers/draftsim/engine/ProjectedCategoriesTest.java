package com.ballknowers.draftsim.engine;

import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/** specs/036 T006: the shared "no projection line carries it" rule. */
class ProjectedCategoriesTest {

    private static Map<String, Double> scoring() {
        Map<String, Double> m = new LinkedHashMap<>();
        m.put("pts", 1.0);
        m.put("bonus_pt_40p", 2.0);
        m.put("td", 0.0);          // zero weight: neither scored nor listed
        m.put("blk", 2.0);
        return m;
    }

    @Test
    void unprojectedListsScoredKeysNoLineCarriesSortedAndIgnoresZeroWeights() {
        assertEquals(List.of("bonus_pt_40p"), ProjectedCategories.unprojected(scoring(), Set.of("pts", "blk")));
        assertEquals(List.of("blk", "bonus_pt_40p", "pts"), ProjectedCategories.unprojected(scoring(), Set.of()));
    }

    @Test
    void restrictKeepsOnlyScoredKeysSomeLineCarries() {
        Map<String, Double> r = ProjectedCategories.restrict(scoring(), Set.of("pts", "blk", "td"));
        assertEquals(Map.of("pts", 1.0, "blk", 2.0), r);
    }

    private static Map<String, Double> defScoring() {
        Map<String, Double> m = new LinkedHashMap<>();
        m.put("sack", 1.0);
        m.put("pts_allow", 0.5);      // plain key, not a bucket
        m.put("pts_allow_0", 10.0);
        m.put("pts_allow_1_6", 7.0);
        m.put("pts_allow_7_13", 4.0);
        m.put("pts_allow_14_20", 1.0);
        m.put("pts_allow_28_34", -1.0);
        m.put("pts_allow_35p", -4.0);
        m.put("yds_allow_350_399", 0.0);   // zero weight: not a member
        return m;
    }

    @Test
    void aPartiallyProjectedBucketFamilyIsExcludedWhole() {
        // measured: a projected DEF line carries one bucket, so only 14_20 and 28_34 ever appear on any line
        Set<String> projected = Set.of("sack", "pts_allow", "pts_allow_14_20", "pts_allow_28_34");
        assertEquals(List.of("pts_allow_0", "pts_allow_14_20", "pts_allow_1_6", "pts_allow_28_34",
                "pts_allow_35p", "pts_allow_7_13"), ProjectedCategories.unprojected(defScoring(), projected));
        assertEquals(Map.of("sack", 1.0, "pts_allow", 0.5), ProjectedCategories.restrict(defScoring(), projected));
    }

    @Test
    void aFullyProjectedBucketFamilyStaysGraded() {
        Set<String> projected = Set.of("sack", "pts_allow", "pts_allow_0", "pts_allow_1_6", "pts_allow_7_13",
                "pts_allow_14_20", "pts_allow_28_34", "pts_allow_35p");
        assertEquals(List.of(), ProjectedCategories.unprojected(defScoring(), projected));
        assertEquals(8, ProjectedCategories.restrict(defScoring(), projected).size());
    }

    @Test
    void kickerDistanceBucketsAreAFamilyButPlainFgmIsNot() {
        Map<String, Double> k = new LinkedHashMap<>();
        k.put("fgm", 3.0);
        k.put("fgm_40_49", 4.0);
        k.put("fgm_50_59", 5.0);
        k.put("fgm_60p", 6.0);
        k.put("xpm", 1.0);
        Set<String> projected = Set.of("fgm", "fgm_40_49", "fgm_50p", "xpm");
        assertEquals(List.of("fgm_40_49", "fgm_50_59", "fgm_60p"), ProjectedCategories.unprojected(k, projected));
        assertEquals(Map.of("fgm", 3.0, "xpm", 1.0), ProjectedCategories.restrict(k, projected));
    }
}
