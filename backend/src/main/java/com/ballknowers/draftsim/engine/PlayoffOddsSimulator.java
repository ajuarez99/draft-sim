package com.ballknowers.draftsim.engine;

import java.util.*;
import java.util.random.RandomGenerator;

/**
 * The playoff-odds Monte Carlo (claude/playoff-odds.md). Pure: every input is
 * a value, the only randomness comes from a seed the caller supplies, and it
 * touches neither the database nor Sleeper -- so the model is testable without
 * standing a league up.
 *
 * The model is deliberately team-level: each roster is a weekly scoring
 * distribution, the remaining fixtures are real, and nothing here knows about
 * players, injuries, byes or trades. The page's footnote says as much, which is
 * the only honest way to ship a number like this.
 */
public final class PlayoffOddsSimulator {

    /** Bumped whenever the model changes, and stored on every snapshot. */
    public static final String MODEL = "shrunk-normal-v1";

    /**
     * Shrinkage constant: a roster is weighted {@code n/(n+K)} toward its own
     * scoring and the rest toward the league's. At n=4 a team is half itself,
     * at n=12 three-quarters. At n=0 it is entirely the league average -- so a
     * league that has not played yet gets odds that differ only by schedule and
     * standings, which is the truth rather than a placeholder.
     */
    public static final double K = 4.0;

    private PlayoffOddsSimulator() {}

    /** One roster's season so far, plus the scoring distribution it carries forward. */
    public record TeamState(int rosterId, double wins, double ties, double pointsFor, double mu, double sigma) {
        /** Ties are half a win everywhere in this file, so the comparator never has to know about them. */
        double winCredit() {
            return wins + ties / 2.0;
        }
    }

    /** One roster's game in one week. {@code matchupId} groups the two sides. */
    public record Fixture(int week, int rosterId, int matchupId) {}

    /**
     * @param seedCounts how many simulated seasons ended with this team at each
     *                   seed, index 0 = the 1 seed. Retained rather than
     *                   collapsed: the loop already sorts a full standings order
     *                   every iteration, and everything a forecast view wants --
     *                   average seed, per-seed odds -- was being thrown away one
     *                   line after it was computed
     *                   (specs/004-ffwrapped-feature-parity, research R8).
     * @param winCounts  how many simulated seasons ended on each whole-number
     *                   win total, index = wins. Gives the win percentile range
     *                   without a second simulation.
     */
    public record Odds(int rosterId, double madePct, double seedOnePct, double projWins, double projPoints,
                       List<Integer> seedCounts, List<Integer> winCounts) {}

