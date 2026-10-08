package com.ballknowers.draftsim.engine;

import com.ballknowers.draftsim.config.PlayerTrendsProperties;
import com.ballknowers.draftsim.domain.Player;
import com.ballknowers.draftsim.domain.Sport;
import com.ballknowers.draftsim.engine.NbaGameLines.Line;
import com.ballknowers.draftsim.sport.SportRulesRegistry;
import com.ballknowers.draftsim.store.LeagueMemberRepository;
import com.ballknowers.draftsim.store.LeagueRepository;
import com.ballknowers.draftsim.store.LeagueRepository.LeagueRow;
import com.ballknowers.draftsim.store.ManagerRepository;
import com.ballknowers.draftsim.store.PlayerAbsenceRepository;
import com.ballknowers.draftsim.store.PlayerGameRepository.SeasonGame;
import com.ballknowers.draftsim.store.PlayerGameRepository.TeamGame;
import com.ballknowers.draftsim.store.PlayerRepository;
import com.ballknowers.draftsim.store.RosterSeasonRepository;
import com.ballknowers.draftsim.store.RosterWeekPointsRepository;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;

/**
 * NBA minutes trends and streaming candidates (specs/019-minutes-streaming; the math is
 * data-model.md, the wire shape contracts/api.md).
 *
 * <p>{@link #compute(Input)} is the pure core: plain values in, plain values out, no I/O.
 * {@link #read(LeagueRow, String)} does the reading and the NOT_CONFIGURED / NOT_BASKETBALL gates.
 *
 * <p>Every hand-set number comes from {@link PlayerTrendsProperties} (ARBITRARY, labelled). Reasons are
 * codes; the sentences live in the web client. Minutes are {@code sp / 60}; a missing stat key counts
 * as 0 (the turnover key is {@code to}); {@code TEAM_*} total rows are used only for usage, never as
 * players; a game whose opponent is not one of the season's team codes is an All-Star game and dropped.
 */
@Service
public class PlayerTrendsService {

    public static final String NOT_CONFIGURED = "NOT_CONFIGURED";
    public static final String NOT_BASKETBALL = "NOT_BASKETBALL";
    public static final String NO_GAMES = "NO_GAMES";
    public static final String SEASON_COMPLETE = "SEASON_COMPLETE";
    public static final String NOT_DRAFTED = "NOT_DRAFTED";
    public static final String ROSTERS_NOT_LOADED = "ROSTERS_NOT_LOADED";

    /** Games in the "recent" window: fixed by the definition of the metric, not a tunable. */
    static final int RECENT_GAMES = 3;

    // ------------------------------------------------------------------ wire records (contract C1)

    public record Windows(int recentGames, int formGames, int formMinGames, int minSeasonGames, int recencyDays) {}

    public record OneGameCredit(String code, double share, int seasonMeasured) {}

    public record TrendRow(String sleeperPlayerId, String name, List<String> positions, String team,
                           int games, LocalDate lastGameDate,
                           Double recentMin, Double seasonMin, Double minDelta, String role,
                           Double recentUsg, Double seasonUsg, Double ptsPerMin,
                           Double seasonPts, Double formPts, int missedTeamGames,
                           Boolean rostered, String rosteredBy, Integer gamesThisWeek, Integer gamesNextWeek) {}

    public record PlayerTrends(String sport, int season, boolean available, String reason,
                               Integer rolesSeason, boolean rolesFallback,
                               Integer streamingSeason, boolean streamingFallback,
                               Windows windows, Integer roleThresholdMinutes, Integer currentWeek,
                               int risersTotal, int fallersTotal,
                               int risersFreeAgentTotal, int risersRosteredTotal,
                               int fallersFreeAgentTotal, int fallersRosteredTotal,
                               int excludedStale, int excludedNoTeam, LocalDate staleReferenceDate,
                               List<TrendRow> risers, List<TrendRow> fallers,
                               String streamingReason, OffsetDateTime rostersFetchedAt,
                               List<TrendRow> streaming, OneGameCredit oneGameCredit) {}

    // ------------------------------------------------------------------ pure core types

