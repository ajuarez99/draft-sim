package com.ballknowers.draftsim.engine;

import com.ballknowers.draftsim.config.GradeProperties;
import com.ballknowers.draftsim.engine.DraftGradesService.Input;
import com.ballknowers.draftsim.engine.DraftGradesService.PickGrade;
import com.ballknowers.draftsim.engine.DraftGradesService.PickIn;
import com.ballknowers.draftsim.engine.DraftGradesService.Result;
import com.ballknowers.draftsim.engine.DraftGradesService.RosterInputs;
import com.ballknowers.draftsim.engine.DraftGradesService.TeamGrade;
import com.ballknowers.draftsim.engine.DraftGradesService.TeamIn;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Spec 018 T014: the pure core {@code DraftGradesService.grade(Input)}. No Spring, no database.
 * Production here is handed in already scored (points per game, per player, per week).
 */
class DraftGradesServiceTest {

    private static final GradeProperties LADDER = new GradeProperties(List.of(
            new GradeProperties.Cutoff(25, "A"), new GradeProperties.Cutoff(75, "B"),
            new GradeProperties.Cutoff(100, "C")));

    private final DraftGradesService service = new DraftGradesService(
            new LetterGrades(LADDER), null, null, null, null, null, null, null, null, null, null, null, null, null);

    /** Builds an Input one pick at a time. Each pick is a player named "P{pickNo}" with sleeper id "s{pickNo}". */
    private static final class B {
        final List<PickIn> picks = new ArrayList<>();
        final Map<String, Map<Integer, List<Double>>> games = new HashMap<>();
        Set<Integer> weeks = Set.of(1);
        int min = 3;
        RosterInputs rosters = RosterInputs.EMPTY;
        List<TeamIn> teams = new ArrayList<>();

        B pick(int pickNo, int slot, String position, double production) {
            return pickIn(pickNo, slot, position, position, production);
        }

        /** Basketball shape: everyone shares the group "ALL"; the listed eligibility is display only. */
        B pick(int pickNo, int slot, List<String> positions, double production) {
            return pickIn(pickNo, slot, "ALL", String.join("/", positions), production);
        }

        B pickIn(int pickNo, int slot, String group, String position, double production) {
            picks.add(new PickIn(pickNo, (pickNo - 1) / 12 + 1, slot, 100L + slot, "s" + pickNo, "P" + pickNo,
                    group, position));
            games.put("s" + pickNo, new HashMap<>(Map.of(1, List.of(production))));
            return this;
        }

        B team(int slot) {
            teams.add(new TeamIn(slot, "M" + slot, "av" + slot));
            return this;
        }

        Input build() {
            return new Input(picks, games, weeks, rosters, min, teams);
        }
    }

    private static PickGrade pick(Result r, int pickNo) {
        return r.picks().stream().filter(p -> p.pickNo() == pickNo).findFirst().orElseThrow();
    }

    private static TeamGrade team(Result r, int slot) {
        return r.teams().stream().filter(t -> t.slot() == slot).findFirst().orElseThrow();
    }

    // ---------------------------------------------------------------- SC-003 and F1

    @Test
    void aPickThatOutscoresHisPositionIsTheTopSteal() {
        B b = new B();
        for (int i = 1; i <= 12; i++) b.pick(i, i, "WR", i == 10 ? 100 : 10);
        Result r = service.grade(b.build());

        assertEquals(10, r.steals().getFirst());
        assertTrue(pick(r, 10).valueOverSlot() > 50, "value " + pick(r, 10).valueOverSlot());
        for (int i = 1; i <= 12; i++) {
            if (i != 10) assertTrue(pick(r, i).valueOverSlot() < pick(r, 10).valueOverSlot());
        }
    }

    @Test
    void aLateQuarterbackAtQuarterbackLevelsIsNotAStealOverAReceiverWhoBeatHisPosition() {
        B b = new B();
        b.pick(1, 1, "QB", 300).pick(2, 2, "QB", 300).pick(3, 3, "QB", 300);
        for (int i = 4; i <= 9; i++) b.pick(i, i, "WR", 100);
        b.pick(10, 10, "WR", 150);          // outscored every WR drafted before him
        b.pick(20, 1, "QB", 300);           // late, but exactly what QBs score
        Result r = service.grade(b.build());

        assertEquals(0.0, pick(r, 20).valueOverSlot(), 1e-9);
        assertTrue(pick(r, 10).valueOverSlot() > 0);
        assertTrue(pick(r, 10).valueOverSlot() > pick(r, 20).valueOverSlot());
        assertNotEquals(20, r.steals().getFirst());
        assertEquals(10, r.steals().getFirst());
    }