    /**
     * @param medianMatch Sleeper's {@code league_average_match}: every team also
     *                    plays that week's median score, which doubles the games
     *                    played and is invisible in the fixture list.
     */
    public static List<Odds> run(List<TeamState> teams, List<Fixture> fixtures, int playoffTeams,
                                 boolean medianMatch, int iterations, long seed) {
        if (teams.isEmpty() || iterations < 1) return List.of();

        int n = teams.size();
        int[] rosterIds = new int[n];
        double[] baseWins = new double[n];
        double[] basePoints = new double[n];
        double[] mu = new double[n];
        double[] sigma = new double[n];
        Map<Integer, Integer> indexOf = new HashMap<>();
        for (int i = 0; i < n; i++) {
            TeamState t = teams.get(i);
            rosterIds[i] = t.rosterId();
            baseWins[i] = t.winCredit();
            basePoints[i] = t.pointsFor();
            mu[i] = t.mu();
            sigma[i] = Math.max(0.0, t.sigma());
            indexOf.put(t.rosterId(), i);
        }

        List<int[][]> weeks = pairUp(fixtures, indexOf);

        int[] made = new int[n];
        int[] seedOne = new int[n];
        double[] winSum = new double[n];
        double[] pointSum = new double[n];
        // Distributions, not just their means. Both are filled from values the
        // loop below already has in hand.
        int[][] seedCounts = new int[n][n];
        double maxBase = 0;
        for (double b : baseWins) maxBase = Math.max(maxBase, b);
        int winBuckets = (int) Math.ceil(maxBase) + weeks.size() * (medianMatch ? 2 : 1) + 2;
        int[][] winCounts = new int[n][winBuckets];

        RandomGenerator rng = new SplittableRandom(seed);
        double[] wins = new double[n];
        double[] points = new double[n];
        double[] weekScore = new double[n];
        boolean[] played = new boolean[n];
        Integer[] order = new Integer[n];

        for (int iter = 0; iter < iterations; iter++) {
            System.arraycopy(baseWins, 0, wins, 0, n);
            System.arraycopy(basePoints, 0, points, 0, n);

            for (int[][] week : weeks) {
                Arrays.fill(played, false);
                for (int[] pair : week) {
                    int a = pair[0], b = pair[1];
                    weekScore[a] = draw(rng, mu[a], sigma[a]);
                    weekScore[b] = draw(rng, mu[b], sigma[b]);
                    played[a] = true;
                    played[b] = true;
                    points[a] += weekScore[a];
                    points[b] += weekScore[b];
                    if (weekScore[a] > weekScore[b]) wins[a] += 1;
                    else if (weekScore[b] > weekScore[a]) wins[b] += 1;
                    else {
                        wins[a] += 0.5;
                        wins[b] += 0.5;
                    }
                }
                if (medianMatch) applyMedianMatch(weekScore, played, wins, n);
            }

            for (int i = 0; i < n; i++) {
                order[i] = i;
                winSum[i] += wins[i];
                pointSum[i] += points[i];
            }
            final double[] w = wins, p = points;
            // Sleeper's default standings order: wins, then points for. Not
            // every league seeds this way, which is why a league with divisions
            // or a non-default playoff_seed_type never reaches this simulator.
            Arrays.sort(order, (x, y) -> {
                int byWins = Double.compare(w[y], w[x]);
                return byWins != 0 ? byWins : Double.compare(p[y], p[x]);
            });
            int spots = Math.min(playoffTeams, n);
            for (int rank = 0; rank < spots; rank++) made[order[rank]]++;
            seedOne[order[0]]++;
            // The same `order` the two lines above read, kept whole.
            for (int rank = 0; rank < n; rank++) seedCounts[order[rank]][rank]++;
            for (int i = 0; i < n; i++) {
                int bucket = (int) Math.round(wins[i]);
                if (bucket >= 0 && bucket < winBuckets) winCounts[i][bucket]++;
            }
        }

        List<Odds> out = new ArrayList<>(n);
        for (int i = 0; i < n; i++) {
            out.add(new Odds(rosterIds[i],
                    pct(made[i], iterations),
                    pct(seedOne[i], iterations),
                    round2(winSum[i] / iterations),
                    round2(pointSum[i] / iterations),
                    boxed(seedCounts[i]), boxed(winCounts[i])));
        }
        out.sort(Comparator.comparingDouble(Odds::madePct).reversed()
                .thenComparingInt(Odds::rosterId));
        return out;
    }

    /**
     * Fixtures to per-week index pairs. A matchup group that does not have
     * exactly two sides is dropped rather than guessed at: that is a bye, an
     * odd roster count, or a roster this league no longer has, and none of the
     * three is a game.
     */
    private static List<int[][]> pairUp(List<Fixture> fixtures, Map<Integer, Integer> indexOf) {
        Map<Integer, Map<Integer, List<Integer>>> byWeek = new TreeMap<>();
        for (Fixture f : fixtures) {
            Integer idx = indexOf.get(f.rosterId());
            if (idx == null) continue;
            byWeek.computeIfAbsent(f.week(), w -> new TreeMap<>())
                    .computeIfAbsent(f.matchupId(), m -> new ArrayList<>())
                    .add(idx);
        }
        List<int[][]> weeks = new ArrayList<>();
        for (Map<Integer, List<Integer>> groups : byWeek.values()) {
            List<int[]> pairs = new ArrayList<>();
            for (List<Integer> side : groups.values()) {
                if (side.size() == 2) pairs.add(new int[]{side.get(0), side.get(1)});
            }
            if (!pairs.isEmpty()) weeks.add(pairs.toArray(new int[0][]));
        }
        return weeks;
    }

