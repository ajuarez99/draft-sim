package com.ballknowers.draftsim.engine;

import com.ballknowers.draftsim.domain.Player;
import com.ballknowers.draftsim.domain.Position;
import com.ballknowers.draftsim.domain.Sport;
import com.ballknowers.draftsim.engine.PlayerSpotlightService.PeriodChoice;
import com.ballknowers.draftsim.engine.PlayerSpotlightService.PeriodRows;
import com.ballknowers.draftsim.engine.PlayerSpotlightService.ScoredGame;
import com.ballknowers.draftsim.engine.PlayerSpotlightService.TrendingEntry;
import com.ballknowers.draftsim.engine.PlayerSpotlightService.TrendingSection;
import com.ballknowers.draftsim.ingest.NextGames;
import com.ballknowers.draftsim.ingest.SportSchedule;
import com.ballknowers.draftsim.store.BoardRepository;
import com.ballknowers.draftsim.store.JsonUtil;
import com.ballknowers.draftsim.store.PlayerGameRepository;
import com.ballknowers.draftsim.store.PlayerRepository;
import com.ballknowers.draftsim.store.SportScheduleRepository;
import com.ballknowers.draftsim.store.SportTrendingRepository;
import com.ballknowers.draftsim.store.SportWeekStatsRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * The morning tweet digest (specs/043-tweet-digest-admin): per sport, last night's trending adds,
 * top performers, key players and (NFL) position leaders, as structured entries the web page turns
 * into post text. Read-only: nothing here writes, and nothing calls an upstream.
 *
 * <p>It sits in {@code engine} so it can call {@link PlayerSpotlightService}'s package-private
 * statics (night choice, completeness, scoring, trending classification) instead of copying them.
 *
 * <p><b>Points are Sleeper's own</b> {@code pts_std} (NBA) / {@code pts_ppr} (NFL), applied through
 * the app's scorer with a one-key scoring map. Sleeper leaves out zero-valued fields, so an absent
 * field scores 0 and the player did play (plan-review F1). These are NOT the league-scored numbers
 * the rest of the app shows, and the page labels the basis for that reason.
 */
@Service
public class AdminDigestService {

    // ---- Hand-set constants. Every one is arbitrary: a taste call, not a measurement. -----------

    /** Key players: the top N of Sleeper's search rank. Arbitrary. */
    static final int KEY_PLAYER_COUNT = 15;
    /** Games this season before his own average is trusted as his expectation. Arbitrary. */
    static final int KEY_SEASON_MIN_GAMES = 5;
    /** Games last season needed to fall back to last year's average. Arbitrary. */
    static final int KEY_LAST_SEASON_MIN_GAMES = 10;
    /** Entries in the top-performers card. Arbitrary. */
    static final int TOP_LIMIT = 10;
    /** Football's top performers and leaders cover these positions only (K and DEF left out). Arbitrary. */
    static final Set<Position> NFL_SKILL = EnumSet.of(Position.QB, Position.RB, Position.WR, Position.TE);
    /** Order of the NFL position-leaders card. Arbitrary. */
    static final List<Position> NFL_LEADER_ORDER = List.of(Position.QB, Position.RB, Position.WR, Position.TE);

    /** The name of one scoring basis: Sleeper's own computed points, which the page labels. */
    private record Basis(String key, String label, List<String> lineKeys) {}

    // One-key scoring maps (arbitrary choice of which Sleeper total to show; the labels say which).
    private static final Basis NBA = new Basis("pts_std", "Sleeper default points",
            List.of("pts", "reb", "ast", "stl", "blk", "tpm"));
    private static final Basis NFL = new Basis("pts_ppr", "Sleeper PPR",
            List.of("pass_yd", "pass_td", "pass_int", "rush_yd", "rush_td", "rec", "rec_yd", "rec_td"));

    private static Basis basis(Sport sport) {
        return sport == Sport.NBA ? NBA : NFL;
    }

    // ---- Statuses ---------------------------------------------------------------------------------

    static final String READY = "READY";
    static final String NO_GAMES_LAST_NIGHT = "NO_GAMES_LAST_NIGHT";
    static final String NOT_FETCHED = "NOT_FETCHED";
    static final String NIGHT_INCOMPLETE = "NIGHT_INCOMPLETE";
    static final String NO_GAMES_ON_DATE = "NO_GAMES_ON_DATE";
    static final String NO_SEASON = "NO_SEASON";
    /** A chosen date after today: nothing can have been played, and fetching cannot help (L1). */
    static final String DATE_IN_FUTURE = "DATE_IN_FUTURE";