    // ---------------------------------------------------------------- per-position log fit (T026a)

    @Test
    void productionExactlyOnTheLogLineGivesEveryPickAValueOfZero() {
        B b = new B();
        for (int i = 1; i <= 20; i++) b.pick(i, i, "WR", 400 - 90 * Math.log(i));
        Result r = service.grade(b.build());
        for (PickGrade p : r.picks()) {
            assertNotNull(p.slotBaseline());
            assertEquals(0.0, p.valueOverSlot(), 0.011, "pick " + p.pickNo());
            assertEquals(p.production(), p.slotBaseline(), 0.011);
        }
    }

    @Test
    void valuesSumToZeroWithinAFittedPosition() {
        B b = new B();
        double[] wr = {210, 180, 190, 120, 160, 90, 130, 70, 100, 40, 80, 30};
        double[] rb = {250, 140, 200, 100, 60, 90, 20, 70, 10};
        for (int i = 0; i < wr.length; i++) b.pick(2 * i + 1, i % 4 + 1, "WR", wr[i]);
        for (int i = 0; i < rb.length; i++) b.pick(2 * i + 2, i % 4 + 1, "RB", rb[i]);
        Result r = service.grade(b.build());

        for (String pos : new String[] {"WR", "RB"}) {
            double sum = r.picks().stream().filter(p -> pos.equals(p.position()))
                    .mapToDouble(PickGrade::valueOverSlot).sum();
            assertEquals(0.0, sum, 0.011 * 12, pos);
        }
    }

    @Test
    void theBaselineSlopesDownWhenLaterPicksScoreLess() {
        B b = new B();
        for (int i = 1; i <= 10; i++) b.pick(i, i, "WR", 300 - 25 * i);
        Result r = service.grade(b.build());
        assertTrue(pick(r, 1).slotBaseline() > pick(r, 5).slotBaseline());
        assertTrue(pick(r, 5).slotBaseline() > pick(r, 10).slotBaseline());
    }

    @Test
    void aPositionBelowTheMinimumGetsNoBaselineButStillGetsPositionalRanks() {
        B b = new B();
        b.min = 4;
        for (int i = 1; i <= 6; i++) b.pick(i, i, "WR", 100 - i);       // 6 >= 4: fitted
        b.pick(7, 1, "QB", 200).pick(8, 2, "QB", 150).pick(9, 3, "QB", 90);   // 3 < 4: not
        Result r = service.grade(b.build());

        for (int pickNo : new int[] {7, 8, 9}) {
            assertNull(pick(r, pickNo).slotBaseline());
            assertNull(pick(r, pickNo).valueOverSlot());
        }
        assertEquals(1, pick(r, 7).positionDrafted());
        assertEquals(1, pick(r, 7).positionFinish());
        assertEquals(3, pick(r, 9).positionFinish());
        assertEquals(200.0, pick(r, 7).production(), 1e-9);   // still graded on production
        assertNotNull(pick(r, 1).slotBaseline());
        assertFalse(r.steals().contains(7));
        assertFalse(r.busts().contains(9));
    }

    @Test
    void aPositionWhosePicksShareOnePickNumberHasNoVarianceAndNoFit() {
        B b = new B();
        // Cannot happen in a real draft, but zero variance of ln(pickNo) must answer null, not divide by zero.
        for (int i = 0; i < 4; i++) {
            b.picks.add(new PickIn(5, 1, i + 1, 100L, "z" + i, "Z" + i, "WR", "WR"));
            b.games.put("z" + i, new HashMap<>(Map.of(1, List.of(10.0 * (i + 1)))));
        }
        Result r = service.grade(b.build());
        for (PickGrade p : r.picks()) {
            assertNull(p.slotBaseline());
            assertNull(p.valueOverSlot());
        }
    }

