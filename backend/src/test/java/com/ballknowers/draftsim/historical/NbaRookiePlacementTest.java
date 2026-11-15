package com.ballknowers.draftsim.historical;

import com.ballknowers.draftsim.historical.HistoricalBoardBuilder.Candidate;
import com.ballknowers.draftsim.historical.HistoricalBoardBuilder.RankConfig;
import com.ballknowers.draftsim.historical.HistoricalBoardBuilder.RookiePick;
import com.ballknowers.draftsim.historical.HistoricalBoardBuilder.RookieRank;
import com.ballknowers.draftsim.historical.HistoricalDataRepository.SeasonLine;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.StringReader;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Basketball rookies placed by their NBA draft pick (Allan 2026-11-15). The placement is a ranking rule, so these
 * assert ORDER (AGENTS.md bug class 1): an earlier pick ranks ahead of a later one, and a drafted rookie ranks
 * ahead of the random tail, never the other way round.
 */
class NbaRookiePlacementTest {

    private static final int SEASON = 2018;
    private static final RankConfig CFG = new RankConfig(72, 20);
    private static final Map<String, Double> SCORING = Map.of("pts", 1.0);

    private static SeasonLine line(long id, int season, double ppg) {
        return new SeasonLine(id, season, "AAA", 25, 70, List.of("PG"), ppg * 70, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0,
                null, null, null, null);
    }

    /** Ten veterans, ids 1..10, best first (id 1 scored the most last season), and whoever else is asked for. */
    private static List<HistoricalPlayerCard> board(List<Long> rookieIds, Map<Long, RookiePick> picks,
                                                    RookieRank rule) {
        List<Candidate> candidates = new ArrayList<>();
        Map<Long, SeasonLine> prior = new HashMap<>();
        Map<Long, SeasonLine> drafted = new HashMap<>();
        for (long id = 1; id <= 10; id++) {
            candidates.add(new Candidate(id, "Vet " + id));
            prior.put(id, line(id, SEASON - 1, 40 - id));
        }
        for (long id : rookieIds) {
            candidates.add(new Candidate(id, "Rookie " + id));
            drafted.put(id, line(id, SEASON, 99));   // a huge rookie year must NOT move him: that would be hindsight
        }
        return HistoricalBoardBuilder.build(SEASON, 7L, SCORING, candidates, List.of(prior), drafted, CFG,
                rule == null ? HistoricalBoardBuilder.nba() : HistoricalBoardBuilder.nba(picks, rule));
    }

    private static List<Long> ids(List<HistoricalPlayerCard> cards) {
        return cards.stream().map(HistoricalPlayerCard::id).toList();
    }

    @Test
    void theFirstPickSitsBehindTheVeteranAtFirstRankAndEachLaterPickFurtherDown() {
        // first-rank 3, step 2: pick 1 level with veteran rank 3, pick 2 with rank 5, pick 3 with rank 7.
        Map<Long, RookiePick> picks = Map.of(101L, new RookiePick(1, 1), 102L, new RookiePick(1, 2),
                103L, new RookiePick(1, 3));
        var cards = board(List.of(103L, 101L, 102L), picks, new RookieRank(3, 2));
        assertEquals(List.of(1L, 2L, 3L, 101L, 4L, 5L, 102L, 6L, 7L, 103L, 8L, 9L, 10L), ids(cards));
        assertEquals(4, cards.get(3).rank(), "ranks are renumbered with the rookies in place");
        assertEquals(1, cards.get(3).rookiePick(), "the card can say which pick he was");
        assertNull(cards.get(3).prior(), "and still shows no prior line");
    }

    @Test
    void anEarlierPickAlwaysRanksAheadOfALaterOneEvenPastTheLastVeteran() {
        // Picks 20 and 30 both land past the tenth veteran; pick 2 lands inside the list.
        Map<Long, RookiePick> picks = Map.of(101L, new RookiePick(1, 30), 102L, new RookiePick(1, 20),
                103L, new RookiePick(1, 2));
        var order = ids(board(List.of(101L, 102L, 103L), picks, new RookieRank(3, 2)));
        assertTrue(order.indexOf(103L) < order.indexOf(102L), "pick 2 before pick 20");
        assertTrue(order.indexOf(102L) < order.indexOf(101L), "pick 20 before pick 30");
        assertEquals(List.of(102L, 101L), order.subList(11, 13), "behind every veteran, in draft order");
    }