    /**
     * One season's raw rows. {@code absences} are that season's stored absences. {@code lines} is the
     * {@link NbaGameLines} over {@code games} and {@code teamGames} when the caller already has it (the
     * {@link SeasonBoxCache} computes it once per load); null means {@code prepare} builds it.
     */
    public record SeasonData(int season, List<SeasonGame> games, List<TeamGame> teamGames,
                             List<PlayerAbsenceRepository.Row> absences, NbaGameLines lines) {
        public SeasonData(int season, List<SeasonGame> games, List<TeamGame> teamGames,
                          List<PlayerAbsenceRepository.Row> absences) {
            this(season, games, teamGames, absences, null);
        }

        public static SeasonData empty(int season) {
            return new SeasonData(season, List.of(), List.of(), List.of());
        }
    }

    /** {@code team} is {@code player.team}, nullable (a free agent with no NBA team). */
    public record PlayerInfo(String name, List<String> positions, String team) {}

    /** The schedule grid as {@link ScheduleGridService.Result} carries it: week numbers, and per-team counts by position. */
    public record Grid(List<Integer> weeks, Map<String, int[]> gamesByTeam) {}

    /**
     * @param leagueStatus Sleeper's league status, nullable (unknown reads as not complete)
     * @param grid         nullable: unavailable
     * @param rostered     empty when ownership was never fetched
     * @param oneGameShare nullable: no multi-game starter-weeks to measure
     */
    public record Input(String sport, int season, String leagueStatus, Integer currentWeek,
                        PlayerTrendsProperties props, Map<String, Double> scoring,
                        SeasonData current, SeasonData previous, Map<String, PlayerInfo> players,
                        Grid grid, Optional<RosterSeasonRepository.Rostered> rostered,
                        Map<Integer, RosterOwners.RosterOwner> owners,
                        Double oneGameShare, int oneGameSeason) {}

    // ------------------------------------------------------------------ wiring

    private final PlayerTrendsProperties props;
    private final SeasonBoxCache boxCache;
    private final PlayerAbsenceRepository absences;
    private final PlayerRepository players;
    private final LeagueRepository leagues;
    private final ScheduleGridService grid;
    private final RosterSeasonRepository rosterSeasons;
    private final LeagueMemberRepository members;
    private final ManagerRepository managers;
    private final RosterWeekPointsRepository weekPoints;
    private final SportRulesRegistry rules;

    public PlayerTrendsService(PlayerTrendsProperties props, SeasonBoxCache boxCache,
                               PlayerAbsenceRepository absences, PlayerRepository players, LeagueRepository leagues,
                               ScheduleGridService grid, RosterSeasonRepository rosterSeasons,
                               LeagueMemberRepository members, ManagerRepository managers,
                               RosterWeekPointsRepository weekPoints, SportRulesRegistry rules) {
        this.props = props;
        this.boxCache = boxCache;
        this.absences = absences;
        this.players = players;
        this.leagues = leagues;
        this.grid = grid;
        this.rosterSeasons = rosterSeasons;
        this.members = members;
        this.managers = managers;
        this.weekPoints = weekPoints;
        this.rules = rules;
    }

    // ------------------------------------------------------------------ read

