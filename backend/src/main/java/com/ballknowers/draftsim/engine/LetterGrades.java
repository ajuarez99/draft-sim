package com.ballknowers.draftsim.engine;

import com.ballknowers.draftsim.config.GradeProperties;
import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.Map;
import java.util.function.ToDoubleFunction;
import java.util.List;

/**
 * Turns a rank into a letter (spec 013 T072). A grade is a reading of a rank, never a measurement:
 * the cutoffs are hand-set ({@link GradeProperties}) and with no config every grade is null.
 */
@Component
public class LetterGrades {

    private final GradeProperties props;

    public LetterGrades(GradeProperties props) {
        this.props = props;
    }

    /**
     * @param rank      1 = best. Tied teams must be passed the same rank, so they share a grade.
     * @param teamCount how many teams were ranked
     * @return the grade, or null when no cutoffs are configured or the input is not a real rank
     */
    public String grade(int rank, int teamCount) {
        if (!props.loaded() || teamCount < 1 || rank < 1 || rank > teamCount) return null;
        double percentile = (rank - 1) / (double) Math.max(1, teamCount - 1) * 100.0;
        for (GradeProperties.Cutoff c : props.cutoffs()) {
            if (percentile <= c.maxPercentile()) return c.grade();
        }
        return null; // unreachable: the last cutoff is validated to be 100
    }

    /**
     * Competition ranking, higher value = better: equal values share the best rank of the group
     * (1, 1, 3). Entries whose value is null are not ranked and not counted.
     */
    public static <T> Map<T, Integer> ranksDescending(List<T> items, ToDoubleFunction<T> value,
                                                       java.util.function.Predicate<T> ranked) {
        Map<T, Integer> out = new HashMap<>();
        List<T> eligible = items.stream().filter(ranked).toList();
        for (T item : eligible) {
            double v = value.applyAsDouble(item);
            int better = (int) eligible.stream().filter(o -> value.applyAsDouble(o) > v).count();
            out.put(item, better + 1);
        }
        return out;
    }
}
