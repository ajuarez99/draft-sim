package com.ballknowers.draftsim.engine;

import com.ballknowers.draftsim.config.DraftGradeProperties;
import com.ballknowers.draftsim.domain.Player;
import com.ballknowers.draftsim.domain.Sport;
import com.ballknowers.draftsim.ingest.DraftOrderMapper;
import com.ballknowers.draftsim.sport.SportRules;
import com.ballknowers.draftsim.sport.SportRulesRegistry;
import com.ballknowers.draftsim.store.DraftRepository;
import com.ballknowers.draftsim.store.JsonUtil;
import com.ballknowers.draftsim.store.LeagueMatchupRepository;
import com.ballknowers.draftsim.store.LeagueRepository;
import com.ballknowers.draftsim.store.ManagerRepository;
import com.ballknowers.draftsim.store.PlayerGameRepository;
import com.ballknowers.draftsim.store.PlayerRepository;
import com.ballknowers.draftsim.store.RosterSeasonRepository;
import com.ballknowers.draftsim.store.RosterWeekPointsRepository;
import com.ballknowers.draftsim.store.SportWeekStatsRepository;
import com.fasterxml.jackson.core.type.TypeReference;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

/**
 * How each pick of a completed draft actually played out (specs/018-draft-grades; math in
 * data-model.md). Production is the league's own scoring applied to the player's real games; a pick
 * is then compared with the same-position picks drafted around him, and a team's grade is its picks'
 * value summed, centred on the draft's average team and ranked.
 *
 * <p>{@link #grade(Input)} is the pure core: plain values in, plain values out, no I/O.
 * {@link #read(DraftRepository.DraftRow)} does the reading and the state check order.
 *
 * <p>The only fitted thing is the per-position line of production against ln(pick number), and it is
 * refit from this draft's own picks on every read. The minimum picks a position needs before it gets a
 * line is the hand-set, ARBITRARY {@link DraftGradeProperties#minPicksPerPosition()}, and a letter is
 * only a reading of a rank.
 */
@Service
public class DraftGradesService {

    public enum ProductionBasis { WEEKLY_GAME, WEEKLY_AVERAGE_GAME }

    /** Reasons are codes; the sentences live in the web client. */
    public static final String NOT_CONFIGURED = "NOT_CONFIGURED";
    public static final String DRAFT_NOT_COMPLETE = "DRAFT_NOT_COMPLETE";
    public static final String NO_SCORED_WEEKS = "NO_SCORED_WEEKS";

    private static final int LIST_SIZE = 5;

    // ------------------------------------------------------------------ wire records (contract C1)

    public record PickGrade(int pickNo, int round, int slot, String sleeperPlayerId, String playerName,
                            String position, double production, int weeksPlayed,
                            Double slotBaseline, Double valueOverSlot,
                            Integer positionDrafted, Integer positionFinish,
                            Double countedForYou, Double creditedForYou,
                            Integer weeksStartedForYou, Integer weeksUnknownForYou) {}

    public record TeamGrade(int slot, String manager, String avatarId, Double draftValue, Integer rank,
                            String grade, Integer bestPickNo, Integer worstPickNo) {}

    public record DraftGrades(String draftId, Sport sport, int season, boolean available, String reason,
                              ProductionBasis productionBasis, List<Integer> countedWeeks, int weeksCounted,
                              List<Integer> weeksMissingGameData, boolean gradesEarly, int earlyThresholdWeeks,
                              Integer minPicksPerPosition, int excludedPicks, int unmappedPicks,
                              int unpositionedPicks, Double averageTeamRawValue,
                              List<PickGrade> picks, List<TeamGrade> teams,
                              List<Integer> steals, List<Integer> busts) {}

    // ------------------------------------------------------------------ pure core types

