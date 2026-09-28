package com.ballknowers.draftsim.engine;

import com.ballknowers.draftsim.engine.MostAddedPlayers.Ranked;
import com.ballknowers.draftsim.engine.WaiverPickupAttribution.CompletedAdd;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.function.Predicate;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The Jabari Smith Jr. Award rule tests (specs/008-season-superlatives T074),
 * pure and in-memory -- no Postgres. Each test is one case from research R16
 * and the task list.
 */
class MostAddedPlayersTest {

    private static final Predicate<String> ALL_ELIGIBLE = id -> true;

    private static CompletedAdd add(int week, String createdAt, String type, String playerId, int rosterId) {
        return new CompletedAdd(week, createdAt == null ? null : Instant.parse(createdAt), type, playerId, rosterId, null);
    }

    // ---------------------------------------------------------------- ordering

    @Test
    void totalAddsBeatsDistinctTeams() {
        // A: 3 adds by one team. B: 2 adds by two different teams. A must win --
        // this is a count of adds, not distinct teams (spec clarification 16).
        List<CompletedAdd> adds = List.of(
                add(1, "2026-09-01T00:00:00Z", "WAIVER", "A", 1),
                add(2, "2026-09-08T00:00:00Z", "WAIVER", "A", 1),
                add(3, "2026-09-15T00:00:00Z", "WAIVER", "A", 1),
                add(1, "2026-09-01T00:00:00Z", "WAIVER", "B", 2),
                add(2, "2026-09-08T00:00:00Z", "WAIVER", "B", 3));

        List<Ranked> result = MostAddedPlayers.rank(adds, 6, ALL_ELIGIBLE);

        assertEquals(1, result.size());
        assertEquals("A", result.get(0).playerId());
        assertEquals(3, result.get(0).adds());
    }

    @Test
    void reAddsBySameTeamAllCount() {
        List<CompletedAdd> adds = List.of(
                add(3, "2026-09-15T00:00:00Z", "WAIVER", "C", 1),
                add(3, "2026-09-15T01:00:00Z", "FREE_AGENT", "C", 1),
                add(3, "2026-09-15T02:00:00Z", "WAIVER", "C", 1));

        List<Ranked> result = MostAddedPlayers.rank(adds, 6, ALL_ELIGIBLE);

        assertEquals(1, result.size());
        assertEquals(3, result.get(0).adds());
        assertEquals(1, result.get(0).distinctTeams());
    }

    @Test
    void onlyWaiverAndFreeAgentCount() {
        List<CompletedAdd> adds = List.of(
                add(1, "2026-09-01T00:00:00Z", "TRADE", "D", 1),
                add(2, "2026-09-08T00:00:00Z", "COMMISSIONER", "D", 1),
                add(1, "2026-09-01T00:00:00Z", "WAIVER", "E", 2));

        List<Ranked> result = MostAddedPlayers.rank(adds, 6, ALL_ELIGIBLE);

        assertEquals(1, result.size());
        assertEquals("E", result.get(0).playerId());
    }

    @Test
    void windowExcludesAddsOutsideOneThroughThroughWeek() {
        List<CompletedAdd> adds = List.of(
                add(0, "2026-09-01T00:00:00Z", "WAIVER", "F", 1),
                add(7, "2026-09-01T00:00:00Z", "WAIVER", "F", 1),
                add(1, "2026-09-01T00:00:00Z", "WAIVER", "F", 1));

        List<Ranked> result = MostAddedPlayers.rank(adds, 6, ALL_ELIGIBLE);

        assertEquals(1, result.size());
        assertEquals(1, result.get(0).adds(), "only the week-1 add is inside 1..6");
    }

    @Test
    void ineligiblePlayerIsNeverCountedEvenAtTheTop() {
        List<CompletedAdd> adds = List.of(
                add(1, "2026-09-01T00:00:00Z", "WAIVER", "JAX", 1),
                add(2, "2026-09-08T00:00:00Z", "WAIVER", "JAX", 2),
                add(3, "2026-09-15T00:00:00Z", "WAIVER", "JAX", 3),
                add(1, "2026-09-01T00:00:00Z", "WAIVER", "G", 4));

        Predicate<String> notJax = id -> !"JAX".equals(id);
        List<Ranked> result = MostAddedPlayers.rank(adds, 6, notJax);

        assertEquals(1, result.size());
        assertEquals("G", result.get(0).playerId());
    }

    @Test
    void tieAtTheTopReturnsBothPlayersEachWithItsOwnAddList() {
        List<CompletedAdd> adds = List.of(
                add(1, "2026-09-01T00:00:00Z", "WAIVER", "H", 1),
                add(2, "2026-09-08T00:00:00Z", "WAIVER", "H", 2),
                add(1, "2026-09-01T00:00:00Z", "WAIVER", "I", 3),
                add(2, "2026-09-08T00:00:00Z", "WAIVER", "I", 4));

        List<Ranked> result = MostAddedPlayers.rank(adds, 6, ALL_ELIGIBLE);

        assertEquals(2, result.size());
        assertEquals(List.of("H", "I"), result.stream().map(Ranked::playerId).sorted().toList());
        assertEquals(2, result.get(0).counted().size());
        assertEquals(2, result.get(1).counted().size());
    }

    @Test
    void distinctTeamsCountsDistinctRosterIdsAmongCountedAdds() {
        List<CompletedAdd> adds = List.of(
                add(1, "2026-09-01T00:00:00Z", "WAIVER", "J", 1),
                add(2, "2026-09-08T00:00:00Z", "WAIVER", "J", 2),
                add(3, "2026-09-15T00:00:00Z", "WAIVER", "J", 1));

        List<Ranked> result = MostAddedPlayers.rank(adds, 6, ALL_ELIGIBLE);

        assertEquals(1, result.size());
        assertEquals(3, result.get(0).adds());
        assertEquals(2, result.get(0).distinctTeams());
    }

    @Test
    void countedAddsAreOrderedByWeekThenCreatedAtWithNullFirst() {
        List<CompletedAdd> adds = List.of(
                add(2, "2026-09-08T00:00:00Z", "WAIVER", "K", 1),
                add(1, null, "WAIVER", "K", 1),
                add(1, "2026-09-01T00:00:00Z", "WAIVER", "K", 1));

        List<Ranked> result = MostAddedPlayers.rank(adds, 6, ALL_ELIGIBLE);

        assertEquals(1, result.size());
        List<CompletedAdd> counted = result.get(0).counted();
        assertEquals(3, counted.size());
        assertEquals(1, counted.get(0).week());
        assertNull(counted.get(0).createdAt(), "a null createdAt sorts first within its week");
        assertEquals(1, counted.get(1).week());
        assertEquals(2, counted.get(2).week());
    }

    @Test
    void noEligibleAddsGivesAnEmptyResult() {
        List<CompletedAdd> adds = List.of(
                add(1, "2026-09-01T00:00:00Z", "TRADE", "L", 1));

        List<Ranked> result = MostAddedPlayers.rank(adds, 6, ALL_ELIGIBLE);

        assertTrue(result.isEmpty());
    }
}
