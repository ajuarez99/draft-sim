package com.ballknowers.draftsim.engine;

import com.ballknowers.draftsim.engine.RivalryGameKind.Kind;
import com.ballknowers.draftsim.store.LeagueBracketRepository.Match;
import com.ballknowers.draftsim.store.LeagueRepository.PlayoffFormat;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * specs/034 T011: GameKind against the shapes research R2/F2/F3/F4 measured. The Foot 2025 bracket below
 * is the live one (league 1254190892974084096): 12 rosters, 6 in the bracket, 7 matches.
 */
class RivalryGameKindTest {

    private static final PlayoffFormat TYPE0 = new PlayoffFormat(6, 15, 0, false, false, 0);
    private static final PlayoffFormat TYPE1 = new PlayoffFormat(6, 15, 0, false, false, 1);

    private static Match m(int id, int round, Integer t1, Integer t2, Integer w, Integer p) {
        return new Match(1L, id, round, t1, t2, w, w == null ? null : (w.equals(t1) ? t2 : t1), p);
    }

    /** Foot 2025: week 15 (8,9) (3,4); week 16 (1,9) (3,11) and the 5th-place game (4,8); week 17 final (1,3), 3rd (9,11). */
    private static List<Match> foot2025() {
        return List.of(
                m(1, 1, 8, 9, 9, null), m(2, 1, 3, 4, 3, null),
                m(3, 2, 1, 9, 1, null), m(4, 2, 11, 3, 3, null), m(5, 2, 4, 8, 4, 5),
                m(6, 3, 1, 3, 1, 1), m(7, 3, 9, 11, 9, 3));
    }

    @Test
    void foot2025SplitsFourteenGamesIntoSevenBracketAndSevenConsolation() {
        // [week, a, b] for the 14 scored pairings of weeks 15-17.
        int[][] games = {
                {15, 8, 9}, {15, 3, 4}, {15, 5, 12}, {15, 6, 7}, {15, 2, 10},
                {16, 1, 9}, {16, 3, 11}, {16, 4, 8}, {16, 2, 12}, {16, 5, 10},
                {17, 1, 3}, {17, 9, 11}, {17, 2, 6}, {17, 7, 12}};
        Map<Kind, Integer> by = new EnumMap<>(Kind.class);
        for (int[] g : games) {
            var c = RivalryGameKind.classify(TYPE0, foot2025(), g[0], g[1], g[2]);
            by.merge(c.kind(), 1, Integer::sum);
        }
        assertEquals(5, by.get(Kind.PLAYOFF), "4 elimination games + the final");
        assertEquals(2, by.get(Kind.PLACEMENT), "the 5th-place and 3rd-place games");
        assertEquals(7, by.get(Kind.CONSOLATION));
        assertNull(by.get(Kind.POSTSEASON_UNSPLIT));
    }

    @Test
    void theFinalIsPlayoffAndTheFifthPlaceGameIsPlacement() {
        assertEquals(Kind.PLAYOFF, RivalryGameKind.classify(TYPE0, foot2025(), 17, 3, 1).kind());
        assertEquals(1, RivalryGameKind.classify(TYPE0, foot2025(), 17, 3, 1).match().placement());
        assertEquals(Kind.PLACEMENT, RivalryGameKind.classify(TYPE0, foot2025(), 16, 8, 4).kind());
        assertEquals(Kind.PLACEMENT, RivalryGameKind.classify(TYPE0, foot2025(), 17, 11, 9).kind());
    }

    @Test
    void thePairMustBeInTHATWEEKSRound() {
        // (8,9) is a round-1 pairing; played in week 16 (round 2) it is NOT a bracket game.
        assertEquals(Kind.CONSOLATION, RivalryGameKind.classify(TYPE0, foot2025(), 16, 8, 9).kind());
    }

    @Test
    void beforeThePlayoffStartOrWithoutPlayoffsIsRegular() {
        assertEquals(Kind.REGULAR, RivalryGameKind.classify(TYPE0, foot2025(), 14, 8, 9).kind());
        assertEquals(Kind.REGULAR, RivalryGameKind.classify(new PlayoffFormat(0, 0, 0, false, false, 0), List.of(), 17, 8, 9).kind());
        assertEquals(Kind.REGULAR, RivalryGameKind.classify(null, List.of(), 17, 8, 9).kind());
    }

    @Test
    void noBracketStoredIsUnsplitNotConsolation() {
        var c = RivalryGameKind.classify(TYPE0, List.of(), 15, 8, 9);
        assertEquals(Kind.POSTSEASON_UNSPLIT, c.kind());
        assertEquals("NO_BRACKET", c.unsplitReason());
    }

