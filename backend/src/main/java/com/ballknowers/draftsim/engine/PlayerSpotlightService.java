package com.ballknowers.draftsim.engine;

import com.ballknowers.draftsim.domain.Player;
import com.ballknowers.draftsim.domain.Sport;
import com.ballknowers.draftsim.engine.SpotlightOwnership.Ownership;
import com.ballknowers.draftsim.sport.SportRules;
import com.ballknowers.draftsim.sport.SportRulesRegistry;
import com.ballknowers.draftsim.store.JsonUtil;
import com.ballknowers.draftsim.store.LeagueRepository;
import com.ballknowers.draftsim.store.PlayerRepository;
import com.ballknowers.draftsim.store.PlayerGameRepository;
import com.ballknowers.draftsim.store.SportTrendingRepository;
import com.ballknowers.draftsim.store.SportWeekStatsRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Supplier;

/**
 * The league home's player spotlight: top players of the period, Sleeper's trending adds, and
 * rookie watch (specs/014-home-player-spotlight; contracts/player-spotlight-api.md).
 *
 * <p>This class owns what every section shares: whether the spotlight applies at all, and which
 * period (a night or a week) the sections are scored against. The section bodies are filled in
 * by later stories; here each is an empty placeholder with the contract's shape.
 *
 * <p><b>The period kind comes from {@code SportRules.playsMultipleGamesPerScoringPeriod()} and
 * nowhere else</b> (FR-013); {@code NoSportNameInPlayerSpotlightTest} scans this file for a sport
 * literal. <b>Each section is computed behind its own catch</b>, so one failing section reports
 * {@code SECTION_FAILED} instead of taking its siblings down (research R11).
 */
@Service
public class PlayerSpotlightService {

    private static final Logger log = LoggerFactory.getLogger(PlayerSpotlightService.class);

    /**
     * Night D counts as complete once its week was fetched at or after D+1 at this hour, UTC.
     *
     * <p><b>An assumption, not a measurement</b> (research R6): 06:00 Eastern, after the latest
     * West Coast finish. It cannot be measured until NBA games exist (2026-10-20), and is owed a
     * check at quickstart V6. Change it by editing this one constant.
     */
    static final int NIGHT_COMPLETE_HOUR_UTC = 10;

    static final String NO_WEEK_SCORED = "NO_WEEK_SCORED";
    static final String NO_GAMES_YET = "NO_GAMES_YET";
    static final String NO_COMPLETE_NIGHT_YET = "NO_COMPLETE_NIGHT_YET";
    static final String NO_ROSTERED_PLAYED = "NO_ROSTERED_PLAYED";
    static final String NEVER_FETCHED = "NEVER_FETCHED";
    static final String SECTION_FAILED = "SECTION_FAILED";
    static final String PAST_SEASON = "PAST_SEASON";

    /** Trending's window until a stored fetch says otherwise (data-model: stored, not assumed). */
    static final int DEFAULT_LOOKBACK_HOURS = 24;

    public enum Kind { NIGHT, WEEK }

    /**
     * What the sections are scored against. Exactly one shape per kind: a NIGHT carries
     * {@code date} and {@code gamesCount}; a WEEK carries {@code week} and {@code weekFinal}.
     * The fields of the other kind are null and are never serialized.
     */
    public record Period(Kind kind, LocalDate date, Integer gamesCount, Integer week, Boolean weekFinal) {
        static Period night(LocalDate date, int gamesCount) {
            return new Period(Kind.NIGHT, date, gamesCount, null, null);
        }

        static Period week(int week, boolean weekFinal) {
            return new Period(Kind.WEEK, null, null, week, weekFinal);
        }
    }

    /** One player's one game or week. {@code opponent}/{@code isAway} nullable: unknown, never guessed. */
    public record Performance(String playerId, String name, String position, String team,
                              String opponent, Boolean isAway, double points, Ownership ownership) {}

    /**
     * {@code outcome} is PLAYED, DID_NOT_PLAY, NO_GAME or NO_PERIOD. {@code points}, {@code opponent}
     * and {@code isAway} are meaningful only when PLAYED and null otherwise (FR-005): a non-game
     * is never a zero.
     */
    public record TrendingEntry(int rank, String playerId, String name, String position, String team,
                                int addCount, Ownership ownership, String outcome,
                                Double points, String opponent, Boolean isAway) {}

