package com.ballknowers.draftsim.engine;

import com.ballknowers.draftsim.domain.Player;
import com.ballknowers.draftsim.domain.Sport;
import com.ballknowers.draftsim.sport.SportRules;
import com.ballknowers.draftsim.sport.SportRulesRegistry;
import com.ballknowers.draftsim.store.*;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.util.*;
import java.util.stream.Collectors;
import com.ballknowers.draftsim.domain.LeagueSettings;

/**
 * Season superlatives, so far (specs/008-season-superlatives). One payload for
 * the whole page: every superlative is computed against the same season
 * window, so nothing on the page can disagree with anything else about which
 * weeks it covers (contracts/superlatives-api.md).
 *
 * <p><b>US1 and US2 are built.</b> US1 is the season's extremes and close
 * games; US2 is LUCKIEST/UNLUCKIEST (from the regular-season-bounded {@link
 * ExpectedWinsService}) and MOST_BENCH_POINTS (from {@link RealizedLineupService}).
 * The remaining three kinds -- WAIVER_WIRE_WARRIOR, JOEL_EMBIID, UNETHICAL --
 * return {@code available: false, reason: "not built yet"} until their own
 * phases wire them in. All twelve kinds are always present, in contract order
 * (FR-002): an absent section is never how this payload says "not built yet".
 */
@Service
public class SeasonSuperlativesService {

    /** research R6, hand-set, arbitrary: fewer than this many scored weeks and every rate-based read is mostly noise. */
    static final int EARLY_THRESHOLD_WEEKS = 4;

    private final LeagueRepository leagues;
    private final LeagueSeasonResolver seasons;
    private final RosterWeekPointsRepository weekPoints;
    private final LeagueMatchupRepository matchups;
    private final RosterSeasonRepository rosterSeasons;
    private final LeagueMemberRepository members;
    private final LeagueRecordService records;
    private final SportRulesRegistry rulesRegistry;
    private final ExpectedWinsService expectedWins;
    private final RealizedLineupService realizedLineups;
    private final PlayerRepository players;
    private final LeagueTransactionRepository transactions;
    private final PlayerAbsenceRepository absences;
    private final PlayerGameRepository games;
    private final GameScoringService gameScoring;
    private final StatusCaptureRepository statusCaptures;
    private final LeagueConductRepository conduct;

    public SeasonSuperlativesService(LeagueRepository leagues, LeagueSeasonResolver seasons,
                                     RosterWeekPointsRepository weekPoints, LeagueMatchupRepository matchups,
                                     RosterSeasonRepository rosterSeasons, LeagueMemberRepository members,
                                     LeagueRecordService records, SportRulesRegistry rulesRegistry,
                                     ExpectedWinsService expectedWins, RealizedLineupService realizedLineups,
                                     PlayerRepository players, LeagueTransactionRepository transactions,
                                     PlayerAbsenceRepository absences, PlayerGameRepository games,
                                     GameScoringService gameScoring, StatusCaptureRepository statusCaptures,
                                     LeagueConductRepository conduct) {
        this.leagues = leagues;
        this.seasons = seasons;
        this.weekPoints = weekPoints;
        this.matchups = matchups;
        this.rosterSeasons = rosterSeasons;
        this.members = members;
        this.records = records;
        this.rulesRegistry = rulesRegistry;
        this.expectedWins = expectedWins;
        this.realizedLineups = realizedLineups;
        this.players = players;
        this.transactions = transactions;
        this.absences = absences;
        this.games = games;
        this.gameScoring = gameScoring;
        this.statusCaptures = statusCaptures;
        this.conduct = conduct;
    }

    // --------------------------------------------------------------- shapes

    public enum Kind {
        HIGHEST_WEEK, LOWEST_WEEK, BIGGEST_BLOWOUT, CLOSEST_GAME, CLOSE_WINS, CLOSE_LOSSES,
        LUCKIEST, UNLUCKIEST, MOST_BENCH_POINTS, WAIVER_WIRE_WARRIOR, JOEL_EMBIID, UNETHICAL
    }

    /** The six kinds FR-006 marks as "mostly noise" while the season window is early. */
    private static final Set<Kind> EARLY_ELIGIBLE = EnumSet.of(
            Kind.LUCKIEST, Kind.UNLUCKIEST, Kind.MOST_BENCH_POINTS,
            Kind.WAIVER_WIRE_WARRIOR, Kind.JOEL_EMBIID, Kind.UNETHICAL);

    public record Holder(int rosterId, Long managerId, String teamName, String avatarId) {}

    public record Coverage(int weeksCovered, int weeksExcluded, List<String> reasons) {}

    /** One row of a superlative's supporting detail. Discriminated on the wire by a "type" field the controller adds. */
    public sealed interface DetailRow
            permits WeekScoreDetail, GameDetail, LuckDetail, BenchTotalDetail, PickupDetail, AbsenceDetail, ConductDetail {}

    public record WeekScoreDetail(int week, int rosterId, double points) implements DetailRow {}

    public record GameDetail(int week, int rosterId, int opponentRosterId, String opponentTeamName,
                             double points, double opponentPoints, double margin) implements DetailRow {}

    /**
     * Copied unmodified from the bounded {@link ExpectedWinsService} row
     * (FR-004) -- never recomputed here. {@code reading} is new for T032 and
     * must be mirrored into {@code web/src/api.ts}'s SuperlativeDetail union
     * (contracts/superlatives-api.md's LUCK row).
     */
    public record LuckDetail(int rosterId, double actualWins, double expectedWins, double winsAboveExpected,
                             List<ExpectedWinsService.SwingWeek> swingWeeks, int fromWeek, int throughWeek,
                             String reading)
            implements DetailRow {}

    public record BiggestBenchWeek(int week, double pointsLeft) {}

    public record BenchTotalDetail(int rosterId, double pointsLeft, int weeksCounted, int fromWeek, int throughWeek,
                                   BiggestBenchWeek biggestWeek) implements DetailRow {}

    public record PickupDetail(String playerId, String playerName, String position, int rosterId, int addedWeek,
                               String addType, List<Integer> startedWeeks, double points) implements DetailRow {}

    /**
     * @param rosterId      the holder this row belongs to (coordinator
     *                      clarification 2026-09-23: required, like every
     *                      other per-holder detail row)
     * @param weeksAffected a COUNT of distinct fantasy weeks containing at
     *                      least one counted missed game -- not a list
     *                      (coordinator clarification 2026-09-23); {@code
     *                      gamesMissed} is the headline and can exceed this in
     *                      basketball, where one week can hold several missed
     *                      games
     */
    public record AbsenceDetail(String playerId, String playerName, String position, int rosterId,
                                int gamesMissed, int weeksAffected, double pointsPerGame,
                                double estimatedPointsLost, boolean estimated) implements DetailRow {}

    public record ConductDetail(String playerId, String playerName, int rosterId, String source,
                                List<Integer> weeks, String reason) implements DetailRow {}

    public record Superlative(Kind kind, boolean available, String reason, boolean early, Double value, String unit,
                              List<Holder> holders, String emptyReason, List<DetailRow> detail, Coverage coverage) {}

    /**
     * @param leagueSleeperId the Sleeper id of the league-season the resolver
     *                        actually resolved to (coordinator follow-up
     *                        2026-09-23, item 9) -- NOT necessarily the id the
     *                        caller passed in, since {@code requestedSeason}
     *                        non-null means the resolver walked back to an
     *                        earlier played season. The frontend's conduct
     *                        list is per-league-row (not resolver-scoped, per
     *                        the contract), so it needs this exact id to show
     *                        the SAME season's commissioner list the award
     *                        itself was computed against.
     */
    public record Result(boolean available, String reason, int season, Integer requestedSeason, Sport sport,
                         Integer throughWeek, int weeksScored, Integer regularSeasonEnd, boolean early,
                         int earlyThresholdWeeks, Double closeGameMargin, List<Integer> suspensionWeeksObserved,
                         boolean commissionerListAvailable, List<Superlative> superlatives, String leagueSleeperId) {}

