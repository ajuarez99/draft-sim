package com.ballknowers.draftsim.domain;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class DraftSlotTest {

    @Test
    void snakeOrderReversesOnEvenRounds() {
        int teams = 14;
        // round 1 runs 1..14
        assertEquals(1, DraftSlot.slot(1, teams));
        assertEquals(14, DraftSlot.slot(14, teams));
        // round 2 runs 14..1
        assertEquals(14, DraftSlot.slot(15, teams));
        assertEquals(1, DraftSlot.slot(28, teams));
        // round 3 runs 1..14 again
        assertEquals(1, DraftSlot.slot(29, teams));
    }

    @Test
    void roundsAreOneIndexed() {
        assertEquals(1, DraftSlot.round(1, 14));
        assertEquals(1, DraftSlot.round(14, 14));
        assertEquals(2, DraftSlot.round(15, 14));
        assertEquals(15, DraftSlot.round(210, 14));
    }

    @Test
    void slot11In14TeamDraftGetsTheExpectedPicks() {
        int[] picks = DraftSlot.picksForSlot(11, 14, 15);
        // 1.11, then the turn: 2.04 (pick 18), 3.11 (pick 39), 4.04 (pick 46) ...
        assertEquals(11, picks[0]);
        assertEquals(18, picks[1]);
        assertEquals(39, picks[2]);
        assertEquals(46, picks[3]);
        assertEquals(15, picks.length);
    }

    /**
     * Run across every size the app can be asked for, plus the odd counts in
     * between as insurance: off-by-one bugs in snake code love odd team counts,
     * and nothing else in the suite would notice one.
     */
    @ParameterizedTest
    @ValueSource(ints = {8, 9, 10, 11, 12, 13, 14, 15})
    void everyPickMapsBackToItsOwnSlot(int teams) {
        int rounds = 15;
        for (int slot = 1; slot <= teams; slot++) {
            for (int pick : DraftSlot.picksForSlot(slot, teams, rounds)) {
                assertEquals(slot, DraftSlot.slot(pick, teams),
                        "pick " + pick + " should belong to slot " + slot
                                + " in a " + teams + "-team draft");
            }
        }
    }

    @ParameterizedTest
    @ValueSource(ints = {8, 9, 10, 11, 12, 13, 14, 15})
    void everyPickNumberIsClaimedExactlyOnce(int teams) {
        int rounds = 15;
        boolean[] seen = new boolean[teams * rounds + 1];
        for (int slot = 1; slot <= teams; slot++) {
            for (int pick : DraftSlot.picksForSlot(slot, teams, rounds)) {
                assertFalse(seen[pick], "pick " + pick + " claimed twice at " + teams + " teams");
                seen[pick] = true;
            }
        }
        for (int p = 1; p < seen.length; p++) {
            assertTrue(seen[p], "pick " + p + " unclaimed at " + teams + " teams");
        }
    }

    /**
     * Pins {@code reversalRound = 0} against real data rather than only against
     * the formula's own self-consistency: the 168 observed {@code draft_slot}
     * values from the 2025 Ball Knowers NBA draft (sleeper draft
     * 1229352720230514688, 12 teams, 14 rounds, {@code reversal_round: 0}),
     * fetched live and cached under claude/scripts/.cache -- see
     * claude/scripts/nba-cascade-length.py for the fetch-and-cache pattern.
     * multi-sport-and-rebrand.md's acceptance criterion 1: "today's plain-snake
     * math already reproduces all 168 observed draft_slot values for the 2025
     * draft" -- this is that check, made permanent rather than one-off.
     */
    @Test
    void reversalRoundZeroReproducesAllObservedSlotsOfTheReal2025NbaDraft() throws IOException {
        int teams = 12;
        List<int[]> observed = loadObservedSlots();
        assertEquals(168, observed.size(), "fixture should carry every pick of the 168-pick draft");

        for (int[] row : observed) {
            int pickNo = row[0];
            int expectedSlot = row[1];
            assertEquals(expectedSlot, DraftSlot.slot(pickNo, teams),
                    "pick " + pickNo + " (plain 2-arg overload)");
            assertEquals(expectedSlot, DraftSlot.slot(pickNo, teams, 0),
                    "pick " + pickNo + " (explicit reversalRound=0)");
        }
    }

    private static List<int[]> loadObservedSlots() throws IOException {
        List<int[]> rows = new ArrayList<>();
        try (InputStream in = DraftSlotTest.class.getResourceAsStream("/nba-2025-draft-slots.csv")) {
            assertNotNull(in, "test fixture nba-2025-draft-slots.csv missing from test resources");
            try (BufferedReader r = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8))) {
                String line;
                while ((line = r.readLine()) != null) {
                    if (line.isBlank()) continue;
                    String[] parts = line.split(",");
                    rows.add(new int[] {Integer.parseInt(parts[0].trim()), Integer.parseInt(parts[1].trim())});
                }
            }
        } catch (UncheckedIOException e) {
            throw e.getCause();
        }
        return rows;
    }

    @ParameterizedTest
    @ValueSource(ints = {8, 9, 10, 11, 12, 13, 14, 15})
    void roundAndSlotAgreeWithPickNumberInBothDirections(int teams) {
        int rounds = 15;
        for (int pick = 1; pick <= teams * rounds; pick++) {
            int round = DraftSlot.round(pick, teams);
            int slot = DraftSlot.slot(pick, teams);
            assertTrue(round >= 1 && round <= rounds, "round " + round + " for pick " + pick);
            assertTrue(slot >= 1 && slot <= teams, "slot " + slot + " for pick " + pick);
            assertEquals(pick, DraftSlot.picksForSlot(slot, teams, rounds)[round - 1],
                    "pick " + pick + " at " + teams + " teams");
        }
    }
}
