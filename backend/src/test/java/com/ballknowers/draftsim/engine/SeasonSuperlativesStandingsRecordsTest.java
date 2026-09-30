package com.ballknowers.draftsim.engine;

import com.ballknowers.draftsim.engine.SeasonSuperlativesService.Standing;
import com.ballknowers.draftsim.store.LeagueMatchupRepository;
import com.ballknowers.draftsim.store.RosterWeekPointsRepository;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Spec 010 T008: the week-score and margin standings, against in-memory rows -- no Postgres. Every
 * direction gets an ordering assertion (AGENTS.md recurring bug class 1), not just a count.
 */
class SeasonSuperlativesStandingsRecordsTest {

    private static final String MID = "·";

    private final Map<Integer, String> names = new HashMap<>(Map.of(1, "Alpha", 2, "Bravo", 3, "Charlie"));
    private final Map<Integer, String> avatars = new HashMap<>();
    private final Map<Integer, Long> managers = new HashMap<>();
    // roster 4 is an orphan: in the universe, absent from names.
    private final Set<Integer> rosterIds = Set.of(1, 2, 3, 4);

    private static RosterWeekPointsRepository.WeekBreakdown wk(int week, int roster, double pts) {
        return new RosterWeekPointsRepository.WeekBreakdown(week, roster, pts, null, null);
    }

    private static LeagueMatchupRepository.PairedGame game(int week, int a, String aPts, int b, String bPts) {
        return new LeagueMatchupRepository.PairedGame(2026, week,
                a, null, "m" + a, null, new BigDecimal(aPts),
                b, null, "m" + b, null, new BigDecimal(bPts));
    }

    private List<Standing> weeks(List<RosterWeekPointsRepository.WeekBreakdown> rows, boolean highest) {
        return SeasonSuperlativesService.weekScoreStandings(rows, rosterIds, highest, names, avatars, managers);
    }

    private List<Standing> margins(List<LeagueMatchupRepository.PairedGame> games, boolean closest) {
        return SeasonSuperlativesService.marginStandings(games, rosterIds, closest, names, avatars, managers);
    }

    private static List<Integer> order(List<Standing> s) {
        return s.stream().map(r -> r.team().rosterId()).toList();
    }

    private static Standing of(List<Standing> s, int roster) {
        return s.stream().filter(r -> r.team().rosterId() == roster).findFirst().orElseThrow();
    }

    // ---------------------------------------------------------------- week scores

    @Test
    void highestWeekUsesEachRostersMaxHighToLow() {
        List<Standing> s = weeks(List.of(
                wk(1, 1, 100.0), wk(2, 1, 130.5), wk(3, 1, 90.0),
                wk(1, 2, 140.0), wk(2, 2, 60.0),
                wk(1, 3, 80.0)), true);
        assertEquals(List.of(2, 1, 3, 4), order(s));
        assertEquals(140.0, of(s, 2).value());
        assertEquals(130.5, of(s, 1).value());
        assertEquals("week 2", of(s, 1).note());
        assertEquals(List.of(1, 2, 3), s.subList(0, 3).stream().map(Standing::rank).toList());
    }

    @Test
    void lowestWeekUsesEachRostersMinLowToHigh() {
        List<Standing> s = weeks(List.of(
                wk(1, 1, 100.0), wk(2, 1, 130.5), wk(3, 1, 90.0),
                wk(1, 2, 140.0), wk(2, 2, 60.0),
                wk(1, 3, 80.0)), false);
        assertEquals(List.of(2, 3, 1, 4), order(s));
        assertEquals(60.0, of(s, 2).value());
        assertEquals("week 2", of(s, 2).note());
        assertEquals(90.0, of(s, 1).value());
        assertEquals("week 3", of(s, 1).note());
    }

    @Test
    void aRosterTyingItsOwnMaxKeepsTheEarliestWeek() {
        List<Standing> s = weeks(List.of(wk(5, 1, 120.0), wk(2, 1, 120.0), wk(9, 1, 120.0)), true);
        assertEquals("week 2", of(s, 1).note());
    }

    @Test
    void aRosterWithNoRowsIsNotMeasuredAndOrphanIsPresent() {
        List<Standing> s = weeks(List.of(wk(1, 1, 100.0), wk(1, 2, 90.0), wk(1, 3, 80.0)), true);
        Standing orphan = of(s, 4);
        assertFalse(orphan.hasValue());
        assertNull(orphan.rank());
        assertEquals("no scored weeks", orphan.missingReason());
        assertEquals("Roster 4", orphan.team().teamName());
        assertEquals(4, order(s).get(3), "unmeasured rows sort last");
    }

    @Test
    void aBreakdownForARosterOutsideTheUniverseIsIgnored() {
        List<Standing> s = weeks(List.of(wk(1, 1, 100.0), wk(1, 99, 999.0)), true);
        assertEquals(4, s.size());
        assertTrue(s.stream().noneMatch(r -> r.team().rosterId() == 99));
        assertEquals(1, s.get(0).team().rosterId());
    }

