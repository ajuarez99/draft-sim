package com.ballknowers.draftsim.engine;

import com.ballknowers.draftsim.engine.RivalryWeekPicker.Candidate;
import com.ballknowers.draftsim.engine.RivalryMatching.PairId;
import com.ballknowers.draftsim.engine.RivalryWeekPicker.WeekPick;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

import static org.junit.jupiter.api.Assertions.*;

/**
 * specs/037 T007 (SC-003), written BEFORE {@link RivalryWeekPicker}. AGENTS.md bug class 1: a ranking change
 * can be well-formed and pointed backwards, so this pins the CONSTRAINT (cap) and the PREFERENCE ORDER (total
 * descending, history-best pairs kept descending, week ascending; ranking on round3(total), plan F2/F11).
 */
class RivalryWeekPickerTest {

    private static final int CAP = 2;

    private static RivalryMatching.PairScore table(Map<String, Double> byPair) {
        return (a, b) -> byPair.getOrDefault(Math.min(a, b) + "-" + Math.max(a, b), 0.0);
    }

    private static Map<String, Double> scores(Object... kv) {
        Map<String, Double> m = new HashMap<>();
        for (int i = 0; i < kv.length; i += 2) m.put((String) kv[i], ((Number) kv[i + 1]).doubleValue());
        return m;
    }

    /** week -> pairs; {@code add(s, week, a, b, a, b, ...)}. */
    private static void add(Map<Integer, List<long[]>> s, int week, long... flat) {
        for (int i = 0; i < flat.length; i += 2) {
            s.computeIfAbsent(week, k -> new ArrayList<>()).add(new long[]{flat[i], flat[i + 1]});
        }
    }

    private static Candidate cand(WeekPick p, int week) {
        return p.candidates().stream().filter(c -> c.week() == week).findFirst().orElseThrow();
    }

    private static boolean paired(Candidate c, long a, long b) {
        return c.matching().pairs().stream().anyMatch(p -> p.a() == Math.min(a, b) && p.b() == Math.max(a, b));
    }

    private static final List<Long> FOUR = List.of(1L, 2L, 3L, 4L);

    // ---- 1: the constraint

    @Test
    void aPairAtTwoMeetingsElsewhereIsNeverPairedEvenThoughItTopsTheScore() {
        Map<Integer, List<long[]>> s = new TreeMap<>();
        add(s, 2, 1, 2, 3, 4);
        add(s, 3, 1, 2, 3, 4);
        WeekPick p = RivalryWeekPicker.pick(FOUR, s, List.of(4, 5), CAP,
                table(scores("1-2", 100, "3-4", 100, "1-3", 1, "2-4", 1)), 16);
        for (Candidate c : p.candidates()) {
            assertTrue(c.feasible());
            assertFalse(paired(c, 1, 2), "1-2 already meets in weeks 2 and 3");
            assertFalse(paired(c, 3, 4));
        }
        assertEquals(2.0, cand(p, 4).total(), 1e-9);
    }

    // ---- 2: the off-by-one

    @Test
    void aPairScheduledInWAndOneOtherWeekMayBePairedInWAndLandsInAlreadyIn() {
        Map<Integer, List<long[]>> s = new TreeMap<>();
        add(s, 1, 1, 2, 3, 4);
        add(s, 4, 1, 2, 3, 4);
        WeekPick p = RivalryWeekPicker.pick(FOUR, s, List.of(4), CAP,
                table(scores("1-2", 100, "3-4", 100)), 16);
        Candidate w4 = cand(p, 4);
        assertTrue(paired(w4, 1, 2), "2 - 1 + 1 = 2 <= cap");
        assertTrue(w4.alreadyIn().contains(new PairId(1, 2)));
        assertTrue(w4.alreadyIn().contains(new PairId(3, 4)));
        assertTrue(w4.displaced().isEmpty());
    }

    // ---- 3: preference ordering, total

