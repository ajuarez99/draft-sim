package com.ballknowers.draftsim.historical;

import com.ballknowers.draftsim.domain.Player;
import com.ballknowers.draftsim.historical.HistoricalDataRepository.GameLine;
import com.ballknowers.draftsim.historical.HistoricalResult.Bust;
import com.ballknowers.draftsim.historical.HistoricalResult.Hindsight;
import com.ballknowers.draftsim.historical.HistoricalResult.Steal;
import com.ballknowers.draftsim.historical.SeasonReplay.Pick;
import com.ballknowers.draftsim.historical.SeasonReplay.PlayerWeeks;
import com.ballknowers.draftsim.historical.SeasonReplay.TeamScorer;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static com.ballknowers.draftsim.historical.HistoricalTestSupport.*;
import static org.junit.jupiter.api.Assertions.*;

/**
 * Spec 053 T021 (written before T022). Each of these pins a DIRECTION, because the original design had the bust
 * formula backwards and a structural test would not have noticed (plan F1, AGENTS.md bug class 1).
 */
class ComparisonsTest {

    private static final Comparisons CMP = new Comparisons(LINEUPS);

    // ---- bust ------------------------------------------------------------------------------------------

    @Test
    void theBustIsThePickTakenFurthestAheadOfWhereHeFinished() {
        // Pick 3 finished 150th (gap +147); pick 160 finished 2nd (gap -158).
        List<Pick> mine = List.of(new Pick(3, 1, 10), new Pick(160, 1, 11));
        Map<Long, Integer> rank = Map.of(10L, 150, 11L, 2);
        Map<Long, Double> value = Map.of(10L, 5.0, 11L, 900.0);
        Bust b = Comparisons.bust(mine, rank, value, Map.of());
        assertNotNull(b);
        assertEquals(3, b.pickNo(), "pick 3 finishing 150th is the bust");
        assertEquals(150, b.seasonValueRank());
        assertEquals(5.0, b.seasonValue());
    }

    @Test
    void pickOneSixtyFinishingSecondIsNeverTheBust() {
        List<Pick> mine = List.of(new Pick(160, 1, 11), new Pick(20, 1, 12));
        // 12 finished 25th (gap +5): the only positive gap. 11 finished 2nd (gap -158) must not win.
        Bust b = Comparisons.bust(mine, Map.of(11L, 2, 12L, 25), Map.of(11L, 900.0, 12L, 300.0), Map.of());
        assertEquals(20, b.pickNo());
    }

    @Test
    void noBustWhenEveryPickFinishedAtOrAheadOfWhereHeWasTaken() {
        List<Pick> mine = List.of(new Pick(5, 1, 1), new Pick(20, 1, 2));
        assertNull(Comparisons.bust(mine, Map.of(1L, 5, 2L, 3), Map.of(1L, 1.0, 2L, 1.0), Map.of()),
                "a gap of exactly 0 or less is not a bust");
    }

    @Test
    void valueRanksAreDescendingWithIdTieBreak() {
        Map<Long, Double> value = Map.of(1L, 10.0, 2L, 30.0, 3L, 30.0);
        Map<Long, Integer> rank = Comparisons.valueRanks(List.of(1L, 2L, 3L), value);
        assertEquals(1, rank.get(2L));
        assertEquals(2, rank.get(3L));
        assertEquals(3, rank.get(1L));
    }

    // ---- steal passed on -------------------------------------------------------------------------------

    @Test
    void theStealExcludesAnyoneTheUserDraftedLater() {
        // X (value 100) is available at pick 1 but the user takes him at pick 5, so he was not "passed on".
        // Z (value 50) was passed on at pick 1, in favour of Y (value 10).
        List<Pick> all = List.of(new Pick(1, 1, 1L /*Y*/), new Pick(2, 2, 90L), new Pick(5, 1, 3L /*X*/));
        List<Pick> mine = List.of(all.get(0), all.get(2));
        Map<Long, Double> value = new HashMap<>(Map.of(1L, 10.0, 3L, 100.0, 4L, 50.0, 90L, 1.0));
        Steal s = Comparisons.steal(mine, all, List.of(1L, 3L, 4L, 90L), value, Map.of());
        assertNotNull(s);
        assertEquals(1, s.pickNo());
        assertEquals(10.0, s.takenValue());
        assertEquals(50.0, s.passedOnValue(), "Z, never X");
    }

    @Test
    void theStealOnlyConsidersPlayersStillAvailableAtThatPick() {
        // W (value 500) was taken by a bot at pick 2, BEFORE the user's pick 3, so he cannot be passed on.
        List<Pick> all = List.of(new Pick(2, 2, 7L /*W*/), new Pick(3, 1, 1L));
        Steal s = Comparisons.steal(List.of(all.get(1)), all, List.of(1L, 7L, 8L), Map.of(1L, 10.0, 7L, 500.0, 8L, 20.0),
                Map.of());
        assertEquals(20.0, s.passedOnValue());
    }

    @Test
    void noStealWhenNobodyAvailableBeatWhatTheUserTook() {
        List<Pick> all = List.of(new Pick(1, 1, 1L));
        assertNull(Comparisons.steal(all, all, List.of(1L, 2L), Map.of(1L, 100.0, 2L, 50.0), Map.of()));
    }

