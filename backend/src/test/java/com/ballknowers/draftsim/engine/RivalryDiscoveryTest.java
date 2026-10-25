package com.ballknowers.draftsim.engine;

import com.ballknowers.draftsim.engine.RivalryMatching.PairStats;
import com.ballknowers.draftsim.engine.RivalryService.DiscoveredPair;
import com.ballknowers.draftsim.engine.RivalryService.Discovery;
import com.ballknowers.draftsim.engine.RivalryService.KindTally;
import com.ballknowers.draftsim.engine.RivalryService.PairCalc;
import com.ballknowers.draftsim.engine.RivalryService.PairKey;
import com.ballknowers.draftsim.engine.RivalryService.PairRecord;
import com.ballknowers.draftsim.engine.RivalryService.SplitNote;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * specs/047 T018 / plan A12: the discovery lists as ORDERING tests, not count tests (lessons #1: a ranking can be
 * well-formed and pointed backwards, and only an assertion on which entry comes out on top catches that).
 */
class RivalryDiscoveryTest {

    private static final int N = 3;

    /** A pair a < b with the given record; {@code playoffMatches} bracket matches (a two-week final is one). */
    private static PairCalc pc(long a, long b, int aWins, int bWins, int ties, int playoffMatches) {
        int meetings = aWins + bWins + ties;
        PairRecord rec = new PairRecord(a, b, meetings, aWins, bWins, ties,
                Map.of("REGULAR", new KindTally(aWins, bWins, ties)),
                meetings > 0 ? "MET" : "NEVER_MET", List.of(), List.of());
        PairStats stats = new PairStats(meetings, aWins, bWins, ties, playoffMatches, playoffMatches > 0);
        return new PairCalc(rec, stats, List.of(), 0, 0, List.of(), null, null, 0);
    }

    private static Map<PairKey, PairCalc> matrix(PairCalc... pcs) {
        Map<PairKey, PairCalc> m = new LinkedHashMap<>();
        for (PairCalc p : pcs) m.put(new PairKey(p.record().aManagerId(), p.record().bManagerId()), p);
        return m;
    }

    private static List<String> ids(List<DiscoveredPair> ps) {
        List<String> out = new ArrayList<>();
        for (DiscoveredPair p : ps) out.add(p.aManagerId() + "-" + p.bManagerId());
        return out;
    }

    private static Discovery run(PairCalc... pcs) {
        return RivalryService.discovery(matrix(pcs), N, List.of());
    }

    // ------------------------------------------------------------------ lopsided

    @Test
    void lopsided_aFourNilBeatsAThreeOne() {
        Discovery d = run(pc(1, 2, 4, 0, 0, 0), pc(1, 3, 3, 1, 0, 0), pc(1, 4, 2, 2, 0, 0));
        assertEquals(List.of("1-2"), ids(d.lopsided()), "4-0 (gap 4) must outrank 3-1 (gap 2)");
    }

    @Test
    void lopsided_aThreeNilBeatsAFiveTwoOnTheWinnersShare() {
        // both have a gap of 3; 3-0 is 100% of the meetings, 5-2 is 71%
        Discovery d = run(pc(1, 2, 5, 2, 0, 0), pc(1, 3, 3, 0, 0, 0), pc(1, 4, 2, 2, 0, 0));
        assertEquals(List.of("1-3"), ids(d.lopsided()));
    }

    @Test
    void lopsided_theLoserSideCountsToo() {
        // b wins 0-4: a gap of 4 from the other side
        Discovery d = run(pc(1, 2, 0, 4, 0, 0), pc(1, 3, 2, 1, 0, 0), pc(1, 4, 2, 2, 0, 0));
        assertEquals(List.of("1-2"), ids(d.lopsided()));
    }

    @Test
    void lopsided_isEmptyWhenEveryPairIsTwoAndOne_theFootballBallKnowersShape() {
        // 7 pairs at 2-1 and one at 2-2: the widest gap is 1, below the floor of 2, so no pair is "one-sided".
        List<PairCalc> pcs = new ArrayList<>();
        for (int b = 2; b <= 8; b++) pcs.add(pc(1, b, 2, 1, 0, 0));
        pcs.add(pc(2, 3, 2, 2, 0, 0));
        Discovery d = run(pcs.toArray(PairCalc[]::new));
        assertTrue(d.lopsided().isEmpty());
        assertEquals(List.of("2-3"), ids(d.closest()), "the 2-2 pair is the closest");
    }

    @Test
    void lopsided_needsToBeWiderThanTheClosestTierToo() {
        // a league where EVERY pair is 3-1: gap 2 meets the floor, but it equals the closest tier's gap
        Discovery d = run(pc(1, 2, 3, 1, 0, 0), pc(1, 3, 1, 3, 0, 0), pc(2, 3, 3, 1, 0, 0));
        assertTrue(d.lopsided().isEmpty(), "everything is equally one-sided, so nothing stands out");
        assertEquals(3, d.closest().size(), "and all three are tied for closest");
    }

    @Test
    void lopsided_returnsEveryTiedEntry() {
        Discovery d = run(pc(1, 2, 4, 0, 0, 0), pc(3, 4, 0, 4, 0, 0), pc(1, 3, 2, 2, 0, 0));
        assertEquals(List.of("1-2", "3-4"), ids(d.lopsided()));
    }

    // ------------------------------------------------------------------ closest

    @Test
    void closest_aTwoTwoBeatsAThreeTwo() {
        Discovery d = run(pc(1, 2, 3, 2, 0, 0), pc(1, 3, 2, 2, 0, 0), pc(1, 4, 4, 0, 0, 0));
        assertEquals(List.of("1-3"), ids(d.closest()), "gap 0 must outrank gap 1");
    }

