package com.ballknowers.draftsim.engine;

import com.ballknowers.draftsim.domain.*;
import com.ballknowers.draftsim.sport.SportRules;
import com.ballknowers.draftsim.sport.SportRulesRegistry;
import com.ballknowers.draftsim.store.*;
import org.springframework.stereotype.Service;

import java.util.*;

/**
 * One week's digest: every matchup, the week's best performances, and the
 * awards that fall out of them (specs/004-ffwrapped-feature-parity, US5).
 *
 * <p><b>Awards come in two kinds, and the difference is the point.</b> Some are
 * computable from totals the app has always stored -- efficiency, scoring rank,
 * one player's share of a lineup. One is not: naming the bench player who
 * should have started needs to know who WAS started, which nothing recorded
 * before V18. Rather than guess, those awards are omitted with a stated reason
 * (US5.4), so a week ingested before the column existed says what it cannot
 * answer instead of quietly answering it wrong.
 *
 * <p>Both sports, through one code path: matchups and scores mean the same
 * thing in basketball, and the optimal-lineup half goes through
 * {@link RealizedLineupService}, which reaches sport rules via
 * {@link SportRules}.
 */
@Service
public class WeeklyReportService {

    private final LeagueRepository leagues;
    private final RosterWeekPointsRepository weekPoints;
    private final LeagueMatchupRepository matchups;
    private final RosterSeasonRepository rosterSeasons;
    private final LeagueMemberRepository members;
    private final PlayerRepository players;
    private final SportRulesRegistry rulesRegistry;
    private final RealizedLineupService realized;

    public WeeklyReportService(LeagueRepository leagues, RosterWeekPointsRepository weekPoints,
                               LeagueMatchupRepository matchups, RosterSeasonRepository rosterSeasons,
                               LeagueMemberRepository members, PlayerRepository players,
                               SportRulesRegistry rulesRegistry, RealizedLineupService realized) {
        this.leagues = leagues;
        this.weekPoints = weekPoints;
        this.matchups = matchups;
        this.rosterSeasons = rosterSeasons;
        this.members = members;
        this.players = players;
        this.rulesRegistry = rulesRegistry;
        this.realized = realized;
    }

    public record Side(int rosterId, String teamName, String avatarId, String record, double points) {}

    public record Matchup(Side home, Side away) {}

    public record Performer(String playerId, String playerName, String position,
                            String teamName, double points) {}

    public record Award(String kind, String teamName, String detail) {}

    /** An award that could not be computed, and why. Never a silent absence. */
    public record OmittedAward(String kind, String reason) {}

    public record Result(boolean available, String reason, int season, int week, Sport sport,
                         List<Matchup> matchups, List<Performer> topPerformers,
                         List<Award> awards, List<OmittedAward> awardsOmitted) {

        static Result unavailable(String reason, int season, int week, Sport sport) {
            return new Result(false, reason, season, week, sport,
                    List.of(), List.of(), List.of(), List.of());
        }
    }

    /** Reason code for an award that needs starter identity and cannot have it. */
    static final String STARTERS_NOT_STORED = "STARTERS_NOT_STORED";

