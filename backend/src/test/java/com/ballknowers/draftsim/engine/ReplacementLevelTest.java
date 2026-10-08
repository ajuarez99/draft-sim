package com.ballknowers.draftsim.engine;

import com.ballknowers.draftsim.config.ScoringProperties;
import com.ballknowers.draftsim.domain.Player;
import com.ballknowers.draftsim.domain.Position;
import com.ballknowers.draftsim.domain.Sport;
import com.ballknowers.draftsim.engine.ReplacementLevel.Qualified;
import com.ballknowers.draftsim.engine.ReplacementLevel.Result;
import com.ballknowers.draftsim.engine.ReplacementLevel.Vor;
import com.ballknowers.draftsim.sport.BasketballRules;
import com.ballknowers.draftsim.sport.SportRules;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Spec 022 T042 (data-model "Replacement", research R11): the starting-slot order, the greedy fill, the
 * level per position, and the value over replacement, including the preference ordering (I7): a lower
 * replacement level must give a higher value over replacement.
 */
class ReplacementLevelTest {

    private static final ScoringProperties.SportScoring CFG = new ScoringProperties.SportScoring(
            new ScoringProperties.Weights(1.0, 0.35, 0.5, 0.25),
            12.0, 3.0, 60.0, 0.15, 6, 0.85, Map.of(), 1.0, 30);
    private static final SportRules RULES = new BasketballRules(new ScoringProperties(null, CFG));

    private static final List<String> LEAGUE = List.of("PG", "SG", "G", "SF", "PF", "F", "C", "UTIL", "UTIL",
            "BN", "BN", "BN", "BN", "BN");

    private static Player player(String id, String name, Position... positions) {
        return new Player(Math.abs(id.hashCode()), Sport.NBA, id, name, List.of(positions), "TM", "Active", null,
                null, null);
    }

    private static Qualified q(Player p, double fp) {
        return new Qualified(p, 60, fp);
    }

    @Test
    void startingSlotsDropBenchIrAndTaxiAndOrderSinglesThenGAndFThenUtil() {
        assertEquals(List.of("PG", "SG", "SF", "PF", "C", "G", "F", "UTIL", "UTIL"),
                ReplacementLevel.startingSlots(LEAGUE));
        // The league's own order is kept inside a class: single positions here arrive as SG, PG.
        assertEquals(List.of("SG", "PG", "G", "UTIL"),
                ReplacementLevel.startingSlots(List.of("UTIL", "SG", "BN", "G", "PG", "IR", "TAXI")));
    }

    @Test
    void fillTakesTheBestRemainingEligiblePlayerAndALevelIsTheBestUnplacedEligibleOne() {
        // One team; slots PG, SG, UTIL (given as UTIL, SG, PG to prove the order is imposed, not inherited).
        Player a = player("a", "A", Position.PG);
        Player b = player("b", "B", Position.PG);
        Player c = player("c", "C", Position.SG);
        Player d = player("d", "D", Position.C);
        Player e = player("e", "E", Position.C);
        Result r = ReplacementLevel.of(List.of(q(a, 50), q(b, 40), q(c, 45), q(d, 30), q(e, 20)),
                List.of("UTIL", "SG", "PG", "BN"), 1, RULES);

        assertEquals(List.of("SG", "PG", "UTIL"), r.replacement().slots());     // singles first, UTIL last
        assertEquals(ReplacementLevel.RULE, r.replacement().rule());
        assertEquals(1, r.replacement().teams());
        // PG slot takes A (50), SG slot takes C (45), UTIL takes the best left overall: B (40).
        assertNull(r.replacement().byPosition().get("PG"));                     // no PG left
        assertNull(r.replacement().byPosition().get("SG"));
        assertNull(r.replacement().byPosition().get("SF"));
        assertEquals(30.0, r.replacement().byPosition().get("C"));              // D is the best unplaced C
        // D sits exactly at his level, E is below it; A has no level at any eligible position.
        assertEquals(0.0, r.byPlayer().get("d").value());
        assertEquals("C", r.byPlayer().get("d").position());
        assertEquals(-10.0, r.byPlayer().get("e").value());
        assertNull(r.byPlayer().get("a").value());
        assertNull(r.byPlayer().get("a").position());
    }

    @Test
    void eligibilityIsTheSportRulesAndPositionsAreTheFiveNbaPositions() {
        Result r = ReplacementLevel.of(List.of(), LEAGUE, 12, RULES);
        assertEquals(List.of("PG", "SG", "SF", "PF", "C"), List.copyOf(r.replacement().byPosition().keySet()));
        assertTrue(r.replacement().byPosition().values().stream().allMatch(v -> v == null));
        assertTrue(r.byPlayer().isEmpty());
        assertEquals(12, r.replacement().teams());
    }

