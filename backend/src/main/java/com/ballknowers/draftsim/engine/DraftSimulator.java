package com.ballknowers.draftsim.engine;

import com.ballknowers.draftsim.domain.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * One complete simulated draft. Single-threaded and self-contained: a fresh
 * instance per iteration, so nothing is shared and virtual threads can run
 * thousands of these at once.
 */
public final class DraftSimulator {

    private static final Logger log = LoggerFactory.getLogger(DraftSimulator.class);

    // One WARN per duplicated player per process, not per iteration. A fresh
    // DraftSimulator is built for every iteration and a duplicated startState
    // repeats identically in all of them, so an unguarded log line would emit 2000
    // copies of the same warning per run and bury everything else on draft night.
    // Bounded by the player table, so it cannot grow without limit.
    private static final Set<Long> WARNED_DUPLICATES = ConcurrentHashMap.newKeySet();

    private final DraftContext ctx;
    private final double temperature;
    private final SplittableRandom rng;
    // Owns the extracted scoring/sampling logic (engine/PickDecider.java) --
    // shared verbatim with the mock draft room's MockDraftEngine so the two
    // never grow independent copies of the same decision (§4 of
    // claude/next-features-roadmap.md, Phase 3).
    private final PickDecider decider;

    public DraftSimulator(DraftContext ctx, PickScorer scorer, double temperature, long seed) {
        this.ctx = ctx;
        this.temperature = temperature;
        this.rng = new SplittableRandom(seed);
        this.decider = new PickDecider(ctx, scorer, temperature);
    }

    /**
     * @param myPicks pick numbers to snapshot the available board at
     * @param snapshotDepth how many of the best available to record at each
     */
    public RunResult run(int[] myPicks, int snapshotDepth) {
        int teams = ctx.settings().teams();
        int rounds = ctx.settings().rounds();
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
                snapshots.put(pickNo, topAvailable(available, round, rounds, snapshotDepth));
            }

            long already = ctx.completedAt(pickNo);
            if (already != 0L) {
                BoardEntry e = byId.get(already);
                // remove()'s return value used to be ignored, which is how the same
                // player could occupy two picks: a startState carrying a duplicate
                // id (the frontend's picker listed a player the board already showed
                // as gone) made the second removal a silent no-op while the roster
                // add below still ran, so the player landed on a roster twice and
                // was double-counted in rosterNeed. Skipping the pick surfaces the
                // duplicate to the caller as an unpreserved pick instead of quietly
                // corrupting the roster -- which matters more once Phase 3's
                // mock_draft_pick table starts persisting these.
                if (e != null && available.remove(e)) {
                    rosters[slot].add(e);
                    recent.addFirst(e.position());
                    picked[pickNo] = already;
                } else if (e != null && WARNED_DUPLICATES.add(already)) {
                    // Say something. Until now the only thing that noticed a
                    // duplicate in startState was a check in the frontend -- the
                    // backend dropped the pick and the caller got a board with an
                    // unexplained hole in it and no explanation anywhere.
                    log.warn("startState pick {} names player {} who is already off the board"
                            + " -- skipping the pick; the caller sent a duplicate", pickNo, already);
                }
                continue;
            }

            BoardEntry choice = decider.decideAndApply(
                    available, pickNo, round, rounds, total, slot, rosters[slot], recent, rng);
            if (choice == null) break;
            picked[pickNo] = choice.player().id();
        }

        Map<Integer, RosterState> rosterMap = new HashMap<>();
        for (int s = 1; s <= teams; s++) rosterMap.put(s, rosters[s]);
        return new RunResult(picked, snapshots, rosterMap);
    }

    private long[] topAvailable(List<BoardEntry> available, int round, int rounds, int depth) {
        long[] out = new long[Math.min(depth, available.size())];
        int n = 0;
        for (BoardEntry e : available) {
            if (n == out.length) break;
            if (!ctx.rules().isDraftable(e, round, rounds)) continue;
            out[n++] = e.player().id();
        }
        return n == out.length ? out : Arrays.copyOf(out, n);
    }

    /**
     * Kept for {@code DraftSimulatorTest.softmaxFavoursHigherScoresWithoutBeingDeterministic},
     * which exercises the softmax directly against this instance's own rng/temperature.
     * The real implementation now lives in {@link PickDecider#sample}.
     */
    int sample(double[] scores) {
        return decider.sample(scores, scores.length, temperature, rng);
    }

    /** @param picked index is pick number, value is player id (0 = not picked) */
    public record RunResult(long[] picked,
                            Map<Integer, long[]> availableAtMyPicks,
                            Map<Integer, RosterState> rosters) {}
}