    // ------------------------------------------------------------- forLeague

    public Optional<Result> forLeague(String sleeperLeagueId) {
        Optional<LeagueSeasonResolver.Resolved> found = seasons.resolve(sleeperLeagueId);
        if (found.isEmpty()) return Optional.empty();
        LeagueRepository.LeagueRow league = found.get().league();
        Integer requestedSeason = found.get().requestedSeason();
        SportRules rules = rulesRegistry.get(league.sport());
        double closeMargin = rules.closeGameMargin();

        Optional<LeagueRepository.PlayoffFormat> format = leagues.playoffFormat(league.id());
        Integer regularSeasonEnd = null;
        Set<Integer> stored = weekPoints.storedWeeks(league.id());
        Set<Integer> scoredWeeks = stored;
        if (format.isPresent() && format.get().playoffWeekStart() >= 2) {
            int pws = format.get().playoffWeekStart();
            regularSeasonEnd = pws - 1;
            scoredWeeks = stored.stream().filter(w -> w < pws)
                    .collect(Collectors.toCollection(TreeSet::new));
        }

        boolean commissionerListAvailable = members.anyCommissioner(league.id());

        if (scoredWeeks.isEmpty()) {
            // No scored week means no window for "within the window" to mean
            // anything -- a capture that exists (e.g. today's Sleeper state week,
            // ahead of any score this league has stored) must not be reported as
            // observed yet (fix below applies the same rule once throughWeek exists).
            return Optional.of(new Result(false, "no week of this season has been scored yet",
                    league.season(), requestedSeason, league.sport(), null, 0, regularSeasonEnd, false,
                    EARLY_THRESHOLD_WEEKS, closeMargin, List.of(), commissionerListAvailable, List.of(),
                    league.sleeperId()));
        }

        int throughWeek = Collections.max(scoredWeeks);
        List<Integer> suspensionWeeksObserved = suspensionWeeksObserved(league, throughWeek);
        int weeksScored = scoredWeeks.size();
        boolean early = weeksScored < EARLY_THRESHOLD_WEEKS;
        WeekBound bound = WeekBound.through(throughWeek);
        final Set<Integer> scoredWeeksFinal = scoredWeeks;

        Map<Integer, String> nameByRoster = new HashMap<>();
        Map<Integer, String> avatarByRoster = new HashMap<>();
        Map<Integer, Long> managerByRoster = new HashMap<>();
        teamMaps(league.id(), nameByRoster, avatarByRoster, managerByRoster);

        // Coordinator follow-up 2026-09-23, item 6: loaded once here rather
        // than once per helper method (bench/waiver/absence/unethical each
        // used to call players.findAll and weekPoints.breakdownsFor
        // independently, and waiver/absence/unethical each re-parsed the same
        // players_points JSON). Behaviour-preserving: each helper below reads
        // exactly the rows it always did, just handed down instead of re-fetched.
        List<RosterWeekPointsRepository.WeekBreakdown> leagueBreakdowns =
                weekPoints.breakdownsFor(league.id(), league.season())
                        .stream().filter(w -> scoredWeeksFinal.contains(w.week())).toList();
        Map<String, Player> playersBySleeperId = playersBySleeperId(players.findAll(league.sport()));
        List<ParsedWeek> parsedWeeks = parseWeeks(leagueBreakdowns);

        List<LeagueMatchupRepository.PairedGame> games =
                matchups.pairedWithScores(List.of(league.id()), bound).stream()
                        .filter(g -> scoredWeeksFinal.contains(g.week()))
                        .toList();
        Set<Integer> pairedWeeks = games.stream().map(LeagueMatchupRepository.PairedGame::week)
                .collect(Collectors.toCollection(TreeSet::new));
        List<Integer> excludedWeeks = scoredWeeksFinal.stream().filter(w -> !pairedWeeks.contains(w)).sorted().toList();
        Coverage pairingCoverage = excludedWeeks.isEmpty() ? null : new Coverage(
                scoredWeeksFinal.size() - excludedWeeks.size(), excludedWeeks.size(),
                excludedWeeks.stream().map(w -> "week " + w + ": no pairings stored").toList());

        Map<Kind, Superlative> built = new EnumMap<>(Kind.class);

        built.put(Kind.HIGHEST_WEEK, weekScoreSuperlative(Kind.HIGHEST_WEEK,
                records.highestWeeks(List.of(league.id()), 20, bound), nameByRoster, avatarByRoster, managerByRoster));
        built.put(Kind.LOWEST_WEEK, weekScoreSuperlative(Kind.LOWEST_WEEK,
                records.lowestWeeks(List.of(league.id()), 20, bound), nameByRoster, avatarByRoster, managerByRoster));
        built.put(Kind.BIGGEST_BLOWOUT, marginSuperlative(Kind.BIGGEST_BLOWOUT,
                records.biggestBlowouts(List.of(league.id()), 20, bound), pairingCoverage,
                nameByRoster, avatarByRoster, managerByRoster));
        built.put(Kind.CLOSEST_GAME, marginSuperlative(Kind.CLOSEST_GAME,
                records.closestMatchups(List.of(league.id()), 20, bound), pairingCoverage,
                nameByRoster, avatarByRoster, managerByRoster));

        Map<Integer, List<GameDetail>> closeWins = closeGames(games, closeMargin, true);
        Map<Integer, List<GameDetail>> closeLosses = closeGames(games, closeMargin, false);
        built.put(Kind.CLOSE_WINS, closeGameSuperlative(Kind.CLOSE_WINS, closeWins, "WINS", pairingCoverage,
                nameByRoster, avatarByRoster, managerByRoster));
        built.put(Kind.CLOSE_LOSSES, closeGameSuperlative(Kind.CLOSE_LOSSES, closeLosses, "GAMES", pairingCoverage,
                nameByRoster, avatarByRoster, managerByRoster));

        addLuckSuperlatives(built, sleeperLeagueId, bound, throughWeek, early);
        built.put(Kind.MOST_BENCH_POINTS, benchSuperlative(league, leagueBreakdowns, playersBySleeperId, throughWeek,
                early, nameByRoster, avatarByRoster, managerByRoster));
        built.put(Kind.WAIVER_WIRE_WARRIOR, waiverSuperlative(league, sleeperLeagueId, parsedWeeks, playersBySleeperId,
                early, nameByRoster, avatarByRoster, managerByRoster));
        built.put(Kind.JOEL_EMBIID, absenceSuperlative(league, sleeperLeagueId, rules, scoredWeeksFinal, parsedWeeks,
                playersBySleeperId, early, nameByRoster, avatarByRoster, managerByRoster));
        built.put(Kind.UNETHICAL, unethicalSuperlative(league, parsedWeeks, playersBySleeperId, early,
                nameByRoster, avatarByRoster, managerByRoster));

        List<Superlative> superlatives = new ArrayList<>();
        for (Kind kind : Kind.values()) {
            superlatives.add(built.getOrDefault(kind, notBuiltYet(kind)));
        }

        return Optional.of(new Result(true, null, league.season(), requestedSeason, league.sport(),
                throughWeek, weeksScored, regularSeasonEnd, early, EARLY_THRESHOLD_WEEKS, closeMargin,
                suspensionWeeksObserved, commissionerListAvailable, superlatives, league.sleeperId()));
    }