    // ---- hindsight -------------------------------------------------------------------------------------

    /**
     * Five centres and two point guards, one week. Centres fit C, UTIL, UTIL: three start, a fourth never does.
     * A hindsight that took the best RAW value at pick 4 would take the fourth centre (97); the lineup-aware one
     * takes a guard (10), because the guard raises the team's total and the centre does not.
     */
    @Test
    void hindsightIsLineupAwareAndSkipsAFourthCentreWhoNeverStarts() {
        Map<Long, HistoricalPlayerCard> cards = new LinkedHashMap<>();
        List<GameLine> games = new ArrayList<>();
        long[] centre = {1, 2, 3, 4, 5};
        int[] pts = {100, 99, 98, 97, 96};
        for (int i = 0; i < 5; i++) {
            cards.put(centre[i], card(centre[i], "C"));
            games.add(game(centre[i], centre[i], 1, pts[i]));
        }
        cards.put(6L, card(6, "PG"));
        games.add(game(6, 6, 1, 10));
        cards.put(7L, card(7, "PG"));
        games.add(game(7, 7, 1, 9));
        // the real draft: the user took junk at 1..4 (ids 20..23), nobody took anyone useful
        for (long j = 20; j <= 23; j++) cards.put(j, card(j, "SF"));
        List<Pick> real = List.of(new Pick(1, 1, 20L), new Pick(2, 1, 21L), new Pick(3, 1, 22L), new Pick(4, 1, 23L));

        PlayerWeeks pw = REPLAY.scorePlayerWeeks(Map.of("pts", 1.0), games);
        Map<Long, Player> players = new HashMap<>();
        cards.values().forEach(c -> players.put(c.id(), HistoricalBoardBuilder.toEntry(c).player()));
        TeamScorer team = new TeamScorer(LINEUPS, settings(12), rules(), players, pw);
        Map<Long, Double> value = new HashMap<>();
        cards.keySet().forEach(id -> value.put(id, pw.seasonValue(id)));

        Hindsight h = CMP.hindsight(List.of(1, 2, 3, 4), real, cards.keySet(), team, 1, value, cards, 0.0);

        assertEquals(List.of(1L, 2L, 3L, 6L), h.picks().stream().map(p -> p.player().id()).toList(),
                "centres 1-3 start, so pick 4 is the best guard, not the 97-point fourth centre");
        assertEquals(100 + 99 + 98 + 10, h.pointsFor());
        assertEquals(Comparisons.HINDSIGHT_LABEL, h.label());
    }

    @Test
    void hindsightOnlyDraftsPlayersStillAvailableInTheRealDraftAtThatPick() {
        // Player 1 (the best) is taken by a bot at pick 1; the user's first pick is pick 2.
        Map<Long, HistoricalPlayerCard> cards = new LinkedHashMap<>();
        List<GameLine> games = new ArrayList<>();
        for (long id = 1; id <= 3; id++) {
            cards.put(id, card(id, "PG"));
            games.add(game(id, id, 1, (int) (40 - id * 10)));   // 30, 20, 10
        }
        List<Pick> real = List.of(new Pick(1, 2, 1L), new Pick(2, 1, 3L));
        PlayerWeeks pw = REPLAY.scorePlayerWeeks(Map.of("pts", 1.0), games);
        Map<Long, Player> players = new HashMap<>();
        cards.values().forEach(c -> players.put(c.id(), HistoricalBoardBuilder.toEntry(c).player()));
        TeamScorer team = new TeamScorer(LINEUPS, settings(12), rules(), players, pw);
        Map<Long, Double> value = new HashMap<>();
        cards.keySet().forEach(id -> value.put(id, pw.seasonValue(id)));

        Hindsight h = CMP.hindsight(List.of(2), real, cards.keySet(), team, 1, value, cards, 0.0);
        assertEquals(2L, h.picks().get(0).player().id(), "player 1 was gone by pick 2");
    }

    @Test
    void userBeatIsTrueExactlyWhenHindsightScoredLess() {
        Map<Long, HistoricalPlayerCard> cards = new LinkedHashMap<>();
        cards.put(1L, card(1, "PG"));
        PlayerWeeks pw = REPLAY.scorePlayerWeeks(Map.of("pts", 1.0), List.of(game(1, 1, 1, 30)));
        Map<Long, Player> players = Map.of(1L, HistoricalBoardBuilder.toEntry(cards.get(1L)).player());
        TeamScorer team = new TeamScorer(LINEUPS, settings(12), rules(), players, pw);
        Map<Long, Double> value = Map.of(1L, 30.0);

        Hindsight beaten = CMP.hindsight(List.of(1), List.of(), cards.keySet(), team, 1, value, cards, 31.0);
        assertEquals(30.0, beaten.pointsFor());
        assertTrue(beaten.userBeatIt(), "user 31 > hindsight 30");
        Hindsight notBeaten = CMP.hindsight(List.of(1), List.of(), cards.keySet(), team, 1, value, cards, 29.0);
        assertFalse(notBeaten.userBeatIt());
        Hindsight equal = CMP.hindsight(List.of(1), List.of(), cards.keySet(), team, 1, value, cards, 30.0);
        assertFalse(equal.userBeatIt(), "a tie is not a win");
    }
}
