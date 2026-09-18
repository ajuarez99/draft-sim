package com.ballknowers.draftsim.engine;

import com.ballknowers.draftsim.domain.*;
import com.ballknowers.draftsim.sport.SportRules;
import com.ballknowers.draftsim.sport.SportRulesRegistry;
import com.ballknowers.draftsim.store.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.*;

/**
 * Total points, potential points and efficiency per team -- the Roster
 * Management view (specs/004-ffwrapped-feature-parity, US2).
 *
 * <p>Read-only and computed on request, the same shape as
 * {@link LeagueAnalysisService}: nothing here is a claim about a moment, so
 * nothing is snapshotted.
 *
 * <p><b>No new ingest.</b> Every input is already stored:
 * {@code roster_week_points.starters_points} is what each roster actually
 * scored, and {@code players_points} is the per-player breakdown the optimal
 * lineup is computed from. V5 has been storing both since the beginning, and
 * its own comment anticipated this use.
 *
 * <p><b>No sport branch.</b> Sport-specific behaviour reaches this class only
 * through {@link SportRules}, via {@link RealizedLineupService}. That is what
 * makes NBA work through the same code path rather than a parallel one
 * (FR-004, SC-002).
 */
@Service
public class RosterManagementService {

    private static final Logger log = LoggerFactory.getLogger(RosterManagementService.class);

    private final LeagueRepository leagues;
    private final RosterWeekPointsRepository weekPoints;
    private final RosterSeasonRepository rosterSeasons;
    private final PlayerRepository players;
    private final LeagueMemberRepository members;
    private final SportRulesRegistry rulesRegistry;
    private final RealizedLineupService realized;
    private final LeagueSeasonResolver seasons;

    public RosterManagementService(LeagueRepository leagues,
                                   RosterWeekPointsRepository weekPoints,
                                   RosterSeasonRepository rosterSeasons,
                                   PlayerRepository players,
                                   LeagueMemberRepository members,
                                   SportRulesRegistry rulesRegistry,
                                   RealizedLineupService realized,
                                   LeagueSeasonResolver seasons) {
        this.leagues = leagues;
        this.weekPoints = weekPoints;
        this.rosterSeasons = rosterSeasons;
        this.players = players;
        this.members = members;
        this.rulesRegistry = rulesRegistry;
        this.realized = realized;
        this.seasons = seasons;
    }

    /**
     * One team's season.
     *
     * @param efficiency total / potential, or <b>null</b> when potential is 0.
     *                   Never 1.0: a team that has not scored has no
     *                   efficiency, and printing a perfect score for it would
     *                   be the most flattering possible wrong answer (US2.4).
     * @param weeksExcluded weeks dropped for want of a per-player breakdown.
     *                      Carried to the caller so the page can show it --
     *                      an excluded week must be visible, not silently
     *                      missing from a total (FR-007, US2.5).
     */
    public record TeamRow(int rosterId, Long managerId, String teamName, String avatarId,
                          double totalPoints, double potentialPoints, Double efficiency,
                          int weeksCounted, List<Integer> weeksExcluded) {}

    /**
     * @param available false when the league has no scored weeks at all. The
     *                  honest answer then is no answer, not a table of zeros
     *                  (US2.4) -- same discipline as
     *                  {@code PlayoffOddsService} refusing before a week is
     *                  scored.
     */
    /**
     * @param requestedSeason non-null when the reader asked for a season that
     *                        has not been played and this is answering about an
     *                        earlier one. The page says so rather than quietly
     *                        showing a different year.
     */
    public record Result(boolean available, String reason, int season, Integer requestedSeason,
                         Sport sport, int weeksScored, List<TeamRow> teams) {

        static Result unavailable(String reason, int season, Sport sport) {
            return new Result(false, reason, season, null, sport, 0, List.of());
        }
    }

