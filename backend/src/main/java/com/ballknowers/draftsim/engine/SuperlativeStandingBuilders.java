package com.ballknowers.draftsim.engine;

import com.ballknowers.draftsim.store.*;
import java.math.BigDecimal;
import java.util.*;
import java.util.stream.Collectors;
import static com.ballknowers.draftsim.util.Rounding.round2;
import com.ballknowers.draftsim.engine.SeasonSuperlativesService.*;

import static com.ballknowers.draftsim.engine.SeasonSuperlativesService.*;

/**
 * The full-standings builders, one per superlative kind (specs/010): pure functions of
 * per-roster figures the service has already computed.
 *
 * <p>Moved out of {@link SeasonSuperlativesService} by specs/021-codebase-cleanup (T051)
 * as a pure move, proven by live JSON parity against the pre-split build.
 */
final class SuperlativeStandingBuilders {

    private SuperlativeStandingBuilders() {}

    /** HIGHEST_WEEK / LOWEST_WEEK: each roster's best (or worst) single week, from the same rows the winner uses. */
    static List<Standing> weekScoreStandings(List<RosterWeekPointsRepository.WeekBreakdown> rows,
                                             Set<Integer> rosterIds, boolean highest,
                                             Map<Integer, String> nameByRoster, Map<Integer, String> avatarByRoster,
                                             Map<Integer, Long> managerByRoster) {
        Map<Integer, Double> bestValue = new HashMap<>();
        Map<Integer, Integer> bestWeek = new HashMap<>();
        for (RosterWeekPointsRepository.WeekBreakdown w : rows) {
            if (!rosterIds.contains(w.rosterId())) continue; // R11: not in the roster universe
            double v = round2(w.startersPoints());
            Double cur = bestValue.get(w.rosterId());
            int c = cur == null ? 0 : Double.compare(v, cur);
            boolean better = cur == null || (highest ? c > 0 : c < 0);
            // A roster tying its own extreme keeps the earliest week, so the note is deterministic.
            boolean earlierTie = cur != null && c == 0 && w.week() < bestWeek.get(w.rosterId());
            if (better || earlierTie) {
                bestValue.put(w.rosterId(), v);
                bestWeek.put(w.rosterId(), w.week());
            }
        }
        List<SuperlativeStandings.Entry> entries = new ArrayList<>();
        for (int id : rosterIds) {
            Holder team = holder(id, nameByRoster, avatarByRoster, managerByRoster);
            Double v = bestValue.get(id);
            entries.add(v == null
                    ? new SuperlativeStandings.Entry(team, null, null, "no scored weeks")
                    : new SuperlativeStandings.Entry(team, v, "week " + bestWeek.get(id), null));
        }
        return SuperlativeStandings.rank(entries, !highest);
    }

    /**
     * BIGGEST_BLOWOUT ({@code closest == false}): each roster's largest winning margin, high to low.
     * CLOSEST_GAME ({@code closest == true}): each roster's smallest margin in any game, win or lose,
     * low to high -- so the loser of the closest game shares rank 1 with its winner, even though only
     * the winner is in {@code holders} (plan amendment 2). The winner is decided exactly as
     * {@code LeagueRecordService.margin} does ({@code aPoints >= bPoints}), and the margin is a
     * {@code BigDecimal} subtraction (R9), so a tie the card sees is a tie here.
     */
    static List<Standing> marginStandings(List<LeagueMatchupRepository.PairedGame> games, Set<Integer> rosterIds,
                                          boolean closest, Map<Integer, String> nameByRoster,
                                          Map<Integer, String> avatarByRoster, Map<Integer, Long> managerByRoster) {
        Map<Integer, MarginPick> best = new HashMap<>();
        for (LeagueMatchupRepository.PairedGame g : games) {
            int cmp = g.aPoints().compareTo(g.bPoints());
            // B5: a tie is nobody's blowout win. Degenerate case accepted: a season where every game is
            // tied -- the card would still name a 0-margin holder while standings say "no wins yet".
            if (!closest && cmp == 0) continue;
            boolean aWon = cmp >= 0;
            double margin = (aWon ? g.aPoints().subtract(g.bPoints()) : g.bPoints().subtract(g.aPoints())).doubleValue();
            if (closest) {
                String aVerb = cmp == 0 ? "tied with" : aWon ? "won vs" : "lost to";
                String bVerb = cmp == 0 ? "tied with" : aWon ? "lost to" : "won vs";
                considerMargin(best, rosterIds, g.aRosterId(), margin, g.week(), g.bRosterId(), aVerb, true);
                considerMargin(best, rosterIds, g.bRosterId(), margin, g.week(), g.aRosterId(), bVerb, true);
            } else {
                considerMargin(best, rosterIds, aWon ? g.aRosterId() : g.bRosterId(), margin, g.week(),
                        aWon ? g.bRosterId() : g.aRosterId(), "vs", false);
            }
        }
        List<SuperlativeStandings.Entry> entries = new ArrayList<>();
        for (int id : rosterIds) {
            Holder team = holder(id, nameByRoster, avatarByRoster, managerByRoster);
            MarginPick p = best.get(id);
            if (p == null) {
                entries.add(new SuperlativeStandings.Entry(team, null, null, closest ? "no games yet" : "no wins yet"));
                continue;
            }
            String opp = nameByRoster.getOrDefault(p.opponentId(), "Roster " + p.opponentId());
            entries.add(new SuperlativeStandings.Entry(team, p.margin(), p.verb() + " " + opp + " · week " + p.week(), null));
        }
        return SuperlativeStandings.rank(entries, closest);
    }

