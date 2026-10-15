package com.ballknowers.draftsim.ingest.espn;

import com.ballknowers.draftsim.domain.Sport;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * ESPN {@code statId} to the Sleeper scoring key the app's {@code scoring_json} uses (research R7).
 *
 * <p><b>Every entry is {@code confirmed = false} in this build.</b> The ids below are from the
 * third-party espn-api client and have never been checked against a real league's scoring page. An
 * entry only counts once a human confirms it against that page (T053). Until then an item with an
 * unconfirmed (or no) mapping lands in {@code untranslated}, and if ANY non-zero item does, the whole
 * {@code scoring} map is {@code {}}: an empty rule set would score every player 0, which is exactly
 * the unexplained zeros the spec forbids and the "more certain than correct" failure AGENTS.md names.
 */
public final class EspnScoringMap {

    private EspnScoringMap() {}

    /** @param confirmed true only after the ESPN scoring page for a real league has been compared. */
    record Entry(String sleeperKey, boolean confirmed) {}

    // unverified: confirm against real league (T053). Every ESPN id here is a guess from espn-api.
    private static final Map<Integer, Entry> NFL = Map.ofEntries(
            Map.entry(3, new Entry("pass_yd", false)), Map.entry(4, new Entry("pass_td", false)),
            Map.entry(20, new Entry("pass_int", false)), Map.entry(24, new Entry("rush_yd", false)),
            Map.entry(25, new Entry("rush_td", false)), Map.entry(42, new Entry("rec_yd", false)),
            Map.entry(43, new Entry("rec_td", false)), Map.entry(53, new Entry("rec", false)),
            Map.entry(72, new Entry("fum_lost", false)));

    // unverified: confirm against real league (T053).
    private static final Map<Integer, Entry> NBA = Map.ofEntries(
            Map.entry(0, new Entry("pts", false)), Map.entry(1, new Entry("blk", false)),
            Map.entry(2, new Entry("stl", false)), Map.entry(3, new Entry("ast", false)),
            Map.entry(6, new Entry("reb", false)), Map.entry(11, new Entry("to", false)),
            Map.entry(13, new Entry("fgm", false)), Map.entry(14, new Entry("fga", false)),
            Map.entry(15, new Entry("ftm", false)), Map.entry(16, new Entry("fta", false)),
            Map.entry(17, new Entry("tpm", false)));

    /**
     * @param scoring     Sleeper key to multiplier; EMPTY whenever {@code untranslated} is not
     * @param untranslated {@code stat:<id>} for every non-zero ESPN item without a confirmed mapping
     */
    public record Result(Map<String, Double> scoring, List<String> untranslated) {}

    /**
     * @param scoringItems ESPN's {@code settings.scoringSettings.scoringItems}: maps with {@code statId}
     *                     and {@code points}
     */
    public static Result translate(Sport sport, List<Map<String, Object>> scoringItems) {
        Map<Integer, Entry> table = sport == Sport.NFL ? NFL : NBA;
        Map<String, Double> scoring = new LinkedHashMap<>();
        List<String> untranslated = new ArrayList<>();
        for (Map<String, Object> item : scoringItems) {
            Object id = item.get("statId");
            Object pts = item.get("points");
            if (!(id instanceof Number sid) || !(pts instanceof Number points)) {
                throw new EspnShapeChanged("settings.scoringSettings.scoringItems[]");
            }
            if (points.doubleValue() == 0.0) continue;
            Entry e = table.get(sid.intValue());
            if (e == null || !e.confirmed()) {
                untranslated.add("stat:" + sid.intValue());
            } else {
                scoring.put(e.sleeperKey(), points.doubleValue());
            }
        }
        if (!untranslated.isEmpty()) return new Result(new LinkedHashMap<>(), untranslated);
        return new Result(scoring, untranslated);
    }

    /** Test seam: confirm every guessed entry, to prove the all-confirmed path writes a map. */
    static Result translateTrustingTable(Sport sport, List<Map<String, Object>> scoringItems) {
        Map<Integer, Entry> table = sport == Sport.NFL ? NFL : NBA;
        Map<String, Double> scoring = new LinkedHashMap<>();
        List<String> untranslated = new ArrayList<>();
        for (Map<String, Object> item : scoringItems) {
            int sid = ((Number) item.get("statId")).intValue();
            double points = ((Number) item.get("points")).doubleValue();
            if (points == 0.0) continue;
            Entry e = table.get(sid);
            if (e == null) untranslated.add("stat:" + sid);
            else scoring.put(e.sleeperKey(), points);
        }
        if (!untranslated.isEmpty()) return new Result(new LinkedHashMap<>(), untranslated);
        return new Result(scoring, untranslated);
    }
}
