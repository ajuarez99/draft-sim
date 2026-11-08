package com.ballknowers.draftsim.historical;

import com.ballknowers.draftsim.domain.Sport;
import com.ballknowers.draftsim.historical.HistoricalBoardBuilder.Candidate;
import com.ballknowers.draftsim.historical.HistoricalBoardBuilder.NflRank;
import com.ballknowers.draftsim.historical.HistoricalBoardBuilder.Options;
import com.ballknowers.draftsim.historical.HistoricalBoardBuilder.RankConfig;
import com.ballknowers.draftsim.historical.HistoricalBoardBuilder.RankMode;
import com.ballknowers.draftsim.historical.HistoricalBoardBuilder.RookiePick;
import com.ballknowers.draftsim.historical.NflHistoricalRepository.NflSeasonLine;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Spec 056 T019 (written before the implementation). The football ranking is points over replacement (review F6,
 * Allan 2026-11-05) with rookies placed by their real NFL draft slot (F14). A well-formed ranking can still be
 * pointed backwards (AGENTS.md bug class 1), so every test here pins a PREFERENCE ORDER, never only counts.
 *
 * <p>To keep the arithmetic readable the scoring map counts one key, {@code rush_yd} at 1.0, so a line's
 * points-per-game is exactly its rushing yards per game, whatever the position. The position is just a label.
 */
class HistoricalBoardBuilderNflTest {

    private static final int SEASON = 2011;
    private static final RankConfig CFG = new RankConfig(17, 6);
    private static final Map<String, Double> SCORING = Map.of("rush_yd", 1.0);

    /** Dedicated starter slots x teams, as NflHistoricalSport derives them: here a small league keeps it legible. */
    private static Options options(Map<String, Integer> depth, Map<Long, RookiePick> rookies) {
        return new Options(Sport.NFL, NflHistoricalSport.COUNTING, y -> String.valueOf(y), RankMode.POINTS_OVER_REPLACEMENT,
                rookies, new NflRank(depth, 12, 2, NflHistoricalSport.TOTAL_KEYS));
    }

    private static NflSeasonLine line(long id, int season, int games, double ppg, String pos) {
        Map<String, Double> t = new LinkedHashMap<>();
        t.put("rush_yd", ppg * games);
        return new NflSeasonLine(id, season, "AAA", games, List.of(pos), t);
    }

    private static Map<Long, NflSeasonLine> map(List<NflSeasonLine> lines) {
        Map<Long, NflSeasonLine> m = new HashMap<>();
        for (NflSeasonLine l : lines) m.put(l.playerId(), l);
        return m;
    }

    private static List<HistoricalPlayerCard> build(List<Candidate> cands, List<NflSeasonLine> s1,
                                                    List<NflSeasonLine> s2, List<NflSeasonLine> drafted,
                                                    Options opts) {
        return HistoricalBoardBuilder.build(SEASON, 7L, SCORING, cands, List.of(map(s1), map(s2)), map(drafted), CFG,
                opts);
    }

    private static int rankOf(List<HistoricalPlayerCard> cards, long id) {
        return cards.stream().filter(c -> c.id() == id).findFirst().orElseThrow().rank();
    }

    private static HistoricalPlayerCard card(List<HistoricalPlayerCard> cards, long id) {
        return cards.stream().filter(c -> c.id() == id).findFirst().orElseThrow();
    }

    // ---- (a)/(b) replacement level -----------------------------------------------------------------------