    @Test
    void theMinimumIsInclusive() {
        B b = new B();
        b.min = 4;
        for (int i = 1; i <= 4; i++) b.pick(i, i, "WR", 50 + (i % 2) * 10);
        assertNotNull(pick(service.grade(b.build()), 1).slotBaseline());
        B c = new B();
        c.min = 5;
        for (int i = 1; i <= 4; i++) c.pick(i, i, "WR", 50 + (i % 2) * 10);
        assertNull(pick(service.grade(c.build()), 1).slotBaseline());
    }

    // ---------------------------------------------------------------- production basis and weeks

    @Test
    void aMultiGameWeekIsAveragedAndASingleGameIsTaken() {
        B b = new B();
        b.weeks = Set.of(1, 2);
        b.pick(1, 1, "PG", 0).pick(2, 2, "WR", 0);
        b.games.put("s1", Map.of(1, List.of(10.0, 20.0, 30.0), 2, List.of(5.0)));
        b.games.put("s2", Map.of(1, List.of(7.0)));
        Result r = service.grade(b.build());

        assertEquals(25.0, pick(r, 1).production(), 1e-9);   // (10+20+30)/3 + 5
        assertEquals(2, pick(r, 1).weeksPlayed());
        assertEquals(7.0, pick(r, 2).production(), 1e-9);
        assertEquals(1, pick(r, 2).weeksPlayed());
    }

    @Test
    void weeksOutsideTheCountedWeeksAreIgnored() {
        B b = new B();
        b.weeks = Set.of(1, 2);
        b.pick(1, 1, "WR", 0);
        b.games.put("s1", Map.of(1, List.of(10.0), 2, List.of(10.0), 5, List.of(999.0)));
        Result r = service.grade(b.build());
        assertEquals(20.0, pick(r, 1).production(), 1e-9);
        assertEquals(2, pick(r, 1).weeksPlayed());
    }

    @Test
    void weeksPlayedNeverExceedsWeeksCountedAndProductionIsNotNegative() {
        B b = new B();
        b.weeks = Set.of(1, 2, 3);
        for (int i = 1; i <= 6; i++) {
            b.pick(i, i, "WR", 0);
            b.games.put("s" + i, Map.of(1, List.of(3.0 * i), 2, List.of(1.0), 9, List.of(50.0)));
        }
        Result r = service.grade(b.build());
        for (PickGrade p : r.picks()) {
            assertTrue(p.weeksPlayed() <= 3, "weeksPlayed " + p.weeksPlayed());
            assertTrue(p.production() >= 0, "production " + p.production());
        }
    }

    // ---------------------------------------------------------------- N11 and positional ranks

    @Test
    void anEmptyPositionListIsNullNotWrAndGetsNoBaselineOrPositionalRanks() {
        B b = new B();
        b.pick(1, 1, "WR", 10).pick(2, 2, "WR", 20).pick(3, 3, "WR", 30);
        b.pick(4, 4, (String) null, 500);
        Result r = service.grade(b.build());

        PickGrade p = pick(r, 4);
        assertNull(p.position());
        assertNull(p.slotBaseline());
        assertNull(p.valueOverSlot());
        assertNull(p.positionDrafted());
        assertNull(p.positionFinish());
        assertEquals(500.0, p.production(), 1e-9);
        assertEquals(1, r.unpositionedPicks());
        // And he is not mixed into the WR fit: it is the same as without him.
        B without = new B();
        without.pick(1, 1, "WR", 10).pick(2, 2, "WR", 20).pick(3, 3, "WR", 30);
        assertEquals(pick(service.grade(without.build()), 1).slotBaseline(), pick(r, 1).slotBaseline(), 1e-9);
    }

    @Test
    void positionalRanksOrderByPickNoAndByProductionWithTiesToTheLowerPick() {
        B b = new B();
        b.pick(5, 1, "WR", 50).pick(7, 2, "WR", 80).pick(9, 3, "WR", 80).pick(8, 4, "RB", 999);
        Result r = service.grade(b.build());

        assertEquals(1, pick(r, 5).positionDrafted());
        assertEquals(2, pick(r, 7).positionDrafted());
        assertEquals(3, pick(r, 9).positionDrafted());
        assertEquals(3, pick(r, 5).positionFinish());
        assertEquals(1, pick(r, 7).positionFinish());
        assertEquals(2, pick(r, 9).positionFinish());
        assertEquals(1, pick(r, 8).positionDrafted());
        assertEquals(1, pick(r, 8).positionFinish());
    }