    /**
     * F2: a pre-playoff season holds a PROVISIONAL bracket seeded from today's standings, with round-2 slots
     * still null. A game in that week whose pair is not in the rows must be UNSPLIT, never CONSOLATION.
     */
    @Test
    void aStaleBracketWithAnUnfilledSlotIsUnsplit() {
        List<Match> provisional = List.of(
                m(1, 1, 9, 2, null, null), m(2, 1, 4, 1, null, null),
                m(3, 2, 10, null, null, null), m(4, 2, 7, null, null, null), m(5, 2, null, null, null, 5),
                m(6, 3, null, null, null, 1), m(7, 3, null, null, null, 3));
        // round 1 has both slots set but round 2 does not; week 16 is round 2.
        var c = RivalryGameKind.classify(TYPE0, provisional, 16, 3, 11);
        assertEquals(Kind.POSTSEASON_UNSPLIT, c.kind());
        assertEquals("BRACKET_PENDING", c.unsplitReason());
        // And a real semifinal-shaped pair that isn't in the stale rows is still not CONSOLATION.
        assertEquals(Kind.POSTSEASON_UNSPLIT, RivalryGameKind.classify(TYPE0, provisional, 17, 1, 3).kind());
    }

    /** F3: West Coast 2025 (round type 1): the final's two legs are paired in weeks 17 and 18; both are PLAYOFF. */
    @Test
    void roundTypeOneFinalLegsAreBothPlayoff() {
        List<Match> wc = List.of(
                m(1, 1, 8, 9, 9, null), m(2, 1, 4, 5, 4, null),
                m(3, 2, 6, 9, 6, null), m(4, 2, 3, 4, 3, null), m(5, 2, 5, 8, 5, 5),
                m(6, 3, 6, 3, 6, 1), m(7, 3, 9, 4, 4, 3));
        var leg1 = RivalryGameKind.classify(TYPE1, wc, 17, 3, 6);
        var leg2 = RivalryGameKind.classify(TYPE1, wc, 18, 3, 6);
        assertEquals(Kind.PLAYOFF, leg1.kind());
        assertEquals(Kind.PLAYOFF, leg2.kind());
        assertSame(leg1.match(), leg2.match(), "one bracket match, two weekly games");
        assertEquals(Kind.PLACEMENT, RivalryGameKind.classify(TYPE1, wc, 18, 4, 9).kind());
        // The first final week is round 3 for round type 1; week 16 is round 2.
        assertEquals(Kind.PLAYOFF, RivalryGameKind.classify(TYPE1, wc, 16, 6, 9).kind());
        // A week past the bracket's extra leg maps to no round.
        assertEquals("ROUND_UNKNOWN", RivalryGameKind.classify(TYPE1, wc, 19, 3, 6).unsplitReason());
    }

    @Test
    void anUnknownOrUnsupportedRoundTypeIsUnsplit() {
        assertEquals("ROUND_TYPE_UNSUPPORTED",
                RivalryGameKind.classify(new PlayoffFormat(6, 15, 0, false, false, 2), foot2025(), 15, 8, 9).unsplitReason());
        assertEquals("ROUND_TYPE_UNKNOWN",
                RivalryGameKind.classify(new PlayoffFormat(6, 15, 0, false, false, null), foot2025(), 15, 8, 9).unsplitReason());
    }

    @Test
    void byesComeFromTheBracketSlotsNotFromAMissingRow() {
        List<Match> b = foot2025();
        // 1 and 11 hold round-2 slots but no round-1 slot: bye in week 15.
        assertTrue(RivalryGameKind.bracketBye(TYPE0, b, 15, 1));
        assertTrue(RivalryGameKind.bracketBye(TYPE0, b, 15, 11));
        // 4 and 8 were eliminated in round 1 and are in round 2 only via the 5th-place game: not a bye.
        assertFalse(RivalryGameKind.bracketBye(TYPE0, b, 15, 4));
        // 12 never made the bracket: not a bye in any week.
        for (int w = 15; w <= 17; w++) assertFalse(RivalryGameKind.bracketBye(TYPE0, b, w, 12));
        // 4 and 8 in week 17 have no slot in round 3: out, not a bye.
        assertFalse(RivalryGameKind.bracketBye(TYPE0, b, 17, 4));
    }

    @Test
    void bracketOpponentNeedsBothSlotsSet() {
        List<Match> b = new ArrayList<>(foot2025());
        assertEquals(9, RivalryGameKind.bracketOpponent(TYPE0, b, 15, 8).orElseThrow());
        assertEquals(8, RivalryGameKind.bracketOpponent(TYPE0, b, 15, 9).orElseThrow());
        assertTrue(RivalryGameKind.bracketOpponent(TYPE0, b, 15, 12).isEmpty());
        assertTrue(RivalryGameKind.bracketOpponent(TYPE0, b, 14, 8).isEmpty(), "not a playoff week");
        List<Match> half = List.of(m(3, 2, 10, null, null, null));
        assertTrue(RivalryGameKind.bracketOpponent(TYPE0, half, 15, 10).isEmpty() , "round 1 has no rows -> unmapped");
    }
}