    /**
     * FR-019: the weeks that had a {@code status_capture} row, bounded to
     * THIS payload's own scored window (1..{@code throughWeek}) -- NOT
     * {@code regularSeasonEnd}, the playoff cutoff, which can sit far ahead
     * of how many weeks are actually scored.
     *
     * <p><b>Live bug, found in verification (2026-09-23):</b> NFL 2026 was
     * scored through week 2, but ingest had already captured suspension tags
     * for week 3 (a player ingest captures whatever week Sleeper's
     * {@code /state/nfl} reports "now", independent of how many weeks this
     * league has scored) -- {@code regularSeasonEnd} (a much later playoff
     * cutoff) let that unscored week 3 leak into {@code
     * suspensionWeeksObserved}, and the page read "tracking began week 3"
     * while no scored week was tracked. Week 0 (Sleeper's offseason marker,
     * {@code PowerRankingService.sportState}'s own javadoc) is never a real
     * fantasy week and is dropped either way.
     */
    private List<Integer> suspensionWeeksObserved(LeagueRepository.LeagueRow league, int throughWeek) {
        return boundedCapturedWeeks(statusCaptures.capturedWeeks(league.sport(), league.season()), throughWeek);
    }

    /** The filter itself, pure and package-private so a test can drive it without Postgres. */
    static List<Integer> boundedCapturedWeeks(Set<Integer> capturedWeeks, int throughWeek) {
        return capturedWeeks.stream()
                .filter(w -> w >= 1 && w <= throughWeek)
                .sorted()
                .toList();
    }

    // ---------------------------------------------------------------- US1

    /** Package-private so SeasonSuperlativesCloseGamesTest (T020) can call it directly, without Postgres. */
    static Map<Integer, List<GameDetail>> closeGames(List<LeagueMatchupRepository.PairedGame> games,
                                                      double margin, boolean wantWinners) {
        Map<Integer, List<GameDetail>> out = new LinkedHashMap<>();
        for (LeagueMatchupRepository.PairedGame g : games) {
            int cmp = g.aPoints().compareTo(g.bPoints());
            if (cmp == 0) continue; // a tie is neither a close win nor a close loss for either side
            double margin2 = g.aPoints().subtract(g.bPoints()).abs().doubleValue();
            if (!(margin2 < margin)) continue; // strict "under", per FR-011

            boolean aWon = cmp > 0;
            int winnerRoster = aWon ? g.aRosterId() : g.bRosterId();
            int loserRoster = aWon ? g.bRosterId() : g.aRosterId();
            double winnerPts = (aWon ? g.aPoints() : g.bPoints()).doubleValue();
            double loserPts = (aWon ? g.bPoints() : g.aPoints()).doubleValue();

            int rosterId = wantWinners ? winnerRoster : loserRoster;
            int opponentId = wantWinners ? loserRoster : winnerRoster;
            double myPoints = wantWinners ? winnerPts : loserPts;
            double oppPoints = wantWinners ? loserPts : winnerPts;

            // opponentTeamName is filled in by the caller, which has the name
            // map this pure function deliberately does not depend on.
            out.computeIfAbsent(rosterId, k -> new ArrayList<>())
                    .add(new GameDetail(g.week(), rosterId, opponentId, null, myPoints, oppPoints, margin2));
        }
        return out;
    }

    private Superlative closeGameSuperlative(Kind kind, Map<Integer, List<GameDetail>> byRoster, String unit,
                                             Coverage coverage, Map<Integer, String> nameByRoster,
                                             Map<Integer, String> avatarByRoster, Map<Integer, Long> managerByRoster) {
        if (byRoster.isEmpty()) {
            return new Superlative(kind, true, null, false, null, unit, List.of(), "no close games yet",
                    List.of(), coverage);
        }
        int max = byRoster.values().stream().mapToInt(List::size).max().orElse(0);
        List<Integer> topRosters = byRoster.entrySet().stream()
                .filter(e -> e.getValue().size() == max)
                .map(Map.Entry::getKey)
                .sorted()
                .toList();
        List<Holder> holders = topRosters.stream()
                .map(id -> holder(id, nameByRoster, avatarByRoster, managerByRoster))
                .toList();
        List<DetailRow> detail = new ArrayList<>();
        for (int id : topRosters) {
            for (GameDetail gd : byRoster.get(id)) {
                detail.add(withOpponentName(gd, nameByRoster));
            }
        }
        return new Superlative(kind, true, null, false, (double) max, unit, holders, null, detail, coverage);
    }

    private Superlative weekScoreSuperlative(Kind kind, List<LeagueRecordService.WeeklyScoreRecord> rows,
                                             Map<Integer, String> nameByRoster, Map<Integer, String> avatarByRoster,
                                             Map<Integer, Long> managerByRoster) {
        if (rows.isEmpty()) {
            return new Superlative(kind, true, null, false, null, "POINTS", List.of(),
                    "no scored weeks yet", List.of(), null);
        }
        BigDecimal top = rows.get(0).points();
        List<LeagueRecordService.WeeklyScoreRecord> tied = rows.stream()
                .filter(r -> r.points().compareTo(top) == 0)
                .toList();
        List<Integer> rosterIds = tied.stream().map(LeagueRecordService.WeeklyScoreRecord::rosterId)
                .distinct().sorted().toList();
        List<Holder> holders = rosterIds.stream()
                .map(id -> holder(id, nameByRoster, avatarByRoster, managerByRoster))
                .toList();
        List<DetailRow> detail = tied.stream()
                .<DetailRow>map(r -> new WeekScoreDetail(r.week(), r.rosterId(), r.points().doubleValue()))
                .toList();
        return new Superlative(kind, true, null, false, top.doubleValue(), "POINTS", holders, null, detail, null);
    }

    private Superlative marginSuperlative(Kind kind, List<LeagueRecordService.MarginRecord> rows, Coverage coverage,
                                          Map<Integer, String> nameByRoster, Map<Integer, String> avatarByRoster,
                                          Map<Integer, Long> managerByRoster) {
        if (rows.isEmpty()) {
            return new Superlative(kind, true, null, false, null, "POINTS", List.of(),
                    "no head-to-head pairings stored for this league yet", List.of(), coverage);
        }
        BigDecimal extreme = rows.get(0).margin();
        List<LeagueRecordService.MarginRecord> tied = rows.stream()
                .filter(r -> r.margin().compareTo(extreme) == 0)
                .toList();
        List<Integer> rosterIds = tied.stream().map(r -> r.winner().rosterId()).distinct().sorted().toList();
        List<Holder> holders = rosterIds.stream()
                .map(id -> holder(id, nameByRoster, avatarByRoster, managerByRoster))
                .toList();
        List<DetailRow> detail = tied.stream()
                .<DetailRow>map(r -> new GameDetail(r.week(), r.winner().rosterId(), r.loser().rosterId(),
                        nameByRoster.getOrDefault(r.loser().rosterId(), "Roster " + r.loser().rosterId()),
                        r.winner().points().doubleValue(), r.loser().points().doubleValue(), r.margin().doubleValue()))
                .toList();
        return new Superlative(kind, true, null, false, extreme.doubleValue(), "POINTS", holders, null, detail, coverage);
    }

    private static GameDetail withOpponentName(GameDetail gd, Map<Integer, String> nameByRoster) {
        return new GameDetail(gd.week(), gd.rosterId(), gd.opponentRosterId(),
                nameByRoster.getOrDefault(gd.opponentRosterId(), "Roster " + gd.opponentRosterId()),
                gd.points(), gd.opponentPoints(), gd.margin());
    }

    // ---------------------------------------------------------------- US2

