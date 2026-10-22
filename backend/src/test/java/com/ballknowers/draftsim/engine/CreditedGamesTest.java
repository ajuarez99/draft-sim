package com.ballknowers.draftsim.engine;

import com.ballknowers.draftsim.engine.CreditedGames.Kind;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/** Spec 044 T007: every classification of {@link CreditedGames#match}, and the invariant that they partition. */
class CreditedGamesTest {

    private static CreditedGames.Match m(double credited, Double... scores) {
        return CreditedGames.match(credited, List.of(scores));
    }

    @Test
    void aUniqueNonLastMatchIsALockAtThatIndex() {
        CreditedGames.Match r = m(30.0, 20.0, 30.0, 25.0);
        assertEquals(Kind.LOCK, r.kind());
        assertEquals(1, r.index());
        assertEquals(0, m(20.0, 20.0, 30.0).index(), "the first of two games");
    }

    @Test
    void aUniqueLastMatchIsRodeOut() {
        assertEquals(Kind.RODE_OUT, m(25.0, 20.0, 30.0, 25.0).kind());
        assertEquals(-1, m(25.0, 20.0, 30.0, 25.0).index());
    }

    @Test
    void creditedZeroWithNoZeroGameIsRodeOutMissedNotUnmatched() {
        assertEquals(Kind.RODE_OUT_MISSED, m(0.0, 20.0, 30.0).kind());
        assertEquals(Kind.RODE_OUT, m(0.0, 20.0, 0.0).kind(), "a 0-point last game is an ordinary rode-out");
        assertEquals(Kind.LOCK, m(0.0, 0.0, 20.0).kind(), "a 0-point earlier game is an ordinary lock");
    }

    @Test
    void aNonZeroCreditThatMatchesNothingIsUnmatched() {
        assertEquals(Kind.UNMATCHED, m(25.0, 20.0, 30.0).kind());
    }

    @Test
    void tiesSplitOnWhetherTheLastGameIsAmongThem() {
        assertEquals(Kind.TIE_NON_LAST, m(20.0, 20.0, 20.0, 30.0).kind());
        assertEquals(Kind.TIE_WITH_LAST, m(20.0, 20.0, 30.0, 20.0).kind());
        assertEquals(Kind.TIE_WITH_LAST, m(20.0, 20.0, 20.0).kind());
    }

    @Test
    void oneGameAndNoGamesAreNotChoices() {
        assertEquals(Kind.SINGLE_GAME, m(20.0, 20.0).kind());
        assertEquals(Kind.SINGLE_GAME, m(99.0, 20.0).kind(), "single-game weeks are never classified by value");
        assertEquals(Kind.NO_GAMES, CreditedGames.match(20.0, List.of()).kind());
    }

    @Test
    void theToleranceBoundaryIsStrictAtHalfAPenny() {
        assertEquals(Kind.LOCK, m(30.004, 20.0, 30.0, 25.0).kind(), "0.004 off still matches");
        assertEquals(Kind.UNMATCHED, m(30.006, 20.0, 30.0, 25.0).kind(), "0.006 off does not");
        assertEquals(Kind.RODE_OUT_MISSED, m(0.004, 20.0, 30.0).kind(), "a credit within tolerance of 0 is a zero credit");
        assertEquals(Kind.UNMATCHED, m(0.006, 20.0, 30.0).kind());
    }

    @Test
    void theListIsTakenAsGivenAndItsLastIndexIsTheLastGame() {
        // Same scores, reversed: the lock and the rode-out swap, because "last" is the caller's last element.
        assertEquals(Kind.LOCK, m(30.0, 30.0, 20.0).kind());
        assertEquals(Kind.RODE_OUT, m(30.0, 20.0, 30.0).kind());
    }

    @Test
    void theClassesPartitionTheMultiGameTotalOverAMixedFixture() {
        record Case(double credited, List<Double> scores) {}
        List<Case> cases = List.of(
                new Case(30.0, List.of(20.0, 30.0, 25.0)),      // LOCK
                new Case(25.0, List.of(20.0, 30.0, 25.0)),      // RODE_OUT
                new Case(0.0, List.of(20.0, 30.0)),             // RODE_OUT_MISSED
                new Case(20.0, List.of(20.0, 20.0, 30.0)),      // TIE_NON_LAST
                new Case(20.0, List.of(20.0, 30.0, 20.0)),      // TIE_WITH_LAST
                new Case(44.0, List.of(20.0, 30.0)),            // UNMATCHED
                new Case(20.0, List.of(20.0)),                  // SINGLE_GAME
                new Case(20.0, List.of()));                     // NO_GAMES
        int[] counts = new int[Kind.values().length];
        int multi = 0;
        for (Case c : cases) {
            Kind k = CreditedGames.match(c.credited(), c.scores()).kind();
            counts[k.ordinal()]++;
            if (c.scores().size() >= 2) multi++;
            assertEquals(c.scores().size() >= 2, k.multiGame());
        }
        int multiKinds = 0;
        for (Kind k : Kind.values()) {
            assertEquals(1, counts[k.ordinal()], k + " should appear exactly once in the fixture");
            if (k.multiGame()) multiKinds += counts[k.ordinal()];
        }
        assertEquals(multi, multiKinds, "lock + rodeOut + rodeOutMissed + tieNonLast + tieWithLast + unmatched == multi");
        assertEquals(4, Arrays.stream(Kind.values()).filter(Kind::matchedAGame).count());
    }
}
