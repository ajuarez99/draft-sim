package com.ballknowers.draftsim.engine;

import com.ballknowers.draftsim.domain.*;
import com.ballknowers.draftsim.sport.SportRules;
import com.ballknowers.draftsim.store.JsonUtil;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;

/**
 * What a roster's best possible starting lineup WOULD have scored in one
 * already-played week, given the points its players actually put up
 * (specs/004-ffwrapped-feature-parity, US2).
 *
 * <p>Its own service rather than a method on {@code RosterManagementService}
 * because three callers need the same answer -- potential points (US2), the
 * efficiency-based weekly awards (US5), and anything later that grades a
 * lineup decision. Three copies of "what was the best lineup here" is the bug
 * class this whole feature exists to remove, and the repo has shipped it three
 * times already under different names.
 *
 * <p><b>Backward-looking, and that is what makes it sport-agnostic.</b> It
 * values a lineup with points already scored, never a projection. Sleeper
 * reports {@code players_points} for basketball exactly as for football, so
 * this works for both sports through one code path and reaches sport-specific
 * behaviour only via {@link SportRules#startingLineup} (FR-004). There is
 * deliberately no {@code Sport} branch anywhere in this file.
 */
@Service
public class RealizedLineupService {

    /**
     * One week's answer, or the explicit absence of one.
     *
     * @param valid  false when this week has no usable per-player breakdown.
     *               Callers must exclude the week rather than add {@code 0.0}
     *               (FR-007, US2.5) -- a week Sleeper never scored is not a
     *               week in which the manager scored nothing.
     * @param points the optimal lineup's total; meaningless when {@code !valid}
     * @param lineup who would have started, and where; empty when {@code !valid}
     */
    public record WeekLineup(boolean valid, double points, List<SportRules.Assigned> lineup, String reason) {

        static WeekLineup absent(String reason) {
            return new WeekLineup(false, 0.0, List.of(), reason);
        }

        static WeekLineup of(double points, List<SportRules.Assigned> lineup) {
            return new WeekLineup(true, points, lineup, null);
        }
    }

    /**
     * The optimal lineup for one roster-week.
     *
     * @param playersPointsJson Sleeper's {@code players_points} for that
     *                          roster-week: {@code sleeper_player_id -> points}
     * @param playersBySleeperId every player this app knows for the sport, so
     *                           positions can be resolved; built once per
     *                           request by the caller, not per week
     */
    public WeekLineup bestLineup(String playersPointsJson,
                                 Map<String, Player> playersBySleeperId,
                                 LeagueSettings settings,
                                 SportRules rules) {
        Map<String, Object> raw = playersPointsJson == null || playersPointsJson.isBlank()
                ? Map.of()
                : JsonUtil.readMap(playersPointsJson);
        if (raw.isEmpty()) {
            return WeekLineup.absent("no per-player points stored for this week");
        }

        // Resolve to (player, points) pairs, dropping ids this app has no
        // player row for. Unlike PowerRankingService we do NOT filter against
        // the draft board: the board is a forward-looking artifact and a
        // player who has left it can still have scored in week 3. Filtering by
        // it here would silently shrink a realized lineup.
        record Scored(Player player, double points) {}
        List<Scored> scored = new ArrayList<>(raw.size());
        for (Map.Entry<String, Object> e : raw.entrySet()) {
            Player p = playersBySleeperId.get(e.getKey());
            if (p == null) continue;
            if (!(e.getValue() instanceof Number n)) continue;
            scored.add(new Scored(p, n.doubleValue()));
        }
        if (scored.isEmpty()) {
            return WeekLineup.absent("no scored players on this roster could be resolved");
        }

        // Highest scorer first. The rank becomes the synthetic adp below so
        // RosterState's per-position ordering matches the ordering the value
        // function will impose anyway -- see the note on adp in toRoster.
        scored.sort(Comparator.comparingDouble(Scored::points).reversed());

        RosterState roster = new RosterState();
        Map<Long, Double> pointsByPlayerId = new java.util.HashMap<>();
        int rank = 1;
        for (Scored s : scored) {
            // adp is a RANK here, not a draft position. RosterState keeps each
            // position's list sorted by adp ascending, and startingLineup
            // re-sorts by the supplied value function regardless -- so adp only
            // needs to be deterministic. Making it the realized-points rank
            // keeps the two orderings consistent instead of merely harmless.
            roster.add(new BoardEntry(s.player(), rank, rank));
            pointsByPlayerId.put(s.player().id(), s.points());
            rank++;
        }

        List<SportRules.Assigned> lineup = rules.startingLineup(
                roster, settings, be -> pointsByPlayerId.getOrDefault(be.player().id(), 0.0));

        double total = 0;
        for (SportRules.Assigned a : lineup) total += a.value();
        return WeekLineup.of(total, lineup);
    }
}
