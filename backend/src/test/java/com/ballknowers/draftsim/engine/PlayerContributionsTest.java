package com.ballknowers.draftsim.engine;

import com.ballknowers.draftsim.domain.Player;
import com.ballknowers.draftsim.domain.Position;
import com.ballknowers.draftsim.domain.Sport;
import com.ballknowers.draftsim.engine.SeasonSuperlativesService.ConductQualifyingWeeks;
import com.ballknowers.draftsim.engine.SeasonSuperlativesService.PlayerContribution;
import com.ballknowers.draftsim.engine.SeasonSuperlativesService.Standing;
import com.ballknowers.draftsim.engine.SeasonSuperlativesService.Superlative;
import com.ballknowers.draftsim.store.LeagueTransactionRepository;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Spec 040 T022 (US3): the per-player rows behind JOEL_EMBIID, WAIVER_WIRE_WARRIOR and UNETHICAL. The contract
 * is that, for every team, the player values add up to that team's standings value -- the toggle on the card
 * must never show a different number than the table next to it. Pure and in-memory, no Postgres.
 */
class PlayerContributionsTest {

    private final Map<Integer, String> names = new HashMap<>(Map.of(1, "Alpha", 2, "Bravo", 3, "Charlie"));
    private final Map<Integer, String> avatars = new HashMap<>();
    private final Map<Integer, Long> managers = new HashMap<>();
    private final Set<Integer> rosterIds = Set.of(1, 2, 3, 4); // 4 is an orphan with nothing

    private static Player player(String id, String name, Position pos) {
        return new Player(1L, Sport.NBA, id, name, List.of(pos), "BOS", "Active", null, null, null);
    }

    private final Map<String, Player> players = Map.of(
            "a", player("a", "Adams", Position.C),
            "b", player("b", "Brown", Position.PG),
            "c", player("c", "Clark", Position.SF),
            "d", player("d", "Davis", Position.PF));

    private static Map<Integer, Double> sumByTeam(List<PlayerContribution> cs) {
        Map<Integer, Double> out = new HashMap<>();
        for (PlayerContribution c : cs) out.merge(c.team().rosterId(), c.value(), Double::sum);
        return out;
    }

    private static void assertSumsMatchStandings(List<PlayerContribution> cs, List<Standing> standings) {
        Map<Integer, Double> sums = sumByTeam(cs);
        for (Standing s : standings) {
            assertEquals(s.value(), sums.getOrDefault(s.team().rosterId(), 0.0), 0.01,
                    "roster " + s.team().rosterId() + " contributions must sum to its standings value");
        }
    }

    private static void assertCompetitionRanksAndOrder(List<PlayerContribution> cs) {
        for (int i = 0; i < cs.size(); i++) {
            PlayerContribution c = cs.get(i);
            assertNotEquals(0.0, c.value(), "zero-value rows are excluded");
            if (i == 0) {
                assertEquals(1, c.rank());
                continue;
            }
            PlayerContribution prev = cs.get(i - 1);
            assertTrue(prev.value() >= c.value(), "value descending");
            if (Double.compare(prev.value(), c.value()) == 0) {
                assertEquals(prev.rank(), c.rank(), "ties share a rank");
                assertTrue(prev.playerName().compareToIgnoreCase(c.playerName()) <= 0, "ties broken by player name");
            } else {
                assertEquals(i + 1, c.rank(), "next rank skips");
            }
        }
    }

    // ---------------------------------------------------------------- Embiid

    private static AbsenceCost.PlayerCost pcost(String id, int weeks, double ppg, double lost) {
        return new AbsenceCost.PlayerCost(id, weeks, weeks, ppg, lost);
    }

    private List<PlayerContribution> embiid(Map<Integer, AbsenceCost.RosterCost> byRoster) {
        Superlative s = SeasonSuperlativesService.absenceWinners(byRoster, Map.of(), List.of(), 8, false, rosterIds,
                players, names, avatars, managers);
        assertSumsMatchStandings(s.playerContributions(), s.standings());
        return s.playerContributions();
    }

