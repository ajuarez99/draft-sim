package com.ballknowers.draftsim.engine;

import com.ballknowers.draftsim.config.PlayerStatsProperties;
import com.ballknowers.draftsim.engine.AdvancedStats.Rate;
import com.ballknowers.draftsim.engine.AdvancedStats.Window;
import com.ballknowers.draftsim.engine.AdvancedStats.WindowKind;
import com.ballknowers.draftsim.engine.NbaGameLines.Line;
import com.ballknowers.draftsim.engine.PlayerStatsService.Pct;
import com.ballknowers.draftsim.engine.PlayerStatsService.Qualification;
import com.ballknowers.draftsim.engine.PlayerTrendsService.PlayerInfo;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Percentiles of the advanced rates (spec 022 data-model "Percentiles", F13), pure.
 *
 * <p>For every window and every advanced rate there are two {@link Pct}s: against the qualified players
 * with the same first listed position ({@code NBA_POSITION}) and against the qualified players rostered in
 * the league at the ownership point ({@code LEAGUE_ROSTERED}). A window's qualification is that window's own
 * (a last-5 percentile is among the players who qualify for last 5).
 *
 * <p><b>Formula</b>: {@code 100 * (below + 0.5 * ties) / n}, where {@code n} counts the group's other
 * members (the player himself excluded when he is one) and {@code below}/{@code ties} count them against his
 * figure. <b>Deviation from the data-model's text, which says "/ (n - 1)" with n also excluding the player:</b>
 * that would give the best player 100 * n / (n - 1), above 100; dividing by the number of others bounds
 * the result to [0, 100]. A non-member (a free agent against {@code LEAGUE_ROSTERED}) is ranked against the
 * whole group. Values are compared at the wire precision (two decimals), like ranks (F13), so shown-equal
 * figures tie. {@code tovPct} is inverted: a lower figure is the better one, so a lower TOV% gives the
 * higher percentile.
 *
 * <p>Reason precedence for one Pct: the player not qualified in the window ({@code NOT_QUALIFIED[_STALE]}),
 * then his own rate having no value (that rate's reason), then {@code OWNERSHIP_UNAVAILABLE} (rostered group
 * only), then {@code GROUP_TOO_SMALL} (fewer than 2 others, or no position). {@code n} is the number of
 * other members with a value for that rate (0 when ownership is unavailable).
 */
public final class PlayerPercentiles {

    private PlayerPercentiles() {}

    public static final String NBA_POSITION = "NBA_POSITION";
    public static final String LEAGUE_ROSTERED = "LEAGUE_ROSTERED";
    public static final String GROUP_TOO_SMALL = "GROUP_TOO_SMALL";
    public static final String OWNERSHIP_UNAVAILABLE = "OWNERSHIP_UNAVAILABLE";

    /** One qualified player in one window: his first position (nullable) and his rates at two decimals. */
    record Member(String id, String position, Map<String, Double> values) {}

    /** Per window, every qualified player. Independent of who the page is about, so it can be shared. */
    public record Population(Map<WindowKind, List<Member>> byWindow) {}

    /** Every player's advanced rates in every window in which he qualifies. */
    public static Population population(NbaGameLines lines, Map<String, PlayerInfo> infos, PlayerStatsProperties p) {
        int minGames = PlayerStatsService.minGames(lines, p);
        LocalDate latest = PlayerStatsService.latestGame(lines);
        Map<WindowKind, List<Member>> out = new EnumMap<>(WindowKind.class);
        for (WindowKind k : WindowKind.values()) out.put(k, new ArrayList<>());
        for (Map.Entry<String, List<Line>> e : lines.byPlayer().entrySet()) {
            PlayerInfo info = infos.get(e.getKey());
            String position = info == null || info.positions().isEmpty() ? null : info.positions().getFirst();
            for (WindowKind k : WindowKind.values()) {
                List<Line> sub = k.select(e.getValue());
                if (!PlayerStatsService.qualify(k, sub, minGames, p, latest).qualified()) continue;
                Map<String, Double> values = new HashMap<>();
                for (Map.Entry<String, Rate> r : AdvancedStats.advanced(sub).byKey().entrySet()) {
                    if (r.getValue().value() != null) values.put(r.getKey(), round2(r.getValue().value()));
                }
                out.get(k).add(new Member(e.getKey(), position, values));
            }
        }
        return new Population(out);
    }

    /**
     * @param target         the player the page is about
     * @param position       his first listed position, nullable
     * @param windows        his own windows (the rates being ranked)
     * @param qualification  his own qualification per window
     * @param rostered       players rostered at the ownership point; null when ownership is unavailable
     * @return window, then rate key (in {@link AdvancedStats#ADVANCED_KEYS} order), then [NBA_POSITION, LEAGUE_ROSTERED]
     */
    public static Map<WindowKind, Map<String, List<Pct>>> of(Population pop, String target, String position,
                                                             Map<WindowKind, Window> windows,
                                                             Map<WindowKind, Qualification> qualification,
                                                             Set<String> rostered) {
        Map<WindowKind, Map<String, List<Pct>>> out = new EnumMap<>(WindowKind.class);
        for (WindowKind k : WindowKind.values()) {
            Map<String, Rate> own = windows.get(k).advanced().byKey();
            Qualification q = qualification.get(k);
            Map<String, List<Pct>> byRate = new LinkedHashMap<>();
            for (String key : AdvancedStats.ADVANCED_KEYS) {
                byRate.put(key, List.of(
                        pct(NBA_POSITION, key, own.get(key), q, pop.byWindow().get(k), target, position, null, false),
                        pct(LEAGUE_ROSTERED, key, own.get(key), q, pop.byWindow().get(k), target, position, rostered,
                                rostered == null)));
            }
            out.put(k, byRate);
        }
        return out;
    }

    private static Pct pct(String group, String key, Rate own, Qualification q, List<Member> members, String target,
                           String position, Set<String> rostered, boolean ownershipUnavailable) {
        List<Double> others = new ArrayList<>();
        if (!ownershipUnavailable) {
            for (Member m : members) {
                if (m.id().equals(target) || !m.values().containsKey(key)) continue;
                if (NBA_POSITION.equals(group)) {
                    if (position == null || !position.equals(m.position())) continue;
                } else if (!rostered.contains(m.id())) {
                    continue;
                }
                others.add(m.values().get(key));
            }
        }
        int n = others.size();
        if (!q.qualified()) return new Pct(null, group, n, q.reason());
        if (own.value() == null) return new Pct(null, group, n, own.reason());
        if (ownershipUnavailable) return new Pct(null, group, 0, OWNERSHIP_UNAVAILABLE);
        if (n < 2) return new Pct(null, group, n, GROUP_TOO_SMALL);
        double mine = round2(own.value());
        boolean lowerIsBetter = AdvancedStats.LOWER_IS_BETTER.equals(key);
        int below = 0;
        int ties = 0;
        for (double v : others) {
            if (v == mine) ties++;
            else if (lowerIsBetter ? v > mine : v < mine) below++;
        }
        return new Pct(100.0 * (below + 0.5 * ties) / n, group, n, null);
    }

    private static double round2(double v) {
        return Math.round(v * 100.0) / 100.0;
    }
}