    @Test
    void aRookieWithNoKnownPickStaysBehindEveryPlacedRookie() {
        var order = ids(board(List.of(101L, 102L), Map.of(101L, new RookiePick(2, 45)), new RookieRank(3, 2)));
        assertEquals(101L, order.get(10), "the drafted rookie follows the veterans");
        assertEquals(102L, order.get(11), "the unknown one is the random tail");
    }

    @Test
    void withNoRuleEveryRookieIsInTheTailAndShowsNoPick() {
        var cards = board(List.of(101L), Map.of(), null);
        assertEquals(101L, cards.get(10).id());
        assertNull(cards.get(10).rookiePick());
    }

    // ---- the name match --------------------------------------------------------------------------------

    private static NbaDraftPicks picks(String rows) throws IOException {
        return NbaDraftPicks.parse(new StringReader("# comment\nseason,pick,round,player\n" + rows));
    }

    @Test
    void aFirstSeasonPlayerIsFoundByNameAcrossAccentsAndSuffixes() throws IOException {
        NbaDraftPicks draft = picks("2018,3,1,Luka Dončić\n2018,4,1,Jaren Jackson Jr.\n2018,36,2,\"Smith, John\"\n");
        List<Candidate> cands = List.of(new Candidate(1, "Old Vet"), new Candidate(2, "Luka Doncic"),
                new Candidate(3, "Jaren Jackson"), new Candidate(4, "Nobody Drafted"));
        Map<Long, RookiePick> found = draft.rookiesOf(2018, cands, Set.of(1L), 3);
        assertEquals(Map.of(2L, new RookiePick(1, 3), 3L, new RookiePick(1, 4)), found);
    }

    @Test
    void aVeteranIsNeverMatchedToASameNamedDraftee() throws IOException {
        NbaDraftPicks draft = picks("2018,1,1,Sam Player\n");
        var found = draft.rookiesOf(2018, List.of(new Candidate(1, "Sam Player"), new Candidate(2, "Other Vet")),
                Set.of(1L, 2L), 3);
        assertTrue(found.isEmpty());
    }

    @Test
    void aDraftOlderThanTheWindowDoesNotCountAndTheNewestOneWins() throws IOException {
        NbaDraftPicks draft = picks("2014,3,1,Late Arrival\n2010,1,1,Too Old\n2016,9,1,Twice Drafted\n2018,50,2,Twice Drafted\n");
        List<Candidate> cands = List.of(new Candidate(1, "Vet"), new Candidate(2, "Late Arrival"),
                new Candidate(3, "Too Old"), new Candidate(4, "Twice Drafted"));
        var found = draft.rookiesOf(2016, cands, Set.of(1L), 3);
        assertEquals(new RookiePick(1, 3), found.get(2L), "drafted 2014, first played 2016: inside three seasons");
        assertFalse(found.containsKey(3L), "2010 is six seasons before 2016");
        assertEquals(new RookiePick(1, 9), found.get(4L), "a 2018 draft is in the future of a 2016 board");
    }

    @Test
    void anAmbiguousNameMatchesNobody() throws IOException {
        NbaDraftPicks draft = picks("2018,5,1,Two Men\n2018,40,2,Two Men\n2018,7,1,One Name\n");
        List<Candidate> cands = List.of(new Candidate(1, "Vet"), new Candidate(2, "Two Men"),
                new Candidate(3, "One Name"), new Candidate(4, "One Name"));
        assertTrue(draft.rookiesOf(2018, cands, Set.of(1L), 3).isEmpty(),
                "one name drafted twice, and one drafted name on two rookies: neither can be told apart");
    }

    @Test
    void aBoardWithNoVeteranCannotTellWhoIsARookie() throws IOException {
        NbaDraftPicks draft = picks("2018,1,1,Some Body\n");
        assertTrue(draft.rookiesOf(2018, List.of(new Candidate(1, "Some Body")), Set.of(), 3).isEmpty());
    }

    @Test
    void theBundledFileLoadsAndKnowsARealDraft() {
        NbaDraftPicks draft = NbaDraftPicks.fromClasspath();
        List<Candidate> cands = List.of(new Candidate(1, "Vet"), new Candidate(2, "Michael Jordan"),
                new Candidate(3, "Hakeem Olajuwon"));
        var found = draft.rookiesOf(1984, cands, Set.of(1L), 3);
        assertEquals(new RookiePick(1, 3), found.get(2L));
        assertEquals(new RookiePick(1, 1), found.get(3L));
    }
}