    /**
     * A graded pick's identity.
     *
     * @param group    the fit-and-rank group from {@code SportRules.draftGradeGroup} (null = none, football only)
     * @param position display only: football's first listed position, basketball's eligibility joined with "/"
     */
    public record PickIn(int pickNo, int round, int slot, Long managerId, String sleeperId, String name,
                         String group, String position) {}

    public record TeamIn(int slot, String manager, String avatarId) {}

    /**
     * What "for you" needs (user story 3). Rosters are keyed by roster id.
     *
     * @param rosterByManager       manager id to roster id; a manager with anything but exactly one roster row is absent
     * @param startersByRosterWeek  roster to week to the sleeper ids in that week's starting lineup; a roster-week
     *                              whose starters or points are unknown is absent
     * @param pointsByRosterWeek    roster to week to Sleeper's {@code players_points}; same presence as the starters
     * @param matchupWeeksByRoster  the weeks a roster had a game (a non-null matchup id)
     * @param unknownWeeksByRoster  the weeks whose answer is unknown (data-model F10); wins over everything else
     */
    public record RosterInputs(Map<Long, Long> rosterByManager,
                               Map<Long, Map<Integer, Set<String>>> startersByRosterWeek,
                               Map<Long, Map<Integer, Map<String, Double>>> pointsByRosterWeek,
                               Map<Long, Set<Integer>> matchupWeeksByRoster,
                               Map<Long, Set<Integer>> unknownWeeksByRoster) {
        public static final RosterInputs EMPTY = new RosterInputs(Map.of(), Map.of(), Map.of(), Map.of(), Map.of());
    }

    /**
     * @param gamePointsByPlayerWeek sleeper id to week to the points of each game he played that week,
     *                               already scored under the league's scoring. No entry = did not play.
     * @param countedWeeks           the only weeks that count
     */
    public record Input(List<PickIn> picks, Map<String, Map<Integer, List<Double>>> gamePointsByPlayerWeek,
                        Set<Integer> countedWeeks, RosterInputs rosters, int minPicksPerPosition,
                        List<TeamIn> teams) {}

    public record Result(List<PickGrade> picks, List<TeamGrade> teams, List<Integer> steals,
                         List<Integer> busts, int unpositionedPicks, int unmappedPicks,
                         Double averageTeamRawValue) {}

    // ------------------------------------------------------------------ collaborators

    private final LetterGrades letterGrades;
    private final DraftGradeProperties props;
    private final DraftRepository drafts;
    private final PlayerRepository players;
    private final PlayerGameRepository playerGames;
    private final SportWeekStatsRepository weekStats;
    private final LeagueRepository leagues;
    private final ScoredWeeks scoredWeeks;
    private final GameScoringService gameScoring;
    private final SportRulesRegistry rules;
    private final ManagerRepository managers;
    private final RosterSeasonRepository rosterSeasons;
    private final RosterWeekPointsRepository rosterWeekPoints;
    private final LeagueMatchupRepository matchups;

    public DraftGradesService(LetterGrades letterGrades, DraftGradeProperties props, DraftRepository drafts,
                              PlayerRepository players, PlayerGameRepository playerGames,
                              SportWeekStatsRepository weekStats, LeagueRepository leagues,
                              ScoredWeeks scoredWeeks, GameScoringService gameScoring,
                              SportRulesRegistry rules, ManagerRepository managers,
                              RosterSeasonRepository rosterSeasons, RosterWeekPointsRepository rosterWeekPoints,
                              LeagueMatchupRepository matchups) {
        this.letterGrades = letterGrades;
        this.props = props;
        this.drafts = drafts;
        this.players = players;
        this.playerGames = playerGames;
        this.weekStats = weekStats;
        this.leagues = leagues;
        this.scoredWeeks = scoredWeeks;
        this.gameScoring = gameScoring;
        this.rules = rules;
        this.managers = managers;
        this.rosterSeasons = rosterSeasons;
        this.rosterWeekPoints = rosterWeekPoints;
        this.matchups = matchups;
    }

    // ------------------------------------------------------------------ read: state check order

