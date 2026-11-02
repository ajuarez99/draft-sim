package com.ballknowers.draftsim.historical;

import com.ballknowers.draftsim.domain.BoardEntry;
import com.ballknowers.draftsim.historical.HistoricalBoardBuilder.Candidate;
import com.ballknowers.draftsim.historical.HistoricalBoardBuilder.RankConfig;
import com.ballknowers.draftsim.historical.HistoricalDataRepository.SeasonLine;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Spec 053 T015 / T024. The ranking is the part of this feature that can be well-formed and pointed
 * backwards (AGENTS.md bug class 1), so these assert PREFERENCE ORDER, not just counts.
 */
class HistoricalBoardBuilderTest {

    private static final int SEASON = 1995;
    private static final RankConfig CFG = new RankConfig(72, 20);

    private static Map<String, Double> scoring(String... keys) {
        Map<String, Double> all = new LinkedHashMap<>();
        all.put("pts", 0.5); all.put("reb", 1.0); all.put("ast", 1.0); all.put("stl", 2.0); all.put("blk", 2.0);
        all.put("to", -1.0); all.put("tpm", 0.5); all.put("dd", 1.0); all.put("td", 2.0);
        all.put("bonus_pt_40p", 2.0);
        if (keys.length == 0) return all;
        Map<String, Double> m = new LinkedHashMap<>();
        for (String k : keys) m.put(k, all.get(k));
        return m;
    }

    /** A line with the given per-game points and rebounds over {@code games} games. */
    private static SeasonLine line(long id, int season, int games, double ppg, double rpg, List<String> pos,
                                   String team, Integer age) {
        return new SeasonLine(id, season, team, age, games, pos, ppg * games, rpg * games, 0.0, 0.0, 0.0, 0.0,
                0.0, null, null, null, null);
    }

    private static SeasonLine line(long id, int season, int games, double ppg) {
        return line(id, season, games, ppg, 0, List.of("PG"), "AAA", 25);
    }

    private static Map<Long, SeasonLine> map(SeasonLine... lines) {
        Map<Long, SeasonLine> m = new HashMap<>();
        for (SeasonLine l : lines) m.put(l.playerId(), l);
        return m;
    }

    private static List<HistoricalPlayerCard> build(List<Candidate> c, Map<Long, SeasonLine> p1,
                                                    Map<Long, SeasonLine> p2, Map<Long, SeasonLine> d,
                                                    Map<String, Double> scoring) {
        return HistoricalBoardBuilder.build(SEASON, 42L, scoring, c, List.of(p1, p2), d, CFG);
    }

    private static List<HistoricalPlayerCard> build3(List<Candidate> c, Map<Long, SeasonLine> p1,
                                                     Map<Long, SeasonLine> p2, Map<Long, SeasonLine> p3,
                                                     Map<String, Double> scoring) {
        return HistoricalBoardBuilder.build(SEASON, 42L, scoring, c, List.of(p1, p2, p3), Map.of(), CFG);
    }

    @Test
    void aQualifyingLineThreeSeasonsBackStillRanksTheStar() {
        // Jordan 1995-96: S-1 = 17 games (below floor), S-2 = none (retired), S-3 = 78 games at 30 ppg.
        var cards = build3(List.of(new Candidate(1, "Star"), new Candidate(2, "Vet"), new Candidate(3, "Rookie")),
                map(line(1, 1994, 17, 30), line(2, 1994, 82, 20), line(3, 1994, 30, 5)),
                Map.of(), map(line(1, 1992, 78, 30)), scoring("pts"));
        assertTrue(rankOf(cards, 1) < rankOf(cards, 2), "30 ppg from S-3 must outrank a 20 ppg veteran");
        assertEquals("1992-93", cards.get(0).prior().bestOfTwo(), "the card names the season actually used");
        assertEquals(78, cards.get(0).prior().games());
    }

    @Test
    void theNewestQualifyingSeasonDecidesNotThePeak() {
        // A declined: S-1 = 70 G at 15 ppg, S-2 = 80 G at 30 ppg. Ranked on S-1, so BELOW a 20 ppg 82-game vet.
        var cards = build(List.of(new Candidate(1, "Declined"), new Candidate(2, "Vet")),
                map(line(1, 1994, 70, 15), line(2, 1994, 82, 20)),
                map(line(1, 1993, 80, 30)), Map.of(), scoring("pts"));
        assertTrue(rankOf(cards, 2) < rankOf(cards, 1), "judged on last year (15 ppg), not the 30 ppg peak");
        assertEquals("1994-95", cards.stream().filter(c -> c.id() == 1).findFirst().orElseThrow().prior().bestOfTwo());
    }

