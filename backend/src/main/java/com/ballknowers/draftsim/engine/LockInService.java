package com.ballknowers.draftsim.engine;

import com.ballknowers.draftsim.config.LockInProperties;
import com.ballknowers.draftsim.config.PlayerTrendsProperties;
import com.ballknowers.draftsim.domain.Player;
import com.ballknowers.draftsim.domain.Sport;
import com.ballknowers.draftsim.engine.CreditedGames.Kind;
import com.ballknowers.draftsim.ingest.NextGames;
import com.ballknowers.draftsim.ingest.SleeperClient;
import com.ballknowers.draftsim.ingest.SportSchedule;
import com.ballknowers.draftsim.store.JsonUtil;
import com.ballknowers.draftsim.store.LeagueMemberRepository;
import com.ballknowers.draftsim.store.LeagueRepository;
import com.ballknowers.draftsim.store.LeagueRepository.LeagueRow;
import com.ballknowers.draftsim.store.PlayerGameRepository;
import com.ballknowers.draftsim.store.PlayerGameRepository.SeasonGame;
import com.ballknowers.draftsim.store.PlayerGameRepository.TeamGame;
import com.ballknowers.draftsim.store.PlayerRepository;
import com.ballknowers.draftsim.store.ProjectionSnapshotRepository;
import com.ballknowers.draftsim.store.RosterSeasonRepository;
import com.ballknowers.draftsim.store.RosterWeekPointsRepository;
import com.ballknowers.draftsim.store.RosterWeekPointsRepository.WeekBreakdown;
import com.ballknowers.draftsim.store.SportScheduleRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.Set;
import java.util.function.Function;

/**
 * What margin above his baseline each NBA manager locked at (specs/044-lock-in-threshold US1, plan as amended
 * after review). In a lock-in league a manager may freeze a starter's score after one of his games in a
 * multi-game week; Sleeper does not publish which game, so {@link CreditedGames} infers it from the credited
 * points. A credit on a game other than the week's last is a <b>provable lock</b>: a later game existed.
 *
 * <p>Read-only and computed on read, cached by a fingerprint of every input (F6). The game set is raw
 * {@code player_game} (via {@link SeasonBoxCache}) minus the All-Star game; the Cup final is <b>kept</b>, because
 * Sleeper's fantasy week credits it (F1/F2), so {@code weights.yml}'s excluded-game-ids are deliberately not
 * applied here, the stand-in included. Only FINAL weeks count, and for a league still being played also not the
 * current week (F7).
 *
 * <p>Two baselines, never combined (FR-004): the player's season-to-date average before the locked game
 * ({@link #KIND_STAND_IN}, a stand-in and not a projection), and the frozen pre-game projection
 * ({@link #KIND_PROJECTION}), both sides of which are scored on spec 036's restricted map so a bonus category the
 * projection cannot carry does not build a positive margin into every lock.
 *
 * <p><b>Thresholds (US4).</b> {@link LockThresholds#fit} runs over the per-game margins of <b>every completed
 * lock-in season in the league's chain, the league's own season included when it is complete</b> (so league 211's
 * thresholds are learned from 2024 + 2025, the same as the 2026 league's; {@code from} publishes the seasons so the
 * choice is visible). The population is every starter's played game in a final multi-game week with at least
 * min-prior-games earlier games, locked or not (research R8). A chain season whose own report is not MEASURED (not
 * lock-in mode, share below threshold) contributes nothing.
 *
 * <p><b>Live calls (US4).</b> For a league still being played, each starter's most recent game this week is
 * judged: Lock iff its margin reaches the threshold for the games still to come. The live gate is the static gate
 * plus "the chain has a completed measured season" (thresholds status OK): a first-week league has no scored week
 * of its own to run F8's share check on, so the seasons its thresholds come from are what vouch for it.
 *
 * <p>The payoff grid (US3) and thresholds ride on a MEASURED report; thresholds and live also ride on
 * NO_SCORED_WEEKS, because that is exactly the state of a season's first week, when the live calls matter most.
 */
@Service
public class LockInService {

    public static final String STATUS_MEASURED = "MEASURED";
    public static final String STATUS_NOT_SLEEPER = "NOT_SLEEPER";
    public static final String STATUS_NOT_NBA = "NOT_NBA";
    public static final String STATUS_GAME_MODE = "GAME_MODE";
    public static final String STATUS_NO_SCORING = "NO_SCORING";
    public static final String STATUS_NO_SCORED_WEEKS = "NO_SCORED_WEEKS";
    public static final String STATUS_SHARE_BELOW_THRESHOLD = "SHARE_BELOW_THRESHOLD";

    public static final String KIND_STAND_IN = "STAND_IN";
    public static final String KIND_PROJECTION = "PROJECTION";

    /**
     * Every record count is over starter-weeks in final weeks. {@code multiGame} is the sum of the six
     * multi-game classes (lock, rodeOut, rodeOutMissed, tieNonLast, tieWithLast, unmatched).
     * {@code noBaselineStandIn} counts locks with no stand-in margin (fewer than min-prior-games earlier games, or
     * an average of 0 or less); {@code noSnapshot} locks with no frozen projection; {@code nonPositiveProjection}
     * locks whose projection scored 0 or less on the restricted map (no margin is formed from it).
     */
    public record Totals(int multiGame, int lock, int rodeOut, int rodeOutMissed, int tieNonLast, int tieWithLast,
                         int unmatched, int singleGame, int noGames, int noBaselineStandIn, int noSnapshot,
                         int nonPositiveProjection) {}

    /** @param median / q1 / q3 null below {@code minLocks} margins; {@code n} is always the margin count */
    public record BaselineFigure(int n, Double median, Double q1, Double q3) {}

    /**
     * One manager (key = manager id, or {@code "roster-N"} for an unowned roster) or one player (key = Sleeper
     * player id). {@code rodeOut} counts unique-last-game credits, {@code tieNonLast} locks of unknown game; both
     * are baseline-independent. {@code avatarId} is a manager's and null for a player.
     */
    public record LockSummary(String key, String name, String avatarId, BaselineFigure standIn,
                              BaselineFigure projection, int rodeOut, int tieNonLast) {}

    /** @param rateLast riding it out beat the lock; @param rateAny any later game did (US3 fills these) */
    public record PayoffCell(String band, int gamesLeft, int n, Double rateLast, Double rateAny) {}

    /** @param baseline null with too few prior games; @param margin null when baseline is null or 0 or less */
    public record StandInFigure(Double baseline, Double margin) {}

    /**
     * Both points on the restricted scoring map.
     *
     * @param margin null when the projection scored 0 or less
     */
    public record ProjectionFigure(double projectedPoints, double actualRestricted, Double margin) {}

