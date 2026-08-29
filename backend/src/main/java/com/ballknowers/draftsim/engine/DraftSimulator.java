package com.ballknowers.draftsim.engine;

import com.ballknowers.draftsim.domain.*;

import java.util.*;

/**
 * One complete simulated draft. Single-threaded and self-contained: a fresh
 * instance per iteration, so nothing is shared and virtual threads can run
 * thousands of these at once.
 */
public final class DraftSimulator {

    private final DraftContext ctx;
    private final PickScorer scorer;
    private final double temperature;
    private final SplittableRandom rng;

    public DraftSimulator(DraftContext ctx, PickScorer scorer, double temperature, long seed) {
        this.ctx = ctx;
        this.scorer = scorer;
        this.temperature = temperature;
        this.rng = new SplittableRandom(seed);
    }

    /**
     * @param myPicks pick numbers to snapshot the available board at
     * @param snapshotDepth how many of the best available to record at each
     */
    public RunResult run(int[] myPicks, int snapshotDepth) {
        int teams = ctx.settings().teams();
        int total = ctx.totalPicks();

        // Available players, best board position first. ArrayList + index removal
        // is fine: the pool is ~600 relevant players and removals are ~200.
        List<BoardEntry> available = new ArrayList<>(ctx.board());
        Map<Integer, RosterState> rosters = new HashMap<>();
        for (int s = 1; s <= teams; s++) rosters.put(s, new RosterState());
        Deque<Position> recent = new ArrayDeque<>();

        long[] picked = new long[total + 1];
        Map<Integer, long[]> snapshots = new HashMap<>();
        Set<Integer> myPickSet = new HashSet<>();
        for (int p : myPicks) myPickSet.add(p);

        // Replay any picks already made before simulating the remainder.
        Map<Long, BoardEntry> byId = new HashMap<>();
        for (BoardEntry e : ctx.board()) byId.put(e.player().id(), e);

        for (int pickNo = 1; pickNo <= total; pickNo++) {
            int round = DraftSlot.round(pickNo, teams);
            int slot = DraftSlot.slot(pickNo, teams);

            if (myPickSet.contains(pickNo)) {
                snapshots.put(pickNo, topAvailable(available, round, snapshotDepth));
            }

            Long already = ctx.completedPicks().get(pickNo);
            if (already != null) {
                BoardEntry e = byId.get(already);
                if (e != null) {
                    available.remove(e);
                    rosters.get(slot).add(e);
                    recent.addFirst(e.position());
                    picked[pickNo] = already;
                }
                continue;
            }

            BoardEntry choice = choose(available, pickNo, round, slot, rosters.get(slot), recent);
            if (choice == null) break;

            available.remove(choice);
            rosters.get(slot).add(choice);
            recent.addFirst(choice.position());
            picked[pickNo] = choice.player().id();
        }

        return new RunResult(picked, snapshots, rosters);
    }

    private long[] topAvailable(List<BoardEntry> available, int round, int depth) {
        long[] out = new long[Math.min(depth, available.size())];
        int n = 0;
        for (BoardEntry e : available) {
            if (n == out.length) break;
            if (!ctx.rules().isDraftable(e, round)) continue;
            out[n++] = e.player().id();
        }
        return n == out.length ? out : Arrays.copyOf(out, n);
    }

    private BoardEntry choose(List<BoardEntry> available, int pickNo, int round, int slot,
                              RosterState roster, Deque<Position> recent) {

        int poolSize = ctx.cfg().candidatePool();
        List<BoardEntry> candidates = new ArrayList<>(poolSize);
        for (BoardEntry e : available) {
            if (candidates.size() == poolSize) break;
            if (!ctx.rules().isDraftable(e, round)) continue;
            candidates.add(e);
        }
        if (candidates.isEmpty()) {
            // Only kickers and defenses left and it is too early for them. Take
            // the best available anyway rather than stalling the draft.
            return available.isEmpty() ? null : available.getFirst();
        }

        var profile = ctx.profileFor(slot);
        double[] scores = new double[candidates.size()];
        for (int i = 0; i < candidates.size(); i++) {
            scores[i] = scorer.score(candidates.get(i), pickNo, round, roster,
                    profile, ctx.settings(), recent);
        }
        // Per-seat unpredictability is a MULTIPLIER on the run temperature, not a
        // replacement for it. That keeps the global chaos slider meaningful: at
        // temperature 0 the board is still the modal board, however erratic a seat
        // is said to be.
        return candidates.get(sample(scores, temperature * profile.unpredictability()));
    }

    /** Softmax over scores at the given temperature; T at or below ~0 is argmax. */
    int sample(double[] scores) {
        return sample(scores, temperature);
    }

    int sample(double[] scores, double temperature) {
        if (temperature <= 1e-6) {
            int best = 0;
            for (int i = 1; i < scores.length; i++) if (scores[i] > scores[best]) best = i;
            return best;
        }
        double max = Double.NEGATIVE_INFINITY;
        for (double s : scores) max = Math.max(max, s);

        double sum = 0;
        double[] w = new double[scores.length];
        for (int i = 0; i < scores.length; i++) {
            w[i] = Math.exp((scores[i] - max) / temperature);
            sum += w[i];
        }
        double r = rng.nextDouble() * sum;
        double acc = 0;
        for (int i = 0; i < w.length; i++) {
            acc += w[i];
            if (r <= acc) return i;
        }
        return w.length - 1;
    }

    /** @param picked index is pick number, value is player id (0 = not picked) */
    public record RunResult(long[] picked,
                            Map<Integer, long[]> availableAtMyPicks,
                            Map<Integer, RosterState> rosters) {}
}