    /**
     * CLOSE_WINS / CLOSE_LOSSES: the count of close games. A roster in a paired game but absent from
     * {@code byRoster} is a real 0; a roster in no paired game at all wasn't measured (R12).
     */
    static List<Standing> closeGameStandings(Map<Integer, List<GameDetail>> byRoster, Set<Integer> rostersWithGames,
                                             Set<Integer> rosterIds, Map<Integer, String> nameByRoster,
                                             Map<Integer, String> avatarByRoster, Map<Integer, Long> managerByRoster) {
        List<SuperlativeStandings.Entry> entries = new ArrayList<>();
        for (int id : rosterIds) {
            Holder team = holder(id, nameByRoster, avatarByRoster, managerByRoster);
            if (!rostersWithGames.contains(id)) {
                entries.add(new SuperlativeStandings.Entry(team, null, null, "no games yet"));
                continue;
            }
            List<GameDetail> games = byRoster.getOrDefault(id, List.of());
            List<Integer> weeks = games.stream().map(GameDetail::week).sorted().toList();
            String note = weeks.isEmpty() ? null
                    : (weeks.size() == 1 ? "week " : "weeks ")
                    + weeks.stream().map(String::valueOf).collect(Collectors.joining(", "));
            entries.add(new SuperlativeStandings.Entry(team, (double) games.size(), note, null));
        }
        return SuperlativeStandings.rank(entries, false);
    }

    /**
     * LUCKIEST ({@code ascending == false}) / UNLUCKIEST ({@code ascending == true}), from the same
     * {@link ExpectedWinsService.TeamRow}s the winner is picked from. The value is {@code
     * winsAboveExpected} unrounded, exactly as {@link #luckSuperlative}'s {@code extreme} is.
     */
    static List<Standing> luckStandings(List<ExpectedWinsService.TeamRow> teams, Set<Integer> rosterIds,
                                        boolean ascending, Map<Integer, String> nameByRoster,
                                        Map<Integer, String> avatarByRoster, Map<Integer, Long> managerByRoster) {
        Map<Integer, ExpectedWinsService.TeamRow> byRoster = new HashMap<>();
        for (ExpectedWinsService.TeamRow t : teams) {
            if (rosterIds.contains(t.rosterId())) byRoster.put(t.rosterId(), t); // R11
        }
        List<SuperlativeStandings.Entry> entries = new ArrayList<>();
        for (int id : rosterIds) {
            Holder team = holder(id, nameByRoster, avatarByRoster, managerByRoster);
            ExpectedWinsService.TeamRow t = byRoster.get(id);
            entries.add(t == null
                    ? new SuperlativeStandings.Entry(team, null, null, "no expected-wins row")
                    : new SuperlativeStandings.Entry(team, t.winsAboveExpected(),
                            winsText(t.actualWins()) + " actual vs "
                                    + String.format(Locale.ROOT, "%.2f", t.expectedWins()) + " expected", null));
        }
        return SuperlativeStandings.rank(entries, ascending);
    }

    /** MOST_BENCH_POINTS: a roster with no valid lineup week wasn't measured, not a 0. */
    static List<Standing> benchStandings(Map<Integer, BenchAgg> byRoster, Set<Integer> rosterIds,
                                         Map<Integer, String> nameByRoster, Map<Integer, String> avatarByRoster,
                                         Map<Integer, Long> managerByRoster) {
        List<SuperlativeStandings.Entry> entries = new ArrayList<>();
        for (int id : rosterIds) {
            Holder team = holder(id, nameByRoster, avatarByRoster, managerByRoster);
            BenchAgg b = byRoster.get(id);
            entries.add(b == null
                    ? new SuperlativeStandings.Entry(team, null, null, "no usable lineup breakdown")
                    : new SuperlativeStandings.Entry(team, round2(b.pointsLeft()),
                            b.weeksCounted() + (b.weeksCounted() == 1 ? " week counted" : " weeks counted"), null));
        }
        return SuperlativeStandings.rank(entries, false);
    }

