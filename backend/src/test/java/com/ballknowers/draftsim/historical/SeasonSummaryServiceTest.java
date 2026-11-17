package com.ballknowers.draftsim.historical;

import com.ballknowers.draftsim.historical.SeasonPage.Finisher;
import com.ballknowers.draftsim.historical.SeasonPage.Mover;
import com.ballknowers.draftsim.historical.SeasonSummaryService.Ranking;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Spec 074 T013: the season page's ordering, not just its shape (bug class 1: a bust that reads as a steal is well
 * formed and pointed backwards). Pure: no Spring, no database. A LOWER rank is better for both ranks, so a bust has a
 * finish rank numerically larger than his going-in rank.
 */
class SeasonSummaryServiceTest {

    private static final List<String> POSITIONS = List.of("QB", "RB", "WR", "TE");

    private static List<HistoricalPlayerCard> board(int n, String position) {
        List<HistoricalPlayerCard> out = new ArrayList<>();
        for (int i = 1; i <= n; i++) out.add(HistoricalTestSupport.card(i, position));   // rank == id
        return out;
    }

    private static Ranking rank(List<HistoricalPlayerCard> cards, int rankedCount, Map<Long, Double> points,
                                int teams) {
        return SeasonSummaryService.rank(cards, rankedCount, id -> points.getOrDefault(id, 0.0), id -> 10, teams,
                POSITIONS);
    }

    private static boolean has(List<Mover> l, long id) {
        return l.stream().anyMatch(m -> m.playerId() == id);
    }

    private static Mover find(List<Mover> l, long id) {
        return l.stream().filter(m -> m.playerId() == id).findFirst().orElseThrow();
    }

    /** Everyone finishes where he was ranked (points fall with the id), then the cases below move one or two. */
    private static Map<Long, Double> inOrder(int n) {
        Map<Long, Double> p = new HashMap<>();
        for (int i = 1; i <= n; i++) p.put((long) i, 1000.0 - i);
        return p;
    }

    @Test
    void aPlayerRankedSecondWhoFinished150thIsABustAndNeverASteal() {
        List<HistoricalPlayerCard> cards = board(300, "RB");
        Map<Long, Double> pts = inOrder(300);
        pts.put(2L, 1000.0 - 150.5);   // 149 players score more than he does: finish 150
        Ranking r = rank(cards, 300, pts, 12);

        Mover bust = find(r.busts(), 2);
        assertEquals(2, bust.goingInRank());
        assertEquals(150, bust.finishRank(), "1 + the 149 who scored more");
        assertFalse(has(r.steals(), 2), "a player who finished far below where he was ranked is never a steal");
        assertEquals(2, r.busts().get(0).playerId(), "and he is the biggest bust: 148 places, nobody else moved more");
    }

    @Test
    void aPlayerRankedFarDownWhoFinished3rdIsAStealAndNeverABust() {
        List<HistoricalPlayerCard> cards = board(300, "WR");
        Map<Long, Double> pts = inOrder(300);
        pts.put(200L, 1000.0 - 2.5);   // ahead of everyone but players 1 and 2
        Ranking r = rank(cards, 300, pts, 12);

        Mover steal = find(r.steals(), 200);
        assertEquals(200, steal.goingInRank());
        assertEquals(3, steal.finishRank());
        assertFalse(has(r.busts(), 200), "ranked 200th going in is outside the early players, and he finished better");
        assertEquals(200, r.steals().get(0).playerId(), "the biggest steal: 197 places up");
    }

    @Test
    void aBustMustHaveBeenTakenEarlyAndAStealMustHaveFinishedEarly() {
        // 12 teams x 5 rounds = 60. Player 70 (going in 70) finishes 150th: a worse finish, but he was never an early pick.
        List<HistoricalPlayerCard> cards = board(300, "RB");
        Map<Long, Double> pts = inOrder(300);
        pts.put(70L, 1000.0 - 150.5);
        pts.put(100L, 1000.0 - 61.5);   // ranked 100th, finished 62nd: better, but not in the first five rounds' worth
        Ranking r = rank(cards, 300, pts, 12);
        assertFalse(has(r.busts(), 70));
        assertFalse(has(r.steals(), 100));
        assertFalse(has(r.steals(), 70));
    }

    @Test
    void aPlayerWhoFinishedWhereHeWasRankedIsNeitherAndTheListsAreNeverPadded() {
        // teams = 1 gives "early" = 5. Player 4 finishes exactly 4th.
        List<HistoricalPlayerCard> cards = board(8, "QB");
        Map<Long, Double> pts = new HashMap<>();
        double[] points = {10, 50, 40, 30, 20, 60, 5, 1};
        for (int i = 0; i < 8; i++) pts.put((long) (i + 1), points[i]);
        // finish ranks: 6:1 (60), 2:2 (50), 3:3 (40), 4:4 (30), 5:5 (20), 1:6 (10), 7:7 (5), 8:8 (1)
        Ranking r = rank(cards, 8, pts, 1);

        assertFalse(has(r.busts(), 4), "finished 4th, ranked 4th");
        assertFalse(has(r.steals(), 4));
        // busts: ranked 1..5 who finished below: 1 (ranked 1, finished 6: 5 places) and 5? 5 finished 5th, no.
        // 2 (ranked 2, finished 2), 3 (3,3), 4 (4,4): level. So exactly one bust, and the list is not padded to 5.
        assertEquals(List.of(1L), r.busts().stream().map(Mover::playerId).toList());
        assertEquals(5, r.busts().get(0).finishRank() - r.busts().get(0).goingInRank());
        // steals: finished in the top 5 and ranked further down: player 6 (ranked 6, finished 1st).
        assertEquals(List.of(6L), r.steals().stream().map(Mover::playerId).toList());
        assertEquals(5, r.steals().get(0).goingInRank() - r.steals().get(0).finishRank());
    }