    /**
     * One provable lock. {@code points} is the credited value under full league scoring; {@code lastGame} the
     * week's last played game under the same scoring. {@code beatenLater} is any later game above {@code points};
     * {@code beatenByLast} is the last game above it. {@code manager} and {@code player} are nullable.
     */
    public record Lock(int week, boolean playoff, Long managerId, String manager, int rosterId, String playerId,
                       String player, LocalDate gameDate, String gameId, double points, StandInFigure standIn,
                       ProjectionFigure projection, int laterGames, double lastGame, double bestLater,
                       boolean beatenByLast, boolean beatenLater) {}

    /** @param threshold a margin as a fraction; @param n games in the distribution it was fitted on (US4) */
    public record ThresholdPoint(int gamesLeft, double threshold, int n) {}

    /** @param status OK | NO_HISTORY; @param from seasons pooled (US4) */
    public record LockInThresholds(String status, List<Integer> from, Double pMissed,
                                   List<ThresholdPoint> byGamesLeft) {}

    public record LatestGame(String gameId, LocalDate gameDate, double points) {}

    /**
     * One starter's call on his latest played game this week (US4). {@code call} is LOCK | WAIT | TOO_EARLY;
     * {@code baseline}, {@code margin}, {@code gamesLeft} and {@code threshold} are null when there is none.
     */
    public record LiveCall(int rosterId, Long managerId, String manager, String playerId, String player, String team,
                           LatestGame latestGame, Double baseline, String baselineKind, Double margin,
                           Integer gamesLeft, Double threshold, String call) {}

    /** @param status OK | NOT_LIVE | ROSTERS_FAILED | NO_HISTORY (US4) */
    public record LiveCalls(Integer week, LocalDate asOf, String status, List<LiveCall> calls) {}

    /**
     * @param status   one of the STATUS_ constants; every other field is empty or zero unless MEASURED, except
     *                 {@code totals} which a SHARE_BELOW_THRESHOLD report keeps so the refusal can be checked
     * @param payoff   {@link #KIND_STAND_IN} / {@link #KIND_PROJECTION} to cells (empty until US3)
     * @param locks    only when a player was asked for; otherwise empty to keep the payload small
     * @param excludedCategories scored categories no projection line carries, graded on neither side
     */
    public record LockInReport(String sport, int season, String status, String reason, int minLocks, int minCell,
                               List<Double> bands, int gamesLeftCap, int minPriorGames, int weeksCovered,
                               Totals totals, List<LockSummary> managers, List<LockSummary> players,
                               Map<String, List<PayoffCell>> payoff, List<Lock> locks,
                               List<String> excludedCategories, List<String> caveats, LockInThresholds thresholds,
                               LiveCalls live) {}

    private static final Logger log = LoggerFactory.getLogger(LockInService.class);

    public static final String BASELINE_LAST_SEASON = "LAST_SEASON";
    public static final String CALL_LOCK = "LOCK";
    public static final String CALL_WAIT = "WAIT";
    public static final String CALL_TOO_EARLY = "TOO_EARLY";
    public static final String LIVE_OK = "OK";
    public static final String LIVE_NOT_LIVE = "NOT_LIVE";
    public static final String LIVE_ROSTERS_FAILED = "ROSTERS_FAILED";
    public static final String LIVE_NO_HISTORY = "NO_HISTORY";
    public static final String THRESHOLDS_OK = "OK";
    public static final String THRESHOLDS_NO_HISTORY = "NO_HISTORY";

    private final SeasonBoxCache boxCache;
    private final RosterWeekPointsRepository weekPoints;
    private final ScoredWeeks scoredWeeks;
    private final LeagueRepository leagues;
    private final RosterSeasonRepository rosterSeasons;
    private final LeagueMemberRepository members;
    private final PlayerRepository players;
    private final ProjectionSnapshotRepository snapshots;
    private final GameScoringService gameScoring;
    private final LockInProperties props;
    private final PlayerTrendsProperties trends;
    private final SportScheduleRepository schedules;
    private final LeagueRosterSource rosterSource;
    private final Clock clock;

    @Autowired
    public LockInService(SeasonBoxCache boxCache, RosterWeekPointsRepository weekPoints, ScoredWeeks scoredWeeks,
                         LeagueRepository leagues, RosterSeasonRepository rosterSeasons,
                         LeagueMemberRepository members, PlayerRepository players,
                         ProjectionSnapshotRepository snapshots, GameScoringService gameScoring,
                         LockInProperties props, PlayerTrendsProperties trends, SportScheduleRepository schedules,
                         SleeperClient sleeper, Clock clock) {
        this.boxCache = boxCache;
        this.weekPoints = weekPoints;
        this.scoredWeeks = scoredWeeks;
        this.leagues = leagues;
        this.rosterSeasons = rosterSeasons;
        this.members = members;
        this.players = players;
        this.snapshots = snapshots;
        this.gameScoring = gameScoring;
        this.props = props;
        this.trends = trends;
        this.schedules = schedules;
        this.rosterSource = new LeagueRosterSource(sleeper, leagues, rosterSeasons);
        this.clock = clock;
    }

    /** For the pure-computation tests, which touch none of the repositories. */
    LockInService(SeasonBoxCache boxCache, RosterWeekPointsRepository weekPoints, ScoredWeeks scoredWeeks,
                  LeagueRepository leagues, RosterSeasonRepository rosterSeasons, LeagueMemberRepository members,
                  PlayerRepository players, ProjectionSnapshotRepository snapshots, GameScoringService gameScoring,
                  LockInProperties props, PlayerTrendsProperties trends) {
        this(boxCache, weekPoints, scoredWeeks, leagues, rosterSeasons, members, players, snapshots, gameScoring,
                props, trends, null, null, Clock.systemUTC());
    }

    /**
     * What the thresholds are fitted on, per league-season: every candidate game's margin against its stand-in
     * (a starter's played game in a final multi-game week with enough prior games, locked or not), and the two
     * counts behind p. Empty unless the season's report is MEASURED.
     */
    record Pool(double[] margins, int rodeOut, int rodeOutMissed) {
        static final Pool EMPTY = new Pool(new double[0], 0, 0);
    }

    /** A computation, and the lock list and threshold pool it came from (the report carries the locks only for one player). */
    record Computed(LockInReport report, List<Lock> allLocks, Pool pool) {
        Computed(LockInReport report, List<Lock> allLocks) {
            this(report, allLocks, Pool.EMPTY);
        }
    }

    private record Cached(String stamp, Computed result) {}

    /**
     * Cached by what it read, not by time (plan review F6): a changed input is a different key, so an answer
     * cannot outlive its data. Bounded like {@code ProjectionAccuracyService}'s.
     */
    private final Map<Long, Cached> cache = Collections.synchronizedMap(
            new LinkedHashMap<>(16, 0.75f, true) {
                @Override
                protected boolean removeEldestEntry(Map.Entry<Long, Cached> eldest) {
                    return size() > 64;
                }
            });

    private record LiveCached(Instant at, LiveCalls calls) {}

