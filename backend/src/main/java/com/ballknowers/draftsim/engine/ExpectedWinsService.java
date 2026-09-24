package com.ballknowers.draftsim.engine;

import com.ballknowers.draftsim.domain.Sport;
import com.ballknowers.draftsim.store.*;
import org.springframework.stereotype.Service;

import java.util.*;

/**
 * Expected wins, schedule luck and strength of schedule
 * (specs/004-ffwrapped-feature-parity, US3).
 *
 * <p>Expected wins is the all-play record: in a given week a team is credited
 * with the fraction of the OTHER teams it outscored. Over a season that is what
 * the team would have won against a uniformly random opponent each week, so the
 * gap between it and the real record is schedule luck rather than skill.
 *
 * <p><b>No new ingest and no sport branch.</b> Every input is a weekly score or
 * a pairing, both already stored, and neither means anything different in
 * basketball. Nothing in this file touches a position or a lineup.
 *
 * <p><b>The conservation invariant is the proof.</b> In any week where every
 * scored roster is paired, the expected wins handed out sum to exactly the
 * number of real wins: each of the N/2 games produces one win, and
 * {@code sum over rosters of outscored/(N-1)} is {@code C(N,2)/(N-1) = N/2}.
 * So {@code sum(expectedWins) == sum(actualWins)} across the league, and a
 * violation is a bug in the model rather than rounding (SC-004). For that to
 * hold, both sides must cover the SAME games -- which is why the scores below
 * are filtered down to the rosters that actually played in each week, instead
 * of every roster the scores table happens to hold.
 */
@Service
public class ExpectedWinsService {

    private final LeagueRepository leagues;
    private final LeagueMatchupRepository matchups;
    private final RosterSeasonRepository rosterSeasons;
    private final LeagueMemberRepository members;
    private final LeagueSeasonResolver seasons;

    public ExpectedWinsService(LeagueRepository leagues,
                               LeagueMatchupRepository matchups,
                               RosterSeasonRepository rosterSeasons,
                               LeagueMemberRepository members,
                               LeagueSeasonResolver seasons) {
        this.leagues = leagues;
        this.matchups = matchups;
        this.rosterSeasons = rosterSeasons;
        this.members = members;
        this.seasons = seasons;
    }

    /** One played, scored game. The pure core's only input. */
    public record Game(int week, int aRosterId, double aPoints, int bRosterId, double bPoints) {}

    /** Why a team's record diverged from its expectation. Exactly one applies. */
    public enum LuckSource { SWING_WEEKS, CONSISTENT_OPPONENT_SCORING }

    public record SwingWeek(int week, boolean won, double points, int weeklyRank, String opponent) {}

    public record TeamRow(int rosterId, Long managerId, String teamName, String avatarId,
                          double expectedWins, double actualWins, double winsAboveExpected,
                          double strengthOfSchedule, LuckSource luckSource, List<SwingWeek> swingWeeks) {}

    public record Result(boolean available, String reason, int season, Integer requestedSeason,
                         Sport sport, int weeksScored, double leagueAveragePpg, List<TeamRow> teams) {

        static Result unavailable(String reason, int season, Sport sport) {
            return new Result(false, reason, season, null, sport, 0, 0, List.of());
        }
    }

    // ---------------------------------------------------------------- pure core

    /**
     * Expected wins per roster: per week, the fraction of the other rosters
     * this one outscored, with a tie counting half.
     *
     * <p>Only rosters that appear in {@code games} for a week take part in that
     * week, so the invariant above holds exactly.
     */
    static Map<Integer, Double> expectedWins(List<Game> games) {
        Map<Integer, Map<Integer, Double>> byWeek = scoresByWeek(games);
        Map<Integer, Double> out = new HashMap<>();
        for (Map<Integer, Double> week : byWeek.values()) {
            int n = week.size();
            if (n < 2) continue;
            for (Map.Entry<Integer, Double> me : week.entrySet()) {
                double beat = 0;
                for (Map.Entry<Integer, Double> other : week.entrySet()) {
                    if (other.getKey().equals(me.getKey())) continue;
                    int cmp = Double.compare(me.getValue(), other.getValue());
                    if (cmp > 0) beat += 1;
                    else if (cmp == 0) beat += 0.5;
                }
                out.merge(me.getKey(), beat / (n - 1), Double::sum);
            }
        }
        return out;
    }

    /** Real wins over the same games; a tie is half a win on each side. */
    static Map<Integer, Double> actualWins(List<Game> games) {
        Map<Integer, Double> out = new HashMap<>();
        for (Game g : games) {
            int cmp = Double.compare(g.aPoints(), g.bPoints());
            double a = cmp > 0 ? 1 : cmp == 0 ? 0.5 : 0;
            out.merge(g.aRosterId(), a, Double::sum);
            out.merge(g.bRosterId(), 1 - a, Double::sum);
        }
        return out;
    }