    /**
     * QB1 20 ppg, QB2 17, QB3 16 (replacement = 2nd QB = 17); WR1 16 ppg, WR2 10, WR3 9 (replacement = 2nd WR = 10).
     * Raw points say QB1 (340) beats WR1 (272); over replacement QB1 is +51 and WR1 is +102, so the WR goes first.
     */
    @Test
    void aWideReceiverOverReplacementOutranksAQuarterbackWithMoreRawPoints() {
        List<NflSeasonLine> s1 = List.of(line(1, 2010, 16, 20, "QB"), line(2, 2010, 16, 17, "QB"),
                line(3, 2010, 16, 16, "QB"), line(4, 2010, 16, 16, "WR"), line(5, 2010, 16, 10, "WR"),
                line(6, 2010, 16, 9, "WR"));
        var cards = build(cands(6), s1, List.of(), List.of(), options(Map.of("QB", 2, "WR", 2), Map.of()));
        assertTrue(rankOf(cards, 4) < rankOf(cards, 1), "WR1 (+102 over replacement) before QB1 (+51)");
        // and the replacement-level players sit below both, in the order of their (here equal) 0 over replacement
        assertTrue(rankOf(cards, 1) < rankOf(cards, 2));
        assertTrue(rankOf(cards, 1) < rankOf(cards, 5));
        // below replacement is last: QB3 (-17) and WR3 (-17) are behind QB2 and WR2 (0)
        assertTrue(rankOf(cards, 2) < rankOf(cards, 3));
        assertTrue(rankOf(cards, 5) < rankOf(cards, 6));
    }

    @Test
    void thePositionalRankInTheCardStillFollowsTheBoardOrder() {
        List<NflSeasonLine> s1 = List.of(line(1, 2010, 16, 20, "QB"), line(2, 2010, 16, 17, "QB"),
                line(4, 2010, 16, 16, "WR"), line(5, 2010, 16, 10, "WR"));
        var cards = build(cands(5), s1, List.of(), List.of(), options(Map.of("QB", 2, "WR", 2), Map.of()));
        assertEquals(1, card(cards, 1).positionalRank());
        assertEquals(2, card(cards, 2).positionalRank());
        assertEquals(1, card(cards, 4).positionalRank());
    }

    /**
     * Fewer veterans than the dedicated slots: the replacement is the LAST veteran's score, not zero. RB1 20 ppg and
     * RB2 10 (depth 2 -> replacement 10 -> +170); WR1 16 and WR2 14 with depth 5 (only two exist -> replacement 14
     * -> WR1 is +34). If a short position wrongly fell back to a replacement of 0 the WR would be +272 and win.
     */
    @Test
    void aPositionShorterThanItsDepthUsesTheLastVeteranAsReplacementNotZero() {
        List<NflSeasonLine> s1 = List.of(line(1, 2010, 16, 20, "RB"), line(2, 2010, 16, 10, "RB"),
                line(3, 2010, 16, 16, "WR"), line(4, 2010, 16, 14, "WR"));
        var cards = build(cands(4), s1, List.of(), List.of(), options(Map.of("RB", 2, "WR", 5), Map.of()));
        assertTrue(rankOf(cards, 1) < rankOf(cards, 3), "RB1 +170 beats WR1 +34 once short positions use the last veteran");
    }

    @Test
    void aPlayerBelowTheGamesFloorIsNeverCountedInTheReplacementLevel() {
        // QB2 played 5 games (< rankMinGames 6) at 40 ppg: not a qualifying veteran, so QB1 (20) is the only one
        // and the replacement is QB1 himself (0 over replacement), not some other value.
        List<NflSeasonLine> s1 = List.of(line(1, 2010, 16, 20, "QB"), line(2, 2010, 5, 40, "QB"),
                line(3, 2010, 16, 5, "RB"));
        var cards = build(cands(3), s1, List.of(), List.of(), options(Map.of("QB", 2, "RB", 1), Map.of()));
        assertEquals(3, cards.size());
        assertTrue(rankOf(cards, 1) <= 2 && rankOf(cards, 3) <= 2, "both qualifying veterans are ranked");
        assertEquals(3, rankOf(cards, 2), "the sub-floor line is unranked, in the tail");
    }

    // ---- (c) rookies -------------------------------------------------------------------------------------

    private static List<NflSeasonLine> thirtyRunningBacks() {
        List<NflSeasonLine> l = new ArrayList<>();
        for (int i = 1; i <= 30; i++) l.add(line(100 + i, 2010, 16, 31 - i, "RB"));   // RB1 = 30 ppg ... RB30 = 1 ppg
        return l;
    }