    /** The state check order of data-model "States": config, then sport, then no games, then compute. */
    public PlayerTrends read(LeagueRow league, String sleeperUserId) {
        Sport sport = league.sport();
        if (!props.loaded()) return unavailable(sport.code(), league.season(), NOT_CONFIGURED, props, null);
        if (!rules.get(sport).playsMultipleGamesPerScoringPeriod()) {
            return unavailable(sport.code(), league.season(), NOT_BASKETBALL, props, null);
        }

        SeasonData cur = load(sport, league.season());
        SeasonData prev = load(sport, league.season() - 1);
        Integer week = leagues.currentLeg(league.id()).isPresent() ? leagues.currentLeg(league.id()).getAsInt() : null;
        if (cur.games().isEmpty() && prev.games().isEmpty()) {
            return unavailable(sport.code(), league.season(), NO_GAMES, props, week);
        }

        Set<String> ids = new HashSet<>();
        for (SeasonGame g : cur.games()) ids.add(g.sleeperPlayerId());
        for (SeasonGame g : prev.games()) ids.add(g.sleeperPlayerId());
        Map<String, PlayerInfo> infos = new HashMap<>();
        for (Map.Entry<String, Player> e : players.byIds(sport, ids).entrySet()) {
            Player p = e.getValue();
            infos.put(e.getKey(), new PlayerInfo(p.name(), p.positions().stream().map(Enum::name).toList(), p.team()));
        }

        Grid g = null;
        Optional<ScheduleGridService.Result> sched = grid.forLeague(league.sleeperId());
        if (sched.isPresent() && sched.get().available()) {
            Map<String, int[]> byTeam = new HashMap<>();
            for (ScheduleGridService.Team t : sched.get().teams()) byTeam.put(t.team(), t.games());
            g = new Grid(sched.get().weeks().stream().map(ScheduleGridService.Week::week).toList(), byTeam);
        }

        Optional<RosterSeasonRepository.Rostered> rostered = rosterSeasons.rosteredPlayers(league.id());
        Map<Integer, RosterOwners.RosterOwner> owners = Map.of();
        if (rostered.isPresent()) {
            Long callerManagerId = sleeperUserId == null || sleeperUserId.isBlank() ? null
                    : managers.idsBySleeperUserId().get(sleeperUserId);
            owners = RosterOwners.ownerNames(rosterSeasons.forLeague(league.id()), members.forLeague(league.id()),
                    callerManagerId);
        }

        Map<String, Double> scoring = leagues.scoringOf(league.id());
        Map<String, Double> measuredScoring = scoring;

        // One-game credit: this league's own stored weeks, else the previous season in the chain.
        List<RosterWeekPointsRepository.WeekBreakdown> weeks = weekPoints.breakdownsFor(league.id(), league.season());
        int measured = league.season();
        if (weeks.isEmpty() && league.previousLeagueId() != null) {
            Optional<LeagueRow> before = leagues.bySleeperId(league.previousLeagueId());
            if (before.isPresent()) {
                weeks = weekPoints.breakdownsFor(before.get().id(), before.get().season());
                measured = before.get().season();
                measuredScoring = leagues.scoringOf(before.get().id());   // B5: that season's own rules
            }
        }
        Double share = null;
        if (!weeks.isEmpty()) {
            List<SeasonGame> measuredGames = measured == cur.season() ? cur.games()
                    : measured == prev.season() ? prev.games() : boxCache.get(sport, measured).games();
            share = oneGameShare(weeks, measuredGames, measuredScoring);
        }

        return compute(new Input(sport.code(), league.season(), league.status(), week, props, scoring, cur, prev,
                infos, g, rostered, owners, share, measured));
    }

    private SeasonData load(Sport sport, int season) {
        SeasonBoxCache.Season box = boxCache.get(sport, season);
        return new SeasonData(season, box.games(), box.teamGames(), absences.forSeason(sport, season), box.lines());
    }

    private static PlayerTrends unavailable(String sport, int season, String reason, PlayerTrendsProperties p,
                                            Integer week) {
        Windows w = new Windows(RECENT_GAMES, orZero(p.formGames()), orZero(p.formMinGames()),
                orZero(p.minSeasonGames()), orZero(p.recencyDays()));
        return new PlayerTrends(sport, season, false, reason, null, false, null, false, w,
                p.roleThresholdMinutes(), week, 0, 0, 0, 0, 0, 0, 0, 0, null, List.of(), List.of(), null, null, List.of(), null);
    }

    private static int orZero(Integer v) {
        return v == null ? 0 : v;
    }

    // ------------------------------------------------------------------ pure core

    private static final GameScoringService SCORER = new GameScoringService();

    /** The per-player numbers of data-model "Derived values". */
    private record Stat(int games, LocalDate lastGame, Double recentMin, Double seasonMin, Double minDelta,
                        String role, Double recentUsg, Double seasonUsg, Double ptsPerMin, Double seasonPts,
                        Double formPts, int missedTeamGames) {}