    // ---- Response records (mirrored field-for-field in web/src/api.ts) ----------------------------

    public record AdminDigest(Instant generatedAt, LocalDate yesterday, List<SportDigest> sports) {}

    /** Cards are populated only when {@code status} is READY; trending can ride along (F16). */
    public record SportDigest(String sport, Integer season, String pointsBasis, String status,
                              Instant statsFetchedAt, LocalDate date, Integer week, Integer gamesCount,
                              LocalDate lastSlateDate, LocalDate inProgressDate, LocalDate nextGameDate,
                              TrendingCard trending, List<DigestEntry> topPerformers,
                              List<KeyEntry> keyPlayers, List<DigestEntry> positionLeaders) {}

    /** {@code points} and {@code line} are null unless the player played; {@code line} holds non-zero headline keys only. */
    public record DigestEntry(String playerId, String name, String position, String team,
                              String opponent, Boolean isAway, Double points, Map<String, Object> line) {}

    public record TrendingCard(String status, int lookbackHours, Instant fetchedAt, boolean stale,
                               int omittedUnknownPlayers, List<TrendingItem> entries) {}

    public record TrendingItem(int rank, int addCount, String outcome, DigestEntry entry) {}

    public record KeyEntry(int rank, DigestEntry entry, boolean played, Double expectation,
                           String expectationBasis, String verdict) {}

    // ---- Data seam: lets the whole assembler run against hand-built rows in a unit test -----------

    /** Everything the digest reads. {@link RepoData} is the one real implementation. */
    interface Data {
        Optional<Integer> currentSeason(Sport sport);

        List<Integer> seasonsOn(Sport sport, LocalDate date);

        List<PlayerGameRepository.DateRow> dates(Sport sport, int season);

        List<SportWeekStatsRepository.Row> weekStats(Sport sport, int season);

        List<SportSchedule.Game> schedule(Sport sport, int season);

        List<PlayerGameRepository.Row> rowsOn(Sport sport, int season, LocalDate date);

        Optional<SportTrendingRepository.Snapshot> trending(Sport sport);

        Map<String, Player> players(Sport sport, Collection<String> sleeperIds);

        List<BoardRepository.RankedPlayer> topSearchRank(Sport sport, int limit);

        List<PlayerGameRepository.Row> gamesOf(Sport sport, int season, Collection<String> sleeperIds);
    }

    private final Data data;
    private final GameScoringService gameScoring;
    private final Clock clock;

    @Autowired
    public AdminDigestService(PlayerGameRepository playerGames, SportWeekStatsRepository weekStats,
                              SportScheduleRepository schedules, SportLeagueSeasonSource currentSeason,
                              SportTrendingRepository trending, PlayerRepository players,
                              BoardRepository board, GameScoringService gameScoring, Clock clock) {
        this(new RepoData(playerGames, weekStats, schedules, currentSeason, trending, players, board),
                gameScoring, clock);
    }

    AdminDigestService(Data data, GameScoringService gameScoring, Clock clock) {
        this.data = data;
        this.gameScoring = gameScoring;
        this.clock = clock;
    }

    /**
     * @param date null for "last night" per sport; otherwise the date every sport covers.
     */
    public AdminDigest digest(LocalDate date) {
        LocalDate yesterday = NextGames.today(clock).minusDays(1);
        List<SportDigest> sports = new ArrayList<>();
        // Basketball first: it plays most nights and is the target (spec).
        for (Sport sport : List.of(Sport.NBA, Sport.NFL)) sports.add(forSport(sport, date, yesterday));
        return new AdminDigest(clock.instant(), yesterday, sports);
    }

    // ---- One sport ----------------------------------------------------------------------------------

