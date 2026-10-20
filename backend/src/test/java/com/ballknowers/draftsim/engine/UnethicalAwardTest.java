package com.ballknowers.draftsim.engine;

import com.ballknowers.draftsim.store.LeagueConductRepository;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/**
 * specs/008-season-superlatives T057: ordering and rule tests for UNETHICAL,
 * driven straight against {@link SeasonSuperlativesService#computeUnethical}
 * -- pure, no Postgres, no Spring context, the same shape as {@code
 * AbsenceCostTest} and {@code WaiverPickupAttributionTest}.
 */
class UnethicalAwardTest {

    private static LeagueConductRepository.Entry conductEntry(long id, long leagueId, String playerId,
                                                               String reason, int appliesFromWeek) {
        return new LeagueConductRepository.Entry(id, leagueId, playerId, reason, appliesFromWeek, null, null, Instant.now());
    }

    /** Spec 040: points at the production total (distinct player-weeks) rather than re-implementing the sum. */
    private static int totalWeeks(Map<Integer, List<SeasonSuperlativesService.ConductQualifyingWeeks>> byRoster,
                                  int rosterId) {
        return SeasonSuperlativesService.unethicalTotals(byRoster).getOrDefault(rosterId, 0);
    }

    private static LeagueConductRepository.Entry ranged(long id, String playerId, int from, Integer through) {
        return new LeagueConductRepository.Entry(id, 100L, playerId, "reason", from, through, null, Instant.now());
    }

    /** (a): the team with more qualifying player-weeks outranks one with fewer. */
    @Test
    void moreQualifyingPlayerWeeksIsTheHolder() {
        Map<Integer, Map<String, Set<Integer>>> rostered = Map.of(
                4, Map.of("p1", Set.of(1, 2, 3)),
                9, Map.of("p2", Set.of(1)));
        Map<Integer, Set<String>> suspended = Map.of(
                1, Set.of("p1", "p2"),
                2, Set.of("p1"),
                3, Set.of("p1"));

        Map<Integer, List<SeasonSuperlativesService.ConductQualifyingWeeks>> byRoster =
                SeasonSuperlativesService.computeUnethical(rostered, suspended, List.of());

        assertEquals(3, totalWeeks(byRoster, 4));
        assertEquals(1, totalWeeks(byRoster, 9));
        assertTrue(totalWeeks(byRoster, 4) > totalWeeks(byRoster, 9), "roster 4 has strictly more qualifying weeks");
    }

    /** (b): a suspended player rostered only in weeks he wasn't tagged doesn't count (FR-018). */
    @Test
    void suspendedPlayerRosteredOutsideTaggedWeeksDoesNotCount() {
        Map<Integer, Map<String, Set<Integer>>> rostered = Map.of(
                4, Map.of("p1", Set.of(5, 6, 7)));
        // p1 is only ever captured suspended in week 2, a week he wasn't on this roster.
        Map<Integer, Set<String>> suspended = Map.of(2, Set.of("p1"));

        Map<Integer, List<SeasonSuperlativesService.ConductQualifyingWeeks>> byRoster =
                SeasonSuperlativesService.computeUnethical(rostered, suspended, List.of());

        assertTrue(byRoster.isEmpty(), "no overlap between rostered weeks and tagged weeks -- nothing qualifies");
    }

    /** (c): a commissioner entry from week 6 counts only for weeks he was rostered at or after week 6. */
    @Test
    void commissionerEntryCountsOnlyFromItsAppliesFromWeek() {
        Map<Integer, Map<String, Set<Integer>>> rostered = Map.of(
                4, Map.of("p1", Set.of(4, 5, 6, 7)));
        List<LeagueConductRepository.Entry> entries = List.of(conductEntry(1, 100L, "p1", "bad conduct", 6));

        Map<Integer, List<SeasonSuperlativesService.ConductQualifyingWeeks>> byRoster =
                SeasonSuperlativesService.computeUnethical(rostered, Map.of(), entries);

        List<SeasonSuperlativesService.ConductQualifyingWeeks> rows = byRoster.get(4);
        assertNotNull(rows);
        assertEquals(1, rows.size());
        assertEquals(List.of(6, 7), rows.get(0).weeks(), "weeks 4 and 5 predate the entry's appliesFromWeek");
    }

