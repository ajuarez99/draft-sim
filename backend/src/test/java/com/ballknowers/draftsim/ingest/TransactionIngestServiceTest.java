package com.ballknowers.draftsim.ingest;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Mapping Sleeper's transaction types to this app's checked enum
 * (specs/004-ffwrapped-feature-parity US6). Kept in the ingest package beside
 * the class that owns the rule rather than widening its visibility for a test.
 */
class TransactionIngestServiceTest {

    @Test
    void sleeperTypesMapToTheCheckedEnum() {
        assertEquals("WAIVER", TransactionIngestService.normaliseType("waiver"));
        assertEquals("FREE_AGENT", TransactionIngestService.normaliseType("free_agent"));
        assertEquals("TRADE", TransactionIngestService.normaliseType("trade"));
        assertEquals("COMMISSIONER", TransactionIngestService.normaliseType("commissioner"));
        assertEquals("WAIVER", TransactionIngestService.normaliseType("  WAIVER  "));
    }

    /**
     * An unrecognised type is dropped, not coerced. A new Sleeper transaction
     * kind is not a waiver just because waiver is the commonest value, and
     * league_transaction has a check constraint that would reject it anyway --
     * better to find out here than as a failed insert mid-ingest.
     */
    @Test
    void anUnknownTypeIsDroppedRatherThanGuessedAt() {
        assertNull(TransactionIngestService.normaliseType("something_new"));
        assertNull(TransactionIngestService.normaliseType(null));
        assertNull(TransactionIngestService.normaliseType(""));
    }
}