    private SportDigest forSport(Sport sport, LocalDate explicitDate, LocalDate yesterday) {
        Basis basis = basis(sport);
        boolean historical = explicitDate != null;
        LocalDate target = historical ? explicitDate : yesterday;
        Instant now = clock.instant();

        LocalDate today = NextGames.today(clock);
        Optional<Integer> current = data.currentSeason(sport);
        Integer season = null;
        // False when a past date has no stored rows and no known season's schedule covers it: then
        // "the next game" can't be read from any season honestly, so it is left null (S3).
        boolean seasonKnown = true;
        if (historical) {
            List<Integer> on = data.seasonsOn(sport, target);
            if (!on.isEmpty()) {
                season = on.getFirst();
            } else if (current.isPresent()) {
                season = seasonForDateWithoutRows(sport, current.get(), target);
                if (season == null) {
                    season = current.get();
                    seasonKnown = false;
                }
            }
        }
        if (season == null) season = current.orElse(null);
        if (season == null) {
            // No season: no night to cover, but default-mode trending still rides along (F16, L2).
            TrendingCard noSeasonTrending;
            if (historical) {
                noSeasonTrending = notHistorical();
            } else {
                Optional<SportTrendingRepository.Snapshot> snap = readTrending(sport);
                Set<String> tids = new HashSet<>();
                snap.ifPresent(sn -> sn.entries().stream().limit(PlayerSpotlightService.MAX_TRENDING_READ)
                        .forEach(e -> tids.add(e.sleeperPlayerId())));
                noSeasonTrending = trendingCard(snap, null, new PeriodRows(List.of(), List.of()),
                        data.players(sport, tids), basis, now);
            }
            return new SportDigest(sport.code(), null, basis.label(), NO_SEASON, null, null, null, null,
                    null, null, null, noSeasonTrending, List.of(), List.of(), leadersOrNull(sport));
        }

        List<PlayerGameRepository.DateRow> dates = data.dates(sport, season);
        List<SportWeekStatsRepository.Row> weekRows = data.weekStats(sport, season);
        Map<Integer, Instant> fetchedAtByWeek = new HashMap<>();
        Map<Integer, Boolean> finalByWeek = new HashMap<>();
        for (SportWeekStatsRepository.Row r : weekRows) {
            fetchedAtByWeek.put(r.week(), r.fetchedAt());
            finalByWeek.put(r.week(), r.fin());
        }
        // Newest complete night and any newer night in progress, from the spotlight's own rule.
        PeriodChoice choice = PlayerSpotlightService.choosePeriod(true, dates, fetchedAtByWeek, finalByWeek, now);
        LocalDate lastSlate = choice.period() == null ? null : choice.period().date();

        // The schedule of the season the covered date belongs to: the rows' season, else the season
        // whose schedule covers the date (S3).
        List<SportSchedule.Game> schedule = data.schedule(sport, season);
        boolean hasRows = dates.stream().anyMatch(d -> d.gameDate().equals(target));
        String status;
        if (hasRows) {
            status = PlayerSpotlightService.isComplete(target, dates, fetchedAtByWeek, now) ? READY : NIGHT_INCOMPLETE;
        } else if (historical && target.isAfter(today)) {
            status = DATE_IN_FUTURE;
        } else if (scheduledOn(schedule, target)) {
            // Games were scheduled but nothing stored: the fetch has not run (F2), not "no games".
            status = NOT_FETCHED;
        } else {
            status = historical ? NO_GAMES_ON_DATE : NO_GAMES_LAST_NIGHT;
        }

        Instant statsFetchedAt = statsFetchedAt(target, hasRows, dates, schedule, fetchedAtByWeek, weekRows);
        // Contract: today's games count in default mode (S1); "after `date`" with an explicit date.
        LocalDate nextGame = !seasonKnown ? null
                : historical ? nextGameDate(sport, season, target, false)
                : nextGameDate(sport, season, today, true);

        boolean ready = READY.equals(status);
        Integer week = null;
        Integer gamesCount = null;
        if (ready) {
            int w = Integer.MAX_VALUE;
            int games = 0;
            for (PlayerGameRepository.DateRow d : dates) {
                if (!d.gameDate().equals(target)) continue;
                w = Math.min(w, d.week());
                games += d.gamesCount();
            }
            week = w;
            gamesCount = games;
        }

        // Rows only on a READY night. Trending reads them too, but in default mode it also rides
        // along on a night that isn't READY, with period == null so each outcome is NO_PERIOD.
        List<PlayerGameRepository.Row> rows = ready ? data.rowsOn(sport, season, target) : List.of();
        rows = rows.stream().filter(r -> !r.sleeperPlayerId().startsWith(PlayerGameRepository.TEAM_ID_PREFIX)).toList();

        Optional<SportTrendingRepository.Snapshot> snapshot = historical ? Optional.empty() : readTrending(sport);
        Set<String> ids = new HashSet<>();
        for (PlayerGameRepository.Row r : rows) ids.add(r.sleeperPlayerId());
        List<String> trendingIds = new ArrayList<>();
        if (snapshot.isPresent()) {
            snapshot.get().entries().stream().limit(PlayerSpotlightService.MAX_TRENDING_READ)
                    .forEach(e -> trendingIds.add(e.sleeperPlayerId()));
        }
        ids.addAll(trendingIds);
        List<BoardRepository.RankedPlayer> ranked = ready ? data.topSearchRank(sport, KEY_PLAYER_COUNT) : List.of();
        for (BoardRepository.RankedPlayer rp : ranked) ids.add(rp.sleeperPlayerId());
        Map<String, Player> byId = data.players(sport, ids);

        Map<String, Double> scoring = Map.of(basis.key(), 1.0);
        List<ScoredGame> scored = PlayerSpotlightService.scoreAll(rows, scoring, gameScoring, byId);
        PeriodRows periodRows = new PeriodRows(rows, scored);
        Map<String, Aggregated> played = aggregate(scored, basis);

        TrendingCard trending;
        if (historical) {
            trending = notHistorical();
        } else {
            // The period is the covered night itself (the same as choice.period() on a READY default
            // night, but built here so it can never name some other night).
            trending = trendingCard(snapshot, ready ? PlayerSpotlightService.Period.night(target, gamesCount) : null,
                    periodRows, byId, basis, now);
        }

        List<DigestEntry> top = List.of();
        List<KeyEntry> key = List.of();
        List<DigestEntry> leaders = sport == Sport.NFL ? List.of() : null;
        if (ready) {
            top = topPerformers(sport, played);
            key = keyPlayers(sport, season, target, historical, played, ranked, byId, schedule, basis);
            if (sport == Sport.NFL) leaders = positionLeaders(played);
        }

        return new SportDigest(sport.code(), season, basis.label(), status, statsFetchedAt,
                ready ? target : null, week, gamesCount, lastSlate, choice.laterNightInProgress(), nextGame,
                trending, top, key, leaders);
    }