    @Test
    void theHigherTotalWeekWinsEvenWhenAnEarlierWeekIsFeasible() {
        Map<Integer, List<long[]>> s = new TreeMap<>();
        add(s, 1, 1, 2, 3, 4);
        add(s, 5, 1, 2, 3, 4);
        WeekPick p = RivalryWeekPicker.pick(FOUR, s, List.of(4, 5), CAP,
                table(scores("1-2", 10, "3-4", 10, "1-3", 1, "2-4", 1)), 16);
        assertEquals(2.0, cand(p, 4).total(), 1e-9, "week 4 would make 1-2 and 3-4 a third meeting");
        assertEquals(20.0, cand(p, 5).total(), 1e-9);
        assertEquals(5, p.recommended().week());
        assertEquals(List.of(5, 4), p.candidates().stream().map(Candidate::week).toList());
    }

    // ---- 4: ties go to the earliest week

    @Test
    void weeksTiedOnTotalAndOnKeptPairsGoToTheEarliestAndCountTheTie() {
        WeekPick p = RivalryWeekPicker.pick(FOUR, new TreeMap<>(), List.of(4, 5), CAP,
                table(scores("1-2", 3, "3-4", 3)), 16);
        assertEquals(4, p.recommended().week());
        assertEquals(2, p.recommended().tiedWith());
        assertEquals(2, cand(p, 5).tiedWith());
        assertTrue(p.recommended().sameAsBest());
        assertTrue(p.recommended().heldBack().isEmpty());
    }

    // ---- F11: the rank key is round3(total), the displayed value

    private static Candidate stub(int week, double total, int kept) {
        return new Candidate(week, true, null, total, List.of(), List.of(), Set.of(), kept, false, 0, 0);
    }

    @Test
    void theRankKeyRoundsTheTotalToThreeDecimalsSoTheSortAndTheTieRuleAgree() {
        // 5.0004 vs 5.0 differ by more than EPS, yet both display as 5, so they TIE on key 1 and the kept
        // count decides: the lower raw total with more history-best pairs kept ranks first.
        Candidate rawHigherKeepsFewer = stub(4, 5.0004, 1);
        Candidate rawLowerKeepsMore = stub(5, 5.0, 2);
        assertTrue(RivalryWeekPicker.RANK.compare(rawLowerKeepsMore, rawHigherKeepsFewer) < 0);
        // a displayed difference of 0.001 still outranks everything below it
        assertTrue(RivalryWeekPicker.RANK.compare(stub(9, 5.001, 0), rawLowerKeepsMore) < 0);
        // then the week ascends
        assertTrue(RivalryWeekPicker.RANK.compare(stub(4, 5.0, 2), stub(5, 5.0004, 2)) < 0);
    }

    // ---- 4b: F2 key 2 -- equal total, more history-best pairs kept beats the earlier week

    @Test
    void equalTotalButMoreHistoryBestPairsKeptBeatsTheEarlierWeek() {
        // Unconstrained = 1-2 + 3-4 (6, first found among the equal optimum 1-3 + 2-4).
        Map<Integer, List<long[]>> s = new TreeMap<>();
        add(s, 1, 1, 2);
        add(s, 5, 1, 2);   // 1-2 meets in weeks 1 and 5: allowed in 5, blocked in 4
        WeekPick p = RivalryWeekPicker.pick(FOUR, s, List.of(4, 5), CAP,
                table(scores("1-2", 3, "3-4", 3, "1-3", 3, "2-4", 3)), 16);
        Candidate w4 = cand(p, 4);
        Candidate w5 = cand(p, 5);
        assertEquals(6.0, w4.total(), 1e-9);
        assertEquals(6.0, w5.total(), 1e-9);
        assertEquals(0, w4.keptBest());
        assertEquals(2, w5.keptBest());
        assertFalse(w4.sameAsBest(), "equal total, different set (plan F1)");
        assertTrue(w5.sameAsBest());
        assertEquals(5, p.recommended().week(), "key 2 outranks 'earliest'");
        assertEquals(1, p.recommended().tiedWith(), "tiedWeeks counts weeks equal on keys 1 AND 2");
        assertEquals(1, w4.tiedWith());
    }