    /**
     * LUCKIEST/UNLUCKIEST (T032): read straight off the bounded {@link
     * ExpectedWinsService} row -- the SAME bound as this payload's own window
     * -- and copy {@code actualWins}/{@code expectedWins}/{@code winsAboveExpected}/
     * {@code swingWeeks} unmodified (FR-004). Never recomputed here.
     */
    private void addLuckSuperlatives(Map<Kind, Superlative> built, String sleeperLeagueId, WeekBound bound,
                                     int throughWeek, boolean early) {
        Optional<ExpectedWinsService.Result> exp = expectedWins.forLeague(sleeperLeagueId, bound);
        if (exp.isEmpty() || !exp.get().available() || exp.get().teams().isEmpty()) {
            String reason = exp.isPresent() && exp.get().reason() != null
                    ? exp.get().reason() : "no completed games for this league yet";
            built.put(Kind.LUCKIEST, unavailable(Kind.LUCKIEST, reason));
            built.put(Kind.UNLUCKIEST, unavailable(Kind.UNLUCKIEST, reason));
            return;
        }
        List<ExpectedWinsService.TeamRow> teams = exp.get().teams();
        built.put(Kind.LUCKIEST, luckSuperlative(Kind.LUCKIEST, teams, true, early, throughWeek));
        built.put(Kind.UNLUCKIEST, luckSuperlative(Kind.UNLUCKIEST, teams, false, early, throughWeek));
    }

    /**
     * T031(a): picks the max ({@code wantMax}) or min winsAboveExpected --
     * LUCKIEST and UNLUCKIEST are never swapped because the caller states
     * which one it wants rather than this method guessing from the kind.
     * Package-private so SeasonSuperlativesLuckTest can call it directly,
     * without Postgres.
     */
    static Superlative luckSuperlative(Kind kind, List<ExpectedWinsService.TeamRow> teams, boolean wantMax,
                                       boolean early, int throughWeek) {
        double extreme = wantMax
                ? teams.stream().mapToDouble(ExpectedWinsService.TeamRow::winsAboveExpected).max().orElseThrow()
                : teams.stream().mapToDouble(ExpectedWinsService.TeamRow::winsAboveExpected).min().orElseThrow();
        List<ExpectedWinsService.TeamRow> tied = teams.stream()
                .filter(t -> Double.compare(t.winsAboveExpected(), extreme) == 0)
                .sorted(Comparator.comparingInt(ExpectedWinsService.TeamRow::rosterId))
                .toList();
        List<Holder> holders = tied.stream()
                .map(t -> new Holder(t.rosterId(), t.managerId(), t.teamName(), t.avatarId()))
                .toList();
        List<DetailRow> detail = tied.stream()
                .<DetailRow>map(t -> new LuckDetail(t.rosterId(), t.actualWins(), t.expectedWins(),
                        t.winsAboveExpected(), t.swingWeeks(), 1, throughWeek, luckReading(t.winsAboveExpected())))
                .toList();
        return new Superlative(kind, true, null, early, extreme, "WINS", holders, null, detail, null);
    }

    /** T032: "2.40 more wins than their scores earned" / "1.30 fewer wins than their scores earned". */
    static String luckReading(double winsAboveExpected) {
        String word = winsAboveExpected >= 0 ? "more" : "fewer";
        return String.format(Locale.ROOT, "%.2f %s wins than their scores earned", Math.abs(winsAboveExpected), word);
    }

    /** One roster-week's optimal-vs-started gap, or its explicit absence. Package-private for T031(c)/(d). */
    record BenchWeekEntry(int week, int rosterId, boolean valid, double pointsLeft) {}

    /** One roster's summed bench cost. Package-private for T031(c)/(d). */
    record BenchAgg(double pointsLeft, int weeksCounted, BiggestBenchWeek biggestWeek) {}

    /**
     * The pure aggregation core (T033): sums each roster's (optimal - started)
     * gap over its valid weeks only (FR-007 -- an invalid week is excluded,
     * never added as zero), and tracks the single worst week. Package-private
     * so SeasonSuperlativesLuckTest (T031 c/d) can call it directly, without
     * Postgres or a real {@code RealizedLineupService}.
     */
    static Map<Integer, BenchAgg> aggregateBench(List<BenchWeekEntry> entries) {
        Map<Integer, Double> pointsLeftByRoster = new HashMap<>();
        Map<Integer, Integer> weeksCountedByRoster = new HashMap<>();
        Map<Integer, BiggestBenchWeek> biggestByRoster = new HashMap<>();
        for (BenchWeekEntry e : entries) {
            if (!e.valid()) continue;
            pointsLeftByRoster.merge(e.rosterId(), e.pointsLeft(), Double::sum);
            weeksCountedByRoster.merge(e.rosterId(), 1, Integer::sum);
            BiggestBenchWeek current = biggestByRoster.get(e.rosterId());
            if (current == null || e.pointsLeft() > current.pointsLeft()) {
                biggestByRoster.put(e.rosterId(), new BiggestBenchWeek(e.week(), round2(e.pointsLeft())));
            }
        }
        Map<Integer, BenchAgg> out = new HashMap<>();
        for (Integer rosterId : pointsLeftByRoster.keySet()) {
            out.put(rosterId, new BenchAgg(round2(pointsLeftByRoster.get(rosterId)),
                    weeksCountedByRoster.get(rosterId), biggestByRoster.get(rosterId)));
        }
        return out;
    }

    /**
     * MOST_BENCH_POINTS (T033): the same optimal-lineup call
     * {@code WeeklyReportService} makes per roster-week
     * ({@code RealizedLineupService.bestLineup}), summed over the regular
     * season via {@link #aggregateBench}. A week a roster's breakdown can't
     * seat (FR-007 -- no per-player points, or none this app can resolve) is
     * excluded for that roster and reported in coverage, never added as zero.
     */
    private Superlative benchSuperlative(LeagueRepository.LeagueRow league,
                                         List<RosterWeekPointsRepository.WeekBreakdown> breakdowns,
                                         Map<String, Player> playersBySleeperId, int throughWeek, boolean early,
                                         Map<Integer, String> nameByRoster, Map<Integer, String> avatarByRoster,
                                         Map<Integer, Long> managerByRoster) {
        LeagueSettings settings = LeagueRepository.toSettings(league, league.rosterPositions().size());
        SportRules rules = rulesRegistry.get(league.sport());

        List<BenchWeekEntry> entries = new ArrayList<>();
        Set<Integer> coveredWeeks = new TreeSet<>();
        Set<Integer> excludedWeeks = new TreeSet<>();
        for (RosterWeekPointsRepository.WeekBreakdown w : breakdowns) {
            RealizedLineupService.WeekLineup best =
                    realizedLineups.bestLineup(w.playersPointsJson(), playersBySleeperId, settings, rules);
            if (!best.valid()) {
                excludedWeeks.add(w.week());
                entries.add(new BenchWeekEntry(w.week(), w.rosterId(), false, 0));
                continue;
            }
            coveredWeeks.add(w.week());
            entries.add(new BenchWeekEntry(w.week(), w.rosterId(), true, best.points() - w.startersPoints()));
        }

        Map<Integer, BenchAgg> byRoster = aggregateBench(entries);
        Coverage coverage = excludedWeeks.isEmpty() ? null : new Coverage(
                coveredWeeks.size(), excludedWeeks.size(),
                excludedWeeks.stream().map(w -> "week " + w + ": no usable lineup breakdown").toList());

        if (byRoster.isEmpty()) {
            return new Superlative(Kind.MOST_BENCH_POINTS, true, null, early, null, "POINTS", List.of(),
                    "no usable lineup breakdowns yet", List.of(), coverage);
        }

        double max = byRoster.values().stream().mapToDouble(BenchAgg::pointsLeft).max().orElseThrow();
        List<Integer> topRosters = byRoster.entrySet().stream()
                .filter(e -> Double.compare(e.getValue().pointsLeft(), max) == 0)
                .map(Map.Entry::getKey)
                .sorted()
                .toList();
        List<Holder> holders = topRosters.stream()
                .map(id -> holder(id, nameByRoster, avatarByRoster, managerByRoster))
                .toList();
        List<DetailRow> detail = topRosters.stream()
                .<DetailRow>map(id -> new BenchTotalDetail(id, byRoster.get(id).pointsLeft(),
                        byRoster.get(id).weeksCounted(), 1, throughWeek, byRoster.get(id).biggestWeek()))
                .toList();
        return new Superlative(Kind.MOST_BENCH_POINTS, true, null, early, round2(max), "POINTS", holders, null,
                detail, coverage);
    }