    @Test
    void embiidContributionsSumToEachTeamAndRankWithTies() {
        Map<Integer, AbsenceCost.RosterCost> byRoster = Map.of(
                // team 1: 30 + 20 = 50; team 2: 30 (ties with team 1's "a") + 0 (excluded) + 5.5
                1, new AbsenceCost.RosterCost(1, 50.0, List.of(pcost("a", 2, 15.0, 30.0), pcost("c", 1, 20.0, 20.0))),
                2, new AbsenceCost.RosterCost(2, 35.5, List.of(pcost("b", 3, 10.0, 30.0), pcost("d", 1, 5.5, 5.5),
                        pcost("a", 0, 12.0, 0.0))));
        List<PlayerContribution> cs = embiid(byRoster);

        assertEquals(4, cs.size(), "the zero-value row is dropped");
        assertCompetitionRanksAndOrder(cs);
        assertEquals(List.of("Adams", "Brown", "Clark", "Davis"), cs.stream().map(PlayerContribution::playerName).toList());
        assertEquals(List.of(1, 1, 3, 4), cs.stream().map(PlayerContribution::rank).toList());
        assertEquals("2 weeks missed · 15.0 pts/game", cs.get(0).note());
        assertEquals("C", cs.get(0).position());
    }

    @Test
    void embiidAPlayerOnTwoTeamsAppearsOncePerTeam() {
        Map<Integer, AbsenceCost.RosterCost> byRoster = Map.of(
                1, new AbsenceCost.RosterCost(1, 10.0, List.of(pcost("a", 1, 10.0, 10.0))),
                2, new AbsenceCost.RosterCost(2, 20.0, List.of(pcost("a", 1, 20.0, 20.0))));
        List<PlayerContribution> cs = embiid(byRoster);
        assertEquals(2, cs.size());
        assertEquals(Set.of(1, 2), Set.of(cs.get(0).team().rosterId(), cs.get(1).team().rosterId()));
        assertEquals(2, cs.get(0).team().rosterId(), "higher value first");
    }

    @Test
    void embiidRowsFromRostersOutsideTheUniverseAreSkippedLikeTheStandings() {
        Map<Integer, AbsenceCost.RosterCost> byRoster = Map.of(
                1, new AbsenceCost.RosterCost(1, 10.0, List.of(pcost("a", 1, 10.0, 10.0))),
                9, new AbsenceCost.RosterCost(9, 99.0, List.of(pcost("b", 1, 99.0, 99.0))));
        assertEquals(1, embiid(byRoster).size());
    }

    // ---------------------------------------------------------------- Waiver

    private static WaiverPickupAttribution.PlayerContribution wpc(String id, int addedWeek, String type, double pts) {
        return new WaiverPickupAttribution.PlayerContribution(id, addedWeek, type, List.of(addedWeek + 1), pts);
    }

    @Test
    void waiverContributionsSumToEachTeamAndNoteNamesTheAddType() {
        Map<Integer, WaiverPickupAttribution.RosterTotal> byRoster = Map.of(
                1, new WaiverPickupAttribution.RosterTotal(1, 70.5,
                        List.of(wpc("a", 2, "WAIVER", 40.25), wpc("b", 3, "FREE_AGENT", 30.25))),
                2, new WaiverPickupAttribution.RosterTotal(2, 40.25,
                        List.of(wpc("c", 1, "WAIVER", 40.25), wpc("d", 4, "WAIVER", 0.0))));
        Superlative s = SeasonSuperlativesService.waiverWinners(byRoster, null, false, rosterIds, players, names,
                avatars, managers);
        List<PlayerContribution> cs = s.playerContributions();

        assertSumsMatchStandings(cs, s.standings());
        assertCompetitionRanksAndOrder(cs);
        assertEquals(3, cs.size());
        assertEquals(List.of(1, 1, 3), cs.stream().map(PlayerContribution::rank).toList());
        assertEquals("Adams", cs.get(0).playerName());
        assertEquals("added wk 2 · waiver", cs.get(0).note());
        assertEquals("added wk 3 · free agent", cs.get(2).note());
    }