    /** Data-model "States and check order": the first condition that matches answers. */
    public DraftGrades read(DraftRepository.DraftRow draft) {
        // Sport and season are on every response, so the league row is read first. It is the draft's
        // own league, never LeagueSeasonResolver (which answers "the current season of this league").
        // A missing league row falls back to NFL the way /board does; it cannot grade anything anyway.
        Sport sport = leagues.byId(draft.leagueId()).map(LeagueRepository.LeagueRow::sport).orElse(Sport.NFL);
        SportRules sportRules = rules.get(sport);
        boolean multiGame = sportRules.playsMultipleGamesPerScoringPeriod();
        ProductionBasis basis = multiGame
                ? ProductionBasis.WEEKLY_AVERAGE_GAME : ProductionBasis.WEEKLY_GAME;

        if (!props.loaded()) return unavailable(draft, sport, basis, NOT_CONFIGURED, List.of());
        if (!"complete".equals(draft.status())) {
            return unavailable(draft, sport, basis, DRAFT_NOT_COMPLETE, List.of());
        }

        Set<Integer> finals = scoredWeeks.of(draft.leagueId()).finalWeeks();
        TreeSet<Integer> counted = countedWeeks(finals, weekStats.forSeason(sport, draft.season()));
        TreeSet<Integer> missing = new TreeSet<>(finals);
        missing.removeAll(counted);
        if (counted.isEmpty()) {
            return unavailable(draft, sport, basis, NO_SCORED_WEEKS, List.copyOf(missing));
        }

        Map<String, Double> scoring = leagues.scoringOf(draft.leagueId());
        Map<Long, Player> playersById = new HashMap<>();
        for (Player p : players.findAll(sport)) playersById.put(p.id(), p);

        List<DraftRepository.PickRow> stored = drafts.picks(draft.id());
        List<PickIn> picks = new ArrayList<>();
        int excluded = 0;
        for (DraftRepository.PickRow pr : stored) {
            Player p = pr.playerId() == null ? null : playersById.get(pr.playerId());
            if (p == null || p.sleeperId() == null) {
                excluded++;
                continue;
            }
            String display = positionsDisplay(p.positions(), multiGame);
            picks.add(new PickIn(pr.pickNo(), pr.round(), pr.draftSlot(), pr.managerId(), p.sleeperId(), p.name(),
                    sportRules.draftGradeGroup(p), display));
        }

        Map<String, Map<Integer, List<Double>>> games = new HashMap<>();
        if (!picks.isEmpty()) {
            List<String> ids = picks.stream().map(PickIn::sleeperId).distinct().toList();
            for (PlayerGameRepository.Row g : playerGames.forPlayers(sport, draft.season(), ids)) {
                if (!counted.contains(g.week())) continue;
                double pts = gameScoring.score(scoring, JsonUtil.readMap(g.statsJson()));
                games.computeIfAbsent(g.sleeperPlayerId(), k -> new HashMap<>())
                        .computeIfAbsent(g.week(), k -> new ArrayList<>()).add(pts);
            }
        }

        RosterInputs rosters = picks.isEmpty() ? RosterInputs.EMPTY : rosterInputs(draft, counted);
        Result r = grade(new Input(picks, games, counted, rosters, props.minPicksPerPosition(),
                teamsOf(draft, stored)));
        int weeksCounted = counted.size();
        return new DraftGrades(draft.sleeperDraftId(), sport, draft.season(), true, null, basis,
                List.copyOf(counted), weeksCounted, List.copyOf(missing),
                SeasonWindow.isEarly(weeksCounted), SeasonWindow.EARLY_THRESHOLD_WEEKS,
                props.minPicksPerPosition(), excluded, r.unmappedPicks(), r.unpositionedPicks(), r.averageTeamRawValue(),
                r.picks(), r.teams(), r.steals(), r.busts());
    }