    private static TrendingCard notHistorical() {
        return new TrendingCard("TRENDING_NOT_HISTORICAL", PlayerSpotlightService.DEFAULT_LOOKBACK_HOURS,
                null, false, 0, List.of());
    }

    /**
     * The season a past date belongs to when no game rows are stored for it: the current season or the
     * one before, whichever schedule lists a game that day, else whichever schedule's span covers it;
     * the gap between the two counts as the current season's (its next game is its opener). Null when
     * nothing known covers the date (S3).
     */
    private Integer seasonForDateWithoutRows(Sport sport, int current, LocalDate date) {
        List<SportSchedule.Game> cur = data.schedule(sport, current);
        List<SportSchedule.Game> prev = data.schedule(sport, current - 1);
        if (scheduledOn(cur, date)) return current;
        if (scheduledOn(prev, date)) return current - 1;
        LocalDate curMin = span(cur, true), curMax = span(cur, false);
        LocalDate prevMin = span(prev, true), prevMax = span(prev, false);
        if (curMin != null && !date.isBefore(curMin) && !date.isAfter(curMax)) return current;
        if (prevMin != null && !date.isBefore(prevMin) && !date.isAfter(prevMax)) return current - 1;
        if (curMin != null && prevMax != null && date.isAfter(prevMax) && date.isBefore(curMin)) return current;
        return null;
    }

    private static LocalDate span(List<SportSchedule.Game> schedule, boolean min) {
        LocalDate best = null;
        for (SportSchedule.Game g : schedule) {
            if (g.date() == null || !SportSchedule.counts(g.status())) continue;
            if (best == null || (min ? g.date().isBefore(best) : g.date().isAfter(best))) best = g.date();
        }
        return best;
    }

    private static List<DigestEntry> leadersOrNull(Sport sport) {
        return sport == Sport.NFL ? List.of() : null;
    }

    private static boolean scheduledOn(List<SportSchedule.Game> schedule, LocalDate date) {
        for (SportSchedule.Game g : schedule) {
            if (date.equals(g.date()) && SportSchedule.counts(g.status())) return true;
        }
        return false;
    }

