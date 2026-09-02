package com.ballknowers.draftsim.engine;

import com.ballknowers.draftsim.domain.BoardEntry;
import com.ballknowers.draftsim.domain.Position;
import com.ballknowers.draftsim.domain.RosterState;

import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.SplittableRandom;

/**
 * Decides one pick and applies it to the given available/roster/recent state.
 *
 * Extracted from {@link DraftSimulator}'s former private {@code choose()} so the
 * batch {@code run()} loop and the interactive mock room
 * (claude/next-features-roadmap.md §4, Phase 3) share one scoring
 * implementation rather than growing a second one. {@code DraftSimulator} still
 * owns one long-lived {@link SplittableRandom} per iteration and passes it on
 * every call, so its output is unaffected -- this type carries no RNG state of
 * its own, since the mock room calls it from a stateless per-HTTP-request
 * service with no long-lived RNG to carry across requests.
 */
public final class PickDecider {

    private final DraftContext ctx;
    private final PickScorer scorer;
    private final double temperature;

    // Reusable per-pick scratch buffers, same reasoning as DraftSimulator's
    // former fields: one instance is used for a whole run (batch) or a whole
    // mock-session advance, and reusing these avoids the handful of short-lived
    // allocations per pick that a fresh array per call would cost (§B2d).
    private final List<BoardEntry> candidateBuf;
    private final int[] candidateIdxBuf;      // candidateBuf[i]'s index in `available`
    private final double[] scoresBuf;
    private final double[] weightsBuf;
    private final double[] positionalCache = new double[Position.values().length];
    private final double[] runCache = new double[Position.values().length];

    public PickDecider(DraftContext ctx, PickScorer scorer, double temperature) {
        this.ctx = ctx;
        this.scorer = scorer;
        this.temperature = temperature;

        int poolSize = ctx.cfg().candidatePool();
        this.candidateBuf = new ArrayList<>(poolSize);
        this.candidateIdxBuf = new int[poolSize];
        this.scoresBuf = new double[poolSize];
        this.weightsBuf = new double[poolSize];
    }

    /**
     * Chooses the winner at this pick, removes it from {@code available}, and
     * applies it to {@code roster}/{@code recent}.
     *
     * @return the chosen entry, or {@code null} if none was available (the
     *         board is exhausted).
     */
    public BoardEntry decideAndApply(List<BoardEntry> available, int pickNo, int round, int rounds,
                                     int totalPicks, int slot, RosterState roster,
                                     Deque<Position> recent, SplittableRandom rng) {
        int chosenIdx = choose(available, pickNo, round, rounds, totalPicks, slot, roster, recent, rng);
        if (chosenIdx < 0) return null;

        BoardEntry choice = available.remove(chosenIdx);
        roster.add(choice);
        recent.addFirst(choice.position());
        return choice;
    }

    /** @return the index into {@code available} of the chosen entry, or -1 if none. */
    private int choose(List<BoardEntry> available, int pickNo, int round, int rounds, int totalPicks,
                       int slot, RosterState roster, Deque<Position> recent,
                       SplittableRandom rng) {

        int poolSize = ctx.cfg().candidatePool();
        candidateBuf.clear();
        int n = 0;
        int avail = available.size();
        for (int i = 0; i < avail && n < poolSize; i++) {
            BoardEntry e = available.get(i);
            if (!ctx.rules().isDraftable(e, round, rounds)) continue;
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
        // The priors table is keyed on fraction-of-draft, not round, so it
        // transfers across league sizes -- bucket once per pick, not per
        // position, since every position at this pick shares it.
        int bucket = ctx.priors().bucketOf(pickNo, totalPicks);
        for (Position p : Position.values()) {
            int ord = p.ordinal();
            positionalCache[ord] = scorer.positionalTerm(bucket, p, profile);
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
        int chosen = sample(scoresBuf, n, temperature * profile.unpredictability(), rng);
        return candidateIdxBuf[chosen];
    }

    /** Softmax over scores at the given temperature; T at or below ~0 is argmax. */
    int sample(double[] scores, int n, double temperature, SplittableRandom rng) {
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
}