    /** One season's rows, cleaned and indexed. */
    private record Prepared(int season, Map<String, List<Line>> byPlayer, Map<String, Stat> stats,
                            Set<String> teamCodes, Map<String, Integer> teamGameCount, LocalDate latest) {}

    public static PlayerTrends compute(Input in) {
        PlayerTrendsProperties p = in.props();
        if (in.current().games().isEmpty() && in.previous().games().isEmpty()) {
            return unavailable(in.sport(), in.season(), NO_GAMES, p, in.currentWeek());
        }
        Prepared cur = prepare(in.current(), in);
        Prepared prev = prepare(in.previous(), in);
        boolean prevHasGames = !in.previous().games().isEmpty();

        boolean streamingFallback = !cutoverMet(cur, p.formMinGames()) && prevHasGames;
        boolean rolesFallback = !cutoverMet(cur, p.minSeasonGames()) && prevHasGames;
        Prepared streamingData = streamingFallback ? prev : cur;
        Prepared rolesData = rolesFallback ? prev : cur;

        boolean complete = LeagueRepository.LeagueRow.isComplete(in.leagueStatus());
        String streamingReason = streamingReason(in, complete);
        boolean ownershipKnown = streamingReason == null;
        Map<String, Integer> rosterOf = in.rostered().map(RosterSeasonRepository.Rostered::byPlayer).orElse(Map.of());

        // risers / fallers. B4: a completed, non-fallback season shows each player's team of that season.
        boolean historic = complete && !rolesFallback;
        int excludedStale = 0;
        int excludedNoTeam = 0;
        List<TrendRow> risers = new ArrayList<>();
        List<TrendRow> fallers = new ArrayList<>();
        for (Map.Entry<String, Stat> e : rolesData.stats().entrySet()) {
            PlayerInfo info = in.players().get(e.getKey());
            Stat st = e.getValue();
            boolean riser = "RISER".equals(st.role());
            boolean faller = "FALLER".equals(st.role());
            // B3: only a player who'd otherwise be listed counts as hidden
            boolean wouldList = riser || faller
                    || (ownershipKnown && st.seasonPts() != null && !rosterOf.containsKey(e.getKey()));
            String team = info == null ? null : info.team();
            if (historic && info != null) {
                String last = lastTeam(rolesData.byPlayer().get(e.getKey()));
                if (last != null) team = last;
            }
            if (info == null || (team == null && !historic)) {
                if (wouldList) excludedNoTeam++;
                continue;
            }
            if (isStale(st, rolesData, p)) {
                if (wouldList) excludedStale++;
                continue;
            }
            if (riser) risers.add(row(e.getKey(), info, team, st, ownershipKnown, rosterOf, in, complete));
            else if (faller) fallers.add(row(e.getKey(), info, team, st, ownershipKnown, rosterOf, in, complete));
        }
        Comparator<TrendRow> byRecentThenId = Comparator.comparing(TrendRow::recentMin, Comparator.reverseOrder())
                .thenComparing(TrendRow::sleeperPlayerId);
        Comparator<TrendRow> riserOrder = Comparator.comparing(TrendRow::minDelta, Comparator.reverseOrder()).thenComparing(byRecentThenId);
        Comparator<TrendRow> fallerOrder = Comparator.comparing(TrendRow::minDelta).thenComparing(byRecentThenId);
        risers.sort(riserOrder);
        fallers.sort(fallerOrder);

        // streaming
        List<TrendRow> streaming = new ArrayList<>();
        if (streamingReason == null) {
            for (Map.Entry<String, Stat> e : streamingData.stats().entrySet()) {
                PlayerInfo info = in.players().get(e.getKey());
                Stat st = e.getValue();
                if (info == null || info.team() == null || st.seasonPts() == null) continue;
                if (isStale(st, streamingData, p) || rosterOf.containsKey(e.getKey())) continue;
                streaming.add(row(e.getKey(), info, info.team(), st, true, rosterOf, in, complete));
            }
            streaming.sort(Comparator.comparing(TrendRow::seasonPts, Comparator.reverseOrder())
                    .thenComparing(TrendRow::formPts, Comparator.nullsLast(Comparator.reverseOrder()))
                    .thenComparing(TrendRow::sleeperPlayerId));
        }

        OneGameCredit credit = in.oneGameShare() != null && in.oneGameShare() >= p.oneGameShare()
                ? new OneGameCredit("ONE_GAME_CREDITED", round(in.oneGameShare(), 2), in.oneGameSeason()) : null;
        Windows windows = new Windows(RECENT_GAMES, p.formGames(), p.formMinGames(), p.minSeasonGames(), p.recencyDays());
        OffsetDateTime fetchedAt = in.rostered().map(RosterSeasonRepository.Rostered::fetchedAt).orElse(null);

        return new PlayerTrends(in.sport(), in.season(), true, null,
                rolesData.season(), rolesFallback, streamingData.season(), streamingFallback,
                windows, p.roleThresholdMinutes(), in.currentWeek(),
                risers.size(), fallers.size(),
                countOwned(risers, false), countOwned(risers, true),
                countOwned(fallers, false), countOwned(fallers, true),
                excludedStale, excludedNoTeam, rolesData.latest(),
                capByOwnership(risers, p.listSize(), ownershipKnown, riserOrder),
                capByOwnership(fallers, p.listSize(), ownershipKnown, fallerOrder),
                streamingReason, fetchedAt, cap(streaming, p.streamingSize()), credit);
    }