    /**
     * When the covered (or scheduled) night's week was last fetched; the oldest if it spans weeks.
     * With no night in play at all, the newest fetch of the season, so the page can say how fresh
     * the "no games" answer is. Null = never fetched.
     */
    private static Instant statsFetchedAt(LocalDate target, boolean hasRows,
                                          List<PlayerGameRepository.DateRow> dates,
                                          List<SportSchedule.Game> schedule,
                                          Map<Integer, Instant> fetchedAtByWeek,
                                          List<SportWeekStatsRepository.Row> weekRows) {
        Set<Integer> weeks = new HashSet<>();
        if (hasRows) {
            for (PlayerGameRepository.DateRow d : dates) if (d.gameDate().equals(target)) weeks.add(d.week());
        } else {
            for (SportSchedule.Game g : schedule) {
                if (target.equals(g.date()) && SportSchedule.counts(g.status())) weeks.add(g.week());
            }
        }
        if (weeks.isEmpty()) {
            return weekRows.stream().map(SportWeekStatsRepository.Row::fetchedAt)
                    .filter(java.util.Objects::nonNull).max(Comparator.naturalOrder()).orElse(null);
        }
        Instant oldest = null;
        for (int w : weeks) {
            Instant f = fetchedAtByWeek.get(w);
            if (f == null) return null; // one week never fetched: the honest answer is "never"
            if (oldest == null || f.isBefore(oldest)) oldest = f;
        }
        return oldest;
    }

    /**
     * First counted game date after {@code reference} (on or after it when {@code inclusive}): this
     * season, else next (the off-season case).
     */
    private LocalDate nextGameDate(Sport sport, int season, LocalDate reference, boolean inclusive) {
        for (int s : new int[]{season, season + 1}) {
            LocalDate best = null;
            for (SportSchedule.Game g : data.schedule(sport, s)) {
                if (g.date() == null || !SportSchedule.counts(g.status())) continue;
                if (inclusive ? g.date().isBefore(reference) : !g.date().isAfter(reference)) continue;
                if (best == null || g.date().isBefore(best)) best = g.date();
            }
            if (best != null) return best;
        }
        return null;
    }

    // ---- Scored rows --------------------------------------------------------------------------------

    /** One player's night: points summed over his rows (one in practice), first game's opponent. */
    private record Aggregated(Player player, double points, String opponent, Boolean isAway,
                              Map<String, Object> line, String gameId) {}

    private static Map<String, Aggregated> aggregate(List<ScoredGame> scored, Basis basis) {
        Map<String, List<ScoredGame>> by = new LinkedHashMap<>();
        for (ScoredGame g : scored) by.computeIfAbsent(g.row().sleeperPlayerId(), k -> new ArrayList<>()).add(g);
        Map<String, Aggregated> out = new LinkedHashMap<>();
        for (Map.Entry<String, List<ScoredGame>> e : by.entrySet()) {
            ScoredGame first = e.getValue().stream()
                    .min(Comparator.comparing(g -> g.row().gameId())).orElseThrow();
            double sum = 0;
            Map<String, Double> totals = new HashMap<>();
            for (ScoredGame g : e.getValue()) {
                sum += g.points();
                Map<String, Object> stats = JsonUtil.readMap(g.row().statsJson());
                for (String k : basis.lineKeys()) {
                    if (stats.get(k) instanceof Number n) totals.merge(k, n.doubleValue(), Double::sum);
                }
            }
            out.put(e.getKey(), new Aggregated(first.player(), sum, first.row().opponent(), first.row().isAway(),
                    headline(totals, basis), first.row().gameId()));
        }
        return out;
    }

    /** Non-zero headline stats in the basis's order, whole numbers as longs. A mutable map, never Map.of. */
    private static Map<String, Object> headline(Map<String, Double> totals, Basis basis) {
        Map<String, Object> line = new LinkedHashMap<>();
        for (String k : basis.lineKeys()) {
            Double v = totals.get(k);
            if (v == null || v == 0) continue;
            line.put(k, v == Math.rint(v) ? (Object) v.longValue() : (Object) v);
        }
        return line;
    }

    private static DigestEntry entry(Aggregated a) {
        return new DigestEntry(a.player().sleeperId(), a.player().name(), a.player().primary().name(),
                a.player().team(), a.opponent(), a.isAway(), a.points(), a.line());
    }

    private static final Comparator<Map.Entry<String, Aggregated>> BEST_FIRST =
            Comparator.<Map.Entry<String, Aggregated>>comparingDouble(e -> e.getValue().points()).reversed()
                    .thenComparing(Map.Entry::getKey);

    // ---- Cards --------------------------------------------------------------------------------------

