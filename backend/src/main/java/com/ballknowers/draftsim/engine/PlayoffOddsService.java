package com.ballknowers.draftsim.engine;

import com.ballknowers.draftsim.store.JsonUtil;
import com.ballknowers.draftsim.store.LeagueMatchupRepository;
import com.ballknowers.draftsim.store.LeagueMemberRepository;
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
    private final LeagueMemberRepository members;
    private final LeagueSeasonResolver seasons;

    public PlayoffOddsService(LeagueRepository leagues, RosterSeasonRepository rosterSeasons,
                              RosterWeekPointsRepository weekPoints, LeagueMatchupRepository fixtures,
                              PlayoffOddsRepository odds, LeagueMemberRepository members,
                              LeagueSeasonResolver seasons) {
        this.leagues = leagues;
        this.rosterSeasons = rosterSeasons;
        this.weekPoints = weekPoints;
        this.fixtures = fixtures;
        this.odds = odds;
        this.members = members;
        this.seasons = seasons;
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
                        o.projWins(), o.projPoints(),
                        countsJson(o.seedCounts(), 1), countsJson(o.winCounts(), 0)))
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

    /**
     * A count array to a {@code key -> count} object, dropping zero buckets.
     *
     * <p>Zeroes are dropped rather than written because a 12-team league's seed
     * array is mostly zeroes and the object is stored per roster per week; a
     * reader treats a missing key as zero, which it is. This is the one place
     * where absent and zero genuinely do mean the same thing -- unlike the
     * column itself being null, which means the snapshot predates V17.
     *
     * @param firstKey 1 for seeds (there is no seed 0), 0 for win totals
     */
    static String countsJson(List<Integer> counts, int firstKey) {
        if (counts == null) return null;
        Map<String, Integer> out = new LinkedHashMap<>();
        for (int i = 0; i < counts.size(); i++) {
            if (counts.get(i) > 0) out.put(String.valueOf(i + firstKey), counts.get(i));
        }
        return JsonUtil.write(out);
    }

    // ------------------------------------------------ Season Forecast (US4)

    /**
     * Why a forecast is not being shown. Distinct values, not one empty list.
     *
     * <p>{@code NOT_COMPUTED} is distinct from {@code NO_SCORED_WEEKS} on purpose:
     * a finished season with 21 played weeks and no odds snapshot is not a
     * season with nothing to project from, and telling the reader it is would
     * send them looking for the wrong thing.
     *
     * <p>There is deliberately no value here for a snapshot that predates V17's
     * distributions. That case is not a refusal: such a snapshot still carries
     * real playoff odds and average wins, and only {@code winRange},
     * {@code averageSeed} and {@code seedOdds} are absent. It is served with
     * {@code available: true} and degrades field by field, so the reader keeps
     * the numbers that do exist. A whole-page refusal would hide them.
     */
    public enum Unavailable { UNMODELLED_SEEDING, NO_SCORED_WEEKS, NOT_COMPUTED }

    public record ForecastTeam(int rosterId, Long managerId, String teamName, String avatarId,
                               double playoffOdds, double averageWins, double projectedPoints,
                               Integer winP10, Integer winP90, Double averageSeed,
                               double seedOnePct, Map<Integer, Double> seedOdds) {}

    public record Forecast(boolean available, Unavailable reason, int season, Integer requestedSeason,
                           int week, int iterations, String model, String snapshotAt,
                           List<ForecastTeam> teams) {

        static Forecast no(Unavailable reason, int season) {
            return no(reason, season, null);
        }

        /** A refusal announces the season fallback too: the reader still needs
         *  to know the answer is about a different year than the one clicked. */
        static Forecast no(Unavailable reason, int season, Integer requestedSeason) {
            return new Forecast(false, reason, season, requestedSeason, 0, 0, null, null, List.of());
        }
    }

    /**
     * Served ENTIRELY from the stored snapshot (FR-009). Nothing here simulates,
     * so two reads return the same numbers and this view cannot disagree with
     * the playoff-odds figure the Record cell already shows (FR-008, SC-005).
     *
     * <p>The refusals are the ones {@code compute} already enforces, surfaced
     * rather than bypassed: a league whose seeding this app does not model has
     * no snapshot to read, which is why the honest answer stays "no answer"
     * instead of becoming a new endpoint's zero.
     */
    public Optional<Forecast> forecast(String sleeperLeagueId) {
        Optional<LeagueSeasonResolver.Resolved> found = seasons.resolve(sleeperLeagueId);
        if (found.isEmpty()) return Optional.empty();
        LeagueRepository.LeagueRow league = found.get().league();
        int season = league.season();

        Optional<LeagueRepository.PlayoffFormat> format = leagues.playoffFormat(league.id());
        if (format.isEmpty() || !format.get().modelable()) {
            return Optional.of(Forecast.no(Unavailable.UNMODELLED_SEEDING, season,
                    found.get().requestedSeason()));
        }

        Optional<PlayoffOddsRepository.Snapshot> snap = odds.latest(league.id(), season);
        if (snap.isEmpty() || snap.get().entries().isEmpty()) {
            // Which refusal depends on WHY there is no snapshot.
            boolean played = !weekPoints.storedWeeks(league.id()).isEmpty();
            return Optional.of(Forecast.no(
                    played ? Unavailable.NOT_COMPUTED : Unavailable.NO_SCORED_WEEKS, season,
                    found.get().requestedSeason()));
        }
        PlayoffOddsRepository.Snapshot s = snap.get();

        Map<Long, String> teamNameByManager = new HashMap<>();
        for (LeagueMemberRepository.MemberRow m : members.forLeague(league.id())) {
            if (m.teamName() != null && !m.teamName().isBlank()) {
                teamNameByManager.put(m.managerId(), m.teamName());
            }
        }
        Map<Integer, Long> managerByRoster = new HashMap<>();
        Map<Integer, String> avatarByRoster = new HashMap<>();
        Map<Integer, String> nameByRoster = new HashMap<>();
        for (RosterSeasonRepository.StandingRow r : rosterSeasons.forLeague(league.id())) {
            managerByRoster.put(r.rosterId(), r.managerId());
            avatarByRoster.put(r.rosterId(), r.avatarId());
            String name = r.managerId() == null ? null : teamNameByManager.get(r.managerId());
            if (name == null) name = r.managerName();
            if (name != null && !name.isBlank()) nameByRoster.put(r.rosterId(), name);
        }

        List<ForecastTeam> teams = new ArrayList<>();
        for (PlayoffOddsRepository.Entry e : s.entries()) {
            Map<Integer, Integer> seeds = parseCounts(e.seedCountsJson());
            Map<Integer, Integer> wins = parseCounts(e.winCountsJson());
            teams.add(new ForecastTeam(
                    e.rosterId(),
                    managerByRoster.get(e.rosterId()),
                    nameByRoster.getOrDefault(e.rosterId(), "Roster " + e.rosterId()),
                    avatarByRoster.get(e.rosterId()),
                    e.madePct(), e.projWins(), e.projPoints(),
                    percentile(wins, 0.10), percentile(wins, 0.90),
                    averageKey(seeds),
                    e.seedOnePct() == null ? 0.0 : e.seedOnePct(),
                    share(seeds)));
        }

        return Optional.of(new Forecast(true, null, s.season(), found.get().requestedSeason(),
                s.week(), s.iterations(), s.model(), null, teams));
    }

    /** {@code "3": 412} -> {3: 412}. Null (a pre-V17 snapshot) is an empty map. */
    static Map<Integer, Integer> parseCounts(String json) {
        if (json == null || json.isBlank()) return Map.of();
        Map<String, Object> raw = JsonUtil.readMap(json);
        Map<Integer, Integer> out = new TreeMap<>();
        raw.forEach((k, v) -> {
            if (v instanceof Number n) out.put(Integer.parseInt(k), n.intValue());
        });
        return out;
    }

    /**
     * The {@code p}-th percentile key of a count distribution.
     *
     * <p>Walks the cumulative count rather than expanding the samples: the
     * distribution is already a histogram, and 10,000 iterations would be
     * 10,000 boxed integers per roster to sort for an answer the counts give
     * directly. Null for an empty distribution -- a pre-V17 snapshot has no
     * range, which is not the same as a range of zero.
     */
    static Integer percentile(Map<Integer, Integer> counts, double p) {
        if (counts.isEmpty()) return null;
        int total = counts.values().stream().mapToInt(Integer::intValue).sum();
        if (total == 0) return null;
        double target = p * total;
        int seen = 0;
        for (Map.Entry<Integer, Integer> e : new TreeMap<>(counts).entrySet()) {
            seen += e.getValue();
            if (seen >= target) return e.getKey();
        }
        return new TreeMap<>(counts).lastKey();
    }

    /** Mean of the keys weighted by their counts; null for an empty distribution. */
    static Double averageKey(Map<Integer, Integer> counts) {
        int total = counts.values().stream().mapToInt(Integer::intValue).sum();
        if (total == 0) return null;
        double sum = 0;
        for (Map.Entry<Integer, Integer> e : counts.entrySet()) sum += (double) e.getKey() * e.getValue();
        return Math.round((sum / total) * 100.0) / 100.0;
    }

    /** Counts to fractions of the whole, so a caller renders odds not tallies. */
    static Map<Integer, Double> share(Map<Integer, Integer> counts) {
        int total = counts.values().stream().mapToInt(Integer::intValue).sum();
        if (total == 0) return Map.of();
        Map<Integer, Double> out = new TreeMap<>();
        counts.forEach((k, v) -> out.put(k, Math.round((v * 1000.0 / total)) / 1000.0));
        return out;
    }
}
