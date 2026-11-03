package com.ballknowers.draftsim.historical;

import com.ballknowers.draftsim.historical.KaggleHistoricalSource.BoxRow;
import com.ballknowers.draftsim.historical.KaggleIdentity.SeasonFileRow;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/** Spec 053 T010/T011 helpers that need no database: tracked stats, played rows, points, COPY quoting. */
class KaggleHistoricalSourceTest {

    private static SeasonFileRow row(int id, Double reb, Double ast, Double stl, Double blk, Double to, Double tpm) {
        return new SeasonFileRow(id, 1980, "P" + id, "p" + id, "PG", 25, "BOS", 50, 100.0, reb, ast, stl, blk, to, tpm,
                null, null, null, null);
    }

    @Test
    void aStatIsTrackedOnlyWhenMoreThanHalfTheSeasonRowsHaveIt() {
        List<SeasonFileRow> rows = new ArrayList<>();
        for (int i = 0; i < 10; i++) {
            rows.add(row(i, 5.0, 3.0,
                    i < 5 ? 1.0 : null,   // exactly half: not tracked
                    i < 6 ? 1.0 : null,   // 6 of 10: tracked
                    null,
                    2.0));                // all: tracked
        }
        Set<String> tracked = KaggleHistoricalSource.trackedStats(rows);
        assertTrue(tracked.containsAll(Set.of("pts", "reb", "ast", "blk", "tpm")), tracked.toString());
        assertFalse(tracked.contains("stl"));
        assertFalse(tracked.contains("to"));
    }

    @Test
    void aPlayedRowHasMinutesOrPointsAndAnEmptyBoxRowIsNotPlayed() {
        assertTrue(box(30, 0).played());
        assertTrue(box(0, 2).played());
        assertTrue(box(null, 2).played());
        assertFalse(box(0, 0).played(), "minutes and points both 0 is a DNP (F11)");
        assertFalse(box(null, null).played());
    }

    @Test
    void countingPointsUseOnlyCountedKeysAndTreatANullStatAsZero() {
        BoxRow r = new BoxRow("g", "OCT 22, 1975", "BOS", "X", 30, 20, 10, 5, 2, 1, 3, 2, 8, 15, 4, 6);
        Map<String, Double> all = Map.of("pts", 0.5, "reb", 1.0, "ast", 1.0, "stl", 2.0, "blk", 2.0, "to", -1.0,
                "tpm", 0.5);
        // 20*.5 + 10 + 5 + 2*2 + 1*2 - 3 + 2*.5
        assertEquals(10 + 10 + 5 + 4 + 2 - 3 + 1, KaggleHistoricalSource.countingPoints(r, all), 1e-9);
        assertEquals(10 + 10 + 5, KaggleHistoricalSource.countingPoints(r, Map.of("pts", 0.5, "reb", 1.0, "ast", 1.0)),
                1e-9, "an uncounted stat contributes nothing");
        BoxRow early = new BoxRow("g", "OCT 22, 1975", "BOS", "X", 30, 20, 10, 5, null, null, null, null, null, null,
                null, null);
        assertEquals(10 + 10 + 5, KaggleHistoricalSource.countingPoints(early, all), 1e-9);
    }

    @Test
    void copyFieldsAreQuotedAndANullIsAnUnquotedEmptyCell() {
        StringBuilder sb = new StringBuilder();
        KaggleHistoricalSource.csvField(sb, "Ted \"Hound Dog\" McClain").append(',');
        KaggleHistoricalSource.csvField(sb, null).append(',');
        KaggleHistoricalSource.csvField(sb, "NOV 19, 1946");
        assertEquals("\"Ted \"\"Hound Dog\"\" McClain\",,\"NOV 19, 1946\"", sb.toString());
    }

    @Test
    void seasonLabelsAreStartDashTwoDigitEndYear() {
        assertEquals("1970-71", KaggleHistoricalSource.label(1970));
        assertEquals("1999-00", KaggleHistoricalSource.label(1999));
        assertEquals("2008-09", KaggleHistoricalSource.label(2008));
        assertEquals("2025-26", KaggleHistoricalSource.label(2025));
    }

    private static BoxRow box(Integer min, Integer pts) {
        return new BoxRow("g", "OCT 22, 1975", "BOS", "X", min, pts, null, null, null, null, null, null, null, null,
                null, null);
    }
}
