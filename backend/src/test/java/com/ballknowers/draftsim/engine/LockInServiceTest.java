package com.ballknowers.draftsim.engine;

import com.ballknowers.draftsim.config.LockInProperties;
import com.ballknowers.draftsim.config.PlayerTrendsProperties;
import com.ballknowers.draftsim.domain.Platform;
import com.ballknowers.draftsim.domain.Sport;
import com.ballknowers.draftsim.engine.LockInService.Computed;
import com.ballknowers.draftsim.engine.LockInService.Inputs;
import com.ballknowers.draftsim.engine.LockInService.Lock;
import com.ballknowers.draftsim.engine.LockInService.LockInReport;
import com.ballknowers.draftsim.engine.LockInService.LockSummary;
import com.ballknowers.draftsim.store.LeagueRepository.LeagueRow;
import com.ballknowers.draftsim.store.PlayerGameRepository.SeasonGame;
import com.ballknowers.draftsim.store.PlayerGameRepository.TeamGame;
import com.ballknowers.draftsim.store.ProjectionSnapshotRepository;
import com.ballknowers.draftsim.store.RosterWeekPointsRepository.WeekBreakdown;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.OptionalInt;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Spec 044 T014: the gate, the game set, the week rule, the baselines and the totals invariant of
 * {@link LockInService#compute}, on small hand-built fixtures. Scoring is {@code pts} x 1 unless a test says
 * otherwise, so a game's score is its points and every expected value below is hand arithmetic.
 */
class LockInServiceTest {

    private static final LocalDate D0 = LocalDate.of(2025, 10, 20);
    private static final Map<String, Double> PTS = Map.of("pts", 1.0);

    private final LockInProperties props = new LockInProperties(2, 15, 5, List.of(0.0, 0.10, 0.25, 0.50), 3, 4, 60, 15);
    private final PlayerTrendsProperties trends = new PlayerTrendsProperties(6, 5, 5, 3, 14, 10, 20, 0.9);
    private final LockInService service = new LockInService(null, null, null, null, null, null, null, null,
            new GameScoringService(), props, trends);

    private static LeagueRow nba() {
        return new LeagueRow(1L, Sport.NBA, "sleeper-1", "L", 2025, 12, List.of(), 0.0, null, "complete");
    }

    /** A fixture: player games, team rows, and the roster-weeks that credit them. */
    private static final class Fx {
        final List<SeasonGame> games = new ArrayList<>();
        final List<TeamGame> teams = new ArrayList<>(List.of(
                new TeamGame("AAA", "t1", D0, "BBB", Map.of()), new TeamGame("BBB", "t1", D0, "AAA", Map.of())));
        final List<WeekBreakdown> weeks = new ArrayList<>();
        final List<ProjectionSnapshotRepository.Line> lines = new ArrayList<>();
        final Map<Integer, Long> managers = new HashMap<>(Map.of(1, 100L));
        int seq;

        /** A real game (opponent a season team code) on day {@code day} of week {@code week}. */
        Fx game(String pid, int week, int day, Map<String, Object> stats) {
            return gameVs(pid, "g" + (++seq), "BBB", week, day, stats);
        }

        Fx gameVs(String pid, String gameId, String opponent, int week, int day, Map<String, Object> stats) {
            Map<String, Object> s = new LinkedHashMap<>(stats);
            s.put("sp", 1800.0);
            games.add(new SeasonGame(pid, gameId, D0.plusDays(day), opponent, s, week, true));
            return this;
        }

        Fx pts(String pid, int week, int day, double points) {
            return game(pid, week, day, Map.of("pts", points));
        }

        /** Roster 1 starts {@code credits.keySet()} in {@code week} and is credited these values. */
        Fx credit(int week, Map<String, Double> credits) {
            StringBuilder pp = new StringBuilder("{");
            StringBuilder st = new StringBuilder("[");
            for (Map.Entry<String, Double> e : credits.entrySet()) {
                if (pp.length() > 1) {
                    pp.append(',');
                    st.append(',');
                }
                pp.append('"').append(e.getKey()).append("\":").append(e.getValue());
                st.append('"').append(e.getKey()).append('"');
            }
            weeks.add(new WeekBreakdown(week, 1, 0, pp.append('}').toString(), st.append(']').toString()));
            return this;
        }
    }

    private Inputs inputs(Fx fx, Set<Integer> weeks, Map<String, Double> scoring, Double shareThreshold) {
        return new Inputs(nba(), OptionalInt.of(1), scoring, weeks, fx.games, fx.teams, fx.weeks, fx.managers,
                Map.of(1, "Roster One"), Map.of(), 0, fx.lines, shareThreshold, ids -> {
                    Map<String, String> out = new HashMap<>();
                    ids.forEach(id -> out.put(id, "Player " + id));
                    return out;
                });
    }

    private LockInReport measure(Fx fx, Set<Integer> weeks) {
        return service.compute(inputs(fx, weeks, PTS, 0.0)).report();
    }

    private static LockSummary manager(LockInReport r) {
        return r.managers().get(0);
    }

    // ---- gate ----

    @Test
    void theGateAnswersEachStatusInOrder() {
        Fx fx = new Fx().pts("p1", 1, 0, 20).pts("p1", 1, 1, 30).credit(1, Map.of("p1", 20.0));
        Set<Integer> weeks = Set.of(1);

        LeagueRow espn = new LeagueRow(1L, Sport.NBA, "e", "L", 2025, 12, List.of(), 0.0, null, "complete",
                Platform.ESPN, "99", List.of(), List.of());
        assertEquals("NOT_SLEEPER", service.compute(new Inputs(espn, OptionalInt.of(1), PTS, weeks, fx.games, fx.teams,
                fx.weeks, fx.managers, Map.of(), Map.of(), 0, List.of(), 0.9, ids -> Map.of())).report().status());

        LeagueRow nfl = new LeagueRow(1L, Sport.NFL, "n", "L", 2025, 12, List.of(), 0.0, null, "complete");
        assertEquals("NOT_NBA", service.compute(new Inputs(nfl, OptionalInt.of(1), PTS, weeks, fx.games, fx.teams,
                fx.weeks, fx.managers, Map.of(), Map.of(), 0, List.of(), 0.9, ids -> Map.of())).report().status());

        for (OptionalInt mode : new OptionalInt[]{OptionalInt.empty(), OptionalInt.of(0), OptionalInt.of(2)}) {
            assertEquals("GAME_MODE", service.compute(new Inputs(nba(), mode, PTS, weeks, fx.games, fx.teams, fx.weeks,
                    fx.managers, Map.of(), Map.of(), 0, List.of(), 0.9, ids -> Map.of())).report().status(),
                    "game_mode " + mode + " is not lock-in; an absent mode is not defaulted");
        }

        assertEquals("NO_SCORING", service.compute(inputs(fx, weeks, Map.of(), 0.9)).report().status());
        assertEquals("NO_SCORED_WEEKS", service.compute(inputs(fx, Set.of(), PTS, 0.9)).report().status());
        assertEquals("MEASURED", service.compute(inputs(fx, weeks, PTS, 0.0)).report().status());
    }

    @Test
    void aShareBelowTrendsOwnThresholdRefusesButKeepsTheTotalsItJudgedBy() {
        // 1 lock (30 of 20/30/25), 1 unmatched (44 of 20/30): matched share 1/2 = 0.5.
        Fx fx = new Fx()
                .pts("p1", 1, 0, 20).pts("p1", 1, 1, 30).pts("p1", 1, 2, 25)
                .pts("p2", 1, 0, 20).pts("p2", 1, 1, 30)
                .credit(1, new LinkedHashMap<>(Map.of("p1", 30.0, "p2", 44.0)));
        Computed refused = service.compute(inputs(fx, Set.of(1), PTS, 0.9));
        assertEquals("SHARE_BELOW_THRESHOLD", refused.report().status());
        assertTrue(refused.report().reason().contains("50.0%"), refused.report().reason());
        assertEquals(2, refused.report().totals().multiGame(), "the totals the refusal rests on stay visible");
        assertTrue(refused.report().managers().isEmpty());
        assertEquals("MEASURED", service.compute(inputs(fx, Set.of(1), PTS, 0.5)).report().status(), "at the threshold passes");

        assertEquals("SHARE_BELOW_THRESHOLD", service.compute(inputs(fx, Set.of(1), PTS, null)).report().status(),
                "an unconfigured threshold is not silently skipped");
    }

    // ---- game set ----

    @Test
    void anAllStarGameCreatesNoLock() {
        // 20 then an All-Star game (opponent STR is no season team) of 30; credited 20. Raw games would read this as
        // a lock of the first game. Without the All-Star row it is a single-game week.
        Fx fx = new Fx().pts("p1", 1, 0, 20).gameVs("p1", "allstar", "STR", 1, 1, Map.of("pts", 30.0))
                .credit(1, Map.of("p1", 20.0));
        Computed c = service.compute(inputs(fx, Set.of(1), PTS, 0.0));
        assertEquals(0, c.report().totals().lock());
        assertEquals(1, c.report().totals().singleGame());
        assertTrue(c.allLocks().isEmpty());
    }

    @Test
    void theCupFinalIsAnOrdinaryMatchableGame() {
        // The service never reads weights.yml's excluded-game-ids: Sleeper credits the Cup final (F2).
        Fx fx = new Fx().gameVs("p1", "20241217_OKC_MIL", "BBB", 1, 0, Map.of("pts", 30.0)).pts("p1", 1, 1, 20)
                .credit(1, Map.of("p1", 30.0));
        Computed c = service.compute(inputs(fx, Set.of(1), PTS, 0.0));
        assertEquals(1, c.report().totals().lock());
        assertEquals("20241217_OKC_MIL", c.allLocks().get(0).gameId());
    }

    // ---- weeks (F7) ----

    @Test
    void aLeagueStillBeingPlayedNeverPublishesItsCurrentWeek() {
        // ScoredWeeks treats a league with no POINTS fetch rows as all-final, which is week 1's state.
        Set<Integer> storedAsFinal = Set.of(1, 2, 3);
        assertEquals(Set.of(1, 2), LockInService.scorable(storedAsFinal, false, OptionalInt.of(3)));
        assertEquals(Set.of(1, 2, 3), LockInService.scorable(storedAsFinal, true, OptionalInt.of(3)),
                "a complete league keeps its championship week");
        assertEquals(storedAsFinal, LockInService.scorable(storedAsFinal, false, OptionalInt.empty()),
                "an absent leg is not defaulted");
        assertEquals(Set.of(), LockInService.scorable(Set.of(3), false, OptionalInt.of(3)));
    }

    @Test
    void onlyTheGivenWeeksAreRead() {
        Fx fx = new Fx()
                .pts("p1", 1, 0, 20).pts("p1", 1, 1, 30).pts("p1", 2, 7, 20).pts("p1", 2, 8, 30)
                .credit(1, Map.of("p1", 20.0)).credit(2, Map.of("p1", 20.0));
        assertEquals(1, measure(fx, Set.of(1)).totals().lock());
        assertEquals(2, measure(fx, Set.of(1, 2)).totals().lock());
    }

    // ---- baselines ----

    /** Six prior single games of 10, then week 7: 15 (locked) and 5. */
    private Fx standInFixture(int priorGames) {
        Fx fx = new Fx();
        for (int i = 0; i < priorGames; i++) fx.pts("p1", i + 1, i, 10);
        return fx.pts("p1", 7, 20, 15).pts("p1", 7, 21, 5).credit(7, Map.of("p1", 15.0));
    }

    @Test
    void theStandInIsTheMeanOfEarlierGamesAndNeedsMinPriorGames() {
        Computed enough = service.compute(inputs(standInFixture(6), Set.of(7), PTS, 0.0));
        Lock l = enough.allLocks().get(0);
        assertEquals(10.0, l.standIn().baseline(), 1e-12);
        assertEquals(0.5, l.standIn().margin(), 1e-12, "(15 - 10) / 10");
        assertEquals(1, l.laterGames());
        assertEquals(5.0, l.lastGame(), 1e-12);
        assertEquals(5.0, l.bestLater(), 1e-12);
        assertFalse(l.beatenByLast());
        assertFalse(l.beatenLater());
        assertEquals(0, enough.report().totals().noBaselineStandIn());
        assertEquals(1, enough.report().weeksCovered());

        Computed few = service.compute(inputs(standInFixture(4), Set.of(7), PTS, 0.0));
        assertEquals(1, few.report().totals().lock(), "still a provable lock");
        assertNull(few.allLocks().get(0).standIn().baseline(), "4 prior games is below min-prior-games 5");
        assertNull(few.allLocks().get(0).standIn().margin());
        assertEquals(1, few.report().totals().noBaselineStandIn());
        assertEquals(0, manager(few.report()).standIn().n());
    }

    @Test
    void aZeroAverageStandInHasNoMargin() {
        Fx fx = new Fx();
        for (int i = 0; i < 5; i++) fx.pts("p1", i + 1, i, 0);
        fx.pts("p1", 7, 20, 15).pts("p1", 7, 21, 5).credit(7, Map.of("p1", 15.0));
        Computed c = service.compute(inputs(fx, Set.of(7), PTS, 0.0));
        assertEquals(0.0, c.allLocks().get(0).standIn().baseline(), 1e-12);
        assertNull(c.allLocks().get(0).standIn().margin(), "a margin is never formed on a baseline of 0 or less");
        assertEquals(1, c.report().totals().noBaselineStandIn());
    }

    @Test
    void theProjectionMarginScoresBothSidesOnTheRestrictedMap() {
        // dd is scored (1.0) but no projection line carries it, so it is graded on neither side.
        Map<String, Double> scoring = new LinkedHashMap<>();
        scoring.put("pts", 1.0);
        scoring.put("ast", 1.0);
        scoring.put("dd", 1.0);
        // Locked game: 30 pts + 5 ast + a double-double = 36 credited; restricted (pts + ast) = 35.
        Fx fx = new Fx()
                .game("p1", 1, 0, Map.of("pts", 30.0, "ast", 5.0, "dd", 1.0))
                .game("p1", 1, 1, Map.of("pts", 10.0))
                .credit(1, Map.of("p1", 36.0));
        String lockedGame = fx.games.get(0).gameId();
        fx.lines.add(new ProjectionSnapshotRepository.Line("p1", lockedGame, Map.of("pts", 20.0, "ast", 5.0)));
        Computed c = service.compute(inputs(fx, Set.of(1), scoring, 0.0));

        Lock l = c.allLocks().get(0);
        assertEquals(36.0, l.points(), 1e-12);
        assertEquals(25.0, l.projection().projectedPoints(), 1e-12);
        assertEquals(35.0, l.projection().actualRestricted(), 1e-12, "re-scored from the game's own stats, without dd");
        assertEquals(0.4, l.projection().margin(), 1e-12, "(35 - 25) / 25, not (36 - 25) / 25");
        assertEquals(List.of("dd"), c.report().excludedCategories());
        assertEquals(0, c.report().totals().noSnapshot());
    }

    @Test
    void aLockWithNoSnapshotIsCountedAndCarriesNoProjection() {
        Computed c = service.compute(inputs(standInFixture(6), Set.of(7), PTS, 0.0));
        assertNull(c.allLocks().get(0).projection());
        assertEquals(1, c.report().totals().noSnapshot());
        assertEquals(List.of(), c.report().excludedCategories());
    }

    @Test
    void standInAndProjectionFiguresAreNeverCombined() {
        // Two locks by one manager, both with a stand-in margin; only the second has a snapshot. min-locks is 2.
        Fx fx = new Fx();
        for (int i = 0; i < 5; i++) fx.pts("p1", i + 1, i, 10).pts("p2", i + 1, i, 20);
        fx.pts("p1", 7, 20, 15).pts("p1", 7, 21, 5)                   // p1 locks 15 vs 10: +50%
                .pts("p2", 7, 20, 30).pts("p2", 7, 21, 5)             // p2 locks 30 vs 20: +50%
                .credit(7, new LinkedHashMap<>(Map.of("p1", 15.0, "p2", 30.0)));
        String p2Locked = fx.games.stream().filter(g -> g.sleeperPlayerId().equals("p2") && g.week() == 7)
                .findFirst().orElseThrow().gameId();
        fx.lines.add(new ProjectionSnapshotRepository.Line("p2", p2Locked, Map.of("pts", 25.0)));
        LockInReport r = service.compute(inputs(fx, Set.of(7), PTS, 0.0)).report();

        LockSummary m = manager(r);
        assertEquals(2, m.standIn().n());
        assertNotNull(m.standIn().median(), "n = min-locks");
        assertEquals(1, m.projection().n(), "only the snapshotted lock carries a projection margin");
        assertNull(m.projection().median(), "below min-locks: n printed, no median");
        assertEquals(1, r.totals().noSnapshot());
    }

    // ---- totals ----

    @Test
    void theClassCountsPartitionTheMultiGameTotal() {
        Fx fx = new Fx()
                .pts("pL", 1, 0, 20).pts("pL", 1, 1, 30).pts("pL", 1, 2, 25)          // credited 30: LOCK
                .pts("pR", 1, 0, 20).pts("pR", 1, 1, 30).pts("pR", 1, 2, 25)          // credited 25: RODE_OUT
                .pts("pM", 1, 0, 20).pts("pM", 1, 1, 30)                              // credited 0: RODE_OUT_MISSED
                .pts("pT", 1, 0, 20).pts("pT", 1, 1, 20).pts("pT", 1, 2, 30)          // credited 20: TIE_NON_LAST
                .pts("pW", 1, 0, 20).pts("pW", 1, 1, 30).pts("pW", 1, 2, 20)          // credited 20: TIE_WITH_LAST
                .pts("pU", 1, 0, 20).pts("pU", 1, 1, 30)                              // credited 44: UNMATCHED
                .pts("pS", 1, 0, 20)                                                  // single game
                .credit(1, new LinkedHashMap<>(Map.ofEntries(
                        Map.entry("pL", 30.0), Map.entry("pR", 25.0), Map.entry("pM", 0.0), Map.entry("pT", 20.0),
                        Map.entry("pW", 20.0), Map.entry("pU", 44.0), Map.entry("pS", 20.0), Map.entry("pN", 5.0))));
        LockInReport r = service.compute(inputs(fx, Set.of(1), PTS, 0.0)).report();
        LockInService.Totals t = r.totals();
        assertEquals(1, t.lock());
        assertEquals(1, t.rodeOut());
        assertEquals(1, t.rodeOutMissed());
        assertEquals(1, t.tieNonLast());
        assertEquals(1, t.tieWithLast());
        assertEquals(1, t.unmatched());
        assertEquals(1, t.singleGame());
        assertEquals(1, t.noGames());
        assertEquals(6, t.multiGame());
        assertEquals(t.multiGame(), t.lock() + t.rodeOut() + t.rodeOutMissed() + t.tieNonLast() + t.tieWithLast()
                + t.unmatched(), "the classes are disjoint and exhaustive");

        LockSummary m = manager(r);
        assertEquals(t.rodeOut(), m.rodeOut(), "the manager's counts sum to the totals");
        assertEquals(t.tieNonLast(), m.tieNonLast());
        assertEquals(List.of("pL", "pT"), r.players().stream().map(LockSummary::key).sorted().toList(),
                "players listed are those with a lock of any kind");
    }

    @Test
    void aMedianNeedsMinLocksAndNIsAlwaysPrinted() {
        LockSummary below = manager(service.compute(inputs(standInFixture(6), Set.of(7), PTS, 0.0)).report());
        assertEquals(1, below.standIn().n());
        assertNull(below.standIn().median());
        assertNull(below.standIn().q1());
        assertNull(below.standIn().q3());
        assertEquals("Roster One", below.name());
    }

    // ---- US2: one player's view, locked by two managers ----

    /** Five games of 10 in weeks 1-5 (single-game weeks), the stand-in history every player below shares. */
    private static Fx withHistory(Fx fx, String... pids) {
        for (String pid : pids) for (int i = 0; i < 5; i++) fx.pts(pid, i + 1, i, 10);
        return fx;
    }

    private static void creditRoster(Fx fx, int rosterId, int week, String pid, double value) {
        fx.weeks.add(new WeekBreakdown(week, rosterId, 0, "{\"" + pid + "\":" + value + "}", "[\"" + pid + "\"]"));
    }

    @Test
    void aPlayerLockedByTwoManagersShowsBothAndNoOneElse() {
        // p1 is locked by roster 1 in week 7 (15 of 15/5, +50%) and by roster 2 in week 8 (14 of 14/6, +40%); p2 only
        // by roster 1. Asking for p1 returns his summary and exactly his two locks, attributed.
        Fx fx = withHistory(new Fx(), "p1", "p2")
                .pts("p1", 7, 20, 15).pts("p1", 7, 21, 5).pts("p1", 8, 30, 14).pts("p1", 8, 31, 6)
                .pts("p2", 7, 20, 15).pts("p2", 7, 21, 5);
        fx.managers.put(2, 200L);
        creditRoster(fx, 1, 7, "p1", 15.0);
        creditRoster(fx, 2, 8, "p1", 14.0);
        creditRoster(fx, 1, 7, "p2", 15.0);
        Computed c = service.compute(inputs(fx, Set.of(7, 8), PTS, 0.0));

        LockInReport r = LockInService.view(c, "p1");
        assertEquals("MEASURED", r.status());
        assertTrue(r.managers().isEmpty(), "never the whole report (F14)");
        assertEquals(1, r.players().size());
        assertEquals("p1", r.players().get(0).key());
        assertEquals(2, r.players().get(0).standIn().n(), "the lock list's length equals n");
        assertEquals(2, r.locks().size());
        assertEquals(List.of(100L, 200L), r.locks().stream().map(Lock::managerId).sorted().toList());
        assertEquals(List.of(0.5, 0.4), r.locks().stream().sorted(java.util.Comparator.comparingInt(Lock::week))
                .map(l -> l.standIn().margin()).toList());
        assertTrue(r.locks().stream().allMatch(l -> l.playerId().equals("p1") && "Player p1".equals(l.player())));
        assertEquals(c.report().totals(), r.totals(), "the totals stay the league's");
        assertEquals(c.report().caveats(), r.caveats());

        LockInReport none = LockInService.view(c, "nobody");
        assertTrue(none.players().isEmpty());
        assertTrue(none.locks().isEmpty());
        assertEquals(2, LockInService.view(c, null).managers().size(), "no filter: the league report");
    }

    // ---- US3: the payoff grid ----

    @Test
    void thePayoffGridCountsEachCellAndWithholdsRatesBelowMinCell() {
        LockInService svc = new LockInService(null, null, null, null, null, null, null, null, new GameScoringService(),
                new LockInProperties(2, 2, 5, List.of(0.0, 0.10, 0.25, 0.50), 3, 4, 60, 15), trends);
        // Every lock is against a stand-in of exactly 10. Locked game, then the later games:
        //   p1 15 | 12, 20  +50%, 2 later, last 20 beat it, so did a later game
        //   p2 15 | 12, 14  +50%, 2 later, neither did
        //   p3 15 | 20, 12  +50%, 2 later, the last did not, a later game did
        //   p4 12 | 5      +20%, 1 later
        //   p5  8 | 12     -20%, 1 later
        Fx fx = withHistory(new Fx(), "p1", "p2", "p3", "p4", "p5")
                .pts("p1", 7, 20, 15).pts("p1", 7, 21, 12).pts("p1", 7, 22, 20)
                .pts("p2", 7, 20, 15).pts("p2", 7, 21, 12).pts("p2", 7, 22, 14)
                .pts("p3", 7, 20, 15).pts("p3", 7, 21, 20).pts("p3", 7, 22, 12)
                .pts("p4", 7, 20, 12).pts("p4", 7, 21, 5)
                .pts("p5", 7, 20, 8).pts("p5", 7, 21, 12)
                .credit(7, new LinkedHashMap<>(Map.of("p1", 15.0, "p2", 15.0, "p3", 15.0, "p4", 12.0, "p5", 8.0)));
        LockInReport r = svc.compute(inputs(fx, Set.of(7), PTS, 0.0)).report();

        List<LockInService.PayoffCell> grid = r.payoff().get("STAND_IN");
        assertEquals(15, grid.size(), "5 bands x games-left 1..3, every cell present");
        assertEquals(List.of("<0", "0-10", "10-25", "25-50", "50+"),
                grid.stream().map(LockInService.PayoffCell::band).distinct().toList());
        LockInService.PayoffCell fifty2 = cell(grid, "50+", 2);
        assertEquals(3, fifty2.n());
        assertEquals(1.0 / 3, fifty2.rateLast(), 1e-12, "riding it out beat the lock in p1 only");
        assertEquals(2.0 / 3, fifty2.rateAny(), 1e-12, "in hindsight a later game beat it for p1 and p3");
        LockInService.PayoffCell ten1 = cell(grid, "10-25", 1);
        assertEquals(1, ten1.n());
        assertNull(ten1.rateLast(), "n below min-cell shows the count, not a rate");
        assertNull(ten1.rateAny());
        assertEquals(1, cell(grid, "<0", 1).n());
        assertEquals(0, cell(grid, "25-50", 3).n());
        assertEquals(5, grid.stream().mapToInt(LockInService.PayoffCell::n).sum(), "every lock is in exactly one cell");
        assertTrue(r.payoff().get("PROJECTION").stream().allMatch(c -> c.n() == 0 && c.rateLast() == null),
                "no snapshots, so the projection grid is empty and never borrows stand-in locks");
    }

    private static LockInService.PayoffCell cell(List<LockInService.PayoffCell> grid, String band, int gamesLeft) {
        return grid.stream().filter(c -> c.band().equals(band) && c.gamesLeft() == gamesLeft).findFirst().orElseThrow();
    }

    @Test
    void theBandLabelsComeFromTheConfiguredEdges() {
        assertEquals(List.of("<0", "0-10", "10-25", "25-50", "50+"),
                LockInService.bandLabels(List.of(0.0, 0.10, 0.25, 0.50)));
        assertEquals(List.of("<5", "5-12.5", "12.5+"), LockInService.bandLabels(List.of(0.05, 0.125)));
    }

    // ---- US4: the threshold population ----

    @Test
    void theThresholdPopulationIsEveryGameOfAMultiGameWeekLockedOrNot() {
        // p1: five 10s, then week 7 = 15 then 5. Games 1: prior 5 -> b 10 -> +0.5. Game 2: prior 6 (sum 65) -> b 65/6.
        Computed c = service.compute(inputs(standInFixture(5), Set.of(7), PTS, 0.0));
        double[] m = c.pool().margins();
        assertEquals(2, m.length, "single-game weeks 1-5 are not candidates");
        assertEquals(0.5, m[0], 1e-12);
        assertEquals((5 - 65.0 / 6) / (65.0 / 6), m[1], 1e-12, "-0.538461538...");
        assertEquals(0, c.pool().rodeOut());
        assertEquals(0, c.pool().rodeOutMissed());
        // Hand fit, p = 0: V1 = (0.5 - 0.5384615)/2 = -0.0192308; V2 = (0.5 + V1... max(-0.538, V1) = V1) = (0.5 - 0.0192308)/2.
        double[] v = LockThresholds.fit(m, 0.0, 4);
        assertEquals(-0.0192307692, v[1], 1e-9);
        assertEquals(0.2403846154, v[2], 1e-9);
    }

    @Test
    void theThresholdPopulationSkipsGamesWithTooFewPriorOrNoStandIn() {
        // Only 3 earlier games: neither week-7 game has 5 prior games (the second has 4), so no candidate at all.
        assertEquals(0, service.compute(inputs(standInFixture(3), Set.of(7), PTS, 0.0)).pool().margins().length);
        // 4 earlier games: the first week-7 game has 4, the second 5 -> exactly one candidate (the second).
        assertEquals(1, service.compute(inputs(standInFixture(4), Set.of(7), PTS, 0.0)).pool().margins().length);
    }

    @Test
    void thePoolCountsRodeOutAndRodeOutMissedForP() {
        Fx fx = withHistory(new Fx(), "p1", "p2", "p3")
                .pts("p1", 7, 20, 15).pts("p1", 7, 21, 18)       // credited 18: rode out
                .pts("p2", 7, 20, 15).pts("p2", 7, 21, 18)       // credited 0 with no 0-point game: rode out, missed
                .pts("p3", 7, 20, 15).pts("p3", 7, 21, 18)       // credited 15: a lock, in neither count
                .credit(7, new LinkedHashMap<>(Map.of("p1", 18.0, "p2", 0.0, "p3", 15.0)));
        LockInService.Pool pool = service.compute(inputs(fx, Set.of(7), PTS, 0.0)).pool();
        assertEquals(1, pool.rodeOut());
        assertEquals(1, pool.rodeOutMissed());
        assertEquals(6, pool.margins().length, "all six week-7 games, whoever locked what");
    }
}