    /** Live calls are cached by time, not by input: the Sleeper roster is an input nobody can fingerprint cheaply. */
    private final Map<String, LiveCached> liveCache = Collections.synchronizedMap(
            new LinkedHashMap<>(16, 0.75f, true) {
                @Override
                protected boolean removeEldestEntry(Map.Entry<String, LiveCached> eldest) {
                    return size() > 64;
                }
            });

    /**
     * @param playerFilter a Sleeper player id: the response then carries only that player's summary and his
     *                     {@code locks} (F14), never the whole report; null or blank for the league report
     * @param live         whether to make the live Lock/Wait calls (a Sleeper roster read, cached briefly); only a
     *                     league still being played has any
     */
    public LockInReport report(LeagueRow league, String playerFilter, boolean live) {
        Computed c = computedOf(league);
        LockInReport base = view(c, playerFilter);
        String status = base.status();
        if (!STATUS_MEASURED.equals(status) && !STATUS_NO_SCORED_WEEKS.equals(status)) return base;
        LockInThresholds th = thresholds(league);
        LiveCalls lc = null;
        if (live && !league.complete() && leagues.currentLeg(league.id()).isPresent()) {
            LocalDate today = NextGames.today(clock);
            lc = liveCalls(league, today, today, th);
            if (playerFilter != null && !playerFilter.isBlank()) {          // F14: only that player's call, if any
                lc = new LiveCalls(lc.week(), lc.asOf(), lc.status(),
                        lc.calls().stream().filter(call -> playerFilter.equals(call.playerId())).toList());
            }
        }
        return new LockInReport(base.sport(), base.season(), base.status(), base.reason(), base.minLocks(),
                base.minCell(), base.bands(), base.gamesLeftCap(), base.minPriorGames(), base.weeksCovered(),
                base.totals(), base.managers(), base.players(), base.payoff(), base.locks(),
                base.excludedCategories(), base.caveats(), th, lc);
    }

    /** The league's own computation (the report without thresholds or live), cached on its input fingerprint. */
    Computed computedOf(LeagueRow league) {
        OptionalInt mode = league.isSleeper() && league.sport() == Sport.NBA ? leagues.gameMode(league.id())
                : OptionalInt.empty();
        Map<String, Double> scoring = league.isSleeper() && league.sport() == Sport.NBA
                ? leagues.scoringOf(league.id()) : Map.of();
        Optional<Computed> refused = gate(league, mode, scoring);
        if (refused.isPresent()) return refused.get();

        ScoredWeeks.Snapshot sw = scoredWeeks.of(league.id());
        Set<Integer> weeks = scorable(sw.finalWeeks(), league.complete(), leagues.currentLeg(league.id()));
        if (weeks.isEmpty()) {
            return refusal(league, STATUS_NO_SCORED_WEEKS, "No final scored weeks are stored for this league yet.");
        }

        List<RosterSeasonRepository.StandingRow> standings = rosterSeasons.forLeague(league.id());
        Map<Integer, RosterOwners.RosterOwner> owners = RosterOwners.ownerNames(standings, members.forLeague(league.id()), null);
        Map<Integer, Long> managerByRoster = new HashMap<>();
        for (RosterSeasonRepository.StandingRow s : standings) managerByRoster.put(s.rosterId(), s.managerId());
        Optional<LeagueRepository.PlayoffFormat> playoff = leagues.playoffFormat(league.id());

        String stamp = String.join("#",
                String.valueOf(boxCache.token(Sport.NBA, league.season())),
                weekPoints.digest(league.id()),
                new java.util.TreeSet<>(weeks).toString(),
                snapshots.stamp(league.sport().code(), league.season()),
                String.valueOf(scoring.hashCode()),
                String.valueOf(mode.getAsInt()),
                String.valueOf(playoff.map(LeagueRepository.PlayoffFormat::playoffWeekStart).orElse(0)),
                String.valueOf(managerByRoster.hashCode()),
                String.valueOf(owners.hashCode()));
        Cached hit = cache.get(league.id());
        if (hit != null && hit.stamp().equals(stamp)) return hit.result();

        SeasonBoxCache.Season box = boxCache.get(Sport.NBA, league.season());
        Map<Integer, String> rosterNames = new HashMap<>();
        Map<Integer, String> rosterAvatars = new HashMap<>();
        owners.forEach((rosterId, o) -> {
            rosterNames.put(rosterId, o.name());
            rosterAvatars.put(rosterId, o.avatarId());
        });
        Function<Collection<String>, Map<String, String>> names = ids -> {
            Map<String, String> out = new HashMap<>();
            for (Player p : players.byIds(Sport.NBA, ids).values()) out.put(p.sleeperId(), p.name());
            return out;
        };
        Inputs in = new Inputs(league, mode, scoring, weeks, box.games(), box.teamGames(),
                weekPoints.breakdownsFor(league.id(), league.season()), managerByRoster, rosterNames, rosterAvatars,
                playoff.map(LeagueRepository.PlayoffFormat::playoffWeekStart).orElse(0),
                snapshots.lines(league.sport().code(), league.season()), trends.oneGameShare(), names);
        Computed fresh = compute(in);
        cache.put(league.id(), new Cached(stamp, fresh));
        return fresh;
    }

    // ---- thresholds (US4) ----

    /**
     * The Lock/Wait thresholds (FR-013), pooled over every completed, MEASURED lock-in season in the chain,
     * this league's own included when it is complete. {@code NO_HISTORY} when there is none. Cheap when warm: each
     * season's computation is cached, and the fit is four passes over ~13k doubles.
     */
    public LockInThresholds thresholds(LeagueRow league) {
        List<Integer> from = new ArrayList<>();
        List<double[]> pools = new ArrayList<>();
        int rodeOut = 0;
        int missed = 0;
        int total = 0;
        for (LeagueRow row : leagues.chainBySleeperId(league.sleeperId())) {
            if (!row.complete() || !row.isSleeper() || row.sport() != Sport.NBA) continue;
            Computed c = computedOf(row);
            if (!STATUS_MEASURED.equals(c.report().status())) continue;
            from.add(row.season());
            pools.add(c.pool().margins());
            total += c.pool().margins().length;
            rodeOut += c.pool().rodeOut();
            missed += c.pool().rodeOutMissed();
        }
        if (total == 0) return new LockInThresholds(THRESHOLDS_NO_HISTORY, List.of(), null, List.of());
        double[] all = new double[total];
        int at = 0;
        for (double[] p : pools) {
            System.arraycopy(p, 0, all, at, p.length);
            at += p.length;
        }
        Collections.sort(from);
        double pMissed = rodeOut + missed == 0 ? 0.0 : (double) missed / (rodeOut + missed);
        double[] v = LockThresholds.fit(all, pMissed, props.thresholdCap());
        List<ThresholdPoint> points = new ArrayList<>();
        for (int k = 1; k <= props.thresholdCap(); k++) points.add(new ThresholdPoint(k, v[k], total));
        return new LockInThresholds(THRESHOLDS_OK, List.copyOf(from), pMissed, List.copyOf(points));
    }