    /**
     * The league's final weeks that also have FINAL per-game data (B3). A present-but-not-final week would
     * read a player who played after the last fetch as "didn't play", so it is left out and reported missing.
     */
    static TreeSet<Integer> countedWeeks(Set<Integer> finalLeagueWeeks, List<SportWeekStatsRepository.Row> gameWeeks) {
        Set<Integer> finalGameWeeks = new HashSet<>();
        for (SportWeekStatsRepository.Row w : gameWeeks) {
            if (w.fin()) finalGameWeeks.add(w.week());
        }
        TreeSet<Integer> counted = new TreeSet<>(finalLeagueWeeks);
        counted.retainAll(finalGameWeeks);
        return counted;
    }

    private DraftGrades unavailable(DraftRepository.DraftRow draft, Sport sport, ProductionBasis basis,
                                    String reason, List<Integer> missing) {
        return new DraftGrades(draft.sleeperDraftId(), sport, draft.season(), false, reason, basis,
                List.of(), 0, missing, SeasonWindow.isEarly(0), SeasonWindow.EARLY_THRESHOLD_WEEKS,
                props.minPicksPerPosition(), 0, 0, 0, null, List.of(), List.of(), List.of(), List.of());
    }

    /**
     * Data-model "Counted for the drafting team". In a week the roster had a game, it is unknown when its
     * starters or points are null / {@code "{}"} (RosterWeekPointsRepository's "no answer") or have no stored
     * row. A week no roster at all has a matchup in is unknown for everyone (the schedule was never stored, so
     * a bye cannot be told from missing data). {@code between} drops null matchup ids, so a roster missing
     * from a week the others are in had no game (a bye, or a playoff week it is out of): not counted, not unknown.
     */
    private RosterInputs rosterInputs(DraftRepository.DraftRow draft, TreeSet<Integer> counted) {
        long leagueId = draft.leagueId();
        Map<Long, List<Integer>> rostersByManager = new HashMap<>();
        for (RosterSeasonRepository.StandingRow row : rosterSeasons.forLeague(leagueId)) {
            if (row.managerId() != null) {
                rostersByManager.computeIfAbsent(row.managerId(), k -> new ArrayList<>()).add(row.rosterId());
            }
        }
        Map<Long, Long> rosterByManager = new HashMap<>();
        rostersByManager.forEach((m, ids) -> {
            if (ids.size() == 1) rosterByManager.put(m, (long) ids.getFirst());
        });

        Map<Long, Map<Integer, Set<String>>> starters = new HashMap<>();
        Map<Long, Map<Integer, Map<String, Double>>> points = new HashMap<>();
        for (RosterWeekPointsRepository.WeekBreakdown b : rosterWeekPoints.breakdownsFor(leagueId, draft.season())) {
            if (!counted.contains(b.week())) continue;
            if (b.startersJson() == null || b.playersPointsJson() == null) continue;
            Map<String, Object> pts = JsonUtil.readMap(b.playersPointsJson());
            if (pts.isEmpty()) continue;
            List<String> started = JsonUtil.read(b.startersJson(), new TypeReference<List<String>>() {});
            if (started == null) continue;
            Map<String, Double> numeric = new HashMap<>();
            pts.forEach((k, v) -> numeric.put(k, v instanceof Number n ? n.doubleValue() : 0.0));
            starters.computeIfAbsent((long) b.rosterId(), k -> new HashMap<>()).put(b.week(), new HashSet<>(started));
            points.computeIfAbsent((long) b.rosterId(), k -> new HashMap<>()).put(b.week(), numeric);
        }

        Map<Long, Set<Integer>> matchupWeeks = new HashMap<>();
        Set<Integer> scheduledWeeks = new HashSet<>();
        for (LeagueMatchupRepository.Fixture f : matchups.between(leagueId, draft.season(), counted.first(), counted.last())) {
            if (f.matchupId() == null) continue;
            matchupWeeks.computeIfAbsent((long) f.rosterId(), k -> new HashSet<>()).add(f.week());
            scheduledWeeks.add(f.week());
        }

        Set<Long> allRosters = new HashSet<>(rosterByManager.values());
        Map<Long, Set<Integer>> unknown = new HashMap<>();
        for (long roster : allRosters) {
            Set<Integer> u = new HashSet<>();
            for (int w : counted) {
                boolean noData = !starters.getOrDefault(roster, Map.of()).containsKey(w);
                boolean hadGame = matchupWeeks.getOrDefault(roster, Set.of()).contains(w);
                // No game that week is never "unknown" (it counts nowhere), unless the schedule itself is missing.
                if (!scheduledWeeks.contains(w) || (hadGame && noData)) u.add(w);
            }
            unknown.put(roster, u);
        }
        return new RosterInputs(rosterByManager, starters, points, matchupWeeks, unknown);
    }