    /**
     * Mean of the opponents' points per game, minus the league-wide points per
     * game. Positive means a harder schedule -- the sign is stated on the page
     * rather than left to the reader (US3.3).
     */
    static Map<Integer, Double> strengthOfSchedule(List<Game> games) {
        Map<Integer, List<Double>> pointsByRoster = new HashMap<>();
        Map<Integer, List<Integer>> opponentsByRoster = new HashMap<>();
        for (Game g : games) {
            pointsByRoster.computeIfAbsent(g.aRosterId(), k -> new ArrayList<>()).add(g.aPoints());
            pointsByRoster.computeIfAbsent(g.bRosterId(), k -> new ArrayList<>()).add(g.bPoints());
            opponentsByRoster.computeIfAbsent(g.aRosterId(), k -> new ArrayList<>()).add(g.bRosterId());
            opponentsByRoster.computeIfAbsent(g.bRosterId(), k -> new ArrayList<>()).add(g.aRosterId());
        }
        Map<Integer, Double> ppg = new HashMap<>();
        pointsByRoster.forEach((r, pts) -> ppg.put(r, pts.stream().mapToDouble(Double::doubleValue).average().orElse(0)));
        double leaguePpg = ppg.values().stream().mapToDouble(Double::doubleValue).average().orElse(0);

        Map<Integer, Double> out = new HashMap<>();
        opponentsByRoster.forEach((r, opps) -> {
            double mean = opps.stream().mapToDouble(o -> ppg.getOrDefault(o, 0.0)).average().orElse(0);
            out.put(r, mean - leaguePpg);
        });
        return out;
    }

    static double leaguePpg(List<Game> games) {
        if (games.isEmpty()) return 0;
        double total = 0;
        for (Game g : games) total += g.aPoints() + g.bPoints();
        return total / (games.size() * 2.0);
    }

    /**
     * Weeks where the result ran against the scoring: a win from the bottom
     * half of the week's scores, or a loss from the top half. These are the
     * weeks that actually moved the record away from the expectation, which is
     * why naming them is more use than repeating the season total.
     */
    static List<SwingWeek> swingWeeks(int rosterId, List<Game> games, Map<Integer, String> nameByRoster) {
        Map<Integer, Map<Integer, Double>> byWeek = scoresByWeek(games);
        List<SwingWeek> out = new ArrayList<>();
        for (Game g : games) {
            // An explicit branch, not a ternary chain: mixing an `int` arm with
            // a `null` arm makes the conditional operator unbox the boxed side,
            // so the null it is testing for throws a NullPointerException
            // before the test happens. Caught by the tests below.
            final int opponent;
            if (g.aRosterId() == rosterId) opponent = g.bRosterId();
            else if (g.bRosterId() == rosterId) opponent = g.aRosterId();
            else continue;
            double mine = g.aRosterId() == rosterId ? g.aPoints() : g.bPoints();
            double theirs = g.aRosterId() == rosterId ? g.bPoints() : g.aPoints();
            if (mine == theirs) continue;
            boolean won = mine > theirs;

            Map<Integer, Double> week = byWeek.getOrDefault(g.week(), Map.of());
            int n = week.size();
            if (n < 2) continue;
            int rank = 1;
            for (double other : week.values()) if (other > mine) rank++;

            boolean bottomHalf = rank > n / 2.0;
            if ((won && bottomHalf) || (!won && !bottomHalf)) {
                out.add(new SwingWeek(g.week(), won, round2(mine), rank,
                        nameByRoster.getOrDefault(opponent, "Roster " + opponent)));
            }
        }
        out.sort(Comparator.comparingInt(SwingWeek::week));
        return out;
    }

    private static Map<Integer, Map<Integer, Double>> scoresByWeek(List<Game> games) {
        Map<Integer, Map<Integer, Double>> byWeek = new TreeMap<>();
        for (Game g : games) {
            Map<Integer, Double> w = byWeek.computeIfAbsent(g.week(), k -> new HashMap<>());
            w.put(g.aRosterId(), g.aPoints());
            w.put(g.bRosterId(), g.bPoints());
        }
        return byWeek;
    }

    // ------------------------------------------------------------- repo walk

    /**
     * The only entry point (specs/008-season-superlatives T027/T028): a caller
     * always states which weeks it wants, explicitly, via {@link WeekBound} --
     * there is deliberately no unbounded overload (memory "optional params
     * that encode rules").
     */
    public Optional<Result> forLeague(String sleeperLeagueId, WeekBound bound) {
        Optional<LeagueSeasonResolver.Resolved> found = seasons.resolve(sleeperLeagueId);
        if (found.isEmpty()) return Optional.empty();
        return Optional.of(compute(found.get(), bound));
    }

