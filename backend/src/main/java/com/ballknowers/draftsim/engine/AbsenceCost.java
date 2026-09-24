package com.ballknowers.draftsim.engine;

import java.util.*;

/**
 * How a missed game costs a roster points (specs/008-season-superlatives US4,
 * research R10, <b>amended 2026-09-23</b>). Pure and static, deliberately --
 * no Postgres, so {@code AbsenceCostTest} can exercise every case in-memory
 * (T046/T047), the same shape as {@link WaiverPickupAttribution}.
 *
 * <p><b>Cost is measured per missed game, not per fantasy week.</b> Allan
 * overruled the first version of this rule ("as a player you want every
 * single game anyways and not just that one game"): a player who missed three
 * nights but played a fourth in the same fantasy week still costs three
 * games. Football has one game per week, so there "games missed" and "weeks
 * missed" are the same number -- one rule serves both sports, with no sport
 * branch here.
 *
 * <p>An absence counts against a roster only in a week that roster actually
 * held the player ({@link RosterMembership#rosteredWeeks()}) -- a missed game
 * while he sat on another fantasy roster is invisible to this one, by
 * construction, since a {@link RosterMembership} only ever lists the weeks
 * ITS OWN roster held him.
 */
public final class AbsenceCost {

    private AbsenceCost() {}

    /**
     * One player's counted cost against one roster.
     *
     * @param weeksAffected a COUNT of distinct fantasy weeks with at least one
     *                      counted missed game -- not a list. {@code
     *                      gamesMissed} is the headline and can exceed this
     *                      (a basketball week with more than one missed game).
     */
    public record PlayerCost(String playerId, int gamesMissed, int weeksAffected,
                             double pointsPerGame, double estimatedPointsLost) {}

    /** One roster's total estimated points lost to absence, and the players who make it up. */
    public record RosterCost(int rosterId, double totalPointsLost, List<PlayerCost> players) {}

    /**
     * One (roster, player) pairing's weeks, already scoped to whatever
     * regular-season window the caller wants.
     *
     * @param rosteredWeeks weeks this roster held the player (a key in its
     *                      stored {@code players_points})
     * @param startedWeeks  the subset of {@code rosteredWeeks} he started in
     */
    public record RosterMembership(int rosterId, String playerId, Set<Integer> rosteredWeeks,
                                   Set<Integer> startedWeeks) {}

    /** One missed game (a {@code player_absence} row), already bounded to the regular-season window. */
    public record Absence(String playerId, int week) {}

    /**
     * Hand-set, arbitrary (research R10): fewer than this many started weeks
     * and a bench stash reads as a regular starter by coincidence.
     */
    static final int MIN_STARTED_WEEKS = 2;

    /**
     * @param memberships        every (roster, player) pairing worth considering
     * @param playedWeeksByPlayer each player's own distinct {@code player_game}
     *                            weeks this season (season-wide, not per-roster;
     *                            a player absent from this map, or mapped to an
     *                            empty set, played zero games all season)
     * @param pointsPerGame       each player's mean points per game played this
     *                            season, under the league's own scoring
     * @param absences            every {@code player_absence} row for this
     *                            season, already bounded to the regular season
     */
    public static Map<Integer, RosterCost> compute(List<RosterMembership> memberships,
                                                     Map<String, Set<Integer>> playedWeeksByPlayer,
                                                     Map<String, Double> pointsPerGame,
                                                     List<Absence> absences) {
        Map<String, List<Integer>> absenceWeeksByPlayer = new HashMap<>();
        for (Absence a : absences) {
            absenceWeeksByPlayer.computeIfAbsent(a.playerId(), k -> new ArrayList<>()).add(a.week());
        }

        Map<Integer, List<PlayerCost>> byRoster = new HashMap<>();
        for (RosterMembership m : memberships) {
            if (!isRegularContributor(m, playedWeeksByPlayer)) continue;

            List<Integer> playerAbsenceWeeks = absenceWeeksByPlayer.getOrDefault(m.playerId(), List.of());
            List<Integer> gamesInWindow = playerAbsenceWeeks.stream()
                    .filter(m.rosteredWeeks()::contains)
                    .sorted()
                    .toList();
            if (gamesInWindow.isEmpty()) continue;

            double ppg = pointsPerGame.getOrDefault(m.playerId(), 0.0);
            int gamesMissed = gamesInWindow.size();
            int weeksAffected = (int) gamesInWindow.stream().distinct().count();
            double lost = round2(gamesMissed * ppg);

            byRoster.computeIfAbsent(m.rosterId(), k -> new ArrayList<>())
                    .add(new PlayerCost(m.playerId(), gamesMissed, weeksAffected, ppg, lost));
        }

        Map<Integer, RosterCost> out = new HashMap<>();
        for (Map.Entry<Integer, List<PlayerCost>> e : byRoster.entrySet()) {
            List<PlayerCost> players = new ArrayList<>(e.getValue());
            players.sort(Comparator.comparingDouble(PlayerCost::estimatedPointsLost).reversed());
            double total = round2(players.stream().mapToDouble(PlayerCost::estimatedPointsLost).sum());
            out.put(e.getKey(), new RosterCost(e.getKey(), total, players));
        }
        return out;
    }

    /**
     * Hand-set, arbitrary (research R10, <b>fixed 2026-09-23</b>): started in
     * at least half of the weeks this roster held him AND he actually played a
     * game, at least {@link #MIN_STARTED_WEEKS} such weeks, and played at
     * least one game all season (implied: an empty {@code playedWeeksByPlayer}
     * entry fails outright). A deep stash never qualifies (US4 scenario 4) --
     * package-private so {@code AbsenceCostTest} can probe it directly.
     *
     * <p><b>Bug fixed here:</b> the denominator was every rostered week,
     * including weeks he never had a chance to play (a season-long injury).
     * That dropped the award's own namesake case: a star started weeks 1-5 of
     * an 18-week season, then missed the remaining 13 to injury -- 5 &lt; 9
     * (half of 18) failed outright, so a star's whole lost season cost
     * nothing. The denominator is now only the rostered weeks in which he
     * actually played at least one game, which for that same player is 5, and
     * 5 &gt;= 5/2 qualifies.
     */
    static boolean isRegularContributor(RosterMembership m, Map<String, Set<Integer>> playedWeeksByPlayer) {
        Set<Integer> playedWeeks = playedWeeksByPlayer.getOrDefault(m.playerId(), Set.of());
        if (playedWeeks.isEmpty()) return false; // played >= 1 game all season, implied
        long rosteredAndPlayed = m.rosteredWeeks().stream().filter(playedWeeks::contains).count();
        int started = m.startedWeeks().size();
        if (started < MIN_STARTED_WEEKS) return false;
        return started >= rosteredAndPlayed / 2.0;
    }

    private static double round2(double d) {
        return Math.round(d * 100.0) / 100.0;
    }
}
