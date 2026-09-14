package com.ballknowers.draftsim.engine;

import com.ballknowers.draftsim.store.LeagueMatchupRepository;
import com.ballknowers.draftsim.store.LeagueRepository;
import com.ballknowers.draftsim.store.PlayoffOddsRepository;
import com.ballknowers.draftsim.store.RosterSeasonRepository;
import com.ballknowers.draftsim.store.RosterWeekPointsRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.*;

/**
 * Assembles the playoff-odds simulation's inputs out of the database and
 * stores what comes back (claude/playoff-odds.md). The model itself lives in
 * {@link PlayoffOddsSimulator}, which knows nothing about storage.
 *
 * Two rules from the brief are enforced here rather than in the simulator,
 * because both are about whether to answer at all:
 * <ul>
 *   <li>A league whose seeding this app does not model -- divisions, or a
 *       non-default {@code playoff_seed_type} -- gets no snapshot. The page
 *       keeps rendering "--", which is the honest answer; a wrong 78% is not.</li>
 *   <li>Nothing is computed on a page load. Snapshots are written on the
 *       commissioner's recompute, the same trigger the box-score rankings
 *       already use.</li>
 * </ul>
 */
@Service
public class PlayoffOddsService {

    private static final Logger log = LoggerFactory.getLogger(PlayoffOddsService.class);

    /** 10k seasons: the sampling error on a percentage is well under half a point, and it runs in milliseconds. */
    public static final int ITERATIONS = 10_000;

    private final LeagueRepository leagues;
    private final RosterSeasonRepository rosterSeasons;
    private final RosterWeekPointsRepository weekPoints;
    private final LeagueMatchupRepository fixtures;
    private final PlayoffOddsRepository odds;

    public PlayoffOddsService(LeagueRepository leagues, RosterSeasonRepository rosterSeasons,
                              RosterWeekPointsRepository weekPoints, LeagueMatchupRepository fixtures,
                              PlayoffOddsRepository odds) {
        this.leagues = leagues;
        this.rosterSeasons = rosterSeasons;
        this.weekPoints = weekPoints;
        this.fixtures = fixtures;
        this.odds = odds;
    }

    /**
     * Computes and stores one week's odds.
     *
     * @param throughWeek the week the standings already reflect; weeks
     *                    {@code throughWeek + 1 .. playoff_week_start - 1} are
     *                    the ones simulated. Passing the same week the
     *                    power-ranking snapshot uses keeps the two aligned.
     * @return the stored entries, or empty when this league's format is one the
     *         app refuses to model or there is nothing to stand on yet
     */
    public List<PlayoffOddsRepository.Entry> compute(long leagueId, int season, int throughWeek) {
        Optional<LeagueRepository.PlayoffFormat> maybeFormat = leagues.playoffFormat(leagueId);
        if (maybeFormat.isEmpty() || !maybeFormat.get().modelable()) {
            log.info("playoff odds: league {} has a format this app does not model ({}), no snapshot written",
                    leagueId, maybeFormat.orElse(null));
            return refuse(leagueId, season, throughWeek);
        }
        LeagueRepository.PlayoffFormat format = maybeFormat.get();

        List<RosterSeasonRepository.StandingRow> standings = rosterSeasons.forLeague(leagueId);
        if (standings.isEmpty()) return refuse(leagueId, season, throughWeek);

        Map<Integer, List<Double>> weekly = weeklyPointsByRoster(leagueId, throughWeek);
        // No scored games anywhere in this league yet. Measured on a live
        // preseason league: with no scoring there is no mean and no variance,
        // every simulated game ends 0-0, every team finishes identical, and the
        // sort hands the playoff spots to whoever happened to be first in the
        // list -- 100% and 0%, stated with total confidence, from nothing. The
        // honest answer before week 1 is scored is no answer.
        int gamesOfScoring = weekly.values().stream().mapToInt(List::size).sum();
        if (gamesOfScoring == 0) {
            log.info("playoff odds: league {} has no scored weeks through week {}, no snapshot written",
                    leagueId, throughWeek);
            return refuse(leagueId, season, throughWeek);
        }
        Map<Integer, double[]> strengths = PlayoffOddsSimulator.strengths(padded(weekly, standings));

        List<PlayoffOddsSimulator.TeamState> teams = new ArrayList<>();
        for (RosterSeasonRepository.StandingRow s : standings) {
            double[] strength = strengths.getOrDefault(s.rosterId(), new double[]{0, 0});
            teams.add(new PlayoffOddsSimulator.TeamState(
                    s.rosterId(),
                    s.wins() == null ? 0 : s.wins(),
                    s.ties() == null ? 0 : s.ties(),
                    s.pointsFor() == null ? 0 : s.pointsFor(),
                    strength[0], strength[1]));
        }

        List<PlayoffOddsSimulator.Fixture> remaining = fixtures
                .between(leagueId, season, throughWeek + 1, format.playoffWeekStart() - 1)
                .stream()
                .filter(f -> f.matchupId() != null)
                .map(f -> new PlayoffOddsSimulator.Fixture(f.week(), f.rosterId(), f.matchupId()))
                .toList();

        // Deterministic per (league, season, week): refreshing the page must not
        // wiggle a team's odds by a third of a point.
        long seed = Objects.hash(leagueId, season, throughWeek);
        List<PlayoffOddsSimulator.Odds> result = PlayoffOddsSimulator.run(
                teams, remaining, format.playoffTeams(), format.medianMatch(), ITERATIONS, seed);
        if (result.isEmpty()) return List.of();

        List<PlayoffOddsRepository.Entry> entries = result.stream()
                .map(o -> new PlayoffOddsRepository.Entry(o.rosterId(), o.madePct(), null, o.seedOnePct(),
                        o.projWins(), o.projPoints()))
                .toList();
        odds.save(leagueId, season, throughWeek, ITERATIONS, PlayoffOddsSimulator.MODEL, entries);
        log.info("playoff odds: league {} week {} -- {} teams, {} remaining fixtures, {} iterations",
                leagueId, throughWeek, teams.size(), remaining.size(), ITERATIONS);
        return entries;
    }

