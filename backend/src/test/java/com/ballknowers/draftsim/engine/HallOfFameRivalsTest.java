package com.ballknowers.draftsim.engine;

import com.ballknowers.draftsim.domain.Sport;
import com.ballknowers.draftsim.engine.HallOfFameService.ChainRivals;
import com.ballknowers.draftsim.engine.HallOfFameService.ManagerRef;
import com.ballknowers.draftsim.engine.HallOfFameService.RivalEntry;
import com.ballknowers.draftsim.engine.RivalryService.KindTally;
import com.ballknowers.draftsim.engine.RivalryService.PairRecord;
import com.ballknowers.draftsim.store.LeagueRepository.LeagueRow;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * specs/047 T025 / plan A12: nemesis and victim as ORDERING tests over synthetic pairs. The manager under test
 * is the HIGHER id in every pair (100 against opponents 1..9), so the pair's A side is always the opponent and
 * a missing orientation would read every record backwards -- exactly the sign inversion lessons #1 describes.
 */
class HallOfFameRivalsTest {

    private static final long ME = 100;
    private static final int N = 3;
    private static final LeagueRow HEAD =
            new LeagueRow(1L, Sport.NFL, "S1", "Test League", 2026, 12, List.of("QB"), 1.0, null, "in_season");

    /** A stored pair (opponent is A, the lower id) with the OPPONENT's wins first, as the matrix stores it. */
    private static PairRecord stored(long opponent, int opponentWins, int myWins, int ties) {
        return new PairRecord(opponent, ME, opponentWins + myWins + ties, opponentWins, myWins, ties,
                Map.of("REGULAR", new KindTally(opponentWins, myWins, ties)), "MET", List.of(), List.of());
    }

    private static RivalEntry entry(long opponent, int opponentWins, int myWins, int ties) {
        ManagerRef ref = new ManagerRef(opponent, "opp" + opponent, null, null, (int) opponent);
        return HallOfFameService.orient(ME, stored(opponent, opponentWins, myWins, ties), ref, false);
    }

    private static ChainRivals summarize(RivalEntry... entries) {
        return HallOfFameService.summarize(HEAD, N, List.of(entries));
    }

    private static List<Long> ids(List<RivalEntry> es) {
        List<Long> out = new ArrayList<>();
        for (RivalEntry e : es) out.add(e.opponent().managerId());
        return out;
    }

    @Test
    void orientationTurnsTheRecordToTheManagersSide() {
        RivalEntry e = entry(1, 3, 0, 0);   // the opponent (A) won 3-0, so I am 0-3
        assertEquals(0, e.wins());
        assertEquals(3, e.losses());
        assertEquals(3, e.meetings());
        // and when the manager is the LOWER id, nothing is swapped
        PairRecord mineAsA = new PairRecord(ME, 200, 4, 3, 1, 0, Map.of(), "MET", List.of(), List.of());
        RivalEntry asA = HallOfFameService.orient(ME, mineAsA, new ManagerRef(200L, "o", null, null, 2), false);
        assertEquals(3, asA.wins());
        assertEquals(1, asA.losses());
    }

    @Test
    void nemesis_aZeroThreeOpponentRanksAboveAOneThreeOne() {
        ChainRivals r = summarize(entry(2, 3, 1, 0), entry(1, 3, 0, 0), entry(3, 1, 1, 0));
        assertEquals(List.of(1L), ids(r.nemesis()), "0-3 must outrank 1-3");
        assertEquals("OK", r.status());
    }

    @Test
    void nemesis_neverNamesATwoOneOpponent() {
        // I am 2-1 against opponent 1: a winning record, so no nemesis; they are my victim instead.
        ChainRivals r = summarize(entry(1, 1, 2, 0));
        assertTrue(r.nemesis().isEmpty());
        assertEquals(List.of(1L), ids(r.victim()));
    }

    @Test
    void nemesis_moreMeetingsBreaksATieOnShare() {
        // 0-3 and 0-4 both have a 0% win share; the longer history is the harder nemesis
        ChainRivals r = summarize(entry(1, 3, 0, 0), entry(2, 4, 0, 0));
        assertEquals(List.of(2L), ids(r.nemesis()));
    }

    @Test
    void nemesis_everyTiedOpponentIsListedInIdOrder() {
        ChainRivals r = summarize(entry(5, 3, 0, 0), entry(2, 3, 0, 0), entry(9, 3, 1, 0));
        assertEquals(List.of(2L, 5L), ids(r.nemesis()));
    }

    @Test
    void victim_isTheMirror_threeNilAboveThreeOneAndNeverALosingRecord() {
        ChainRivals r = summarize(entry(1, 0, 3, 0), entry(2, 1, 3, 0), entry(3, 3, 1, 0));
        assertEquals(List.of(1L), ids(r.victim()), "3-0 must outrank 3-1");
        assertEquals(List.of(3L), ids(r.nemesis()), "1-3 is a losing record, so it is the nemesis, not a victim");
    }

    @Test
    void victim_moreMeetingsBreaksATie_andTiesAreAllListed() {
        ChainRivals longer = summarize(entry(1, 0, 3, 0), entry(2, 0, 4, 0));
        assertEquals(List.of(2L), ids(longer.victim()));
        ChainRivals tied = summarize(entry(7, 0, 3, 0), entry(4, 0, 3, 0));
        assertEquals(List.of(4L, 7L), ids(tied.victim()));
    }

    @Test
    void anOpponentBelowTheFloorQualifiesForNothing() {
        ChainRivals r = summarize(entry(1, 2, 0, 0), entry(2, 0, 2, 0));
        assertEquals("NONE_QUALIFY", r.status());
        assertEquals(N, r.minMeetings());
        assertTrue(r.nemesis().isEmpty() && r.victim().isEmpty());
    }

    @Test
    void anEvenRecordAtTheFloorQualifiesButNamesNeitherSide() {
        ChainRivals r = summarize(entry(1, 2, 2, 0));
        assertEquals("OK", r.status());
        assertTrue(r.nemesis().isEmpty());
        assertTrue(r.victim().isEmpty());
    }

    @Test
    void aTieCountsAsHalfAWin() {
        // 1-1-1 is an even record (a tie counts half); 1-2 is a losing one
        RivalEntry halfAndHalf = entry(1, 1, 1, 1);   // 1-1-1: not losing
        RivalEntry worse = entry(2, 2, 1, 0);         // 1-2: losing
        ChainRivals r = summarize(halfAndHalf, worse);
        assertEquals(List.of(2L), ids(r.nemesis()));
        assertTrue(r.victim().isEmpty(), "1-1-1 is not a winning record");
    }

    @Test
    void theFormerFlagAndTheOpponentTravelWithTheEntry() {
        ManagerRef ref = new ManagerRef(4L, "gone", "The Gone", "av", 4);
        RivalEntry e = HallOfFameService.orient(ME, stored(4, 3, 0, 0), ref, true);
        ChainRivals r = HallOfFameService.summarize(HEAD, N, List.of(e));
        assertTrue(r.nemesis().get(0).former());
        assertEquals("The Gone", r.nemesis().get(0).opponent().teamName());
        assertEquals("nfl", r.sport());
        assertEquals("S1", r.headSleeperLeagueId());
    }
}
