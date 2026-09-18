package com.ballknowers.draftsim.engine;

import com.ballknowers.draftsim.domain.Player;
import com.ballknowers.draftsim.domain.Position;
import com.ballknowers.draftsim.domain.Sport;
import com.ballknowers.draftsim.store.RosterWeekPointsRepository.WeekBreakdown;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Ranking a player within his own position for the weeks after a move
 * (specs/004-ffwrapped-feature-parity US6). Sleeper's type mapping lives in
 * {@code TransactionIngestServiceTest}, beside the class that owns it. The
 * repository walk is left to an integration test, per the split
 * {@code LeagueAnalysisServiceTest} describes.
 */
class TransactionAnalysisServiceTest {

    // ------------------------------------------------------ positional ranks

    private static Map<String, Player> players(Object... idsAndPositions) {
        Map<String, Player> out = new HashMap<>();
        for (int i = 0; i < idsAndPositions.length; i += 2) {
            String id = (String) idsAndPositions[i];
            Position pos = (Position) idsAndPositions[i + 1];
            out.put(id, new Player(id.hashCode(), Sport.NFL, id, "Player " + id, List.of(pos),
                    null, "Active", null, null, null));
        }
        return out;
    }

    private static WeekBreakdown week(int w, int rosterId, String playersPoints) {
        return new WeekBreakdown(w, rosterId, 0, playersPoints, null);
    }

    /**
     * US6.3. Rank is WITHIN a position and across the whole league: "RB1" is
     * the best running back that week, not the best player on a roster.
     */
    @Test
    void rankIsWithinAPositionAcrossTheWholeLeague() {
        Map<String, Player> p = players(
                "rbA", Position.RB, "rbB", Position.RB, "rbC", Position.RB, "wrA", Position.WR);
        // Two rosters, so the ranking must span both.
        List<WeekBreakdown> weeks = List.of(
                week(1, 1, "{\"rbA\":5.0,\"wrA\":30.0}"),
                week(1, 2, "{\"rbB\":20.0,\"rbC\":12.0}"));

        Map<Integer, Map<String, Integer>> ranks =
                TransactionAnalysisService.positionalRanksByWeek(weeks, p);

        Map<String, Integer> w1 = ranks.get(1);
        assertEquals(1, w1.get("rbB"), "20 is the best RB in the league that week");
        assertEquals(2, w1.get("rbC"));
        assertEquals(3, w1.get("rbA"));
        // The 30-point receiver is WR1, not the overall leader by another name.
        assertEquals(1, w1.get("wrA"), "ranked against receivers, not against running backs");
    }

    /**
     * US6.5 / FR-004. Positions come from the player rows, which are the
     * sport's own, so basketball ranks centres against centres. Nothing in this
     * code names a football position.
     */
    @Test
    void basketballPositionsRankAgainstTheirOwnKind() {
        Map<String, Player> p = new HashMap<>();
        p.put("c1", new Player(1, Sport.NBA, "c1", "Centre One", List.of(Position.C),
                null, "Active", null, null, null));
        p.put("c2", new Player(2, Sport.NBA, "c2", "Centre Two", List.of(Position.C),
                null, "Active", null, null, null));
        p.put("pg1", new Player(3, Sport.NBA, "pg1", "Guard One", List.of(Position.PG),
                null, "Active", null, null, null));

        List<WeekBreakdown> weeks = List.of(week(1, 1, "{\"c1\":40.0,\"c2\":55.0,\"pg1\":12.0}"));
        Map<String, Integer> w1 = TransactionAnalysisService.positionalRanksByWeek(weeks, p).get(1);

        assertEquals(1, w1.get("c2"));
        assertEquals(2, w1.get("c1"));
        assertEquals(1, w1.get("pg1"), "the only point guard is PG1 even at 12 points");
    }

    /** A player this app has no row for cannot be ranked, and is skipped. */
    @Test
    void unknownPlayersAreSkippedRatherThanRankedAsZero() {
        Map<String, Player> p = players("rbA", Position.RB);
        List<WeekBreakdown> weeks = List.of(week(1, 1, "{\"rbA\":10.0,\"ghost\":99.0}"));
        Map<String, Integer> w1 = TransactionAnalysisService.positionalRanksByWeek(weeks, p).get(1);
        assertEquals(1, w1.size());
        assertEquals(1, w1.get("rbA"), "the ghost must not take the top spot");
    }

    /** A week with no stored breakdown contributes no ranks, rather than zeros. */
    @Test
    void weeksWithNoBreakdownContributeNothing() {
        Map<String, Player> p = players("rbA", Position.RB);
        List<WeekBreakdown> weeks = List.of(week(1, 1, null), week(2, 1, ""), week(3, 1, "{\"rbA\":8.0}"));
        Map<Integer, Map<String, Integer>> ranks =
                TransactionAnalysisService.positionalRanksByWeek(weeks, p);
        assertFalse(ranks.containsKey(1));
        assertFalse(ranks.containsKey(2));
        assertEquals(1, ranks.get(3).get("rbA"));
    }
}