    private static List<Candidate> cands(int n) {
        List<Candidate> c = new ArrayList<>();
        for (int i = 1; i <= n; i++) c.add(new Candidate(i, "P" + i));
        return c;
    }

    private static List<Candidate> rbCands(long... extra) {
        List<Candidate> c = new ArrayList<>();
        for (int i = 1; i <= 30; i++) c.add(new Candidate(100 + i, "RB" + i));
        for (long e : extra) c.add(new Candidate(e, "R" + e));
        return c;
    }

    @Test
    void anEarlyRookieIsValuedBetweenTheFifthAndThirtiethVeteranAtHisPosition() {
        // Rookie 900: RB, round 1 pick 10 (the only early RB rookie -> positional rank 12 + 1 = 13).
        // Rookie 901: RB, round 2 pick 40 (-> 12 + 2 = 14). Rookie 902: RB, round 3 pick 70 -> the seeded tail.
        List<NflSeasonLine> drafted = List.of(line(900, SEASON, 16, 999, "RB"), line(901, SEASON, 16, 999, "RB"),
                line(902, SEASON, 16, 999, "RB"));
        Map<Long, RookiePick> picks = Map.of(900L, new RookiePick(1, 10), 901L, new RookiePick(2, 40),
                902L, new RookiePick(3, 70));
        var cards = build(rbCands(900, 901, 902), thirtyRunningBacks(), List.of(), drafted,
                options(Map.of("RB", 24), picks));

        assertTrue(rankOf(cards, 105) < rankOf(cards, 900), "an early rookie is below the 5th veteran RB");
        assertTrue(rankOf(cards, 900) < rankOf(cards, 130), "...and above the 30th");
        // pinned exactly: valued as the 13th veteran, so right behind him (a tie goes to the veteran) and ahead of the 14th
        assertEquals(rankOf(cards, 113) + 1, rankOf(cards, 900));
        assertTrue(rankOf(cards, 900) < rankOf(cards, 114));
        // the second early rookie is valued as the 14th and keeps the draft order between the two rookies
        assertTrue(rankOf(cards, 900) < rankOf(cards, 901));
        assertEquals(rankOf(cards, 114) + 1, rankOf(cards, 901));
        // a round-3 rookie is NOT placed by his pick: he is in the unranked tail, behind every veteran
        assertTrue(rankOf(cards, 902) > rankOf(cards, 130));
        assertEquals(33, cards.size());
        assertEquals(33, rankOf(cards, 902));
    }

    @Test
    void aRookieCardCarriesHisNflPickAndNoPriorLine() {
        List<NflSeasonLine> drafted = List.of(line(900, SEASON, 16, 999, "RB"), line(902, SEASON, 16, 999, "RB"));
        Map<Long, RookiePick> picks = Map.of(900L, new RookiePick(1, 10), 902L, new RookiePick(5, 150));
        var cards = build(rbCands(900, 902), thirtyRunningBacks(), List.of(), drafted, options(Map.of("RB", 24), picks));
        HistoricalPlayerCard r = card(cards, 900);
        assertEquals(10, r.rookiePick());
        assertNull(r.prior(), "a rookie has no prior line, and the drafted season's 999 yards/game must not appear");
        assertNull(r.team());
        assertEquals(150, card(cards, 902).rookiePick(), "a late pick still shows his slot; he just is not placed by it");
        assertNull(card(cards, 101).rookiePick(), "a veteran has no rookie pick");
    }

    @Test
    void anEarlyRookieAtAPositionWithTooFewVeteransTakesTheLastVeteransValue() {
        // Three veteran RBs (30, 20, 10 ppg); rookie valued as the 13th veteran, which does not exist -> the 3rd.
        List<NflSeasonLine> s1 = List.of(line(1, 2010, 16, 30, "RB"), line(2, 2010, 16, 20, "RB"),
                line(3, 2010, 16, 10, "RB"));
        var cards = build(List.of(new Candidate(1, "a"), new Candidate(2, "b"), new Candidate(3, "c"),
                        new Candidate(900, "rk")), s1, List.of(), List.of(line(900, SEASON, 16, 1, "RB")),
                options(Map.of("RB", 2), Map.of(900L, new RookiePick(1, 3))));
        assertEquals(4, rankOf(cards, 900));
        assertEquals(3, rankOf(cards, 3));
    }