    @Test
    void aLineFourSeasonsBackIsOutsideTheWindow() {
        // Only an S-4 line qualifies; the window is 3, so he is in the unranked tail behind the 5 ppg vet.
        var cards = build3(List.of(new Candidate(1, "Old"), new Candidate(2, "Vet")),
                map(line(1, 1994, 10, 30), line(2, 1994, 82, 5)), Map.of(), Map.of(), scoring("pts"));
        assertEquals(2, cards.size());
        assertTrue(rankOf(cards, 2) < rankOf(cards, 1), "the unqualified player is in the tail");
        assertNull(cards.get(1).prior().bestOfTwo(), "no qualifying line in the window");
    }

    private static int rankOf(List<HistoricalPlayerCard> cards, long id) {
        return cards.stream().filter(c -> c.id() == id).findFirst().orElseThrow().rank();
    }

    @Test
    void perGameAndTheGamesFloorBeatAHalfSeasonOfHeroics() {
        // A: 30 ppg in only 17 games last year (below the 20-game floor), 30 ppg over 82 the year before.
        // B: 15 ppg over 82 last year. A is the better player; ranking must not be fooled by the short season.
        var cards = build(List.of(new Candidate(1, "A"), new Candidate(2, "B")),
                map(line(1, 1994, 17, 30), line(2, 1994, 82, 15)),
                map(line(1, 1993, 82, 30)), Map.of(), scoring("pts"));
        assertTrue(rankOf(cards, 1) < rankOf(cards, 2), "30 ppg (best of two, floor-qualified) must outrank 15 ppg");
        assertEquals("1993-94", cards.get(0).prior().bestOfTwo(), "the ranking used the 82-game season");
        assertEquals(82, cards.get(0).prior().games(), "the card shows the line the ranking used");
    }

    @Test
    void aTotalsRankerWouldBeWrongHere() {
        // 82 games at 10 ppg (820 pts) vs 25 games at 30 ppg (750 pts): per-game puts the 30 ppg man first
        // even though his season total is lower.
        var cards = build(List.of(new Candidate(1, "Volume"), new Candidate(2, "Efficient")),
                map(line(1, 1994, 82, 10), line(2, 1994, 25, 30)), Map.of(), Map.of(), scoring("pts"));
        assertTrue(rankOf(cards, 2) < rankOf(cards, 1));
    }

    @Test
    void aHigherScorerIsNeverRankedLower() {
        Random r = new Random(7);
        List<Candidate> cs = new ArrayList<>();
        Map<Long, SeasonLine> p1 = new HashMap<>();
        Map<Long, Double> ppg = new HashMap<>();
        for (long id = 1; id <= 200; id++) {
            cs.add(new Candidate(id, "P" + id));
            double v = 5 + r.nextInt(300) / 10.0;
            ppg.put(id, v);
            p1.put(id, line(id, 1994, 50 + r.nextInt(33), v));
        }
        var cards = build(cs, p1, Map.of(), Map.of(), scoring("pts"));
        for (int i = 1; i < cards.size(); i++) {
            double prev = ppg.get(cards.get(i - 1).id()), cur = ppg.get(cards.get(i).id());
            assertTrue(prev >= cur, "rank " + i + " (" + prev + ") scores below rank " + (i + 1) + " (" + cur + ")");
        }
        assertEquals(1, cards.get(0).rank());
        for (int i = 0; i < cards.size(); i++) assertEquals(i + 1, cards.get(i).rank(), "ranks are 1..n in list order");
    }