    // ---------------------------------------------------------------- US3

    /**
     * WAIVER_WIRE_WARRIOR (T039), per research R8's rule: starting-lineup
     * points from players whose most recent arrival on that roster was a
     * completed WAIVER or FREE_AGENT add. The rule itself is {@link
     * WaiverPickupAttribution#attribute} -- pure and tested without Postgres
     * (T037/T038); this method's own job is just gathering that pure
     * function's inputs from this league-season's stored rows and shaping
     * its output into the wire's PICKUP detail rows.
     */
    private Superlative waiverSuperlative(LeagueRepository.LeagueRow league, String sleeperLeagueId,
                                          List<ParsedWeek> parsedWeeks, Map<String, Player> playersBySleeperId,
                                          boolean early, Map<Integer, String> nameByRoster,
                                          Map<Integer, String> avatarByRoster, Map<Integer, Long> managerByRoster) {
        List<LeagueTransactionRepository.Row> txRows = transactions.forSeason(league.id(), league.season());
        if (txRows.isEmpty()) {
            return unavailable(Kind.WAIVER_WIRE_WARRIOR,
                    "no transactions stored for this season — run POST /api/ingest/transactions/" + sleeperLeagueId);
        }

        // Coverage: a week with no stored starters is excluded outright, never
        // read as "everyone rostered started" (US3 scenario 4, AGENTS.md hard
        // rule against a fallback that looks more confident than the data is).
        List<WaiverPickupAttribution.StarterWeek> starterWeeks = new ArrayList<>();
        Set<Integer> coveredWeeks = new TreeSet<>();
        Set<Integer> excludedWeeks = new TreeSet<>();
        for (ParsedWeek w : parsedWeeks) {
            if (w.starters().isEmpty()) {
                excludedWeeks.add(w.week());
                continue;
            }
            coveredWeeks.add(w.week());
            starterWeeks.add(new WaiverPickupAttribution.StarterWeek(w.week(), w.rosterId(), w.starters(), w.playersPoints()));
        }

        Coverage coverage = excludedWeeks.isEmpty() ? null : new Coverage(
                coveredWeeks.size(), excludedWeeks.size(),
                excludedWeeks.stream().map(w -> "week " + w + ": no stored starters").toList());

        Map<Integer, WaiverPickupAttribution.RosterTotal> byRoster =
                WaiverPickupAttribution.attribute(txRows, starterWeeks);

        if (byRoster.isEmpty()) {
            return new Superlative(Kind.WAIVER_WIRE_WARRIOR, true, null, early, null, "POINTS", List.of(),
                    "no starting-lineup points credited to a waiver or free-agent pickup yet", List.of(), coverage);
        }

        double max = byRoster.values().stream()
                .mapToDouble(WaiverPickupAttribution.RosterTotal::totalPoints).max().orElseThrow();
        List<Integer> topRosters = byRoster.entrySet().stream()
                .filter(e -> Double.compare(e.getValue().totalPoints(), max) == 0)
                .map(Map.Entry::getKey)
                .sorted()
                .toList();
        List<Holder> holders = topRosters.stream()
                .map(id -> holder(id, nameByRoster, avatarByRoster, managerByRoster))
                .toList();

        List<DetailRow> detail = pickupDetail(topRosters, byRoster, playersBySleeperId);

        return new Superlative(Kind.WAIVER_WIRE_WARRIOR, true, null, early, round2(max), "POINTS", holders, null,
                detail, coverage);
    }

    /**
     * Top-3-per-holder PICKUP rows. Each row carries the rosterId of the
     * holder it was built for -- not derived from the player, since a tie
     * between two holders means the SAME player could in principle appear
     * once per holder's own detail list, each tagged with its own roster
     * (coordinator follow-up after the live check: every other detail type
     * already carries rosterId, PICKUP was the one that didn't). Package-
     * private so SeasonSuperlativesWaiverTest can call it directly, without
     * Postgres.
     */
    static List<DetailRow> pickupDetail(List<Integer> topRosters,
                                        Map<Integer, WaiverPickupAttribution.RosterTotal> byRoster,
                                        Map<String, Player> playersBySleeperId) {
        List<DetailRow> detail = new ArrayList<>();
        for (int id : topRosters) {
            for (WaiverPickupAttribution.PlayerContribution pc : byRoster.get(id).contributions().stream().limit(3).toList()) {
                Player p = playersBySleeperId.get(pc.playerId());
                detail.add(new PickupDetail(pc.playerId(), p == null ? "Unknown player" : p.name(),
                        p == null ? null : p.primary().name(), id, pc.addedWeek(), pc.addType(), pc.startedWeeks(),
                        pc.points()));
            }
        }
        return detail;
    }

    // ---------------------------------------------------------------- US4

