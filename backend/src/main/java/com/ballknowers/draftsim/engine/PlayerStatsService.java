package com.ballknowers.draftsim.engine;

import com.ballknowers.draftsim.config.PlayerStatsProperties;
import com.ballknowers.draftsim.domain.Player;
import com.ballknowers.draftsim.domain.Sport;
import com.ballknowers.draftsim.engine.AdvancedStats.Counting;
import com.ballknowers.draftsim.engine.AdvancedStats.Window;
import com.ballknowers.draftsim.engine.AdvancedStats.WindowKind;
import com.ballknowers.draftsim.engine.LeagueSeasonResolver.SeasonOption;
import com.ballknowers.draftsim.engine.NbaGameLines.Line;
import com.ballknowers.draftsim.engine.DraftAndAdpJoin.AdpState;
import com.ballknowers.draftsim.engine.DraftAndAdpJoin.DraftGradesState;
import com.ballknowers.draftsim.engine.DraftAndAdpJoin.DraftPick;
import com.ballknowers.draftsim.engine.DraftAndAdpJoin.DraftState;
import com.ballknowers.draftsim.engine.PlayerOwnership.AsOf;
import com.ballknowers.draftsim.engine.PlayerOwnership.Facts;
import com.ballknowers.draftsim.engine.PlayerOwnership.Ownership;
import com.ballknowers.draftsim.engine.PlayerTrendsService.PlayerInfo;
import com.ballknowers.draftsim.sport.SportRulesRegistry;
import com.ballknowers.draftsim.store.LeagueMemberRepository;
import com.ballknowers.draftsim.store.LeagueRepository;
import com.ballknowers.draftsim.store.LeagueRepository.LeagueRow;
import com.ballknowers.draftsim.store.ManagerRepository;
import com.ballknowers.draftsim.store.PlayerAbsenceRepository;
import com.ballknowers.draftsim.store.PlayerRepository;
import com.ballknowers.draftsim.store.RosterSeasonRepository;
import com.ballknowers.draftsim.store.RosterWeekPointsRepository;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalInt;

/**
 * The player page's data (specs/022-player-stat-analysis, user story 1; the wire shape is
 * contracts/api.md C1, the math data-model.md as amended after review).
 *
 * <p>{@link #compute(Input, GameScoringService)} is the pure core: plain values in, plain values out.
 * {@link #read} does the reading and the gates. This slice carries the traditional windows, fantasy
 * figures, ownership and the game log; the advanced rates and percentiles (user story 2) are added to
 * {@link Window} and the page record later, as pure additions.
 *
 * <p><b>Fantasy figures always come from {@link GameScoringService}</b> under the answered season's own
 * league (FR-029); real-basketball figures never depend on the league (FR-004). Every hand-set number
 * is {@link PlayerStatsProperties} (ARBITRARY, labelled). Reasons are codes; the sentences live in the web client.
 *
 * <p><b>Ranks are competition ranks on the value rounded to two decimals</b>, what the reader sees, so
 * two players showing the same figure share a rank (F13). Games, name and id order the display within a
 * tie and never change a rank number (I4).
 */
@Service
public class PlayerStatsService {

    public static final String NOT_CONFIGURED = "NOT_CONFIGURED";
    public static final String NOT_BASKETBALL = "NOT_BASKETBALL";
    public static final String NO_GAMES = "NO_GAMES";
    public static final String NO_PLAYER_GAMES = "NO_PLAYER_GAMES";
    public static final String NOT_QUALIFIED = "NOT_QUALIFIED";
    public static final String NOT_QUALIFIED_STALE = "NOT_QUALIFIED_STALE";

    // ------------------------------------------------------------------ wire records (contract C1, user story 1)

    /**
     * {@code known} is false when there is no {@code player} row (or none was read): {@code name} is then
     * null, {@code positions} empty and {@code team} null. {@code team} is today's team, nullable (a free
     * agent with no NBA team).
     */
    public record PlayerRef(String sleeperPlayerId, String name, List<String> positions, String team,
                            boolean known) {}

    /**
     * Season ranks among qualified players (data-model "Ranks"). All the numbers are null when the player
     * is not qualified, with {@code reason} NOT_QUALIFIED; {@code groupSize} stays. {@code position} and
     * {@code positionRank}/{@code positionGroupSize} are null when the player has no position (no player row).
     * {@code rankMove} is {@code pointsRank - leagueRank}.
     */
    public record Ranks(Integer leagueRank, Integer positionRank, Integer pointsRank, Integer rankMove,
                        Integer groupSize, Integer positionGroupSize, String position, String reason) {}

    /** One scoring category's season points; {@code share} is null when the season total is not above 0. */
    public record BreakdownRow(String key, double points, Double share) {}

