package com.ballknowers.draftsim.engine;

import com.ballknowers.draftsim.engine.NbaGameLines.Line;

import java.util.List;
import java.util.Map;

/**
 * Real-basketball derived figures over {@link Line}s (specs/022-player-stat-analysis). Pure: no I/O,
 * no Spring. {@code usage} moved here from {@code PlayerTrendsService} so there is one definition.
 */
public final class AdvancedStats {

    private AdvancedStats() {}

    public static final String NO_ATTEMPTS = "NO_ATTEMPTS";
    public static final String NO_MINUTES = "NO_MINUTES";
    public static final String NO_TEAM_ROW = "NO_TEAM_ROW";

    /**
     * A rate that is either a value or a reason there is none (invariant I2): exactly one of the two
     * is non-null.
     */
    public record Rate(Double value, String reason) {
        public Rate {
            if ((value == null) == (reason == null)) {
                throw new IllegalArgumentException("a Rate has exactly one of value and reason");
            }
        }

        public static Rate of(double v) {
            return new Rate(v, null);
        }

        public static Rate none(String reason) {
            return new Rate(null, reason);
        }
    }

    /**
     * 100 * sum((FGA + 0.44 FTA + TO) * (TmMIN / 5)) / sum(MIN * (TmFGA + 0.44 TmFTA + TmTO)), pooled
     * over the games that have a team row (F12).
     *
     * <p>Reasons: {@code NO_TEAM_ROW} when no game in the window has a team row (including an empty
     * window); {@code NO_MINUTES} when the games that do have one carry no minutes; {@code NO_ATTEMPTS}
     * when minutes exist but the team's possessions-used are zero, so the denominator is still zero.
     */
    public static Rate usage(List<Line> games) {
        double num = 0;
        double den = 0;
        double minutes = 0;
        boolean anyTeamRow = false;
        for (Line g : games) {
            if (g.teamRow() == null) continue;
            anyTeamRow = true;
            Map<String, Object> t = g.teamRow().stats();
            double tmMin = num(t, "sp") / 60.0;
            num += (num(g.stats(), "fga") + 0.44 * num(g.stats(), "fta") + num(g.stats(), "to")) * (tmMin / 5.0);
            den += g.minutes() * (num(t, "fga") + 0.44 * num(t, "fta") + num(t, "to"));
            minutes += g.minutes();
        }
        if (!anyTeamRow) return Rate.none(NO_TEAM_ROW);
        if (minutes <= 0) return Rate.none(NO_MINUTES);
        if (den <= 0) return Rate.none(NO_ATTEMPTS);
        return Rate.of(100.0 * num / den);
    }

    /** A missing or non-numeric stat key is 0 (F12). */
    static double num(Map<String, Object> m, String key) {
        return m != null && m.get(key) instanceof Number n ? n.doubleValue() : 0.0;
    }
}
