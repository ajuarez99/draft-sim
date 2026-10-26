package com.ballknowers.draftsim.engine;

import com.ballknowers.draftsim.engine.WeeklyPotLedger.Balance;
import com.ballknowers.draftsim.engine.WeeklyPotLedger.Debt;
import com.ballknowers.draftsim.engine.WeeklyPotLedger.Ledger;
import com.ballknowers.draftsim.engine.WeeklyPotLedger.Score;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.stream.IntStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Spec 048 T004: the pot arithmetic, with literal fixtures and a seeded property loop. */
class WeeklyPotLedgerTest {

    private static Score s(int week, int roster, String points) {
        return new Score(week, roster, new BigDecimal(points));
    }

    private static List<Integer> rosters(int n) {
        return IntStream.rangeClosed(1, n).boxed().toList();
    }

    /** One week for rosters 1..n, roster i scoring 100 + i (so roster n wins), overridable. */
    private static List<Score> oneWeek(int week, int n, Map<Integer, String> override) {
        List<Score> out = new ArrayList<>();
        for (int i = 1; i <= n; i++) out.add(s(week, i, override.getOrDefault(i, String.valueOf(100 + i))));
        return out;
    }

    private static long sumNet(Ledger l) {
        return l.balances().values().stream().mapToLong(Balance::netCents).sum();
    }

    /** Applies each debt to the balances; a settle-up list is right when everyone ends at zero. */
    private static Map<Integer, Long> settle(Ledger l, List<Debt> debts) {
        Map<Integer, Long> net = new HashMap<>();
        l.balances().forEach((id, b) -> net.put(id, b.netCents()));
        for (Debt d : debts) {
            net.merge(d.fromRosterId(), d.cents(), Long::sum);
            net.merge(d.toRosterId(), -d.cents(), Long::sum);
        }
        return net;
    }

    private static void assertClears(Ledger l) {
        for (List<Debt> list : List.of(l.pairwise(), l.shortcut())) {
            settle(l, list).forEach((id, v) -> assertEquals(0L, v, "roster " + id + " not cleared"));
            for (Debt d : list) assertTrue(d.cents() > 0);
        }
        assertTrue(l.shortcut().size() <= l.pairwise().size());
        for (Debt d : l.shortcut()) {
            assertTrue(l.balances().get(d.fromRosterId()).netCents() < 0, "shortcut payer must be a net debtor");
            assertTrue(l.balances().get(d.toRosterId()).netCents() > 0, "shortcut payee must be a net creditor");
        }
        Set<Integer> inShortcut = new HashSet<>();
        for (Debt d : l.shortcut()) {
            inShortcut.add(d.fromRosterId());
            inShortcut.add(d.toRosterId());
        }
        for (int id : inShortcut) assertTrue(l.balances().get(id).netCents() != 0, "zero balance in shortcut");
        assertEquals(0L, sumNet(l));
    }

    @Test
    void oneUntiedWeekPaysTheWinnerFromEveryoneElse() {
        Ledger l = WeeklyPotLedger.compute(oneWeek(1, 12, Map.of()), rosters(12), 100);
        assertEquals(1100L, l.balances().get(12).netCents());
        for (int i = 1; i <= 11; i++) assertEquals(-100L, l.balances().get(i).netCents());
        assertEquals(List.of(12), l.weeks().get(0).winners());
        assertEquals(11, l.weeks().get(0).payers());
        assertEquals(1100L, l.weeks().get(0).potCents());
        assertEquals(0, new BigDecimal("111").compareTo(l.weeks().get(0).runnerUpPoints()));
        assertEquals(0L, sumNet(l));
        assertClears(l);
    }

    @Test
    void theHigherScorerWinsNotTheLowerOne() {
        // bug class 1: a sign inversion is well-formed and still wrong, so assert preference order
        List<Score> a = List.of(s(1, 1, "120.5"), s(1, 2, "99.5"));
        List<Score> b = List.of(s(1, 1, "99.5"), s(1, 2, "120.5"));
        assertEquals(List.of(1), WeeklyPotLedger.compute(a, rosters(2), 100).weeks().get(0).winners());
        assertEquals(List.of(2), WeeklyPotLedger.compute(b, rosters(2), 100).weeks().get(0).winners());
        assertEquals(100L, WeeklyPotLedger.compute(a, rosters(2), 100).balances().get(1).netCents());
        assertEquals(100L, WeeklyPotLedger.compute(b, rosters(2), 100).balances().get(2).netCents());
    }