    /**
     * {@code fpPerGame} has an entry per window, null (the value) for a window with no games, rounded to
     * two decimals. {@code ranks} are the SEASON window's. {@code breakdown} is the season's, by points
     * descending, zero-point categories left out. {@code seasonTotal} is the sum of the games' scores.
     */
    public record Fantasy(Map<WindowKind, Double> fpPerGame, Ranks ranks, List<BreakdownRow> breakdown,
                          double seasonTotal) {}

    /**
     * One played game, newest first in the log. {@code isHome} is null when the stored row has no
     * {@code is_away}; {@code team} is null when the game has no team row.
     */
    public record GameLogRow(String gameId, LocalDate date, int week, String team, String opponent, Boolean isHome,
                             double minutes, Counting line, double plusMinus, double gameScore,
                             double fantasyPoints) {}

    /**
     * Contract C1. When {@code available} is false only {@code sport}, {@code season},
     * {@code requestedSeason}, {@code reason}, {@code seasons} and {@code player} are meaningful:
     * {@code windows} is empty, {@code fantasy} and {@code ownership} are null, the lists are empty.
     * NO_PLAYER_GAMES is available: windows with 0 games, fantasy without ranks, a game log of [].
     *
     * @param qualification    the ranking rule in force, computed from the season's data; null when unavailable
     * @param requestedSeason  set when the resolver moved to an earlier season (R7); else null
     * @param dataAsOf         {@code max(player_game.fetched_at)} for the season; null when unknown
     * @param currentOwnership the requested season's current ownership; only when {@code requestedSeason} is set (F6)
     * @param ownership        at the end of the answered season's regular season, or current when it is in progress
     */
    public record PlayerStatsPage(String sport, int season, Integer requestedSeason, boolean available,
                                  String reason, OffsetDateTime dataAsOf, List<SeasonOption> seasons,
                                  Ownership currentOwnership, PlayerRef player, List<String> teamsThisSeason,
                                  int teamGamesMissed, Ownership ownership, Map<WindowKind, Window> windows,
                                  Fantasy fantasy, Map<WindowKind, Map<String, List<Pct>>> percentiles,
                                  List<GameLogRow> gameLog, QualificationRule qualification) {}

    /**
     * Who the page ranks (the rule behind {@link #qualify}), so the client never restates it. {@code minGames}
     * is {@code ceil(minGamesShare x maxTeamGames)} and {@code maxTeamGames} the most games any team has played
     * in the season's stored data. Null on a gated page.
     */
    public record QualificationRule(double minGamesShare, int minGames, int maxTeamGames, int minMinutesPerGame,
                                    int recencyDays) {}

    /**
     * One percentile of one advanced rate against one group (see {@link PlayerPercentiles}). Exactly one of
     * {@code value} (0..100) and {@code reason} is non-null. {@code group} is {@code NBA_POSITION} or
     * {@code LEAGUE_ROSTERED}; {@code n} is the group's size excluding the player himself. Reasons:
     * {@code NOT_QUALIFIED}, {@code NOT_QUALIFIED_STALE}, {@code GROUP_TOO_SMALL}, {@code OWNERSHIP_UNAVAILABLE},
     * or, when his own rate has none, that rate's reason ({@code NO_ATTEMPTS}, {@code NO_MINUTES}, {@code NO_TEAM_ROW}).
     */
    public record Pct(Double value, String group, int n, String reason) {
        public Pct {
            if ((value == null) == (reason == null)) {
                throw new IllegalArgumentException("a Pct has exactly one of value and reason");
            }
        }
    }

    // ------------------------------------------------------------------ wire records (contract C2, user story 4)

    /**
     * One player in the leaderboard. {@code stats} is {@code read}'s window for the same player and window
     * (I6). {@code qualified} follows the window's rule and {@code reason} is {@code NOT_QUALIFIED} or
     * {@code NOT_QUALIFIED_STALE} when it is false. The four ranks and the two replacement fields are null for a
     * player who is not qualified; {@code fpPerGame} is not. {@code draft} is null for a player with no pick (and
     * always unless the draft state is {@code COMPLETE}), {@code draftValue} is Draft Grades' {@code valueOverSlot}
     * for that pick or null, {@code adp} the blend's ADP at the draft's start or null.
     * {@code currentOwnership} is set only when the answered season fell back from the one asked for (F6).
     */
    public record LeaderboardRow(String sleeperPlayerId, String name, List<String> positions, String team,
                                 Ownership ownership, Ownership currentOwnership, boolean qualified, String reason,
                                 Window stats, Double fpPerGame, Integer leagueRank, Integer positionRank,
                                 Integer pointsRank, Integer rankMove, Double valueOverReplacement,
                                 String vorPosition, DraftPick draft, Double draftValue, Double adp) {}

