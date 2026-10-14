package com.ballknowers.draftsim.engine;

import com.ballknowers.draftsim.engine.OwnershipTimeline.*;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The ownership rules of specs/033-trade-verdicts, on literal fixtures: no Spring, no database.
 *
 * <p>Part one is synthetic and pins the ARITHMETIC and its direction (AGENTS.md bug class 1: a sign
 * inversion passes every structural test). Part two is real rows copied out of the local database.
 * Their expected outcomes come from the executed script in plan-review.md and from independent SQL
 * sums over roster_week_points; if the Java ever disagrees, find which is wrong, do not edit the
 * expectation.
 *
 * <p>The SQL used for every real-row fixture (L is the internal league.id; PLAYERS the case's keys):
 * <pre>
 *   select week, roster_id,
 *          (select string_agg(k||'='||v, ',' order by k) from jsonb_each_text(players_points) e(k,v) where k = any(PLAYERS)),
 *          (select string_agg(s, ',' order by s) from jsonb_array_elements_text(starters) s where s = any(PLAYERS))
 *   from roster_week_points
 *   where league_id = L and (players_points ?| PLAYERS or starters ?| PLAYERS) order by week, roster_id;
 *
 *   select sleeper_transaction_id, type, week, created_at, adds, drops, id
 *   from league_transaction where league_id = L and status = 'complete'
 *     and (adds ?| PLAYERS or drops ?| PLAYERS) order by created_at, id;
 * </pre>
 * NBA 2025 is league 211, NFL 2025 is league 5. Roster-weeks that list none of the case's players are
 * omitted; that is equivalent to a row that does not list them.
 */
class OwnershipTimelineTest {

    // ------------------------------------------------------------------ helpers

    private static final Set<Integer> WEEKS_1_TO_21 = rangeSet(1, 21);
    private static final String AT = "2025-10-01T00:00:00Z";

    private static Set<Integer> rangeSet(int lo, int hi) {
        Set<Integer> s = new TreeSet<>();
        for (int i = lo; i <= hi; i++) s.add(i);
        return s;
    }

    /** {@code points} is "id=pts,id=pts"; {@code starters} is "id,id". */
    private static WeekRow row(int week, int roster, String points, String starters) {
        Map<String, Double> pts = new LinkedHashMap<>();
        if (!points.isEmpty()) {
            for (String kv : points.split(",")) {
                String[] p = kv.split("=");
                pts.put(p[0], Double.parseDouble(p[1]));
            }
        }
        Set<String> st = new LinkedHashSet<>();
        if (!starters.isEmpty()) st.addAll(Arrays.asList(starters.split(",")));
        return new WeekRow(week, roster, pts, st);
    }

    private static Map<String, Integer> ids(Object... kv) {
        Map<String, Integer> m = new LinkedHashMap<>();
        for (int i = 0; i < kv.length; i += 2) m.put((String) kv[i], (Integer) kv[i + 1]);
        return m;
    }

    private static Move move(String tx, String type, int leg, String at, Map<String, Integer> adds,
                             Map<String, Integer> drops) {
        return new Move(tx, type, leg, Instant.parse(at), adds, drops);
    }

    private static Trade tradeOf(List<Move> moves, String txId) {
        Move m = moves.stream().filter(x -> x.txId().equals(txId)).findFirst().orElseThrow();
        assertEquals("TRADE", m.type());
        return new Trade(m.txId(), m.leg(), m.acceptedAt(), m.adds(), m.drops(), List.of(), List.of(), true);
    }

    private static Inputs inputs(List<WeekRow> weeks, List<Move> moves, List<Trade> trades,
                                 Set<Integer> finals, boolean seasonComplete) {
        return new Inputs(2025, weeks, moves, trades, List.of(), true, Map.of(), Map.of(), finals, seasonComplete);
    }

    /** Evaluates the named trades of one fixture over a complete season with weeks 1-21 final. */
    private static Map<String, Evaluated> complete(List<WeekRow> weeks, List<Move> moves, String... txIds) {
        List<Trade> trades = new ArrayList<>();
        for (String id : txIds) trades.add(tradeOf(moves, id));
        Map<String, Evaluated> out = new LinkedHashMap<>();
        for (Evaluated e : OwnershipTimeline.evaluate(inputs(weeks, moves, trades, WEEKS_1_TO_21, true))) {
            out.put(e.txId(), e);
        }
        return out;
    }

    private static Evaluated only(Map<String, Evaluated> m) {
        assertEquals(1, m.size());
        return m.values().iterator().next();
    }

    private static Side side(Evaluated e, int rosterId) {
        return e.sides().stream().filter(s -> s.rosterId() == rosterId).findFirst().orElseThrow();
    }

    private static PlayerAsset player(Evaluated e, int rosterId, String key) {
        return side(e, rosterId).assets().stream()
                .filter(a -> a instanceof PlayerAsset p && p.playerKey().equals(key))
                .map(a -> (PlayerAsset) a).findFirst().orElseThrow();
    }

    private static void assertWindow(PlayerAsset p, int first, int last) {
        assertEquals(first, p.windowFirst(), p.playerKey() + " window start");
        assertEquals(last, p.windowLast(), p.playerKey() + " window end");
    }

    private static void assertCounted(PlayerAsset p, int firstCounted, int lastCounted, double started, double bench) {
        assertEquals(Status.COUNTED, p.status(), p.playerKey());
        assertEquals(firstCounted, p.firstWeek(), p.playerKey() + " first counted week");
        assertEquals(lastCounted, p.lastWeek(), p.playerKey() + " last counted week");
        assertEquals(started, p.startedPoints(), 0.05, p.playerKey() + " started");
        assertEquals(bench, p.benchPoints(), 0.05, p.playerKey() + " bench");
    }

    // ====================================================================================
    // Part one: synthetic fixtures, the arithmetic and its direction
    // ====================================================================================

    /** Roster 1 receives "A" from roster 2; roster 2 receives "B" from roster 1. Proposed in week 2. */
    private static List<Move> swapMoves() {
        return List.of(move("t1", "TRADE", 2, AT, ids("A", 1, "B", 2), ids("A", 2, "B", 1)));
    }

    /** Week 1 is before the trade; weeks 2-4 after. */
    private static List<WeekRow> swapWeeks(double aPts, double bPts) {
        List<WeekRow> w = new ArrayList<>();
        w.add(row(1, 2, "A=" + aPts, "A"));
        w.add(row(1, 1, "B=" + bPts, "B"));
        for (int wk = 2; wk <= 4; wk++) {
            w.add(row(wk, 1, "A=" + aPts, "A"));
            w.add(row(wk, 2, "B=" + bPts, "B"));
        }
        return w;
    }

    private static Evaluated swap(List<WeekRow> weeks, Set<Integer> finals, boolean complete) {
        List<Move> moves = swapMoves();
        return OwnershipTimeline.evaluate(inputs(weeks, moves, List.of(tradeOf(moves, "t1")), finals, complete)).getFirst();
    }

    /** SC-002: the side that got the 20-a-week player beats the side that got the 5-a-week player, by the summed difference. */
    @Test
    void theSideThatStartedMoreWinsAndTheMarginIsTheSummedDifference() {
        Evaluated e = swap(swapWeeks(20, 5), rangeSet(1, 4), true);
        assertEquals(State.WINNER, e.verdict().state());
        assertEquals(1, e.verdict().winnerRosterId());
        assertEquals(45.0, e.verdict().margin(), 1e-9, "3 weeks x (20 - 5)");
        assertEquals(1, e.sides().getFirst().rosterId(), "the winner ranks first");
        assertEquals(60.0, side(e, 1).startedTotal(), 1e-9);
        assertEquals(15.0, side(e, 2).startedTotal(), 1e-9);
        assertEquals(3, e.verdict().weeksCounted());
        assertTrue(e.verdict().isFinal());
    }

    @Test
    void theMirroredFixtureHasTheOtherSideWinning() {
        Evaluated e = swap(swapWeeks(5, 20), rangeSet(1, 4), true);
        assertEquals(State.WINNER, e.verdict().state());
        assertEquals(2, e.verdict().winnerRosterId(), "a sign inversion would still pass the fixture above");
        assertEquals(45.0, e.verdict().margin(), 1e-9);
        assertEquals(2, e.sides().getFirst().rosterId());
    }

    @Test
    void benchOnlyWeeksAreBenchPointsAndNeverStartedPoints() {
        List<WeekRow> w = new ArrayList<>(swapWeeks(10, 10));
        w.removeIf(r -> r.week() == 4 && r.rosterId() == 1);
        w.add(row(4, 1, "A=30.0", ""));  // week 4: A sits on roster 1's bench and scores 30
        Evaluated e = swap(w, rangeSet(1, 4), true);
        PlayerAsset a = player(e, 1, "A");
        assertEquals(20.0, a.startedPoints(), 1e-9, "weeks 2 and 3 only");
        assertEquals(30.0, a.benchPoints(), 1e-9, "the benched 30 is bench, and does not decide the trade");
        assertEquals(State.WINNER, e.verdict().state());
        assertEquals(2, e.verdict().winnerRosterId(), "B started 30 over 3 weeks; A only started 20");
        assertEquals(10.0, e.verdict().margin(), 1e-9);
    }

    @Test
    void underFiveHundredthsOfAPointIsEvenAndTheNextStepUpIsAWinner() {
        List<WeekRow> w = List.of(
                row(1, 1, "B=0.0", ""), row(1, 2, "A=0.0", ""),
                row(2, 1, "A=10.04", "A"), row(2, 2, "B=10.0", "B"));
        Evaluated even = swap(w, rangeSet(1, 2), true);
        assertEquals(State.EVEN, even.verdict().state());
        assertNull(even.verdict().winnerRosterId());
        assertEquals(0.0, even.verdict().margin(), 1e-9, "0.04 rounds to 0.0 at one decimal");

        List<WeekRow> w2 = List.of(
                row(1, 1, "B=0.0", ""), row(1, 2, "A=0.0", ""),
                row(2, 1, "A=10.06", "A"), row(2, 2, "B=10.0", "B"));
        assertEquals(State.WINNER, swap(w2, rangeSet(1, 2), true).verdict().state(), "0.06 is not even");
    }

    @Test
    void whenNeitherSideStartedAnythingTheVerdictIsEvenAtZero() {
        Evaluated e = swap(swapWeeks(0, 0), rangeSet(1, 4), true);
        assertEquals(State.EVEN, e.verdict().state());
        assertEquals(0.0, side(e, 1).startedTotal(), 1e-9);
        assertEquals(0.0, side(e, 2).startedTotal(), 1e-9);
        assertEquals(3, e.verdict().weeksCounted(), "weeks were counted: the page says 'even', never '0-0'");
    }

    @Test
    void aNegativeStartedWeekLowersTheTotal() {
        // An NFL defense can score below zero (PIT -2.0 in the real data).
        List<WeekRow> w = List.of(
                row(1, 1, "B=0.0", ""), row(1, 2, "A=0.0", ""),
                row(2, 1, "A=5.0", "A"), row(2, 2, "B=1.0", "B"),
                row(3, 1, "A=-2.0", "A"), row(3, 2, "B=1.0", "B"));
        Evaluated e = swap(w, rangeSet(1, 3), true);
        assertEquals(3.0, player(e, 1, "A").startedPoints(), 1e-9, "5.0 + -2.0");
        assertEquals(2.0, player(e, 2, "B").startedPoints(), 1e-9);
        assertEquals(1.0, e.verdict().margin(), 1e-9);
    }

    @Test
    void aThreeSidedTradeRanksAllThreeSides() {
        // 1 gets A from 2; 2 gets B from 3; 3 gets C from 1.
        List<Move> moves = List.of(move("t3", "TRADE", 2, AT,
                ids("A", 1, "B", 2, "C", 3), ids("A", 2, "B", 3, "C", 1)));
        List<WeekRow> w = List.of(
                row(1, 2, "A=1.0", "A"), row(1, 3, "B=1.0", "B"), row(1, 1, "C=1.0", "C"),
                row(2, 1, "A=10.0", "A"), row(2, 2, "B=30.0", "B"), row(2, 3, "C=20.0", "C"));
        Evaluated e = OwnershipTimeline.evaluate(inputs(w, moves, List.of(tradeOf(moves, "t3")), rangeSet(1, 2), true)).getFirst();
        assertEquals(List.of(2, 3, 1), e.sides().stream().map(Side::rosterId).toList(), "ranked 30, 20, 10");
        assertEquals(State.WINNER, e.verdict().state());
        assertEquals(2, e.verdict().winnerRosterId());
        assertEquals(10.0, e.verdict().margin(), 1e-9, "winner minus RUNNER-UP, not minus last place");
    }

    @Test
    void noFinalWeekIsNoWeekYetNeverAScoreOfZero() {
        Evaluated e = swap(swapWeeks(20, 5), Set.of(), false);
        assertEquals(State.NO_WEEK_YET, e.verdict().state());
        assertNull(e.verdict().margin());
        assertEquals(0, e.verdict().weeksCounted());
        assertFalse(e.verdict().isFinal());
        assertEquals(Status.NO_WEEK_YET, player(e, 1, "A").status());
        assertEquals(Status.NO_WEEK_YET, player(e, 2, "B").status());
    }

    /**
     * Code review R1. Accepted after week 2's games, so the receiver's week-2 row lists A unstarted (the F4 boundary
     * week, dropped); week 3 is not stored yet and the season is incomplete. Every window week is final, yet A is on
     * that roster right now, so he is NO_WEEK_YET, not NEVER_PLAYED_FOR_THEM.
     */
    @Test
    void aBoundaryWeekReceiverInALiveSeasonIsNoWeekYetNotNeverPlayed() {
        List<WeekRow> w = List.of(
                row(1, 2, "A=5.0", "A"), row(1, 1, "B=5.0", "B"),
                row(2, 1, "A=0.0", ""), row(2, 2, "B=3.0", "B"));
        Evaluated e = swap(w, rangeSet(1, 2), false);
        PlayerAsset a = player(e, 1, "A");
        assertEquals(Status.NO_WEEK_YET, a.status());
        assertTrue(a.countedWeeks().isEmpty());
        // The same rows in a COMPLETE season really are "never played for them".
        assertEquals(Status.NEVER_PLAYED_FOR_THEM, player(swap(w, rangeSet(1, 2), true), 1, "A").status());
    }

    /** Code review R2: a resolved pick whose drafted player was dropped before he ever scored carries that status, not COUNTED. */
    @Test
    void aResolvedPickReadsAsItsDraftedPlayerDoes() {
        Trade t = new Trade("tp", 1, Instant.parse(AT), Map.of(), Map.of(),
                List.of(new PickRef(2026, 1, 11, 8)), List.of(), true);
        List<Move> moves = List.of(
                move("tp", "TRADE", 1, AT, ids(), ids()),
                move("drop", "FREE_AGENT", 1, "2025-10-02T00:00:00Z", ids(), ids("P", 8)));
        Inputs in = new Inputs(2026, List.of(row(1, 8, "X=1.0", "X")), moves, List.of(t),
                List.of(new DraftedPick(1, 10, 10, 50L, "P")), true,
                Map.of(10, 51L), Map.of(8, 50L, 11, 51L), rangeSet(1, 1), true);
        PickAsset pick = (PickAsset) OwnershipTimeline.evaluate(in).getFirst().sides().getFirst().assets().getFirst();
        assertEquals(Status.LEFT_BEFORE_PLAYING, pick.player().status());
        assertEquals(Status.LEFT_BEFORE_PLAYING, pick.status());
        assertEquals(10, pick.pickNo());
    }

    @Test
    void aWeekThatIsStoredButNotFinalNeverCounts() {
        // Weeks 1-3 final, week 4 is in progress: its partial points must stay out.
        Evaluated e = swap(swapWeeks(20, 5), rangeSet(1, 3), false);
        assertEquals(40.0, side(e, 1).startedTotal(), 1e-9, "weeks 2 and 3 only");
        assertEquals(2, e.verdict().weeksCounted());
        assertFalse(e.verdict().isFinal(), "so far, not final");
    }

    @Test
    void aSideWithOnlyFaabIsNotComparable() {
        List<Move> moves = List.of(move("t4", "TRADE", 2, AT, ids("A", 1), ids("A", 2)));
        Trade t = new Trade("t4", 2, Instant.parse(AT), ids("A", 1), ids("A", 2),
                List.of(), List.of(new FaabRef(1, 2, 30)), true);
        List<WeekRow> w = List.of(row(1, 2, "A=1.0", "A"), row(2, 1, "A=9.0", "A"));
        Evaluated e = OwnershipTimeline.evaluate(inputs(w, moves, List.of(t), rangeSet(1, 2), true)).getFirst();
        assertEquals(State.NOT_COMPARABLE, e.verdict().state());
        assertNull(e.verdict().margin());
        assertTrue(side(e, 2).assets().getFirst() instanceof FaabAsset f && f.amount() == 30 && f.fromRosterId() == 1);
    }

    @Test
    void aWeekListedOnTwoRostersIsCountedOnceForTheStarterNeverSummedAcrossRosters() {
        // Week 2: both rosters list A, roster 2 (the giver) started him. Roster 1's window starts at week 3.
        List<WeekRow> w = List.of(
                row(1, 2, "A=7.0", "A"), row(1, 1, "B=7.0", "B"),
                row(2, 2, "A=9.0,B=0.0", "A"), row(2, 1, "A=9.0,B=8.0", "B"),
                row(3, 1, "A=11.0,B=8.0", "A,B"), row(3, 2, "B=0.0", ""));
        Evaluated e = swap(w, rangeSet(1, 3), true);
        PlayerAsset a = player(e, 1, "A");
        assertWindow(a, 3, 3);
        assertCounted(a, 3, 3, 11.0, 0.0);
    }

    @Test
    void twoListingsAndNoStarterIsADuplicateStartedByNeither() {
        List<WeekRow> w = List.of(
                row(1, 2, "A=7.0", "A"), row(1, 1, "B=7.0", "B"),
                row(2, 2, "A=9.0,B=0.0", ""), row(2, 1, "A=9.0", ""),     // duplicate: nobody started A
                row(3, 1, "A=11.0", "A"));
        Evaluated e = swap(w, rangeSet(1, 3), true);
        PlayerAsset a = player(e, 1, "A");
        assertWindow(a, 2, 3);
        // Week 2 is the duplicate: kept in the window, never started; counting starts at his first start, week 3.
        assertCounted(a, 3, 3, 11.0, 0.0);
        assertTrue(a.duplicateWeeks().isEmpty(), "the duplicate week precedes the first start, so it is not counted");

        // Counting a duplicate week: A is never started at all by roster 1, so only week 3 onward counts and
        // the duplicate week 2 is the skipped boundary week.
        List<WeekRow> w2 = List.of(
                row(1, 2, "A=7.0", "A"), row(1, 1, "B=7.0", "B"),
                row(2, 2, "A=9.0,B=0.0", ""), row(2, 1, "A=9.0", ""),
                row(3, 2, "A=9.0", ""), row(3, 1, "A=9.0", ""));
        PlayerAsset a2 = player(swap(w2, rangeSet(1, 3), true), 1, "A");
        assertEquals(List.of(3), a2.countedWeeks());
        assertEquals(List.of(3), a2.duplicateWeeks());
        assertEquals(0.0, a2.startedPoints(), 1e-9);
        assertEquals(9.0, a2.benchPoints(), 1e-9, "flagged duplicate: bench only, never started");
    }

    @Test
    void aPickThatResolvesCountsItsDraftedPlayerForTheReceiver() {
        // Roster 8 (manager 50) got roster 11's round-1 pick; roster 11's slot is 10; slot 10 drafted P, by manager 50.
        // Roster 11 (manager 51) got roster 8's round-1 pick; roster 8's slot is 2; slot 2 drafted Q, by manager 51.
        Trade t = new Trade("tp", 1, Instant.parse(AT), Map.of(), Map.of(),
                List.of(new PickRef(2026, 1, 11, 8), new PickRef(2026, 1, 8, 11)), List.of(), true);
        List<Move> moves = List.of(move("tp", "TRADE", 1, AT, ids(), ids()));
        List<DraftedPick> drafted = List.of(new DraftedPick(1, 10, 10, 50L, "P"), new DraftedPick(1, 2, 2, 51L, "Q"));
        List<WeekRow> w = List.of(
                row(1, 8, "P=30.0", "P"), row(1, 11, "Q=10.0", "Q"),
                row(2, 8, "P=10.0", "P"), row(2, 11, "Q=40.0", ""));
        Inputs in = new Inputs(2026, w, moves, List.of(t), drafted, true,
                Map.of(10, 51L, 2, 50L), Map.of(8, 50L, 11, 51L), rangeSet(1, 2), false);
        Evaluated e = OwnershipTimeline.evaluate(in).getFirst();
        PickAsset got = (PickAsset) side(e, 8).assets().getFirst();
        assertEquals(Status.COUNTED, got.status());
        assertEquals(10, got.pickNo());
        assertEquals("P", got.player().playerKey());
        assertEquals(40.0, got.player().startedPoints(), 1e-9);
        assertEquals(State.WINNER, e.verdict().state());
        assertEquals(8, e.verdict().winnerRosterId());
        assertEquals(30.0, e.verdict().margin(), 1e-9, "P started 40; Q started 10 (week 2 on the bench)");
    }

    @Test
    void aPickWhoseDrafterIsNotTheReceiverIsNotMatchedNeverGuessed() {
        Trade t = new Trade("tp", 1, Instant.parse(AT), Map.of(), Map.of(),
                List.of(new PickRef(2026, 1, 11, 8)), List.of(), true);
        List<Move> moves = List.of(move("tp", "TRADE", 1, AT, ids(), ids()));
        // Slot 10 was drafted by manager 49, not roster 8's manager 50.
        Inputs in = new Inputs(2026, List.of(row(1, 8, "P=30.0", "P")), moves, List.of(t),
                List.of(new DraftedPick(1, 10, 10, 49L, "P")),
                true, Map.of(10, 51L), Map.of(8, 50L, 11, 51L), rangeSet(1, 1), false);
        PickAsset got = (PickAsset) OwnershipTimeline.evaluate(in).getFirst().sides().getFirst().assets().getFirst();
        assertEquals(Status.PICK_NOT_MATCHED, got.status());
        assertNull(got.player());
        assertNull(got.pickNo());
    }

    @Test
    void aFutureSeasonsPickOrAnUnfinishedDraftIsNotDraftedYet() {
        List<Move> moves = List.of(move("tp", "TRADE", 1, AT, ids(), ids()));
        Trade future = new Trade("tp", 1, Instant.parse(AT), Map.of(), Map.of(),
                List.of(new PickRef(2026, 1, 11, 8)), List.of(), true);
        Inputs in = new Inputs(2025, List.of(), moves, List.of(future), List.of(), true,
                Map.of(), Map.of(8, 50L, 11, 51L), Set.of(), false);
        assertEquals(Status.NOT_DRAFTED_YET,
                ((PickAsset) OwnershipTimeline.evaluate(in).getFirst().sides().getFirst().assets().getFirst()).status(),
                "a 2026 pick traded in the 2025 season");

        Trade now = new Trade("tp", 1, Instant.parse(AT), Map.of(), Map.of(),
                List.of(new PickRef(2025, 1, 11, 8)), List.of(), true);
        Inputs unfinished = new Inputs(2025, List.of(), moves, List.of(now), List.of(), false,
                Map.of(), Map.of(8, 50L, 11, 51L), Set.of(), false);
        assertEquals(Status.NOT_DRAFTED_YET,
                ((PickAsset) OwnershipTimeline.evaluate(unfinished).getFirst().sides().getFirst().assets().getFirst()).status());
    }

    // ====================================================================================
    // Part two: real rows
    // ====================================================================================

    /** NBA 816: leg 3, accepted in week 6. Weeks 3-5 are on the giver, so the windows start at week 6. */
    @Test
    void nba816WindowsStartAtWeekSixNotTheProposalLeg() {
        Evaluated e = only(complete(weeks816(), moves816(), "1292031786481614848"));
        PlayerAsset p2449 = player(e, 12, "2449");   // 2449 went 2 -> 12
        PlayerAsset p2566 = player(e, 2, "2566");    // 2566 went 12 -> 2
        assertWindow(p2449, 6, 21);
        assertWindow(p2566, 6, 16);
        assertCounted(p2449, 6, 21, 346.0, 46.0);
        // 2566 sat on roster 2's bench in week 6 (15.0): the FIRST window week, unstarted, is dropped (F4).
        assertCounted(p2566, 7, 16, 170.5, 52.0);
        assertEquals(State.WINNER, e.verdict().state());
        assertEquals(12, e.verdict().winnerRosterId());
        assertEquals(175.5, e.verdict().margin(), 0.05);
    }

    /** NBA 704: leg 1. Week 1 has both rosters listing all three players, each started by the GIVER: windows start at week 2. */
    @Test
    void nba704WindowsStartAtWeekTwoBecauseWeekOneBelongsToTheStartingGiver() {
        Evaluated e = only(complete(weeks704(), moves704(), "1286718069925756928"));
        assertWindow(player(e, 4, "1830"), 2, 21);
        assertWindow(player(e, 4, "2012"), 2, 13);
        assertWindow(player(e, 9, "1845"), 2, 12);
        assertCounted(player(e, 4, "1830"), 3, 21, 544.5, 7.5);
        assertCounted(player(e, 4, "2012"), 2, 13, 47.0, 46.0);
        assertCounted(player(e, 9, "1845"), 3, 12, 323.5, 21.0);
        assertEquals(State.WINNER, e.verdict().state());
        assertEquals(4, e.verdict().winnerRosterId());
        assertEquals(268.0, e.verdict().margin(), 0.05);
    }

    /** NBA 1426 then 1804 swapped the same two players back: windows of week 15, then weeks 16-19. */
    @Test
    void nba1426And1804AreTwoTradesEachGradedOnItsOwnWindow() {
        Map<String, Evaluated> es = complete(weeks1426(), moves1426(), "1318150714127585280", "1324111353627049984");
        Evaluated first = es.get("1318150714127585280");
        Evaluated second = es.get("1324111353627049984");

        assertWindow(player(first, 1, "1589"), 15, 15);
        assertCounted(player(first, 1, "1589"), 15, 15, 30.5, 0.0);
        // 2156 sat unstarted at 0.0 on roster 3 in week 15 only, then 1804 sent him back: the window exists, nothing counts.
        PlayerAsset p2156 = player(first, 3, "2156");
        assertWindow(p2156, 15, 15);
        assertEquals(Status.UNDONE, p2156.status());
        assertEquals(State.WINNER, first.verdict().state());
        assertEquals(1, first.verdict().winnerRosterId());

        assertWindow(player(second, 3, "1589"), 16, 19);
        // Expectation changed from 18-19 / bench 0.0 because plan-review F4 was NARROWED (only the first
        // window week is dropped), not to make a test pass: week 17 (33.0, unstarted) is now bench.
        assertCounted(player(second, 3, "1589"), 17, 19, 42.5, 33.0);
        assertWindow(player(second, 1, "2156"), 16, 19);
        assertCounted(player(second, 1, "2156"), 17, 19, 0.0, 13.0);
        assertEquals(3, second.verdict().weeksCounted());
    }

    /**
     * NBA 1753 (accepted 02-05) was undone before any week was scored (commissioner reversal, then 1720 on 02-07).
     * In the scored rows neither player ever moved: 2316 sits on roster 11 and 1380 on roster 1 for weeks 1-21.
     * So 1753's assets are UNDONE, and 1720 (which only restored the status quo) credits nobody: both assets are
     * ALREADY_HELD and the verdict is RESTORED (plan-review F8). The earlier "+62.0 over 6 weeks" was a false claim.
     */
    @Test
    void nba1753IsUndoneAndNba1720OnlyRestoredTheStatusQuo() {
        Map<String, Evaluated> es = complete(weeks1753(), moves1753(), "1325241509389295616", "1325944237992185856");
        Evaluated undone = es.get("1325241509389295616");
        assertEquals(Status.UNDONE, player(undone, 11, "1380").status());
        assertEquals(Status.UNDONE, player(undone, 1, "2316").status());
        assertNull(player(undone, 11, "1380").windowFirst(), "no window: neither player ever sat on his new roster");
        assertEquals(0, undone.verdict().weeksCounted());
        assertEquals(State.UNDONE, undone.verdict().state());

        Evaluated redo = es.get("1325944237992185856");
        PlayerAsset giannis = player(redo, 1, "1380");
        PlayerAsset reaves = player(redo, 11, "2316");
        assertEquals(Status.ALREADY_HELD, giannis.status());
        assertEquals(Status.ALREADY_HELD, reaves.status());
        for (PlayerAsset a : List.of(giannis, reaves)) {
            assertTrue(a.countedWeeks().isEmpty(), a.playerKey());
            assertEquals(0.0, a.startedPoints(), 1e-9);
            assertEquals(0.0, a.benchPoints(), 1e-9);
        }
        assertEquals(State.RESTORED, redo.verdict().state());
        assertNull(redo.verdict().winnerRosterId());
        assertNull(redo.verdict().margin());
        assertEquals(0, redo.verdict().weeksCounted());
    }

    /** F7: a finished-season trade where one asset left before playing and the other never played counts nothing, for neither reason. */
    @Test
    void aTradeWhoseAssetsAreLeftAndNeverPlayedIsNoneCounted() {
        // A goes to roster 1, then roster 1 drops him; B goes to roster 2 but never appears on any scored row.
        List<Move> moves = List.of(
                move("t1", "TRADE", 2, AT, ids("A", 1, "B", 2), ids("A", 2, "B", 1)),
                move("t2", "FREE_AGENT", 2, "2025-10-02T00:00:00Z", ids(), ids("A", 1)));
        List<WeekRow> w = List.of(row(1, 2, "A=5.0", "A"), row(1, 1, "B=5.0", "B"),
                row(2, 2, "A=0.0", ""), row(3, 1, "X=1.0", ""));
        Evaluated e = OwnershipTimeline.evaluate(inputs(w, moves, List.of(tradeOf(moves, "t1")), rangeSet(1, 3), true)).getFirst();
        assertEquals(Status.LEFT_BEFORE_PLAYING, player(e, 1, "A").status());
        assertEquals(Status.NEVER_PLAYED_FOR_THEM, player(e, 2, "B").status());
        assertEquals(State.NONE_COUNTED, e.verdict().state());
    }

    /** NFL 2403 (leg 5) and 2428 (leg 6): weeks 5-6, then from week 7. Two trades, each graded on its own window. */
    @Test
    void nfl2403And2428CountWeeksFiveToSixThenFromWeekSeven() {
        Map<String, Evaluated> es = complete(weeks2403(), moves2403(), "1279146147700543488", "1283514796255232000");
        Evaluated first = es.get("1279146147700543488");
        Evaluated second = es.get("1283514796255232000");

        assertWindow(player(first, 7, "5870"), 5, 6);
        assertWindow(player(first, 7, "CLE"), 5, 6);
        assertWindow(player(first, 10, "DET"), 5, 17);
        assertCounted(player(first, 7, "5870"), 5, 6, 40.16, 0.0);
        assertCounted(player(first, 7, "CLE"), 5, 6, 10.0, 0.0);
        assertEquals(State.WINNER, first.verdict().state());
        assertEquals(7, first.verdict().winnerRosterId());
        assertEquals(15.2, first.verdict().margin(), 0.05);

        assertWindow(player(second, 10, "5870"), 7, 15);
        assertWindow(player(second, 10, "CLE"), 7, 17);
        assertWindow(player(second, 10, "7021"), 7, 17);
        assertWindow(player(second, 7, "PIT"), 7, 8);
        assertWindow(player(second, 7, "8155"), 7, 17);
        assertWindow(player(second, 7, "11563"), 7, 11);
        // 5870 sat unstarted in week 7 (22.22, listed only): the window starts at 7, counting at his first start, week 8.
        assertCounted(player(second, 10, "5870"), 8, 15, 75.04, 16.5);
        // A negative started week (PIT -3.0 in week 8) is in the total.
        assertCounted(player(second, 7, "PIT"), 7, 8, -2.0, 0.0);
        assertEquals(262.7, side(second, 10).startedTotal(), 0.05);
        assertEquals(239.3, side(second, 7).startedTotal(), 0.05);
        assertEquals(10, second.sides().getFirst().rosterId());
        assertEquals(23.4, second.verdict().margin(), 0.05);
    }

    /** NFL 2464: 4039 never played for roster 1 (dropped), and 5012's week-7 row is a listed-unstarted boundary week. */
    @Test
    void nfl2464HasALeftBeforePlayingAssetAndNeverCountsTheBoundaryWeek() {
        Evaluated e = only(complete(weeks2464(), moves2464(), "1286111781520965632"));
        PlayerAsset p4039 = player(e, 1, "4039");
        assertNull(p4039.windowFirst(), "week 7 has him on the giver, then roster 9 picks him up");
        assertEquals(Status.LEFT_BEFORE_PLAYING, p4039.status(), "roster 1 dropped him; that is not 'undone'");

        PlayerAsset p5012 = player(e, 3, "5012");
        assertWindow(p5012, 7, 7);
        assertEquals(Status.LEFT_BEFORE_PLAYING, p5012.status());
        assertTrue(p5012.countedWeeks().isEmpty());

        assertCounted(player(e, 1, "12529"), 8, 17, 141.7, 0.0);
        assertCounted(player(e, 3, "11603"), 9, 12, 15.3, 17.4);
        assertEquals(State.WINNER, e.verdict().state());
        assertEquals(1, e.verdict().winnerRosterId());
        assertEquals(126.4, e.verdict().margin(), 0.05);
    }

    /** NBA 766: roster 8 paid $67 FAAB for 2334 and dropped him before he ever scored; roster 1's side holds only FAAB. */
    @Test
    void nba766IsNotComparableWithAFaabAssetAndALeftBeforePlayingPlayer() {
        List<Move> moves = moves766();
        Move m = moves.stream().filter(x -> x.type().equals("TRADE")).findFirst().orElseThrow();
        Trade t = new Trade(m.txId(), m.leg(), m.acceptedAt(), m.adds(), m.drops(), List.of(),
                List.of(new FaabRef(8, 1, 67)), true);   // waiver_budget [{sender: 8, receiver: 1, amount: 67}]
        Evaluated e = OwnershipTimeline.evaluate(inputs(weeks766(), moves, List.of(t), WEEKS_1_TO_21, true)).getFirst();
        assertEquals(Status.LEFT_BEFORE_PLAYING, player(e, 8, "2334").status());
        FaabAsset faab = (FaabAsset) side(e, 1).assets().getFirst();
        assertEquals(67, faab.amount());
        assertEquals(8, faab.fromRosterId());
        assertEquals(State.NOT_COMPARABLE, e.verdict().state());
    }

    /** NBA 8044: neither side started what it got. EVEN, with weeks counted, so the page can say so in words. */
    @Test
    void nba8044IsEvenBecauseNeitherSideStartedWhatItGot() {
        Evaluated e = only(complete(weeks8044(), moves8044(), "1314493099052924928"));
        assertEquals(State.EVEN, e.verdict().state());
        assertEquals(0.0, side(e, 2).startedTotal(), 1e-9);
        assertEquals(0.0, side(e, 10).startedTotal(), 1e-9);
        assertEquals(9, e.verdict().weeksCounted(), "2456's listed weeks 13-21; week 12 is the possible boundary week");
        assertEquals(Status.LEFT_BEFORE_PLAYING, player(e, 2, "2144").status());
    }

    /**
     * The real 2026 NBA picks trade (draft 230): roster 11's R1/R2/R9 (slot 10) went to roster 8, and roster 8's R1
     * (slot 2) went to roster 11. Resolved through slot_to_manager and checked against the drafter. No week is scored yet.
     */
    @Test
    void nba2026PicksTradeResolvesToTheDraftedPlayersAndIsNoWeekYet() {
        Map<Integer, Long> slotToManager = new HashMap<>();
        long[] mgr = {49, 50, 48, 45, 7, 32, 588, 55, 46, 51, 43, 52};
        for (int i = 0; i < 12; i++) slotToManager.put(i + 1, mgr[i]);
        List<DraftedPick> drafted = List.of(
                new DraftedPick(1, 10, 10, 50L, "2308"),    // Sengun
                new DraftedPick(2, 10, 15, 50L, "2161"),    // Haliburton
                new DraftedPick(9, 10, 99, 50L, "2445"),    // Jabari Smith
                new DraftedPick(1, 2, 2, 51L, "2577"));     // Wembanyama
        Trade t = new Trade("1346741394630250496", 1, Instant.parse("2026-04-09T08:11:26.572Z"), Map.of(), Map.of(),
                List.of(new PickRef(2026, 1, 11, 8), new PickRef(2026, 2, 11, 8), new PickRef(2026, 9, 11, 8),
                        new PickRef(2026, 1, 8, 11)),
                List.of(), true);
        List<Move> moves = List.of(move(t.txId(), "TRADE", 1, "2026-04-09T08:11:26.572Z", ids(), ids()));
        Inputs in = new Inputs(2026, List.of(), moves, List.of(t), drafted, true, slotToManager,
                Map.of(8, 50L, 11, 51L), Set.of(), false);
        Evaluated e = OwnershipTimeline.evaluate(in).getFirst();

        List<Asset> toEight = side(e, 8).assets();
        assertEquals(3, toEight.size());
        assertEquals(List.of("2308", "2161", "2445"), toEight.stream().map(a -> ((PickAsset) a).player().playerKey()).toList());
        assertEquals(List.of(10, 15, 99), toEight.stream().map(a -> ((PickAsset) a).pickNo()).toList());
        PickAsset wemby = (PickAsset) side(e, 11).assets().getFirst();
        assertEquals("2577", wemby.player().playerKey());
        for (Side s : e.sides()) {
            for (Asset a : s.assets()) assertEquals(Status.NO_WEEK_YET, ((PickAsset) a).status(), "no scored week yet");
        }
        assertEquals(State.NO_WEEK_YET, e.verdict().state());
    }


    // ====================================================================================
    // US2: stretches and reconciliation (T017)
    // ====================================================================================

    private static Inputs recon(List<WeekRow> weeks, List<Move> moves, List<DraftedPick> drafted, Map<Integer, Long> managers) {
        return new Inputs(2025, weeks, moves, List.of(), drafted, true, Map.of(), managers, rangeSet(1, 21), true);
    }

    private static Reconciliation reconcile(Inputs in) {
        return OwnershipTimeline.analyze(in, k -> "Player " + k, r -> "Roster " + r).reconciliation();
    }

    /** Real NFL 2483 shape: player 9225 added by roster 2 at leg 8, dropped by WAIVER 2519 at leg 9, never on a scored roster. */
    @Test
    void aWaiverDroppedTheNextLegIsNeverOnAScoredRosterAndNamesTheNextMove() {
        List<Move> moves = List.of(
                move("2327", "FREE_AGENT", 2, "2025-09-10T12:42:03.253Z", ids(), ids("9225", 1)),
                move("2483", "WAIVER", 8, "2025-10-29T07:13:33.825Z", ids("9225", 2), ids()),
                move("2519", "WAIVER", 9, "2025-11-05T08:13:33.707Z", ids(), ids("9225", 2)));
        List<WeekRow> w = List.of(row(1, 1, "9225=1.2", ""), row(8, 3, "X=1.0", ""), row(9, 3, "X=1.0", ""));
        Reconciliation r = reconcile(recon(w, moves,
                List.of(new DraftedPick(5, 1, 50, 7L, "9225"), new DraftedPick(1, 2, 2, 9L, "X")), Map.of(1, 7L, 3, 9L)));
        assertEquals(0, r.unexplainedStretches(), "week 1 is the draft pick of roster 1's manager");
        assertEquals(1, r.neverOnAScoredRoster());
        assertEquals(0, r.sameWeekInAndOut());
        assertEquals(1, r.examples().size());
        assertTrue(r.examples().getFirst().contains("Player 9225") && r.examples().getFirst().contains("Roster 2")
                && r.examples().getFirst().contains("WAIVER in week 8") && r.examples().getFirst().contains("WAIVER in week 9"),
                r.examples().getFirst());
    }

    /** A free-agent stream added and dropped within one leg is expected, counted separately, and never an "error". */
    @Test
    void anAddAndDropInTheSameLegCountsOnlyAsSameWeekInAndOut() {
        List<Move> moves = List.of(
                move("a", "FREE_AGENT", 3, "2025-10-10T10:00:00Z", ids("S", 1), ids()),
                move("b", "FREE_AGENT", 3, "2025-10-10T11:00:00Z", ids(), ids("S", 1)));
        Reconciliation r = reconcile(recon(List.of(row(3, 2, "X=1.0", ""), row(4, 2, "X=1.0", "")), moves,
                List.of(new DraftedPick(1, 1, 1, 9L, "X")), Map.of(2, 9L)));
        assertEquals(1, r.sameWeekInAndOut());
        assertEquals(0, r.neverOnAScoredRoster());
        assertEquals(0, r.unexplainedStretches());
        assertTrue(r.examples().isEmpty());
    }

    @Test
    void aRosterChangeWithNoDraftOrMoveBehindItIsUnexplained() {
        Analysis a = OwnershipTimeline.analyze(recon(List.of(row(2, 2, "Z=9.0", "Z")), List.of(), List.of(), Map.of()),
                k -> k, r -> "R" + r);
        assertEquals(1, a.reconciliation().unexplainedStretches());
        assertEquals(How.UNEXPLAINED, a.stretchesByPlayer().get("Z").getFirst().how());
    }

    @Test
    void stretchesNameHowEachBeganAndSumFinalWeekPoints() {
        // Drafted by roster 1's manager (weeks 1-2), traded to roster 2 at leg 3 (weeks 3-4, bench in week 4).
        List<Move> moves = List.of(move("trade1", "TRADE", 3, AT, ids("P", 2), ids("P", 1)));
        List<WeekRow> w = List.of(
                row(1, 1, "P=10.0", "P"), row(2, 1, "P=5.0", "P"),
                row(3, 2, "P=7.0", "P"), row(4, 2, "P=3.0", ""));
        Analysis a = OwnershipTimeline.analyze(
                recon(w, moves, List.of(new DraftedPick(1, 1, 1, 7L, "P")), Map.of(1, 7L, 2, 8L)), k -> k, r -> "R" + r);
        List<Stretch> st = a.stretchesByPlayer().get("P");
        assertEquals(2, st.size());
        assertEquals(How.DRAFT, st.get(0).how());
        assertNull(st.get(0).viaTxId());
        assertEquals(15.0, st.get(0).startedPoints(), 1e-9);
        assertEquals(How.TRADE, st.get(1).how());
        assertEquals("trade1", st.get(1).viaTxId());
        assertEquals(7.0, st.get(1).startedPoints(), 1e-9);
        assertEquals(3.0, st.get(1).benchPoints(), 1e-9);
        assertFalse(st.get(1).leadingUnstarted(), "started in his first week there");
        assertEquals(0, a.reconciliation().unexplainedStretches());
        assertEquals(4, a.reconciliation().playerWeeksChecked());
    }

    @Test
    void aDuplicateWeekAndALeadingUnstartedWeekAreFlaggedOnTheStretch() {
        List<Move> moves = List.of(move("t", "TRADE", 2, AT, ids("A", 1), ids("A", 2)));
        List<WeekRow> w = List.of(
                row(1, 2, "A=1.0", "A"),
                row(2, 2, "A=9.0", ""), row(2, 1, "A=9.0", ""),   // duplicate: nobody started him
                row(3, 1, "A=4.0", "A"));
        Analysis a = OwnershipTimeline.analyze(
                recon(w, moves, List.of(new DraftedPick(1, 1, 1, 7L, "A")), Map.of(1, 8L, 2, 7L)), k -> k, r -> "R" + r);
        Stretch onOne = a.stretchesByPlayer().get("A").stream().filter(x -> x.rosterId() == 1).findFirst().orElseThrow();
        assertEquals(2, onOne.firstWeek());
        assertEquals(3, onOne.lastWeek());
        assertEquals(List.of(2), onOne.duplicateWeeks());
        assertTrue(onOne.leadingUnstarted());
        assertEquals(4.0, onOne.startedPoints(), 1e-9);
        assertEquals(9.0, onOne.benchPoints(), 1e-9);
    }

    // ====================================================================================
    // Real-row fixtures (generated from the local database; the SQL is in the class comment)
    // ====================================================================================

    // case 816 211 ['2449', '2566']
    private static List<WeekRow> weeks816() {
        return List.of(
        row(1, 2, "2449=11.5", ""),
        row(1, 12, "2566=30.5", "2566"),
        row(2, 2, "2449=29.0", "2449"),
        row(2, 12, "2566=28.5", "2566"),
        row(3, 2, "2449=28.0", "2449"),
        row(3, 12, "2566=20.5", "2566"),
        row(4, 2, "2449=28.5", ""),
        row(4, 12, "2566=0.0", ""),
        row(5, 2, "2449=22.0", "2449"),
        row(5, 12, "2566=12.0", ""),
        row(6, 2, "2566=15.0", ""),
        row(6, 12, "2449=26.0", "2449"),
        row(7, 2, "2566=21.5", "2566"),
        row(7, 12, "2449=13.0", ""),
        row(8, 2, "2566=18.0", ""),
        row(8, 12, "2449=27.0", "2449"),
        row(9, 2, "2566=23.0", "2566"),
        row(9, 12, "2449=25.0", "2449"),
        row(10, 2, "2566=32.0", "2566"),
        row(10, 12, "2449=24.5", "2449"),
        row(11, 2, "2566=15.0", "2566"),
        row(11, 12, "2449=29.5", "2449"),
        row(12, 2, "2566=24.5", "2566"),
        row(12, 12, "2449=28.5", "2449"),
        row(13, 2, "2566=18.5", ""),
        row(13, 12, "2449=0.0", ""),
        row(14, 2, "2566=15.5", ""),
        row(14, 12, "2449=35.5", "2449"),
        row(15, 2, "2566=26.0", "2566"),
        row(15, 12, "2449=28.5", "2449"),
        row(16, 2, "2566=28.5", "2566"),
        row(16, 12, "2449=17.5", ""),
        row(17, 12, "2449=15.5", ""),
        row(18, 4, "2566=19.0", ""),
        row(18, 12, "2449=27.5", "2449"),
        row(19, 4, "2566=23.5", "2566"),
        row(19, 12, "2449=34.0", "2449"),
        row(20, 4, "2566=29.0", "2566"),
        row(20, 12, "2449=28.0", "2449"),
        row(21, 4, "2566=9.0", "2566"),
        row(21, 12, "2449=32.0", "2449")
        );
    }

    private static List<Move> moves816() {
        return List.of(
        move("1292031786481614848", "TRADE", 3, "2025-11-28T02:36:13.417Z", ids("2449", 12, "2566", 2), ids("2449", 2, "2566", 12)), // id 816
        move("1327765480609419264", "FREE_AGENT", 17, "2026-02-12T20:01:08.568Z", ids(), ids("2566", 2)), // id 1836
        move("1327766981914406912", "WAIVER", 17, "2026-02-14T19:08:13.636Z", ids("2566", 4), ids()) // id 1834
        );
    }

    // case 704 211 ['1830', '1845', '2012']
    private static List<WeekRow> weeks704() {
        return List.of(
        row(1, 4, "1830=27.0,1845=30.5,2012=20.0", "1845"),
        row(1, 9, "1830=27.0,1845=30.5,2012=20.0", "1830,2012"),
        row(2, 4, "1830=11.0,2012=10.5", "2012"),
        row(2, 9, "1845=32.5", ""),
        row(3, 4, "1830=28.0,2012=0.0", "1830"),
        row(3, 9, "1845=40.0", "1845"),
        row(4, 4, "1830=32.0,2012=0.0", "1830"),
        row(4, 9, "1845=41.5", "1845"),
        row(5, 4, "1830=30.0,2012=0.0", "1830"),
        row(5, 9, "1845=29.0", "1845"),
        row(6, 4, "1830=32.5,2012=0.0", "1830"),
        row(6, 9, "1845=30.5", "1845"),
        row(7, 4, "1830=33.5,2012=0.0", "1830"),
        row(7, 9, "1845=37.5", "1845"),
        row(8, 4, "1830=7.5,2012=19.5", "2012"),
        row(8, 9, "1845=37.0", "1845"),
        row(9, 4, "1830=39.0,2012=16.5", "1830"),
        row(9, 9, "1845=21.0", ""),
        row(10, 4, "1830=27.0,2012=4.5", "1830"),
        row(10, 9, "1845=39.0", "1845"),
        row(11, 4, "1830=40.5,2012=17.0", "1830,2012"),
        row(11, 9, "1845=40.0", "1845"),
        row(12, 4, "1830=36.0,2012=13.5", "1830"),
        row(12, 9, "1845=29.0", "1845"),
        row(13, 4, "1830=28.0,2012=11.5", "1830"),
        row(13, 12, "1845=37.0", "1845"),
        row(14, 4, "1830=27.0", "1830"),
        row(14, 12, "1845=33.0", "1845"),
        row(15, 4, "1830=26.0", "1830"),
        row(15, 12, "1845=33.0", "1845"),
        row(16, 4, "1830=28.5", "1830"),
        row(16, 12, "1845=36.0", "1845"),
        row(17, 4, "1830=26.5", "1830"),
        row(17, 12, "1845=36.5", "1845"),
        row(18, 4, "1830=25.0", "1830"),
        row(18, 8, "2012=24.0", "2012"),
        row(18, 12, "1845=23.0", "1845"),
        row(19, 4, "1830=36.0", "1830"),
        row(19, 8, "2012=0.0", ""),
        row(19, 12, "1845=0.0", ""),
        row(20, 4, "1830=27.0", "1830"),
        row(20, 12, "1845=34.5", "1845"),
        row(21, 4, "1830=22.0", "1830"),
        row(21, 12, "1845=23.5", "1845")
        );
    }

    private static List<Move> moves704() {
        return List.of(
        move("1286718069925756928", "TRADE", 1, "2025-10-27T05:03:17.828Z", ids("1830", 4, "1845", 9, "2012", 4), ids("1830", 9, "1845", 4, "2012", 9)), // id 704
        move("1316305294548025344", "TRADE", 12, "2026-01-12T20:06:09.016Z", ids("1845", 12), ids("1845", 9)), // id 7986
        move("1319843490967846912", "FREE_AGENT", 14, "2026-01-21T23:21:59.055Z", ids(), ids("2012", 4)), // id 1535
        move("1331067875074527232", "FREE_AGENT", 18, "2026-02-21T22:43:40.765Z", ids("2012", 8), ids()), // id 1967
        move("1335746846764593152", "FREE_AGENT", 20, "2026-03-06T20:36:14.546Z", ids(), ids("2012", 8)) // id 2151
        );
    }

    // case 1426 211 ['1589', '2156']
    private static List<WeekRow> weeks1426() {
        return List.of(
        row(1, 10, "1589=9.0", ""),
        row(2, 10, "1589=14.0", ""),
        row(3, 12, "1589=16.0", ""),
        row(4, 12, "1589=11.0", ""),
        row(5, 12, "1589=20.0", "1589"),
        row(7, 3, "1589=20.0", ""),
        row(8, 3, "1589=28.0", "1589"),
        row(9, 3, "1589=32.5", "1589"),
        row(10, 3, "1589=27.5", "1589"),
        row(11, 3, "1589=14.5,2156=29.5", "2156"),
        row(12, 1, "2156=15.5", ""),
        row(12, 3, "1589=21.0", "1589"),
        row(13, 1, "2156=37.0", "2156"),
        row(13, 3, "1589=18.0", ""),
        row(14, 1, "2156=17.0", ""),
        row(14, 3, "1589=22.5", "1589"),
        row(15, 1, "1589=30.5", "1589"),
        row(15, 3, "2156=0.0", ""),
        row(16, 1, "2156=0.0", ""),
        row(16, 3, "1589=18.0", ""),
        row(17, 1, "2156=0.0", ""),
        row(17, 3, "1589=33.0", ""),
        row(18, 1, "2156=13.0", ""),
        row(18, 3, "1589=25.0", "1589"),
        row(19, 1, "2156=0.0", ""),
        row(19, 3, "1589=17.5", "1589"),
        row(21, 3, "1589=35.5", "")
        );
    }

    private static List<Move> moves1426() {
        return List.of(
        move("1291634394901004288", "FREE_AGENT", 3, "2025-11-05T03:09:06.452Z", ids(), ids("1589", 10)), // id 824
        move("1293132534967701504", "FREE_AGENT", 3, "2025-11-09T06:22:10.878Z", ids("1589", 12), ids()), // id 800
        move("1293285624333242368", "FREE_AGENT", 3, "2025-11-09T16:30:30.227Z", ids(), ids("1589", 12)), // id 793
        move("1293336346445778944", "FREE_AGENT", 3, "2025-11-09T19:52:03.321Z", ids("1589", 12), ids()), // id 792
        move("1300653049584304128", "FREE_AGENT", 6, "2025-11-30T00:26:01.287Z", ids(), ids("1589", 12)), // id 972
        move("1302443880263729152", "FREE_AGENT", 7, "2025-12-04T23:02:08.594Z", ids("1589", 3), ids()), // id 1015
        move("1311510083301031936", "FREE_AGENT", 11, "2025-12-29T23:27:59.839Z", ids("2156", 1), ids()), // id 1317
        move("1311643380987494400", "FREE_AGENT", 11, "2025-12-30T08:17:40.484Z", ids(), ids("2156", 1)), // id 1302
        move("1311985226011901952", "FREE_AGENT", 11, "2025-12-31T06:56:02.689Z", ids("2156", 3), ids()), // id 1290
        move("1314459947144052736", "FREE_AGENT", 12, "2026-01-07T02:49:42.185Z", ids(), ids("2156", 3)), // id 8045
        move("1315345780667604992", "FREE_AGENT", 12, "2026-01-09T13:29:41.355Z", ids("2156", 1), ids()), // id 8023
        move("1318150714127585280", "TRADE", 13, "2026-01-26T18:09:44.396Z", ids("1589", 1, "2156", 3), ids("1589", 3, "2156", 1)), // id 1426
        move("1324111353627049984", "TRADE", 16, "2026-02-03T21:02:00.033Z", ids("1589", 3, "2156", 1), ids("1589", 1, "2156", 3)), // id 1804
        move("1336255311366852608", "FREE_AGENT", 20, "2026-03-08T06:16:41.954Z", ids(), ids("1589", 3)), // id 2132
        move("1336430059367436288", "FREE_AGENT", 20, "2026-03-08T17:51:05.124Z", ids(), ids("2156", 1)), // id 2130
        move("1336936473609400320", "WAIVER", 21, "2026-03-10T05:18:27.824Z", ids("1589", 3), ids()), // id 2276
        move("1336888652919226368", "WAIVER", 21, "2026-03-10T16:58:31.016Z", ids("2156", 1), ids()), // id 2278
        move("1338406998961573888", "FREE_AGENT", 21, "2026-03-14T04:46:44.253Z", ids(), ids("2156", 1)) // id 2236
        );
    }

    // case 1753 211 ['1380', '2316']
    private static List<WeekRow> weeks1753() {
        return List.of(
        row(1, 1, "1380=44.5", "1380"),
        row(1, 11, "2316=26.5", "2316"),
        row(2, 1, "1380=37.5", "1380"),
        row(2, 11, "2316=37.5", "2316"),
        row(3, 1, "1380=53.0", "1380"),
        row(3, 11, "2316=0.0", ""),
        row(4, 1, "1380=40.5", "1380"),
        row(4, 11, "2316=28.0", "2316"),
        row(5, 1, "1380=0.0", ""),
        row(5, 11, "2316=26.5", "2316"),
        row(6, 1, "1380=39.0", "1380"),
        row(6, 11, "2316=33.5", "2316"),
        row(7, 1, "1380=0.0", ""),
        row(7, 11, "2316=38.5", "2316"),
        row(8, 1, "1380=0.0", ""),
        row(8, 11, "2316=22.5", ""),
        row(9, 1, "1380=0.0", ""),
        row(9, 11, "2316=0.0", ""),
        row(10, 1, "1380=26.0", "1380"),
        row(10, 11, "2316=0.0", ""),
        row(11, 1, "1380=29.5", "1380"),
        row(11, 11, "2316=0.0", ""),
        row(12, 1, "1380=41.0", "1380"),
        row(12, 11, "2316=0.0", ""),
        row(13, 1, "1380=18.0", ""),
        row(13, 11, "2316=0.0", ""),
        row(14, 1, "1380=36.5", "1380"),
        row(14, 11, "2316=0.0", ""),
        row(15, 1, "1380=0.0", ""),
        row(15, 11, "2316=0.0", ""),
        row(16, 1, "1380=0.0", ""),
        row(16, 11, "2316=22.0", "2316"),
        row(17, 1, "1380=0.0", ""),
        row(17, 11, "2316=17.5", "2316"),
        row(18, 1, "1380=0.0", ""),
        row(18, 11, "2316=25.5", "2316"),
        row(19, 1, "1380=0.0", ""),
        row(19, 11, "2316=14.0", "2316"),
        row(20, 1, "1380=36.5", "1380"),
        row(20, 11, "2316=26.5", "2316"),
        row(21, 1, "1380=38.5", "1380"),
        row(21, 11, "2316=31.5", "2316")
        );
    }

    private static List<Move> moves1753() {
        return List.of(
        move("1325241509389295616", "TRADE", 16, "2026-02-05T22:23:12.249Z", ids("1380", 11, "2316", 1), ids("1380", 1, "2316", 11)), // id 1753
        move("1325288999224365056", "COMMISSIONER", 16, "2026-02-06T00:00:29.396Z", ids("1380", 1), ids("1380", 11)), // id 1746
        move("1325289219513421824", "COMMISSIONER", 16, "2026-02-06T00:01:21.916Z", ids("1380", 11), ids("1380", 1)), // id 1745
        move("1325944237992185856", "TRADE", 16, "2026-02-07T23:25:47.685Z", ids("1380", 1, "2316", 11), ids("1380", 11, "2316", 1)) // id 1720
        );
    }

    // case 2403 5 ['5870', 'CLE', 'DET', 'PIT', '7021', '8155', '11563']
    private static List<WeekRow> weeks2403() {
        return List.of(
        row(1, 6, "DET=0.0", "DET"),
        row(1, 10, "11563=8.84,8155=16.5,PIT=2.0", "11563,8155,PIT"),
        row(2, 6, "DET=9.0", "DET"),
        row(2, 10, "11563=21.24,5870=22.84,8155=5.8,PIT=5.0", "8155,PIT"),
        row(3, 10, "11563=13.42,5870=15.82,8155=9.2,PIT=20.0", "11563,8155,PIT"),
        row(4, 7, "DET=20.0", "DET"),
        row(4, 10, "11563=26.74,5870=12.38,8155=16.1,PIT=12.0", "11563,8155,PIT"),
        row(5, 7, "5870=16.68,7021=32.4,CLE=9.0", "5870,7021,CLE"),
        row(5, 10, "11563=17.88,8155=17.5,DET=12.0,PIT=0.0", "8155,DET"),
        row(6, 7, "5870=23.48,7021=33.9,CLE=1.0", "5870,7021,CLE"),
        row(6, 10, "11563=13.36,8155=5.9,DET=3.0,PIT=10.0", "8155,PIT"),
        row(7, 7, "11563=39.96,8155=7.2,PIT=1.0", "11563,8155,PIT"),
        row(7, 10, "5870=22.22,7021=10.6,CLE=26.0,DET=14.0", "7021,CLE"),
        row(8, 7, "11563=24.78,8155=32.86,PIT=-3.0", "11563,8155,PIT"),
        row(8, 10, "5870=23.18,7021=5.4,CLE=11.0,DET=0.0", "5870,7021,CLE"),
        row(9, 7, "11563=19.52,8155=0.0", "11563"),
        row(9, 10, "5870=17.08,7021=28.1,CLE=0.0,DET=7.0", "5870,7021,DET"),
        row(10, 7, "11563=7.8,8155=19.5", "11563,8155"),
        row(10, 10, "5870=16.5,7021=15.3,CLE=5.0,DET=2.0", "7021,CLE"),
        row(11, 6, "PIT=22.0", "PIT"),
        row(11, 7, "11563=12.6,8155=8.4", "11563,8155"),
        row(11, 10, "5870=0.0,7021=15.0,CLE=16.0,DET=2.0", "7021,CLE"),
        row(12, 6, "PIT=12.0", ""),
        row(12, 7, "8155=13.9", "8155"),
        row(12, 10, "5870=16.84,7021=11.4,CLE=18.0,DET=4.0", "5870,7021,DET"),
        row(13, 6, "PIT=7.0", ""),
        row(13, 7, "8155=15.6", "8155"),
        row(13, 9, "11563=17.44", ""),
        row(13, 10, "5870=16.14,7021=9.9,CLE=1.0,DET=0.0", "5870,7021,DET"),
        row(14, 6, "PIT=4.0", "PIT"),
        row(14, 7, "8155=4.3", "8155"),
        row(14, 9, "11563=15.98", "11563"),
        row(14, 10, "5870=1.8,7021=0.0,CLE=2.0,DET=13.0", "5870,CLE"),
        row(15, 6, "PIT=7.0", "PIT"),
        row(15, 7, "8155=5.7", "8155"),
        row(15, 9, "11563=29.08", "11563"),
        row(15, 10, "5870=0.0,7021=12.4,CLE=2.0,DET=0.0", "7021,CLE"),
        row(16, 6, "PIT=6.0", ""),
        row(16, 7, "8155=8.3", "8155"),
        row(16, 9, "11563=16.18", "11563"),
        row(16, 10, "7021=8.3,CLE=2.0,DET=5.0", "7021,DET"),
        row(17, 6, "PIT=10.0", "PIT"),
        row(17, 7, "8155=20.9", "8155"),
        row(17, 9, "11563=20.48", ""),
        row(17, 10, "7021=9.3,CLE=12.0,DET=7.0", "7021,DET")
        );
    }

    private static List<Move> moves2403() {
        return List.of(
        move("1271825556857438208", "FREE_AGENT", 2, "2025-09-11T11:15:51.433Z", ids("5870", 10), ids()), // id 2321
        move("1273555702199123968", "WAIVER", 2, "2025-09-17T07:13:19.632Z", ids(), ids("DET", 6)), // id 2306
        move("1276228544099274752", "WAIVER", 3, "2025-09-24T07:13:07.873Z", ids("DET", 7), ids()), // id 2336
        move("1279082979548762112", "FREE_AGENT", 5, "2025-10-01T11:54:15.838Z", ids("CLE", 10), ids()), // id 2409
        move("1279146147700543488", "TRADE", 5, "2025-10-02T02:44:03.730Z", ids("CLE", 7, "DET", 10, "5870", 7), ids("CLE", 10, "DET", 7, "5870", 10)), // id 2403
        move("1279888795956707328", "FREE_AGENT", 5, "2025-10-03T17:16:17.441Z", ids("7021", 7), ids()), // id 2394
        move("1283514796255232000", "TRADE", 6, "2025-10-14T13:48:45.331Z", ids("CLE", 10, "PIT", 7, "5870", 10, "7021", 10, "8155", 7, "11563", 7), ids("CLE", 7, "PIT", 10, "5870", 7, "7021", 7, "8155", 10, "11563", 10)), // id 2428
        move("1289160501216956416", "FREE_AGENT", 9, "2025-10-29T07:18:44.236Z", ids(), ids("PIT", 7)), // id 2550
        move("1294377026546847744", "FREE_AGENT", 11, "2025-11-12T16:47:20.793Z", ids("PIT", 6), ids()), // id 2598
        move("1296312141912997888", "WAIVER", 11, "2025-11-19T08:13:53.751Z", ids(), ids("11563", 7)), // id 2587
        move("1301002137601331200", "FREE_AGENT", 13, "2025-11-30T23:33:10.356Z", ids("11563", 9), ids()), // id 2641
        move("1307561383545569280", "FREE_AGENT", 16, "2025-12-19T01:57:16.454Z", ids(), ids("5870", 10)) // id 2729
        );
    }

    // case 2464 5 ['4039', '5012', '11603', '12529']
    private static List<WeekRow> weeks2464() {
        return List.of(
        row(1, 1, "5012=1.5", "5012"),
        row(1, 3, "12529=11.1,4039=3.5", "12529"),
        row(2, 1, "5012=1.4", ""),
        row(2, 3, "12529=6.0,4039=16.0", "12529"),
        row(3, 1, "5012=27.1", ""),
        row(3, 3, "12529=7.7,4039=5.1", "4039"),
        row(4, 1, "5012=10.0", ""),
        row(4, 3, "12529=12.6,4039=6.6", "4039"),
        row(5, 1, "5012=4.2", ""),
        row(5, 3, "12529=4.7,4039=11.9", "12529"),
        row(6, 1, "11603=10.1,5012=6.6", ""),
        row(6, 3, "12529=4.6,4039=12.0", ""),
        row(7, 1, "11603=1.9", ""),
        row(7, 3, "12529=0.5,4039=3.2,5012=0.0", ""),
        row(8, 1, "12529=5.5", "12529"),
        row(8, 3, "11603=0.0", ""),
        row(8, 9, "4039=0.0", ""),
        row(9, 1, "12529=12.7", "12529"),
        row(9, 3, "11603=11.5", "11603"),
        row(9, 9, "4039=0.0,5012=16.6", ""),
        row(10, 1, "12529=28.0", "12529"),
        row(10, 3, "11603=0.3", ""),
        row(10, 9, "4039=9.4,5012=10.4", "5012"),
        row(11, 1, "12529=32.3", "12529"),
        row(11, 3, "11603=17.1", ""),
        row(11, 9, "4039=5.3,5012=15.7", "5012"),
        row(12, 1, "12529=11.1", "12529"),
        row(12, 3, "11603=3.8", "11603"),
        row(12, 9, "4039=4.4,5012=2.1", ""),
        row(13, 1, "12529=11.6", "12529"),
        row(13, 9, "4039=5.4,5012=8.7", ""),
        row(14, 1, "12529=0.0", ""),
        row(14, 9, "4039=11.5,5012=1.9", ""),
        row(15, 1, "12529=30.1", "12529"),
        row(15, 9, "4039=9.6,5012=3.8", ""),
        row(16, 1, "12529=2.2", "12529"),
        row(16, 9, "4039=6.9,5012=4.4", ""),
        row(17, 1, "12529=8.2", "12529"),
        row(17, 3, "11603=13.3", ""),
        row(17, 9, "4039=1.6,5012=6.8", "")
        );
    }

    private static List<Move> moves2464() {
        return List.of(
        move("1282070739733729280", "FREE_AGENT", 6, "2025-10-09T17:46:33.376Z", ids("11603", 1), ids()), // id 2438
        move("1286111781520965632", "TRADE", 7, "2025-10-21T04:35:49.436Z", ids("4039", 1, "5012", 3, "11603", 3, "12529", 1), ids("4039", 3, "5012", 1, "11603", 1, "12529", 3)), // id 2464
        move("1287229502765678592", "FREE_AGENT", 8, "2025-10-23T23:25:38.328Z", ids(), ids("5012", 3)), // id 2504
        move("1288203067338022912", "FREE_AGENT", 8, "2025-10-26T15:54:14.211Z", ids(), ids("4039", 1)), // id 2500
        move("1288897522516922368", "WAIVER", 8, "2025-10-29T07:13:33.825Z", ids("4039", 9), ids()), // id 2490
        move("1288897272859353088", "WAIVER", 8, "2025-10-29T07:13:33.825Z", ids("5012", 9), ids()), // id 2491
        move("1299455571119837184", "FREE_AGENT", 13, "2025-11-26T17:07:40.174Z", ids(), ids("11603", 3)), // id 2649
        move("1310031943085092864", "FREE_AGENT", 17, "2025-12-25T21:34:23.749Z", ids("11603", 3), ids()) // id 2750
        );
    }

    // case 766 211 ['2334']
    private static List<WeekRow> weeks766() {
        return List.of(

        );
    }

    private static List<Move> moves766() {
        return List.of(
        move("1288719375783710720", "FREE_AGENT", 2, "2025-10-28T02:05:51.737Z", ids("2334", 3), ids()), // id 782
        move("1288720058767413248", "WAIVER", 2, "2025-10-28T23:58:23.510Z", ids(), ids("2334", 3)), // id 781
        move("1289094616431226880", "FREE_AGENT", 2, "2025-10-29T02:56:56.078Z", ids("2334", 1), ids()), // id 777
        move("1289869016390725632", "TRADE", 2, "2025-11-02T01:20:48.212Z", ids("2334", 8), ids("2334", 1)), // id 766
        move("1290946561076887552", "WAIVER", 2, "2025-11-03T08:08:07.475Z", ids(), ids("2334", 8)) // id 744
        );
    }

    // case 8044 211 ['2144', '2456']
    private static List<WeekRow> weeks8044() {
        return List.of(
        row(1, 1, "2144=21.5", "2144"),
        row(1, 5, "2456=0.0", ""),
        row(2, 1, "2144=24.0", "2144"),
        row(2, 5, "2456=0.0", ""),
        row(3, 1, "2144=22.0", "2144"),
        row(3, 5, "2456=0.0", ""),
        row(4, 1, "2144=0.0", ""),
        row(4, 5, "2456=0.0", ""),
        row(5, 1, "2144=0.0", ""),
        row(5, 5, "2456=23.0", "2456"),
        row(6, 1, "2144=0.0", ""),
        row(6, 5, "2456=36.5", "2456"),
        row(7, 5, "2456=21.0", "2456"),
        row(8, 5, "2456=20.5", "2456"),
        row(9, 2, "2456=24.0", "2456"),
        row(10, 2, "2456=0.0", ""),
        row(10, 10, "2144=15.0", "2144"),
        row(11, 2, "2456=25.0", "2456"),
        row(11, 10, "2144=23.5", "2144"),
        row(12, 2, "2144=16.5", ""),
        row(12, 10, "2456=0.0", ""),
        row(13, 10, "2456=0.0", ""),
        row(14, 8, "2144=18.0", "2144"),
        row(14, 10, "2456=0.0", ""),
        row(15, 8, "2144=13.0", ""),
        row(15, 10, "2456=0.0", ""),
        row(16, 8, "2144=8.0", ""),
        row(16, 10, "2456=0.0", ""),
        row(17, 10, "2456=0.0", ""),
        row(18, 10, "2456=18.0", ""),
        row(19, 10, "2456=0.0", ""),
        row(20, 1, "2144=9.0", ""),
        row(20, 10, "2456=0.0", ""),
        row(21, 5, "2144=21.5", "2144"),
        row(21, 10, "2456=0.0", "")
        );
    }

    private static List<Move> moves8044() {
        return List.of(
        move("1302157115221118976", "FREE_AGENT", 7, "2025-12-04T04:02:38.480Z", ids(), ids("2144", 1)), // id 1021
        move("1306060554170793984", "TRADE", 8, "2025-12-15T17:34:58.071Z", ids("2456", 2), ids("2456", 5)), // id 1053
        move("1310430847739891712", "FREE_AGENT", 10, "2025-12-26T23:59:30.031Z", ids("2144", 10), ids()), // id 1205
        move("1314493099052924928", "TRADE", 12, "2026-01-09T21:39:17.211Z", ids("2144", 2, "2456", 10), ids("2144", 10, "2456", 2)), // id 8044
        move("1318350734378426368", "FREE_AGENT", 13, "2026-01-17T20:30:18.149Z", ids(), ids("2144", 2)), // id 1423
        move("1319051917497753600", "WAIVER", 14, "2026-01-19T19:38:36.230Z", ids("2144", 8), ids()), // id 1579
        move("1327405992652058624", "FREE_AGENT", 17, "2026-02-11T20:12:39.960Z", ids(), ids("2144", 8)), // id 1864
        move("1328290165558345728", "FREE_AGENT", 18, "2026-02-14T06:46:03.208Z", ids("2144", 8), ids()), // id 2031
        move("1330834237430968320", "WAIVER", 18, "2026-02-21T08:09:02.334Z", ids(), ids("2144", 8)), // id 1973
        move("1336497910262562816", "FREE_AGENT", 20, "2026-03-08T22:20:42.038Z", ids("2144", 1), ids()), // id 2126
        move("1337578462088548352", "FREE_AGENT", 21, "2026-03-11T21:54:25.669Z", ids(), ids("2144", 1)), // id 2265
        move("1338319088753061888", "FREE_AGENT", 21, "2026-03-13T22:57:24.827Z", ids("2144", 12), ids()), // id 2245
        move("1338407310724198400", "FREE_AGENT", 21, "2026-03-14T04:47:58.583Z", ids(), ids("2144", 12)), // id 2234
        move("1338978771079823360", "FREE_AGENT", 21, "2026-03-15T18:38:45.350Z", ids("2144", 5), ids()) // id 2204
        );
    }

    // case 1162 league 211 players ['1438']
    private static List<WeekRow> weeks1162() {
        return List.of(

        );
    }

    private static List<Move> moves1162() {
        return List.of(
        move("1306561097469280256", "FREE_AGENT", 9, "2025-12-16T07:42:29.669Z", ids("1438", 1), ids()),  // id 1163
        move("1306562426694561792", "TRADE", 9, "2025-12-16T20:43:11.925Z", ids("1438", 8), ids("1438", 1)),  // id 1162
        move("1306841829546668032", "FREE_AGENT", 9, "2025-12-17T02:18:01.412Z", ids(), ids("1438", 8)),  // id 1159
        move("1329360043899904000", "FREE_AGENT", 18, "2026-02-17T05:37:22.082Z", ids("1438", 1), ids()),  // id 2025
        move("1329962924529176576", "FREE_AGENT", 18, "2026-02-18T21:33:00.026Z", ids(), ids("1438", 1))  // id 2015
        );
    }

    // case 1036 league 211 players ['1779', '2590']
    private static List<WeekRow> weeks1036() {
        return List.of(
        row(1, 11, "1779=24.0", "1779"),
        row(2, 11, "1779=0.0", ""),
        row(3, 11, "1779=6.0", ""),
        row(4, 11, "1779=13.0", ""),
        row(5, 4, "2590=16.0", ""),
        row(5, 11, "1779=10.5", ""),
        row(6, 4, "2590=21.5", ""),
        row(6, 11, "1779=30.0", ""),
        row(7, 4, "1779=4.5", ""),
        row(7, 11, "2590=22.5", ""),
        row(8, 11, "1779=17.5,2590=20.5", "2590"),
        row(9, 11, "2590=20.5", "2590"),
        row(10, 11, "2590=27.0", "2590"),
        row(11, 11, "2590=22.0", "2590"),
        row(12, 11, "2590=28.5", "2590"),
        row(13, 11, "2590=26.5", "2590"),
        row(14, 11, "2590=23.5", ""),
        row(15, 11, "2590=23.0", "2590"),
        row(16, 11, "2590=21.0", "2590"),
        row(17, 11, "2590=26.0", "2590"),
        row(18, 11, "2590=21.0", "2590"),
        row(19, 6, "1779=6.0", "1779"),
        row(19, 11, "2590=0.0", ""),
        row(20, 6, "1779=27.5", ""),
        row(20, 11, "2590=0.0", ""),
        row(21, 6, "1779=2.0", ""),
        row(21, 11, "2590=0.0", "")
        );
    }

    private static List<Move> moves1036() {
        return List.of(
        move("1295072567983366144", "FREE_AGENT", 4, "2025-11-14T14:51:10.793Z", ids("2590", 6), ids()),  // id 860
        move("1295293760652988416", "FREE_AGENT", 4, "2025-11-15T05:30:07.236Z", ids(), ids("2590", 6)),  // id 855
        move("1297669945944977408", "FREE_AGENT", 5, "2025-11-21T18:52:13.956Z", ids("2590", 4), ids()),  // id 914
        move("1301367667583614976", "TRADE", 7, "2025-12-04T09:10:43.635Z", ids("1779", 4, "2590", 11), ids("1779", 11, "2590", 4)),  // id 1036
        move("1304628674179403776", "FREE_AGENT", 8, "2025-12-10T23:43:44.054Z", ids(), ids("1779", 4)),  // id 1075
        move("1306073765678960640", "FREE_AGENT", 8, "2025-12-14T23:26:00.716Z", ids("1779", 1), ids()),  // id 1052
        move("1306105064489779200", "FREE_AGENT", 8, "2025-12-15T01:30:22.935Z", ids(), ids("1779", 1)),  // id 1049
        move("1306119461111083008", "WAIVER", 8, "2025-12-15T08:08:22.801Z", ids("1779", 11), ids()),  // id 1048
        move("1307600552078610432", "FREE_AGENT", 9, "2025-12-19T04:32:54.960Z", ids(), ids("1779", 11)),  // id 1142
        move("1308397576222965760", "FREE_AGENT", 9, "2025-12-21T09:20:00.324Z", ids("1779", 1), ids()),  // id 1111
        move("1308634175254847488", "FREE_AGENT", 9, "2025-12-22T01:00:09.929Z", ids(), ids("1779", 1)),  // id 1106
        move("1316899897055653888", "FREE_AGENT", 13, "2026-01-13T20:25:11.576Z", ids("1779", 1), ids()),  // id 1467
        move("1318098241698406400", "WAIVER", 13, "2026-01-17T11:48:19.002Z", ids(), ids("1779", 1)),  // id 1431
        move("1321597735723335680", "FREE_AGENT", 15, "2026-01-26T19:32:43.597Z", ids("1779", 3), ids()),  // id 1680
        move("1322051275050725377", "FREE_AGENT", 15, "2026-01-28T01:34:55.798Z", ids(), ids("1779", 3)),  // id 1664
        move("1323819651431419904", "FREE_AGENT", 15, "2026-02-01T22:41:49.582Z", ids("1779", 8), ids()),  // id 1606
        move("1323876598381826048", "FREE_AGENT", 15, "2026-02-02T02:28:06.794Z", ids(), ids("1779", 8)),  // id 1599
        move("1331780141126254592", "FREE_AGENT", 19, "2026-02-23T21:53:58.224Z", ids("1779", 6), ids())  // id 2102
        );
    }
}