    @Test
    void basketballGroupsEveryoneTogetherAndShowsEligibilityJoined() {
        // B1: a PG-listed player and an SG-only player share one fit, so the SG is not stranded below the minimum.
        B b = new B();
        b.min = 3;
        b.pick(1, 1, List.of("PG", "SG"), 100).pick(2, 2, List.of("SG"), 90).pick(3, 3, List.of("C"), 80)
                .pick(4, 4, List.of("PF", "PG", "SF"), 70);
        Result r = service.grade(b.build());
        assertEquals("PG/SG", pick(r, 1).position());
        assertEquals("SG", pick(r, 2).position());
        assertEquals(0, r.unpositionedPicks());
        for (int i = 1; i <= 4; i++) {
            assertNotNull(pick(r, i).slotBaseline(), "pick " + i);
            assertEquals(i, pick(r, i).positionDrafted());
            assertEquals(i, pick(r, i).positionFinish());
        }
    }

    @Test
    void aFootballPickWithNoGroupIsUnpositionedNotAWr() {
        B b = new B();
        b.pick(1, 1, "WR", 10).pick(2, 2, "WR", 20).pick(3, 3, "WR", 30);
        b.pickIn(4, 4, null, null, 500);
        Result r = service.grade(b.build());
        assertNull(pick(r, 4).slotBaseline());
        assertNull(pick(r, 4).positionDrafted());
        assertEquals(1, r.unpositionedPicks());
    }

    @Test
    void positionalFinishTiesCompareTheTwoDecimalValueNotTheRawDouble() {
        // 0.1 * 3 sums to 0.30000000000000004 in doubles; both show 0.30, so the lower pick finishes ahead.
        B b = new B();
        b.pick(1, 1, "WR", 0).pick(2, 2, "WR", 0).pick(3, 3, "WR", 0);
        b.games.put("s1", Map.of(1, List.of(0.3)));
        b.games.put("s2", Map.of(1, List.of(0.1), 2, List.of(0.1), 3, List.of(0.1)));
        b.games.put("s3", Map.of(1, List.of(0.0)));
        b.weeks = Set.of(1, 2, 3);
        Result r = service.grade(b.build());
        assertEquals(1, pick(r, 1).positionFinish());
        assertEquals(2, pick(r, 2).positionFinish());
        assertEquals(3, pick(r, 3).positionFinish());
    }

    @Test
    void aFinalLeagueWeekWhoseGameDataIsNotFinalIsNotCounted() {
        java.time.Instant t = java.time.Instant.EPOCH;
        List<com.ballknowers.draftsim.store.SportWeekStatsRepository.Row> rows = List.of(
                new com.ballknowers.draftsim.store.SportWeekStatsRepository.Row(com.ballknowers.draftsim.domain.Sport.NFL, 2026, 1, t, true),
                new com.ballknowers.draftsim.store.SportWeekStatsRepository.Row(com.ballknowers.draftsim.domain.Sport.NFL, 2026, 2, t, false),
                new com.ballknowers.draftsim.store.SportWeekStatsRepository.Row(com.ballknowers.draftsim.domain.Sport.NFL, 2026, 4, t, true));
        // League finals 1,2,3: week 2 has non-final game data, week 3 has none, week 4 is not a league final week.
        java.util.TreeSet<Integer> counted = DraftGradesService.countedWeeks(Set.of(1, 2, 3), rows);
        assertEquals(Set.of(1), counted);
        java.util.TreeSet<Integer> missing = new java.util.TreeSet<>(Set.of(1, 2, 3));
        missing.removeAll(counted);
        assertEquals(List.of(2, 3), List.copyOf(missing));
    }

    // ---------------------------------------------------------------- teams

    private static B threeTeams() {
        B b = new B();
        b.team(1).team(2).team(3);
        // One position, 9 picks: slot s holds picks s, s+3, s+6.
        double[] prod = {50, 10, 30, 20, 80, 40, 60, 15, 90};
        for (int i = 1; i <= 9; i++) b.pick(i, (i - 1) % 3 + 1, "WR", prod[i - 1]);
        return b;
    }

