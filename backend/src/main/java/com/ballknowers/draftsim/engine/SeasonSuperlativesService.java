package com.ballknowers.draftsim.engine;

import com.ballknowers.draftsim.domain.Player;
import com.ballknowers.draftsim.domain.Position;
import com.ballknowers.draftsim.domain.Sport;
import com.ballknowers.draftsim.sport.SportRules;
import com.ballknowers.draftsim.sport.SportRulesRegistry;
import com.ballknowers.draftsim.store.*;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.util.*;
import java.util.function.Predicate;
import java.util.stream.Collectors;
import com.ballknowers.draftsim.domain.LeagueSettings;

import static com.ballknowers.draftsim.util.Rounding.round2;

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

    // The early-season threshold lives in SeasonWindow (spec 013 T069): one declaration, shared with the grades.

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
        LUCKIEST, UNLUCKIEST, MOST_BENCH_POINTS, WAIVER_WIRE_WARRIOR, JABARI_SMITH_JR, JOEL_EMBIID, UNETHICAL
    }

    /** The six kinds FR-006 marks as "mostly noise" while the season window is early. */
    private static final Set<Kind> EARLY_ELIGIBLE = EnumSet.of(
            Kind.LUCKIEST, Kind.UNLUCKIEST, Kind.MOST_BENCH_POINTS,
            Kind.WAIVER_WIRE_WARRIOR, Kind.JOEL_EMBIID, Kind.UNETHICAL);

    /** {@code username} is the Sleeper display name, the secondary line under the team name; filled in once, in {@link #withUsernames}. */
    public record Holder(int rosterId, Long managerId, String teamName, String username, String avatarId) {}

    public record Coverage(int weeksCovered, int weeksExcluded, List<String> reasons) {}

    /** One row of a superlative's supporting detail. Discriminated on the wire by a "type" field the controller adds. */
    public sealed interface DetailRow
            permits WeekScoreDetail, GameDetail, LuckDetail, BenchTotalDetail, PickupDetail, AbsenceDetail,
            ConductDetail, AddDetail {}

    public record WeekScoreDetail(int week, int rosterId, double points) implements DetailRow {}

    public record GameDetail(int week, int rosterId, int opponentRosterId, String opponentTeamName,
                             double points, double opponentPoints, double margin) implements DetailRow {}

    /**
     * Copied unmodified from the bounded {@link ExpectedWinsService} row
     * (FR-004) -- never recomputed here. {@code reading} is new for T032 and
     * must be mirrored into {@code web/src/api/superlatives.ts}'s SuperlativeDetail union
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

    /**
     * One counted add for JABARI_SMITH_JR (US7, research R16 decision 6). Unlike every other
     * detail row, the team here isn't a holder (the award is player-headed) -- {@code
     * teamName}/{@code avatarId} are carried on the row itself, the same reasoning GameDetail's
     * {@code opponentTeamName} already uses for a team that's an opponent, not a holder.
     */
    public record AddDetail(String playerId, int week, int rosterId, String teamName, String avatarId,
                            String addType, Integer faabBid) implements DetailRow {}

    /**
     * One row of a team award's full standings (spec 010). Invariants: {@code rank == null} iff
     * {@code !hasValue}, and {@code missingReason != null} iff {@code !hasValue}. A row without a
     * value is a roster the award couldn't measure -- listed last with the reason, never as a 0.
     * {@code note} is the per-row context line and may be null.
     */
    public record Standing(Integer rank, Holder team, Double value, String note, boolean hasValue,
                           String missingReason) {
        public Standing {
            if ((rank == null) == hasValue) throw new IllegalArgumentException("rank must be null iff !hasValue");
            if ((missingReason == null) != hasValue) {
                throw new IllegalArgumentException("missingReason must be non-null iff !hasValue");
            }
        }
    }

    /** One row of JABARI_SMITH_JR's player standings (spec 010). {@code position} and {@code team} are nullable. */
    public record PlayerStanding(int rank, String playerId, String playerName, String position, String team,
                                 int adds, int distinctTeams) {}

    /**
     * @param playerHolders JABARI_SMITH_JR only (research R16 decision 6): every tied-top player,
     *                       {@code []} for every other kind. {@code holders} stays {@code []} for
     *                       this kind -- the award is headed by players, not teams.
     * @param standings       spec 010: one row per roster in the league-season (orphans included), ranked
     *                        for this award; {@code []} for an unavailable/empty kind and for JABARI_SMITH_JR
     * @param playerStandings spec 010: JABARI_SMITH_JR only, {@code []} for every other kind
     */
    public record Superlative(Kind kind, boolean available, String reason, boolean early, Double value, String unit,
                              List<Holder> holders, String emptyReason, List<DetailRow> detail, Coverage coverage,
                              List<PlayerHolder> playerHolders, List<Standing> standings,
                              List<PlayerStanding> playerStandings) {
        /** Old 10-argument shape, kept so the 17 existing call sites don't all need touching (T076). */
        public Superlative(Kind kind, boolean available, String reason, boolean early, Double value, String unit,
                           List<Holder> holders, String emptyReason, List<DetailRow> detail, Coverage coverage) {
            this(kind, available, reason, early, value, unit, holders, emptyReason, detail, coverage, List.of(),
                    List.of(), List.of());
        }

        /** The 11-argument shape (playerHolders, no standings), for JABARI_SMITH_JR's empty/available-without-standings returns. */
        public Superlative(Kind kind, boolean available, String reason, boolean early, Double value, String unit,
                           List<Holder> holders, String emptyReason, List<DetailRow> detail, Coverage coverage,
                           List<PlayerHolder> playerHolders) {
            this(kind, available, reason, early, value, unit, holders, emptyReason, detail, coverage, playerHolders,
                    List.of(), List.of());
        }
    }

    /** One tied-top player for JABARI_SMITH_JR. {@code team} is nullable: a free agent has none. */
    public record PlayerHolder(String playerId, String playerName, String position, String team,
                               int adds, int distinctTeams) {}

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
                    SeasonWindow.EARLY_THRESHOLD_WEEKS, closeMargin, List.of(), commissionerListAvailable, List.of(),
                    league.sleeperId()));
        }

        int throughWeek = Collections.max(scoredWeeks);
        List<Integer> suspensionWeeksObserved = suspensionWeeksObserved(league, throughWeek);
        int weeksScored = scoredWeeks.size();
        boolean early = weeksScored < SeasonWindow.EARLY_THRESHOLD_WEEKS;
        WeekBound bound = WeekBound.through(throughWeek);
        final Set<Integer> scoredWeeksFinal = scoredWeeks;

        Map<Integer, String> nameByRoster = new HashMap<>();
        Map<Integer, String> avatarByRoster = new HashMap<>();
        Map<Integer, Long> managerByRoster = new HashMap<>();
        teamMaps(league.id(), nameByRoster, avatarByRoster, managerByRoster);
        // The roster universe for every standings list (plan amendment 7 / FR-012): every roster_season
        // row, an orphan with no manager or name included. NOT nameByRoster.keySet(), which teamMaps
        // leaves without a roster that has no name.
        final Set<Integer> rosterIds = Set.copyOf(managerByRoster.keySet());

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

        // Standings come from the SAME loaded rows the winners' populations equal (plan amendment 1):
        // leagueBreakdowns for the week scores, games for the margins.
        built.put(Kind.HIGHEST_WEEK, weekScoreSuperlative(Kind.HIGHEST_WEEK,
                records.highestWeeks(List.of(league.id()), 20, bound),
                weekScoreStandings(leagueBreakdowns, rosterIds, true, nameByRoster, avatarByRoster, managerByRoster),
                nameByRoster, avatarByRoster, managerByRoster));
        built.put(Kind.LOWEST_WEEK, weekScoreSuperlative(Kind.LOWEST_WEEK,
                records.lowestWeeks(List.of(league.id()), 20, bound),
                weekScoreStandings(leagueBreakdowns, rosterIds, false, nameByRoster, avatarByRoster, managerByRoster),
                nameByRoster, avatarByRoster, managerByRoster));
        built.put(Kind.BIGGEST_BLOWOUT, marginSuperlative(Kind.BIGGEST_BLOWOUT,
                records.biggestBlowouts(List.of(league.id()), 20, bound), pairingCoverage,
                marginStandings(games, rosterIds, false, nameByRoster, avatarByRoster, managerByRoster),
                nameByRoster, avatarByRoster, managerByRoster));
        built.put(Kind.CLOSEST_GAME, marginSuperlative(Kind.CLOSEST_GAME,
                records.closestMatchups(List.of(league.id()), 20, bound), pairingCoverage,
                marginStandings(games, rosterIds, true, nameByRoster, avatarByRoster, managerByRoster),
                nameByRoster, avatarByRoster, managerByRoster));

        Map<Integer, List<GameDetail>> closeWins = closeGames(games, closeMargin, true);
        Map<Integer, List<GameDetail>> closeLosses = closeGames(games, closeMargin, false);
        // A roster in a paired game but absent from closeWins/closeLosses is a real 0; one in no paired
        // game at all wasn't measured (R12).
        Set<Integer> rostersWithGames = new HashSet<>();
        for (LeagueMatchupRepository.PairedGame g : games) {
            rostersWithGames.add(g.aRosterId());
            rostersWithGames.add(g.bRosterId());
        }
        built.put(Kind.CLOSE_WINS, closeGameSuperlative(Kind.CLOSE_WINS, closeWins, "WINS", pairingCoverage,
                rostersWithGames, rosterIds, nameByRoster, avatarByRoster, managerByRoster));
        built.put(Kind.CLOSE_LOSSES, closeGameSuperlative(Kind.CLOSE_LOSSES, closeLosses, "GAMES", pairingCoverage,
                rostersWithGames, rosterIds, nameByRoster, avatarByRoster, managerByRoster));

        addLuckSuperlatives(built, sleeperLeagueId, bound, throughWeek, early, rosterIds,
                nameByRoster, avatarByRoster, managerByRoster);
        built.put(Kind.MOST_BENCH_POINTS, benchSuperlative(league, leagueBreakdowns, playersBySleeperId, throughWeek,
                early, rosterIds, nameByRoster, avatarByRoster, managerByRoster));

        // Fetched once (T076 coordinator instruction): both WAIVER_WIRE_WARRIOR and
        // JABARI_SMITH_JR read this league-season's transactions, and the second
        // award adds no second query.
        List<LeagueTransactionRepository.Row> txRows = transactions.forSeason(league.id(), league.season());
        built.put(Kind.WAIVER_WIRE_WARRIOR, waiverSuperlative(league, sleeperLeagueId, txRows, parsedWeeks,
                playersBySleeperId, early, rosterIds, nameByRoster, avatarByRoster, managerByRoster));
        built.put(Kind.JABARI_SMITH_JR, mostAddedSuperlative(sleeperLeagueId, txRows, throughWeek,
                playersBySleeperId, nameByRoster, avatarByRoster));

        built.put(Kind.JOEL_EMBIID, SuperlativeAbsenceMath.absenceSuperlative(this.absences, this.games, this.gameScoring,
                this.leagues, league, sleeperLeagueId, rules, scoredWeeksFinal, parsedWeeks,
                playersBySleeperId, early, rosterIds, nameByRoster, avatarByRoster, managerByRoster));
        built.put(Kind.UNETHICAL, unethicalSuperlative(league, parsedWeeks, playersBySleeperId, early, rosterIds,
                nameByRoster, avatarByRoster, managerByRoster));

        Map<Integer, String> usernameByRoster = new HashMap<>();
        for (RosterSeasonRepository.StandingRow s : rosterSeasons.forLeague(league.id())) {
            usernameByRoster.put(s.rosterId(), s.managerName());
        }
        List<Superlative> superlatives = new ArrayList<>();
        for (Kind kind : Kind.values()) {
            superlatives.add(withUsernames(built.getOrDefault(kind, notBuiltYet(kind)), usernameByRoster));
        }

        return Optional.of(new Result(true, null, league.season(), requestedSeason, league.sport(),
                throughWeek, weeksScored, regularSeasonEnd, early, SeasonWindow.EARLY_THRESHOLD_WEEKS, closeMargin,
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

    // ---------------------------------------------------------------- US1

    private Superlative closeGameSuperlative(Kind kind, Map<Integer, List<GameDetail>> byRoster, String unit,
                                             Coverage coverage, Set<Integer> rostersWithGames, Set<Integer> rosterIds,
                                             Map<Integer, String> nameByRoster,
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
        return new Superlative(kind, true, null, false, (double) max, unit, holders, null, detail, coverage, List.of(),
                closeGameStandings(byRoster, rostersWithGames, rosterIds, nameByRoster, avatarByRoster, managerByRoster),
                List.of());
    }

    private Superlative weekScoreSuperlative(Kind kind, List<LeagueRecordService.WeeklyScoreRecord> rows,
                                             List<Standing> standings, Map<Integer, String> nameByRoster, Map<Integer, String> avatarByRoster,
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
        return new Superlative(kind, true, null, false, top.doubleValue(), "POINTS", holders, null, detail, null,
                List.of(), standings, List.of());
    }

    private Superlative marginSuperlative(Kind kind, List<LeagueRecordService.MarginRecord> rows, Coverage coverage,
                                          List<Standing> standings, Map<Integer, String> nameByRoster, Map<Integer, String> avatarByRoster,
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
        return new Superlative(kind, true, null, false, extreme.doubleValue(), "POINTS", holders, null, detail, coverage,
                List.of(), standings, List.of());
    }

    static GameDetail withOpponentName(GameDetail gd, Map<Integer, String> nameByRoster) {
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
                                     int throughWeek, boolean early, Set<Integer> rosterIds,
                                     Map<Integer, String> nameByRoster, Map<Integer, String> avatarByRoster,
                                     Map<Integer, Long> managerByRoster) {
        Optional<ExpectedWinsService.Result> exp = expectedWins.forLeague(sleeperLeagueId, bound);
        if (exp.isEmpty() || !exp.get().available() || exp.get().teams().isEmpty()) {
            String reason = exp.isPresent() && exp.get().reason() != null
                    ? exp.get().reason() : "no completed games for this league yet";
            built.put(Kind.LUCKIEST, unavailable(Kind.LUCKIEST, reason));
            built.put(Kind.UNLUCKIEST, unavailable(Kind.UNLUCKIEST, reason));
            return;
        }
        List<ExpectedWinsService.TeamRow> teams = exp.get().teams();
        // luckSuperlative keeps its signature (SeasonSuperlativesLuckTest calls it directly); the standings
        // are attached here, from the same TeamRows it picks the winner from.
        built.put(Kind.LUCKIEST, withStandings(luckSuperlative(Kind.LUCKIEST, teams, true, early, throughWeek),
                luckStandings(teams, rosterIds, false, nameByRoster, avatarByRoster, managerByRoster)));
        built.put(Kind.UNLUCKIEST, withStandings(luckSuperlative(Kind.UNLUCKIEST, teams, false, early, throughWeek),
                luckStandings(teams, rosterIds, true, nameByRoster, avatarByRoster, managerByRoster)));
    }

    static Superlative withStandings(Superlative s, List<Standing> standings) {
        return new Superlative(s.kind(), s.available(), s.reason(), s.early(), s.value(), s.unit(), s.holders(),
                s.emptyReason(), s.detail(), s.coverage(), s.playerHolders(), standings, s.playerStandings());
    }

    /** One roster-week's optimal-vs-started gap, or its explicit absence. Package-private for T031(c)/(d). */
    record BenchWeekEntry(int week, int rosterId, boolean valid, double pointsLeft) {}

    /** One roster's summed bench cost. Package-private for T031(c)/(d). */
    record BenchAgg(double pointsLeft, int weeksCounted, BiggestBenchWeek biggestWeek) {}

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
                                         Set<Integer> rosterIds, Map<Integer, String> nameByRoster,
                                         Map<Integer, String> avatarByRoster, Map<Integer, Long> managerByRoster) {
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
                detail, coverage, List.of(),
                benchStandings(byRoster, rosterIds, nameByRoster, avatarByRoster, managerByRoster), List.of());
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
                                          List<LeagueTransactionRepository.Row> txRows, List<ParsedWeek> parsedWeeks,
                                          Map<String, Player> playersBySleeperId, boolean early,
                                          Set<Integer> rosterIds, Map<Integer, String> nameByRoster,
                                          Map<Integer, String> avatarByRoster, Map<Integer, Long> managerByRoster) {
        if (txRows.isEmpty()) {
            return unavailable(Kind.WAIVER_WIRE_WARRIOR,
                    "Transactions for this season haven't loaded yet.");
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

        return waiverWinners(byRoster, coverage, early, rosterIds, playersBySleeperId,
                nameByRoster, avatarByRoster, managerByRoster);
    }

    // ---------------------------------------------------------------- US7

    // ---------------------------------------------------------------- US4

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
     * {@code web/src/api/superlatives.ts}'s {@code Superlative.unit} type is a fixed
     * {@code 'POINTS' | 'WINS' | 'GAMES'} union the frontend already ships
     * with, and widening it is outside this backend task.
     */
    private Superlative unethicalSuperlative(LeagueRepository.LeagueRow league, List<ParsedWeek> parsedWeeks,
                                             Map<String, Player> playersBySleeperId, boolean early,
                                             Set<Integer> rosterIds, Map<Integer, String> nameByRoster, Map<Integer, String> avatarByRoster,
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

        return new Superlative(Kind.UNETHICAL, true, null, early, (double) max, "GAMES", holders, null, detail, null,
                List.of(), conductStandings(totalByRoster, rosterIds, nameByRoster, avatarByRoster, managerByRoster),
                List.of());
    }

    // ------------------------------------------------- full standings (spec 010)

    /*
     * One function per kind family, each package-private and static so a test can drive it without
     * Postgres. Every one returns each id in rosterIds exactly once (plan amendment 7 / FR-012: the
     * universe is every roster_season row, orphans included -- never nameByRoster.keySet()). A source
     * row whose roster isn't in rosterIds is skipped (plan amendment 13, R11): the universe is the
     * contract, and the IT fails loudly if the winner's roster ever falls outside it.
     */

    // ---- Moved to SuperlativeStandingBuilders / GameMath / AbsenceMath / WaiverMath / ConductMath
    // (specs/021-codebase-cleanup T051). These forwarders exist because tests call them here.
    static List<Standing> weekScoreStandings(List<RosterWeekPointsRepository.WeekBreakdown> rows, Set<Integer> rosterIds, boolean highest, Map<Integer, String> nameByRoster, Map<Integer, String> avatarByRoster, Map<Integer, Long> managerByRoster) { return SuperlativeStandingBuilders.weekScoreStandings(rows, rosterIds, highest, nameByRoster, avatarByRoster, managerByRoster); }
    static List<Standing> marginStandings(List<LeagueMatchupRepository.PairedGame> games, Set<Integer> rosterIds, boolean closest, Map<Integer, String> nameByRoster, Map<Integer, String> avatarByRoster, Map<Integer, Long> managerByRoster) { return SuperlativeStandingBuilders.marginStandings(games, rosterIds, closest, nameByRoster, avatarByRoster, managerByRoster); }
    static List<Standing> closeGameStandings(Map<Integer, List<GameDetail>> byRoster, Set<Integer> rostersWithGames, Set<Integer> rosterIds, Map<Integer, String> nameByRoster, Map<Integer, String> avatarByRoster, Map<Integer, Long> managerByRoster) { return SuperlativeStandingBuilders.closeGameStandings(byRoster, rostersWithGames, rosterIds, nameByRoster, avatarByRoster, managerByRoster); }
    static List<Standing> luckStandings(List<ExpectedWinsService.TeamRow> teams, Set<Integer> rosterIds, boolean ascending, Map<Integer, String> nameByRoster, Map<Integer, String> avatarByRoster, Map<Integer, Long> managerByRoster) { return SuperlativeStandingBuilders.luckStandings(teams, rosterIds, ascending, nameByRoster, avatarByRoster, managerByRoster); }
    static List<Standing> benchStandings(Map<Integer, BenchAgg> byRoster, Set<Integer> rosterIds, Map<Integer, String> nameByRoster, Map<Integer, String> avatarByRoster, Map<Integer, Long> managerByRoster) { return SuperlativeStandingBuilders.benchStandings(byRoster, rosterIds, nameByRoster, avatarByRoster, managerByRoster); }
    static List<Standing> waiverStandings(Map<Integer, WaiverPickupAttribution.RosterTotal> byRoster, Set<Integer> rosterIds, Map<Integer, String> nameByRoster, Map<Integer, String> avatarByRoster, Map<Integer, Long> managerByRoster) { return SuperlativeStandingBuilders.waiverStandings(byRoster, rosterIds, nameByRoster, avatarByRoster, managerByRoster); }
    static List<Standing> absenceStandings(Map<Integer, AbsenceCost.RosterCost> byRoster, Map<Integer, Set<Integer>> unclassifiedWeeksByRoster, Set<Integer> rosterIds, Map<Integer, String> nameByRoster, Map<Integer, String> avatarByRoster, Map<Integer, Long> managerByRoster) { return SuperlativeStandingBuilders.absenceStandings(byRoster, unclassifiedWeeksByRoster, rosterIds, nameByRoster, avatarByRoster, managerByRoster); }
    static List<Standing> conductStandings(Map<Integer, Integer> totalByRoster, Set<Integer> rosterIds, Map<Integer, String> nameByRoster, Map<Integer, String> avatarByRoster, Map<Integer, Long> managerByRoster) { return SuperlativeStandingBuilders.conductStandings(totalByRoster, rosterIds, nameByRoster, avatarByRoster, managerByRoster); }
    static Map<Integer, List<GameDetail>> closeGames(List<LeagueMatchupRepository.PairedGame> games, double margin, boolean wantWinners) { return SuperlativeGameMath.closeGames(games, margin, wantWinners); }
    static Superlative luckSuperlative(Kind kind, List<ExpectedWinsService.TeamRow> teams, boolean wantMax, boolean early, int throughWeek) { return SuperlativeGameMath.luckSuperlative(kind, teams, wantMax, early, throughWeek); }
    static String luckReading(double winsAboveExpected) { return SuperlativeGameMath.luckReading(winsAboveExpected); }
    static Map<Integer, BenchAgg> aggregateBench(List<BenchWeekEntry> entries) { return SuperlativeGameMath.aggregateBench(entries); }
    static Superlative absenceWinners(Map<Integer, AbsenceCost.RosterCost> byRoster, Map<Integer, Set<Integer>> unclassifiedWeeksByRoster, List<String> leagueLevelReasons, int weeksScored, boolean early, Set<Integer> rosterIds, Map<String, Player> playersBySleeperId, Map<Integer, String> nameByRoster, Map<Integer, String> avatarByRoster, Map<Integer, Long> managerByRoster) { return SuperlativeAbsenceMath.absenceWinners(byRoster, unclassifiedWeeksByRoster, leagueLevelReasons, weeksScored, early, rosterIds, playersBySleeperId, nameByRoster, avatarByRoster, managerByRoster); }
    static Coverage unclassifiedCoverage(List<Integer> topRosters, Map<Integer, Set<Integer>> unclassifiedWeeksByRoster, int weeksScored) { return SuperlativeAbsenceMath.unclassifiedCoverage(topRosters, unclassifiedWeeksByRoster, weeksScored); }
    static Coverage mergeCoverage(List<String> leagueLevelReasons, Coverage unclassified, int weeksScored) { return SuperlativeAbsenceMath.mergeCoverage(leagueLevelReasons, unclassified, weeksScored); }
    static Set<String> neverWalked(Set<String> rosteredPlayerIds, Set<String> gamePlayerIds, Set<String> absencePlayerIds) { return SuperlativeAbsenceMath.neverWalked(rosteredPlayerIds, gamePlayerIds, absencePlayerIds); }
    static BoundedPlayerGames boundedPlayerGames(List<PlayerGameRepository.Row> gameRows, Set<Integer> scoredWeeks, Map<String, Double> scoring, GameScoringService gameScoring) { return SuperlativeAbsenceMath.boundedPlayerGames(gameRows, scoredWeeks, scoring, gameScoring); }
    static Superlative waiverWinners(Map<Integer, WaiverPickupAttribution.RosterTotal> byRoster, Coverage coverage, boolean early, Set<Integer> rosterIds, Map<String, Player> playersBySleeperId, Map<Integer, String> nameByRoster, Map<Integer, String> avatarByRoster, Map<Integer, Long> managerByRoster) { return SuperlativeWaiverMath.waiverWinners(byRoster, coverage, early, rosterIds, playersBySleeperId, nameByRoster, avatarByRoster, managerByRoster); }
    static List<DetailRow> pickupDetail(List<Integer> topRosters, Map<Integer, WaiverPickupAttribution.RosterTotal> byRoster, Map<String, Player> playersBySleeperId) { return SuperlativeWaiverMath.pickupDetail(topRosters, byRoster, playersBySleeperId); }
    static Superlative mostAddedSuperlative(String sleeperLeagueId, List<LeagueTransactionRepository.Row> txRows, int throughWeek, Map<String, Player> playersBySleeperId, Map<Integer, String> nameByRoster, Map<Integer, String> avatarByRoster) { return SuperlativeWaiverMath.mostAddedSuperlative(sleeperLeagueId, txRows, throughWeek, playersBySleeperId, nameByRoster, avatarByRoster); }
    static List<Integer> boundedCapturedWeeks(Set<Integer> capturedWeeks, int throughWeek) { return SuperlativeConductMath.boundedCapturedWeeks(capturedWeeks, throughWeek); }
    static Map<Integer, List<ConductQualifyingWeeks>> computeUnethical( Map<Integer, Map<String, Set<Integer>>> rosteredByRosterPlayer, Map<Integer, Set<String>> suspendedByWeek, List<LeagueConductRepository.Entry> conductEntries) { return SuperlativeConductMath.computeUnethical(rosteredByRosterPlayer, suspendedByWeek, conductEntries); }
    static String unethicalEmptyReason(boolean anyCapture, boolean conductListEmpty) { return SuperlativeConductMath.unethicalEmptyReason(anyCapture, conductListEmpty); }

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
    record ParsedWeek(int week, int rosterId, Map<String, Double> playersPoints, Set<String> starters) {}

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

    static Superlative notBuiltYet(Kind kind) {
        return new Superlative(kind, false, "not built yet", false, null, null, List.of(), null, List.of(), null);
    }

    static Superlative unavailable(Kind kind, String reason) {
        return new Superlative(kind, false, reason, false, null, null, List.of(), null, List.of(), null);
    }

    static Holder holder(int rosterId, Map<Integer, String> nameByRoster, Map<Integer, String> avatarByRoster,
                                 Map<Integer, Long> managerByRoster) {
        return new Holder(rosterId, managerByRoster.get(rosterId),
                nameByRoster.getOrDefault(rosterId, "Roster " + rosterId), null, avatarByRoster.get(rosterId));
    }

    /** Fills each holder's username after the kinds are built, so the kind builders keep their signatures. */
    static Superlative withUsernames(Superlative s, Map<Integer, String> usernameByRoster) {
        List<Holder> holders = s.holders().stream()
                .map(h -> withUsername(h, usernameByRoster))
                .toList();
        // Spec 010 (plan amendment 3): the standings rows' teams need their username too, or every row
        // after rank 1 would have none.
        List<Standing> standings = s.standings().stream()
                .map(r -> new Standing(r.rank(), withUsername(r.team(), usernameByRoster), r.value(), r.note(),
                        r.hasValue(), r.missingReason()))
                .toList();
        return new Superlative(s.kind(), s.available(), s.reason(), s.early(), s.value(), s.unit(), holders,
                s.emptyReason(), s.detail(), s.coverage(), s.playerHolders(), standings, s.playerStandings());
    }

    private static Holder withUsername(Holder h, Map<Integer, String> usernameByRoster) {
        return h.username() != null ? h
                : new Holder(h.rosterId(), h.managerId(), h.teamName(), usernameByRoster.get(h.rosterId()), h.avatarId());
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