    private TrendingCard trendingCard(Optional<SportTrendingRepository.Snapshot> snapshot,
                                      PlayerSpotlightService.Period period,
                                      PeriodRows rows, Map<String, Player> byId, Basis basis, Instant now) {
        TrendingSection t = PlayerSpotlightService.trending(snapshot, period, rows, Map.of(), byId, now);
        if (PlayerSpotlightService.NEVER_FETCHED.equals(t.unavailable())) {
            return new TrendingCard("NEVER_FETCHED", t.lookbackHours(), null, false, 0, List.of());
        }
        Map<String, Aggregated> played = aggregate(rows.scored(), basis);
        List<TrendingItem> items = new ArrayList<>();
        for (TrendingEntry e : t.entries()) {
            Aggregated a = played.get(e.playerId());
            DigestEntry de;
            if ("PLAYED".equals(e.outcome()) && a != null) {
                de = new DigestEntry(e.playerId(), e.name(), e.position(), e.team(), e.opponent(), e.isAway(),
                        e.points(), a.line());
            } else {
                de = new DigestEntry(e.playerId(), e.name(), e.position(), e.team(), null, null, null, null);
            }
            items.add(new TrendingItem(e.rank(), e.addCount(), e.outcome(), de));
        }
        return new TrendingCard("OK", t.lookbackHours(), t.fetchedAt(), t.stale(), t.omittedUnknownPlayers(), items);
    }

    private static List<DigestEntry> topPerformers(Sport sport, Map<String, Aggregated> played) {
        List<Map.Entry<String, Aggregated>> all = new ArrayList<>();
        for (Map.Entry<String, Aggregated> e : played.entrySet()) {
            if (sport == Sport.NFL && !isSkill(e.getValue().player())) continue;
            all.add(e);
        }
        all.sort(BEST_FIRST);
        List<DigestEntry> out = new ArrayList<>();
        for (Map.Entry<String, Aggregated> e : all) {
            if (out.size() == TOP_LIMIT) break;
            out.add(entry(e.getValue()));
        }
        return out;
    }

    /** An empty position list would read as WR through {@code primary()}; it is no skill player (L3). */
    private static boolean isSkill(Player p) {
        return !p.positions().isEmpty() && NFL_SKILL.contains(p.primary());
    }

    private static List<DigestEntry> positionLeaders(Map<String, Aggregated> played) {
        List<DigestEntry> out = new ArrayList<>();
        for (Position pos : NFL_LEADER_ORDER) {
            played.entrySet().stream()
                    .filter(e -> !e.getValue().player().positions().isEmpty() && e.getValue().player().primary() == pos)
                    .min(BEST_FIRST)
                    .ifPresent(e -> out.add(entry(e.getValue())));
        }
        return out;
    }

    private List<KeyEntry> keyPlayers(Sport sport, int season, LocalDate date, boolean historical,
                                      Map<String, Aggregated> played, List<BoardRepository.RankedPlayer> ranked,
                                      Map<String, Player> byId, List<SportSchedule.Game> schedule, Basis basis) {
        // Expectation inputs: only games dated BEFORE the covered night (F6), this and last season.
        List<String> keyIds = ranked.stream().map(BoardRepository.RankedPlayer::sleeperPlayerId).toList();
        Map<String, List<Double>> thisSeason = pointsBefore(sport, season, keyIds, date, basis);
        Map<String, List<Double>> lastSeason = pointsBefore(sport, season - 1, keyIds, date, basis);

        List<KeyEntry> out = new ArrayList<>();
        for (BoardRepository.RankedPlayer rp : ranked) {
            Player p = byId.get(rp.sleeperPlayerId());
            if (p == null) continue;
            Aggregated a = played.get(rp.sleeperPlayerId());
            DigestEntry entry;
            if (a != null) {
                entry = entry(a);
            } else if (!historical) {
                // A DNP only when his current team had a game that night. Past dates rely on rows
                // alone: the current team can't be trusted for a traded player (F7).
                SportSchedule.Game g = gameOf(schedule, date, p.team());
                if (g == null) continue;
                boolean away = p.team().equals(g.away());
                entry = new DigestEntry(p.sleeperId(), p.name(), p.primary().name(), p.team(),
                        away ? g.home() : g.away(), away, null, null);
            } else {
                continue;
            }

            Double expectation = null;
            String expBasis = "NONE";
            List<Double> cur = thisSeason.getOrDefault(rp.sleeperPlayerId(), List.of());
            List<Double> prev = lastSeason.getOrDefault(rp.sleeperPlayerId(), List.of());
            if (cur.size() >= KEY_SEASON_MIN_GAMES) {
                expectation = mean(cur);
                expBasis = "THIS_SEASON";
            } else if (prev.size() >= KEY_LAST_SEASON_MIN_GAMES) {
                expectation = mean(prev);
                expBasis = "LAST_SEASON";
            }

            String verdict;
            if (a == null) verdict = "DNP";
            else if (expectation == null) verdict = "NO_BASELINE";
            else verdict = a.points() >= expectation ? "ABOVE" : "BELOW";
            out.add(new KeyEntry(rp.rank(), entry, a != null, expectation, expBasis, verdict));
        }
        return out;
    }

