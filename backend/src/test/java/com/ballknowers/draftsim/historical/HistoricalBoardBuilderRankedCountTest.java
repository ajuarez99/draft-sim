package com.ballknowers.draftsim.historical;

import com.ballknowers.draftsim.domain.Sport;
import com.ballknowers.draftsim.historical.HistoricalBoardBuilder.Board;
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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Spec 074 T011 (PR-12): the builder reports how many of its cards are RANKED, because a card cannot say: a football
 * rookie drafted after the configured rookie round carries his pick number and still sits in the seeded tail.
 */
class HistoricalBoardBuilderRankedCountTest {

    private static final int SEASON = 2011;
    private static final RankConfig CFG = new RankConfig(17, 6);
    private static final Map<String, Double> SCORING = Map.of("rush_yd", 1.0);

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

    private static Options nfl(Map<Long, RookiePick> picks) {
        // rookie-max-round 2: only rookies drafted in rounds 1..2 are placed by their pick
        return new Options(Sport.NFL, NflHistoricalSport.COUNTING, y -> String.valueOf(y),
                RankMode.POINTS_OVER_REPLACEMENT, picks,
                new NflRank(Map.of("RB", 24), 12, 2, NflHistoricalSport.TOTAL_KEYS), null);
    }

    @Test
    void aRookieDraftedAfterTheRookieRoundIsInTheTailNotInTheRankedCount() {
        List<NflSeasonLine> s1 = new ArrayList<>();
        List<Candidate> cands = new ArrayList<>();
        for (int i = 1; i <= 30; i++) {
            s1.add(line(100 + i, 2010, 16, 30 - i * 0.5, "RB"));
            cands.add(new Candidate(100 + i, "RB" + i));
        }
        List<NflSeasonLine> drafted = new ArrayList<>();
        for (long r : new long[]{900, 901, 902}) {
            cands.add(new Candidate(r, "R" + r));
            drafted.add(line(r, SEASON, 16, 999, "RB"));
        }
        Map<Long, RookiePick> picks = Map.of(900L, new RookiePick(1, 10), 901L, new RookiePick(2, 40),
                902L, new RookiePick(3, 70));

        Board board = HistoricalBoardBuilder.buildRanked(SEASON, 7L, SCORING, cands, List.of(map(s1), map(List.of())),
                map(drafted), CFG, nfl(picks));

        assertEquals(33, board.cards().size());
        assertEquals(32, board.rankedCount(), "30 veterans and the two rookies placed by their pick; not the round-3 rookie");
        HistoricalPlayerCard tail = board.cards().get(32);
        assertEquals(902, tail.id(), "the round-3 rookie is the first card of the tail");
        assertEquals(70, tail.rookiePick(), "he carries his pick number, so the card alone could not say he is unranked");
        assertEquals(33, tail.rank());
        // the two placed rookies are inside the ranked part
        long placed = board.cards().subList(0, board.rankedCount()).stream().filter(c -> c.rookiePick() != null).count();
        assertEquals(2, placed);
    }

    @Test
    void theOldEntryPointReturnsTheSameCardsAndEveryCardIsRankedWhenEveryoneHasALine() {
        List<NflSeasonLine> s1 = new ArrayList<>();
        List<Candidate> cands = new ArrayList<>();
        for (int i = 1; i <= 5; i++) {
            s1.add(line(i, 2010, 16, 20 - i, "RB"));
            cands.add(new Candidate(i, "P" + i));
        }
        Board board = HistoricalBoardBuilder.buildRanked(SEASON, 7L, SCORING, cands, List.of(map(s1), map(List.of())),
                Map.of(), CFG, nfl(Map.of()));
        assertEquals(5, board.rankedCount());
        assertEquals(board.cards(), HistoricalBoardBuilder.build(SEASON, 7L, SCORING, cands,
                List.of(map(s1), map(List.of())), Map.of(), CFG, nfl(Map.of())));
        assertTrue(board.cards().get(0).rank() == 1);
    }

    @Test
    void aPlayerWithNoQualifyingLineIsUnrankedAndComesAfterEveryRankedPlayer() {
        List<NflSeasonLine> s1 = List.of(line(1, 2010, 16, 20, "RB"), line(2, 2010, 3, 25, "RB"));   // 2: under the games floor
        List<Candidate> cands = List.of(new Candidate(1, "a"), new Candidate(2, "b"));
        Board board = HistoricalBoardBuilder.buildRanked(SEASON, 7L, SCORING, cands, List.of(map(s1), map(List.of())),
                Map.of(), CFG, nfl(Map.of()));
        assertEquals(1, board.rankedCount());
        assertEquals(1, board.cards().get(0).id());
        assertEquals(2, board.cards().get(1).id());
    }
}
