package com.ballknowers.draftsim.engine;

import com.ballknowers.draftsim.engine.SeasonSuperlativesService.BenchAgg;
import com.ballknowers.draftsim.engine.SeasonSuperlativesService.GameDetail;
import com.ballknowers.draftsim.engine.SeasonSuperlativesService.Standing;
import com.ballknowers.draftsim.engine.SeasonSuperlativesService.Superlative;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Spec 010 T009 + T010: the count/total standings (close games, luck, bench, waiver, absence, conduct)
 * and the R4 empty-state rule, against in-memory inputs -- no Postgres.
 */
class SeasonSuperlativesStandingsTotalsTest {

    private final Map<Integer, String> names = new HashMap<>(Map.of(1, "Alpha", 2, "Bravo", 3, "Charlie"));
    private final Map<Integer, String> avatars = new HashMap<>();
    private final Map<Integer, Long> managers = new HashMap<>();
    private final Set<Integer> rosterIds = Set.of(1, 2, 3, 4); // 4 is an orphan

    private static List<Integer> order(List<Standing> s) {
        return s.stream().map(r -> r.team().rosterId()).toList();
    }

    private static Standing of(List<Standing> s, int roster) {
        return s.stream().filter(r -> r.team().rosterId() == roster).findFirst().orElseThrow();
    }

    private static void assertEveryRosterOnce(List<Standing> s, Set<Integer> ids) {
        assertEquals(ids.size(), s.size());
        assertEquals(ids, Set.copyOf(order(s)));
    }

    // ---------------------------------------------------------------- close games

    private static GameDetail cg(int week, int roster) {
        return new GameDetail(week, roster, 9, null, 100, 99, 1);
    }

    @Test
    void closeGamesCountHighToLowWithWeeksNote() {
        Map<Integer, List<GameDetail>> byRoster = Map.of(
                1, List.of(cg(7, 1), cg(3, 1)),
                2, List.of(cg(5, 2)));
        List<Standing> s = SeasonSuperlativesService.closeGameStandings(
                byRoster, Set.of(1, 2, 3, 4), rosterIds, names, avatars, managers);
        assertEveryRosterOnce(s, rosterIds);
        assertEquals(List.of(1, 2), order(s).subList(0, 2));
        assertEquals(2.0, of(s, 1).value());
        assertEquals("weeks 3, 7", of(s, 1).note());
        assertEquals("week 5", of(s, 2).note());
        assertEquals(1, of(s, 1).rank());
        assertEquals(2, of(s, 2).rank());
    }

    @Test
    void aRosterInAPairedGameButAbsentFromTheMapIsARealZero() {
        List<Standing> s = SeasonSuperlativesService.closeGameStandings(
                Map.of(1, List.of(cg(1, 1))), Set.of(1, 2, 3, 4), rosterIds, names, avatars, managers);
        Standing zero = of(s, 2);
        assertTrue(zero.hasValue());
        assertEquals(0.0, zero.value());
        assertNull(zero.note());
        assertEquals(2, zero.rank());
        assertEquals(2, of(s, 3).rank(), "zeros tie");
    }

    @Test
    void aRosterInNoPairedGameIsNotMeasured() {
        List<Standing> s = SeasonSuperlativesService.closeGameStandings(
                Map.of(1, List.of(cg(1, 1))), Set.of(1, 2, 3), rosterIds, names, avatars, managers);
        Standing none = of(s, 4);
        assertFalse(none.hasValue());
        assertNull(none.rank());
        assertEquals("no games yet", none.missingReason());
        assertEquals(4, order(s).get(3));
    }

    // ---------------------------------------------------------------- luck

    private static ExpectedWinsService.TeamRow team(int id, double expected, double actual, double above) {
        return new ExpectedWinsService.TeamRow(id, null, "t" + id, null, null, expected, actual, above, 0.0,
                ExpectedWinsService.LuckSource.CONSISTENT_OPPONENT_SCORING, List.of());
    }