    @Test
    void tiedTopWeeksShareRankOne() {
        List<Standing> s = weeks(List.of(wk(1, 1, 150.0), wk(2, 2, 150.0), wk(1, 3, 100.0)), true);
        assertEquals(List.of(1, 1, 3), s.subList(0, 3).stream().map(Standing::rank).toList());
    }

    // ---------------------------------------------------------------- margins

    @Test
    void aTiedGameIsNobodysBlowoutWinButStillCountsForClosestGame() {
        // B5: the tie must not hand roster 1 (side A) a 0.00 "win".
        List<LeagueMatchupRepository.PairedGame> games = List.of(game(1, 1, "100.00", 2, "100.00"));
        List<Standing> blowout = margins(games, false);
        assertTrue(blowout.stream().noneMatch(Standing::hasValue));
        assertEquals("no wins yet", of(blowout, 1).missingReason());
        List<Standing> closest = margins(games, true);
        assertEquals(0.0, of(closest, 1).value());
        assertEquals(0.0, of(closest, 2).value());
    }

    @Test
    void biggestBlowoutUsesLargestWinningMarginHighToLow() {
        List<Standing> s = margins(List.of(
                game(1, 1, "150.00", 2, "100.00"),   // 1 beats 2 by 50
                game(2, 3, "110.00", 1, "100.00"),   // 3 beats 1 by 10
                game(3, 1, "120.00", 3, "115.00")),  // 1 beats 3 by 5
                false);
        assertEquals(List.of(1, 3, 2, 4), order(s));
        assertEquals(50.0, of(s, 1).value());
        assertEquals("vs Bravo " + MID + " week 1", of(s, 1).note());
        assertEquals(10.0, of(s, 3).value());
        assertEquals(1, of(s, 1).rank());
        assertEquals(2, of(s, 3).rank());
        Standing noWins = of(s, 2);
        assertFalse(noWins.hasValue());
        assertEquals("no wins yet", noWins.missingReason());
    }

    @Test
    void closestGameUsesSmallestMarginLowToHighAndBothSidesOfItShareRankOne() {
        List<Standing> s = margins(List.of(
                game(1, 1, "100.50", 2, "100.00"),   // margin 0.50, 1 wins
                game(2, 3, "150.00", 1, "100.00"),   // margin 50
                game(3, 3, "110.00", 2, "108.00")),  // margin 2
                true);
        Standing one = of(s, 1);
        Standing two = of(s, 2);
        assertEquals(1, one.rank());
        assertEquals(1, two.rank());
        assertEquals(0.5, one.value());
        assertEquals("won vs Bravo " + MID + " week 1", one.note());
        assertEquals("lost to Alpha " + MID + " week 1", two.note());
        Standing three = of(s, 3);
        assertEquals(3, three.rank());
        assertEquals(2.0, three.value());
        assertEquals("won vs Bravo " + MID + " week 3", three.note());
        Standing orphan = of(s, 4);
        assertFalse(orphan.hasValue());
        assertEquals("no games yet", orphan.missingReason());
        // Ascending: smaller margins first.
        List<Double> vals = s.stream().filter(Standing::hasValue).map(Standing::value).toList();
        assertEquals(vals.stream().sorted().toList(), vals);
    }

    @Test
    void zeroMarginGameIsTiedForBothSides() {
        List<Standing> s = margins(List.of(game(4, 1, "100.00", 2, "100.00")), true);
        assertEquals("tied with Bravo " + MID + " week 4", of(s, 1).note());
        assertEquals("tied with Alpha " + MID + " week 4", of(s, 2).note());
        assertEquals(0.0, of(s, 1).value());
        assertEquals(1, of(s, 1).rank());
        assertEquals(1, of(s, 2).rank());
    }

    @Test
    void marginsComeFromBigDecimalSubtractionSoTheseTwoTieExactly() {
        // 101.10 - 100.00 and 50.20 - 49.10 are both exactly 1.10 in BigDecimal; in doubles they aren't.
        List<Standing> s = margins(List.of(
                game(1, 1, "101.10", 2, "100.00"),
                game(2, 3, "50.20", 4, "49.10")), true);
        assertEquals(1, of(s, 1).rank());
        assertEquals(1, of(s, 3).rank());
        assertEquals(of(s, 1).value(), of(s, 3).value());
    }

    @Test
    void anOrphanedRosterOpponentIsNamedRosterN() {
        List<Standing> s = margins(List.of(game(2, 1, "120.00", 4, "100.00")), false);
        assertEquals("vs Roster 4 " + MID + " week 2", of(s, 1).note());
    }

    @Test
    void anOrphanedRosterCanHoldARecord() {
        List<Standing> s = margins(List.of(game(2, 4, "150.00", 1, "100.00")), false);
        assertEquals(4, s.get(0).team().rosterId());
        assertEquals("Roster 4", s.get(0).team().teamName());
        assertEquals(1, s.get(0).rank());
    }

    @Test
    void aGameForARosterOutsideTheUniverseIsIgnored() {
        List<Standing> s = margins(List.of(game(1, 1, "100.00", 99, "90.00")), false);
        assertEquals(4, s.size());
        assertEquals(1, of(s, 1).rank());
        assertTrue(s.stream().noneMatch(r -> r.team().rosterId() == 99));
    }
}