    /**
     * Contract C2. When {@code available} is false only {@code sport}, {@code season}, {@code requestedSeason},
     * {@code reason}, {@code window} and {@code seasons} are meaningful; the rest is null and {@code rows} empty.
     * {@code ownershipAsOf} is the point in time every row's ownership is read at (null when unknown).
     * {@code scoringSeason} is the season whose league scoring scored every fantasy figure;
     * {@code scoringMatchesRequested} is null when there was no fallback (or when either league's scoring is
     * not stored), else whether the requested season's league scoring is identical to it, ignoring entries
     * weighted 0 (see {@code sameScoring}). Both are null when unavailable.
     * Ranks, replacement and the qualification rule are over the qualified, non-stale group of this window only.
     */
    public record StatLeaderboard(String sport, int season, Integer requestedSeason, Integer scoringSeason,
                                  Boolean scoringMatchesRequested, boolean available, String reason, OffsetDateTime dataAsOf, List<SeasonOption> seasons,
                                  WindowKind window, QualificationRule qualification, AsOf ownershipAsOf,
                                  DraftState draft, AdpState adp, DraftGradesState draftGrades,
                                  ReplacementLevel.Replacement replacement, List<LeaderboardRow> rows) {}

    // ------------------------------------------------------------------ pure core types

    /**
     * @param lines   the season's {@link NbaGameLines}
     * @param infos   sleeperPlayerId to player row facts; a player with none is unknown
     * @param target  the player the page is about (may have no lines)
     * @param absences the target's stored absences for the season (empty when none)
     */
    public record Input(PlayerStatsProperties props, Map<String, Double> scoring, NbaGameLines lines,
                        Map<String, PlayerInfo> infos, String target,
                        List<PlayerAbsenceRepository.Row> absences) {}

    /** Whether a player counts for ranks in a window, and if not, why ({@code NOT_QUALIFIED} or {@code NOT_QUALIFIED_STALE}). */
    public record Qualification(boolean qualified, String reason) {}

    public record Computed(Map<WindowKind, Window> windows, Fantasy fantasy, int teamGamesMissed,
                           List<String> teams, List<GameLogRow> gameLog,
                           Map<WindowKind, Qualification> qualification) {}

    /** One player in a ranking: {@code value} is already rounded to two decimals. */
    public record Candidate(String sleeperPlayerId, String name, int games, double value) {}

    public record Ranked(Candidate candidate, int rank) {}

    // ------------------------------------------------------------------ wiring

    private final PlayerStatsProperties props;
    private final SeasonBoxCache boxCache;
    private final LeagueSeasonResolver resolver;
    private final GameScoringService scorer;
    private final LeagueRepository leagues;
    private final PlayerRepository players;
    private final RosterSeasonRepository rosterSeasons;
    private final LeagueMemberRepository members;
    private final ManagerRepository managers;
    private final RosterWeekPointsRepository weekPoints;
    private final SportRulesRegistry rules;
    private final PlayerAbsenceRepository absences;
    private final DraftAndAdpJoin draftJoin;

    public PlayerStatsService(PlayerStatsProperties props, SeasonBoxCache boxCache, LeagueSeasonResolver resolver,
                              GameScoringService scorer, LeagueRepository leagues, PlayerRepository players,
                              RosterSeasonRepository rosterSeasons, LeagueMemberRepository members,
                              ManagerRepository managers, RosterWeekPointsRepository weekPoints,
                              SportRulesRegistry rules, PlayerAbsenceRepository absences,
                              DraftAndAdpJoin draftJoin) {
        this.props = props;
        this.boxCache = boxCache;
        this.resolver = resolver;
        this.scorer = scorer;
        this.leagues = leagues;
        this.players = players;
        this.rosterSeasons = rosterSeasons;
        this.members = members;
        this.managers = managers;
        this.weekPoints = weekPoints;
        this.rules = rules;
        this.absences = absences;
        this.draftJoin = draftJoin;
    }

    // ------------------------------------------------------------------ read

