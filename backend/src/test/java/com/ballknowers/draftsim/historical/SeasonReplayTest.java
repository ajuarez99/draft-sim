package com.ballknowers.draftsim.historical;

import com.ballknowers.draftsim.historical.HistoricalDataRepository.GameLine;
import com.ballknowers.draftsim.historical.SeasonReplay.Pick;
import com.ballknowers.draftsim.historical.SeasonReplay.PlayerWeeks;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static com.ballknowers.draftsim.historical.HistoricalTestSupport.*;
import static org.junit.jupiter.api.Assertions.*;

/** Spec 053 T017: lock-in best-ball scoring, the lineup, the schedule and the standings. */
class SeasonReplayTest {

    private static Map<String, Double> ptsAndTo() {
        Map<String, Double> m = new LinkedHashMap<>();
        m.put("pts", 1.0);
        m.put("to", -1.0);
        return m;
    }

    @Test
    void aPlayerWeekIsHisBestSingleGameNotTheSum() {
        PlayerWeeks pw = REPLAY.scorePlayerWeeks(ptsAndTo(), List.of(
                game(1, 1, 1, 0, 10, null), game(1, 2, 1, 2, 25, null), game(1, 3, 1, 4, 18, null)));
        assertEquals(25.0, pw.get(1, 1).points());
        assertEquals(DAY.plusDays(2), pw.get(1, 1).date(), "the locked game is the 25-point one");
        assertNull(pw.get(1, 2), "no game that week, no entry");
    }

    @Test
    void aWeekWhoseOnlyGameIsNegativeKeepsItsNegativeMax() {
        // 0 points and 2 turnovers is -2 under {pts 1, to -1}: lock-in does not floor a bad week at zero.
        PlayerWeeks pw = REPLAY.scorePlayerWeeks(ptsAndTo(), List.of(game(1, 1, 1, 0, 0, 2)));
        assertEquals(-2.0, pw.get(1, 1).points());
        assertEquals(0.0, pw.positiveValue(1), "and it adds nothing to his positive upper bound");
    }

    @Test
    void aCountedStatMissingFromABoxScoreIsZeroAndAnUncountedOneNeverContributes() {
        Map<String, Double> scoring = new LinkedHashMap<>();
        scoring.put("pts", 1.0);
        scoring.put("reb", 1.0);          // counted, but this box score has no rebounds recorded
        // ast is NOT counted: the line below has 99 assists and they must not matter.
        GameLine g = new GameLine(1, 1, 1995, DAY, 1, "AAA", 20, null, 99, null, null, null, null, null, null, null,
                null, null, null, null, null, null, null, null, null);
        assertEquals(20.0, REPLAY.scorePlayerWeeks(scoring, List.of(g)).get(1, 1).points());
    }

    @Test
    void theLineupObeysTheNbaRosterEligibility() {
        // Team 1 drafted five centres who each scored 50. Only C, UTIL, UTIL can hold a centre, so 3 start.
        List<Pick> picks = new ArrayList<>();
        List<GameLine> games = new ArrayList<>();
        Map<Long, HistoricalPlayerCard> cards = new LinkedHashMap<>();
        for (long id = 1; id <= 5; id++) {
            picks.add(new Pick((int) id, 1, id));
            games.add(game(id, id, 1, 50));
            cards.put(id, card(id, "C"));
        }
        picks.add(new Pick(6, 2, 6));
        games.add(game(6, 6, 1, 10));
        cards.put(6L, card(6, "PG"));
        for (long id = 7; id <= 30; id++) cards.put(id, card(id, "SF"));   // undrafted filler

        HistoricalResult r = REPLAY.replay(input(picks, cards, games, 1, 1L));
        var starters = r.lineups().get("1").get(0).starters();
        assertEquals(3, starters.size(), "three centres start, the other two sit");
        assertEquals(150.0, starters.stream().mapToDouble(HistoricalResult.Starter::points).sum());
        assertEquals(150.0, r.weeks().get(0).matchups().get(0).homePoints()
                + r.weeks().get(0).matchups().get(0).awayPoints() - 10.0);
        assertEquals(1, r.userFinish(), "150 beats 10");
        assertEquals(DAY.toString(), starters.get(0).gameDate(), "each starter carries the locked game's date");
    }