    /** B2: with ownership known, the top n of each group; otherwise the top n overall. Result keeps list order. */
    private static List<TrendRow> capByOwnership(List<TrendRow> sorted, int n, boolean ownershipKnown,
                                                 Comparator<TrendRow> order) {
        if (!ownershipKnown) return cap(sorted, n);
        List<TrendRow> out = new ArrayList<>();
        out.addAll(cap(sorted.stream().filter(r -> Boolean.FALSE.equals(r.rostered())).toList(), n));
        out.addAll(cap(sorted.stream().filter(r -> Boolean.TRUE.equals(r.rostered())).toList(), n));
        out.sort(order);
        return out;
    }

    private static int countOwned(List<TrendRow> rows, boolean rostered) {
        return (int) rows.stream().filter(r -> Boolean.valueOf(rostered).equals(r.rostered())).count();
    }

    /** The team of his last game that season (from the team-row pairing), nullable. */
    private static String lastTeam(List<Line> games) {
        if (games == null) return null;
        for (int i = games.size() - 1; i >= 0; i--) if (games.get(i).team() != null) return games.get(i).team();
        return null;
    }

    private static <T> List<T> cap(List<T> l, int n) {
        return new ArrayList<>(l.subList(0, Math.min(n, l.size())));
    }

    /** data-model "Streaming availability", in order. */
    private static String streamingReason(Input in, boolean complete) {
        if (complete) return SEASON_COMPLETE;
        String st = in.leagueStatus();
        if ("pre_draft".equals(st) || "drafting".equals(st)) return NOT_DRAFTED;
        if (in.rostered().isEmpty()) return ROSTERS_NOT_LOADED;
        // B7: emptiness is only a fallback for a status we don't know
        if (st == null && in.rostered().get().byPlayer().isEmpty()) return NOT_DRAFTED;
        return null;
    }

    /** Half of the season's teams have played at least {@code minGames}; false for a season with no team rows. */
    private static boolean cutoverMet(Prepared d, int minGames) {
        if (d.teamCodes().isEmpty()) return false;
        long reached = d.teamCodes().stream().filter(c -> d.teamGameCount().getOrDefault(c, 0) >= minGames).count();
        return reached * 2 >= d.teamCodes().size();
    }

    private static boolean isStale(Stat st, Prepared d, PlayerTrendsProperties p) {
        return d.latest() != null && st.lastGame().isBefore(d.latest().minusDays(p.recencyDays()));
    }