    /** A list section. {@code entries} empty with {@code unavailable} null is a placeholder only. */
    public record Section(List<Performance> entries, String unavailable) {
        static Section empty() {
            return new Section(List.of(), null);
        }

        static Section failed() {
            return new Section(List.of(), SECTION_FAILED);
        }
    }

    public record TrendingSection(List<TrendingEntry> entries, int lookbackHours, Instant fetchedAt,
                                  boolean stale, int omittedUnknownPlayers, String unavailable) {
        static TrendingSection empty() {
            return new TrendingSection(List.of(), DEFAULT_LOOKBACK_HOURS, null, false, 0, null);
        }

        static TrendingSection failed() {
            return new TrendingSection(List.of(), DEFAULT_LOOKBACK_HOURS, null, false, 0, SECTION_FAILED);
        }
    }

    /**
     * {@code topOfNight} is null when the sport has one game per period (the football top list is
     * the weekly report's {@code topPerformers}, research R9); the controller then omits the key.
     * When {@code applies} is false only {@code reason}, {@code season} and {@code sport} are meaningful.
     */
    public record Result(boolean applies, String reason, int season, Sport sport,
                         boolean playersPlayMultiplePerPeriod, Period period, String periodUnavailable,
                         LocalDate laterNightInProgress, LocalDate seasonStartDate,
                         Section topOfNight, TrendingSection trending, Section rookieWatch) {

        public static Result notApplicable(String reason, int season, Sport sport) {
            return new Result(false, reason, season, sport, false, null, null, null, null,
                    null, null, null);
        }
    }

    /** The period choice, plus the two explanations that ride beside a null period. */
    record PeriodChoice(Period period, String unavailable, LocalDate laterNightInProgress) {}

    private final LeagueRepository leagues;
    private final SportRulesRegistry rulesRegistry;
    private final PlayerGameRepository playerGames;
    private final SportWeekStatsRepository weekStats;
    private final SportLeagueSeasonSource currentSeason;
    private final Clock clock;
    private final PlayerRepository players;
    private final GameScoringService gameScoring;
    private final SpotlightOwnership ownership;
    private final SportTrendingRepository trendingRepo;

    public PlayerSpotlightService(LeagueRepository leagues, SportRulesRegistry rulesRegistry,
                                  PlayerGameRepository playerGames,
                                  SportWeekStatsRepository weekStats, SportLeagueSeasonSource currentSeason,
                                  Clock clock, PlayerRepository players, GameScoringService gameScoring,
                                  SpotlightOwnership ownership, SportTrendingRepository trendingRepo) {
        this.leagues = leagues;
        this.rulesRegistry = rulesRegistry;
        this.playerGames = playerGames;
        this.weekStats = weekStats;
        this.currentSeason = currentSeason;
        this.clock = clock;
        this.players = players;
        this.gameScoring = gameScoring;
        this.ownership = ownership;
        this.trendingRepo = trendingRepo;
    }