    @Test
    void earlyRookiesAtDifferentPositionsAreOrderedAmongTheirOwnPositionOnly() {
        // The WR rookie taken at pick 1 and the RB rookie taken at pick 2 are each the FIRST early rookie of their own
        // position, so each is valued as that position's 13th veteran; the pick order between positions does not matter.
        List<NflSeasonLine> s1 = new ArrayList<>(thirtyRunningBacks());
        for (int i = 1; i <= 30; i++) s1.add(line(200 + i, 2010, 16, 31 - i, "WR"));
        List<Candidate> c = new ArrayList<>(rbCands(900, 901));
        for (int i = 1; i <= 30; i++) c.add(new Candidate(200 + i, "WR" + i));
        var cards = build(c, s1, List.of(), List.of(line(900, SEASON, 16, 1, "RB"), line(901, SEASON, 16, 1, "WR")),
                options(Map.of("RB", 24, "WR", 24), Map.of(900L, new RookiePick(1, 2), 901L, new RookiePick(1, 1))));
        assertTrue(rankOf(cards, 900) > rankOf(cards, 113));
        assertTrue(rankOf(cards, 900) < rankOf(cards, 114));
        assertTrue(rankOf(cards, 901) > rankOf(cards, 213));
        assertTrue(rankOf(cards, 901) < rankOf(cards, 214));
    }

    // ---- (d) which season decides ------------------------------------------------------------------------

    @Test
    void theNewestQualifyingSeasonDecidesAndASubFloorNewestSeasonIsSkipped() {
        // Player 1: S-1 = 4 games at 99 ppg (below the floor), S-2 = 16 games at 10 ppg -> S-2 decides, and the card says 2009.
        // Player 2: 16 games at 12 ppg in S-1 only. Player 1 must rank BELOW player 2 (10 < 12), not above on the 99.
        List<NflSeasonLine> s1 = List.of(line(1, 2010, 4, 99, "WR"), line(2, 2010, 16, 12, "WR"));
        List<NflSeasonLine> s2 = List.of(line(1, 2009, 16, 10, "WR"));
        var cards = build(cands(2), s1, s2, List.of(), options(Map.of("WR", 1), Map.of()));
        assertTrue(rankOf(cards, 2) < rankOf(cards, 1));
        assertEquals("2009", card(cards, 1).prior().seasonLabel());
        assertEquals("2009", card(cards, 1).prior().bestOfTwo());
    }

    @Test
    void theNewestQualifyingSeasonBeatsAnOlderPeak() {
        // 17 games at 8 ppg last year, 16 at 30 ppg the year before: last year decides (053's rule, kept).
        List<NflSeasonLine> s1 = List.of(line(1, 2010, 17, 8, "WR"), line(2, 2010, 16, 9, "WR"));
        List<NflSeasonLine> s2 = List.of(line(1, 2009, 16, 30, "WR"));
        var cards = build(cands(2), s1, s2, List.of(), options(Map.of("WR", 1), Map.of()));
        assertTrue(rankOf(cards, 2) < rankOf(cards, 1), "9 ppg (last year) outranks 8 ppg (last year) even though 1 peaked at 30");
        assertEquals("2010", card(cards, 1).prior().seasonLabel());
    }

    // ---- (e) the list contract ---------------------------------------------------------------------------

    @Test
    void theListIsRankAscendingFromOneAndTiesBreakByIdAscending() {
        // Three identical receivers: same score, so ids 7, 8, 9 in that order, ranks 1, 2, 3 in LIST order.
        List<NflSeasonLine> s1 = List.of(line(9, 2010, 16, 10, "WR"), line(7, 2010, 16, 10, "WR"),
                line(8, 2010, 16, 10, "WR"));
        var cards = build(List.of(new Candidate(9, "c"), new Candidate(7, "a"), new Candidate(8, "b")), s1,
                List.of(), List.of(), options(Map.of("WR", 3), Map.of()));
        assertEquals(List.of(7L, 8L, 9L), cards.stream().map(HistoricalPlayerCard::id).toList());
        for (int i = 0; i < cards.size(); i++) assertEquals(i + 1, cards.get(i).rank());
    }