    /**
     * JOEL_EMBIID (T048), per research R10 (amended 2026-09-23): every missed
     * game of a regular contributor, costed at his mean points per game
     * played. The rule itself is {@link AbsenceCost#compute} -- pure and
     * tested without Postgres (T046/T047); this method's own job is gathering
     * that pure function's inputs from this league-season's stored rows.
     *
     * <p><b>Never reads {@code Player.injuryStatus}</b> (FR-013): membership,
     * regularity and cost all come from stored {@code players_points},
     * {@code starters} and {@code player_absence}/{@code player_game} rows.
     */
    private Superlative absenceSuperlative(LeagueRepository.LeagueRow league, String sleeperLeagueId,
                                           SportRules rules, Set<Integer> scoredWeeksFinal,
                                           List<ParsedWeek> parsedWeeks, Map<String, Player> playersBySleeperId,
                                           boolean early, Map<Integer, String> nameByRoster,
                                           Map<Integer, String> avatarByRoster, Map<Integer, Long> managerByRoster) {
        Sport sport = league.sport();

        // Membership per (roster, player): rosteredWeeks are the weeks he is a
        // key in that roster's players_points; startedWeeks are the subset of
        // those he actually started (research R11/T019: players_points keys are
        // this feature's membership rule, IR slots included).
        Map<Integer, Map<String, Set<Integer>>> rosteredByRosterPlayer = new HashMap<>();
        Map<Integer, Map<String, Set<Integer>>> startedByRosterPlayer = new HashMap<>();
        Set<String> allPlayerIds = new HashSet<>();
        for (ParsedWeek w : parsedWeeks) {
            for (String pid : w.playersPoints().keySet()) {
                allPlayerIds.add(pid);
                rosteredByRosterPlayer.computeIfAbsent(w.rosterId(), k -> new HashMap<>())
                        .computeIfAbsent(pid, k -> new TreeSet<>()).add(w.week());
                if (w.starters().contains(pid)) {
                    startedByRosterPlayer.computeIfAbsent(w.rosterId(), k -> new HashMap<>())
                            .computeIfAbsent(pid, k -> new TreeSet<>()).add(w.week());
                }
            }
        }

        List<AbsenceCost.RosterMembership> memberships = new ArrayList<>();
        for (var rosterEntry : rosteredByRosterPlayer.entrySet()) {
            int rosterId = rosterEntry.getKey();
            Map<String, Set<Integer>> startedForRoster = startedByRosterPlayer.getOrDefault(rosterId, Map.of());
            for (var playerEntry : rosterEntry.getValue().entrySet()) {
                memberships.add(new AbsenceCost.RosterMembership(rosterId, playerEntry.getKey(),
                        playerEntry.getValue(), startedForRoster.getOrDefault(playerEntry.getKey(), Set.of())));
            }
        }

        // Coordinator follow-up 2026-09-23, item 3: availability and coverage
        // are PER LEAGUE, not sport-season-wide. The per-game backfill
        // (POST /api/ingest/player-games/{id}) walks one league's rostered
        // players at a time, so a second same-sport-season league (this DB
        // genuinely has two NFL 2025 leagues) getting ingested does not make
        // this league's players "walked" -- games.playersWithGames(sport,
        // season) used to gate on the SPORT-SEASON union, silently hiding a
        // partial award for whichever league wasn't the one last backfilled.
        // "Walked" now means: this league's own rostered player has at least
        // one player_game OR player_absence row for the season (whichever
        // basis) -- a player nobody ever asked Sleeper about has neither.
        List<PlayerGameRepository.Row> gameRows = games.forPlayers(sport, league.season(), allPlayerIds);
        List<PlayerAbsenceRepository.Row> allAbsenceRows = absences.forPlayers(sport, league.season(), allPlayerIds);
        Set<String> neverWalked = neverWalked(allPlayerIds,
                gameRows.stream().map(PlayerGameRepository.Row::sleeperPlayerId).collect(Collectors.toSet()),
                allAbsenceRows.stream().map(PlayerAbsenceRepository.Row::playerId).collect(Collectors.toSet()));

        if (!allPlayerIds.isEmpty() && neverWalked.size() == allPlayerIds.size()) {
            return unavailable(Kind.JOEL_EMBIID,
                    "per-game records not ingested — run POST /api/ingest/player-games/" + sleeperLeagueId);
        }

        // Coordinator follow-up 2026-09-23, item 4: bounded to THIS payload's
        // regular-season window -- player_game rows are shared across leagues
        // and seasons' worth of playoff/consolation weeks would otherwise
        // silently drag the mean up or down. Item 1: playedWeeksByPlayer (the
        // same filtered rows) replaces the season-wide playedAtLeastOneGame
        // set, so AbsenceCost.isRegularContributor's denominator is only the
        // weeks he actually had a chance to play, not every rostered week.
        Map<String, Double> scoring = leagues.scoringOf(league.id());
        BoundedPlayerGames bounded = boundedPlayerGames(gameRows, scoredWeeksFinal, scoring, gameScoring);
        Map<String, Double> pointsPerGame = bounded.pointsPerGame();
        Map<String, Set<Integer>> playedWeeksByPlayer = bounded.playedWeeksByPlayer();

        // UNCLASSIFIED rows (V23, coordinator follow-up 2026-09-23) never cost
        // a roster anything, but they still get reported -- persisted rather
        // than only counted at ingest time, so this coverage note is backed by
        // a real number instead of a standing, unbacked caveat.
        List<AbsenceCost.Absence> absenceRows = allAbsenceRows.stream()
                .filter(r -> scoredWeeksFinal.contains(r.week()) && !"UNCLASSIFIED".equals(r.basis()))
                .map(r -> new AbsenceCost.Absence(r.playerId(), r.week()))
                .toList();

        Map<Integer, AbsenceCost.RosterCost> byRoster =
                AbsenceCost.compute(memberships, playedWeeksByPlayer, pointsPerGame, absenceRows);

        // Per-player unclassified weeks (regular season only), to attribute to
        // whichever roster(s) actually held that player and rely on him as a
        // regular contributor.
        Map<String, Set<Integer>> unclassifiedWeeksByPlayer = new HashMap<>();
        for (PlayerAbsenceRepository.Row r : allAbsenceRows) {
            if (!"UNCLASSIFIED".equals(r.basis()) || !scoredWeeksFinal.contains(r.week())) continue;
            unclassifiedWeeksByPlayer.computeIfAbsent(r.playerId(), k -> new TreeSet<>()).add(r.week());
        }
        Map<Integer, Set<Integer>> unclassifiedWeeksByRoster = new HashMap<>();
        for (AbsenceCost.RosterMembership m : memberships) {
            if (!AbsenceCost.isRegularContributor(m, playedWeeksByPlayer)) continue;
            Set<Integer> weeks = unclassifiedWeeksByPlayer.get(m.playerId());
            if (weeks == null) continue;
            for (Integer wk : weeks) {
                if (m.rosteredWeeks().contains(wk)) {
                    unclassifiedWeeksByRoster.computeIfAbsent(m.rosterId(), k -> new TreeSet<>()).add(wk);
                }
            }
        }

        // Item 3's "some, not all" case: a coverage reason naming how many of
        // this league's rostered players were never walked at all, distinct
        // from (and reported alongside) the per-holder unclassified-week notes.
        List<String> leagueLevelReasons = neverWalked.isEmpty() ? List.of() : List.of(
                neverWalked.size() + " rostered player" + (neverWalked.size() == 1 ? "" : "s")
                        + " have no per-game records — run POST /api/ingest/player-games/" + sleeperLeagueId);

        if (byRoster.isEmpty()) {
            Coverage emptyCoverage = leagueLevelReasons.isEmpty() ? null
                    : new Coverage(scoredWeeksFinal.size(), 0, leagueLevelReasons);
            return new Superlative(Kind.JOEL_EMBIID, true, null, early, null, "POINTS", List.of(),
                    "nobody's been bitten yet", List.of(), emptyCoverage);
        }

        double max = byRoster.values().stream().mapToDouble(AbsenceCost.RosterCost::totalPointsLost).max().orElseThrow();
        List<Integer> topRosters = byRoster.entrySet().stream()
                .filter(e -> Double.compare(e.getValue().totalPointsLost(), max) == 0)
                .map(Map.Entry::getKey)
                .sorted()
                .toList();
        List<Holder> holders = topRosters.stream()
                .map(id -> holder(id, nameByRoster, avatarByRoster, managerByRoster))
                .toList();

        Coverage coverage = mergeCoverage(leagueLevelReasons,
                unclassifiedCoverage(topRosters, unclassifiedWeeksByRoster, scoredWeeksFinal.size()),
                scoredWeeksFinal.size());

        List<DetailRow> detail = new ArrayList<>();
        for (int id : topRosters) {
            for (AbsenceCost.PlayerCost pc : byRoster.get(id).players().stream().limit(3).toList()) {
                Player p = playersBySleeperId.get(pc.playerId());
                detail.add(new AbsenceDetail(pc.playerId(), p == null ? "Unknown player" : p.name(),
                        p == null ? null : p.primary().name(), id, pc.gamesMissed(), pc.weeksAffected(),
                        pc.pointsPerGame(), pc.estimatedPointsLost(), true));
            }
        }

        return new Superlative(Kind.JOEL_EMBIID, true, null, early, round2(max), "POINTS", holders, null,
                detail, coverage);
    }

    /**
     * Per holder, only when it actually happened (a count of 0 is not a
     * coverage gap): "N weeks couldn't be classified as a bye or a missed
     * game" for whichever holder(s) have an unclassified week among their own
     * regular contributors' rostered weeks (V23, coordinator follow-up
     * 2026-09-23) -- a caveat backed by a real, persisted count rather than a
     * standing, unbacked note. {@code null} when nobody has one. Package-
     * private so a test can drive it directly, without Postgres.
     */
    static Coverage unclassifiedCoverage(List<Integer> topRosters, Map<Integer, Set<Integer>> unclassifiedWeeksByRoster,
                                         int weeksScored) {
        List<String> reasons = topRosters.stream()
                .map(id -> Map.entry(id, unclassifiedWeeksByRoster.getOrDefault(id, Set.of()).size()))
                .filter(e -> e.getValue() > 0)
                .map(e -> "roster " + e.getKey() + ": " + e.getValue()
                        + (e.getValue() == 1 ? " week" : " weeks")
                        + " couldn't be classified as a bye or a missed game")
                .toList();
        return reasons.isEmpty() ? null : new Coverage(weeksScored, 0, reasons);
    }