    /** (d): a removed entry (not passed in) counts for nobody. */
    @Test
    void removedEntryCountsForNobody() {
        Map<Integer, Map<String, Set<Integer>>> rostered = Map.of(
                4, Map.of("p1", Set.of(4, 5, 6)));

        // The entry was removed, so the caller simply doesn't pass it -- LeagueConductRepository.delete's job.
        Map<Integer, List<SeasonSuperlativesService.ConductQualifyingWeeks>> byRoster =
                SeasonSuperlativesService.computeUnethical(rostered, Map.of(), List.of());

        assertTrue(byRoster.isEmpty());
    }

    /** (e): every detail row carries a source. */
    @Test
    void everyRowCarriesASource() {
        Map<Integer, Map<String, Set<Integer>>> rostered = Map.of(
                4, Map.of("p1", Set.of(1), "p2", Set.of(2)));
        Map<Integer, Set<String>> suspended = Map.of(1, Set.of("p1"));
        List<LeagueConductRepository.Entry> entries = List.of(conductEntry(1, 100L, "p2", "reason", 1));

        Map<Integer, List<SeasonSuperlativesService.ConductQualifyingWeeks>> byRoster =
                SeasonSuperlativesService.computeUnethical(rostered, suspended, entries);

        List<SeasonSuperlativesService.ConductQualifyingWeeks> rows = byRoster.get(4);
        assertEquals(2, rows.size());
        assertTrue(rows.stream().allMatch(r -> r.source() != null && !r.source().isBlank()));
        assertTrue(rows.stream().anyMatch(r -> r.source().equals("SUSPENDED")));
        assertTrue(rows.stream().anyMatch(r -> r.source().equals("COMMISSIONER")));
    }

    /** (f): nothing qualifies -- empty, and the empty reason names both sources (contract). */
    @Test
    void nothingQualifiesIsEmpty() {
        Map<Integer, List<SeasonSuperlativesService.ConductQualifyingWeeks>> byRoster =
                SeasonSuperlativesService.computeUnethical(Map.of(), Map.of(), List.of());

        assertTrue(byRoster.isEmpty());
        // The service-level emptyReason string ("no suspension has been captured yet, and the
        // commissioner's list is empty") is asserted where it's produced, in
        // SeasonSuperlativesService.unethicalSuperlative -- this test only pins that the pure
        // rule itself reports "nothing" rather than inventing a qualifying row.
    }

    /** A player traded mid-season: weeks on each roster are attributed only to that roster. */
    @Test
    void tradedPlayerAttributesEachRostersOwnWeeksOnly() {
        Map<Integer, Map<String, Set<Integer>>> rostered = Map.of(
                4, Map.of("p1", Set.of(1, 2)),
                9, Map.of("p1", Set.of(3, 4)));
        Map<Integer, Set<String>> suspended = Map.of(1, Set.of("p1"), 2, Set.of("p1"), 3, Set.of("p1"), 4, Set.of("p1"));

        Map<Integer, List<SeasonSuperlativesService.ConductQualifyingWeeks>> byRoster =
                SeasonSuperlativesService.computeUnethical(rostered, suspended, List.of());

        assertEquals(List.of(1, 2), byRoster.get(4).get(0).weeks());
        assertEquals(List.of(3, 4), byRoster.get(9).get(0).weeks());
    }

    // ---- spec 040 (US2): through-week and distinct player-weeks --------------------------------

    /** (a): an entry from week 5 through week 7 only qualifies rostered weeks 5-7. */
    @Test
    void entryWithAThroughWeekQualifiesOnlyItsRange() {
        Map<Integer, Map<String, Set<Integer>>> rostered = Map.of(4, Map.of("p1", Set.of(3, 4, 5, 6, 7, 8, 9)));

        var byRoster = SeasonSuperlativesService.computeUnethical(rostered, Map.of(), List.of(ranged(1, "p1", 5, 7)));

        assertEquals(List.of(5, 6, 7), byRoster.get(4).get(0).weeks());
        assertEquals(3, totalWeeks(byRoster, 4));
    }