    @Test
    void anEmptyRosterWeekIsZeroPointsNotAbsent() {
        // Team 2's only player never plays in week 2: the team scores 0, and the matchup still happens.
        List<Pick> picks = List.of(new Pick(1, 1, 1), new Pick(2, 2, 2));
        Map<Long, HistoricalPlayerCard> cards = Map.of(1L, card(1, "PG"), 2L, card(2, "PG"));
        List<GameLine> games = List.of(game(1, 1, 1, 10), game(2, 2, 1, 5), game(1, 3, 2, 7));
        HistoricalResult r = REPLAY.replay(input(picks, cards, games, 2, 2L));
        var m = r.weeks().get(1).matchups().get(0);
        assertEquals(7.0 + 0.0, m.homePoints() + m.awayPoints());
        assertTrue(m.homePoints() == 0.0 || m.awayPoints() == 0.0);
    }

    @Test
    void equalTotalsAreATieForBoth() {
        List<Pick> picks = List.of(new Pick(1, 1, 1), new Pick(2, 2, 2));
        Map<Long, HistoricalPlayerCard> cards = Map.of(1L, card(1, "PG"), 2L, card(2, "PG"));
        List<GameLine> games = List.of(game(1, 1, 1, 20), game(2, 2, 1, 20));
        HistoricalResult r = REPLAY.replay(input(picks, cards, games, 1, 3L));
        for (var s : r.standings()) {
            assertEquals(0, s.wins());
            assertEquals(0, s.losses());
            assertEquals(1, s.ties());
        }
    }

    @Test
    void sameSeedGivesByteIdenticalJson() throws Exception {
        List<Pick> picks = new ArrayList<>();
        List<GameLine> games = new ArrayList<>();
        Map<Long, HistoricalPlayerCard> cards = new LinkedHashMap<>();
        String[] pos = {"PG", "SG", "SF", "PF", "C"};
        for (long id = 1; id <= 28; id++) {
            cards.put(id, card(id, pos[(int) (id % 5)]));
            if (id <= 28) picks.add(new Pick((int) id, 1 + (int) ((id - 1) % 4), id));
            for (int w = 1; w <= 3; w++) games.add(game(id, id * 10 + w, w, (int) (id * 3 + w * 7) % 40));
        }
        ObjectMapper om = new ObjectMapper();
        String a = om.writeValueAsString(REPLAY.replay(input(picks, cards, games, 3, 99L, 4, 2)));
        String b = om.writeValueAsString(REPLAY.replay(input(picks, cards, games, 3, 99L, 4, 2)));
        assertEquals(a, b);
    }

    @Test
    void theCircleMethodMeetsEveryOpponentExactlyOncePerCycle() {
        List<Integer> order = new ArrayList<>();
        for (int s = 1; s <= 12; s++) order.add(s);
        Set<String> seen = new HashSet<>();
        for (int w = 1; w <= 11; w++) {
            Set<Integer> thisWeek = new HashSet<>();
            for (int[] p : SeasonReplay.pairings(order, w - 1, w)) {
                assertTrue(thisWeek.add(p[0]) && thisWeek.add(p[1]), "each team plays once per week");
                assertTrue(seen.add(Math.min(p[0], p[1]) + "-" + Math.max(p[0], p[1])), "no repeat in a cycle");
            }
        }
        assertEquals(66, seen.size(), "12 teams: 66 distinct pairings in 11 weeks");
    }

    // ---- fixtures --------------------------------------------------------------------------------------

    private static SeasonReplay.Input input(List<Pick> picks, Map<Long, HistoricalPlayerCard> cards,
                                            List<GameLine> games, int weekCount, long seed) {
        return input(picks, cards, games, weekCount, seed, 2, 1);
    }

    private static SeasonReplay.Input input(List<Pick> picks, Map<Long, HistoricalPlayerCard> cards,
                                            List<GameLine> games, int weekCount, long seed, int teams,
                                            int userSlot) {
        Map<Integer, LocalDate> starts = new LinkedHashMap<>();
        for (int w = 1; w <= weekCount; w++) starts.put(w, DAY.plusDays(7L * (w - 1)));
        Map<String, Double> scoring = new LinkedHashMap<>();
        scoring.put("pts", 1.0);
        return new SeasonReplay.Input(settings(teams), rules(), scoring, weekCount, 19, seed, userSlot, picks, cards,
                games, starts);
    }
}