    private static TrendRow row(String pid, PlayerInfo info, String team, Stat st, boolean ownershipKnown,
                                Map<String, Integer> rosterOf, Input in, boolean complete) {
        Boolean rostered = null;
        String rosteredBy = null;
        if (ownershipKnown) {
            Integer rid = rosterOf.get(pid);
            rostered = rid != null;
            if (rid != null) {
                RosterOwners.RosterOwner o = in.owners().get(rid);
                rosteredBy = o == null ? "Roster " + rid : o.name();
            }
        }
        Integer thisWeek = null;
        Integer nextWeek = null;
        if (!complete && in.grid() != null && in.currentWeek() != null) {
            thisWeek = gamesIn(in.grid(), team, in.currentWeek());
            nextWeek = gamesIn(in.grid(), team, in.currentWeek() + 1);
        }
        return new TrendRow(pid, info.name(), info.positions(), team, st.games(), st.lastGame(),
                st.recentMin(), st.seasonMin(), st.minDelta(), st.role(), st.recentUsg(), st.seasonUsg(),
                st.ptsPerMin(), st.seasonPts(), st.formPts(), st.missedTeamGames(),
                rostered, rosteredBy, thisWeek, nextWeek);
    }

    /** The grid cell for a team in a week NUMBER (not a position: N8); null when either is not in the grid. */
    private static Integer gamesIn(Grid grid, String team, int week) {
        int i = grid.weeks().indexOf(week);
        int[] games = grid.gamesByTeam().get(team);
        if (i < 0 || games == null || i >= games.length) return null;
        return games[i];
    }

    private static Prepared prepare(SeasonData d, Input in) {
        PlayerTrendsProperties p = in.props();
        NbaGameLines lines = d.lines() != null ? d.lines() : NbaGameLines.of(d.games(), d.teamGames());
        Set<String> codes = lines.teamCodes();
        Map<String, Integer> teamGameCount = new HashMap<>();
        lines.teamGames().forEach((c, rows) ->
                teamGameCount.put(c, (int) rows.stream().map(TeamGame::gameId).distinct().count()));

        Map<String, List<Line>> byPlayer = lines.byPlayer();
        LocalDate latest = null;
        for (List<Line> l : byPlayer.values()) {
            for (Line g : l) if (latest == null || g.date().isAfter(latest)) latest = g.date();
        }

        // each team's games, newest first, for the missed-team-games run
        Map<String, List<TeamGame>> scheduleByTeam = new HashMap<>();
        lines.teamGames().forEach((c, rows) -> {
            List<TeamGame> copy = new ArrayList<>(rows);     // the cached list is unmodifiable
            copy.sort(Comparator.comparing(TeamGame::date).thenComparing(TeamGame::gameId).reversed());
            scheduleByTeam.put(c, copy);
        });
        Map<String, Set<String>> missedByPlayer = new HashMap<>();
        for (PlayerAbsenceRepository.Row a : d.absences()) {
            // B1: a basketball missed game is ENTRY_WITHOUT_PLAY with a game id; week-level rows have none
            if (a.gameId() == null) continue;
            missedByPlayer.computeIfAbsent(a.playerId(), k -> new HashSet<>()).add(a.gameId());
        }

        Map<String, Stat> stats = new HashMap<>();
        for (Map.Entry<String, List<Line>> e : byPlayer.entrySet()) {
            stats.put(e.getKey(), stat(e.getValue(), p, in.scoring(),
                    missedRun(e.getValue(), scheduleByTeam, missedByPlayer.getOrDefault(e.getKey(), Set.of()))));
        }
        return new Prepared(d.season(), byPlayer, stats, codes, teamGameCount, latest);
    }

    /** How many of his team's most recent consecutive games he has a game-level absence row for (either basis). */
    private static int missedRun(List<Line> games, Map<String, List<TeamGame>> scheduleByTeam, Set<String> missed) {
        if (missed.isEmpty()) return 0;
        String team = null;
        for (int i = games.size() - 1; i >= 0 && team == null; i--) team = games.get(i).team();
        if (team == null) return 0;
        int n = 0;
        for (TeamGame t : scheduleByTeam.getOrDefault(team, List.of())) {
            if (!missed.contains(t.gameId())) break;
            n++;
        }
        return n;
    }

