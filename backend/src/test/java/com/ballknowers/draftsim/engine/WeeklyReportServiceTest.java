package com.ballknowers.draftsim.engine;

import org.junit.jupiter.api.Test;

import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The pure half of the weekly report: reading Sleeper's starters list, which is
 * what decides whether an award can be computed at all
 * (specs/004-ffwrapped-feature-parity, US5). The repository walk is left to an
 * integration test, per the split {@code LeagueAnalysisServiceTest} describes.
 */
class WeeklyReportServiceTest {

    /**
     * US5.4. A week ingested before V18 has no starters list, and the empty set
     * is what makes the affected award omit itself with a reason instead of
     * guessing. "Not stored" and "nobody started" must not look alike here.
     */
    @Test
    void anAbsentStartersListReadsAsEmptyRatherThanThrowing() {
        assertTrue(WeeklyReportService.startersOf(null).isEmpty());
        assertTrue(WeeklyReportService.startersOf("").isEmpty());
        assertTrue(WeeklyReportService.startersOf("   ").isEmpty());
    }

    /** Malformed JSON is treated as absent, not as a crash on a page load. */
    @Test
    void malformedStartersJsonIsTreatedAsAbsent() {
        assertTrue(WeeklyReportService.startersOf("{not json").isEmpty());
    }

    @Test
    void aStartersListIsReadInOrder() {
        Set<String> s = WeeklyReportService.startersOf("[\"4034\",\"6794\",\"5849\"]");
        assertEquals(3, s.size());
        assertEquals(java.util.List.of("4034", "6794", "5849"), java.util.List.copyOf(s));
    }

    /**
     * Sleeper writes "0" for an empty starting slot -- a seat nobody was put
     * in. Counting it as a started player would put a phantom on the board and,
     * worse, let it win an award.
     */
    @Test
    void emptySlotsAreNotPlayers() {
        Set<String> s = WeeklyReportService.startersOf("[\"4034\",\"0\",\"5849\",\"0\"]");
        assertEquals(Set.of("4034", "5849"), s);
    }

    /** Numeric ids (Sleeper sends strings, but a fixture may not) still read. */
    @Test
    void numericIdsAreAccepted() {
        assertEquals(Set.of("4034", "5849"), WeeklyReportService.startersOf("[4034,5849]"));
    }

    /** The real NBA payload's nine starters, straight from the stored fixture. */
    @Test
    void theRealBasketballStartersListReadsAsNinePlayers() {
        String json = "[\"2084\",\"2285\",\"2144\",\"2836\",\"1380\",\"2480\",\"2733\",\"2455\",\"2259\"]";
        assertEquals(9, WeeklyReportService.startersOf(json).size(),
                "basketball starts nine, and this is the shape Sleeper actually sent");
    }
}
