package com.ballknowers.draftsim.engine;

import com.ballknowers.draftsim.domain.BoardEntry;
import com.ballknowers.draftsim.domain.DraftSlot;
import com.ballknowers.draftsim.profile.ManagerProfile;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.IntConsumer;

/**
 * Runs N independent simulations across virtual threads and aggregates them.
 *
 * Each iteration is fully independent — its own RNG, its own roster state, its
 * own copy of the available pool — so there is no shared mutable state and no
 * locking on the hot path. Results are merged single-threaded afterwards, which
 * is cheap next to the simulation itself.
 */
@Component
public class MonteCarloRunner {

    private static final Logger log = LoggerFactory.getLogger(MonteCarloRunner.class);
    private static final int SNAPSHOT_DEPTH = 75;   // how deep the availability curve goes
    private static final int ALTERNATIVES = 3;

    public SimulationResult run(DraftContext ctx,
                                int mySlot,
                                int iterations,
                                double temperature,
                                long seed,
                                SimulationResult.Confidence confidence,
                                IntConsumer onProgress) {

        int teams = ctx.settings().teams();
        int rounds = ctx.settings().rounds();
        int[] myPicks = DraftSlot.picksForSlot(mySlot, teams, rounds);
        PickScorer scorer = new PickScorer(ctx.cfg(), ctx.rules(), ctx.priors());

        long start = System.nanoTime();
        List<DraftSimulator.RunResult> results = new ArrayList<>(iterations);
        AtomicInteger done = new AtomicInteger();

        try (ExecutorService pool = Executors.newVirtualThreadPerTaskExecutor()) {
            List<Future<DraftSimulator.RunResult>> futures = new ArrayList<>(iterations);
            for (int i = 0; i < iterations; i++) {
                long runSeed = seed + i * 0x9E3779B97F4A7C15L;
                futures.add(pool.submit(() -> {
                    DraftSimulator.RunResult r =
                            new DraftSimulator(ctx, scorer, temperature, runSeed).run(myPicks, SNAPSHOT_DEPTH);
                    int n = done.incrementAndGet();
                    if (onProgress != null && n % Math.max(1, iterations / 50) == 0) onProgress.accept(n);
                    return r;
                }));
            }
            for (Future<DraftSimulator.RunResult> f : futures) {
                try {
                    results.add(f.get());
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new IllegalStateException("simulation interrupted", e);
                } catch (ExecutionException e) {
                    throw new IllegalStateException("simulation failed", e.getCause());
                }
            }
        }
        log.info("{} iterations in {} ms", iterations, (System.nanoTime() - start) / 1_000_000);

        return aggregate(ctx, mySlot, myPicks, iterations, temperature, results, confidence);
    }

    private SimulationResult aggregate(DraftContext ctx, int mySlot, int[] myPicks,
                                       int iterations, double temperature,
                                       List<DraftSimulator.RunResult> runs,
                                       SimulationResult.Confidence confidence) {

        int teams = ctx.settings().teams();
        int rounds = ctx.settings().rounds();
        int total = ctx.totalPicks();

        Map<Long, BoardEntry> byId = new HashMap<>();
        for (BoardEntry e : ctx.board()) byId.put(e.player().id(), e);

        // --- modal pick per slot ---------------------------------------
        List<Map<Long, Integer>> counts = new ArrayList<>(total + 1);
        for (int i = 0; i <= total; i++) counts.add(new HashMap<>());
        for (DraftSimulator.RunResult r : runs) {
            for (int p = 1; p <= total; p++) {
                long id = r.picked()[p];
                if (id != 0) counts.get(p).merge(id, 1, Integer::sum);
            }
        }

        List<SimulationResult.PredictedPick> board = new ArrayList<>(total);
        for (BoardAssembler.Assignment a : BoardAssembler.assemble(counts, iterations, ALTERNATIVES)) {
            int slot = DraftSlot.slot(a.pickNo(), teams);
            ManagerProfile prof = ctx.profileFor(slot);

            List<SimulationResult.Candidate> alts = a.alternatives().stream()
                    .map(r -> new SimulationResult.Candidate(ref(byId.get(r.playerId())), r.probability()))
                    .filter(c -> c.player() != null)
                    .toList();

            board.add(new SimulationResult.PredictedPick(
                    a.pickNo(), DraftSlot.round(a.pickNo(), teams), slot, prof.displayName(),
                    ref(byId.get(a.playerId())), a.probability(), a.isModal(), alts));
        }

        // --- availability curves ---------------------------------------
        Map<Long, Map<Integer, Integer>> survived = new HashMap<>();
        Map<Integer, Map<Long, Integer>> bestAvailCounts = new HashMap<>();
        for (int p : myPicks) bestAvailCounts.put(p, new HashMap<>());

        for (DraftSimulator.RunResult r : runs) {
            for (Map.Entry<Integer, long[]> e : r.availableAtMyPicks().entrySet()) {
                int pickNo = e.getKey();
                long[] avail = e.getValue();
                for (long id : avail) {
                    survived.computeIfAbsent(id, k -> new HashMap<>()).merge(pickNo, 1, Integer::sum);
                }
                if (avail.length > 0) {
                    bestAvailCounts.get(pickNo).merge(avail[0], 1, Integer::sum);
                }
            }
        }

        List<SimulationResult.AvailabilityRow> availability = survived.entrySet().stream()
                .map(e -> {
                    Map<Integer, Double> probs = new TreeMap<>();
                    e.getValue().forEach((pick, n) -> probs.put(pick, n / (double) iterations));
                    return new SimulationResult.AvailabilityRow(ref(byId.get(e.getKey())), probs);
                })
                .filter(row -> row.player() != null)
                .sorted(Comparator.comparingDouble(row -> row.player().adp()))
                .toList();

        Map<Integer, List<SimulationResult.Candidate>> bestAvailable = new TreeMap<>();
        bestAvailCounts.forEach((pick, c) -> bestAvailable.put(pick,
                c.entrySet().stream()
                        .sorted(Map.Entry.<Long, Integer>comparingByValue().reversed())
                        .limit(10)
                        .map(e -> new SimulationResult.Candidate(ref(byId.get(e.getKey())),
                                e.getValue() / (double) iterations))
                        .filter(cand -> cand.player() != null)
                        .toList()));

        return new SimulationResult(
                iterations, temperature, teams, rounds, mySlot,
                Arrays.stream(myPicks).boxed().toList(),
                board, availability, bestAvailable, confidence);
    }

    private static SimulationResult.PlayerRef ref(BoardEntry e) {
        if (e == null) return null;
        return new SimulationResult.PlayerRef(
                e.player().id(), e.player().sleeperId(), e.player().name(),
                e.position().name(), e.player().team(), e.adp(), e.positionalRank());
    }
}