    // ---- live calls (US4) ----

    /** Whether this league is a Sleeper NBA lock-in league with stored scoring (the static part of the gate). */
    public boolean isLockInLeague(LeagueRow league) {
        if (!league.isSleeper() || league.sport() != Sport.NBA) return false;
        return gate(league, leagues.gameMode(league.id()), leagues.scoringOf(league.id())).isEmpty();
    }

    /** The live calls as of {@code asOf}, judged on games played through {@code asOf} (the live endpoint's form). */
    public LiveCalls liveCalls(LeagueRow league, LocalDate asOf) {
        return liveCalls(league, asOf, asOf, thresholds(league));
    }

    /**
     * The digest's form: the games judged are those through {@code playedThrough} (the digest's night) and the
     * games left are those on or after {@code asOf} (the night after), so a player's later games the same week are
     * never confused with the night being reported.
     */
    public LiveCalls liveCalls(LeagueRow league, LocalDate asOf, LocalDate playedThrough) {
        return liveCalls(league, asOf, playedThrough, thresholds(league));
    }

    private LiveCalls liveCalls(LeagueRow league, LocalDate asOf, LocalDate playedThrough, LockInThresholds th) {
        OptionalInt leg = leagues.currentLeg(league.id());
        if (league.complete() || leg.isEmpty() || !isLockInLeague(league)) {
            return new LiveCalls(leg.isPresent() ? leg.getAsInt() : null, asOf, LIVE_NOT_LIVE, List.of());
        }
        int week = leg.getAsInt();
        if (th == null || !THRESHOLDS_OK.equals(th.status())) {
            return new LiveCalls(week, asOf, LIVE_NO_HISTORY, List.of());
        }
        String key = league.id() + "|" + asOf + "|" + playedThrough;
        LiveCached hit = liveCache.get(key);
        Instant now = clock.instant();
        if (hit != null) {
            // A failure is remembered for the short negative TTL only, so an outage is not one Sleeper call per request.
            int ttl = LIVE_OK.equals(hit.calls().status()) ? props.liveTtlSeconds() : props.liveFailureTtlSeconds();
            if (now.isBefore(hit.at().plusSeconds(ttl))) return hit.calls();
        }
        LiveCalls fresh = computeLive(league, week, asOf, playedThrough, th);
        liveCache.put(key, new LiveCached(now, fresh));
        return fresh;
    }

    /**
     * The week the live calls are about: the schedule's week for the first counted game on or after
     * {@code day} (the week containing it, or the next one across a gap), so a league row whose {@code leg} has
     * not been refreshed yet cannot make Monday read as last week. Falls back to {@code legWeek} without a schedule.
     */
    static int liveWeek(List<SportSchedule.Game> schedule, LocalDate day, int legWeek) {
        if (schedule == null || schedule.isEmpty()) return legWeek;
        java.util.function.Predicate<SportSchedule.Game> exhibition = ScheduleGridService.exhibitionGames(schedule);
        SportSchedule.Game first = null;
        for (SportSchedule.Game g : schedule) {
            if (g.date() == null || g.date().isBefore(day) || !SportSchedule.counts(g.status()) || exhibition.test(g)) continue;
            if (first == null || g.date().isBefore(first.date())) first = g;
        }
        return first == null ? legWeek : first.week();
    }

    private LiveCalls computeLive(LeagueRow league, int legWeek, LocalDate asOf, LocalDate playedThrough,
                                  LockInThresholds th) {
        List<SportSchedule.Game> seasonSchedule = schedules == null ? List.of()
                : schedules.forSeason(league.sport().code(), league.season());
        int week = liveWeek(seasonSchedule, playedThrough, legWeek);
        List<Map<String, Object>> rosters;
        try {
            rosters = rosterSource.rosters(league);
        } catch (RuntimeException e) {
            log.warn("lock-ins: live rosters for league {} failed: {}", league.sleeperId(), e.toString());
            return new LiveCalls(week, asOf, LIVE_ROSTERS_FAILED, List.of());
        }
        if (rosters == null || rosters.isEmpty()) return new LiveCalls(week, asOf, LIVE_ROSTERS_FAILED, List.of());

        Map<String, Double> scoring = leagues.scoringOf(league.id());
        List<RosterSeasonRepository.StandingRow> standings = rosterSeasons.forLeague(league.id());
        Map<Integer, RosterOwners.RosterOwner> owners = RosterOwners.ownerNames(standings, members.forLeague(league.id()), null);
        Map<Integer, Long> managerByRoster = new HashMap<>();
        for (RosterSeasonRepository.StandingRow s : standings) managerByRoster.put(s.rosterId(), s.managerId());

        Map<String, Integer> rosterOf = new LinkedHashMap<>();       // starter id -> roster id
        for (Map<String, Object> r : rosters) {
            if (!(r.get("roster_id") instanceof Number rn) || !(r.get("starters") instanceof List<?> st)) continue;
            for (Object o : st) {
                if (o == null) continue;
                String pid = String.valueOf(o);
                if (!pid.isBlank() && !"0".equals(pid)) rosterOf.putIfAbsent(pid, rn.intValue());
            }
        }
        Map<String, Player> byId = players.byIds(Sport.NBA, rosterOf.keySet());

        SeasonBoxCache.Season box = boxCache.get(Sport.NBA, league.season());
        Set<String> codes = NbaGameLines.seasonTeamCodes(box.teamGames());
        Map<String, PlayerGames> thisSeason = gamesByPlayer(box.games(), codes, rosterOf.keySet()::contains, scoring);

        // Last season's full average, from the nearest earlier season in the chain: read only if somebody needs it.
        Map<String, PlayerGames> lastSeason = null;
        List<LiveCall> calls = new ArrayList<>();
        for (Map.Entry<String, Integer> e : rosterOf.entrySet()) {
            String pid = e.getKey();
            PlayerGames pg = thisSeason.get(pid);
            if (pg == null) continue;
            List<Game> wk = pg.byWeek().getOrDefault(week, List.of());
            Game latest = null;
            for (Game g : wk) if (!g.date().isAfter(playedThrough)) latest = g;   // wk is chronological
            if (latest == null) continue;
            Player p = byId.get(pid);
            String team = p == null ? null : p.team();
            if (team == null) continue;
            // B1: only a finished game is judged. B2: and only while it is still the game that can be locked.
            if (!lockableLatest(seasonSchedule, team, latest, week, asOf)) continue;
            int left = 0;
            for (SportSchedule.Game g : NextGames.forTeam(seasonSchedule, team, asOf, Integer.MAX_VALUE)) {
                if (g.week() == week && g.date().isAfter(latest.date()) && !STATUS_IN_GAME.equals(g.status())) left++;
            }
            if (left == 0) continue;                                            // nothing left to wait for: no decision

            Double baseline = standInBaseline(pg, latest);
            String kind = baseline == null ? null : KIND_STAND_IN;
            if (baseline == null) {
                if (lastSeason == null) lastSeason = lastSeasonGames(league, rosterOf.keySet(), scoring);
                PlayerGames prev = lastSeason.get(pid);
                if (prev != null && prev.all().size() >= props.minPriorGames()) {
                    double sum = 0;
                    for (Game g : prev.all()) sum += g.score();
                    baseline = sum / prev.all().size();
                    kind = BASELINE_LAST_SEASON;
                }
            }
            int rosterId = e.getValue();
            RosterOwners.RosterOwner owner = owners.get(rosterId);
            LatestGame lg = new LatestGame(latest.gameId(), latest.date(), latest.score());
            Long managerId = managerByRoster.get(rosterId);
            String manager = owner == null ? null : owner.name();
            String name = p == null ? null : p.name();
            if (baseline == null || baseline <= 0) {
                calls.add(new LiveCall(rosterId, managerId, manager, pid, name, team, lg, null, null, null, left, null,
                        CALL_TOO_EARLY));
                continue;
            }
            double margin = (latest.score() - baseline) / baseline;
            int k = Math.min(left, props.thresholdCap());
            double threshold = th.byGamesLeft().get(k - 1).threshold();
            calls.add(new LiveCall(rosterId, managerId, manager, pid, name, team, lg, baseline, kind, margin, left,
                    threshold, margin >= threshold ? CALL_LOCK : CALL_WAIT));
        }
        calls.sort(Comparator.comparing((LiveCall c) -> c.margin(), Comparator.nullsLast(Comparator.<Double>reverseOrder()))
                .thenComparing(LiveCall::playerId));
        return new LiveCalls(week, asOf, LIVE_OK, List.copyOf(calls));
    }