    // ---- 5: infeasible weeks

    @Test
    void aWeekWithNoCapObeyingFullMatchingIsInfeasibleAndSortsAfterFeasibleWeeks() {
        // cap 1 and a full round robin in weeks 5-7: every pair has met once, so outside its own week it is blocked.
        Map<Integer, List<long[]>> s = new TreeMap<>();
        add(s, 5, 1, 2, 3, 4);
        add(s, 6, 1, 3, 2, 4);
        add(s, 7, 1, 4, 2, 3);
        WeekPick p = RivalryWeekPicker.pick(FOUR, s, List.of(4, 5), 1, (a, b) -> 1.0, 16);
        assertFalse(cand(p, 4).feasible());
        assertNull(cand(p, 4).matching());
        assertTrue(cand(p, 5).feasible());
        assertEquals(List.of(5, 4), p.candidates().stream().map(Candidate::week).toList());
        assertEquals(5, p.recommended().week());
    }

    @Test
    void whenEveryWeekIsInfeasibleNothingIsRecommendedAndTheyAreOrderedByWeek() {
        Map<Integer, List<long[]>> s = new TreeMap<>();
        add(s, 5, 1, 2, 3, 4);
        add(s, 6, 1, 3, 2, 4);
        add(s, 7, 1, 4, 2, 3);
        WeekPick p = RivalryWeekPicker.pick(FOUR, s, List.of(8, 9), 1, (a, b) -> 1.0, 16);
        assertNull(p.recommended());
        assertEquals(List.of(8, 9), p.candidates().stream().map(Candidate::week).toList());
        assertTrue(p.candidates().stream().noneMatch(Candidate::feasible));
    }

    // ---- 6 / 8: displaced

    @Test
    void inAnOddLeagueTheSitOutManagersOriginalMatchupIsDisplaced() {
        List<Long> five = List.of(1L, 2L, 3L, 4L, 5L);
        Map<Integer, List<long[]>> s = new TreeMap<>();
        add(s, 4, 1, 2, 3, 4);   // 5 is on a bye
        WeekPick p = RivalryWeekPicker.pick(five, s, List.of(4), CAP,
                table(scores("2-3", 5, "4-5", 5)), 16);
        Candidate w = cand(p, 4);
        assertEquals(1L, w.matching().unpaired());
        assertEquals(List.of(new PairId(1, 2), new PairId(3, 4)), w.displaced());
    }

    @Test
    void displacedIsTheWeeksScheduledPairsNotInTheMatchingAndNeverOverlapsAlreadyIn() {
        Map<Integer, List<long[]>> s = new TreeMap<>();
        add(s, 4, 1, 2, 3, 4);
        WeekPick p = RivalryWeekPicker.pick(FOUR, s, List.of(4), CAP,
                table(scores("1-2", 1, "3-4", 1, "1-3", 9, "2-4", 9)), 16);
        Candidate w = cand(p, 4);
        assertEquals(List.of(new PairId(1, 2), new PairId(3, 4)), w.displaced());
        assertTrue(w.alreadyIn().isEmpty());
        // one kept, two displaced (six managers)
        Map<Integer, List<long[]>> s6 = new TreeMap<>();
        add(s6, 4, 1, 2, 3, 4, 5, 6);
        WeekPick q = RivalryWeekPicker.pick(List.of(1L, 2L, 3L, 4L, 5L, 6L), s6, List.of(4), CAP,
                table(scores("1-2", 9, "3-5", 5, "4-6", 5)), 16);
        Candidate x = cand(q, 4);
        assertEquals(Set.of(new PairId(1, 2)), x.alreadyIn());
        assertEquals(List.of(new PairId(3, 4), new PairId(5, 6)), x.displaced());
        for (PairId d : x.displaced()) assertFalse(x.alreadyIn().contains(d));
    }

    // ---- 7: heldBack

