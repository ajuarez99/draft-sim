package com.ballknowers.draftsim.historical;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

/**
 * Which scoring stats exist in a historical season (spec 053 FR-007, research R2 as amended).
 *
 * <p>Pure: no Spring, no database. The caller measures per-stat coverage (the share of PLAYED box-score rows
 * carrying each stat) and which stats the league tracked that season, and this decides which entries of the
 * base scoring map survive.
 *
 * <p>For a Kaggle season a stat counts iff the league tracked it AND its coverage is at least the era
 * threshold (hand-set, 0.90, not fitted). A counted stat missing from one box score scores 0 and the screen
 * shows the coverage. Derived keys follow their inputs: {@code dd}/{@code td} need all of
 * pts/reb/ast/stl/blk, the point bonuses need pts, the rebound bonus reb, the assist bonus ast.
 * {@code tf}/{@code ff} are never in the box scores. A Sleeper season counts every key: Sleeper omits
 * zero-valued stats, so absence there means 0, not "unrecorded" (research R2).
 */
public final class EraScoring {

    public static final String KAGGLE = "KAGGLE";
    public static final String SLEEPER = "SLEEPER";

    private static final Set<String> BASE_STATS = Set.of("pts", "reb", "ast", "stl", "blk", "to", "tpm");
    private static final java.util.List<String> DOUBLE_DOUBLE_INPUTS = java.util.List.of("pts", "reb", "ast", "stl", "blk");

    private EraScoring() {}

    /**
     * @param scoring  the base scoring map restricted to counted keys
     * @param coverage the measured coverage that was supplied, echoed for the screen (counted or not)
     * @param excluded key -> human reason, for each base-scoring key that does not count
     */
    public record Era(Map<String, Double> scoring, Map<String, Double> coverage, Map<String, String> excluded) {}

    /**
     * @param coverage  stat -> share of played rows carrying it, for the seven box-score stats
     * @param tracked   the stats the league tracked that season (the season file has them); ignored for Sleeper
     * @param source    {@link #KAGGLE} or {@link #SLEEPER}
     * @param base      the full scoring map to filter
     * @param threshold the era threshold (historical.era-threshold)
     */
    public static Era resolve(Map<String, Double> coverage, Set<String> tracked, String source,
                              Map<String, Double> base, double threshold) {
        Map<String, Double> cov = new LinkedHashMap<>(coverage);
        Map<String, Double> counted = new LinkedHashMap<>();
        Map<String, String> excluded = new LinkedHashMap<>();

        if (SLEEPER.equals(source)) {
            counted.putAll(base);
            return new Era(counted, cov, excluded);
        }
        if (!KAGGLE.equals(source)) {
            throw new IllegalArgumentException("unknown source " + source);
        }

        // Decide the seven measured box-score stats first; the derived keys read the verdicts. A null
        // reason means the stat counts. (Mutable map: nulls are legitimate here, so no Map.of.)
        Map<String, String> boxVerdict = new LinkedHashMap<>();
        for (String stat : BASE_STATS) {
            if (!tracked.contains(stat)) {
                boxVerdict.put(stat, "not tracked by the league that season");
                continue;
            }
            double c = coverage.getOrDefault(stat, 0.0);
            boxVerdict.put(stat, c >= threshold ? null
                    : "recorded in only " + percent(c) + " of box scores (needs " + percent(threshold) + ")");
        }

        for (Map.Entry<String, Double> e : base.entrySet()) {
            String key = e.getKey();
            String reason = reasonFor(key, boxVerdict);
            if (reason == null) counted.put(key, e.getValue());
            else excluded.put(key, reason);
        }
        return new Era(counted, cov, excluded);
    }

    private static String reasonFor(String key, Map<String, String> boxVerdict) {
        if (BASE_STATS.contains(key)) return boxVerdict.get(key);
        return switch (key) {
            case "dd", "td" -> {
                for (String input : DOUBLE_DOUBLE_INPUTS) {
                    if (boxVerdict.get(input) != null) {
                        yield ("stl".equals(input) || "blk".equals(input))
                                ? "double-doubles need steals and blocks, which weren't fully recorded"
                                : "double-doubles need pts, reb, ast, stl and blk all counted; " + input + " is not";
                    }
                }
                yield null;
            }
            case "bonus_pt_40p", "bonus_pt_50p" -> followsReason(boxVerdict, "pts");
            case "bonus_reb_20p" -> followsReason(boxVerdict, "reb");
            case "bonus_ast_15p" -> followsReason(boxVerdict, "ast");
            case "tf", "ff" -> "not recorded in historical box scores";
            default -> "not available in historical box scores";
        };
    }

    private static String followsReason(Map<String, String> boxVerdict, String input) {
        return boxVerdict.get(input) == null ? null : "needs " + input + ", which is not counted";
    }

    private static String percent(double share) {
        return String.format(java.util.Locale.US, "%.1f%%", share * 100);
    }
}