    private static final String STATUS_COMPLETE = "complete";
    private static final String STATUS_IN_GAME = "in_game";

    /**
     * Whether the player's latest box game is one that can still be locked (code review B1, B2; only the latest
     * game can be locked, plan amendment 6): the game must be over (its schedule row is {@code complete}, matched by
     * game id, else by team and date), and the team must not have started another game since, up to {@code asOf}
     * (a started game means the earlier score is gone, whether or not the player has a box row for it). A call
     * failing this is omitted, the same "no decision" answer as a team with nothing left.
     */
    static boolean lockableLatest(List<SportSchedule.Game> schedule, String team, Game latest, int week, LocalDate asOf) {
        SportSchedule.Game judged = null;
        for (SportSchedule.Game g : schedule) {
            if (latest.gameId() != null && latest.gameId().equals(g.gameId())) { judged = g; break; }
        }
        if (judged == null) {
            for (SportSchedule.Game g : schedule) {
                if (latest.date().equals(g.date()) && (team.equals(g.home()) || team.equals(g.away()))) { judged = g; break; }
            }
        }
        if (judged == null || !STATUS_COMPLETE.equals(judged.status())) return false;
        for (SportSchedule.Game g : schedule) {
            if (g.week() != week || g.date() == null || !(team.equals(g.home()) || team.equals(g.away()))) continue;
            if (!g.date().isAfter(latest.date()) || g.date().isAfter(asOf)) continue;
            // A row dated strictly before asOf has been played whatever its stored status says: nothing refreshes
            // schedule status reliably, so a stale pre_game must not keep the earlier game lockable (P4).
            boolean stale = g.date().isBefore(asOf) && SportSchedule.counts(g.status());
            if (stale || STATUS_COMPLETE.equals(g.status()) || STATUS_IN_GAME.equals(g.status())) return false;
        }
        return true;
    }

    /** The nearest earlier season of the league's chain, scored under THIS league's scoring so the margin compares like with like. */
    private Map<String, PlayerGames> lastSeasonGames(LeagueRow league, Set<String> pids, Map<String, Double> scoring) {
        LeagueRow prev = null;
        for (LeagueRow row : leagues.chainBySleeperId(league.sleeperId())) {
            if (row.sport() == Sport.NBA && row.season() < league.season()
                    && (prev == null || row.season() > prev.season())) {
                prev = row;
            }
        }
        if (prev == null) return Map.of();
        SeasonBoxCache.Season box = boxCache.get(Sport.NBA, prev.season());
        return gamesByPlayer(box.games(), NbaGameLines.seasonTeamCodes(box.teamGames()), pids::contains, scoring);
    }

    // ---- gate and week set (pure, package-private for tests) ----

    /**
     * The gate (plan review F8), in order. Empty means "carry on". The share check needs the computation and is
     * applied inside {@link #compute}.
     */
    Optional<Computed> gate(LeagueRow league, OptionalInt gameMode, Map<String, Double> scoring) {
        if (!league.isSleeper()) {
            return Optional.of(refusal(league, STATUS_NOT_SLEEPER,
                    "Lock-ins are read from Sleeper's credited points; this league isn't on Sleeper."));
        }
        if (league.sport() != Sport.NBA) {
            return Optional.of(refusal(league, STATUS_NOT_NBA, "Lock-ins apply to NBA leagues only."));
        }
        if (gameMode.isEmpty() || gameMode.getAsInt() != 1) {
            return Optional.of(refusal(league, STATUS_GAME_MODE,
                    "This NBA league isn't in lock-in mode (game_mode 1), or its mode isn't stored."));
        }
        if (scoring == null || scoring.isEmpty()) {
            return Optional.of(refusal(league, STATUS_NO_SCORING,
                    "This league's scoring settings aren't stored, so no game could be scored."));
        }
        return Optional.empty();
    }

    /**
     * The weeks that may be read: the final ones, and while the league is still being played also not the
     * current week (F7). {@code ScoredWeeks} treats a league with no POINTS fetch rows as all-final, which is
     * exactly the state a season's first week arrives in, and the in-progress week's "last game" is only the
     * latest game so far. An absent {@code leg} is not defaulted: nothing more is dropped.
     */
    static Set<Integer> scorable(Set<Integer> finalWeeks, boolean complete, OptionalInt currentLeg) {
        Set<Integer> out = new HashSet<>(finalWeeks);
        if (!complete && currentLeg.isPresent()) out.removeIf(w -> w >= currentLeg.getAsInt());
        return out;
    }

    private Computed refusal(LeagueRow league, String status, String reason) {
        return new Computed(new LockInReport(league.sport().code(), league.season(), status, reason, props.minLocks(),
                props.minCell(), props.marginBands(), props.gamesLeftCap(), props.minPriorGames(), 0, emptyTotals(),
                List.of(), List.of(), emptyPayoff(), List.of(), List.of(), List.of(), null, null), List.of());
    }

    private static Totals emptyTotals() {
        return new Totals(0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0);
    }

    private static Map<String, List<PayoffCell>> emptyPayoff() {
        Map<String, List<PayoffCell>> m = new LinkedHashMap<>();
        m.put(KIND_STAND_IN, List.of());
        m.put(KIND_PROJECTION, List.of());
        return m;
    }