    public Optional<Result> forWeek(String sleeperLeagueId, int week) {
        Optional<LeagueRepository.LeagueRow> found = leagues.bySleeperId(sleeperLeagueId);
        if (found.isEmpty()) return Optional.empty();
        LeagueRepository.LeagueRow league = found.get();
        LeagueSettings settings = LeagueRepository.toSettings(league, league.rosterPositions().size());
        SportRules rules = rulesRegistry.get(settings.sport());

        List<RosterWeekPointsRepository.WeekBreakdown> all =
                weekPoints.breakdownsFor(league.id(), league.season());
        List<RosterWeekPointsRepository.WeekBreakdown> thisWeek = all.stream()
                .filter(w -> w.week() == week).toList();
        if (thisWeek.isEmpty()) {
            return Optional.of(Result.unavailable(
                    "week " + week + " has not been scored for this league",
                    league.season(), week, settings.sport()));
        }

        Map<Integer, String> nameByRoster = new HashMap<>();
        Map<Integer, String> avatarByRoster = new HashMap<>();
        Map<Integer, String> recordByRoster = new HashMap<>();
        Map<Long, String> teamNameByManager = new HashMap<>();
        for (LeagueMemberRepository.MemberRow m : members.forLeague(league.id())) {
            if (m.teamName() != null && !m.teamName().isBlank()) {
                teamNameByManager.put(m.managerId(), m.teamName());
            }
        }
        for (RosterSeasonRepository.StandingRow s : rosterSeasons.forLeague(league.id())) {
            String name = s.managerId() == null ? null : teamNameByManager.get(s.managerId());
            if (name == null) name = s.managerName();
            nameByRoster.put(s.rosterId(), name == null || name.isBlank() ? "Roster " + s.rosterId() : name);
            avatarByRoster.put(s.rosterId(), s.avatarId());
            recordByRoster.put(s.rosterId(), recordAfter(all, league.season(), week, s.rosterId(),
                    matchups.pairedWithScores(List.of(league.id()))));
        }

        Map<Integer, Double> pointsByRoster = new HashMap<>();
        for (RosterWeekPointsRepository.WeekBreakdown w : thisWeek) {
            pointsByRoster.put(w.rosterId(), w.startersPoints());
        }

        // ---- matchups (US5.1): both teams, their records, their final scores
        List<Matchup> games = new ArrayList<>();
        for (LeagueMatchupRepository.PairedGame p : matchups.pairedWithScores(List.of(league.id()))) {
            if (p.season() != league.season() || p.week() != week) continue;
            games.add(new Matchup(
                    new Side(p.aRosterId(), nameByRoster.getOrDefault(p.aRosterId(), "Roster " + p.aRosterId()),
                            avatarByRoster.get(p.aRosterId()), recordByRoster.get(p.aRosterId()),
                            p.aPoints() == null ? 0 : p.aPoints().doubleValue()),
                    new Side(p.bRosterId(), nameByRoster.getOrDefault(p.bRosterId(), "Roster " + p.bRosterId()),
                            avatarByRoster.get(p.bRosterId()), recordByRoster.get(p.bRosterId()),
                            p.bPoints() == null ? 0 : p.bPoints().doubleValue())));
        }

        // ---- top performers (US5.2), ranked by points actually scored
        Map<String, Player> playersBySleeperId = new HashMap<>();
        for (Player pl : players.findAll(settings.sport())) {
            if (pl.sleeperId() != null) playersBySleeperId.put(pl.sleeperId(), pl);
        }
        List<Performer> performers = new ArrayList<>();
        for (RosterWeekPointsRepository.WeekBreakdown w : thisWeek) {
            Map<String, Object> pts = w.playersPointsJson() == null || w.playersPointsJson().isBlank()
                    ? Map.of() : JsonUtil.readMap(w.playersPointsJson());
            Set<String> started = startersOf(w.startersJson());
            for (Map.Entry<String, Object> e : pts.entrySet()) {
                if (!(e.getValue() instanceof Number n)) continue;
                // Only players who were actually started, where we know. A
                // bench player's points are real but were never on the board.
                if (!started.isEmpty() && !started.contains(e.getKey())) continue;
                Player pl = playersBySleeperId.get(e.getKey());
                if (pl == null) continue;
                performers.add(new Performer(e.getKey(), pl.name(), pl.primary().name(),
                        nameByRoster.getOrDefault(w.rosterId(), "Roster " + w.rosterId()), n.doubleValue()));
            }
        }
        performers.sort(Comparator.comparingDouble(Performer::points).reversed()
                .thenComparing(Performer::playerName));
        List<Performer> top = performers.stream().limit(10).toList();

        // ---- awards
        List<Award> awards = new ArrayList<>();
        List<OmittedAward> omitted = new ArrayList<>();
        boolean anyStarters = thisWeek.stream().anyMatch(w -> !startersOf(w.startersJson()).isEmpty());

        Map<Integer, Double> optimalByRoster = new HashMap<>();
        for (RosterWeekPointsRepository.WeekBreakdown w : thisWeek) {
            RealizedLineupService.WeekLineup best =
                    realized.bestLineup(w.playersPointsJson(), playersBySleeperId, settings, rules);
            if (best.valid()) optimalByRoster.put(w.rosterId(), best.points());
        }

        gotAwayWithIt(games, pointsByRoster, optimalByRoster, nameByRoster).ifPresent(awards::add);
        deservedBetter(games, pointsByRoster, nameByRoster).ifPresent(awards::add);
        onePlayerCarry(thisWeek, playersBySleeperId, nameByRoster).ifPresent(awards::add);

        if (anyStarters) {
            selfInflictedWound(games, thisWeek, playersBySleeperId, nameByRoster, settings, rules)
                    .ifPresentOrElse(awards::add,
                            () -> { /* computable, simply nobody qualified this week */ });
        } else {
            omitted.add(new OmittedAward("SELF_INFLICTED_WOUND", STARTERS_NOT_STORED));
        }

        return Optional.of(new Result(true, null, league.season(), week, settings.sport(),
                games, top, awards, omitted));
    }

