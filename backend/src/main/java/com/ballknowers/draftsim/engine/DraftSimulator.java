package com.ballknowers.draftsim.engine;

import com.ballknowers.draftsim.domain.*;
import com.ballknowers.draftsim.profile.ManagerProfile;

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

    // Reusable per-pick scratch buffers, sized once from candidatePool and
    // reused for all ~210 picks this instance's run() makes. Each
    // DraftSimulator is used for exactly one iteration/run(), so this is not
    // a shared-mutable-state risk -- it just avoids the ~4 short-lived
    // allocations per pick (~840 per run, ~1.7M across a 2000-iteration run)
    // that choose()/sample() used to make every time (§B2d).
    private final List<BoardEntry> candidateBuf;
    private final int[] candidateIdxBuf;      // candidateBuf[i]'s index in `available`
    private final double[] scoresBuf;
    private final double[] weightsBuf;
    private final double[] positionalCache = new double[Position.values().length];
    private final double[] runCache = new double[Position.values().length];

    public DraftSimulator(DraftContext ctx, PickScorer scorer, double temperature, long seed) {
        this.ctx = ctx;
        this.scorer = scorer;
        this.temperature = temperature;
        this.rng = new SplittableRandom(seed);

        int poolSize = ctx.cfg().candidatePool();
        this.candidateBuf = new ArrayList<>(poolSize);
        this.candidateIdxBuf = new int[poolSize];
        this.scoresBuf = new double[poolSize];
        this.weightsBuf = new double[poolSize];
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

        // Dense small-integer-keyed state as arrays instead of boxed maps
        // (§B3) -- slots and pick numbers are both small and contiguous.
        RosterState[] rosters = new RosterState[teams + 1];
        for (int s = 1; s <= teams; s++) rosters[s] = new RosterState();
        Deque<Position> recent = new ArrayDeque<>();

        long[] picked = new long[total + 1];
        Map<Integer, long[]> snapshots = new HashMap<>();
        boolean[] myPickMask = new boolean[total + 1];
        for (int p : myPicks) if (p >= 0 && p <= total) myPickMask[p] = true;

        Map<Long, BoardEntry> byId = ctx.byId();   // built once on DraftContext, shared

        for (int pickNo = 1; pickNo <= total; pickNo++) {
            int round = DraftSlot.round(pickNo, teams);
            int slot = DraftSlot.slot(pickNo, teams);

            if (pickNo <= total && myPickMask[pickNo]) {
                snapshots.put(pickNo, topAvailable(available, round, snapshotDepth));
            }

            long already = ctx.completedAt(pickNo);
            if (already != 0L) {
                BoardEntry e = byId.get(already);
                if (e != null) {
                    available.remove(e);
                    rosters[slot].add(e);
                    recent.addFirst(e.position());
                    picked[pickNo] = already;
                }
                continue;
            }

            int chosenIdx = choose(available, pickNo, round, slot, rosters[slot], recent);
            if (chosenIdx < 0) break;

            BoardEntry choice = available.remove(chosenIdx);
            rosters[slot].add(choice);
            recent.addFirst(choice.position());
            picked[pickNo] = choice.player().id();
        }

        Map<Integer, RosterState> rosterMap = new HashMap<>();
        for (int s = 1; s <= teams; s++) rosterMap.put(s, rosters[s]);
        return new RunResult(picked, snapshots, rosterMap);
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

    /** @return the index into {@code available} of the chosen entry, or -1 if none. */
    private int choose(List<BoardEntry> available, int pickNo, int round, int slot,
                       RosterState roster, Deque<Position> recent) {

        int poolSize = ctx.cfg().candidatePool();
        candidateBuf.clear();
        int n = 0;
        int avail = available.size();
        for (int i = 0; i < avail && n < poolSize; i++) {
            BoardEntry e = available.get(i);
            if (!ctx.rules().isDraftable(e, round)) continue;
            candidateBuf.add(e);
            candidateIdxBuf[n] = i;
            n++;
        }
        if (n == 0) {
            // Only kickers and defenses left and it is too early for them. Take
            // the best available anyway rather than stalling the draft.
            return available.isEmpty() ? -1 : 0;
        }

        var profile = ctx.profileFor(slot);
        // Terms that only depend on a candidate's position, not the candidate
        // itself, are computed once per position here instead of once per
        // candidate below (§B2c) -- at most 6 positions exist, well under the
        // pool of up to 30 candidates. `lineup` similarly captures everything
        // rosterNeed() needs about the roster's *current* shape once per pick,
        // so scoring each candidate against it is O(1) (§B2a) rather than a
        // fresh roster-wide re-walk per candidate.
        Object lineup = ctx.rules().prepareLineup(roster, ctx.settings(), ctx::valueOf);
        double reachBias = profile.reachBias();
        for (Position p : Position.values()) {
            int ord = p.ordinal();
            positionalCache[ord] = scorer.positionalTerm(round, p, profile);
            runCache[ord] = scorer.runPressure(recent, p);
        }

        for (int i = 0; i < n; i++) {
            BoardEntry c = candidateBuf.get(i);
            int ord = c.position().ordinal();
            scoresBuf[i] = scorer.score(c, pickNo, lineup, reachBias,
                    positionalCache[ord], runCache[ord]);
        }
        // Per-seat unpredictability is a MULTIPLIER on the run temperature, not a
        // replacement for it. That keeps the global chaos slider meaningful: at
        // temperature 0 the board is still the modal board, however erratic a seat
        // is said to be.
        int chosen = sample(scoresBuf, n, temperature * profile.unpredictability());
        return candidateIdxBuf[chosen];
    }

    /** Softmax over scores at the given temperature; T at or below ~0 is argmax. */
    int sample(double[] scores) {
        return sample(scores, scores.length, temperature);
    }

    int sample(double[] scores, double temperature) {
        return sample(scores, scores.length, temperature);
    }

    private int sample(double[] scores, int n, double temperature) {
        if (temperature <= 1e-6) {
            int best = 0;
            for (int i = 1; i < n; i++) if (scores[i] > scores[best]) best = i;
            return best;
        }
        double max = Double.NEGATIVE_INFINITY;
        for (int i = 0; i < n; i++) max = Math.max(max, scores[i]);

        double sum = 0;
        double[] w = (n <= weightsBuf.length) ? weightsBuf : new double[n];
        for (int i = 0; i < n; i++) {
            w[i] = Math.exp((scores[i] - max) / temperature);
            sum += w[i];
        }
        double r = rng.nextDouble() * sum;
        double acc = 0;
        for (int i = 0; i < n; i++) {
            acc += w[i];
            if (r <= acc) return i;
        }
        return n - 1;
    }

    /** @param picked index is pick number, value is player id (0 = not picked) */
    public record RunResult(long[] picked,
                            Map<Integer, long[]> availableAtMyPicks,
                            Map<Integer, RosterState> rosters) {}
}