    /**
     * The extra game against the week's median score. A win against the median
     * is a win in the standings but adds no points -- Sleeper counts it in the
     * record only, and double-counting the points would inflate the tiebreak.
     */
    private static void applyMedianMatch(double[] weekScore, boolean[] played, double[] wins, int n) {
        double[] scores = new double[n];
        int count = 0;
        for (int i = 0; i < n; i++) if (played[i]) scores[count++] = weekScore[i];
        if (count == 0) return;
        double[] sorted = Arrays.copyOf(scores, count);
        Arrays.sort(sorted);
        double median = count % 2 == 1
                ? sorted[count / 2]
                : (sorted[count / 2 - 1] + sorted[count / 2]) / 2.0;
        for (int i = 0; i < n; i++) {
            if (!played[i]) continue;
            if (weekScore[i] > median) wins[i] += 1;
            else if (weekScore[i] == median) wins[i] += 0.5;
        }
    }

    /** Clamped at zero: a fantasy week can be bad, it cannot be negative. */
    private static double draw(RandomGenerator rng, double mu, double sigma) {
        return Math.max(0.0, mu + sigma * rng.nextGaussian());
    }

    private static double pct(int hits, int iterations) {
        return round2(100.0 * hits / iterations);
    }

    /**
     * A counter array to an immutable {@code List<Integer>}.
     *
     * <p>The record holds lists rather than the raw {@code int[]} the loop fills
     * because a record's generated {@code equals} compares components with
     * {@code Objects.equals}, and arrays compare by IDENTITY -- so two runs from
     * the same seed produced structurally identical Odds that were never equal,
     * and {@code sameSeedGivesTheSameAnswer} failed. The counting itself stays
     * on primitive arrays; this converts once per roster at the end.
     */
    private static List<Integer> boxed(int[] counts) {
        List<Integer> out = new ArrayList<>(counts.length);
        for (int c : counts) out.add(c);
        return List.copyOf(out);
    }

    private static double round2(double v) {
        return Math.round(v * 100.0) / 100.0;
    }

    // --- strength estimation ---------------------------------------------

    /**
     * Each roster's weekly scoring mean and spread, shrunk toward the league's
     * by sample size ({@link #K}). A one- or two-game sample has a mean that is
     * mostly noise and a standard deviation that is meaningless on its own, and
     * this is the difference between "week 2 already has opinions" and odds that
     * earn their confidence as games get played.
     *
     * @param weeklyPointsByRoster every stored weekly score per roster; a roster
     *                             with no entry is treated as pure league average
     */
    public static Map<Integer, double[]> strengths(Map<Integer, List<Double>> weeklyPointsByRoster) {
        List<Double> all = new ArrayList<>();
        for (List<Double> v : weeklyPointsByRoster.values()) all.addAll(v);

        double leagueMean = mean(all);
        double leagueSigma = stdev(all, leagueMean);
        // A league with one game played has no spread to speak of; fall back to
        // a share of the mean rather than simulating twelve identical seasons.
        if (leagueSigma <= 0.0) leagueSigma = leagueMean * 0.25;

        Map<Integer, double[]> out = new LinkedHashMap<>();
        for (Map.Entry<Integer, List<Double>> e : weeklyPointsByRoster.entrySet()) {
            List<Double> v = e.getValue();
            int n = v.size();
            double w = n / (n + K);
            double m = n == 0 ? leagueMean : mean(v);
            double s = n < 2 ? leagueSigma : stdev(v, m);
            out.put(e.getKey(), new double[]{
                    w * m + (1 - w) * leagueMean,
                    w * s + (1 - w) * leagueSigma});
        }
        return out;
    }

    private static double mean(List<Double> v) {
        if (v.isEmpty()) return 0.0;
        double sum = 0;
        for (double d : v) sum += d;
        return sum / v.size();
    }

    private static double stdev(List<Double> v, double mean) {
        if (v.size() < 2) return 0.0;
        double sum = 0;
        for (double d : v) sum += (d - mean) * (d - mean);
        return Math.sqrt(sum / (v.size() - 1));
    }
}