    // ------------------------------------------------------------- awards

    /** Won while leaving the most points unused. */
    private static Optional<Award> gotAwayWithIt(List<Matchup> games, Map<Integer, Double> actual,
                                                 Map<Integer, Double> optimal, Map<Integer, String> names) {
        Integer worst = null;
        double worstEff = 1.1;
        for (Matchup g : games) {
            for (Side s : List.of(g.home(), g.away())) {
                Side other = s == g.home() ? g.away() : g.home();
                if (s.points() <= other.points()) continue;   // only winners
                Double opt = optimal.get(s.rosterId());
                if (opt == null || opt <= 0) continue;
                double eff = actual.getOrDefault(s.rosterId(), 0.0) / opt;
                if (eff < worstEff) { worstEff = eff; worst = s.rosterId(); }
            }
        }
        if (worst == null || worstEff > 0.95) return Optional.empty();
        double left = optimal.get(worst) - actual.getOrDefault(worst, 0.0);
        return Optional.of(new Award("GOT_AWAY_WITH_IT", names.get(worst),
                String.format("Won while using only %.0f%% of their optimal lineup. They left %.2f"
                        + " possible points unused, but the matchup still broke their way.",
                        worstEff * 100, left)));
    }

    /** Lost despite outscoring most of the league. */
    private static Optional<Award> deservedBetter(List<Matchup> games, Map<Integer, Double> actual,
                                                  Map<Integer, String> names) {
        List<Double> allScores = new ArrayList<>(actual.values());
        allScores.sort(Comparator.reverseOrder());
        Integer best = null;
        int bestRank = Integer.MAX_VALUE;
        for (Matchup g : games) {
            for (Side s : List.of(g.home(), g.away())) {
                Side other = s == g.home() ? g.away() : g.home();
                if (s.points() >= other.points()) continue;   // only losers
                int rank = allScores.indexOf(s.points()) + 1;
                if (rank < bestRank) { bestRank = rank; best = s.rosterId(); }
            }
        }
        if (best == null || bestRank > allScores.size() / 2) return Optional.empty();
        final int loser = best;
        final double loserPoints = actual.getOrDefault(loser, 0.0);
        int outscored = (int) actual.values().stream().filter(v -> v < loserPoints).count();
        return Optional.of(new Award("DESERVED_BETTER", names.get(loser),
                String.format("Lost despite outscoring %d of %d other teams. They finished %d%s in"
                        + " weekly scoring and still took the loss.",
                        outscored, actual.size() - 1, bestRank, ordinalSuffix(bestRank))));
    }

    /** One player supplied an outsized share of a lineup. */
    private static Optional<Award> onePlayerCarry(List<RosterWeekPointsRepository.WeekBreakdown> week,
                                                  Map<String, Player> players,
                                                  Map<Integer, String> names) {
        String bestPlayer = null;
        Integer bestRoster = null;
        double bestShare = 0, bestPoints = 0, bestTotal = 0;
        for (RosterWeekPointsRepository.WeekBreakdown w : week) {
            Set<String> started = startersOf(w.startersJson());
            if (started.isEmpty() || w.startersPoints() <= 0) continue;
            Map<String, Object> pts = JsonUtil.readMap(w.playersPointsJson());
            for (String id : started) {
                if (!(pts.get(id) instanceof Number n)) continue;
                double share = n.doubleValue() / w.startersPoints();
                if (share > bestShare) {
                    bestShare = share; bestPlayer = id; bestRoster = w.rosterId();
                    bestPoints = n.doubleValue(); bestTotal = w.startersPoints();
                }
            }
        }
        if (bestPlayer == null || bestShare < 0.30) return Optional.empty();
        Player p = players.get(bestPlayer);
        return Optional.of(new Award("ONE_PLAYER_CARRY", names.get(bestRoster),
                String.format("%s supplied %.0f%% of their starting lineup's points — %.2f of %.2f.",
                        p == null ? "One player" : p.name(), bestShare * 100, bestPoints, bestTotal)));
    }