    @Test
    void waiverNegativePickupStaysSoTheRowsStillSumToTheTeamFigure() {
        // Roster 1 started a pickup for -0.76 while he was credited (a QB sack-heavy week): the team total
        // includes it, so the Players view must too (spec 040 code-review M1).
        Map<Integer, WaiverPickupAttribution.RosterTotal> byRoster = Map.of(
                1, new WaiverPickupAttribution.RosterTotal(1, 40.24,
                        List.of(wpc("a", 2, "WAIVER", 41.0), wpc("b", 3, "FREE_AGENT", -0.76), wpc("d", 4, "WAIVER", 0.0))),
                2, new WaiverPickupAttribution.RosterTotal(2, 10.0, List.of(wpc("c", 1, "WAIVER", 10.0))));
        Superlative s = SeasonSuperlativesService.waiverWinners(byRoster, null, false, rosterIds, players, names,
                avatars, managers);
        List<PlayerContribution> cs = s.playerContributions();

        assertSumsMatchStandings(cs, s.standings());
        assertEquals(3, cs.size(), "the exactly-zero row is dropped, the negative one is kept");
        assertEquals(-0.76, cs.get(2).value(), 1e-9);
        assertEquals("Brown", cs.get(2).playerName(), "negative rows rank last");
        assertEquals(3, cs.get(2).rank());
        assertCompetitionRanksAndOrder(cs);
    }

    @Test
    void embiidNegativeMeanPlayerStaysSoTheRowsStillSumToTheTeamFigure() {
        // A regular contributor (say a D/ST) whose mean is negative costs -4.0 per missed game.
        Map<Integer, AbsenceCost.RosterCost> byRoster = Map.of(
                1, new AbsenceCost.RosterCost(1, 26.0, List.of(pcost("a", 2, 15.0, 30.0), pcost("b", 1, -4.0, -4.0))));
        List<PlayerContribution> cs = embiid(byRoster); // embiid() asserts the sum against the standings
        assertEquals(2, cs.size());
        assertEquals(-4.0, cs.get(1).value(), 1e-9);
        assertEquals("Brown", cs.get(1).playerName());
    }

    @Test
    void waiverWithNoWinnerHasNoStandingsAndNoContributions() {
        Map<Integer, WaiverPickupAttribution.RosterTotal> byRoster =
                Map.of(1, new WaiverPickupAttribution.RosterTotal(1, 0.0, List.of()));
        Superlative s = SeasonSuperlativesService.waiverWinners(byRoster, null, false, rosterIds, players, names,
                avatars, managers);
        assertTrue(s.standings().isEmpty());
        assertTrue(s.playerContributions().isEmpty());
    }

    // ---------------------------------------------------------------- Unethical

    private static ConductQualifyingWeeks cqw(int roster, String player, String source, Integer... weeks) {
        return new ConductQualifyingWeeks(roster, player, source, List.of(weeks), null);
    }