    /**
     * The state check order is Trends': configuration, then sport, then no games, then compute.
     *
     * @param requested the league the caller is allowed to see, and the route's season
     * @return empty when the player has no games in the answered season and no {@code player} row (the controller's 404)
     */
    public Optional<PlayerStatsPage> read(LeagueRow requested, String sleeperPlayerId, String requester) {
        Sport sport = requested.sport();
        PlayerRef unknown = new PlayerRef(sleeperPlayerId, null, List.of(), null, false);
        if (!props.loaded()) {
            return Optional.of(unavailable(sport, requested.season(), null, NOT_CONFIGURED, null, List.of(), unknown));
        }
        if (!rules.get(sport).playsMultipleGamesPerScoringPeriod()) {
            return Optional.of(unavailable(sport, requested.season(), null, NOT_BASKETBALL, null, List.of(), unknown));
        }

        LeagueSeasonResolver.Resolved resolved = resolver.resolve(requested.sleeperId(),
                LeagueSeasonResolver.Rule.STORED_GAMES).orElse(new LeagueSeasonResolver.Resolved(requested, null));
        LeagueRow league = resolved.league();
        List<SeasonOption> seasons = resolver.seasons(requested.sleeperId());
        SeasonBoxCache.Season box = boxCache.get(sport, league.season());
        OffsetDateTime dataAsOf = box.token().maxFetchedAt();

        Map<String, PlayerInfo> infos = new HashMap<>();
        for (Map.Entry<String, Player> e : players.byIds(sport, box.lines().byPlayer().keySet()).entrySet()) {
            Player p = e.getValue();
            infos.put(e.getKey(), new PlayerInfo(p.name(), p.positions().stream().map(Enum::name).toList(), p.team()));
        }
        PlayerInfo targetInfo = infos.get(sleeperPlayerId);
        if (targetInfo == null) {
            Player p = players.byIds(sport, List.of(sleeperPlayerId)).get(sleeperPlayerId);
            if (p != null) {
                targetInfo = new PlayerInfo(p.name(), p.positions().stream().map(Enum::name).toList(), p.team());
                infos.put(sleeperPlayerId, targetInfo);
            }
        }
        PlayerRef ref = targetInfo == null ? unknown
                : new PlayerRef(sleeperPlayerId, targetInfo.name(), targetInfo.positions(), targetInfo.team(), true);

        if (box.games().isEmpty()) {
            return Optional.of(unavailable(sport, league.season(), resolved.requestedSeason(), NO_GAMES, dataAsOf,
                    seasons, ref));
        }
        List<Line> mine = box.lines().byPlayer().get(sleeperPlayerId);
        boolean hasGames = mine != null && !mine.isEmpty();
        if (!hasGames && targetInfo == null) return Optional.empty();

        Map<String, Double> scoring = leagues.scoringOf(league.id());
        Computed c = compute(new Input(props, scoring, box.lines(), infos, sleeperPlayerId,
                absences.forPlayers(sport, league.season(), List.of(sleeperPlayerId))), scorer);

        Long callerManagerId = requester == null || requester.isBlank() ? null
                : managers.idsBySleeperUserId().get(requester);
        Map<Integer, RosterOwners.RosterOwner> owners = RosterOwners.ownerNames(rosterSeasons.forLeague(league.id()),
                members.forLeague(league.id()), callerManagerId);
        Facts seasonFacts = facts(league, owners);
        Ownership ownership = PlayerOwnership.forSeasonView(sleeperPlayerId, seasonFacts);
        java.util.Set<String> rostered = PlayerOwnership.rosteredAtSeasonView(seasonFacts).orElse(null);
        String firstPosition = targetInfo == null || targetInfo.positions().isEmpty() ? null
                : targetInfo.positions().getFirst();
        Map<WindowKind, Map<String, List<Pct>>> percentiles = PlayerPercentiles.of(
                PlayerPercentiles.population(box.lines(), infos, props), sleeperPlayerId, firstPosition, c.windows(),
                c.qualification(), rostered);
        Ownership currentOwnership = null;
        if (resolved.requestedSeason() != null) {
            Map<Integer, RosterOwners.RosterOwner> requestedOwners = RosterOwners.ownerNames(
                    rosterSeasons.forLeague(requested.id()), members.forLeague(requested.id()), callerManagerId);
            currentOwnership = PlayerOwnership.currentOf(sleeperPlayerId, facts(requested, requestedOwners));
        }

        return Optional.of(new PlayerStatsPage(sport.code(), league.season(), resolved.requestedSeason(), true,
                hasGames ? null : NO_PLAYER_GAMES, dataAsOf, seasons, currentOwnership, ref, c.teams(),
                c.teamGamesMissed(), ownership, c.windows(), c.fantasy(), percentiles, c.gameLog(),
                new QualificationRule(props.rankMinGamesShare(), minGames(box.lines(), props),
                        maxTeamGames(box.lines()), props.rankMinMinutesPerGame(), props.recencyDays())));
    }

    /**
     * The facts {@link PlayerOwnership} reads for one league-season. Only the source the season needs is
     * loaded: the V28 rosters for a season in progress, the stored weeks for a completed one.
     * {@code lastWeek} is the last playoff week when the format names it, else the last stored week.
     */
    Facts facts(LeagueRow league, Map<Integer, RosterOwners.RosterOwner> owners) {
        boolean complete = league.complete();
        Optional<LeagueRepository.PlayoffFormat> format = leagues.playoffFormat(league.id());
        Integer playoffStart = format.map(LeagueRepository.PlayoffFormat::playoffWeekStart).orElse(null);
        OptionalInt leg = leagues.currentLeg(league.id());
        RosterSeasonRepository.Rostered current = complete ? null : rosterSeasons.rosteredPlayers(league.id()).orElse(null);
        Map<Integer, Map<Integer, java.util.Set<String>>> weeks = complete
                ? PlayerOwnership.weekRosters(weekPoints.breakdownsFor(league.id(), league.season()))
                : Map.of();
        Integer lastWeek = format.flatMap(f -> {
            OptionalInt w = f.lastPlayoffWeek();
            return w.isPresent() ? Optional.of(w.getAsInt()) : Optional.<Integer>empty();
        }).orElse(weeks.keySet().stream().max(Integer::compare).orElse(null));
        return new Facts(league.status(), playoffStart, leg.isPresent() ? leg.getAsInt() : null, lastWeek, current,
                weeks, owners);
    }