    /**
     * Draft slot to the drafting manager's display name, by Draft Grades' own naming rule (slot, then manager,
     * then display name: {@link #teamsOf}). The stats leaderboard names a pick's manager through this so it
     * matches the Draft Grades page it links to (spec 022 F12). A slot with no known manager is absent.
     */
    public Map<Integer, String> managerNamesBySlot(DraftRepository.DraftRow draft, List<DraftRepository.PickRow> stored) {
        Map<Integer, String> out = new HashMap<>();
        for (TeamIn t : teamsOf(draft, stored)) if (t.manager() != null) out.put(t.slot(), t.manager());
        return out;
    }

    /** One team per draft slot, named the way {@code /board} names a pick: by the slot's manager. */
    private List<TeamIn> teamsOf(DraftRepository.DraftRow draft, List<DraftRepository.PickRow> stored) {
        Map<Long, String> names = managers.names();
        Map<Long, String> avatars = managers.avatarIds();
        Map<String, Long> bySlot = DraftOrderMapper.normalize(draft.slotToManager());
        TreeSet<Integer> slots = new TreeSet<>();
        for (int s = 1; s <= draft.teams(); s++) slots.add(s);
        Map<Integer, Long> pickManager = new HashMap<>();
        for (DraftRepository.PickRow p : stored) {
            slots.add(p.draftSlot());
            if (p.managerId() != null) pickManager.putIfAbsent(p.draftSlot(), p.managerId());
        }
        List<TeamIn> out = new ArrayList<>();
        for (int slot : slots) {
            Long managerId = bySlot.get(String.valueOf(slot));
            if (managerId == null) managerId = pickManager.get(slot);
            out.add(new TeamIn(slot, managerId == null ? null : names.get(managerId),
                    managerId == null ? null : avatars.get(managerId)));
        }
        return out;
    }

    // ------------------------------------------------------------------ pure core

    /** Working row for one pick while the core runs. */
    private static final class Acc {
        final PickIn in;
        final String position;
        final String group;
        double production;
        int weeksPlayed;
        Double baseline;
        Double value;
        Integer positionDrafted;
        Integer positionFinish;

        Acc(PickIn in, String position, String group) {
            this.in = in;
            this.position = position;
            this.group = group;
        }
    }

    private static final class TeamAcc {
        final TeamIn in;
        Double raw;
        Double centred;
        Integer rank;

        TeamAcc(TeamIn in) {
            this.in = in;
        }
    }

