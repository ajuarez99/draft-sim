package com.ballknowers.draftsim.engine;

import java.util.List;

/**
 * Which of a player's games in a week Sleeper credited, inferred from the credited value (spec 044, research
 * R2 v2 / R3, plan review F5/F9). Sleeper's matchup payload does not say which game a lock-in manager locked,
 * so the only evidence is that a starter's credited points equal one game's league-scored points.
 *
 * <p>This is the one implementation of that match. {@code PlayerTrendsService.oneGameShare} and the lock-in
 * read both call it, so "credited exactly one game" cannot mean two things.
 *
 * <p><b>The list is taken as given</b> (F9): no All-Star filter, no week filter, no re-sorting. The caller owns
 * the game set and its order, which is date order for the lock-in read (the last index is "the last game") and
 * whatever order for {@code oneGameShare}, whose numerator ({@link Kind#matchedAGame}) does not depend on it.
 * The two callers deliberately pass different game sets.
 *
 * <p>A match is {@code |score - credited| < 0.005}, the tolerance {@code oneGameShare} always used: scores are
 * rounded to 2 dp by {@link GameScoringService}, and half-point scoring makes exact ties common.
 *
 * <p>The default for a starter nobody locked is the week's last scheduled game (measured, R3 v2: bench players,
 * who cannot be locked, are credited their last game in 99% of uniquely matched weeks). That is why a credit of
 * 0 with no 0-point game is {@link Kind#RODE_OUT_MISSED}, a default that landed on a game the player missed,
 * and not a failure to infer ({@link Kind#UNMATCHED}, measured at 0 real cases in 2024 and 2025).
 */
public final class CreditedGames {

    private CreditedGames() {}

    static final double TOLERANCE = 0.005;

    public enum Kind {
        /** One game matches and it is not the last: a provable lock, because a later game existed. */
        LOCK,
        /** One game matches and it is the last: the default, or a lock on the last game; not a choice. */
        RODE_OUT,
        /** Credited 0 and no game scored 0: the default landed on a last game the player missed. */
        RODE_OUT_MISSED,
        /** Two or more games match and none is the last: a lock, but which game is unknown. */
        TIE_NON_LAST,
        /** Two or more games match and one is the last: ambiguous. */
        TIE_WITH_LAST,
        /** Two or more games, nothing matches, and the credit is not 0: an inference failure. */
        UNMATCHED,
        /** Only one game that week: nothing was chosen. */
        SINGLE_GAME,
        /** No game rows that week. */
        NO_GAMES;

        /** The "credited exactly one game" numerator of {@code oneGameShare}: any game matched. */
        public boolean matchedAGame() {
            return this == LOCK || this == RODE_OUT || this == TIE_NON_LAST || this == TIE_WITH_LAST;
        }

        /** A week with a choice in it: two or more games. */
        public boolean multiGame() {
            return this != SINGLE_GAME && this != NO_GAMES;
        }
    }

    /**
     * @param kind  the classification
     * @param index for {@link Kind#LOCK} the matched game's index in the list given; -1 otherwise
     */
    public record Match(Kind kind, int index) {}

    /**
     * @param credited   the starter's {@code players_points} value for the week
     * @param weekScores his games that week, scored under the league's scoring, in the caller's order
     */
    public static Match match(double credited, List<Double> weekScores) {
        int n = weekScores.size();
        if (n == 0) return new Match(Kind.NO_GAMES, -1);
        if (n == 1) return new Match(Kind.SINGLE_GAME, -1);
        int first = -1;
        int count = 0;
        boolean last = false;
        for (int i = 0; i < n; i++) {
            if (Math.abs(weekScores.get(i) - credited) < TOLERANCE) {
                if (count == 0) first = i;
                count++;
                if (i == n - 1) last = true;
            }
        }
        if (count == 0) {
            return new Match(Math.abs(credited) < TOLERANCE ? Kind.RODE_OUT_MISSED : Kind.UNMATCHED, -1);
        }
        if (count == 1) return last ? new Match(Kind.RODE_OUT, -1) : new Match(Kind.LOCK, first);
        return new Match(last ? Kind.TIE_WITH_LAST : Kind.TIE_NON_LAST, -1);
    }
}