    private static PlayerStatsPage unavailable(Sport sport, int season, Integer requestedSeason, String reason,
                                               OffsetDateTime dataAsOf, List<SeasonOption> seasons, PlayerRef player) {
        return new PlayerStatsPage(sport.code(), season, requestedSeason, false, reason, dataAsOf, seasons, null,
                player, List.of(), 0, null, new EnumMap<>(WindowKind.class), null, new EnumMap<>(WindowKind.class),
                List.of(), null);
    }

    // ------------------------------------------------------------------ leaderboard (contract C2)

    /**
     * Every player with a game in the answered season, in {@code window}, with ranks, value over
     * replacement and the draft join. The gates and the season resolution are {@link #read}'s.
     *
     * <p>Each row's window, qualification and fantasy points per game come from {@link #windowFigures}, the
     * computation {@link #read} uses, so they equal the player page's (I6). Ranks, replacement and the
     * qualification are over the qualified, non-stale group of this window; the order of the rows is
     * {@link #rank}'s (value, games, name, id), over every row.
     */
    public StatLeaderboard readLeaderboard(LeagueRow requested, WindowKind window, String requester) {
        Sport sport = requested.sport();
        if (!props.loaded()) return unavailableBoard(sport, requested.season(), null, NOT_CONFIGURED, null, List.of(), window);
        if (!rules.get(sport).playsMultipleGamesPerScoringPeriod()) {
            return unavailableBoard(sport, requested.season(), null, NOT_BASKETBALL, null, List.of(), window);
        }
        LeagueSeasonResolver.Resolved resolved = resolver.resolve(requested.sleeperId(),
                LeagueSeasonResolver.Rule.STORED_GAMES).orElse(new LeagueSeasonResolver.Resolved(requested, null));
        LeagueRow league = resolved.league();
        List<SeasonOption> seasons = resolver.seasons(requested.sleeperId());
        SeasonBoxCache.Season box = boxCache.get(sport, league.season());
        OffsetDateTime dataAsOf = box.token().maxFetchedAt();
        if (box.games().isEmpty()) {
            return unavailableBoard(sport, league.season(), resolved.requestedSeason(), NO_GAMES, dataAsOf, seasons, window);
        }

        NbaGameLines lines = box.lines();
        Map<String, Player> byId = players.byIds(sport, lines.byPlayer().keySet());
        Map<String, Double> scoring = leagues.scoringOf(league.id());
        int minGames = minGames(lines, props);
        LocalDate latest = latestGame(lines);

        Map<String, WindowFigures> figures = new HashMap<>();
        Map<String, List<Line>> inWindow = new HashMap<>();
        for (Map.Entry<String, List<Line>> e : lines.byPlayer().entrySet()) {
            if (e.getValue().isEmpty()) continue;
            figures.put(e.getKey(), windowFigures(window, e.getValue(), scoring, scorer, minGames, props, latest));
            inWindow.put(e.getKey(), window.select(e.getValue()));
        }

        // The qualified, non-stale group: the only one ranks and replacement are taken over.
        List<Candidate> everyone = new ArrayList<>();
        List<Candidate> byFp = new ArrayList<>();
        List<Candidate> byPts = new ArrayList<>();
        Map<String, List<Candidate>> byPositionFp = new HashMap<>();
        List<ReplacementLevel.Qualified> qualified = new ArrayList<>();
        for (Map.Entry<String, WindowFigures> e : figures.entrySet()) {
            String id = e.getKey();
            Player pl = byId.get(id);
            String name = pl == null ? null : pl.name();
            List<Line> sub = inWindow.get(id);
            WindowFigures f = e.getValue();
            everyone.add(new Candidate(id, name, sub.size(), f.fpPerGame()));
            if (!f.qualification().qualified()) continue;
            Candidate fp = new Candidate(id, name, sub.size(), f.fpPerGame());
            byFp.add(fp);
            byPts.add(new Candidate(id, name, sub.size(), ptsPerGame(sub)));
            if (pl != null && !pl.positions().isEmpty()) {
                byPositionFp.computeIfAbsent(pl.positions().getFirst().name(), k -> new ArrayList<>()).add(fp);
            }
            if (pl != null) qualified.add(new ReplacementLevel.Qualified(pl, sub.size(), f.fpPerGame()));
        }
        Map<String, Integer> leagueRank = ranks(byFp);
        Map<String, Integer> pointsRank = ranks(byPts);
        Map<String, Integer> positionRank = new HashMap<>();
        for (List<Candidate> group : byPositionFp.values()) positionRank.putAll(ranks(group));
        ReplacementLevel.Result replacement = ReplacementLevel.of(qualified, league.rosterPositions(),
                league.totalRosters(), rules.get(sport));

        Long callerManagerId = requester == null || requester.isBlank() ? null
                : managers.idsBySleeperUserId().get(requester);
        Map<Integer, RosterOwners.RosterOwner> owners = RosterOwners.ownerNames(rosterSeasons.forLeague(league.id()),
                members.forLeague(league.id()), callerManagerId);
        Facts seasonFacts = facts(league, owners);
        Facts requestedFacts = null;
        if (resolved.requestedSeason() != null) {
            requestedFacts = facts(requested, RosterOwners.ownerNames(rosterSeasons.forLeague(requested.id()),
                    members.forLeague(requested.id()), callerManagerId));
        }
        AsOf ownershipAsOf = PlayerOwnership.forSeasonView("", seasonFacts).asOf();
        DraftAndAdpJoin.Joined joined = draftJoin.join(requested, box.token());

        List<LeaderboardRow> rows = new ArrayList<>(everyone.size());
        for (Ranked r : rank(everyone)) {
            String id = r.candidate().sleeperPlayerId();
            Player pl = byId.get(id);
            WindowFigures f = figures.get(id);
            boolean q = f.qualification().qualified();
            ReplacementLevel.Vor vor = q && pl != null ? replacement.byPlayer().get(pl.sleeperId()) : null;
            Integer lr = q ? leagueRank.get(id) : null;
            Integer pr = q ? pointsRank.get(id) : null;
            rows.add(new LeaderboardRow(id, pl == null ? null : pl.name(),
                    pl == null ? List.of() : pl.positions().stream().map(Enum::name).toList(),
                    pl == null ? null : pl.team(),
                    PlayerOwnership.forSeasonView(id, seasonFacts),
                    requestedFacts == null ? null : PlayerOwnership.currentOf(id, requestedFacts),
                    q, f.qualification().reason(), f.window(), f.fpPerGame(), lr,
                    q ? positionRank.get(id) : null, pr, lr == null || pr == null ? null : pr - lr,
                    vor == null ? null : vor.value(), vor == null ? null : vor.position(),
                    joined.picks().get(id), joined.draftValue().get(id), joined.adpOf(id)));
        }
        Boolean scoringMatchesRequested = resolved.requestedSeason() == null ? null
                : sameScoring(scoring, leagues.scoringOf(requested.id()));
        return new StatLeaderboard(sport.code(), league.season(), resolved.requestedSeason(), league.season(),
                scoringMatchesRequested, true, null, dataAsOf,
                seasons, window,
                new QualificationRule(props.rankMinGamesShare(), minGames, maxTeamGames(lines),
                        props.rankMinMinutesPerGame(), props.recencyDays()),
                ownershipAsOf, joined.draft(), joined.adp(), joined.draftGrades(), replacement.replacement(), rows);
    }