    public Result grade(Input input) {
        List<Acc> accs = new ArrayList<>();
        for (PickIn p : input.picks()) {
            Acc a = new Acc(p, p.position(), p.group());
            Map<Integer, List<Double>> byWeek = input.gamePointsByPlayerWeek().getOrDefault(p.sleeperId(), Map.of());
            for (int week : input.countedWeeks()) {
                List<Double> games = byWeek.get(week);
                if (games == null || games.isEmpty()) continue;
                a.production += games.stream().mapToDouble(Double::doubleValue).average().orElse(0.0);
                a.weeksPlayed++;
            }
            accs.add(a);
        }
        accs.sort(Comparator.comparingInt(a -> a.in.pickNo()));

        // Per-group log fit and ranks (group = SportRules.draftGradeGroup). Null group: graded on production only.
        Map<String, List<Acc>> byPosition = new LinkedHashMap<>();
        int unpositioned = 0;
        for (Acc a : accs) {
            if (a.group == null) {
                unpositioned++;
                continue;
            }
            byPosition.computeIfAbsent(a.group, k -> new ArrayList<>()).add(a);   // already pickNo order
        }
        for (List<Acc> same : byPosition.values()) {
            for (int k = 0; k < same.size(); k++) same.get(k).positionDrafted = k + 1;
            double[] fit = logFit(same, input.minPicksPerPosition());
            if (fit != null) {
                for (Acc a : same) {
                    a.baseline = fit[0] + fit[1] * Math.log(a.in.pickNo());
                    a.value = a.production - a.baseline;
                }
            }
            List<Acc> byFinish = new ArrayList<>(same);
            byFinish.sort(Comparator.comparingDouble((Acc a) -> round2(a.production)).reversed()
                    .thenComparingInt(a -> a.in.pickNo()));
            for (int k = 0; k < byFinish.size(); k++) byFinish.get(k).positionFinish = k + 1;
        }

        // Teams.
        List<TeamAcc> teams = new ArrayList<>();
        for (TeamIn t : input.teams()) teams.add(new TeamAcc(t));
        for (TeamAcc t : teams) {
            double sum = 0;
            boolean any = false;
            for (Acc a : accs) {
                if (a.in.slot() == t.in.slot() && a.value != null) {
                    sum += a.value;
                    any = true;
                }
            }
            t.raw = any ? sum : null;
        }
        List<TeamAcc> withRaw = teams.stream().filter(t -> t.raw != null).toList();
        Double average = withRaw.isEmpty() ? null
                : withRaw.stream().mapToDouble(t -> t.raw).sum() / withRaw.size();
        for (TeamAcc t : withRaw) t.centred = t.raw - average;
        Map<TeamAcc, Integer> ranks = LetterGrades.ranksDescending(teams, t -> t.centred, t -> t.centred != null);
        int rankedCount = ranks.size();

        List<TeamGrade> teamOut = new ArrayList<>();
        for (TeamAcc t : teams) {
            Integer rank = ranks.get(t);
            Acc best = null, worst = null;
            for (Acc a : accs) {
                if (a.in.slot() != t.in.slot() || a.value == null) continue;
                // accs is in pickNo order, and values compare as shown (2 dp), so ties keep the lower pickNo.
                if (best == null || round2(a.value) > round2(best.value)) best = a;
                if (worst == null || round2(a.value) < round2(worst.value)) worst = a;
            }
            teamOut.add(new TeamGrade(t.in.slot(), t.in.manager(), t.in.avatarId(), round2(t.centred), rank,
                    rank == null ? null : letterGrades.grade(rank, rankedCount),
                    best == null ? null : best.in.pickNo(), worst == null ? null : worst.in.pickNo()));
        }

        // Steals and busts.
        List<Acc> valued = accs.stream().filter(a -> a.value != null).toList();
        List<Acc> top = new ArrayList<>(valued);
        top.sort(Comparator.comparingDouble((Acc a) -> round2(a.value)).reversed().thenComparingInt(a -> a.in.pickNo()));
        List<Integer> steals = top.stream().limit(LIST_SIZE).map(a -> a.in.pickNo()).toList();
        List<Acc> bottom = new ArrayList<>(valued);
        bottom.sort(Comparator.comparingDouble((Acc a) -> round2(a.value)).thenComparingInt(a -> a.in.pickNo()));
        List<Integer> busts = bottom.stream().map(a -> a.in.pickNo()).filter(n -> !steals.contains(n))
                .limit(LIST_SIZE).toList();

        List<PickGrade> picksOut = new ArrayList<>();
        int unmapped = 0;
        RosterInputs ri = input.rosters();
        for (Acc a : accs) {
            Long roster = a.in.managerId() == null ? null : ri.rosterByManager().get(a.in.managerId());
            Double counted = null, credited = null;
            Integer started = null, unknown = null;
            if (roster == null) {
                unmapped++;
            } else {
                double c = 0, cr = 0;
                int st = 0, un = 0;
                Map<Integer, Set<String>> lineups = ri.startersByRosterWeek().getOrDefault(roster, Map.of());
                Map<Integer, Map<String, Double>> pts = ri.pointsByRosterWeek().getOrDefault(roster, Map.of());
                Set<Integer> unknownWeeks = ri.unknownWeeksByRoster().getOrDefault(roster, Set.of());
                Set<Integer> gameWeeks = ri.matchupWeeksByRoster().getOrDefault(roster, Set.of());
                Map<Integer, List<Double>> byWeek = input.gamePointsByPlayerWeek().getOrDefault(a.in.sleeperId(), Map.of());
                for (int week : input.countedWeeks()) {
                    if (unknownWeeks.contains(week)) {
                        un++;
                        continue;
                    }
                    if (!gameWeeks.contains(week)) continue;
                    Set<String> lineup = lineups.get(week);
                    if (lineup == null || !lineup.contains(a.in.sleeperId())) continue;
                    st++;
                    List<Double> games = byWeek.get(week);
                    if (games != null && !games.isEmpty()) {
                        c += games.stream().mapToDouble(Double::doubleValue).average().orElse(0.0);
                    }
                    Double credit = pts.getOrDefault(week, Map.of()).get(a.in.sleeperId());
                    if (credit != null) cr += credit;
                }
                counted = round2(c);
                credited = round2(cr);
                started = st;
                unknown = un;
            }
            picksOut.add(new PickGrade(a.in.pickNo(), a.in.round(), a.in.slot(), a.in.sleeperId(), a.in.name(),
                    a.position, round2(a.production), a.weeksPlayed, round2(a.baseline), round2(a.value),
                    a.positionDrafted, a.positionFinish, counted, credited, started, unknown));
        }
        return new Result(picksOut, teamOut, steals, busts, unpositioned, unmapped, round2(average));
    }