    /** The league report, or for one player only his summary and locks. */
    static LockInReport view(Computed c, String playerFilter) {
        if (playerFilter == null || playerFilter.isBlank()) return c.report();
        LockInReport r = c.report();
        List<LockSummary> mine = r.players().stream().filter(p -> p.key().equals(playerFilter)).toList();
        List<Lock> locks = c.allLocks().stream().filter(l -> l.playerId().equals(playerFilter)).toList();
        return new LockInReport(r.sport(), r.season(), r.status(), r.reason(), r.minLocks(), r.minCell(), r.bands(),
                r.gamesLeftCap(), r.minPriorGames(), r.weeksCovered(), r.totals(), List.of(), mine, emptyPayoff(),
                locks, r.excludedCategories(), r.caveats(), r.thresholds(), r.live());
    }

    // ---- the computation ----

    /**
     * @param weeks              the weeks that may be read, already filtered by {@link #scorable}
     * @param games              raw {@code player_game} rows of the season (team-total rows excluded)
     * @param teamGames          the season's team rows, whose codes define "a team's game"
     * @param playoffWeekStart   0 when the league stores none
     * @param snapshotLines      every frozen projection line of the sport-season; empty before any capture
     * @param shareThreshold     {@code draftsim.player-trends.one-game-share}; null when not configured
     * @param names              player ids to display names; called once, with the players it will print
     */
    record Inputs(LeagueRow league, OptionalInt gameMode, Map<String, Double> scoring, Set<Integer> weeks,
                  List<SeasonGame> games, List<TeamGame> teamGames, List<WeekBreakdown> breakdowns,
                  Map<Integer, Long> managerByRoster, Map<Integer, String> rosterNames,
                  Map<Integer, String> rosterAvatars, int playoffWeekStart,
                  List<ProjectionSnapshotRepository.Line> snapshotLines, Double shareThreshold,
                  Function<Collection<String>, Map<String, String>> names) {}

    /** @param priorCount / priorSum the player's games dated strictly before this one (a same-day game is not "prior") */
    private record Game(String gameId, LocalDate date, int week, double score, Map<String, Object> stats,
                        int priorCount, double priorSum) {}

    private record PlayerGames(List<Game> all, Map<Integer, List<Game>> byWeek) {}

    /**
     * Every player's played games (the All-Star game and team-total rows left out; a game with no minutes is no
     * game), chronological by (date, game id), each scored under {@code scoring} and carrying its prior count and
     * sum. The ONE implementation of "prior games" for locks, candidates and live calls alike.
     */
    private Map<String, PlayerGames> gamesByPlayer(List<SeasonGame> games, Set<String> codes,
                                                   java.util.function.Predicate<String> wanted,
                                                   Map<String, Double> scoring) {
        Map<String, List<SeasonGame>> raw = new HashMap<>();
        for (SeasonGame g : games) {
            if (g.sleeperPlayerId() == null || g.sleeperPlayerId().startsWith(PlayerGameRepository.TEAM_ID_PREFIX)) continue;
            if (!wanted.test(g.sleeperPlayerId())) continue;
            if (!NbaGameLines.isTeamGame(g, codes)) continue;           // the All-Star game is no one's choice (F1)
            if (AdvancedStats.num(g.stats(), "sp") <= 0) continue;       // a DNP has no game
            raw.computeIfAbsent(g.sleeperPlayerId(), k -> new ArrayList<>()).add(g);
        }
        Comparator<SeasonGame> chrono = Comparator.comparing(SeasonGame::gameDate).thenComparing(SeasonGame::gameId);
        Map<String, PlayerGames> out = new HashMap<>();
        for (Map.Entry<String, List<SeasonGame>> e : raw.entrySet()) out.put(e.getKey(), playerGames(e.getValue(), chrono, scoring));
        return out;
    }

    private PlayerGames playerGames(List<SeasonGame> rawGames, Comparator<SeasonGame> chrono, Map<String, Double> scoring) {
        List<SeasonGame> sorted = new ArrayList<>(rawGames);
        sorted.sort(chrono);
        List<Game> all = new ArrayList<>(sorted.size());
        Map<Integer, List<Game>> byWeek = new HashMap<>();
        int count = 0;
        double sum = 0;
        int i = 0;
        while (i < sorted.size()) {
            LocalDate day = sorted.get(i).gameDate();
            int priorCount = count;
            double priorSum = sum;
            for (; i < sorted.size() && sorted.get(i).gameDate().equals(day); i++) {
                SeasonGame g = sorted.get(i);
                double score = gameScoring.score(scoring, g.stats());
                Game x = new Game(g.gameId(), g.gameDate(), g.week(), score, g.stats(), priorCount, priorSum);
                all.add(x);
                byWeek.computeIfAbsent(g.week(), w -> new ArrayList<>()).add(x);
                count++;
                sum += score;
            }
        }
        return new PlayerGames(all, byWeek);
    }

    /** His season average before {@code g}, or null with fewer than min-prior-games earlier games. */
    private Double standInBaseline(PlayerGames pg, Game g) {
        return g.priorCount() >= props.minPriorGames() ? g.priorSum() / g.priorCount() : null;
    }

    /** One lock with a margin, for the payoff grid. */
    private record Obs(double margin, int laterGames, boolean beatenByLast, boolean beatenLater) {}

    private static final class Acc {
        final String key;
        String name;
        String avatarId;
        final List<Double> standIn = new ArrayList<>();
        final List<Double> projection = new ArrayList<>();
        int locks;
        int rodeOut;
        int tieNonLast;

        Acc(String key) {
            this.key = key;
        }
    }