    @Test
    void anUnrankedTopFinisherIsInNeitherListAndIsCounted() {
        List<HistoricalPlayerCard> cards = board(300, "RB");
        Map<Long, Double> pts = inOrder(300);
        pts.put(280L, 5000.0);   // ranked count is 250, so 280 is in the seeded tail: unranked, and he finished 1st
        pts.put(290L, 998.5);    // unranked, finished 2nd
        pts.put(299L, 3.0);      // unranked, finished far down: not counted
        Ranking r = rank(cards, 250, pts, 12);

        assertFalse(has(r.steals(), 280), "he has no going-in rank, so he cannot beat it");
        assertFalse(has(r.busts(), 280));
        assertFalse(has(r.steals(), 290));
        assertEquals(2, r.unrankedLeftOut());
        Finisher top = r.topOverall().get(0);
        assertEquals(280, top.playerId());
        assertEquals(1, top.finishRank());
        assertNull(top.goingInRank(), "an unranked finisher is listed, with no going-in rank");
    }

    @Test
    void levelPointsShareARankAndTheNextRankSkipsOver() {
        List<HistoricalPlayerCard> cards = board(5, "RB");
        Map<Long, Double> pts = new HashMap<>(Map.of(1L, 50.0, 2L, 50.0, 3L, 40.0, 4L, 40.0, 5L, 10.0));
        Ranking r = rank(cards, 5, pts, 1);
        Map<Long, Integer> rankOf = new HashMap<>();
        for (Finisher f : r.topOverall()) rankOf.put(f.playerId(), f.finishRank());
        assertEquals(1, rankOf.get(1L));
        assertEquals(1, rankOf.get(2L));
        assertEquals(3, rankOf.get(3L), "two players are ahead, so the next rank is 3, not 2");
        assertEquals(3, rankOf.get(4L));
        assertEquals(5, rankOf.get(5L));
        assertEquals(5, r.topOverall().size(), "fewer than 12 players: the list is not padded");
    }

    @Test
    void tiesOrderByPlayerIdSoTwoRunsAgreeWhateverTheInputOrder() {
        List<HistoricalPlayerCard> cards = board(40, "WR");
        Map<Long, Double> pts = new HashMap<>();
        for (int i = 1; i <= 40; i++) pts.put((long) i, (double) (i % 4));   // heavy ties
        List<HistoricalPlayerCard> shuffled = new ArrayList<>(cards);
        Collections.reverse(shuffled);
        Ranking a = rank(cards, 0, pts, 1);
        Ranking b = rank(shuffled, 0, pts, 1);
        assertEquals(a.topOverall(), b.topOverall());
        assertEquals(a.topByPosition(), b.topByPosition());
        // within a level, the lower id first
        List<Finisher> top = a.topOverall();
        for (int i = 1; i < top.size(); i++) {
            if (top.get(i).points() == top.get(i - 1).points()) {
                assertTrue(top.get(i).playerId() > top.get(i - 1).playerId());
            }
        }
        assertEquals(12, top.size());
    }

    @Test
    void positionRowsRankWithinThePositionNotOverall() {
        // RB: ids 1 (5 pts) and 3 (40). WR: ids 2 (50) and 4 (30). Overall order: 2, 3, 4, 1.
        List<HistoricalPlayerCard> cards = new ArrayList<>(List.of(
                HistoricalTestSupport.card(1, "RB"), HistoricalTestSupport.card(2, "WR"),
                HistoricalTestSupport.card(3, "RB"), HistoricalTestSupport.card(4, "WR")));
        Map<Long, Double> pts = Map.of(1L, 5.0, 2L, 50.0, 3L, 40.0, 4L, 30.0);
        Ranking r = rank(cards, 4, pts, 1);

        assertEquals(List.of("RB", "WR"), List.copyOf(r.topByPosition().keySet()), "the sport's position order; empty positions left out");
        List<Finisher> rb = r.topByPosition().get("RB");
        assertEquals(List.of(3L, 1L), rb.stream().map(Finisher::playerId).toList());
        assertEquals(List.of(1, 2), rb.stream().map(Finisher::finishRank).toList(), "rank within RB");
        assertEquals(4, r.topOverall().stream().filter(f -> f.playerId() == 1).findFirst().orElseThrow().finishRank(),
                "the same player is 4th overall");
        assertEquals(1, rb.get(1).goingInRank(), "going in stays the overall board rank");
    }

    @Test
    void moversCarryWeeksPlayedSoAnInjuryReadsAsAnInjury() {
        List<HistoricalPlayerCard> cards = board(100, "RB");
        Map<Long, Double> pts = inOrder(100);
        pts.put(1L, 3.0);
        Ranking r = SeasonSummaryService.rank(cards, 100, id -> pts.get(id), id -> id == 1 ? 2 : 14, 1, POSITIONS);
        assertEquals(2, find(r.busts(), 1).weeksPlayed());
    }
}
