package com.ballknowers.draftsim.engine;

import org.junit.jupiter.api.Test;

import java.util.Comparator;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * claude/plan-review-power-rankings-ballots.md finding 6, and
 * claude/power-rankings-ballots.md AC10: a tie test coupled to sort
 * direction (the original {@code rounded < prevRounded}) makes an ascending
 * ranker put every item at rank 1 -- claude/lessons.md #1's shape verbatim,
 * structurally perfect and pointed backwards. These assert ORDERING
 * DIRECTION against known input, not merely that both directions call the
 * same method (which would pass even with the bug).
 */
class RankerTest {

    private record Item(String name, double score) {}

    @Test
    void descendingRanksHighestScoreFirst() {
        var ranked = Ranker.rank(
                List.of(new Item("low", 1.0), new Item("high", 9.0), new Item("mid", 5.0)),
                Comparator.comparingDouble(Item::score).reversed(), Item::score);

        assertEquals("high", ranked.get(0).item().name());
        assertEquals(1, ranked.get(0).rank());
        assertEquals("mid", ranked.get(1).item().name());
        assertEquals(2, ranked.get(1).rank());
        assertEquals("low", ranked.get(2).item().name());
        assertEquals(3, ranked.get(2).rank());
    }

    /**
     * The exact case that breaks under a direction-coupled tie test: lower
     * score is better (an average ballot rank), sorted ascending. If the tie
     * test were still {@code rounded < prevRounded} -- correct only for
     * descending -- rounded would increase every step and every item would
     * come out rank 1.
     */
    @Test
    void ascendingRanksLowestScoreFirst_theBugThisClassExistsToPrevent() {
        var ranked = Ranker.rank(
                List.of(new Item("worst", 9.0), new Item("best", 1.0), new Item("mid", 5.0)),
                Comparator.comparingDouble(Item::score), Item::score);

        assertEquals("best", ranked.get(0).item().name());
        assertEquals(1, ranked.get(0).rank());
        assertEquals("mid", ranked.get(1).item().name());
        assertEquals(2, ranked.get(1).rank());
        assertEquals("worst", ranked.get(2).item().name());
        assertEquals(3, ranked.get(2).rank(), "must NOT be rank 1 -- every item landing at rank 1 is exactly the bug");
    }

    @Test
    void tiedScoresShareTheLowerRankAndTheNextRankIsSkipped_bothDirections() {
        var descending = Ranker.rank(
                List.of(new Item("a", 5.0), new Item("b", 5.0), new Item("c", 1.0)),
                Comparator.comparingDouble(Item::score).reversed(), Item::score);
        assertEquals(1, rankOf(descending, "a"));
        assertEquals(1, rankOf(descending, "b"));
        assertEquals(3, rankOf(descending, "c"));

        var ascending = Ranker.rank(
                List.of(new Item("a", 5.0), new Item("b", 5.0), new Item("c", 9.0)),
                Comparator.comparingDouble(Item::score), Item::score);
        assertEquals(1, rankOf(ascending, "a"));
        assertEquals(1, rankOf(ascending, "b"));
        assertEquals(3, rankOf(ascending, "c"));
    }

    private static int rankOf(List<Ranker.Ranked<Item>> ranked, String name) {
        return ranked.stream().filter(r -> r.item().name().equals(name)).findFirst().orElseThrow().rank();
    }
}