    /**
     * Whether two leagues score identically, or null when that can't be said. An entry weighted
     * 0.0 contributes nothing (the scorer treats it exactly like an absent key), so zero entries
     * are dropped before comparing: a season that merely adds a zero-weight stat key is not a
     * scoring change. An empty map means the scoring isn't stored (not yet ingested), which is
     * "unknown", not "changed". Amended after code review (spec 023 N1, N2).
     */
    static Boolean sameScoring(Map<String, Double> a, Map<String, Double> b) {
        if (a.isEmpty() || b.isEmpty()) return null;
        return nonZero(a).equals(nonZero(b));
    }

    private static Map<String, Double> nonZero(Map<String, Double> m) {
        Map<String, Double> out = new HashMap<>();
        m.forEach((k, v) -> {
            if (v != null && v != 0.0) out.put(k, v);
        });
        return out;
    }

    private static Map<String, Integer> ranks(List<Candidate> group) {
        Map<String, Integer> out = new HashMap<>();
        for (Ranked r : rank(group)) out.put(r.candidate().sleeperPlayerId(), r.rank());
        return out;
    }

    private static StatLeaderboard unavailableBoard(Sport sport, int season, Integer requestedSeason, String reason,
                                                    OffsetDateTime dataAsOf, List<SeasonOption> seasons,
                                                    WindowKind window) {
        return new StatLeaderboard(sport.code(), season, requestedSeason, null, null, false, reason, dataAsOf, seasons, window,
                null, null, null, null, null, null, List.of());
    }

    // ------------------------------------------------------------------ pure core