    /**
     * Empty when the league is unknown. The league answered about is the one whose id was given,
     * not {@code LeagueSeasonResolver}'s "newest played season": that resolver walks a pre-draft
     * 2026 league back to 2025, and the spotlight must answer about 2026 (its pre-season state is
     * the point) and say PAST_SEASON about 2025, not the reverse.
     */
    public Optional<Result> forLeague(String sleeperLeagueId, String sleeperUserId) {
        List<LeagueRepository.LeagueRow> chain = leagues.chainBySleeperId(sleeperLeagueId);
        if (chain.isEmpty()) return Optional.empty();
        LeagueRepository.LeagueRow league = chain.getFirst();
        Sport sport = league.sport();

        if (!isCurrentSeason(league)) {
            return Optional.of(Result.notApplicable(PAST_SEASON, league.season(), sport));
        }

        boolean multiple = rulesRegistry.get(sport).playsMultipleGamesPerScoringPeriod();
        SportRules rules = rulesRegistry.get(sport);

        // The period is shared by every section, so it is computed once and outside the
        // per-section catches: there is nothing to say about sections with no period at all.
        List<SportWeekStatsRepository.Row> statsRows = weekStats.forSeason(sport, league.season());
        PeriodChoice choice = choosePeriod(multiple,
                playerGames.datesForSeason(sport, league.season()),
                fetchedAtByWeek(statsRows), finalByWeek(statsRows), clock.instant());

        // Loaded once and scored once; topOfNight and rookieWatch (and later trending) all read it.
        // Loading happens inside the sections' own catch, so a failed read costs those sections only.
        Supplier<PeriodRows> rows = memoize(() -> loadPeriodRows(league, sport, choice.period()));
        Supplier<Map<String, Ownership>> owners = memoize(() -> ownership.forLeague(league, sleeperUserId));

        Section topOfNight = multiple
                ? safely("topOfNight", () -> topOfNight(choice.period(), rows.get(), owners.get()), Section::failed)
                : null;
        // Read once: the section and the top-level seasonStartDate come from the same snapshot (R7).
        Optional<SportTrendingRepository.Snapshot> snapshot = Optional.empty();
        try {
            snapshot = trendingRepo.read(sport.code());
        } catch (RuntimeException e) {
            log.warn("player spotlight trending snapshot read failed", e);
        }
        Optional<SportTrendingRepository.Snapshot> snap = snapshot;
        TrendingSection trending = safely("trending", () -> {
            List<String> ids = snap.map(x -> x.entries().stream().limit(MAX_TRENDING_READ)
                    .map(SportTrendingRepository.Entry::sleeperPlayerId).toList()).orElse(List.of());
            return trending(snap, choice.period(), rows.get(), owners.get(),
                    players.byIds(sport, ids), clock.instant());
        }, TrendingSection::failed);
        Section rookieWatch = safely("rookieWatch",
                () -> rookieWatch(choice.period(), rows.get(), owners.get(), rules), Section::failed);

        return Optional.of(new Result(true, null, league.season(), sport, multiple,
                choice.period(), choice.unavailable(), choice.laterNightInProgress(),
                snap.map(SportTrendingRepository.Snapshot::seasonStartDate).orElse(null),
                topOfNight, trending, rookieWatch));
    }

    /**
     * Research R8. A league is current when its season equals the sport's stored
     * {@code league_season}; with that unknown, when no other league names it as its predecessor
     * (it is the newest season in its own chain). Anything else is a past season, where trending
     * is meaningless and today's {@code years_exp} would mislabel rookies (R4).
     */
    private boolean isCurrentSeason(LeagueRepository.LeagueRow league) {
        Optional<Integer> stored = Optional.empty();
        try {
            stored = currentSeason.leagueSeason(league.sport());
        } catch (RuntimeException e) {
            log.warn("player spotlight could not read the stored league season; using the chain rule", e);
        }
        if (stored.isPresent()) return stored.get() == league.season();
        for (LeagueRepository.LeagueRow other : leagues.all()) {
            if (league.sleeperId().equals(other.previousLeagueId())) return false;
        }
        return true;
    }

    private static Map<Integer, Instant> fetchedAtByWeek(List<SportWeekStatsRepository.Row> statsRows) {
        Map<Integer, Instant> out = new HashMap<>();
        for (SportWeekStatsRepository.Row r : statsRows) out.put(r.week(), r.fetchedAt());
        return out;
    }

    /** Week to its sport-wide {@code sport_week_stats.final} flag. */
    private static Map<Integer, Boolean> finalByWeek(List<SportWeekStatsRepository.Row> statsRows) {
        Map<Integer, Boolean> out = new HashMap<>();
        for (SportWeekStatsRepository.Row r : statsRows) out.put(r.week(), r.fin());
        return out;
    }

    /** Entries per list section. */
    static final int LIMIT = 10;

    /**
     * One stored game with its player resolved and its points computed once. Everything that
     * ranks or filters a period reads these, so a game is never scored twice per request and two
     * sections cannot disagree about what it was worth.
     */
    record ScoredGame(PlayerGameRepository.Row row, Player player, double points) {}

    /**
     * A period's stored games, and the scored subset whose player resolves. {@code opponents()}
     * is for trending (T033), which classifies a no-row player as DID_NOT_PLAY or NO_GAME against it.
     */
    record PeriodRows(List<PlayerGameRepository.Row> rows, List<ScoredGame> scored) {
        Set<String> opponents() {
            Set<String> out = new HashSet<>();
            for (PlayerGameRepository.Row r : rows) if (r.opponent() != null) out.add(r.opponent());
            return out;
        }
    }