    private static Stat stat(List<Line> games, PlayerTrendsProperties p, Map<String, Double> scoring, int missed) {
        int n = games.size();
        List<Line> recent = games.subList(Math.max(0, n - RECENT_GAMES), n);
        Double recentMin = n >= RECENT_GAMES ? median(recent.stream().mapToDouble(Line::minutes).toArray()) : null;
        double minSum = games.stream().mapToDouble(Line::minutes).sum();
        Double seasonMin = n >= p.minSeasonGames() ? minSum / n : null;
        Double delta = recentMin != null && seasonMin != null ? recentMin - seasonMin : null;
        String role = delta == null ? null
                : delta >= p.roleThresholdMinutes() ? "RISER" : delta <= -p.roleThresholdMinutes() ? "FALLER" : "STEADY";

        double[] pts = games.stream().mapToDouble(g -> SCORER.score(scoring, g.stats())).toArray();
        Double seasonPts = null;
        Double formPts = null;
        if (n >= p.formMinGames()) {
            seasonPts = mean(pts, 0, n);
            formPts = mean(pts, Math.max(0, n - p.formGames()), n);
        }
        Double ptsPerMin = minSum > 0 ? java.util.Arrays.stream(pts).sum() / minSum : null;

        return new Stat(n, games.getLast().date(), r2(recentMin), r2(seasonMin), r2(delta), role,
                n >= RECENT_GAMES ? r2(AdvancedStats.usage(recent).value()) : null,
                n >= p.minSeasonGames() ? r2(AdvancedStats.usage(games).value()) : null,
                r2(ptsPerMin), r2(seasonPts), r2(formPts), missed);
    }

    /** A missing or non-numeric stat key is 0 (F12). */
    private static double num(Map<String, Object> m, String key) {
        return AdvancedStats.num(m, key);
    }

    private static double mean(double[] v, int from, int to) {
        double s = 0;
        for (int i = from; i < to; i++) s += v[i];
        return s / (to - from);
    }

    private static double median(double[] v) {
        double[] c = v.clone();
        java.util.Arrays.sort(c);
        int n = c.length;
        return n % 2 == 1 ? c[n / 2] : (c[n / 2 - 1] + c[n / 2]) / 2.0;
    }

    private static Double r2(Double v) {
        return v == null ? null : round(v, 2);
    }

    private static double round(double v, int dp) {
        double f = Math.pow(10, dp);
        return Math.round(v * f) / f;
    }

    // ------------------------------------------------------------------ one-game share

    /**
     * The share of multi-game starter-weeks whose credited points equal exactly one of that week's game
     * scores; null when there are none (data-model "One-game note").
     */
    public static Double oneGameShare(List<RosterWeekPointsRepository.WeekBreakdown> weeks, List<SeasonGame> games,
                                      Map<String, Double> scoring) {
        Map<String, Map<Integer, List<Double>>> scores = new HashMap<>();
        for (SeasonGame g : games) {
            scores.computeIfAbsent(g.sleeperPlayerId(), k -> new HashMap<>())
                    .computeIfAbsent(g.week(), k -> new ArrayList<>()).add(SCORER.score(scoring, g.stats()));
        }
        int total = 0;
        int match = 0;
        for (RosterWeekPointsRepository.WeekBreakdown w : weeks) {
            if (w.startersJson() == null || w.startersJson().isBlank()
                    || w.playersPointsJson() == null || w.playersPointsJson().isBlank()) continue;
            Map<String, Object> credited = com.ballknowers.draftsim.store.JsonUtil.readMap(w.playersPointsJson());
            for (String pid : WeeklyReportService.startersOf(w.startersJson())) {
                if (!(credited.get(pid) instanceof Number c)) continue;
                List<Double> weekGames = scores.getOrDefault(pid, Map.of()).get(w.week());
                if (weekGames == null || weekGames.size() < 2) continue;
                total++;
                for (double s : weekGames) {
                    if (Math.abs(s - c.doubleValue()) < 0.005) {
                        match++;
                        break;
                    }
                }
            }
        }
        return total == 0 ? null : (double) match / total;
    }
}