    @Test
    void theListIsSortedByAdpAscendingAsTheEngineReads() {
        var cards = build(List.of(new Candidate(1, "A"), new Candidate(2, "B"), new Candidate(3, "C")),
                map(line(1, 1994, 70, 10), line(2, 1994, 70, 20), line(3, 1994, 70, 15)), Map.of(), Map.of(),
                scoring("pts"));
        List<BoardEntry> entries = cards.stream().map(HistoricalBoardBuilder::toEntry).toList();
        for (int i = 1; i < entries.size(); i++) assertTrue(entries.get(i - 1).adp() < entries.get(i).adp());
        assertEquals(2L, entries.get(0).player().id(), "the best player has the SMALLEST adp");
        assertEquals("h2", entries.get(0).player().sleeperId());
    }

    @Test
    void ties_breakByPlayerIdAscending() {
        var cards = build(List.of(new Candidate(9, "Nine"), new Candidate(3, "Three"), new Candidate(5, "Five")),
                map(line(9, 1994, 70, 20), line(3, 1994, 70, 20), line(5, 1994, 70, 20)), Map.of(), Map.of(),
                scoring("pts"));
        assertEquals(List.of(3L, 5L, 9L), cards.stream().map(HistoricalPlayerCard::id).toList());
    }

    @Test
    void aRookieRanksAfterEveryVeteranAndTheTailIsSeededNotInputOrdered() {
        // A terrible veteran (3 ppg) still outranks any rookie.
        List<Candidate> cs = new ArrayList<>();
        Map<Long, SeasonLine> p1 = new HashMap<>(), d = new HashMap<>();
        cs.add(new Candidate(1, "Scrub"));
        p1.put(1L, line(1, 1994, 30, 3));
        for (long id = 100; id < 120; id++) {   // twenty rookies: no prior line, a drafted-season line only
            cs.add(new Candidate(id, "R" + id));
            d.put(id, line(id, SEASON, 70, 40, 0, List.of("SF"), "RKY", 19));
        }
        var cards = build(cs, p1, Map.of(), d, scoring("pts"));
        assertEquals(1L, cards.get(0).id(), "the scrub is first because every rookie ranks after every veteran");
        List<Long> tail = cards.subList(1, cards.size()).stream().map(HistoricalPlayerCard::id).toList();
        assertNotEquals(tail.stream().sorted().toList(), tail, "the unranked tail is shuffled by the seed");
        assertEquals(tail, build(cs, p1, Map.of(), d, scoring("pts")).subList(1, 21).stream()
                .map(HistoricalPlayerCard::id).toList(), "...and the same seed gives the same order");
        // a rookie's card has no team, no age, no prior line; positions come from the drafted-season line
        HistoricalPlayerCard rookie = cards.stream().filter(c -> c.id() == 100).findFirst().orElseThrow();
        assertNull(rookie.team());
        assertNull(rookie.age());
        assertNull(rookie.prior());
        assertEquals(List.of("SF"), rookie.positions());
    }

    @Test
    void teamAndAgeComeFromTheLineTheCardShowsNotFromAnEmptySeasonMinusOne() {
        // S-1 line has too few games and no team (a Sleeper totals line); the shown/ranking line is S-2.
        var cards = build(List.of(new Candidate(1, "Vet")),
                map(line(1, 1994, 5, 20, 0, List.of("PG"), null, null)),
                map(line(1, 1993, 80, 20, 0, List.of("PG"), "BBB", 24)), Map.of(), scoring("pts"));
        assertEquals("BBB", cards.get(0).team(), "the card header agrees with the line it shows");
        assertEquals(24, cards.get(0).age());
        assertEquals("1993-94", cards.get(0).prior().seasonLabel());
    }

    @Test
    void cardsContainNoDraftedSeasonStats() throws Exception {
        // The drafted-season line carries sentinels that must not appear anywhere in the serialized board.
        SeasonLine drafted = new SeasonLine(1, SEASON, "ZZZ", 99, 77, List.of("PG"), 31337.0, 4242.0, 5151.0,
                606.0, 707.0, 808.0, 909.0, null, null, null, null);
        var cards = build(List.of(new Candidate(1, "Vet")), map(line(1, 1994, 80, 20, 5, List.of("PG"), "AAA", 25)),
                Map.of(), map(drafted), scoring());
        String text = new ObjectMapper().writeValueAsString(cards);
        for (String s : List.of("31337", "4242", "5151", "ZZZ", "\"age\":99", "\"games\":77", "1995-96")) {
            assertFalse(text.contains(s), "drafted-season value leaked into the card: " + s);
        }
        assertEquals("AAA", cards.get(0).team(), "team is the PRIOR season's");
        assertEquals(25, cards.get(0).age());
        assertEquals("1994-95", cards.get(0).prior().seasonLabel());
    }