    private PeriodRows loadPeriodRows(LeagueRepository.LeagueRow league, Sport sport, Period period) {
        if (period == null) return new PeriodRows(List.of(), List.of());
        List<PlayerGameRepository.Row> rows = period.kind() == Kind.NIGHT
                ? playerGames.forDate(sport, league.season(), period.date())
                : playerGames.forWeek(sport, league.season(), period.week());
        Set<String> ids = new HashSet<>();
        for (PlayerGameRepository.Row r : rows) ids.add(r.sleeperPlayerId());
        Map<String, Player> bySleeperId = players.byIds(sport, ids);
        return new PeriodRows(rows, scoreAll(rows, leagues.scoringOf(league.id()), gameScoring, bySleeperId));
    }

    /** Scores every row once; a row whose player id does not resolve is skipped. */
    static List<ScoredGame> scoreAll(List<PlayerGameRepository.Row> rows, Map<String, Double> scoring,
                                     GameScoringService scorer, Map<String, Player> bySleeperId) {
        List<ScoredGame> out = new ArrayList<>();
        for (PlayerGameRepository.Row r : rows) {
            Player p = bySleeperId.get(r.sleeperPlayerId());
            if (p == null) continue;
            out.add(new ScoredGame(r, p, scorer.score(scoring, JsonUtil.readMap(r.statsJson()))));
        }
        return out;
    }

    /**
     * Best single-game scores of the night among players <b>rostered in this league</b> (US1).
     * Order is points descending, then player id (FR-014), limit {@value #LIMIT}.
     *
     * <p>{@code period == null} is {@code NO_PERIOD}. A period in which no rostered player has a
     * game (rosters not stored yet, or a slate with none rostered) is {@code NO_ROSTERED_PLAYED},
     * so an empty list always carries a reason (contract, "Amended after build").
     */
    static Section topOfNight(Period period, PeriodRows rows, Map<String, Ownership> owners) {
        if (period == null) return new Section(List.of(), "NO_PERIOD");
        List<ScoredGame> rostered = new ArrayList<>();
        for (ScoredGame g : rows.scored()) {
            Ownership o = owners.get(g.row().sleeperPlayerId());
            if (o != null && o.rostered()) rostered.add(g);
        }
        if (rostered.isEmpty()) return new Section(List.of(), NO_ROSTERED_PLAYED);
        return new Section(rank(rostered, owners), null);
    }

    /** Ids read from the stored list before unknown ones are dropped; room for a few unknowns. */
    static final int MAX_TRENDING_READ = 25;