    /**
     * Ordinary least squares of production on ln(pickNo): {a, b}. Null when the position has fewer than
     * {@code minPicks} picks or all its picks share one pick number (no variance to fit a slope to).
     */
    private static double[] logFit(List<Acc> same, int minPicks) {
        int n = same.size();
        if (n < minPicks || n < 2) return null;
        double mx = 0, my = 0;
        for (Acc a : same) {
            mx += Math.log(a.in.pickNo());
            my += a.production;
        }
        mx /= n;
        my /= n;
        double sxx = 0, sxy = 0;
        for (Acc a : same) {
            double dx = Math.log(a.in.pickNo()) - mx;
            sxx += dx * dx;
            sxy += dx * (a.production - my);
        }
        if (sxx < 1e-12) return null;
        double b = sxy / sxx;
        return new double[] {my - b * mx, b};
    }

    private static Double round2(Double v) {
        return v == null ? null : Math.round(v * 100.0) / 100.0;
    }

    private static double round2(double v) {
        return Math.round(v * 100.0) / 100.0;
    }

    /**
     * The pick's position label. Basketball joins the whole eligibility in the fixed PG,SG,SF,PF,C order
     * (enum declaration order), NOT stored order: stored order leads with Sleeper's primary (spec 026), so
     * joining it directly would flip "PG/SG" to "SG/PG" for the same player. Football shows the first listed.
     */
    static String positionsDisplay(List<com.ballknowers.draftsim.domain.Position> positions, boolean multiGame) {
        if (positions.isEmpty()) return null;
        if (!multiGame) return positions.getFirst().name();
        return positions.stream().sorted().map(Enum::name).collect(java.util.stream.Collectors.joining("/"));
    }
}
