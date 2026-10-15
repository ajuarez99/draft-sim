package com.ballknowers.draftsim.ingest.espn;

import com.ballknowers.draftsim.domain.Sport;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class EspnProTeamsTest {

    // Measured 2026-10-14 from ?view=proTeamSchedules_wl (research R4). NFL has no id 31 or 32.
    private static final List<Integer> NFL_IDS = List.of(1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12, 13, 14, 15, 16, 17, 18, 19, 20,
            21, 22, 23, 24, 25, 26, 27, 28, 29, 30, 33, 34);

    // The distinct non-null player.team values in the scratch DB on the same date.
    private static final Set<String> APP_NFL = Set.of("ARI", "ATL", "BAL", "BUF", "CAR", "CHI", "CIN", "CLE", "DAL", "DEN", "DET",
            "GB", "HOU", "IND", "JAX", "KC", "LAC", "LAR", "LV", "MIA", "MIN", "NE", "NO", "NYG", "NYJ", "PHI", "PIT", "SEA", "SF",
            "TB", "TEN", "WAS");
    private static final Set<String> APP_NBA = Set.of("ATL", "BKN", "BOS", "CHA", "CHI", "CLE", "DAL", "DEN", "DET", "GSW", "HOU",
            "IND", "LAC", "LAL", "MEM", "MIA", "MIL", "MIN", "NOP", "NYK", "OKC", "ORL", "PHI", "PHX", "POR", "SAC", "SAS", "TOR",
            "UTA", "WAS");

    @Test
    void everyMeasuredNflIdMapsToAnAbbreviationTheAppUses() {
        for (int id : NFL_IDS) {
            String a = EspnProTeams.abbreviation(Sport.NFL, id);
            assertNotNull(a, "NFL id " + id);
            assertTrue(APP_NFL.contains(a), a);
        }
        assertEquals(NFL_IDS.size(), APP_NFL.size(), "32 teams");
        assertEquals("WAS", EspnProTeams.abbreviation(Sport.NFL, 28));   // ESPN spells it WSH
    }

    @Test
    void everyMeasuredNbaIdMapsToAnAbbreviationTheAppUses() {
        for (int id = 1; id <= 30; id++) {
            String a = EspnProTeams.abbreviation(Sport.NBA, id);
            assertNotNull(a, "NBA id " + id);
            assertTrue(APP_NBA.contains(a), a);
        }
        assertEquals("BKN", EspnProTeams.abbreviation(Sport.NBA, 17));
        assertEquals("NYK", EspnProTeams.abbreviation(Sport.NBA, 18));
        assertEquals("GSW", EspnProTeams.abbreviation(Sport.NBA, 9));
        assertEquals("NOP", EspnProTeams.abbreviation(Sport.NBA, 3));
        assertEquals(30, EspnProTeams.table(Sport.NBA).size());
    }

    @Test
    void freeAgentAndUnknownAreNull() {
        assertNull(EspnProTeams.abbreviation(Sport.NFL, 0));
        assertNull(EspnProTeams.abbreviation(Sport.NBA, 0));
        assertNull(EspnProTeams.abbreviation(Sport.NFL, 31));
        assertNull(EspnProTeams.abbreviation(Sport.NBA, null));
    }
}