    /**
     * Sleeper's trending adds, each classified against the period's games (US2). Order is
     * Sleeper's rank. {@code points}, {@code opponent} and {@code isAway} are set only for PLAYED.
     * "Never fetched" is {@code fetchedAt == null}; a stored failure does not make a fetched list
     * unavailable (the failure columns are not cleared by a later success).
     *
     * <p>No row in the period: NO_GAME only when his team is known, is no row's opponent, and the
     * period is settled (a NIGHT, or a WEEK with {@code weekFinal}); otherwise DID_NOT_PLAY. An
     * unsettled week has teams whose game has not kicked off, which look exactly like a bye
     * (research R3, amended after review).
     */
    static TrendingSection trending(Optional<SportTrendingRepository.Snapshot> snapshot, Period period,
                                    PeriodRows rows, Map<String, Ownership> owners,
                                    Map<String, Player> byId, Instant now) {
        if (snapshot.isEmpty() || snapshot.get().fetchedAt() == null) {
            return new TrendingSection(List.of(), DEFAULT_LOOKBACK_HOURS, null, false, 0, NEVER_FETCHED);
        }
        SportTrendingRepository.Snapshot snap = snapshot.get();
        Instant fetchedAt = snap.fetchedAt().toInstant();
        boolean stale = fetchedAt.isBefore(now.minus(java.time.Duration.ofHours(snap.lookbackHours())));

        Map<String, List<ScoredGame>> gamesByPlayer = new HashMap<>();
        for (ScoredGame g : rows.scored()) {
            gamesByPlayer.computeIfAbsent(g.row().sleeperPlayerId(), k -> new ArrayList<>()).add(g);
        }
        Set<String> opponents = rows.opponents();
        boolean settled = period != null && (period.kind() == Kind.NIGHT
                || Boolean.TRUE.equals(period.weekFinal()));

        List<TrendingEntry> out = new ArrayList<>();
        int omitted = 0;
        int rank = 0;
        for (SportTrendingRepository.Entry e : snap.entries()) {
            if (out.size() == LIMIT) break;
            rank++;
            Player p = byId.get(e.sleeperPlayerId());
            if (p == null) {
                omitted++;
                continue;
            }
            Ownership own = owners.getOrDefault(e.sleeperPlayerId(), Ownership.UNROSTERED);
            String position = p.primary().name();
            String outcome;
            Double points = null;
            String opponent = null;
            Boolean isAway = null;
            List<ScoredGame> played = gamesByPlayer.get(e.sleeperPlayerId());
            if (period == null) {
                outcome = "NO_PERIOD";
            } else if (played != null && !played.isEmpty()) {
                outcome = "PLAYED";
                double sum = 0;
                for (ScoredGame g : played) sum += g.points();
                points = sum;
                ScoredGame first = played.stream()
                        .min(Comparator.comparing(g -> g.row().gameId())).orElseThrow();
                opponent = first.row().opponent();
                isAway = first.row().isAway();
            } else if (settled && p.team() != null && !opponents.contains(p.team())) {
                outcome = "NO_GAME";
            } else {
                outcome = "DID_NOT_PLAY";
            }
            out.add(new TrendingEntry(rank, e.sleeperPlayerId(), p.name(), position, p.team(),
                    e.addCount(), own, outcome, points, opponent, isAway));
        }
        return new TrendingSection(out, snap.lookbackHours(), fetchedAt, stale, omitted, null);
    }

    /**
     * First-season players' best games of the period, rostered or not (US3). A rookie is
     * {@code years_exp == 0} exactly; null is excluded (research R4), and a position the sport's
     * rules exclude ({@link SportRules#rookieWatchEligible}, football's K and DEF) is excluded
     * whatever its years_exp. A period with no usable rows at all is also
     * {@code NO_ROOKIE_PLAYED}: an empty list must always carry a reason.
     */
    static Section rookieWatch(Period period, PeriodRows rows, Map<String, Ownership> owners,
                               SportRules rules) {
        if (period == null) return new Section(List.of(), "NO_PERIOD");
        List<ScoredGame> rookies = new ArrayList<>();
        for (ScoredGame g : rows.scored()) {
            Integer exp = g.player().yearsExp();
            if (exp != null && exp == 0 && rules.rookieWatchEligible(g.player())) rookies.add(g);
        }
        if (rookies.isEmpty()) return new Section(List.of(), "NO_ROOKIE_PLAYED");
        return new Section(rank(rookies, owners), null);
    }

    private static List<Performance> rank(List<ScoredGame> games, Map<String, Ownership> owners) {
        List<ScoredGame> sorted = new ArrayList<>(games);
        sorted.sort(Comparator.comparingDouble(ScoredGame::points).reversed()
                .thenComparing(g -> g.row().sleeperPlayerId())
                .thenComparing(g -> g.row().gameId()));
        List<Performance> out = new ArrayList<>();
        for (ScoredGame g : sorted) {
            if (out.size() == LIMIT) break;
            String id = g.row().sleeperPlayerId();
            out.add(new Performance(id, g.player().name(), g.player().primary().name(), g.player().team(),
                    g.row().opponent(), g.row().isAway(), g.points(),
                    owners.getOrDefault(id, Ownership.UNROSTERED)));
        }
        return out;
    }

    /** Computes at most once; a throwing computation rethrows on every call (each section catches it). */
    private static <T> Supplier<T> memoize(Supplier<T> compute) {
        Object[] cell = new Object[1];
        boolean[] done = new boolean[1];
        return () -> {
            if (!done[0]) {
                cell[0] = compute.get();
                done[0] = true;
            }
            @SuppressWarnings("unchecked") T t = (T) cell[0];
            return t;
        };
    }