    @Test
    void aTwoWayTieSplitsEvenly() {
        Ledger l = WeeklyPotLedger.compute(oneWeek(1, 6, Map.of(5, "200", 6, "200.00")), rosters(6), 100);
        assertEquals(List.of(5, 6), l.weeks().get(0).winners());
        assertEquals(4, l.weeks().get(0).payers());
        // each of the 4 payers pays 100 total, 50 to each winner
        assertEquals(200L, l.balances().get(5).collectedCents());
        assertEquals(200L, l.balances().get(6).collectedCents());
        assertEquals(1, l.balances().get(5).weeksWon());
        assertEquals(0, new BigDecimal("104").compareTo(l.weeks().get(0).runnerUpPoints()));
        assertClears(l);
    }

    @Test
    void aThreeWayTieGivesTheOddCentsToTheLowestRosterIds() {
        Map<Integer, String> tie = Map.of(1, "300", 2, "300", 3, "300");
        // 100c over 3: 34/33/33 per payer, 3 payers (rosters 4..6)
        Ledger at100 = WeeklyPotLedger.compute(oneWeek(1, 6, tie), rosters(6), 100);
        assertEquals(3 * 34L, at100.balances().get(1).collectedCents());
        assertEquals(3 * 33L, at100.balances().get(2).collectedCents());
        assertEquals(3 * 33L, at100.balances().get(3).collectedCents());
        assertEquals(-100L, at100.balances().get(4).netCents());
        assertClears(at100);
        // 200c over 3: 67/67/66
        Ledger at200 = WeeklyPotLedger.compute(oneWeek(1, 6, tie), rosters(6), 200);
        assertEquals(3 * 67L, at200.balances().get(1).collectedCents());
        assertEquals(3 * 67L, at200.balances().get(2).collectedCents());
        assertEquals(3 * 66L, at200.balances().get(3).collectedCents());
        assertClears(at200);
    }

    @Test
    void numericallyEqualScoresTieWhateverTheirScale() {
        List<Score> rows = List.of(s(1, 1, "150.1"), s(1, 2, "150.10"), s(1, 3, "90"));
        Ledger l = WeeklyPotLedger.compute(rows, rosters(3), 100);
        assertEquals(List.of(1, 2), l.weeks().get(0).winners());
        assertEquals(-100L, l.balances().get(3).netCents());
    }

    @Test
    void aNegativeScoreIsAPayerNotAnAllZeroWeek() {
        List<Score> rows = List.of(s(1, 1, "-3.5"), s(1, 2, "-10"), s(1, 3, "-20"));
        Ledger l = WeeklyPotLedger.compute(rows, rosters(3), 100);
        assertEquals(List.of(1), l.weeks().get(0).winners());
        assertEquals(200L, l.balances().get(1).netCents());
        assertEquals(-100L, l.balances().get(2).netCents());
        assertClears(l);
    }

    @Test
    void aMultiWeekSeasonClearsBothWaysAndTheShortcutIsNoLonger() {
        List<Score> rows = new ArrayList<>();
        rows.addAll(oneWeek(1, 6, Map.of(1, "300")));
        rows.addAll(oneWeek(2, 6, Map.of(2, "300")));
        rows.addAll(oneWeek(3, 6, Map.of(3, "300", 4, "300")));
        rows.addAll(oneWeek(4, 6, Map.of()));
        Ledger l = WeeklyPotLedger.compute(rows, rosters(6), 100);
        assertClears(l);
        assertEquals(11, l.pairwise().size());
        assertEquals(4, l.shortcut().size());
    }

    @Test
    void randomSeasonsAlwaysSumToZeroAndBothSettleUpsClear() {
        Random rnd = new Random(48L);
        for (int trial = 0; trial < 300; trial++) {
            int n = 8 + rnd.nextInt(7);
            int weeks = 1 + rnd.nextInt(14);
            long stake = 1 + rnd.nextInt(500);
            List<Score> rows = new ArrayList<>();
            for (int w = 1; w <= weeks; w++) {
                for (int r = 1; r <= n; r++) {
                    // coarse scores so ties actually happen
                    rows.add(s(w, r, String.valueOf(50 + rnd.nextInt(6) * 10)));
                }
            }
            Ledger l = WeeklyPotLedger.compute(rows, rosters(n), stake);
            assertEquals(0L, sumNet(l), "trial " + trial);
            assertClears(l);
        }
    }

    @Test
    void aRosterWithNoRowsStillAppearsWithZeros() {
        Ledger l = WeeklyPotLedger.compute(oneWeek(1, 3, Map.of()), List.of(1, 2, 3, 99), 100);
        Balance b = l.balances().get(99);
        assertEquals(new Balance(99, 0, 0, 0, 0), b);
        assertFalse(l.shortcut().stream().anyMatch(d -> d.fromRosterId() == 99 || d.toRosterId() == 99));
        // and with no decided weeks at all, everyone is present at zero
        Ledger empty = WeeklyPotLedger.compute(List.of(), rosters(4), 100);
        assertEquals(4, empty.balances().size());
        assertTrue(empty.weeks().isEmpty());
        assertTrue(empty.pairwise().isEmpty());
        assertTrue(empty.shortcut().isEmpty());
    }
}