    public Optional<Result> forLeague(String sleeperLeagueId) {
        Optional<LeagueSeasonResolver.Resolved> found = seasons.resolve(sleeperLeagueId);
        if (found.isEmpty()) return Optional.empty();
        LeagueRepository.LeagueRow league = found.get().league();

        LeagueSettings settings = LeagueRepository.toSettings(league, league.rosterPositions().size());
        SportRules rules = rulesRegistry.get(settings.sport());

        List<RosterWeekPointsRepository.WeekBreakdown> weeks =
                weekPoints.breakdownsFor(league.id(), league.season());
        if (weeks.isEmpty()) {
            return Optional.of(Result.unavailable(
                    "no scored weeks yet for this league", league.season(), settings.sport()));
        }

        // One player lookup for the whole request, not one per roster-week.
        Map<String, Player> playersBySleeperId = new HashMap<>();
        for (Player p : players.findAll(settings.sport())) {
            if (p.sleeperId() != null) playersBySleeperId.put(p.sleeperId(), p);
        }

        // Team name lives on league_member (Sleeper's per-league team name),
        // the manager's display name is the fallback, and "Roster N" is the
        // last resort for a roster nobody owns -- which Sleeper produces when
        // someone leaves mid-season. Never drop such a roster: it still scored.
        Map<Long, String> teamNameByManager = new HashMap<>();
        for (LeagueMemberRepository.MemberRow m : members.forLeague(league.id())) {
            if (m.teamName() != null && !m.teamName().isBlank()) {
                teamNameByManager.put(m.managerId(), m.teamName());
            }
        }

        Map<Integer, Long> managerByRoster = new HashMap<>();
        Map<Integer, String> avatarByRoster = new HashMap<>();
        Map<Integer, String> teamNameByRoster = new HashMap<>();
        for (RosterSeasonRepository.StandingRow s : rosterSeasons.forLeague(league.id())) {
            managerByRoster.put(s.rosterId(), s.managerId());
            avatarByRoster.put(s.rosterId(), s.avatarId());
            String name = s.managerId() == null ? null : teamNameByManager.get(s.managerId());
            if (name == null) name = s.managerName();
            if (name != null && !name.isBlank()) teamNameByRoster.put(s.rosterId(), name);
        }

        Map<Integer, Double> totalByRoster = new LinkedHashMap<>();
        Map<Integer, Double> potentialByRoster = new HashMap<>();
        Map<Integer, Integer> countedByRoster = new HashMap<>();
        Map<Integer, List<Integer>> excludedByRoster = new HashMap<>();
        Set<Integer> scoredWeeks = new TreeSet<>();

        for (RosterWeekPointsRepository.WeekBreakdown w : weeks) {
            scoredWeeks.add(w.week());
            totalByRoster.merge(w.rosterId(), w.startersPoints(), Double::sum);

            RealizedLineupService.WeekLineup best = realized.bestLineup(
                    w.playersPointsJson(), playersBySleeperId, settings, rules);

            if (best.valid()) {
                potentialByRoster.merge(w.rosterId(), best.points(), Double::sum);
                countedByRoster.merge(w.rosterId(), 1, Integer::sum);
            } else {
                // Excluded, NOT summed as zero. See FR-007.
                excludedByRoster.computeIfAbsent(w.rosterId(), k -> new ArrayList<>()).add(w.week());
            }
        }

        List<TeamRow> teams = new ArrayList<>();
        for (Map.Entry<Integer, Double> e : totalByRoster.entrySet()) {
            int rosterId = e.getKey();
            double total = e.getValue();
            double potential = potentialByRoster.getOrDefault(rosterId, 0.0);
            Double efficiency = potential > 0 ? total / potential : null;
            teams.add(new TeamRow(
                    rosterId,
                    managerByRoster.get(rosterId),
                    teamNameByRoster.getOrDefault(rosterId, "Roster " + rosterId),
                    avatarByRoster.get(rosterId),
                    round2(total), round2(potential), efficiency,
                    countedByRoster.getOrDefault(rosterId, 0),
                    excludedByRoster.getOrDefault(rosterId, List.of())));
        }
        teams.sort(Comparator.comparingDouble(TeamRow::totalPoints).reversed()
                .thenComparingInt(TeamRow::rosterId));

        int excludedTotal = excludedByRoster.values().stream().mapToInt(List::size).sum();
        if (excludedTotal > 0) {
            log.info("roster management: league {} excluded {} roster-weeks with no per-player breakdown",
                    league.id(), excludedTotal);
        }

        return Optional.of(new Result(true, null, league.season(), found.get().requestedSeason(),
                settings.sport(), scoredWeeks.size(), teams));
    }

    /** Points are a two-decimal quantity everywhere Sleeper reports them. */
    private static double round2(double d) {
        return Math.round(d * 100.0) / 100.0;
    }
}