    private static SportSchedule.Game gameOf(List<SportSchedule.Game> schedule, LocalDate date, String team) {
        if (team == null) return null;
        for (SportSchedule.Game g : schedule) {
            if (date.equals(g.date()) && SportSchedule.counts(g.status())
                    && (team.equals(g.home()) || team.equals(g.away()))) return g;
        }
        return null;
    }

    /** Each player's scored points in games dated before {@code before}, one value per game row. */
    private Map<String, List<Double>> pointsBefore(Sport sport, int season, Collection<String> ids,
                                                   LocalDate before, Basis basis) {
        Map<String, List<Double>> out = new HashMap<>();
        if (ids.isEmpty()) return out;
        Map<String, Double> scoring = Map.of(basis.key(), 1.0);
        for (PlayerGameRepository.Row r : data.gamesOf(sport, season, ids)) {
            if (!r.gameDate().isBefore(before)) continue;
            double pts = gameScoring.score(scoring, JsonUtil.readMap(r.statsJson()));
            out.computeIfAbsent(r.sleeperPlayerId(), k -> new ArrayList<>()).add(pts);
        }
        return out;
    }

    private static double mean(List<Double> xs) {
        double s = 0;
        for (double x : xs) s += x;
        return s / xs.size();
    }

    // ---- Trending read -------------------------------------------------------------------------------

    /** A failed snapshot read is "no snapshot" (NEVER_FETCHED), not a 500 for the whole digest. */
    private Optional<SportTrendingRepository.Snapshot> readTrending(Sport sport) {
        try {
            return data.trending(sport);
        } catch (RuntimeException e) {
            return Optional.empty();
        }
    }

    // ---- Repository-backed Data ------------------------------------------------------------------------

    private record RepoData(PlayerGameRepository playerGames, SportWeekStatsRepository weekStats,
                            SportScheduleRepository schedules, SportLeagueSeasonSource currentSeason,
                            SportTrendingRepository trending, PlayerRepository players,
                            BoardRepository board) implements Data {
        @Override
        public Optional<Integer> currentSeason(Sport sport) {
            return currentSeason.leagueSeason(sport);
        }

        @Override
        public List<Integer> seasonsOn(Sport sport, LocalDate date) {
            return playerGames.seasonsOn(sport, date);
        }

        @Override
        public List<PlayerGameRepository.DateRow> dates(Sport sport, int season) {
            return playerGames.datesForSeason(sport, season);
        }

        @Override
        public List<SportWeekStatsRepository.Row> weekStats(Sport sport, int season) {
            return weekStats.forSeason(sport, season);
        }

        @Override
        public List<SportSchedule.Game> schedule(Sport sport, int season) {
            return schedules.forSeason(sport.code(), season);
        }

        @Override
        public List<PlayerGameRepository.Row> rowsOn(Sport sport, int season, LocalDate date) {
            return playerGames.forDate(sport, season, date);
        }

        @Override
        public Optional<SportTrendingRepository.Snapshot> trending(Sport sport) {
            return trending.read(sport.code());
        }

        @Override
        public Map<String, Player> players(Sport sport, Collection<String> sleeperIds) {
            return players.byIds(sport, sleeperIds);
        }

        @Override
        public List<BoardRepository.RankedPlayer> topSearchRank(Sport sport, int limit) {
            return board.topRanked(sport, BoardRepository.SOURCE_SEARCH_RANK, limit);
        }

        @Override
        public List<PlayerGameRepository.Row> gamesOf(Sport sport, int season, Collection<String> sleeperIds) {
            return playerGames.forPlayers(sport, season, sleeperIds);
        }
    }
}