    /**
     * Refusing to answer also clears any answer already stored for that week:
     * whatever put a number there, this run no longer stands behind it, and a
     * stale snapshot outlives every reason it was written.
     */
    private List<PlayoffOddsRepository.Entry> refuse(long leagueId, int season, int week) {
        odds.deleteWeek(leagueId, season, week);
        return List.of();
    }

    /**
     * {@code week -> rosterId -> made-playoffs percentage} for a whole season,
     * the shape the power-rankings payload needs to hang odds off entries it is
     * already building.
     */
    public Map<Integer, Map<Integer, Double>> madePctByWeek(long leagueId, int season) {
        return odds.madePctByWeek(leagueId, season);
    }

    /**
     * What the page needs to describe the number honestly: which week's odds
     * these are, how many seasons were simulated, and how many weeks of real
     * scoring the strength estimate stands on. Empty when no snapshot exists --
     * and then the page says nothing about odds at all rather than repeating a
     * sentence about a simulation that never ran.
     */
    public record Summary(int week, int iterations, String model, int weeksOfScoring) {}

    /**
     * Describes the newest snapshot this league has, with no week bound: the
     * page renders every week at once, and a past season's page would otherwise
     * be asked about the CURRENT week and answer "no odds" about a season whose
     * odds it is displaying.
     */
    public Optional<Summary> summary(long leagueId, int season) {
        return odds.latest(leagueId, season)
                .map(s -> new Summary(s.week(), s.iterations(), s.model(), weeksOfScoring(leagueId, s.week())));
    }

    /** rosterId -> made-playoffs percentage for one week, empty when that week has no snapshot. */
    public Map<Integer, Double> madePctByRoster(long leagueId, int season, int week) {
        return odds.forWeek(leagueId, season, week)
                .map(s -> {
                    Map<Integer, Double> out = new HashMap<>();
                    for (PlayoffOddsRepository.Entry e : s.entries()) out.put(e.rosterId(), e.madePct());
                    return out;
                })
                .orElseGet(Map::of);
    }

    /**
     * How many weeks of real scoring the estimate stands on -- the page says so
     * out loud ("10,000 seasons, from 3 weeks of scoring"), because 3 weeks and
     * 11 weeks deserve very different amounts of trust.
     */
    public int weeksOfScoring(long leagueId, int throughWeek) {
        return weeklyPointsByRoster(leagueId, throughWeek).values().stream()
                .mapToInt(List::size).max().orElse(0);
    }

    /**
     * A roster's weekly scores, dropping exact zeroes. A stored 0.0 is a week
     * that roster did not actually play -- an unplayed week Sleeper still
     * answered for, or a roster added mid-season -- and feeding those into the
     * mean would quietly halve a team's projected scoring.
     */
    private Map<Integer, List<Double>> weeklyPointsByRoster(long leagueId, int throughWeek) {
        Map<Integer, List<Double>> out = new LinkedHashMap<>();
        for (RosterWeekPointsRepository.WeekPoint p : weekPoints.through(leagueId, throughWeek)) {
            if (p.startersPoints() <= 0.0) continue;
            out.computeIfAbsent(p.rosterId(), r -> new ArrayList<>()).add(p.startersPoints());
        }
        return out;
    }

    /** Every roster in the league appears, even one with no scores yet -- it is league-average, not absent. */
    private static Map<Integer, List<Double>> padded(Map<Integer, List<Double>> weekly,
                                                     List<RosterSeasonRepository.StandingRow> standings) {
        Map<Integer, List<Double>> out = new LinkedHashMap<>(weekly);
        for (RosterSeasonRepository.StandingRow s : standings) {
            out.computeIfAbsent(s.rosterId(), r -> new ArrayList<>());
        }
        return out;
    }
}