    Computed compute(Inputs in) {
        LeagueRow league = in.league();
        Optional<Computed> refused = gate(league, in.gameMode(), in.scoring());
        if (refused.isPresent()) return refused.get();
        if (in.weeks().isEmpty()) {
            return refusal(league, STATUS_NO_SCORED_WEEKS, "No final scored weeks are stored for this league yet.");
        }

        Map<String, Double> scoring = in.scoring();
        Set<String> codes = NbaGameLines.seasonTeamCodes(in.teamGames());
        Map<String, PlayerGames> scored = gamesByPlayer(in.games(), codes, pid -> true, scoring);
        Function<String, PlayerGames> gamesOf = pid -> scored.getOrDefault(pid, new PlayerGames(List.of(), Map.of()));

        // Spec 036's restricted map, built once from every frozen line of the sport-season (B3).
        Map<String, Map<String, Object>> snapshotByGame = new HashMap<>();
        Set<String> projectedKeys = new HashSet<>();
        for (ProjectionSnapshotRepository.Line l : in.snapshotLines()) {
            snapshotByGame.put(l.sleeperPlayerId() + '\u0000' + l.gameId(), l.stats());
            projectedKeys.addAll(l.stats().keySet());
        }
        Map<String, Double> graded = snapshotByGame.isEmpty() ? Map.of()
                : ProjectedCategories.restrict(scoring, projectedKeys);
        List<String> excluded = snapshotByGame.isEmpty() ? List.of()
                : ProjectedCategories.unprojected(scoring, projectedKeys);

        int[] kinds = new int[Kind.values().length];
        int noStandIn = 0;
        int noSnapshot = 0;
        int nonPositiveProjection = 0;
        Map<String, Acc> managers = new HashMap<>();
        Map<String, Acc> playerAccs = new HashMap<>();
        List<Lock> locks = new ArrayList<>();
        List<Double> candidates = new ArrayList<>();
        List<Obs> standInObs = new ArrayList<>();
        List<Obs> projectionObs = new ArrayList<>();

        for (WeekBreakdown w : in.breakdowns()) {
            if (!in.weeks().contains(w.week())) continue;
            Set<String> starters = WeeklyReportService.startersOf(w.startersJson());
            if (starters.isEmpty() || w.playersPointsJson() == null || w.playersPointsJson().isBlank()) continue;
            Map<String, Object> credited = JsonUtil.readMap(w.playersPointsJson());
            if (credited.isEmpty()) continue;
            Long managerId = in.managerByRoster().get(w.rosterId());
            String managerKey = managerId != null ? managerId.toString() : "roster-" + w.rosterId();
            Acc manager = managers.computeIfAbsent(managerKey, Acc::new);
            if (manager.name == null) {
                manager.name = in.rosterNames().get(w.rosterId());
                manager.avatarId = in.rosterAvatars().get(w.rosterId());
            }
            boolean playoff = in.playoffWeekStart() > 0 && w.week() >= in.playoffWeekStart();

            for (String pid : starters) {
                PlayerGames pg = gamesOf.apply(pid);
                List<Game> wk = pg.byWeek().getOrDefault(w.week(), List.of());
                // The threshold population (research R8): every game of a starter's multi-game week with a
                // stand-in, whether anyone locked it or not.
                if (wk.size() >= 2) {
                    for (Game g : wk) {
                        Double b = standInBaseline(pg, g);
                        if (b != null && b > 0) candidates.add((g.score() - b) / b);
                    }
                }
                if (!(credited.get(pid) instanceof Number num)) continue;
                double c = num.doubleValue();
                List<Double> scores = new ArrayList<>(wk.size());
                for (Game g : wk) scores.add(g.score());
                CreditedGames.Match m = CreditedGames.match(c, scores);
                kinds[m.kind().ordinal()]++;

                Acc player = playerAccs.computeIfAbsent(pid, Acc::new);
                switch (m.kind()) {
                    case RODE_OUT -> {
                        manager.rodeOut++;
                        player.rodeOut++;
                    }
                    case TIE_NON_LAST -> {
                        manager.tieNonLast++;
                        player.tieNonLast++;
                    }
                    case LOCK -> {
                        manager.locks++;
                        player.locks++;
                        Game lg = wk.get(m.index());
                        double bestLater = Double.NEGATIVE_INFINITY;
                        for (int i = m.index() + 1; i < wk.size(); i++) bestLater = Math.max(bestLater, wk.get(i).score());
                        double last = wk.get(wk.size() - 1).score();

                        // Stand-in: the mean of his games dated strictly before the locked game's date.
                        int later = wk.size() - 1 - m.index();
                        Double baseline = standInBaseline(pg, lg);
                        Double standInMargin = baseline != null && baseline > 0 ? (c - baseline) / baseline : null;
                        if (standInMargin == null) {
                            noStandIn++;
                        } else {
                            manager.standIn.add(standInMargin);
                            player.standIn.add(standInMargin);
                            standInObs.add(new Obs(standInMargin, later, last > c, bestLater > c));
                        }

                        ProjectionFigure projection = null;
                        Map<String, Object> line = snapshotByGame.get(pid + '\u0000' + lg.gameId());
                        if (line == null) {
                            noSnapshot++;
                        } else {
                            double projected = gameScoring.score(graded, line);
                            double actual = gameScoring.score(graded, lg.stats());   // re-scored, not players_points
                            Double margin = projected > 0 ? (actual - projected) / projected : null;
                            projection = new ProjectionFigure(projected, actual, margin);
                            if (margin == null) {
                                nonPositiveProjection++;
                            } else {
                                manager.projection.add(margin);
                                player.projection.add(margin);
                                projectionObs.add(new Obs(margin, later, last > c, bestLater > c));
                            }
                        }
                        locks.add(new Lock(w.week(), playoff, managerId, manager.name, w.rosterId(), pid, null,
                                lg.date(), lg.gameId(), c, new StandInFigure(baseline, standInMargin), projection,
                                later, last, bestLater, last > c, bestLater > c));
                    }
                    default -> { /* counted in totals only */ }
                }
            }
        }

        Totals totals = new Totals(
                kinds[Kind.LOCK.ordinal()] + kinds[Kind.RODE_OUT.ordinal()] + kinds[Kind.RODE_OUT_MISSED.ordinal()]
                        + kinds[Kind.TIE_NON_LAST.ordinal()] + kinds[Kind.TIE_WITH_LAST.ordinal()]
                        + kinds[Kind.UNMATCHED.ordinal()],
                kinds[Kind.LOCK.ordinal()], kinds[Kind.RODE_OUT.ordinal()], kinds[Kind.RODE_OUT_MISSED.ordinal()],
                kinds[Kind.TIE_NON_LAST.ordinal()], kinds[Kind.TIE_WITH_LAST.ordinal()],
                kinds[Kind.UNMATCHED.ordinal()], kinds[Kind.SINGLE_GAME.ordinal()], kinds[Kind.NO_GAMES.ordinal()],
                noStandIn, noSnapshot, nonPositiveProjection);

        // F8: this report's own share, on the same numerator Trends uses, against Trends' own threshold.
        if (in.shareThreshold() == null) {
            return withTotals(refusal(league, STATUS_SHARE_BELOW_THRESHOLD,
                    "The one-game share threshold (draftsim.player-trends.one-game-share) isn't configured, so this "
                            + "league can't be checked as a lock-in league."), totals);
        }
        int matched = totals.lock() + totals.rodeOut() + totals.tieNonLast() + totals.tieWithLast();
        if (totals.multiGame() == 0) {
            return withTotals(refusal(league, STATUS_SHARE_BELOW_THRESHOLD,
                    "No multi-game starter-weeks were found, so this league can't be checked as a lock-in league."),
                    totals);
        }
        double share = (double) matched / totals.multiGame();
        if (share < in.shareThreshold()) {
            return withTotals(refusal(league, STATUS_SHARE_BELOW_THRESHOLD, String.format(
                    "Only %.1f%% of multi-game starter-weeks are credited as exactly one game, below the %.1f%% a "
                            + "lock-in league needs, so no locks are inferred.", share * 100, in.shareThreshold() * 100)),
                    totals);
        }

        // Summaries. Managers: every roster that started anyone. Players: those with a lock of any kind.
        Set<String> printed = new HashSet<>();
        for (Acc a : playerAccs.values()) if (a.locks > 0 || a.tieNonLast > 0) printed.add(a.key);
        Map<String, String> playerNames = in.names().apply(printed);

        List<LockSummary> managerRows = new ArrayList<>();
        for (Acc a : managers.values()) managerRows.add(summary(a, a.name));
        managerRows.sort(Comparator.comparing((LockSummary s) -> s.standIn().median(),
                        Comparator.nullsLast(Comparator.<Double>reverseOrder()))
                .thenComparing(s -> -s.standIn().n()).thenComparing(LockSummary::key));
        List<LockSummary> playerRows = new ArrayList<>();
        for (String pid : printed) playerRows.add(summary(playerAccs.get(pid), playerNames.get(pid)));
        playerRows.sort(Comparator.comparing((LockSummary s) -> -s.standIn().n())
                .thenComparing(s -> s.name() == null ? "" : s.name()).thenComparing(LockSummary::key));

        List<Lock> namedLocks = new ArrayList<>(locks.size());
        for (Lock l : locks) {
            namedLocks.add(new Lock(l.week(), l.playoff(), l.managerId(), l.manager(), l.rosterId(), l.playerId(),
                    playerNames.get(l.playerId()), l.gameDate(), l.gameId(), l.points(), l.standIn(), l.projection(),
                    l.laterGames(), l.lastGame(), l.bestLater(), l.beatenByLast(), l.beatenLater()));
        }
        namedLocks.sort(Comparator.comparingInt(Lock::week).thenComparingInt(Lock::rosterId)
                .thenComparing(Lock::playerId));

        Map<String, List<PayoffCell>> payoff = new LinkedHashMap<>();
        payoff.put(KIND_STAND_IN, payoffGrid(standInObs));
        payoff.put(KIND_PROJECTION, payoffGrid(projectionObs));

        List<String> caveats = new ArrayList<>();
        caveats.add("Locks are inferred: Sleeper doesn't publish which game a manager locked. A starter credited a "
                + "game other than his last one that week is read as having locked it.");
        caveats.add("A starter nobody locks is credited his last scheduled game, so a credit on the last game is "
                + "not a choice and is never counted as a lock.");
        caveats.add("A later game means a later game the player actually played; a game he missed isn't visible.");
        caveats.add("Season-average stand-in is not a projection: it is the player's own average over his earlier "
                + "games this season (at least " + props.minPriorGames() + "), so early-season margins are noisy.");
        caveats.add("Describes this league's past weeks; not a prediction.");
        if (!excluded.isEmpty()) {
            caveats.add("Projection margins ignore categories no projection carries: " + String.join(", ", excluded) + ".");
        }

        LockInReport report = new LockInReport(league.sport().code(), league.season(), STATUS_MEASURED, null,
                props.minLocks(), props.minCell(), props.marginBands(), props.gamesLeftCap(), props.minPriorGames(),
                in.weeks().size(), totals, List.copyOf(managerRows), List.copyOf(playerRows), payoff, List.of(),
                excluded, List.copyOf(caveats), null, null);
        double[] margins = new double[candidates.size()];
        for (int i = 0; i < margins.length; i++) margins[i] = candidates.get(i);
        return new Computed(report, List.copyOf(namedLocks),
                new Pool(margins, kinds[Kind.RODE_OUT.ordinal()], kinds[Kind.RODE_OUT_MISSED.ordinal()]));
    }

