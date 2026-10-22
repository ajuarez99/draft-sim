package com.ballknowers.draftsim.engine;

import com.ballknowers.draftsim.domain.Player;
import com.ballknowers.draftsim.domain.Sport;
import com.ballknowers.draftsim.engine.AdvancedStats.WindowKind;
import com.ballknowers.draftsim.engine.LineupCheck.FpAvg;
import com.ballknowers.draftsim.engine.LineupCheck.LineupFlag;
import com.ballknowers.draftsim.ingest.NextGames;
import com.ballknowers.draftsim.ingest.SleeperClient;
import com.ballknowers.draftsim.ingest.SportSchedule;
import com.ballknowers.draftsim.sport.SportRules;
import com.ballknowers.draftsim.sport.SportRulesRegistry;
import com.ballknowers.draftsim.store.LeagueRepository;
import com.ballknowers.draftsim.store.LeagueRepository.LeagueRow;
import com.ballknowers.draftsim.store.ManagerRepository;
import com.ballknowers.draftsim.store.PlayerRepository;
import com.ballknowers.draftsim.store.RosterSeasonRepository;
import com.ballknowers.draftsim.store.SportScheduleRepository;
import com.ballknowers.draftsim.store.StatusCaptureRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.OptionalInt;
import java.util.Set;

/**
 * The loader behind {@link LineupCheck} (specs/042-fantasypros-design-features US3, contracts/lineup-check.md).
 * The rule is {@link LineupCheck#flags}; this reads what it needs.
 *
 * <p><b>The league row is the URL's</b> (the one {@code visibleLeague} returned), as in
 * {@link NextMatchupService}: the week, the schedule season and the as-of are all this league's, never the
 * resolver's fallback season (review F1's lesson).
 *
 * <p><b>"Mine" is {@link NextMatchupService#myRosterId}</b> (review F8): the caller's manager id, then the
 * lowest roster id. The roster's contents are then read <i>live</i> from Sleeper ({@code starters} is the one
 * thing nothing stores for the current week); a failed call is logged and answered as {@code ROSTERS_FAILED},
 * never thrown, so league home still renders.
 *
 * <p><b>Gate</b> (review F12): a complete or not-yet-drafted league is {@code NOT_IN_SEASON}. The regular-season
 * stop {@code NextMatchupService} applies is deliberately not copied: starters in a playoff week need checking.
 *
 * <p>NBA bench options are ordered by the season average from <b>one</b> {@link PlayerStatsService#readLeaderboard}
 * call per request (F10), made only when some flag has options to order. Football has no average to order by.
 */
@Service
public class LineupCheckService {

    private static final Logger log = LoggerFactory.getLogger(LineupCheckService.class);

    private final LeagueRepository leagues;
    private final RosterSeasonRepository rosterSeasons;
    private final ManagerRepository managers;
    private final PlayerRepository players;
    private final SportRulesRegistry rules;
    private final SportScheduleRepository schedules;
    private final StatusCaptureRepository statusCaptures;
    private final PlayerStatsService stats;
    private final Clock clock;
    private final LeagueRosterSource rosterSource;

    public LineupCheckService(LeagueRepository leagues, RosterSeasonRepository rosterSeasons,
                              ManagerRepository managers, PlayerRepository players, SportRulesRegistry rules,
                              SportScheduleRepository schedules, StatusCaptureRepository statusCaptures,
                              PlayerStatsService stats, SleeperClient sleeper, Clock clock) {
        this.leagues = leagues;
        this.rosterSeasons = rosterSeasons;
        this.managers = managers;
        this.players = players;
        this.rules = rules;
        this.schedules = schedules;
        this.statusCaptures = statusCaptures;
        this.stats = stats;
        this.clock = clock;
        this.rosterSource = new LeagueRosterSource(sleeper, leagues, rosterSeasons);
    }

