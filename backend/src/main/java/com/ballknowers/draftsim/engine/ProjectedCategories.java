package com.ballknowers.draftsim.engine;

import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * The one rule for "scored categories that no projection line carries".
 *
 * <p>A league's scoring map can reward things a projection never contains (NBA bonus categories
 * such as {@code bonus_pt_40p}, football's {@code bonus_rec_yd_100}). Two callers need the same
 * answer and must not each write it:
 * <ul>
 *   <li>{@link LeagueAnalysisService} lists them as the page's "not projected" caveat;</li>
 *   <li>spec 036's accuracy read scores BOTH sides of every sample with the scoring map
 *       {@link #restrict restricted} to what projections carry. Without that, a realized line
 *       holding a bonus category the projection cannot have would build "projections run low"
 *       into the lean (plan amendment B3: measured +0.69 pts/game on NBA under 2.0 weights).</li>
 * </ul>
 * A key carried by even one line is not "unprojected" -- it is an implicit zero for everyone else,
 * like {@code dd} and {@code td}.
 */
public final class ProjectedCategories {

    private ProjectedCategories() {}

    private static final Pattern PTS_ALLOW = Pattern.compile("^pts_allow_\\w+$");
    private static final Pattern YDS_ALLOW = Pattern.compile("^yds_allow_\\w+$");
    private static final Pattern FGM_DIST = Pattern.compile("^fgm_\\d.*");
    private static final Pattern FGMISS_DIST = Pattern.compile("^fgmiss_\\d.*");

    /**
     * The mutually-exclusive bucket family a key belongs to, or null. A projected DEF line carries exactly
     * one points-allowed bucket, so "some line carries this bucket" says nothing about whether the family
     * is projected: grading only the buckets this season's lines happened to touch makes the error depend
     * on which ones those were. Plain {@code pts_allow}, {@code yds_allow}, {@code fgm}, {@code fgmiss} and
     * {@code fgm_yds} are not buckets.
     */
    private static String family(String key) {
        if (PTS_ALLOW.matcher(key).matches()) return "pts_allow";
        if (YDS_ALLOW.matcher(key).matches()) return "yds_allow";
        if (FGM_DIST.matcher(key).matches()) return "fgm_dist";
        if (FGMISS_DIST.matcher(key).matches()) return "fgmiss_dist";
        return null;
    }

    private static boolean scored(Number v) {
        return v != null && v.doubleValue() != 0;
    }

    /** Keys not graded: unprojected non-family keys, plus every member of a family not fully projected. */
    private static Set<String> excludedKeys(Map<String, ? extends Number> scoring, Set<String> projectedKeys) {
        Set<String> brokenFamilies = new HashSet<>();
        scoring.forEach((k, v) -> {
            String f = family(k);
            if (f != null && scored(v) && !projectedKeys.contains(k)) brokenFamilies.add(f);
        });
        Set<String> out = new HashSet<>();
        scoring.forEach((k, v) -> {
            if (!scored(v)) return;
            String f = family(k);
            if (f != null ? brokenFamilies.contains(f) : !projectedKeys.contains(k)) out.add(k);
        });
        return out;
    }

    /**
     * Scored (non-null, non-zero) keys that are in no projection line, sorted -- plus, for a bucket
     * family (see {@link #family}), every member the league scores when any member is missing.
     */
    public static List<String> unprojected(Map<String, ? extends Number> scoring, Set<String> projectedKeys) {
        return excludedKeys(scoring, projectedKeys).stream().sorted().toList();
    }

    /** The scoring map without the {@link #unprojected} keys; order preserved. */
    public static <N extends Number> Map<String, N> restrict(Map<String, N> scoring, Set<String> projectedKeys) {
        Set<String> excluded = excludedKeys(scoring, projectedKeys);
        Map<String, N> out = new LinkedHashMap<>();
        scoring.forEach((k, v) -> {
            if (scored(v) && !excluded.contains(k)) out.put(k, v);
        });
        return out;
    }
}