    /**
     * Combines the league-level "N players never walked" reason (item 3) with
     * the per-holder unclassified-week reasons (already-built {@link Coverage}
     * or {@code null}) into one {@link Coverage}, since a {@code Superlative}
     * carries exactly one. {@code null} only when both sources are empty.
     * Package-private so a test can drive it directly, without Postgres.
     */
    static Coverage mergeCoverage(List<String> leagueLevelReasons, Coverage unclassified, int weeksScored) {
        if (leagueLevelReasons.isEmpty() && unclassified == null) return null;
        List<String> all = new ArrayList<>(leagueLevelReasons);
        if (unclassified != null) all.addAll(unclassified.reasons());
        return new Coverage(weeksScored, 0, all);
    }

    /**
     * Item 3 (coordinator follow-up 2026-09-23): which of THIS league's
     * rostered players have never been walked by the per-game backfill at
     * all -- no {@code player_game} row and no {@code player_absence} row for
     * the season, either basis. Deliberately not gated on {@code
     * games.playersWithGames(sport, season)} (sport-season-wide, shared with
     * every other league of the same sport and season): a second same-sport
     * -season league that HAS been backfilled would make that set non-empty
     * and silently hide the fact that THIS league was never walked. Package-
     * private and pure so a test can drive it directly, without Postgres.
     */
    static Set<String> neverWalked(Set<String> rosteredPlayerIds, Set<String> gamePlayerIds,
                                   Set<String> absencePlayerIds) {
        Set<String> out = new TreeSet<>();
        for (String pid : rosteredPlayerIds) {
            if (!gamePlayerIds.contains(pid) && !absencePlayerIds.contains(pid)) out.add(pid);
        }
        return out;
    }

    /**
     * Items 1 and 4 (coordinator follow-up 2026-09-23), computed together
     * since both come from the same regular-season-bounded pass over {@code
     * player_game} rows: each player's mean points per game played (bounded
     * to {@code scoredWeeks} -- unbounded, playoff and off-window weeks would
     * silently drag the mean up or down) and the distinct weeks he actually
     * played, which feeds {@link AbsenceCost#isRegularContributor}'s fixed
     * denominator (only weeks he had a chance to play, not every rostered
     * week). Package-private and pure so a test can drive it directly,
     * without Postgres.
     */
    record BoundedPlayerGames(Map<String, Double> pointsPerGame, Map<String, Set<Integer>> playedWeeksByPlayer) {}

    static BoundedPlayerGames boundedPlayerGames(List<PlayerGameRepository.Row> gameRows, Set<Integer> scoredWeeks,
                                                 Map<String, Double> scoring, GameScoringService gameScoring) {
        Map<String, List<Double>> scoresByPlayer = new HashMap<>();
        Map<String, Set<Integer>> playedWeeksByPlayer = new HashMap<>();
        for (PlayerGameRepository.Row row : gameRows) {
            if (!scoredWeeks.contains(row.week())) continue;
            double pts = gameScoring.score(scoring, JsonUtil.readMap(row.statsJson()));
            scoresByPlayer.computeIfAbsent(row.sleeperPlayerId(), k -> new ArrayList<>()).add(pts);
            playedWeeksByPlayer.computeIfAbsent(row.sleeperPlayerId(), k -> new TreeSet<>()).add(row.week());
        }
        Map<String, Double> pointsPerGame = new HashMap<>();
        scoresByPlayer.forEach((pid, scores) -> pointsPerGame.put(pid,
                round2(scores.stream().mapToDouble(Double::doubleValue).average().orElse(0.0))));
        return new BoundedPlayerGames(pointsPerGame, playedWeeksByPlayer);
    }

    // ---------------------------------------------------------------- US6

    /**
     * One qualifying stretch of weeks for one (roster, player, source)
     * (T057/T058). A player can generate up to two rows for the same roster
     * -- one SUSPENDED, one COMMISSIONER -- when both apply, since each
     * source's own qualifying weeks can differ and every detail row must
     * carry exactly one {@code source} (T057 e).
     */
    record ConductQualifyingWeeks(int rosterId, String playerId, String source, List<Integer> weeks, String reason) {}

    /**
     * UNETHICAL (T058): a player-week qualifies for roster {@code T} when he
     * is a key in {@code T}'s {@code players_points} that week (research
     * R11/T019's membership rule, same as JOEL_EMBIID) and either he was
     * captured suspended that week, or a commissioner conduct entry for this
     * league applies from that week on. Package-private and pure -- no
     * Postgres, no DB rows -- so {@code UnethicalAwardTest} (T057) can drive
     * it directly.
     */
    static Map<Integer, List<ConductQualifyingWeeks>> computeUnethical(
            Map<Integer, Map<String, Set<Integer>>> rosteredByRosterPlayer,
            Map<Integer, Set<String>> suspendedByWeek,
            List<LeagueConductRepository.Entry> conductEntries) {
        Map<Integer, List<ConductQualifyingWeeks>> out = new LinkedHashMap<>();
        for (Map.Entry<Integer, Map<String, Set<Integer>>> rosterEntry : rosteredByRosterPlayer.entrySet()) {
            int rosterId = rosterEntry.getKey();
            for (Map.Entry<String, Set<Integer>> playerEntry : rosterEntry.getValue().entrySet()) {
                String playerId = playerEntry.getKey();
                Set<Integer> rosteredWeeks = playerEntry.getValue();

                List<Integer> suspendedWeeks = rosteredWeeks.stream()
                        .filter(w -> suspendedByWeek.getOrDefault(w, Set.of()).contains(playerId))
                        .sorted()
                        .toList();
                if (!suspendedWeeks.isEmpty()) {
                    out.computeIfAbsent(rosterId, k -> new ArrayList<>())
                            .add(new ConductQualifyingWeeks(rosterId, playerId, "SUSPENDED", suspendedWeeks, null));
                }

                for (LeagueConductRepository.Entry ce : conductEntries) {
                    if (!ce.playerId().equals(playerId)) continue;
                    List<Integer> weeks = rosteredWeeks.stream()
                            .filter(w -> w >= ce.appliesFromWeek())
                            .sorted()
                            .toList();
                    if (!weeks.isEmpty()) {
                        out.computeIfAbsent(rosterId, k -> new ArrayList<>())
                                .add(new ConductQualifyingWeeks(rosterId, playerId, "COMMISSIONER", weeks, ce.reason()));
                    }
                }
            }
        }
        return out;
    }

    /**
     * (T058, amended 2026-09-23 after live verification): names WHICH source
     * came up empty rather than always blaming "no suspension captured" --
     * live, a capture already existed (just not one that caught a rostered
     * player), and the old fixed string was simply wrong in that case. The
     * commissioner clause is appended only when that list is actually empty,
     * never when it merely failed to produce a qualifying week (an entry
     * whose {@code appliesFromWeek} hasn't been reached yet is a real,
     * non-empty list that just doesn't qualify anyone today). Package-private
     * and pure so a test can drive all four combinations without Postgres.
     */
    static String unethicalEmptyReason(boolean anyCapture, boolean conductListEmpty) {
        String base = anyCapture
                ? "no rostered player has been suspended in a tracked week"
                : "suspension tracking hasn't covered a scored week yet";
        return conductListEmpty ? base + ", and the commissioner's list is empty" : base;
    }

