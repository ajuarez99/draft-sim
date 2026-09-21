package com.ballknowers.draftsim.engine;

import com.ballknowers.draftsim.domain.*;
import com.ballknowers.draftsim.store.PlayerGameRepository;
import com.ballknowers.draftsim.sport.SportRules;
import com.ballknowers.draftsim.sport.SportRulesRegistry;
import com.ballknowers.draftsim.store.*;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
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
    private final LeagueSeasonResolver seasons;
    private final PlayerGameRepository playerGames;
    private final GameScoringService gameScoring;

    public WeeklyReportService(LeagueRepository leagues, RosterWeekPointsRepository weekPoints,
                               LeagueMatchupRepository matchups, RosterSeasonRepository rosterSeasons,
                               LeagueMemberRepository members, PlayerRepository players,
                               SportRulesRegistry rulesRegistry, RealizedLineupService realized,
                               LeagueSeasonResolver seasons, PlayerGameRepository playerGames,
                               GameScoringService gameScoring) {
        this.leagues = leagues;
        this.weekPoints = weekPoints;
        this.matchups = matchups;
        this.rosterSeasons = rosterSeasons;
        this.members = members;
        this.players = players;
        this.rulesRegistry = rulesRegistry;
        this.realized = realized;
        this.seasons = seasons;
        this.playerGames = playerGames;
        this.gameScoring = gameScoring;
    }

    public record Side(int rosterId, String teamName, String avatarId, String record, double points) {}

    public record Matchup(Side home, Side away) {}

    public record Performer(String playerId, String playerName, String position,
                            String teamName, double points) {}

    public record Award(String kind, String teamName, String detail) {}

    /** An award that could not be computed, and why. Never a silent absence. */
    public record OmittedAward(String kind, String reason) {}

    /** One player's one game: what they scored, which night, against whom (US1). */
    public record NightPerformance(String playerId, String playerName, String position,
                                   String teamName, double points, LocalDate date,
                                   String opponent, Boolean isAway) {}

    /** One player's whole fantasy week, across every game they played (US2). */
    public record PlayerWeek(String playerId, String playerName, String position,
                             String teamName, double totalPoints, int gamesPlayed) {}

    /**
     * A section that could not be filled, and why. A discriminator rather than a
     * sentence: the words a reader sees belong in the page, so changing them is
     * not a contract change.
     */
    public record SectionUnavailable(String section, String reason) {}

    /**
     * {@code ALL_GAMES_PLAYED} says the week totals count every game a player
     * played, including games this league's scoring never counted. Required on
     * the basketball shape and never defaulted, because the page's FR-005
     * disclosure is driven by it rather than by prose hardcoded in a component.
     */
    public static final String BASIS_ALL_GAMES = "ALL_GAMES_PLAYED";

    static final String PER_GAME_DETAIL_MISSING = "PER_GAME_DETAIL_MISSING";
    static final String SECTION_BEST_NIGHTS = "BEST_NIGHTS";
    static final String SECTION_BEST_WEEK = "BEST_WEEK";

    /** How many entries each ranking carries. A presentation choice, not a rule. */
    private static final int RANK_LIMIT = 5;

    /**
     * Carries EITHER {@code topPerformers} OR the {@code bestNights}/{@code bestWeek}
     * pair, never both -- decided by the sport's own cadence rule.
     *
     * <p>A null side means "this measure does not apply to this sport", and
     * {@code WeeklyReportController} leaves it out of the response entirely
     * rather than sending null or an empty array. An empty array would say "we
     * looked and there were none", which is a different claim.
     */
    public record Result(boolean available, String reason, int season, Integer requestedSeason,
                         int week, Sport sport, boolean playersPlayMultiplePerPeriod,
                         List<Matchup> matchups, List<Performer> topPerformers,
                         List<NightPerformance> bestNights, List<PlayerWeek> bestWeek,
                         String basis, List<SectionUnavailable> sectionsUnavailable,
                         List<Award> awards, List<OmittedAward> awardsOmitted) {

        static Result unavailable(String reason, int season, int week, Sport sport) {
            return new Result(false, reason, season, null, week, sport, false,
                    List.of(), List.of(), null, null, null, null, List.of(), List.of());
        }
    }

    /** Reason code for an award that needs starter identity and cannot have it. */
    static final String STARTERS_NOT_STORED = "STARTERS_NOT_STORED";

    public Optional<Result> forWeek(String sleeperLeagueId, int week) {
        Optional<LeagueSeasonResolver.Resolved> found = seasons.resolve(sleeperLeagueId);
        if (found.isEmpty()) return Optional.empty();
        LeagueRepository.LeagueRow league = found.get().league();
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

        // ---- Best Nights / Best Week (specs/005, US1 + US2)
        //
        // Which form this league gets comes from the sport's own cadence rule and
        // from nowhere else. No sport name is compared here (FR-004).
        boolean multipleGames = rules.playsMultipleGamesPerScoringPeriod();
        List<NightPerformance> bestNights = null;
        List<PlayerWeek> bestWeek = null;
        String basis = null;
        List<SectionUnavailable> sectionsUnavailable = null;
        List<Performer> topForResult = top;

        if (multipleGames) {
            // The pair replaces the single list rather than joining it: three
            // overlapping rankings of the same week is not a richer page.
            topForResult = null;
            basis = BASIS_ALL_GAMES;
            sectionsUnavailable = new ArrayList<>();

            Map<Integer, String> ownerByRoster = nameByRoster;
            Map<String, Integer> rosterByPlayer = new HashMap<>();
            for (RosterWeekPointsRepository.WeekBreakdown w : thisWeek) {
                if (w.playersPointsJson() == null || w.playersPointsJson().isBlank()) continue;
                for (String pid : JsonUtil.readMap(w.playersPointsJson()).keySet()) {
                    rosterByPlayer.put(pid, w.rosterId());
                }
            }

            Map<String, Double> scoring = leagues.scoringOf(league.id());
            List<PlayerGameRepository.Row> rows =
                    playerGames.forWeek(settings.sport(), league.season(), week);

            // Rows exist for every player in the sport, not just this league's.
            // Restricting to the rostered set is what keeps this a league page.
            List<NightPerformance> nights = new ArrayList<>();
            Map<String, double[]> weekTotals = new HashMap<>();
            for (PlayerGameRepository.Row r : rows) {
                Integer rosterId = rosterByPlayer.get(r.sleeperPlayerId());
                if (rosterId == null) continue;
                Player pl = playersBySleeperId.get(r.sleeperPlayerId());
                if (pl == null) continue;

                double pts = gameScoring.score(scoring, JsonUtil.readMap(r.statsJson()));
                String owner = ownerByRoster.getOrDefault(rosterId, "Roster " + rosterId);
                nights.add(new NightPerformance(r.sleeperPlayerId(), pl.name(), pl.primary().name(),
                        owner, pts, r.gameDate(), r.opponent(), r.isAway()));

                double[] acc = weekTotals.computeIfAbsent(r.sleeperPlayerId(), k -> new double[2]);
                acc[0] += pts;
                acc[1] += 1;
            }

            if (nights.isEmpty()) {
                // No per-game detail for this week. Say so for both sections
                // rather than falling back to the stored single-game value and
                // presenting it as a night or a week (FR-006).
                sectionsUnavailable.add(new SectionUnavailable(SECTION_BEST_NIGHTS, PER_GAME_DETAIL_MISSING));
                sectionsUnavailable.add(new SectionUnavailable(SECTION_BEST_WEEK, PER_GAME_DETAIL_MISSING));
                bestNights = List.of();
                bestWeek = List.of();
            } else {
                bestNights = rankNights(nights, RANK_LIMIT);

                List<PlayerWeek> weeks = new ArrayList<>();
                for (Map.Entry<String, double[]> e : weekTotals.entrySet()) {
                    int played = (int) e.getValue()[1];
                    // A player who played nothing is absent, never a 0.0 row.
                    if (played == 0) continue;
                    Player pl = playersBySleeperId.get(e.getKey());
                    Integer rosterId = rosterByPlayer.get(e.getKey());
                    weeks.add(new PlayerWeek(e.getKey(), pl.name(), pl.primary().name(),
                            ownerByRoster.getOrDefault(rosterId, "Roster " + rosterId),
                            Math.round(e.getValue()[0] * 100.0) / 100.0, played));
                }
                bestWeek = rankWeeks(weeks, RANK_LIMIT);
            }
        }

        return Optional.of(new Result(true, null, league.season(), found.get().requestedSeason(),
                week, settings.sport(), multipleGames, games, topForResult,
                bestNights, bestWeek, basis, sectionsUnavailable, awards, omitted));
    }

    /**
     * Best Nights order: points descending, then player id, then date (FR-009).
     *
     * <p>Extracted and named because the tiebreak is the part that matters and
     * the part nobody would think to check. Half-point scoring makes exact ties
     * ordinary rather than rare -- two 44.0 nights in one week is a Tuesday --
     * and an unstable sort would reorder them between two loads of the same
     * finished week, which reads as data changing under the reader.
     *
     * <p>One player can hold two rows here, which is why date breaks the tie
     * after the player id rather than before it.
     */
    static List<NightPerformance> rankNights(List<NightPerformance> nights, int limit) {
        List<NightPerformance> sorted = new ArrayList<>(nights);
        sorted.sort(Comparator.comparingDouble(NightPerformance::points).reversed()
                .thenComparing(NightPerformance::playerId)
                .thenComparing(n -> n.date().toString()));
        return sorted.stream().limit(limit).toList();
    }

    /** Best Week order: total descending, then player id. Same reasoning. */
    static List<PlayerWeek> rankWeeks(List<PlayerWeek> weeks, int limit) {
        List<PlayerWeek> sorted = new ArrayList<>(weeks);
        sorted.sort(Comparator.comparingDouble(PlayerWeek::totalPoints).reversed()
                .thenComparing(PlayerWeek::playerId));
        return sorted.stream().limit(limit).toList();
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