    @Test
    void valueOverReplacementIsTheLargestAcrossEligiblePositionsAndReportsWhichOne() {
        Player x = player("x", "X", Position.PG, Position.SG);
        Map<String, Double> levels = new LinkedHashMap<>();
        levels.put("PG", 20.0);
        levels.put("SG", 30.0);
        levels.put("SF", 1.0);              // not eligible: must not count
        levels.put("PF", null);
        levels.put("C", null);
        Vor v = ReplacementLevel.vor(x, 35.0, levels, RULES);
        assertEquals(15.0, v.value());
        assertEquals("PG", v.position());
        // A position with no level is skipped, not treated as 0.
        levels.put("PG", null);
        Vor onlySg = ReplacementLevel.vor(x, 35.0, levels, RULES);
        assertEquals(5.0, onlySg.value());
        assertEquals("SG", onlySg.position());
    }

    /** I7: preference ordering. A lower replacement level is a higher value over replacement. */
    @Test
    void aLowerReplacementLevelGivesAHigherValueOverReplacement() {
        Player x = player("x", "X", Position.C);
        Vor high = ReplacementLevel.vor(x, 40.0, Map.of("C", 30.0), RULES);
        Vor low = ReplacementLevel.vor(x, 40.0, Map.of("C", 20.0), RULES);
        assertTrue(low.value() > high.value());

        // End to end: a deeper league (more teams) places more players, so the best unplaced one is worse,
        // the level is lower and the same player is worth more.
        List<Qualified> pool = new ArrayList<>();
        for (int i = 0; i < 12; i++) pool.add(q(player("c" + i, "C" + i, Position.C), 50 - i));
        Result shallow = ReplacementLevel.of(pool, List.of("C"), 2, RULES);
        Result deep = ReplacementLevel.of(pool, List.of("C"), 6, RULES);
        assertTrue(deep.replacement().byPosition().get("C") < shallow.replacement().byPosition().get("C"));
        assertTrue(deep.byPlayer().get("c0").value() > shallow.byPlayer().get("c0").value());
        // The value over replacement never rises as the player gets worse.
        assertTrue(shallow.byPlayer().get("c0").value() > shallow.byPlayer().get("c5").value());
    }

    @Test
    void aCenterScarceLeagueHasALowerCenterLevelThanPointGuardLevel() {
        List<Qualified> pool = new ArrayList<>();
        for (int i = 1; i <= 12; i++) pool.add(q(player("pg" + i, "PG" + i, Position.PG), 51 - i));   // 50 .. 39
        pool.add(q(player("c1", "Center1", Position.C), 30));
        pool.add(q(player("c2", "Center2", Position.C), 25));
        pool.add(q(player("c3", "Center3", Position.C), 20));
        Result r = ReplacementLevel.of(pool, List.of("PG", "SG", "SF", "PF", "C", "G", "F", "UTIL", "BN"), 2, RULES);

        // PG x2, C x2, G x2 (pg3, pg4), UTIL x2 (pg5, pg6): pg7 (44) is the best PG left, Center3 the only C.
        assertEquals(44.0, r.replacement().byPosition().get("PG"));
        assertEquals(20.0, r.replacement().byPosition().get("C"));
        assertTrue(r.replacement().byPosition().get("C") < r.replacement().byPosition().get("PG"));
        assertNull(r.replacement().byPosition().get("SG"));                
    }

    @Test
    void tiesBreakByGamesThenNameSoTheFillIsDeterministic() {
        Player abe = player("abe", "Abe", Position.PG);
        Player zed = player("zed", "Zed", Position.PG, Position.SG);
        // One PG slot, two equal players: Abe (alphabetically first) is placed, so Zed is the unplaced one and
        // gives SG a level; had Zed been placed, SG would have none.
        Result a = ReplacementLevel.of(List.of(q(zed, 30), q(abe, 30)), List.of("PG"), 1, RULES);
        assertEquals(30.0, a.replacement().byPosition().get("SG"));
        // More games wins before name: Zed with 70 games is placed first.
        Result b = ReplacementLevel.of(List.of(new Qualified(abe, 60, 30), new Qualified(zed, 70, 30)),
                List.of("PG"), 1, RULES);
        assertNull(b.replacement().byPosition().get("SG"));
        // And the same input in the other order gives the same answer.
        Result c = ReplacementLevel.of(List.of(new Qualified(zed, 70, 30), new Qualified(abe, 60, 30)),
                List.of("PG"), 1, RULES);
        assertEquals(b.replacement().byPosition(), c.replacement().byPosition());
    }
}