    @Test
    void teamValuesAreCentredOnTheAverageTeam() {
        Result r = service.grade(threeTeams().build());

        double sum = 0, raw = 0;
        for (TeamGrade t : r.teams()) {
            assertNotNull(t.draftValue());
            sum += t.draftValue();
        }
        assertEquals(0.0, sum, 0.05);
        for (PickGrade p : r.picks()) raw += p.valueOverSlot();
        assertEquals(raw / 3.0, r.averageTeamRawValue(), 0.01);
    }

    @Test
    void aTeamWithNoGradedPicksIsNullAndUnranked() {
        B b = threeTeams();
        b.team(4);                                              // no picks at all
        b.pick(10, 5, (String) null, 100);                      // one pick, but no position -> no value
        b.team(5);
        Result r = service.grade(b.build());

        for (int slot : new int[] {4, 5}) {
            TeamGrade t = team(r, slot);
            assertNull(t.draftValue());
            assertNull(t.rank());
            assertNull(t.grade());
        }
        // The three real teams are still ranked among themselves.
        for (int slot : new int[] {1, 2, 3}) {
            assertNotNull(team(r, slot).rank());
            assertTrue(team(r, slot).rank() >= 1 && team(r, slot).rank() <= 3);
        }
        assertEquals(0.0, r.teams().stream().filter(t -> t.draftValue() != null)
                .mapToDouble(TeamGrade::draftValue).sum(), 0.05);
    }

    @Test
    void tiedTeamsShareARankAndAGrade() {
        B b = new B();
        b.team(1).team(2).team(3).team(4);
        // Every pick the same production -> every team's raw value is exactly 0.
        for (int i = 1; i <= 8; i++) b.pick(i, (i - 1) % 4 + 1, "WR", 10);
        Result r = service.grade(b.build());

        assertEquals(1, team(r, 1).rank());
        assertEquals(1, team(r, 4).rank());
        assertEquals(team(r, 1).grade(), team(r, 2).grade());
        assertEquals("A", team(r, 3).grade());
    }

    @Test
    void bestAndWorstPickComeFromTheTeamsOwnPicks() {
        Result r = service.grade(threeTeams().build());
        for (TeamGrade t : r.teams()) {
            List<PickGrade> mine = r.picks().stream().filter(p -> p.slot() == t.slot()).toList();
            int best = mine.stream().max(java.util.Comparator.comparingDouble(PickGrade::valueOverSlot)
                    .thenComparing(java.util.Comparator.comparingInt(PickGrade::pickNo).reversed())).get().pickNo();
            int worst = mine.stream().min(java.util.Comparator.comparingDouble(PickGrade::valueOverSlot)
                    .thenComparingInt(PickGrade::pickNo)).get().pickNo();
            assertEquals(best, t.bestPickNo());
            assertEquals(worst, t.worstPickNo());
        }
    }

    // ---------------------------------------------------------------- steals and busts

    @Test
    void stealsAndBustsAreAtMostFiveEachAndNeverOverlap() {
        B b = new B();
        double[] prod = {10, 10, 10, 10, 10, 70};   // WR 6 outscores the flat line by a lot
        for (int i = 1; i <= 6; i++) b.pick(i, i, "WR", prod[i - 1]);
        Result r = service.grade(b.build());
        assertEquals(5, r.steals().size());
        assertEquals(1, r.busts().size());   // 6 graded picks: only one is left after the steals
        for (int s : r.steals()) assertFalse(r.busts().contains(s));
        assertEquals(6, r.steals().getFirst());
        double minSteal = r.steals().stream().mapToDouble(n -> pick(r, n).valueOverSlot()).min().getAsDouble();
        assertTrue(pick(r, r.busts().getFirst()).valueOverSlot() <= minSteal);
        for (int k = 1; k < r.steals().size(); k++) {
            assertTrue(pick(r, r.steals().get(k - 1)).valueOverSlot() >= pick(r, r.steals().get(k)).valueOverSlot());
        }
    }

    @Test
    void aBigDraftHoldsExactlyFiveStealsAndFiveBusts() {
        B b = new B();
        for (int i = 1; i <= 30; i++) b.pick(i, (i - 1) % 6 + 1, "WR", 200 - 3 * i + ((i * 37) % 11) * 4);
        Result r = service.grade(b.build());
        assertEquals(5, r.steals().size());
        assertEquals(5, r.busts().size());
        for (int s : r.steals()) assertFalse(r.busts().contains(s));
    }

