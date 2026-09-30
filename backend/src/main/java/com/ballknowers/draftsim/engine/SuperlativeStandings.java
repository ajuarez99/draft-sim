package com.ballknowers.draftsim.engine;

import com.ballknowers.draftsim.engine.SeasonSuperlativesService.Holder;
import com.ballknowers.draftsim.engine.SeasonSuperlativesService.Standing;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * Ranking core for a team award's full standings (spec 010). Pure and static, no Spring, so
 * {@code SuperlativeStandingsTest} drives it without Postgres.
 *
 * <p>Ranks are competition ranks (1, 1, 3): tied values share a rank and the next rank skips.
 * Ties compare with {@link Double#compare} on values the caller has already rounded exactly as the
 * award's winner value is, so "rank 1" and "holder" can't disagree on what counts as tied.
 */
final class SuperlativeStandings {

    private SuperlativeStandings() {}

    /**
     * @param value         null when the award couldn't measure this roster
     * @param missingReason required when {@code value} is null, the reason shown instead of a figure
     */
    record Entry(Holder team, Double value, String note, String missingReason) {}

    static List<Standing> rank(List<Entry> entries, boolean ascending) {
        List<Entry> withValue = new ArrayList<>();
        List<Entry> without = new ArrayList<>();
        for (Entry e : entries) {
            if (e.value() == null) {
                if (e.missingReason() == null) {
                    throw new IllegalArgumentException(
                            "roster " + e.team().rosterId() + " has no value and no missingReason");
                }
                without.add(e);
            } else {
                withValue.add(e);
            }
        }
        // Double.compare-based comparator, so -0.0 vs 0.0 orders the same way the tie test below does.
        Comparator<Entry> cmp = Comparator.<Entry, Double>comparing(Entry::value, Double::compare);
        if (!ascending) cmp = cmp.reversed();
        cmp = cmp.thenComparingInt(e -> e.team().rosterId());
        withValue.sort(cmp);
        without.sort(Comparator.comparingInt(e -> e.team().rosterId()));

        List<Standing> out = new ArrayList<>();
        int rank = 0;
        Double prev = null;
        for (int i = 0; i < withValue.size(); i++) {
            Entry e = withValue.get(i);
            if (prev == null || Double.compare(prev, e.value()) != 0) rank = i + 1;
            prev = e.value();
            out.add(new Standing(rank, e.team(), e.value(), e.note(), true, null));
        }
        for (Entry e : without) {
            out.add(new Standing(null, e.team(), null, e.note(), false, e.missingReason()));
        }
        return out;
    }
}
