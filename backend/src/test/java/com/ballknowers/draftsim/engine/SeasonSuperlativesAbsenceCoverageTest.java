package com.ballknowers.draftsim.engine;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/**
 * JOEL_EMBIID's unclassified-week coverage (specs/008-season-superlatives,
 * coordinator follow-up 2026-09-23): a caveat backed by a persisted
 * {@code UNCLASSIFIED} {@code player_absence} row (V23), never a standing
 * note with no number behind it. Pure and in-memory -- no Postgres.
 */
class SeasonSuperlativesAbsenceCoverageTest {

    @Test
    void zeroUnclassifiedWeeksProduceNoCoverageAtAll() {
        SeasonSuperlativesService.Coverage coverage = SeasonSuperlativesService.unclassifiedCoverage(
                List.of(4), Map.of(), 14);

        assertNull(coverage, "a count of 0 is not a coverage gap");
    }

    @Test
    void anUnclassifiedWeekForAHolderProducesACoverageReasonNamingTheCount() {
        SeasonSuperlativesService.Coverage coverage = SeasonSuperlativesService.unclassifiedCoverage(
                List.of(4), Map.of(4, Set.of(6, 9)), 14);

        assertNotNull(coverage);
        assertEquals(1, coverage.reasons().size());
        assertEquals("roster 4: 2 weeks couldn't be classified as a bye or a missed game", coverage.reasons().get(0));
    }

    @Test
    void aSingleUnclassifiedWeekUsesSingularWording() {
        SeasonSuperlativesService.Coverage coverage = SeasonSuperlativesService.unclassifiedCoverage(
                List.of(4), Map.of(4, Set.of(6)), 14);

        assertEquals("roster 4: 1 week couldn't be classified as a bye or a missed game", coverage.reasons().get(0));
    }

    @Test
    void onlyHoldersWithAnUnclassifiedWeekGetAReason() {
        // Two tied holders (4 and 7); only 4 has an unclassified week.
        SeasonSuperlativesService.Coverage coverage = SeasonSuperlativesService.unclassifiedCoverage(
                List.of(4, 7), Map.of(4, Set.of(3)), 14);

        assertEquals(1, coverage.reasons().size());
        assertTrue(coverage.reasons().get(0).startsWith("roster 4:"));
    }

    @Test
    void unclassifiedWeeksForANonHolderRosterAreIgnored() {
        // Roster 9 has unclassified weeks but isn't a holder of this award.
        SeasonSuperlativesService.Coverage coverage = SeasonSuperlativesService.unclassifiedCoverage(
                List.of(4), Map.of(9, Set.of(3, 4, 5)), 14);

        assertNull(coverage);
    }
}