    /** WAIVER_WIRE_WARRIOR: a roster absent from the map credited nothing, which is a real 0. */
    static List<Standing> waiverStandings(Map<Integer, WaiverPickupAttribution.RosterTotal> byRoster,
                                          Set<Integer> rosterIds, Map<Integer, String> nameByRoster,
                                          Map<Integer, String> avatarByRoster, Map<Integer, Long> managerByRoster) {
        List<SuperlativeStandings.Entry> entries = new ArrayList<>();
        for (int id : rosterIds) {
            WaiverPickupAttribution.RosterTotal t = byRoster.get(id);
            entries.add(new SuperlativeStandings.Entry(holder(id, nameByRoster, avatarByRoster, managerByRoster),
                    round2(t == null ? 0.0 : t.totalPoints()), null, null));
        }
        return SuperlativeStandings.rank(entries, false);
    }

    /**
     * JOEL_EMBIID: absent is 0, but absent does not always mean nothing was missed -- every roster with
     * weeks whose absences couldn't be classified says so on its own row, winners included (plan
     * amendment 4 / FR-010). The card today says it only for the winners.
     */
    static List<Standing> absenceStandings(Map<Integer, AbsenceCost.RosterCost> byRoster,
                                           Map<Integer, Set<Integer>> unclassifiedWeeksByRoster,
                                           Set<Integer> rosterIds, Map<Integer, String> nameByRoster,
                                           Map<Integer, String> avatarByRoster, Map<Integer, Long> managerByRoster) {
        List<SuperlativeStandings.Entry> entries = new ArrayList<>();
        for (int id : rosterIds) {
            AbsenceCost.RosterCost c = byRoster.get(id);
            int unclassified = unclassifiedWeeksByRoster.getOrDefault(id, Set.of()).size();
            String note = unclassified == 0 ? null
                    : unclassified + (unclassified == 1 ? " week" : " weeks")
                    + " couldn't be classified as a bye or a missed game";
            entries.add(new SuperlativeStandings.Entry(holder(id, nameByRoster, avatarByRoster, managerByRoster),
                    round2(c == null ? 0.0 : c.totalPointsLost()), note, null));
        }
        return SuperlativeStandings.rank(entries, false);
    }

    /** UNETHICAL: qualifying player-weeks per roster; a roster absent from the map has none, a real 0. */
    static List<Standing> conductStandings(Map<Integer, Integer> totalByRoster, Set<Integer> rosterIds,
                                           Map<Integer, String> nameByRoster, Map<Integer, String> avatarByRoster,
                                           Map<Integer, Long> managerByRoster) {
        List<SuperlativeStandings.Entry> entries = new ArrayList<>();
        for (int id : rosterIds) {
            entries.add(new SuperlativeStandings.Entry(holder(id, nameByRoster, avatarByRoster, managerByRoster),
                    (double) totalByRoster.getOrDefault(id, 0), null, null));
        }
        return SuperlativeStandings.rank(entries, false);
    }

    private static void considerMargin(Map<Integer, MarginPick> best, Set<Integer> rosterIds, int rosterId,
                                       double margin, int week, int opponentId, String verb, boolean smallerIsBetter) {
        if (!rosterIds.contains(rosterId)) return; // R11: not in the roster universe
        MarginPick cur = best.get(rosterId);
        boolean take;
        if (cur == null) {
            take = true;
        } else {
            int c = Double.compare(margin, cur.margin());
            if (c != 0) take = smallerIsBetter ? c < 0 : c > 0;
            else take = week < cur.week() || (week == cur.week() && opponentId < cur.opponentId());
        }
        if (take) best.put(rosterId, new MarginPick(margin, week, opponentId, verb));
    }

    /** Actual wins can be a half (a tie counts half): "7" for a whole number, "5.5" otherwise. */
    private static String winsText(double wins) {
        return wins == Math.rint(wins) ? String.valueOf((long) wins) : String.format(Locale.ROOT, "%.1f", wins);
    }

    /** One roster's best game for BIGGEST_BLOWOUT / CLOSEST_GAME. */
    private record MarginPick(double margin, int week, int opponentId, String verb) {}
}