    @Test
    void closest_aThreeTwoBeatsATwoOneOnMeetings() {
        // both a gap of 1; the longer history wins the tie-break, and the reason is printed by the record itself
        Discovery d = run(pc(1, 2, 2, 1, 0, 0), pc(1, 3, 3, 2, 0, 0), pc(1, 4, 4, 0, 0, 0));
        assertEquals(List.of("1-3"), ids(d.closest()));
    }

    @Test
    void closest_aTieIsStillALevelRecord() {
        // 2 wins, 2 losses, 1 tie: a gap of 0 over 5 meetings, ahead of a 3-2 (gap 1) with the same 5 meetings
        Discovery d = run(pc(1, 2, 2, 2, 1, 0), pc(1, 3, 3, 2, 0, 0), pc(1, 4, 4, 0, 0, 0));
        assertEquals(List.of("1-2"), ids(d.closest()));
    }

    @Test
    void closest_returnsEveryPairLevelAtTheSameRecord() {
        List<PairCalc> pcs = new ArrayList<>();
        long id = 10;
        for (int i = 0; i < 16; i++) pcs.add(pc(id++, id++, 2, 2, 0, 0));
        pcs.add(pc(1, 2, 4, 0, 0, 0));
        Discovery d = run(pcs.toArray(PairCalc[]::new));
        assertEquals(16, d.closest().size(), "the NBA shape: 16 pairs at 2-2, all listed, none silently picked");
    }

    // ------------------------------------------------------------------ the floors

    @Test
    void aLeagueWhereNoPairHasMetNTimesHasEmptyMostPlayedClosestAndLopsided() {
        Discovery d = run(pc(1, 2, 1, 0, 0, 0), pc(1, 3, 1, 1, 0, 0), pc(2, 3, 2, 0, 0, 0));
        assertTrue(d.mostPlayed().isEmpty());
        assertTrue(d.closest().isEmpty());
        assertTrue(d.lopsided().isEmpty());
        assertEquals(N, d.minMeetings());
        assertEquals("hand-set", d.minMeetingsLabel());
    }

    @Test
    void mostPlayedIgnoresPairsBelowTheFloorAndReturnsTies() {
        Discovery d = run(pc(1, 2, 2, 0, 0, 0), pc(1, 3, 2, 1, 0, 0), pc(2, 3, 1, 2, 0, 0), pc(1, 4, 1, 1, 0, 0));
        assertEquals(List.of("1-3", "2-3"), ids(d.mostPlayed()), "both 3-meeting pairs, not the 2-meeting ones");
    }

    @Test
    void aPairThatMetFewerThanNTimesIsNeverInClosestMostPlayedOrLopsided() {
        Discovery d = run(pc(1, 2, 2, 0, 0, 0), pc(1, 3, 2, 1, 0, 0), pc(1, 4, 3, 0, 0, 0));
        for (var list : List.of(d.mostPlayed(), d.closest(), d.lopsided())) {
            for (DiscoveredPair p : list) assertTrue(p.meetings() >= N, p.aManagerId() + "-" + p.bManagerId());
        }
    }

    // ------------------------------------------------------------------ playoffs

    @Test
    void playoffListsEveryPairThatMetInThePlayoffsMostMatchesFirst() {
        Discovery d = run(pc(1, 2, 1, 0, 0, 1), pc(1, 3, 2, 1, 0, 2), pc(1, 4, 5, 0, 0, 0));
        assertEquals(List.of("1-3", "1-2"), ids(d.playoff()));
        assertEquals(2, d.playoff().get(0).playoffMatches());
    }

    @Test
    void aTwoWeekFinalCountsAsOnePlayoffMatch() {
        // PairStats counts per bracket MATCH (RivalryService.pairCalc): a two-week final is one match
        Discovery d = run(pc(1, 2, 1, 1, 0, 1));
        assertEquals(1, d.playoff().get(0).playoffMatches());
    }

    @Test
    void playoffHasNoMeetingsFloor_aPlayoffMeetingIsAFactNotAClaim() {
        Discovery d = run(pc(1, 2, 1, 0, 0, 1));
        assertEquals(List.of("1-2"), ids(d.playoff()));
        assertTrue(d.closest().isEmpty());
    }

    @Test
    void unsplitSeasonsComeFromTheSplitNotes() {
        Discovery d = RivalryService.discovery(matrix(pc(1, 2, 1, 0, 0, 0)), N,
                List.of(new SplitNote(2025, "NO_BRACKET", "x"), new SplitNote(2024, "NO_BRACKET", "y")));
        assertEquals(List.of(2025, 2024), d.playoffUnsplitSeasons());
        assertTrue(d.playoff().isEmpty());
    }

    // ------------------------------------------------------------------ the entry is its cell

    @Test
    void anEntryCopiesItsPairRecordFieldForField() {
        PairCalc p = pc(1, 2, 4, 1, 1, 1);
        DiscoveredPair d = RivalryService.discovery(matrix(p), N, List.of()).mostPlayed().get(0);
        PairRecord r = p.record();
        assertEquals(r.aManagerId(), d.aManagerId());
        assertEquals(r.bManagerId(), d.bManagerId());
        assertEquals(r.meetings(), d.meetings());
        assertEquals(r.aWins(), d.aWins());
        assertEquals(r.bWins(), d.bWins());
        assertEquals(r.ties(), d.ties());
        assertEquals(r.byKind(), d.byKind());
        assertEquals(r.status(), d.status());
        assertEquals(r.finals(), d.finals());
        assertEquals(r.games(), d.games());
    }

    @Test
    void anEmptyMatrixYieldsFourEmptyLists() {
        Discovery d = run();
        assertTrue(d.mostPlayed().isEmpty() && d.closest().isEmpty() && d.lopsided().isEmpty() && d.playoff().isEmpty());
    }
}