    @Test
    void unethicalCountsDistinctFlaggedWeeksPerPlayerSoTheSumMatchesTheTotals() {
        Map<Integer, List<ConductQualifyingWeeks>> byRoster = Map.of(
                // "a" is both SUSPENDED and COMMISSIONER over wks 5-7 plus 9: 4 distinct weeks, not 7.
                1, List.of(cqw(1, "a", "SUSPENDED", 5, 6, 7), cqw(1, "a", "COMMISSIONER", 5, 6, 7, 9),
                        cqw(1, "b", "COMMISSIONER", 3)),
                // the same player "a" on another team, once.
                2, List.of(cqw(2, "a", "SUSPENDED", 1, 2), cqw(2, "c", "SUSPENDED", 4)));
        List<PlayerContribution> cs = SeasonSuperlativesService.unethicalContributions(byRoster, rosterIds, players,
                names, avatars, managers);
        Map<Integer, Integer> totals = SeasonSuperlativesService.unethicalTotals(byRoster);
        List<Standing> standings = SeasonSuperlativesService.conductStandings(totals, rosterIds, names, avatars, managers);

        assertSumsMatchStandings(cs, standings);
        assertCompetitionRanksAndOrder(cs);
        assertEquals(4, cs.size(), "a on two teams counts once per team");
        assertEquals(4.0, cs.get(0).value());
        assertEquals("Commissioner + Suspended · wks 5–7, 9", cs.get(0).note());
        assertEquals(1, cs.stream().filter(c -> c.playerId().equals("a") && c.team().rosterId() == 1).count());
        assertEquals(1, cs.stream().filter(c -> c.playerId().equals("a") && c.team().rosterId() == 2).count());
    }

    @Test
    void unethicalSingleSourceNoteNamesThatSourceAndTheWeeks() {
        Map<Integer, List<ConductQualifyingWeeks>> byRoster =
                Map.of(1, List.of(cqw(1, "b", "COMMISSIONER", 3), cqw(1, "c", "COMMISSIONER", 5, 6, 7)));
        List<PlayerContribution> cs = SeasonSuperlativesService.unethicalContributions(byRoster, rosterIds, players,
                names, avatars, managers);
        assertEquals("Commissioner · wks 5–7", cs.get(0).note());
        assertEquals("Commissioner · wk 3", cs.get(1).note());
    }

    @Test
    void weekSpanCollapsesConsecutiveWeeks() {
        assertEquals("wk 4", SeasonSuperlativesService.weekSpan(new TreeSet<>(Set.of(4))));
        assertEquals("wks 5–7, 9", SeasonSuperlativesService.weekSpan(new TreeSet<>(Set.of(5, 6, 7, 9))));
        assertEquals("wks 2, 4", SeasonSuperlativesService.weekSpan(new TreeSet<>(Set.of(2, 4))));
    }

    // ---------------------------------------------------------------- other kinds / Jabari

    @Test
    void kindsWithoutAPlayersViewCarryAnEmptyList() {
        Superlative s = SeasonSuperlativesService.absenceWinners(Map.of(), Map.of(), List.of(), 8, false, rosterIds,
                players, names, avatars, managers);
        assertTrue(s.playerContributions().isEmpty());
        // A standings-only helper never builds contributions: the field defaults to [] for every other kind.
        Superlative plain = new Superlative(SeasonSuperlativesService.Kind.HIGHEST_WEEK, true, null, false, 1.0,
                "POINTS", List.of(), null, List.of(), null);
        assertTrue(plain.playerContributions().isEmpty());
    }

    @Test
    void jabariPlayerStandingsAreNotCappedAtTen() {
        List<LeagueTransactionRepository.Row> rows = new ArrayList<>();
        int seq = 0;
        // 12 players with strictly different add counts (2..13), so no tie rescues an old cap of 10.
        for (int i = 0; i < 12; i++) {
            for (int k = 1; k <= i + 2; k++) {
                int roster = 1 + (k % 2);
                rows.add(new LeagueTransactionRepository.Row(1L, 2026, k, "tx" + (seq++), "FREE_AGENT", "complete",
                        roster, null, "{\"p" + i + "\": " + roster + "}", "{}", null, null, null, null));
            }
        }
        Superlative s = SeasonSuperlativesService.mostAddedSuperlative("L", rows, 13, Map.of(), Map.of(), Map.of());
        assertEquals(12, s.playerStandings().size());
        assertEquals(1, s.playerHolders().size(), "playerHolders stays tied-top only");
    }
}