    @Test
    void theRankIsAscendingInTheListForAMixedBoard() {
        List<NflSeasonLine> s1 = new ArrayList<>(thirtyRunningBacks());
        var cards = build(rbCands(900), s1, List.of(), List.of(line(900, SEASON, 16, 1, "RB")),
                options(Map.of("RB", 24), Map.of(900L, new RookiePick(1, 3))));
        int prev = 0;
        for (HistoricalPlayerCard c : cards) {
            assertEquals(prev + 1, c.rank());
            prev = c.rank();
        }
        // RB1 (30 ppg) is first: the best over-replacement veteran is the best raw one inside one position
        assertEquals(101, cards.get(0).id());
    }

    // ---- the card ----------------------------------------------------------------------------------------

    @Test
    void theCardShowsPerGameForTheMainKeysAndSeasonTotalsForTheRareOnesAndHidesNothingFromTheDraftedSeason() {
        Map<String, Double> t = new LinkedHashMap<>();
        t.put("pass_yd", 0.0); t.put("pass_td", 0.0); t.put("pass_int", 0.0);
        t.put("rush_yd", 800.0); t.put("rush_td", 8.0); t.put("rec", 32.0); t.put("rec_yd", 320.0);
        t.put("rec_td", 2.0); t.put("fum_lost", 3.0); t.put("pass_2pt", 0.0); t.put("rush_2pt", 0.0);
        t.put("rec_2pt", 1.0); t.put("st_td", 2.0);
        NflSeasonLine v = new NflSeasonLine(1, 2010, "SD", 16, List.of("RB"), t);
        Map<String, Double> full = new LinkedHashMap<>();
        for (String k : NflHistoricalSport.COUNTING) full.put(k, 1.0);
        var cards = HistoricalBoardBuilder.build(SEASON, 7L, full, List.of(new Candidate(1, "Vet")),
                List.of(map(List.of(v)), Map.of()), Map.of(), CFG, options(Map.of("RB", 1), Map.of()));
        HistoricalPlayerCard c = cards.get(0);
        assertEquals("SD", c.team());
        assertNull(c.age());
        assertEquals("2010", c.prior().seasonLabel());
        assertEquals(16, c.prior().games());
        assertEquals(50.0, c.prior().perGame().get("rush_yd"));   // 800 / 16
        assertEquals(0.5, c.prior().perGame().get("rush_td"));
        assertEquals(2.0, c.prior().perGame().get("rec"));
        assertEquals(20.0, c.prior().perGame().get("rec_yd"));
        assertEquals(0.0, c.prior().perGame().get("pass_yd"), "zeros stay in perGame; the web hides them");
        assertEquals(8, c.prior().perGame().size(), "only the yardage/TD/rec/int keys are per game");
        assertEquals(Set.of("fum_lost", "rec_2pt", "st_td"), c.prior().totals().keySet(), "nonzero rare keys, as totals");
        assertEquals(3.0, c.prior().totals().get("fum_lost"));
        assertEquals(2.0, c.prior().totals().get("st_td"));
        // fantasyPoints is the season total over every counted key (all weights 1.0 here): 800+8+32+320+2+3+1+2
        assertEquals(1168.0, c.prior().fantasyPoints());
    }

    @Test
    void thePositionCheckRejectsAnNbaPositionOnAFootballBoard() {
        List<NflSeasonLine> s1 = List.of(new NflSeasonLine(1, 2010, "AAA", 16, List.of("PG"), Map.of("rush_yd", 100.0)));
        assertThrows(IllegalStateException.class, () -> build(cands(1), s1, List.of(), List.of(),
                options(Map.of("QB", 1), Map.of())), "PG is not an NFL position; Position.valueOf accepts it, the sport check refuses it");
    }
}