    public static Computed compute(Input in, GameScoringService scorer) {
        PlayerStatsProperties p = in.props();
        Map<String, List<Line>> byPlayer = in.lines().byPlayer();
        List<Line> lines = byPlayer.getOrDefault(in.target(), List.of());

        LocalDate latest = latestGame(in.lines());
        int minGames = minGames(in.lines(), p);

        Map<WindowKind, Window> windows = new EnumMap<>(WindowKind.class);
        Map<WindowKind, Qualification> qualification = new EnumMap<>(WindowKind.class);
        Map<WindowKind, Double> fpPerGame = new EnumMap<>(WindowKind.class);
        for (WindowKind k : WindowKind.values()) {
            WindowFigures f = windowFigures(k, lines, in.scoring(), scorer, minGames, p, latest);
            windows.put(k, f.window());
            qualification.put(k, f.qualification());
            fpPerGame.put(k, f.fpPerGame());
        }

        // Season ranks over every qualified player.
        List<Candidate> byFp = new ArrayList<>();
        List<Candidate> byPts = new ArrayList<>();
        for (Map.Entry<String, List<Line>> e : byPlayer.entrySet()) {
            List<Line> season = e.getValue();
            if (!qualify(WindowKind.SEASON, season, minGames, p, latest).qualified()) continue;
            PlayerInfo info = in.infos().get(e.getKey());
            String name = info == null ? null : info.name();
            byFp.add(new Candidate(e.getKey(), name, season.size(),
                    round2(fantasyTotal(scorer, in.scoring(), season) / season.size())));
            byPts.add(new Candidate(e.getKey(), name, season.size(), ptsPerGame(season)));
        }
        PlayerInfo targetInfo = in.infos().get(in.target());
        String position = targetInfo == null || targetInfo.positions().isEmpty() ? null : targetInfo.positions().getFirst();
        int positionGroup = 0;
        List<Candidate> positionFp = new ArrayList<>();
        if (position != null) {
            for (Candidate c : byFp) {
                PlayerInfo info = in.infos().get(c.sleeperPlayerId());
                if (info != null && !info.positions().isEmpty() && position.equals(info.positions().getFirst())) {
                    positionFp.add(c);
                }
            }
            positionGroup = positionFp.size();
        }
        Ranks ranks;
        if (!qualification.get(WindowKind.SEASON).qualified()) {
            ranks = new Ranks(null, null, null, null, byFp.size(), position == null ? null : positionGroup, position,
                    NOT_QUALIFIED);
        } else {
            int league = rankOf(rank(byFp), in.target());
            int pts = rankOf(rank(byPts), in.target());
            Integer posRank = position == null ? null : rankOf(rank(positionFp), in.target());
            ranks = new Ranks(league, posRank, pts, pts - league, byFp.size(), position == null ? null : positionGroup,
                    position, null);
        }

        // Season breakdown: the per-category sums, in scoring-key order before sorting (naive left to right).
        Map<String, Double> sums = new LinkedHashMap<>();
        double total = 0.0;
        List<GameLogRow> log = new ArrayList<>(lines.size());
        for (Line l : lines) {
            for (Map.Entry<String, Double> e : scorer.contributions(in.scoring(), l.stats()).entrySet()) {
                sums.merge(e.getKey(), e.getValue(), Double::sum);
            }
            double fp = scorer.score(in.scoring(), l.stats());
            total += fp;
            log.add(new GameLogRow(l.gameId(), l.date(), l.week(), l.team(), l.opponent(), l.isHome(), l.minutes(),
                    Counting.of(l.stats()), AdvancedStats.num(l.stats(), "plus_minus"), AdvancedStats.gameScore(l),
                    fp));
        }
        double seasonTotal = round2(total);
        List<BreakdownRow> breakdown = new ArrayList<>();
        for (Map.Entry<String, Double> e : sums.entrySet()) {
            if (e.getValue() == 0.0) continue;
            breakdown.add(new BreakdownRow(e.getKey(), e.getValue(), seasonTotal > 0 ? e.getValue() / seasonTotal : null));
        }
        breakdown.sort(Comparator.comparingDouble(BreakdownRow::points).reversed().thenComparing(BreakdownRow::key));
        Collections.reverse(log);

        LinkedHashSet<String> teams = new LinkedHashSet<>();
        for (Line l : lines) if (l.team() != null) teams.add(l.team());

        return new Computed(windows, new Fantasy(fpPerGame, ranks, breakdown, seasonTotal),
                AdvancedStats.teamGamesMissed(lines, in.lines().teamGames(), in.absences()), List.copyOf(teams), log, qualification);
    }

    /** One player's figures in one window: the numbers the player page and a leaderboard row both show. */
    record WindowFigures(Window window, Qualification qualification, Double fpPerGame) {}