    @Test
    void stealTiesGoToTheLowerPickNumber() {
        B b = new B();
        for (int i = 1; i <= 4; i++) b.pick(i, i, "WR", 10);
        Result r = service.grade(b.build());   // every value is 0
        assertEquals(List.of(1, 2, 3, 4), r.steals());
        assertTrue(r.busts().isEmpty());
    }

    @Test
    void picksWithoutAValueAreNeverStealsOrBusts() {
        B b = new B();
        b.pick(1, 1, "QB", 500);   // alone at his position: null value
        b.pick(2, 2, "WR", 10).pick(3, 3, "WR", 30).pick(4, 4, "WR", 20);
        Result r = service.grade(b.build());
        assertFalse(r.steals().contains(1));
        assertFalse(r.busts().contains(1));
    }

    // ---------------------------------------------------------------- counted for the drafting team (US3)

    /** Roster id = slot; the manager of slot s is 100 + s, as in {@code B.pick}. */
    private static final class R {
        final Map<Long, Long> rosterByManager = new HashMap<>();
        final Map<Long, Map<Integer, Set<String>>> starters = new HashMap<>();
        final Map<Long, Map<Integer, Map<String, Double>>> points = new HashMap<>();
        final Map<Long, Set<Integer>> matchupWeeks = new HashMap<>();
        final Map<Long, Set<Integer>> unknown = new HashMap<>();

        R roster(int slot) {
            rosterByManager.put(100L + slot, (long) slot);
            return this;
        }

        R week(int slot, int week, boolean game, Map<String, Double> pts, String... started) {
            if (game) matchupWeeks.computeIfAbsent((long) slot, k -> new java.util.HashSet<>()).add(week);
            starters.computeIfAbsent((long) slot, k -> new HashMap<>()).put(week, Set.of(started));
            points.computeIfAbsent((long) slot, k -> new HashMap<>()).put(week, pts);
            return this;
        }

        R unknown(int slot, int... weeks) {
            for (int w : weeks) unknown.computeIfAbsent((long) slot, k -> new java.util.HashSet<>()).add(w);
            return this;
        }

        RosterInputs build() {
            return new RosterInputs(rosterByManager, starters, points, matchupWeeks, unknown);
        }
    }

    @Test
    void countedForYouIsOnProductionsBasisSoItNeverExceedsProductionEvenWhenCreditedDoes() {
        B b = new B();
        b.weeks = Set.of(1, 2);
        b.pick(1, 1, "PG", 0);
        b.games.put("s1", Map.of(1, List.of(10.0, 30.0), 2, List.of(10.0)));   // week 1 averages 20
        b.rosters = new R().roster(1)
                .week(1, 1, true, Map.of("s1", 30.0), "s1").week(1, 2, true, Map.of("s1", 10.0), "s1").build();
        PickGrade p = pick(service.grade(b.build()), 1);

        assertEquals(30.0, p.production(), 1e-9);
        assertEquals(30.0, p.countedForYou(), 1e-9);
        assertEquals(40.0, p.creditedForYou(), 1e-9);            // Sleeper's number, on its own scale
        assertTrue(p.countedForYou() <= p.production() + 0.01);
        assertEquals(2, p.weeksStartedForYou());
        assertEquals(0, p.weeksUnknownForYou());
    }

    @Test
    void aLoyalStarterCountsAboutAllOfHisProductionAndADroppedPlayerFarLess() {
        B b = new B();
        b.weeks = Set.of(1, 2, 3, 4);
        b.pick(1, 1, "WR", 0).pick(2, 2, "WR", 0);
        b.games.put("s1", Map.of(1, List.of(10.0), 2, List.of(10.0), 3, List.of(10.0), 4, List.of(10.0)));
        b.games.put("s2", Map.of(1, List.of(10.0), 2, List.of(10.0), 3, List.of(10.0), 4, List.of(10.0)));
        R r = new R().roster(1).roster(2);
        for (int w = 1; w <= 4; w++) r.week(1, w, true, Map.of("s1", 10.0), "s1");
        r.week(2, 1, true, Map.of("s2", 10.0), "s2");                       // dropped after week 1
        for (int w = 2; w <= 4; w++) r.week(2, w, true, Map.of("x", 5.0), "x");
        b.rosters = r.build();
        Result res = service.grade(b.build());

        assertEquals(pick(res, 1).production(), pick(res, 1).countedForYou(), 0.01);
        assertEquals(10.0, pick(res, 2).countedForYou(), 1e-9);
        assertEquals(1, pick(res, 2).weeksStartedForYou());
        assertTrue(pick(res, 2).countedForYou() < 0.5 * pick(res, 2).production());
    }