    @Test
    void luckiestIsHighToLowAndUnluckiestIsLowToHigh() {
        List<ExpectedWinsService.TeamRow> teams = List.of(
                team(1, 5.0, 6.0, 1.0), team(2, 6.3, 5.0, -1.3), team(3, 4.0, 6.5, 2.5));
        List<Standing> lucky = SeasonSuperlativesService.luckStandings(teams, rosterIds, false, names, avatars, managers);
        assertEquals(List.of(3, 1, 2, 4), order(lucky));
        assertEquals(2.5, lucky.get(0).value());

        List<Standing> unlucky = SeasonSuperlativesService.luckStandings(teams, rosterIds, true, names, avatars, managers);
        assertEquals(List.of(2, 1, 3, 4), order(unlucky));
        assertEquals(-1.3, unlucky.get(0).value());
        assertEquals(1, unlucky.get(0).rank());
    }

    @Test
    void luckNoteIsActualVersusExpectedWithExpectedToTwoDecimals() {
        List<ExpectedWinsService.TeamRow> teams = List.of(team(1, 6.3, 5.0, -1.3), team(2, 4.0, 6.5, 2.5));
        List<Standing> s = SeasonSuperlativesService.luckStandings(teams, rosterIds, false, names, avatars, managers);
        assertEquals("5 actual vs 6.30 expected", of(s, 1).note());
        assertEquals("6.5 actual vs 4.00 expected", of(s, 2).note());
    }

    @Test
    void aRosterWithNoExpectedWinsRowIsNotMeasured() {
        List<Standing> s = SeasonSuperlativesService.luckStandings(
                List.of(team(1, 5.0, 6.0, 1.0)), rosterIds, false, names, avatars, managers);
        assertEveryRosterOnce(s, rosterIds);
        Standing missing = of(s, 4);
        assertFalse(missing.hasValue());
        assertEquals("no expected-wins row", missing.missingReason());
    }

    // ---------------------------------------------------------------- bench

    @Test
    void benchIsHighToLowWithWeeksCountedAndAbsentIsNotMeasured() {
        Map<Integer, BenchAgg> byRoster = Map.of(
                1, new BenchAgg(30.5, 3, null),
                2, new BenchAgg(80.0, 1, null));
        List<Standing> s = SeasonSuperlativesService.benchStandings(byRoster, rosterIds, names, avatars, managers);
        assertEquals(List.of(2, 1), order(s).subList(0, 2));
        assertEquals("3 weeks counted", of(s, 1).note());
        Standing absent = of(s, 3);
        assertFalse(absent.hasValue());
        assertNull(absent.rank());
        assertEquals("no usable lineup breakdown", absent.missingReason());
        assertEveryRosterOnce(s, rosterIds);
    }

    // ---------------------------------------------------------------- waiver / conduct

    @Test
    void waiverAbsentRosterIsARealZeroAndOrderIsHighToLow() {
        Map<Integer, WaiverPickupAttribution.RosterTotal> byRoster = Map.of(
                2, new WaiverPickupAttribution.RosterTotal(2, 12.5, List.of()),
                1, new WaiverPickupAttribution.RosterTotal(1, 40.0, List.of()));
        List<Standing> s = SeasonSuperlativesService.waiverStandings(byRoster, rosterIds, names, avatars, managers);
        assertEquals(List.of(1, 2), order(s).subList(0, 2));
        assertEquals(0.0, of(s, 3).value());
        assertTrue(of(s, 3).hasValue());
        assertEquals(3, of(s, 3).rank());
        assertEquals(3, of(s, 4).rank());
        assertEveryRosterOnce(s, rosterIds);
    }

    @Test
    void conductAbsentRosterIsARealZeroAndOrderIsHighToLow() {
        List<Standing> s = SeasonSuperlativesService.conductStandings(
                Map.of(3, 5, 1, 2), rosterIds, names, avatars, managers);
        assertEquals(List.of(3, 1), order(s).subList(0, 2));
        assertEquals(5.0, s.get(0).value());
        assertEquals(0.0, of(s, 2).value());
        assertTrue(of(s, 2).hasValue());
        assertEveryRosterOnce(s, rosterIds);
    }

    // ---------------------------------------------------------------- absence

    private static AbsenceCost.RosterCost cost(int id, double lost) {
        return new AbsenceCost.RosterCost(id, lost, List.of());
    }