    private static Computed withTotals(Computed c, Totals totals) {
        LockInReport r = c.report();
        return new Computed(new LockInReport(r.sport(), r.season(), r.status(), r.reason(), r.minLocks(), r.minCell(),
                r.bands(), r.gamesLeftCap(), r.minPriorGames(), r.weeksCovered(), totals, r.managers(), r.players(),
                r.payoff(), r.locks(), r.excludedCategories(), r.caveats(), r.thresholds(), r.live()), List.of());
    }

    // ---- payoff grid (US3) ----

    /**
     * Band x min(laterGames, games-left-cap), every cell present even at n = 0. {@code rateLast} (the headline) is
     * the share of locks where the week's last played game scored more than the lock, i.e. where riding it out beat
     * it; {@code rateAny} the share where any later played game did ("in hindsight"). Both null below min-cell.
     * Bands come from config edges: "<0", "0-10", "10-25", "25-50", "50+" for [0, .10, .25, .50].
     */
    private List<PayoffCell> payoffGrid(List<Obs> obs) {
        List<Double> edges = props.marginBands();
        List<String> labels = bandLabels(edges);
        int cap = props.gamesLeftCap();
        int[][] n = new int[labels.size()][cap];
        int[][] last = new int[labels.size()][cap];
        int[][] any = new int[labels.size()][cap];
        for (Obs o : obs) {
            int b = 0;
            while (b < edges.size() && o.margin() >= edges.get(b)) b++;
            int g = Math.min(o.laterGames(), cap) - 1;
            n[b][g]++;
            if (o.beatenByLast()) last[b][g]++;
            if (o.beatenLater()) any[b][g]++;
        }
        List<PayoffCell> out = new ArrayList<>();
        for (int b = 0; b < labels.size(); b++) {
            for (int g = 0; g < cap; g++) {
                boolean enough = n[b][g] >= props.minCell();
                out.add(new PayoffCell(labels.get(b), g + 1, n[b][g],
                        enough ? (double) last[b][g] / n[b][g] : null,
                        enough ? (double) any[b][g] / n[b][g] : null));
            }
        }
        return out;
    }

    static List<String> bandLabels(List<Double> edges) {
        List<String> out = new ArrayList<>();
        out.add("<" + pct(edges.get(0)));
        for (int i = 0; i + 1 < edges.size(); i++) out.add(pct(edges.get(i)) + "-" + pct(edges.get(i + 1)));
        out.add(pct(edges.get(edges.size() - 1)) + "+");
        return out;
    }

    private static String pct(double fraction) {
        double v = fraction * 100;
        return v == Math.rint(v) ? String.valueOf((long) v) : String.valueOf(Math.round(v * 10) / 10.0);
    }

    private LockSummary summary(Acc a, String name) {
        return new LockSummary(a.key, name, a.avatarId, figure(a.standIn), figure(a.projection), a.rodeOut,
                a.tieNonLast);
    }

    /** n always; the quartiles only from {@code min-locks} margins (below it the row says "too few"). */
    private BaselineFigure figure(List<Double> margins) {
        int n = margins.size();
        if (n < props.minLocks()) return new BaselineFigure(n, null, null, null);
        double[] q = LockThresholds.quantiles(margins.stream().mapToDouble(Double::doubleValue).toArray());
        return q == null ? new BaselineFigure(n, null, null, null) : new BaselineFigure(n, q[1], q[0], q[2]);
    }
}
