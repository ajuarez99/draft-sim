package com.ballknowers.draftsim.engine;

import java.util.Arrays;

/**
 * The pure maths of spec 044: how much better than his baseline a game must be before locking it beats waiting
 * (research R8), and the quartiles the margin tables print.
 *
 * <p><b>Why not the naive break-even.</b> The first idea was the margin at which "riding it out beat the lock"
 * drops below 50%. Measured over every candidate game (a starter's non-last played game in a multi-game week,
 * locked or not), that rate falls under 50% already in the 0-10% band, so the rule collapses to "lock anything
 * above average" for every games-left count. It values waiting as "take the last game" and ignores that the
 * player can lock a later game too. The recursion below prices the option instead.
 *
 * <p><b>Optimal stopping.</b> Let {@code m} be a game's margin against its baseline, drawn from the league's own
 * per-game distribution (independent draws; one league-wide distribution, so a streaky player's real threshold is
 * higher), and {@code p} the measured rate at which the default landed on a missed last game (that game scores
 * 0, a margin of -1). With one game left the choice is made for you: the last game, worth its expected margin
 * unless it is missed. With {@code k + 1} left you either take this game or keep the option:
 * <pre>
 *   V1     = (1 - p) * E[m] + p * (-1)
 *   V(k+1) = E[ max(m, Vk) ]
 * </pre>
 * and the rule is "lock with k games left iff margin >= Vk". {@code p} is applied only to the last game.
 */
public final class LockThresholds {

    private LockThresholds() {}

    /**
     * @param margins every candidate game's margin, as a fraction of its baseline (0.25 is +25%)
     * @param pMissed share of defaults that landed on a missed last game, in [0, 1]
     * @param cap     the largest games-left value to fit
     * @return a {@code cap + 1} array whose index {@code k} (1..cap) is the threshold with k games left; index 0
     *         is {@code NaN} (no games left means no decision). Empty {@code margins} gives all {@code NaN}.
     */
    public static double[] fit(double[] margins, double pMissed, int cap) {
        double[] v = new double[cap + 1];
        Arrays.fill(v, Double.NaN);
        if (margins.length == 0 || cap < 1) return v;
        v[1] = (1.0 - pMissed) * mean(margins) + pMissed * -1.0;
        for (int k = 1; k < cap; k++) {
            double floor = v[k];
            double sum = 0;
            for (double m : margins) sum += Math.max(m, floor);
            v[k + 1] = sum / margins.length;
        }
        return v;
    }

    /**
     * {q1, median, q3} by the <b>exclusive</b> method, which is what Python's {@code statistics.quantiles(n=4)}
     * does by default (hand checks against the research scripts must agree to the last digit; pinned in the test
     * at n = 10 and n = 11). Null below 2 values, where that method is undefined.
     */
    public static double[] quantiles(double[] values) {
        int ld = values.length;
        if (ld < 2) return null;
        double[] data = values.clone();
        Arrays.sort(data);
        int n = 4;
        int m = ld + 1;
        double[] out = new double[3];
        for (int i = 1; i < n; i++) {
            int j = i * m / n;                              // rescale i to m / n
            j = j < 1 ? 1 : Math.min(j, ld - 1);            // clamp to 1 .. ld - 1
            int delta = i * m - j * n;                      // exact integer arithmetic
            out[i - 1] = (data[j - 1] * (n - delta) + data[j] * delta) / n;
        }
        return out;
    }

    private static double mean(double[] v) {
        double s = 0;
        for (double x : v) s += x;
        return s / v.length;
    }
}