    @Test
    void absenceAbsentRosterIsZeroAndEveryRosterWithUnclassifiedWeeksSaysSo() {
        Map<Integer, AbsenceCost.RosterCost> byRoster = Map.of(1, cost(1, 60.0), 2, cost(2, 10.0));
        Map<Integer, Set<Integer>> unclassified = Map.of(
                1, Set.of(4),               // a winner, with one unclassified week
                3, Set.of(2, 6));           // a roster absent from byRoster, with two
        List<Standing> s = SeasonSuperlativesService.absenceStandings(
                byRoster, unclassified, rosterIds, names, avatars, managers);
        assertEquals(List.of(1, 2), order(s).subList(0, 2));
        assertEquals("1 week couldn't be classified as a bye or a missed game", of(s, 1).note());
        assertNull(of(s, 2).note());
        Standing three = of(s, 3);
        assertTrue(three.hasValue());
        assertEquals(0.0, three.value());
        assertEquals("2 weeks couldn't be classified as a bye or a missed game", three.note());
        assertNull(of(s, 4).note());
        assertEveryRosterOnce(s, rosterIds);
    }

    // ---------------------------------------------------------------- R4 empty state (T010)

    @Test
    void waiverWithEveryTotalAtOrBelowZeroHasNoWinner() {
        Map<Integer, WaiverPickupAttribution.RosterTotal> byRoster = Map.of(
                1, new WaiverPickupAttribution.RosterTotal(1, -1.0, List.of()),
                2, new WaiverPickupAttribution.RosterTotal(2, 0.0, List.of()));
        Superlative s = SeasonSuperlativesService.waiverWinners(byRoster, null, false, rosterIds, Map.of(),
                names, avatars, managers);
        assertTrue(s.available());
        assertTrue(s.holders().isEmpty());
        assertNull(s.value());
        assertEquals("no started pickup has scored yet", s.emptyReason());
        assertTrue(s.standings().isEmpty());
    }

    @Test
    void waiverWithAPositiveBestStillCrownsAWinnerWhoIsRankOne() {
        Map<Integer, WaiverPickupAttribution.RosterTotal> byRoster = Map.of(
                1, new WaiverPickupAttribution.RosterTotal(1, 0.5, List.of()),
                2, new WaiverPickupAttribution.RosterTotal(2, -3.0, List.of()));
        Superlative s = SeasonSuperlativesService.waiverWinners(byRoster, null, false, rosterIds, Map.of(),
                names, avatars, managers);
        assertNull(s.emptyReason());
        assertEquals(List.of(1), s.holders().stream().map(SeasonSuperlativesService.Holder::rosterId).toList());
        assertEquals(1, s.standings().get(0).team().rosterId());
        assertEquals(1, s.standings().get(0).rank());
        assertEveryRosterOnce(s.standings(), rosterIds);
    }

    @Test
    void embiidWithEveryCostAtOrBelowZeroHasNoWinner() {
        Map<Integer, AbsenceCost.RosterCost> byRoster = Map.of(1, cost(1, 0.0), 2, cost(2, 0.0));
        Superlative s = SeasonSuperlativesService.absenceWinners(byRoster, Map.of(), List.of(), 8, false, rosterIds,
                Map.of(), names, avatars, managers);
        assertTrue(s.available());
        assertTrue(s.holders().isEmpty());
        assertNull(s.value());
        assertEquals("no absence has cost anyone points yet", s.emptyReason());
        assertTrue(s.standings().isEmpty());
    }

    @Test
    void embiidEmptyStateKeepsTheLeagueLevelCoverageLine() {
        Map<Integer, AbsenceCost.RosterCost> byRoster = Map.of(1, cost(1, 0.0));
        Superlative s = SeasonSuperlativesService.absenceWinners(byRoster, Map.of(),
                List.of("3 rostered players have no game-by-game records yet."), 8, false, rosterIds, Map.of(),
                names, avatars, managers);
        assertNotNull(s.coverage());
        assertEquals(List.of("3 rostered players have no game-by-game records yet."), s.coverage().reasons());
    }

    @Test
    void embiidWithAPositiveCostCrownsTheTopRosterAsRankOne() {
        Map<Integer, AbsenceCost.RosterCost> byRoster = Map.of(1, cost(1, 60.0), 2, cost(2, 10.0));
        Superlative s = SeasonSuperlativesService.absenceWinners(byRoster, Map.of(), List.of(), 8, false, rosterIds,
                Map.of(), names, avatars, managers);
        assertEquals(60.0, s.value());
        assertEquals(List.of(1), s.holders().stream().map(SeasonSuperlativesService.Holder::rosterId).toList());
        assertEquals(1, s.standings().get(0).team().rosterId());
        assertEveryRosterOnce(s.standings(), rosterIds);
    }
}
