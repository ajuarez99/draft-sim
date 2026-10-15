package com.ballknowers.draftsim.ingest.espn;

import com.ballknowers.draftsim.domain.Sport;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class EspnScoringMapTest {

    private static Map<String, Object> item(int stat, double pts) {
        return Map.of("statId", stat, "points", pts);
    }

    @Test
    void noEntryIsConfirmedSoEveryNonZeroItemIsUntranslatedAndTheMapIsEmpty() {
        EspnScoringMap.Result r = EspnScoringMap.translate(Sport.NBA, List.of(item(0, 1.0), item(6, 1.2), item(99, 3.0)));
        assertEquals(List.of("stat:0", "stat:6", "stat:99"), r.untranslated());
        assertTrue(r.scoring().isEmpty());
    }

    @Test
    void zeroPointItemsAreIgnored() {
        EspnScoringMap.Result r = EspnScoringMap.translate(Sport.NFL, List.of(item(3, 0.0)));
        assertTrue(r.untranslated().isEmpty());
        assertTrue(r.scoring().isEmpty());
    }

    @Test
    void oneUnmappedItemEmptiesTheWholeMapEvenWhenTheOthersWouldMap() {
        EspnScoringMap.Result r = EspnScoringMap.translateTrustingTable(Sport.NBA, List.of(item(0, 1.0), item(99, 2.0)));
        assertEquals(List.of("stat:99"), r.untranslated());
        assertTrue(r.scoring().isEmpty(), "a partial rule set would silently under-score");
    }

    @Test
    void onceEveryItemMapsTheTranslationIsWritten() {
        EspnScoringMap.Result r = EspnScoringMap.translateTrustingTable(Sport.NBA, List.of(item(0, 1.0), item(6, 1.2)));
        assertTrue(r.untranslated().isEmpty());
        assertEquals(Map.of("pts", 1.0, "reb", 1.2), r.scoring());
    }

    @Test
    void aMalformedItemIsAShapeError() {
        assertThrows(EspnShapeChanged.class, () -> EspnScoringMap.translate(Sport.NBA, List.of(Map.of("statId", "x"))));
    }
}
