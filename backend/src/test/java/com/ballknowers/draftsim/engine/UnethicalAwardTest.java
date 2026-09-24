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
        return new LeagueConductRepository.Entry(id, leagueId, playerId, reason, appliesFromWeek, null, Instant.now());
    }

    private static int totalWeeks(Map<Integer, List<SeasonSuperlativesService.ConductQualifyingWeeks>> byRoster,
                                  int rosterId) {
        return byRoster.getOrDefault(rosterId, List.of()).stream()
                .mapToInt(r -> r.weeks().size())
                .sum();
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
}
