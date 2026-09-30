package com.ballknowers.draftsim.engine;

import com.ballknowers.draftsim.engine.MostAddedPlayers.Ranked;
import com.ballknowers.draftsim.engine.WaiverPickupAttribution.CompletedAdd;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Predicate;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Spec 010 T022: {@link MostAddedPlayers#top}, the full-standings sibling of {@link MostAddedPlayers#rank}.
 * Pure and in-memory. One test per filter, because the point of the shared helper is that both methods
 * agree on which adds count.
 */
class MostAddedPlayersTopTest {

    private static final Predicate<String> ALL_ELIGIBLE = id -> true;

    private static CompletedAdd add(int week, String type, String playerId, int rosterId) {
        return new CompletedAdd(week, null, type, playerId, rosterId, null);
    }

    /** {@code n} WAIVER adds of one player in week 1, by rosters 1..n. */
    private static List<CompletedAdd> adds(String playerId, int n) {
        List<CompletedAdd> out = new ArrayList<>();
        for (int i = 1; i <= n; i++) out.add(add(1, "WAIVER", playerId, i));
        return out;
    }

    private static List<String> ids(List<Ranked> r) {
        return r.stream().map(Ranked::playerId).toList();
    }

    @Test
    void onlyWaiverAndFreeAgentAddsCount() {
        List<CompletedAdd> in = new ArrayList<>(List.of(
                add(1, "WAIVER", "A", 1), add(2, "FREE_AGENT", "A", 2),
                add(1, "TRADE", "B", 1), add(2, "COMMISSIONER", "B", 2), add(3, "WAIVER", "B", 3)));
        List<Ranked> top = MostAddedPlayers.top(in, 6, ALL_ELIGIBLE, 10);
        // B has 3 add-events but only 1 counts; it falls below the floor.
        assertEquals(List.of("A"), ids(top));
        assertEquals(2, top.get(0).adds());
    }

    @Test
    void theWeekWindowIsRespected() {
        List<CompletedAdd> in = List.of(
                add(1, "WAIVER", "A", 1), add(2, "WAIVER", "A", 2),
                add(3, "WAIVER", "B", 1), add(7, "WAIVER", "B", 2), add(0, "WAIVER", "B", 3));
        List<Ranked> top = MostAddedPlayers.top(in, 3, ALL_ELIGIBLE, 10);
        assertEquals(List.of("A"), ids(top), "B's week 7 and week 0 adds fall outside 1..3");
    }

    @Test
    void ineligiblePlayersAreExcluded() {
        List<CompletedAdd> in = new ArrayList<>(adds("DEF", 4));
        in.addAll(adds("P", 2));
        List<Ranked> top = MostAddedPlayers.top(in, 6, id -> !id.equals("DEF"), 10);
        assertEquals(List.of("P"), ids(top));
    }

    @Test
    void playersBelowTheNamingFloorAreExcluded() {
        List<CompletedAdd> in = new ArrayList<>(adds("ONE", 1));
        in.addAll(adds("TWO", MostAddedPlayers.MIN_ADDS_TO_NAME));
        assertEquals(List.of("TWO"), ids(MostAddedPlayers.top(in, 6, ALL_ELIGIBLE, 10)));
    }

    @Test
    void orderedByAddsHighToLowThenPlayerId() {
        List<CompletedAdd> in = new ArrayList<>();
        in.addAll(adds("b", 3));
        in.addAll(adds("a", 3));
        in.addAll(adds("z", 5));
        in.addAll(adds("m", 2));
        List<Ranked> top = MostAddedPlayers.top(in, 6, ALL_ELIGIBLE, 10);
        assertEquals(List.of("z", "a", "b", "m"), ids(top));
        assertEquals(List.of(5, 3, 3, 2), top.stream().map(Ranked::adds).toList());
    }

    @Test
    void theCutIncludesEveryoneTiedWithTheLastKept() {
        List<CompletedAdd> in = new ArrayList<>();
        // 8 distinct players with 5..2 adds, then players 9..12 all tied at 2, then one more at 2.
        String[] ids = {"p01", "p02", "p03", "p04", "p05", "p06", "p07", "p08"};
        int[] counts = {9, 8, 7, 6, 5, 4, 3, 3};
        for (int i = 0; i < ids.length; i++) in.addAll(adds(ids[i], counts[i]));
        for (String id : new String[]{"p09", "p10", "p11", "p12"}) in.addAll(adds(id, 2));
        in.addAll(adds("p13", 1)); // below the floor, never in

        List<Ranked> top = MostAddedPlayers.top(in, 6, ALL_ELIGIBLE, 10);
        // limit 10: 8 players clearly + p09, p10 fill the count; p11 and p12 tie p10 at 2 and come along.
        assertEquals(12, top.size());
        assertEquals("p12", top.get(11).playerId());
        assertFalse(ids(top).contains("p13"));
    }

    @Test
    void aCutInsideADistinctTierDoesNotOvershoot() {
        List<CompletedAdd> in = new ArrayList<>();
        in.addAll(adds("a", 5));
        in.addAll(adds("b", 4));
        in.addAll(adds("c", 3));
        in.addAll(adds("d", 2));
        assertEquals(List.of("a", "b"), ids(MostAddedPlayers.top(in, 6, ALL_ELIGIBLE, 2)));
    }

    @Test
    void theTiedForMostInTopEqualRanksPlayers() {
        List<CompletedAdd> in = new ArrayList<>();
        in.addAll(adds("x", 4));
        in.addAll(adds("y", 4));
        in.addAll(adds("z", 3));
        List<Ranked> top = MostAddedPlayers.top(in, 6, ALL_ELIGIBLE, 10);
        List<String> topTier = top.stream().filter(r -> r.adds() == top.get(0).adds()).map(Ranked::playerId).toList();
        assertEquals(ids(MostAddedPlayers.rank(in, 6, ALL_ELIGIBLE)), topTier);
    }

    @Test
    void countedAddsAreOrderedByWeekLikeRank() {
        List<CompletedAdd> in = List.of(add(3, "WAIVER", "A", 1), add(1, "WAIVER", "A", 2));
        Ranked r = MostAddedPlayers.top(in, 6, ALL_ELIGIBLE, 10).get(0);
        assertEquals(List.of(1, 3), r.counted().stream().map(CompletedAdd::week).toList());
        assertEquals(2, r.distinctTeams());
    }

    @Test
    void noLimitOrNothingCountedGivesAnEmptyList() {
        assertTrue(MostAddedPlayers.top(adds("A", 3), 6, ALL_ELIGIBLE, 0).isEmpty());
        assertTrue(MostAddedPlayers.top(List.of(), 6, ALL_ELIGIBLE, 10).isEmpty());
    }
}
