package com.ballknowers.draftsim.historical;

import org.junit.jupiter.api.Test;

import java.io.StringReader;
import java.time.LocalDate;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/** Spec 053 T009: the parser and the decoding rules the Kaggle box-score file needs. */
class KaggleCsvTest {

    @Test
    void parsesTheDateFormTheFileUses() {
        assertEquals(LocalDate.of(1946, 11, 19), KaggleCsv.parseDate("NOV 19, 1946"));
        assertEquals(LocalDate.of(2001, 3, 5), KaggleCsv.parseDate("Mar 5, 2001"));
    }

    @Test
    void decodesTheSeasonFromTheGameIdNotTheCalendarYear() {
        assertEquals(1946, KaggleCsv.seasonStartOfGameId("0024600034"));
        assertEquals(1998, KaggleCsv.seasonStartOfGameId("0029800001"));
        assertEquals(2001, KaggleCsv.seasonStartOfGameId("0020100001"));
    }

    @Test
    void aGameIdThatLostItsLeadingZerosStillDecodes() {
        assertEquals(1998, KaggleCsv.seasonStartOfGameId("29800001"));
    }

    @Test
    void readsQuotedFieldsWithCommasAndEmptyFirstHeader() {
        String csv = ",Player_Name,PTS\r\n0,\"Smith, John\",12\r\n1,\"He said \"\"hi\"\"\",7\r\n";
        try (KaggleCsv r = new KaggleCsv(new StringReader(csv))) {
            assertEquals(List.of("", "Player_Name", "PTS"), r.header());
            assertEquals(2, r.indexOf("PTS"));
            assertArrayEquals(new String[]{"0", "Smith, John", "12"}, r.next());
            assertArrayEquals(new String[]{"1", "He said \"hi\"", "7"}, r.next());
            assertNull(r.next());
        }
    }

    @Test
    void anEmptyCellIsNullNotZero() {
        String csv = "a,REB,AST\n1,,5\n2,3,\n";
        try (KaggleCsv r = new KaggleCsv(new StringReader(csv))) {
            assertArrayEquals(new String[]{"1", null, "5"}, r.next());
            assertArrayEquals(new String[]{"2", "3", null}, r.next());
        }
    }

    @Test
    void aLineBreakInsideQuotesStaysInTheCell() {
        String csv = "a,b\n\"line1\nline2\",x\n";
        try (KaggleCsv r = new KaggleCsv(new StringReader(csv))) {
            assertArrayEquals(new String[]{"line1\nline2", "x"}, r.next());
        }
    }

    @Test
    void aFinalRecordWithoutATrailingNewlineIsKept() {
        try (KaggleCsv r = new KaggleCsv(new StringReader("a,b\n1,2"))) {
            assertArrayEquals(new String[]{"1", "2"}, r.next());
            assertNull(r.next());
        }
    }

    @Test
    void theColumnMapNamesTurnoversAndThreePointers() {
        assertEquals("to", KaggleCsv.COLUMN_MAP.get("TOV"));
        assertEquals("tpm", KaggleCsv.COLUMN_MAP.get("FG3M"));
        assertEquals("pts", KaggleCsv.COLUMN_MAP.get("PTS"));
    }
}
