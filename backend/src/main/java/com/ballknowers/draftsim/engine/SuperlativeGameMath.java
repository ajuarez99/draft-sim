package com.ballknowers.draftsim.engine;

import com.ballknowers.draftsim.store.*;
import java.util.*;
import static com.ballknowers.draftsim.util.Rounding.round2;
import com.ballknowers.draftsim.engine.SeasonSuperlativesService.*;

import static com.ballknowers.draftsim.engine.SeasonSuperlativesService.*;

/**
 * Close games, luck and bench aggregation: the pure arithmetic behind CLOSEST_GAME,
 * CLOSE_WINS/LOSSES, LUCKIEST/UNLUCKIEST and MOST_BENCH_POINTS.
 *
 * <p>Moved out of {@link SeasonSuperlativesService} by specs/021-codebase-cleanup (T051)
 * as a pure move, proven by live JSON parity against the pre-split build.
 */
final class SuperlativeGameMath {

    private SuperlativeGameMath() {}

    /** Package-private so SeasonSuperlativesCloseGamesTest (T020) can call it directly, without Postgres. */
    static Map<Integer, List<GameDetail>> closeGames(List<LeagueMatchupRepository.PairedGame> games,
                                                      double margin, boolean wantWinners) {
        Map<Integer, List<GameDetail>> out = new LinkedHashMap<>();
        for (LeagueMatchupRepository.PairedGame g : games) {
            int cmp = g.aPoints().compareTo(g.bPoints());
            if (cmp == 0) continue; // a tie is neither a close win nor a close loss for either side
            double margin2 = g.aPoints().subtract(g.bPoints()).abs().doubleValue();
            if (!(margin2 < margin)) continue; // strict "under", per FR-011

            boolean aWon = cmp > 0;
            int winnerRoster = aWon ? g.aRosterId() : g.bRosterId();
            int loserRoster = aWon ? g.bRosterId() : g.aRosterId();
            double winnerPts = (aWon ? g.aPoints() : g.bPoints()).doubleValue();
            double loserPts = (aWon ? g.bPoints() : g.aPoints()).doubleValue();

            int rosterId = wantWinners ? winnerRoster : loserRoster;
            int opponentId = wantWinners ? loserRoster : winnerRoster;
            double myPoints = wantWinners ? winnerPts : loserPts;
            double oppPoints = wantWinners ? loserPts : winnerPts;

            // opponentTeamName is filled in by the caller, which has the name
            // map this pure function deliberately does not depend on.
            out.computeIfAbsent(rosterId, k -> new ArrayList<>())
                    .add(new GameDetail(g.week(), rosterId, opponentId, null, myPoints, oppPoints, margin2));
        }
        return out;
    }

    /**
     * T031(a): picks the max ({@code wantMax}) or min winsAboveExpected --
     * LUCKIEST and UNLUCKIEST are never swapped because the caller states
     * which one it wants rather than this method guessing from the kind.
     * Package-private so SeasonSuperlativesLuckTest can call it directly,
     * without Postgres.
     */
    static Superlative luckSuperlative(Kind kind, List<ExpectedWinsService.TeamRow> teams, boolean wantMax,
                                       boolean early, int throughWeek) {
        double extreme = wantMax
                ? teams.stream().mapToDouble(ExpectedWinsService.TeamRow::winsAboveExpected).max().orElseThrow()
                : teams.stream().mapToDouble(ExpectedWinsService.TeamRow::winsAboveExpected).min().orElseThrow();
        List<ExpectedWinsService.TeamRow> tied = teams.stream()
                .filter(t -> Double.compare(t.winsAboveExpected(), extreme) == 0)
                .sorted(Comparator.comparingInt(ExpectedWinsService.TeamRow::rosterId))
                .toList();
        List<Holder> holders = tied.stream()
                .map(t -> new Holder(t.rosterId(), t.managerId(), t.teamName(), t.username(), t.avatarId()))
                .toList();
        List<DetailRow> detail = tied.stream()
                .<DetailRow>map(t -> new LuckDetail(t.rosterId(), t.actualWins(), t.expectedWins(),
                        t.winsAboveExpected(), t.swingWeeks(), 1, throughWeek, luckReading(t.winsAboveExpected())))
                .toList();
        return new Superlative(kind, true, null, early, extreme, "WINS", holders, null, detail, null);
    }

    /** T032: "2.40 more wins than their scores earned" / "1.30 fewer wins than their scores earned". */
    static String luckReading(double winsAboveExpected) {
        String word = winsAboveExpected >= 0 ? "more" : "fewer";
        return String.format(Locale.ROOT, "%.2f %s wins than their scores earned", Math.abs(winsAboveExpected), word);
    }

    /**
     * The pure aggregation core (T033): sums each roster's (optimal - started)
     * gap over its valid weeks only (FR-007 -- an invalid week is excluded,
     * never added as zero), and tracks the single worst week. Package-private
     * so SeasonSuperlativesLuckTest (T031 c/d) can call it directly, without
     * Postgres or a real {@code RealizedLineupService}.
     */
    static Map<Integer, BenchAgg> aggregateBench(List<BenchWeekEntry> entries) {
        Map<Integer, Double> pointsLeftByRoster = new HashMap<>();
        Map<Integer, Integer> weeksCountedByRoster = new HashMap<>();
        Map<Integer, BiggestBenchWeek> biggestByRoster = new HashMap<>();
        for (BenchWeekEntry e : entries) {
            if (!e.valid()) continue;
            pointsLeftByRoster.merge(e.rosterId(), e.pointsLeft(), Double::sum);
            weeksCountedByRoster.merge(e.rosterId(), 1, Integer::sum);
            BiggestBenchWeek current = biggestByRoster.get(e.rosterId());
            if (current == null || e.pointsLeft() > current.pointsLeft()) {
                biggestByRoster.put(e.rosterId(), new BiggestBenchWeek(e.week(), round2(e.pointsLeft())));
            }
        }
        Map<Integer, BenchAgg> out = new HashMap<>();
        for (Integer rosterId : pointsLeftByRoster.keySet()) {
            out.put(rosterId, new BenchAgg(round2(pointsLeftByRoster.get(rosterId)),
                    weeksCountedByRoster.get(rosterId), biggestByRoster.get(rosterId)));
        }
        return out;
    }
}