    @Test
    void heldBackIsTheUnconstrainedPairsNotAllowedInThatWeek() {
        Map<Integer, List<long[]>> s = new TreeMap<>();
        add(s, 1, 1, 2);
        add(s, 2, 1, 2);
        WeekPick p = RivalryWeekPicker.pick(FOUR, s, List.of(4), CAP,
                table(scores("1-2", 9, "3-4", 5, "1-3", 1, "2-4", 1)), 16);
        assertEquals(List.of(new PairId(1, 2)), cand(p, 4).heldBack());
        assertEquals(List.of(new PairId(1, 2), new PairId(3, 4)),
                p.unconstrained().pairs().stream().map(q -> new PairId(q.a(), q.b())).toList());
    }

    // ---- knock-on (F16)

    @Test
    void aBlockedPairForcesOtherPairsToRearrangeAndOnlyTheBlockedOneIsHeldBack() {
        List<Long> six = List.of(1L, 2L, 3L, 4L, 5L, 6L);
        Map<Integer, List<long[]>> s = new TreeMap<>();
        add(s, 1, 1, 2);
        add(s, 2, 1, 2);
        // Unconstrained: 1-2 (10) + 3-4 (4) + 5-6 (4) = 18. With 1-2 out: 1-3 + 2-4 + 5-6 = 3+3+4 = 10 beats
        // 1-5 + 2-6 + 3-4 = 2+2+4 = 8, so 3-4 (allowed, in the best set) is displaced to make room.
        WeekPick p = RivalryWeekPicker.pick(six, s, List.of(5), CAP,
                table(scores("1-2", 10, "3-4", 4, "5-6", 4, "1-3", 3, "2-4", 3, "1-5", 2, "2-6", 2)), 16);
        Candidate w = cand(p, 5);
        assertEquals(List.of(new PairId(1, 2)), w.heldBack(), "3-4 was allowed, so it is not 'held back'");
        assertFalse(paired(w, 1, 2));
        assertFalse(paired(w, 3, 4), "yet it is rearranged: a knock-on the held-back list does not show");
        assertEquals(1, w.keptBest());
        assertEquals(10.0, w.total(), 1e-9);
    }

    // ---- 9: already over the cap

    @Test
    void aPairAlreadyAboveTheCapIsListedAndNeverChosenInAnyEligibleWeekIncludingItsOwn() {
        Map<Integer, List<long[]>> s = new TreeMap<>();
        add(s, 1, 1, 2);
        add(s, 2, 1, 2);
        add(s, 3, 1, 2);
        WeekPick p = RivalryWeekPicker.pick(FOUR, s, List.of(3, 4, 5), CAP, table(scores("1-2", 100)), 16);
        assertEquals(1, p.overCapPairs().size());
        assertEquals(1L, p.overCapPairs().get(0).a());
        assertEquals(2L, p.overCapPairs().get(0).b());
        assertEquals(3, p.overCapPairs().get(0).meetings());
        for (Candidate c : p.candidates()) assertFalse(paired(c, 1, 2), "week " + c.week());
    }

    // ---- 10 / 11

    @Test
    void isDeterministic() {
        Map<Integer, List<long[]>> s = new TreeMap<>();
        add(s, 1, 1, 2, 3, 4);
        add(s, 5, 1, 2, 3, 4);
        var fn = table(scores("1-2", 3, "3-4", 3, "1-3", 3, "2-4", 3));
        assertEquals(RivalryWeekPicker.pick(FOUR, s, List.of(4, 5, 6), CAP, fn, 16),
                RivalryWeekPicker.pick(FOUR, s, List.of(4, 5, 6), CAP, fn, 16));
    }

    @Test
    void noEligibleWeeksMeansNoCandidatesAndNoRecommendation() {
        WeekPick p = RivalryWeekPicker.pick(FOUR, new TreeMap<>(), List.of(), CAP, (a, b) -> 1.0, 16);
        assertTrue(p.candidates().isEmpty());
        assertNull(p.recommended());
        assertNotNull(p.unconstrained());
    }
}