    /** Runs one section's computation; a failure is logged and becomes that section's own SECTION_FAILED. */
    private static <T> T safely(String section, Supplier<T> compute, Supplier<T> failed) {
        try {
            return compute.get();
        } catch (RuntimeException e) {
            log.warn("player spotlight section {} failed", section, e);
            return failed.get();
        }
    }

    /**
     * Which period the sections are scored against. Pure, so the rules are testable at a pinned
     * clock without a database.
     *
     * <p><b>WEEK</b> (one game per period), amended after design review: the newest week whose
     * sport-wide {@code sport_week_stats.final} is true ({@code weekFinal} true); with none final,
     * the newest stored {@code sport_week_stats} week ({@code weekFinal} false). It deliberately
     * does NOT follow the weekly report's league-side week: Sleeper finalizes a league's week
     * later than its stats settle. {@code sport_week_stats} (not the league's stored weeks) is
     * the source because the sections read per-game rows, which are fetched per sport-week.
     * Nothing stored gives {@code NO_WEEK_SCORED}.
     *
     * <p><b>NIGHT</b> (research R6): the latest game date whose week was fetched at or after D+1
     * at {@link #NIGHT_COMPLETE_HOUR_UTC}:00 UTC. A later date that has rows but is not yet
     * complete is reported as {@code laterNightInProgress} rather than hidden.
     *
     * @param now a night whose cutoff is still in the future is never complete, so a row stamped
     *            by a skewed clock cannot promote a night early
     */
    static PeriodChoice choosePeriod(boolean multiplePerPeriod,
                                     List<PlayerGameRepository.DateRow> dates,
                                     Map<Integer, Instant> fetchedAtByWeek,
                                     Map<Integer, Boolean> finalByWeek, Instant now) {
        if (!multiplePerPeriod) {
            int newestFinal = 0;
            int newestStored = 0;
            for (Map.Entry<Integer, Boolean> e : finalByWeek.entrySet()) {
                newestStored = Math.max(newestStored, e.getKey());
                if (Boolean.TRUE.equals(e.getValue())) newestFinal = Math.max(newestFinal, e.getKey());
            }
            if (newestFinal > 0) return new PeriodChoice(Period.week(newestFinal, true), null, null);
            if (newestStored > 0) return new PeriodChoice(Period.week(newestStored, false), null, null);
            return new PeriodChoice(null, NO_WEEK_SCORED, null);
        }
        if (dates.isEmpty()) return new PeriodChoice(null, NO_GAMES_YET, null);

        Set<LocalDate> distinct = new HashSet<>();
        for (PlayerGameRepository.DateRow d : dates) distinct.add(d.gameDate());
        List<LocalDate> newestFirst = new ArrayList<>(distinct);
        newestFirst.sort(Comparator.reverseOrder());

        for (LocalDate date : newestFirst) {
            if (!isComplete(date, dates, fetchedAtByWeek, now)) continue;
            int games = dates.stream().filter(d -> d.gameDate().equals(date))
                    .mapToInt(PlayerGameRepository.DateRow::gamesCount).sum();
            LocalDate newest = newestFirst.getFirst();
            return new PeriodChoice(Period.night(date, games), null, newest.isAfter(date) ? newest : null);
        }
        // Dates exist but none is complete: the newest is "in progress" by the contract's wording
        // (a later date has rows but is not complete), so say which one rather than leave the
        // reader with only "no complete night".
        return new PeriodChoice(null, NO_COMPLETE_NIGHT_YET, newestFirst.getFirst());
    }

    /** Every week the date's rows belong to must have been fetched at or after the cutoff. */
    private static boolean isComplete(LocalDate date, List<PlayerGameRepository.DateRow> dates,
                                      Map<Integer, Instant> fetchedAtByWeek, Instant now) {
        Instant cutoff = date.plusDays(1).atTime(LocalTime.of(NIGHT_COMPLETE_HOUR_UTC, 0))
                .toInstant(ZoneOffset.UTC);
        if (cutoff.isAfter(now)) return false;
        for (PlayerGameRepository.DateRow d : dates) {
            if (!d.gameDate().equals(date)) continue;
            Instant fetched = fetchedAtByWeek.get(d.week());
            if (fetched == null || fetched.isBefore(cutoff)) return false;
        }
        return true;
    }
}