    /**
     * Lost by less than the best available bench-for-starter swap would have
     * gained. Needs starter identity, which is why it is the one award that can
     * be omitted.
     */
    private static Optional<Award> selfInflictedWound(List<Matchup> games,
                                                      List<RosterWeekPointsRepository.WeekBreakdown> week,
                                                      Map<String, Player> players,
                                                      Map<Integer, String> names,
                                                      LeagueSettings settings, SportRules rules) {
        Map<Integer, RosterWeekPointsRepository.WeekBreakdown> byRoster = new HashMap<>();
        for (RosterWeekPointsRepository.WeekBreakdown w : week) byRoster.put(w.rosterId(), w);

        for (Matchup g : games) {
            for (Side s : List.of(g.home(), g.away())) {
                Side other = s == g.home() ? g.away() : g.home();
                double margin = other.points() - s.points();
                if (margin <= 0) continue;   // only losers
                RosterWeekPointsRepository.WeekBreakdown w = byRoster.get(s.rosterId());
                if (w == null) continue;
                Set<String> started = startersOf(w.startersJson());
                if (started.isEmpty()) continue;
                Map<String, Object> pts = JsonUtil.readMap(w.playersPointsJson());

                // The best swap available, searched over same-position PAIRS.
                //
                // An earlier version took the single best bench score against
                // the single worst starter score and required those two to
                // share a position. That silently found nothing whenever the
                // worst starter was a kicker or defence -- which is most weeks
                // -- so the award just never fired, indistinguishable from
                // nobody qualifying. Measured against ffwrapped's week 1, which
                // reported a swap this did not find.
                String bestBench = null, worstStarter = null;
                double bestGain = 0;
                for (Map.Entry<String, Object> b : pts.entrySet()) {
                    if (started.contains(b.getKey())) continue;
                    if (!(b.getValue() instanceof Number bn)) continue;
                    Player benched = players.get(b.getKey());
                    if (benched == null) continue;
                    for (Map.Entry<String, Object> t : pts.entrySet()) {
                        if (!started.contains(t.getKey())) continue;
                        if (!(t.getValue() instanceof Number tn)) continue;
                        Player seated = players.get(t.getKey());
                        if (seated == null) continue;
                        // Same position, so the swap is a lineup decision the
                        // manager could actually have made.
                        if (!benched.primary().equals(seated.primary())) continue;
                        double gain = bn.doubleValue() - tn.doubleValue();
                        if (gain > bestGain) {
                            bestGain = gain; bestBench = b.getKey(); worstStarter = t.getKey();
                        }
                    }
                }
                if (bestBench == null || worstStarter == null) continue;
                Player benchP = players.get(bestBench), startP = players.get(worstStarter);
                double gain = bestGain;
                if (gain <= margin) continue;
                return Optional.of(new Award("SELF_INFLICTED_WOUND", names.get(s.rosterId()),
                        String.format("%s outscored %s by %.2f. %s lost by %.2f, so the best bench"
                                + " swap was enough to flip the matchup.",
                                benchP.name(), startP.name(), gain, names.get(s.rosterId()), margin)));
            }
        }
        return Optional.empty();
    }

    // ------------------------------------------------------------- helpers

    /** The starters list, or empty when this week predates V18 and was never refetched. */
    static Set<String> startersOf(String startersJson) {
        if (startersJson == null || startersJson.isBlank()) return Set.of();
        try {
            List<?> raw = JsonUtil.read(startersJson, new com.fasterxml.jackson.core.type.TypeReference<List<?>>() {});
            Set<String> out = new LinkedHashSet<>();
            for (Object o : raw) {
                if (o != null && !"0".equals(String.valueOf(o))) out.add(String.valueOf(o));
            }
            return out;
        } catch (RuntimeException e) {
            return Set.of();
        }
    }

    /** W-L through this week, from the paired games. */
    private static String recordAfter(List<RosterWeekPointsRepository.WeekBreakdown> all, int season,
                                      int week, int rosterId,
                                      List<LeagueMatchupRepository.PairedGame> games) {
        int w = 0, l = 0, t = 0;
        for (LeagueMatchupRepository.PairedGame g : games) {
            if (g.season() != season || g.week() > week) continue;
            if (g.aPoints() == null || g.bPoints() == null) continue;
            double mine, theirs;
            if (g.aRosterId() == rosterId) { mine = g.aPoints().doubleValue(); theirs = g.bPoints().doubleValue(); }
            else if (g.bRosterId() == rosterId) { mine = g.bPoints().doubleValue(); theirs = g.aPoints().doubleValue(); }
            else continue;
            if (mine > theirs) w++; else if (mine < theirs) l++; else t++;
        }
        return t > 0 ? w + "-" + l + "-" + t : w + "-" + l;
    }

    private static String ordinalSuffix(int n) {
        int v = n % 100;
        if (v >= 11 && v <= 13) return "th";
        return switch (n % 10) { case 1 -> "st"; case 2 -> "nd"; case 3 -> "rd"; default -> "th"; };
    }
}