    @Test
    void aCardCarriesOnlyCountedCountingStatsAndExcludesBonuses() {
        // Early-era scoring: pts/reb/ast only. The season-file line has steals and blocks; the card must not.
        SeasonLine l = new SeasonLine(1, 1994, "AAA", 25, 80, List.of("C"), 1600.0, 800.0, 160.0, 200.0, 150.0,
                100.0, 10.0, null, null, null, null);
        var card = build(List.of(new Candidate(1, "Big")), map(l), Map.of(), Map.of(),
                scoring("pts", "reb", "ast", "dd", "bonus_pt_40p")).get(0);
        assertEquals(List.of("pts", "reb", "ast"), List.copyOf(card.prior().perGame().keySet()));
        assertEquals(20.0, card.prior().perGame().get("pts"));
        // 0.5*1600 + 800 + 160, no dd/td/bonus terms
        assertEquals(1760.0, card.prior().fantasyPoints());
    }

    @Test
    void a197576CardCarriesNoStealsOrBlocksEvenThoughTheSeasonFileHasThem() {
        // F12, end to end through the era filter: 1975-76 box coverage for stl/blk is ~62-63%, under the
        // 0.90 threshold, so they are excluded; the season-file line still has 150 stl / 100 blk.
        Map<String, Double> cov = new LinkedHashMap<>();
        cov.put("pts", 1.0); cov.put("reb", 0.988); cov.put("ast", 0.987); cov.put("stl", 0.615);
        cov.put("blk", 0.633); cov.put("to", 0.0); cov.put("tpm", 0.0);
        EraScoring.Era era = EraScoring.resolve(cov, java.util.Set.of("pts", "reb", "ast", "stl", "blk"),
                EraScoring.KAGGLE, scoring(), 0.90);
        assertTrue(era.excluded().containsKey("stl") && era.excluded().containsKey("blk"));
        SeasonLine l = new SeasonLine(1, 1974, "AAA", 25, 80, List.of("C"), 1600.0, 800.0, 160.0, 150.0, 100.0,
                100.0, 0.0, null, null, null, null);
        var card = HistoricalBoardBuilder.build(1975, 42L, era.scoring(), List.of(new Candidate(1, "Big")),
                List.of(map(l), Map.of()), Map.of(), CFG).get(0);
        assertFalse(card.prior().perGame().containsKey("stl"));
        assertFalse(card.prior().perGame().containsKey("blk"));
        assertFalse(card.prior().perGame().containsKey("to"));
        assertEquals(1760.0, card.prior().fantasyPoints(), "0.5*1600 + 800 + 160; steals and blocks add nothing");
    }

    @Test
    void positionalRankCountsWithinThePrimaryPositionInBoardOrder() {
        var cards = build(List.of(new Candidate(1, "G1"), new Candidate(2, "C1"), new Candidate(3, "G2")),
                map(line(1, 1994, 70, 30, 0, List.of("PG", "SG"), "A", 25), line(2, 1994, 70, 25, 0, List.of("C"), "A", 25),
                        line(3, 1994, 70, 20, 0, List.of("PG"), "A", 25)), Map.of(), Map.of(), scoring("pts"));
        assertEquals(List.of(1, 1, 2), cards.stream().map(HistoricalPlayerCard::positionalRank).toList());
    }

    @Test
    void anEmptyPositionSetFailsLoudInsteadOfBecomingAWideReceiver() {
        var ex = assertThrows(IllegalStateException.class, () ->
                build(List.of(new Candidate(1, "Nobody")), map(line(1, 1994, 70, 20, 0, List.of(), "A", 25)),
                        Map.of(), Map.of(), scoring("pts")));
        assertTrue(ex.getMessage().contains("empty position"));
    }

    @Test
    void aPlayerWithNoLineAtAllIsLeftOffTheBoard() {
        var cards = build(List.of(new Candidate(1, "Known"), new Candidate(2, "Mystery")),
                map(line(1, 1994, 70, 20)), Map.of(), Map.of(), scoring("pts"));
        assertEquals(1, cards.size());
    }
}