    public LineupCheck forLeague(LeagueRow league, String sleeperUserId) {
        String status = league.status();
        if (league.complete() || "pre_draft".equals(status) || "drafting".equals(status)) {
            return LineupCheck.unavailable(LineupCheck.NOT_IN_SEASON, null);
        }
        OptionalInt leg = leagues.currentLeg(league.id());
        Integer week = leg.isPresent() ? leg.getAsInt() : null;

        Long callerManagerId = sleeperUserId == null || sleeperUserId.isBlank() ? null
                : ManagerRepository.caller(managers, league.id(), sleeperUserId);
        Integer myRoster = NextMatchupService.myRosterId(rosterSeasons.forLeague(league.id()), callerManagerId);
        if (myRoster == null) return LineupCheck.unavailable(LineupCheck.NOT_MEMBER, week);

        Map<String, Object> mine = null;
        try {
            for (Map<String, Object> r : rosterSource.rosters(league)) {
                if (r.get("roster_id") instanceof Number n && n.intValue() == myRoster) mine = r;
            }
        } catch (RuntimeException e) {
            log.warn("lineup check: live rosters for league {} failed: {}", league.sleeperId(), e.toString());
            return LineupCheck.unavailable(LineupCheck.ROSTERS_FAILED, week);
        }
        if (mine == null) return LineupCheck.unavailable(LineupCheck.ROSTERS_FAILED, week);

        Sport sport = league.sport();
        SportRules sportRules = rules.get(sport);
        LineupCheck.Roster roster = new LineupCheck.Roster(ids(mine.get("starters")), ids(mine.get("players")),
                ids(mine.get("reserve")), ids(mine.get("taxi")));
        List<String> startingSlots = league.rosterPositions().stream()
                .filter(s -> !LeagueAnalysisService.NON_STARTING_SLOTS.contains(s)).toList();

        Set<String> wanted = new LinkedHashSet<>(roster.players());
        wanted.addAll(roster.starters());
        wanted.remove("0");
        Map<String, Player> byId = players.byIds(sport, wanted);

        // The game check needs a stored schedule for the sport/season, a known week, AND that week in the schedule
        // (review S3/S5: the same guard ScheduleGridService.teamsWithoutGame applies; t001 (b): NFL has rows).
        List<SportSchedule.Game> games = schedules.forSeason(sport.code(), league.season());
        String gameCheckReason = games.isEmpty() ? LineupCheck.NO_SCHEDULE
                : week == null ? LineupCheck.WEEK_UNKNOWN
                : !ScheduleGridService.scheduleHoldsWeek(games, week) ? LineupCheck.WEEK_NOT_IN_SCHEDULE : null;
        boolean gameCheck = gameCheckReason == null;
        LocalDate today = NextGames.today(clock);
        int checkedWeek = week == null ? 0 : week;
        LineupCheck.Schedule schedule = !gameCheck ? null : new LineupCheck.Schedule() {
            @Override
            public boolean hasGameInWeek(Player p) {
                return NextGames.hasCountedGameInWeek(games, p.team(), checkedWeek);
            }

            @Override
            public int gamesLeftInWeek(Player p) {
                if (p.team() == null) return 0;
                return (int) NextGames.forTeam(games, p.team(), today, Integer.MAX_VALUE).stream()
                        .filter(g -> g.week() == checkedWeek).count();
            }
        };

        // First pass without averages: the leaderboard is the costly read, and most weeks nothing is flagged.
        List<LineupFlag> flags = LineupCheck.flags(roster, startingSlots, byId, sportRules, schedule, Map.of());
        if (sportRules.playsMultipleGamesPerScoringPeriod() && flags.stream().anyMatch(f -> !f.options().isEmpty())) {
            Map<String, FpAvg> fp = seasonAverages(league, sleeperUserId);
            if (!fp.isEmpty()) {
                flags = LineupCheck.flags(roster, startingSlots, byId, sportRules, schedule, fp);
            }
        }

        Instant asOf = statusCaptures.statusAsOf(sport, league.season()).orElse(null);
        OffsetDateTime scheduleAsOf = games.isEmpty() ? null
                : schedules.fetchedAt(sport.code(), league.season()).orElse(null);
        return new LineupCheck(true, null, week, asOf == null ? null : OffsetDateTime.ofInstant(asOf, ZoneOffset.UTC),
                scheduleAsOf, gameCheck, gameCheckReason, flags);
    }

    /** One leaderboard read: each qualified-or-not player's season fp/g, the season it is from and its games. */
    private Map<String, FpAvg> seasonAverages(LeagueRow league, String sleeperUserId) {
        Map<String, FpAvg> out = new HashMap<>();
        PlayerStatsService.StatLeaderboard board = stats.readLeaderboard(league, WindowKind.SEASON, sleeperUserId);
        if (board == null || !board.available()) return out;
        for (PlayerStatsService.LeaderboardRow r : board.rows()) {
            if (r.fpPerGame() == null || r.stats() == null) continue;
            out.put(r.sleeperPlayerId(), new FpAvg(r.fpPerGame(), board.season(), r.stats().games()));
        }
        return out;
    }

    private static List<String> ids(Object o) {
        if (!(o instanceof List<?> list)) return List.of();
        List<String> out = new ArrayList<>();
        for (Object e : list) if (e != null) out.add(String.valueOf(e));
        return out;
    }
}