    /**
     * Gathers this league-season's stored rows for {@link #computeUnethical}
     * and shapes its output into CONDUCT detail rows. Always {@code
     * available: true} (contract): with nothing captured and an empty
     * conduct list it is {@code holders: []} with an {@code emptyReason}
     * naming both sources.
     *
     * <p>Unit is {@code GAMES} (T058): the value counted is total qualifying
     * player-weeks per roster, not points or wins. {@code GAMES} is the
     * closest existing wire unit already used for a plain count
     * ({@code CLOSE_LOSSES}); a new literal isn't added here because
     * {@code web/src/api.ts}'s {@code Superlative.unit} type is a fixed
     * {@code 'POINTS' | 'WINS' | 'GAMES'} union the frontend already ships
     * with, and widening it is outside this backend task.
     */
    private Superlative unethicalSuperlative(LeagueRepository.LeagueRow league, List<ParsedWeek> parsedWeeks,
                                             Map<String, Player> playersBySleeperId, boolean early,
                                             Map<Integer, String> nameByRoster, Map<Integer, String> avatarByRoster,
                                             Map<Integer, Long> managerByRoster) {
        Sport sport = league.sport();

        Map<Integer, Map<String, Set<Integer>>> rosteredByRosterPlayer = new HashMap<>();
        for (ParsedWeek w : parsedWeeks) {
            for (String pid : w.playersPoints().keySet()) {
                rosteredByRosterPlayer.computeIfAbsent(w.rosterId(), k -> new HashMap<>())
                        .computeIfAbsent(pid, k -> new TreeSet<>()).add(w.week());
            }
        }

        Map<Integer, Set<String>> suspendedByWeek = statusCaptures.suspendedIn(sport, league.season());
        List<LeagueConductRepository.Entry> conductEntries = conduct.forLeague(league.id());

        Map<Integer, List<ConductQualifyingWeeks>> byRoster =
                computeUnethical(rosteredByRosterPlayer, suspendedByWeek, conductEntries);

        if (byRoster.isEmpty()) {
            // "Any week" captured, not just a week inside this payload's own
            // window: a capture past throughWeek still means tracking HAS
            // started, just that nothing rostered was ever tagged within a
            // week this league has actually scored (T058 amendment,
            // 2026-09-23 live verification).
            boolean anyCapture = !statusCaptures.capturedWeeks(sport, league.season()).isEmpty();
            String emptyReason = unethicalEmptyReason(anyCapture, conductEntries.isEmpty());
            return new Superlative(Kind.UNETHICAL, true, null, early, null, "GAMES", List.of(),
                    emptyReason, List.of(), null);
        }

        Map<Integer, Integer> totalByRoster = new HashMap<>();
        byRoster.forEach((id, rows) -> totalByRoster.put(id, rows.stream().mapToInt(r -> r.weeks().size()).sum()));

        int max = totalByRoster.values().stream().mapToInt(Integer::intValue).max().orElseThrow();
        List<Integer> topRosters = totalByRoster.entrySet().stream()
                .filter(e -> e.getValue() == max)
                .map(Map.Entry::getKey)
                .sorted()
                .toList();
        List<Holder> holders = topRosters.stream()
                .map(id -> holder(id, nameByRoster, avatarByRoster, managerByRoster))
                .toList();

        List<DetailRow> detail = new ArrayList<>();
        for (int id : topRosters) {
            for (ConductQualifyingWeeks row : byRoster.get(id)) {
                Player p = playersBySleeperId.get(row.playerId());
                detail.add(new ConductDetail(row.playerId(), p == null ? "Unknown player" : p.name(), id,
                        row.source(), row.weeks(), row.reason()));
            }
        }

        return new Superlative(Kind.UNETHICAL, true, null, early, (double) max, "GAMES", holders, null, detail, null);
    }

    // -------------------------------------------------------------- shared

    /**
     * One roster-week's {@code players_points} parsed to numeric values and
     * its started-player set, computed once per payload (coordinator
     * follow-up 2026-09-23, item 6). Bench's own {@link
     * RealizedLineupService#bestLineup} call needs the raw JSON string, so it
     * still reads {@link RosterWeekPointsRepository.WeekBreakdown} directly --
     * this record serves the three helpers (waiver, absence, unethical) that
     * only ever needed the parsed map, and used to each parse it separately.
     */
    private record ParsedWeek(int week, int rosterId, Map<String, Double> playersPoints, Set<String> starters) {}

    /**
     * Behaviour-preserving versus each superlative's own former copy of this
     * loop: a blank or missing {@code players_points} JSON becomes an EMPTY
     * map here (not a skipped week), the same as {@code waiverSuperlative}'s
     * former inline parse did -- a caller that needs to skip such a week
     * (absence/unethical did, via {@code continue}) gets the same effect for
     * free, since iterating an empty map's keys contributes nothing.
     */
    private static List<ParsedWeek> parseWeeks(List<RosterWeekPointsRepository.WeekBreakdown> breakdowns) {
        List<ParsedWeek> out = new ArrayList<>();
        for (RosterWeekPointsRepository.WeekBreakdown w : breakdowns) {
            Map<String, Double> points = new HashMap<>();
            if (w.playersPointsJson() != null && !w.playersPointsJson().isBlank()) {
                JsonUtil.readMap(w.playersPointsJson()).forEach((pid, v) -> {
                    if (v instanceof Number n) points.put(pid, n.doubleValue());
                });
            }
            Set<String> starters = WeeklyReportService.startersOf(w.startersJson());
            out.add(new ParsedWeek(w.week(), w.rosterId(), points, starters));
        }
        return out;
    }

    private static Map<String, Player> playersBySleeperId(List<Player> all) {
        Map<String, Player> out = new HashMap<>();
        for (Player p : all) {
            if (p.sleeperId() != null) out.put(p.sleeperId(), p);
        }
        return out;
    }

    private static Superlative notBuiltYet(Kind kind) {
        return new Superlative(kind, false, "not built yet", false, null, null, List.of(), null, List.of(), null);
    }

    private static Superlative unavailable(Kind kind, String reason) {
        return new Superlative(kind, false, reason, false, null, null, List.of(), null, List.of(), null);
    }

    private static double round2(double d) {
        return Math.round(d * 100.0) / 100.0;
    }

    private static Holder holder(int rosterId, Map<Integer, String> nameByRoster, Map<Integer, String> avatarByRoster,
                                 Map<Integer, Long> managerByRoster) {
        return new Holder(rosterId, managerByRoster.get(rosterId),
                nameByRoster.getOrDefault(rosterId, "Roster " + rosterId), avatarByRoster.get(rosterId));
    }

    /** Same fallback chain as {@link ExpectedWinsService#forLeague}: member team name, else manager name, else "Roster N". */
    private void teamMaps(long leagueId, Map<Integer, String> nameByRoster, Map<Integer, String> avatarByRoster,
                          Map<Integer, Long> managerByRoster) {
        Map<Long, String> teamNameByManager = new HashMap<>();
        for (LeagueMemberRepository.MemberRow m : members.forLeague(leagueId)) {
            if (m.teamName() != null && !m.teamName().isBlank()) {
                teamNameByManager.put(m.managerId(), m.teamName());
            }
        }
        for (RosterSeasonRepository.StandingRow s : rosterSeasons.forLeague(leagueId)) {
            managerByRoster.put(s.rosterId(), s.managerId());
            avatarByRoster.put(s.rosterId(), s.avatarId());
            String name = s.managerId() == null ? null : teamNameByManager.get(s.managerId());
            if (name == null) name = s.managerName();
            if (name != null && !name.isBlank()) nameByRoster.put(s.rosterId(), name);
        }
    }
}