    /** (b): a null through-week is the rest of the window -- the behaviour every pre-V38 row has. */
    @Test
    void nullThroughWeekMeansRestOfWindow() {
        Map<Integer, Map<String, Set<Integer>>> rostered = Map.of(4, Map.of("p1", Set.of(3, 4, 5, 6, 7, 8, 9)));

        var byRoster = SeasonSuperlativesService.computeUnethical(rostered, Map.of(), List.of(ranged(1, "p1", 5, null)));

        assertEquals(List.of(5, 6, 7, 8, 9), byRoster.get(4).get(0).weeks());
    }

    /** A range that ends before he was ever rostered qualifies nothing. */
    @Test
    void rangeOutsideRosteredWeeksQualifiesNothing() {
        Map<Integer, Map<String, Set<Integer>>> rostered = Map.of(4, Map.of("p1", Set.of(8, 9)));

        var byRoster = SeasonSuperlativesService.computeUnethical(rostered, Map.of(), List.of(ranged(1, "p1", 2, 3)));

        assertTrue(byRoster.isEmpty());
    }

    /** (c): SUSPENDED and COMMISSIONER over the same weeks count each (player, week) once (research R7). */
    @Test
    void samePlayerWeekUnderBothSourcesCountsOnce() {
        Map<Integer, Map<String, Set<Integer>>> rostered = Map.of(4, Map.of("p1", Set.of(2, 3, 4)));
        Map<Integer, Set<String>> suspended = Map.of(2, Set.of("p1"), 3, Set.of("p1"));

        var byRoster = SeasonSuperlativesService.computeUnethical(rostered, suspended, List.of(ranged(1, "p1", 2, 3)));

        assertEquals(2, byRoster.get(4).size(), "both source rows are still listed");
        assertEquals(2, totalWeeks(byRoster, 4), "but weeks 2 and 3 are each one player-week, not two");
    }

    /** Two sources covering different weeks of one player union to their distinct weeks. */
    @Test
    void sourcesCoveringDifferentWeeksUnion() {
        Map<Integer, Map<String, Set<Integer>>> rostered = Map.of(4, Map.of("p1", Set.of(1, 2, 3, 4)));
        Map<Integer, Set<String>> suspended = Map.of(1, Set.of("p1"), 2, Set.of("p1"));

        var byRoster = SeasonSuperlativesService.computeUnethical(rostered, suspended, List.of(ranged(1, "p1", 2, 4)));

        assertEquals(4, totalWeeks(byRoster, 4));
    }

    /**
     * (d) preference ordering (lessons class 1): a roster with 3 distinct flagged player-weeks outranks one with
     * 2, even though the latter has 4 source rows' worth of week-entries (two players, each under both sources).
     */
    @Test
    void threeDistinctPlayerWeeksOutrankTwoEvenWithMoreSourceRows() {
        Map<Integer, Map<String, Set<Integer>>> rostered = Map.of(
                4, Map.of("a", Set.of(1, 2, 3)),
                9, Map.of("b", Set.of(1), "c", Set.of(1)));
        Map<Integer, Set<String>> suspended = Map.of(1, Set.of("a", "b", "c"), 2, Set.of("a"), 3, Set.of("a"));
        List<LeagueConductRepository.Entry> entries = List.of(ranged(1, "b", 1, null), ranged(2, "c", 1, null));

        var byRoster = SeasonSuperlativesService.computeUnethical(rostered, suspended, entries);

        int rowWeeks9 = byRoster.get(9).stream().mapToInt(r -> r.weeks().size()).sum();
        assertEquals(4, rowWeeks9, "roster 9 has 4 week-entries across its source rows");
        assertEquals(3, totalWeeks(byRoster, 4));
        assertEquals(2, totalWeeks(byRoster, 9));
        assertTrue(totalWeeks(byRoster, 4) > totalWeeks(byRoster, 9));
    }
}