    @Test
    void creditedForYouSumsPlayersPointsOverTheStartedWeeksOnly() {
        B b = new B();
        b.weeks = Set.of(1, 2, 3);
        b.pick(1, 1, "WR", 0);
        b.games.put("s1", Map.of(1, List.of(8.0), 2, List.of(9.0), 3, List.of(7.0)));
        b.rosters = new R().roster(1)
                .week(1, 1, true, Map.of("s1", 8.5), "s1")
                .week(1, 2, true, Map.of("s1", 99.0), "other")      // benched: his 99 is not credited to the team
                .week(1, 3, true, Map.of("s1", 7.25), "s1").build();
        PickGrade p = pick(service.grade(b.build()), 1);

        assertEquals(15.75, p.creditedForYou(), 1e-9);
        assertEquals(15.0, p.countedForYou(), 1e-9);
        assertEquals(2, p.weeksStartedForYou());
    }

    @Test
    void aWeekWithoutAMatchupDoesNotCountEvenIfHeIsInTheStoredLineup() {
        B b = new B();
        b.weeks = Set.of(1, 2, 3);
        b.pick(1, 1, "PG", 0);
        b.games.put("s1", Map.of(1, List.of(10.0), 2, List.of(10.0), 3, List.of(50.0)));
        b.rosters = new R().roster(1)
                .week(1, 1, true, Map.of("s1", 10.0), "s1").week(1, 2, true, Map.of("s1", 10.0), "s1")
                .week(1, 3, false, Map.of("s1", 50.0), "s1").build();     // week 3: roster had no game (playoffs, out)
        PickGrade p = pick(service.grade(b.build()), 1);

        assertEquals(2, p.weeksStartedForYou());
        assertEquals(0, p.weeksUnknownForYou());
        assertEquals(20.0, p.countedForYou(), 1e-9);
        assertEquals(20.0, p.creditedForYou(), 1e-9);
        assertEquals(70.0, p.production(), 1e-9);
    }

    @Test
    void anUnknownWeekIsCountedAsUnknownAndNeverAsZeroPoints() {
        B b = new B();
        b.weeks = Set.of(1, 2, 3);
        b.pick(1, 1, "WR", 0);
        b.games.put("s1", Map.of(1, List.of(10.0), 2, List.of(10.0), 3, List.of(10.0)));
        b.rosters = new R().roster(1)
                .week(1, 1, true, Map.of("s1", 10.0), "s1")
                .unknown(1, 2, 3).build();                                // starters null / "{}" points / no matchup row
        PickGrade p = pick(service.grade(b.build()), 1);

        assertEquals(1, p.weeksStartedForYou());
        assertEquals(2, p.weeksUnknownForYou());
        assertEquals(10.0, p.countedForYou(), 1e-9);
        assertTrue(p.weeksStartedForYou() + p.weeksUnknownForYou() <= 3);
    }

    @Test
    void aManagerWithoutExactlyOneRosterLeavesAllFourFieldsNullAndIsCounted() {
        B b = new B();
        b.pick(1, 1, "WR", 10).pick(2, 2, "WR", 10);
        b.rosters = new R().roster(1).build();            // manager 102 has no roster, or two (absent from the map)
        Result r = service.grade(b.build());

        assertNotNull(pick(r, 1).countedForYou());
        PickGrade p = pick(r, 2);
        assertNull(p.countedForYou());
        assertNull(p.creditedForYou());
        assertNull(p.weeksStartedForYou());
        assertNull(p.weeksUnknownForYou());
        assertEquals(1, r.unmappedPicks());
    }

    @Test
    void aPickWithNoManagerIsUnmapped() {
        B b = new B();
        b.picks.add(new PickIn(1, 1, 1, null, "s1", "P1", "WR", "WR"));
        b.games.put("s1", new HashMap<>(Map.of(1, List.of(10.0))));
        b.rosters = new R().roster(1).build();
        Result r = service.grade(b.build());
        assertNull(pick(r, 1).countedForYou());
        assertEquals(1, r.unmappedPicks());
    }
}