    /**
     * The one per-player computation of a window (spec 022 I6, FR-025): the player page's
     * {@link #compute} and the leaderboard's rows both call it, so a row's values equal the page's by
     * construction. {@code seasonLines} is the player's season, oldest first; {@code fpPerGame} is null for
     * an empty window and is rounded to two decimals, the value the ranks use.
     */
    static WindowFigures windowFigures(WindowKind k, List<Line> seasonLines, Map<String, Double> scoring,
                                       GameScoringService scorer, int minGames, PlayerStatsProperties p,
                                       LocalDate latest) {
        List<Line> sub = k.select(seasonLines);
        return new WindowFigures(AdvancedStats.window(sub, p.smallSampleMinutes()),
                qualify(k, sub, minGames, p, latest),
                sub.isEmpty() ? null : round2(fantasyTotal(scorer, scoring, sub) / sub.size()));
    }

    /** Points per game over {@code lines}, rounded to two decimals: the value of the points rank. */
    static double ptsPerGame(List<Line> lines) {
        return round2(ptsTotal(lines) / lines.size());
    }

    /** The most games any team has played so far, times the rank share, rounded up (the SEASON qualification). */
    static int minGames(NbaGameLines lines, PlayerStatsProperties p) {
        return (int) Math.ceil(p.rankMinGamesShare() * maxTeamGames(lines));
    }

    /** The most games any team has played in the season's stored data. */
    static int maxTeamGames(NbaGameLines lines) {
        int max = 0;
        for (List<?> g : lines.teamGames().values()) max = Math.max(max, g.size());
        return max;
    }

    /** The season's latest stored game date; null when there are no player lines. */
    static LocalDate latestGame(NbaGameLines lines) {
        LocalDate latest = null;
        for (List<Line> l : lines.byPlayer().values()) {
            LocalDate d = l.getLast().date();
            if (latest == null || d.isAfter(latest)) latest = d;
        }
        return latest;
    }

    /**
     * data-model "Qualification". SEASON: {@code games >= ceil(share x maxTeamGames)} and the minutes rule.
     * LAST_N: all N games played, the minutes rule, and a last game within {@code recency-days} of the
     * season's latest stored game; failing only the recency rule is {@code NOT_QUALIFIED_STALE}.
     */
    static Qualification qualify(WindowKind kind, List<Line> window, int minGames, PlayerStatsProperties p,
                                 LocalDate latest) {
        if (window.isEmpty()) return new Qualification(false, NOT_QUALIFIED);
        double minutes = 0;
        for (Line l : window) minutes += l.minutes();
        boolean minutesOk = minutes / window.size() >= p.rankMinMinutesPerGame();
        if (kind == WindowKind.SEASON) {
            boolean ok = window.size() >= minGames && minutesOk;
            return new Qualification(ok, ok ? null : NOT_QUALIFIED);
        }
        if (window.size() < kind.lastN() || !minutesOk) return new Qualification(false, NOT_QUALIFIED);
        LocalDate last = window.getLast().date();
        boolean stale = latest != null && last.isBefore(latest.minusDays(p.recencyDays()));
        return stale ? new Qualification(false, NOT_QUALIFIED_STALE) : new Qualification(true, null);
    }

    /**
     * Competition ranks (1, 1, 3) on {@link Candidate#value}, returned in display order: value descending,
     * then games descending, name ascending (a missing name last), sleeper id. The order never changes a
     * rank: players with equal values share the better number (F13, I4).
     */
    public static List<Ranked> rank(List<Candidate> candidates) {
        List<Candidate> sorted = new ArrayList<>(candidates);
        sorted.sort(Comparator.comparingDouble(Candidate::value).reversed()
                .thenComparing(Comparator.comparingInt(Candidate::games).reversed())
                .thenComparing(Candidate::name, Comparator.nullsLast(Comparator.naturalOrder()))
                .thenComparing(Candidate::sleeperPlayerId));
        List<Ranked> out = new ArrayList<>(sorted.size());
        int rank = 0;
        for (int i = 0; i < sorted.size(); i++) {
            if (i == 0 || Double.compare(sorted.get(i).value(), sorted.get(i - 1).value()) != 0) rank = i + 1;
            out.add(new Ranked(sorted.get(i), rank));
        }
        return out;
    }

    private static int rankOf(List<Ranked> ranked, String id) {
        for (Ranked r : ranked) if (r.candidate().sleeperPlayerId().equals(id)) return r.rank();
        throw new IllegalStateException("a qualified player is in his own ranking: " + id);
    }

    /** Naive left-to-right sum of per-game scores, in game order (N3). */
    private static double fantasyTotal(GameScoringService scorer, Map<String, Double> scoring, List<Line> lines) {
        double total = 0.0;
        for (Line l : lines) total += scorer.score(scoring, l.stats());
        return total;
    }

    private static double ptsTotal(List<Line> lines) {
        double total = 0.0;
        for (Line l : lines) total += AdvancedStats.num(l.stats(), "pts");
        return total;
    }

    static double round2(double v) {
        return Math.round(v * 100.0) / 100.0;
    }
}