    /**
     * Convenience for the common case of "this season's own regular season"
     * (T029's Expected wins page, T030's career sum), so neither caller
     * duplicates {@link LeagueSeasonResolver} resolution just to compute the
     * bound itself (contracts/superlatives-api.md T028/T029 discussion).
     */
    public Optional<Result> forLeagueRegularSeason(String sleeperLeagueId) {
        Optional<LeagueSeasonResolver.Resolved> found = seasons.resolve(sleeperLeagueId);
        if (found.isEmpty()) return Optional.empty();
        WeekBound bound = regularSeasonBound(found.get().league());
        return Optional.of(compute(found.get(), bound));
    }

    /**
     * The regular-season ceiling for one league row: the week before
     * {@code playoff_week_start} when the league has one configured (>= 2),
     * else no ceiling at all. Public so every caller of a season window
     * (this class, {@code SeasonSuperlativesService}, {@code ManagerCareerService})
     * applies the identical rule rather than each re-deriving it.
     */
    public WeekBound regularSeasonBound(LeagueRepository.LeagueRow league) {
        Optional<LeagueRepository.PlayoffFormat> format = leagues.playoffFormat(league.id());
        if (format.isPresent() && format.get().playoffWeekStart() >= 2) {
            return WeekBound.through(format.get().playoffWeekStart() - 1);
        }
        return WeekBound.ALL_WEEKS;
    }

    private Result compute(LeagueSeasonResolver.Resolved found, WeekBound bound) {
        LeagueRepository.LeagueRow league = found.league();

        List<Game> games = new ArrayList<>();
        for (LeagueMatchupRepository.PairedGame p : matchups.pairedWithScores(List.of(league.id()), bound)) {
            if (p.season() != league.season()) continue;
            if (p.aPoints() == null || p.bPoints() == null) continue;
            games.add(new Game(p.week(), p.aRosterId(), p.aPoints().doubleValue(),
                    p.bRosterId(), p.bPoints().doubleValue()));
        }
        if (games.isEmpty()) {
            return Result.unavailable("no completed games for this league yet", league.season(), league.sport());
        }

        Map<Long, String> teamNameByManager = new HashMap<>();
        for (LeagueMemberRepository.MemberRow m : members.forLeague(league.id())) {
            if (m.teamName() != null && !m.teamName().isBlank()) {
                teamNameByManager.put(m.managerId(), m.teamName());
            }
        }
        Map<Integer, Long> managerByRoster = new HashMap<>();
        Map<Integer, String> avatarByRoster = new HashMap<>();
        Map<Integer, String> nameByRoster = new HashMap<>();
        for (RosterSeasonRepository.StandingRow s : rosterSeasons.forLeague(league.id())) {
            managerByRoster.put(s.rosterId(), s.managerId());
            avatarByRoster.put(s.rosterId(), s.avatarId());
            String name = s.managerId() == null ? null : teamNameByManager.get(s.managerId());
            if (name == null) name = s.managerName();
            if (name != null && !name.isBlank()) nameByRoster.put(s.rosterId(), name);
        }

        Map<Integer, Double> expected = expectedWins(games);
        Map<Integer, Double> actual = actualWins(games);
        Map<Integer, Double> sos = strengthOfSchedule(games);

        List<TeamRow> teams = new ArrayList<>();
        for (Integer rosterId : expected.keySet()) {
            double exp = expected.getOrDefault(rosterId, 0.0);
            double act = actual.getOrDefault(rosterId, 0.0);
            List<SwingWeek> swings = swingWeeks(rosterId, games, nameByRoster);
            teams.add(new TeamRow(
                    rosterId,
                    managerByRoster.get(rosterId),
                    nameByRoster.getOrDefault(rosterId, "Roster " + rosterId),
                    avatarByRoster.get(rosterId),
                    round2(exp), act, round2(act - exp),
                    round2(sos.getOrDefault(rosterId, 0.0)),
                    swings.isEmpty() ? LuckSource.CONSISTENT_OPPONENT_SCORING : LuckSource.SWING_WEEKS,
                    swings));
        }
        teams.sort(Comparator.comparingDouble(TeamRow::winsAboveExpected).reversed()
                .thenComparingInt(TeamRow::rosterId));

        int weeks = (int) games.stream().mapToInt(Game::week).distinct().count();
        return new Result(true, null, league.season(), found.requestedSeason(),
                league.sport(), weeks, round2(leaguePpg(games)), teams);
    }

    private static double round2(double d) {
        return Math.round(d * 100.0) / 100.0;
    }
}
