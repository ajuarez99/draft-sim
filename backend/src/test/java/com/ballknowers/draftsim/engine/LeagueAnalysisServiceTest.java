package com.ballknowers.draftsim.engine;

import com.ballknowers.draftsim.domain.Sport;
import com.ballknowers.draftsim.engine.LeagueAnalysisService.LineupPlayer;
import com.ballknowers.draftsim.engine.LeagueAnalysisService.Matchup;
import com.ballknowers.draftsim.engine.LeagueAnalysisService.RosterProjection;
import com.ballknowers.draftsim.store.LeagueMatchupRepository.Fixture;
import com.ballknowers.draftsim.store.PlayerProjectionRepository.ScoringKey;
import org.junit.jupiter.api.Test;

import com.ballknowers.draftsim.config.GradeProperties;
import com.ballknowers.draftsim.store.LeagueRepository;
import com.ballknowers.draftsim.store.RosterSeasonRepository;
import com.ballknowers.draftsim.store.RosterWeekPointsRepository;
import org.mockito.Mockito;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The rules in claude/league-analysis.md and its second pass
 * (claude/league-analysis-lineups-and-matchups.md) that are pure functions,
 * pinned without a database: ffwrapped's formula, the scoring-key derivation,
 * the lineup-card order and the matchup pairing.
 *
 * The rest of {@link LeagueAnalysisService} is a walk over Sleeper's live
 * rosters and five repositories -- exercised by the live pass recorded in the
 * brief rather than mocked into a shape that proves only that the mocks were
 * wired up.
 */
class LeagueAnalysisServiceTest {

    /**
     * Worked by hand from the formula as ffwrapped publishes it. If someone
     * later "improves" the weights, this is what should stop them doing it
     * silently -- the number is supposed to be theirs.
     */
    @Test
    void rawScoreIsFfwrappedsFormulaVerbatim() {
        // ((120 * 6) + ((150 + 90) * 2) + (0.5 * 400)) / 10
        //   = (720 + 480 + 200) / 10 = 140
        assertEquals(140.0, LeagueAnalysisService.rawScore(120, 150, 90, 0.5), 1e-9);
    }

    /** A winless team still scores for what it put up. */
    @Test
    void rawScoreRewardsScoringIndependentlyOfRecord() {
        double winless = LeagueAnalysisService.rawScore(130, 160, 100, 0.0);
        double alsoWinless = LeagueAnalysisService.rawScore(100, 120, 80, 0.0);
        assertTrue(winless > alsoWinless,
                "the higher-scoring winless team should still rank above the lower-scoring one");
    }

    /**
     * What the formula actually trades record against, measured rather than
     * assumed: {@code winPct} spans 0 to 1 and is divided by 10 after being
     * multiplied by 400, so a perfect record is worth exactly 40 raw points
     * over a winless one, whatever the team scored.
     */
    @Test
    void aPerfectRecordIsWorthExactlyFortyRawPoints() {
        double winless = LeagueAnalysisService.rawScore(120, 150, 90, 0.0);
        double undefeated = LeagueAnalysisService.rawScore(120, 150, 90, 1.0);
        assertEquals(40.0, undefeated - winless, 1e-9);
    }

    /**
     * And what that is worth in scoring: 40 raw points is the same 40 points
     * per week of average scoring (0.6 per point of average, 0.2 each per
     * point of high and low). These two teams are genuinely tied -- an
     * undefeated team scoring 100 a week and a winless team scoring 140 come
     * out at exactly the same number, which is the formula's central claim
     * rather than a rounding artefact.
     */
    @Test
    void fortyPointsAWeekOfScoringIsWorthAPerfectRecord() {
        double undefeatedLowScorer = LeagueAnalysisService.rawScore(100, 120, 80, 1.0);
        double winlessHighScorer = LeagueAnalysisService.rawScore(140, 170, 110, 0.0);
        assertEquals(undefeatedLowScorer, winlessHighScorer, 1e-9);

        // One more point of average scoring breaks the tie the winless way.
        assertTrue(LeagueAnalysisService.rawScore(141, 170, 110, 0.0) > undefeatedLowScorer);
    }

    /**
     * The derivation exists so that a league setting is read rather than
     * assumed. Full PPR is what the real league plays; the other two are what
     * makes this a lookup instead of a constant.
     */
    @Test
    void scoringKeyFollowsTheLeaguesOwnReceptionPoints() {
        assertEquals(ScoringKey.PPR, ScoringKey.forReceptionPoints(1.0));
        assertEquals(ScoringKey.HALF_PPR, ScoringKey.forReceptionPoints(0.5));
        assertEquals(ScoringKey.STANDARD, ScoringKey.forReceptionPoints(0.0));
    }

    /** A league that pays more than a point per catch is still PPR, not unknown. */
    @Test
    void scoringKeyDoesNotFallOffTheTopEnd() {
        assertEquals(ScoringKey.PPR, ScoringKey.forReceptionPoints(1.5));
    }

    /** Each key names a real column on player_projection. */
    @Test
    void everyScoringKeyMapsToItsOwnColumn() {
        assertEquals("pts_ppr", ScoringKey.PPR.column());
        assertEquals("pts_half_ppr", ScoringKey.HALF_PPR.column());
        assertEquals("pts_std", ScoringKey.STANDARD.column());
    }

    /** The gate piece 1 refuses below. One scored week cannot feed this formula. */
    @Test
    void theRankingScoreNeedsMoreThanOneWeek() {
        assertTrue(LeagueAnalysisService.MIN_SCORED_WEEKS > 1);
    }

    // ---- the lineup card (claude/league-analysis-lineups-and-matchups.md piece 1) ----

    /** (Foot) Ball Knowers' own roster_positions, which is where the order comes from. */
    private static final List<String> SLOTS =
            List.of("QB", "RB", "RB", "WR", "WR", "TE", "FLEX", "FLEX", "K", "DEF",
                    "BN", "BN", "BN", "BN", "BN");

    private static LineupPlayer at(String slot, double points) {
        return new LineupPlayer("id-" + slot + "-" + points, slot + " " + points, "WR",
                "CIN", slot, points, null, false);
    }

    private static List<String> slotsOf(List<LineupPlayer> players) {
        List<LineupPlayer> sorted = new ArrayList<>(players);
        sorted.sort(LeagueAnalysisService.byLineupCard(SLOTS));
        return sorted.stream().map(LineupPlayer::slot).toList();
    }

    /**
     * The whole point of the change: a lineup card is in slot order even when
     * that puts a 12-point quarterback above a 23-point running back. The old
     * points-descending sort produced exactly the reversed list below.
     */
    @Test
    void theLineupCardIsInTheLeaguesSlotOrderNotPointsOrder() {
        assertEquals(List.of("QB", "RB", "WR", "TE", "FLEX", "K", "DEF"),
                slotsOf(List.of(at("DEF", 8.0), at("K", 9.0), at("FLEX", 11.0), at("TE", 14.0),
                        at("WR", 19.0), at("RB", 23.0), at("QB", 12.0))));
    }

    /** Within one slot name, the better projection is the one listed first. */
    @Test
    void twoPlayersInTheSameSlotAreOrderedByPoints() {
        List<LineupPlayer> sorted = new ArrayList<>(List.of(at("RB", 9.4), at("RB", 17.2)));
        sorted.sort(LeagueAnalysisService.byLineupCard(SLOTS));
        assertEquals(List.of(17.2, 9.4), sorted.stream().map(LineupPlayer::points).toList());
    }

    /**
     * A slot this app has never heard of sorts to the end, never to the front.
     * {@code indexOf} answers -1 for it, and -1 sorts BEFORE the quarterback
     * unless it is mapped -- which is the whole reason the comparator does not
     * use the raw index.
     */
    @Test
    void anUnknownSlotSortsToTheEndRatherThanAheadOfTheQuarterback() {
        assertEquals(List.of("QB", "SUPER_FLEX"),
                slotsOf(List.of(at("SUPER_FLEX", 30.0), at("QB", 4.0))));
    }

    // ---- the weekly strip (claude/league-analysis-week-by-week.md) ----

    private static RosterProjection withStarters(double total, LineupPlayer... starters) {
        return new RosterProjection(1, 1L, "kieriskash", null, 1, false, total,
                Map.of(), Map.of(), List.of(starters), List.of(), List.of(), 0, 0);
    }

    private static LineupPlayer starter(String id, double points) {
        return new LineupPlayer(id, id, "RB", "CIN", "RB", points, null, false);
    }

    /**
     * The invariant the first cut broke: the weeks under a bar must sum to the
     * bar. It re-optimised the lineup each week, which scores higher than one
     * lineup locked for the season -- always, and by 93 points for a real
     * roster -- so the strip and the total it sat under disagreed with nothing
     * saying why. This values the bar's OWN lineup week by week.
     */
    @Test
    void theWeeklyStripSumsToTheBarItSitsUnder() {
        RosterProjection roster = withStarters(30.0, starter("a", 20.0), starter("b", 10.0));

        List<RosterProjection> out = LeagueAnalysisService.withWeekly(List.of(roster), Map.of(
                2, Map.of("a", 12.0, "b", 4.0),
                3, Map.of("a", 8.0, "b", 6.0)));

        List<LeagueAnalysisService.WeekTotal> weeks = out.getFirst().byWeek();
        assertEquals(List.of(2, 3), weeks.stream().map(LeagueAnalysisService.WeekTotal::week).toList());
        assertEquals(30.0, weeks.stream().mapToDouble(LeagueAnalysisService.WeekTotal::points).sum(), 1e-9);
    }

    /**
     * The rank is a fact about the WEEK, read down the column -- and it ties
     * through Ranker like every other rank on this page, so two rosters
     * projected level in a week are both 1st and nobody is 2nd.
     */
    @Test
    void eachWeekIsRankedAcrossRostersAndTiesShareAPlace() {
        RosterProjection a = new RosterProjection(1, 1L, "a", null, 1, false, 20.0,
                Map.of(), Map.of(), List.of(starter("a", 20.0)), List.of(), List.of(), 0, 0);
        RosterProjection b = new RosterProjection(2, 2L, "b", null, 2, false, 20.0,
                Map.of(), Map.of(), List.of(starter("b", 20.0)), List.of(), List.of(), 0, 0);
        RosterProjection c = new RosterProjection(3, 3L, "c", null, 3, false, 5.0,
                Map.of(), Map.of(), List.of(starter("c", 5.0)), List.of(), List.of(), 0, 0);

        List<RosterProjection> out = LeagueAnalysisService.withWeekly(List.of(a, b, c), Map.of(
                2, Map.of("a", 9.0, "b", 9.0, "c", 4.0)));

        assertEquals(1, out.get(0).byWeek().getFirst().rank());
        assertEquals(1, out.get(1).byWeek().getFirst().rank());
        assertEquals(3, out.get(2).byWeek().getFirst().rank());
    }

    /**
     * A bye is a hole in the week, not a missing entry: the week is still
     * listed, at whatever the rest of the lineup scores. Dropping it would hide
     * the dip that is the entire reason to draw the strip.
     */
    @Test
    void aByeWeekIsADipRatherThanAGapInTheSeries() {
        RosterProjection roster = withStarters(24.0, starter("a", 14.0), starter("b", 10.0));

        List<RosterProjection> out = LeagueAnalysisService.withWeekly(List.of(roster), Map.of(
                2, Map.of("a", 14.0, "b", 10.0),
                3, Map.of("b", 10.0)));

        assertEquals(List.of(24.0, 10.0),
                out.getFirst().byWeek().stream().map(LeagueAnalysisService.WeekTotal::points).toList());
    }

    /** Weeks come out in week order whatever order the query handed them over in. */
    @Test
    void theSeriesIsInWeekOrder() {
        RosterProjection roster = withStarters(3.0, starter("a", 3.0));

        List<RosterProjection> out = LeagueAnalysisService.withWeekly(List.of(roster), Map.of(
                9, Map.of("a", 1.0), 4, Map.of("a", 1.0), 11, Map.of("a", 1.0)));

        assertEquals(List.of(4, 9, 11),
                out.getFirst().byWeek().stream().map(LeagueAnalysisService.WeekTotal::week).toList());
    }

    // ---- the matchup pairing (piece 3) ----

    private static RosterProjection roster(int rosterId, double total) {
        return new RosterProjection(rosterId, (long) rosterId, "manager " + rosterId, null, 0,
                false, total, Map.of(), Map.of(), List.of(), List.of(), List.of(), 0, 0);
    }

    private static Map<Integer, RosterProjection> valued(RosterProjection... rosters) {
        Map<Integer, RosterProjection> byRoster = new HashMap<>();
        for (RosterProjection r : rosters) byRoster.put(r.rosterId(), r);
        return byRoster;
    }

    /**
     * Sleeper groups two rosters under one matchup_id and never says which is
     * home, so the pairing is by that key alone and the sides come out
     * best-projected first.
     */
    @Test
    void rostersSharingAMatchupIdBecomeTheTwoSidesOfOneGame() {
        List<Matchup> games = LeagueAnalysisService.pair(
                List.of(new Fixture(2, 1, 7), new Fixture(2, 2, 7)),
                valued(roster(1, 101.5), roster(2, 133.0)));

        assertEquals(1, games.size());
        assertEquals(7, games.getFirst().matchupId());
        assertEquals(List.of(2, 1), games.getFirst().sides().stream()
                .map(LeagueAnalysisService.Side::rosterId).toList());
    }

    /**
     * A bye -- an odd team count, or a week Sleeper has half-published -- is a
     * group of one, and is carried rather than dropped. A manager with no game
     * next week needs telling; a missing card tells them nothing.
     */
    @Test
    void aMatchupWithOneRosterInItIsAByeAndSurvives() {
        List<Matchup> games = LeagueAnalysisService.pair(
                List.of(new Fixture(2, 5, 3)), valued(roster(5, 118.2)));

        assertEquals(1, games.size());
        assertEquals(1, games.getFirst().sides().size());
    }

    /** Heaviest combined projection first, because matchup_id itself means nothing. */
    @Test
    void gamesAreOrderedByHowBigTheyAre() {
        List<Matchup> games = LeagueAnalysisService.pair(
                List.of(new Fixture(2, 1, 1), new Fixture(2, 2, 1),
                        new Fixture(2, 3, 2), new Fixture(2, 4, 2)),
                valued(roster(1, 90.0), roster(2, 95.0), roster(3, 140.0), roster(4, 130.0)));

        assertEquals(List.of(2, 1), games.stream().map(Matchup::matchupId).toList());
    }

    /**
     * A fixture naming a roster nothing valued is dropped, not shown at zero.
     * That state means Sleeper's roster list and the stored schedule disagree,
     * and "projected to score nothing" is a claim where an absent row is an
     * absence.
     */
    @Test
    void aFixtureWithNoValuedRosterIsDroppedRatherThanShownAtZero() {
        List<Matchup> games = LeagueAnalysisService.pair(
                List.of(new Fixture(2, 1, 4), new Fixture(2, 99, 4)),
                valued(roster(1, 112.0)));

        assertEquals(1, games.getFirst().sides().size());
        assertEquals(1, games.getFirst().sides().getFirst().rosterId());
    }

    // ---- spec 013 T075/T076: letter grades on the ranking scores

    private static final GradeProperties LADDER = new GradeProperties(List.of(
            new GradeProperties.Cutoff(25, "A"), new GradeProperties.Cutoff(75, "C"),
            new GradeProperties.Cutoff(100, "F")));

    private static LeagueAnalysisService service(RosterSeasonRepository rs, RosterWeekPointsRepository wp,
                                                 GradeProperties grades) {
        return new LeagueAnalysisService(null, rs, wp, null, null, null, null, null, null, null,
                new LetterGrades(grades), null, null, new com.ballknowers.draftsim.config.PreseasonProperties(3.0, 3), null, false);
    }

    private static LeagueAnalysisService.ScoreEntry raw(int rosterId, double rawScore) {
        return new LeagueAnalysisService.ScoreEntry(0, rosterId, null, "m" + rosterId, null, 0, rawScore,
                rawScore, rawScore, rawScore, 0.5, 1, 1, 0, null);
    }

    @Test
    void gradeOrderFollowsRankAndTiesShareAGrade() {
        var svc = service(null, null, LADDER);
        var ranked = svc.normalise(List.of(raw(1, 100), raw(2, 90), raw(3, 90), raw(4, 50), raw(5, 10)));
        assertEquals("A", ranked.get(0).grade());
        assertEquals(ranked.get(1).rank(), ranked.get(2).rank());
        assertEquals(ranked.get(1).grade(), ranked.get(2).grade(), "tied ranks share a grade");
        assertEquals("F", ranked.get(4).grade());
        List<String> order = List.of("A", "C", "F");
        for (int i = 1; i < ranked.size(); i++) {
            assertTrue(order.indexOf(ranked.get(i).grade()) >= order.indexOf(ranked.get(i - 1).grade()));
        }
    }

    @Test
    void noGradeConfigMeansNullGrades() {
        var ranked = service(null, null, new GradeProperties(null)).normalise(List.of(raw(1, 100), raw(2, 10)));
        assertNull(ranked.get(0).grade());
        assertNull(ranked.get(1).grade());
    }

    @Test
    void gradesAreEarlyAtThreeWeeksAndNotAtFour() {
        var svc = service(Mockito.mock(RosterSeasonRepository.class), Mockito.mock(RosterWeekPointsRepository.class), LADDER);
        var league = Mockito.mock(LeagueRepository.LeagueRow.class);
        assertTrue(svc.rankingScores(league, 2, 2).gradesEarly(), "unavailable shape still states it");
        assertTrue(svc.rankingScores(league, 3, 3).gradesEarly());
        assertFalse(svc.rankingScores(league, 4, 4).gradesEarly());
    }

    /**
     * specs/016: the reason a basketball league gets in place of roster
     * projections. It used to say Sleeper's projections have "no nba
     * equivalent" -- false: Sleeper serves per-game NBA projections, measured
     * 2026-10-05 (claude/projection-tools.md, research R0). The reason now says
     * only what is true here -- not built yet -- and asserts nothing about
     * whether a source exists, which would go stale or be unmeasured.
     */
    @Test
    void basketballIsToldProjectionsArentBuiltYetNotThatNoSourceExists() {
        String reason = LeagueAnalysisService.projectionsNotBuiltReason(Sport.NBA);
        assertEquals("Roster projections aren't built for nba leagues yet: "
                + "so far this app only has football projections.", reason);
        assertTrue(reason.contains("aren't built"));
        assertTrue(reason.contains("nba"));
        assertFalse(reason.contains("equivalent"), reason);
        assertFalse(reason.contains("/api/ingest"), reason);
        assertFalse(reason.contains("POST /api/"), reason);
    }

    // ---- spec 030: the basketball path (T023, T024) ----

    private static final com.ballknowers.draftsim.config.ScoringProperties NBA_PROPS =
            new com.ballknowers.draftsim.config.ScoringProperties(null,
                    new com.ballknowers.draftsim.config.ScoringProperties.SportScoring(
                            new com.ballknowers.draftsim.config.ScoringProperties.Weights(1.0, 0.35, 0.5, 0.25),
                            12.0, 3.0, 60.0, 0.15, 6, 0.85, Map.of(), 1.0, 30));
    private static final com.ballknowers.draftsim.sport.SportRules NBA_RULES =
            new com.ballknowers.draftsim.sport.BasketballRules(NBA_PROPS);
    private static final List<String> NBA_SLOT_LIST = List.of(
            "PG", "SG", "G", "SF", "PF", "F", "C", "UTIL", "UTIL", "BN", "BN", "BN");
    private static final com.ballknowers.draftsim.domain.LeagueSettings NBA_SETTINGS =
            new com.ballknowers.draftsim.domain.LeagueSettings(Sport.NBA, 4, 14, NBA_SLOT_LIST, 0.0);
    private static final List<String> NBA_GROUPS = List.of("PG", "SG", "SF", "PF", "C");

    private static final com.ballknowers.draftsim.domain.Position[] FIVE = {
            com.ballknowers.draftsim.domain.Position.PG, com.ballknowers.draftsim.domain.Position.SG,
            com.ballknowers.draftsim.domain.Position.SF, com.ballknowers.draftsim.domain.Position.PF,
            com.ballknowers.draftsim.domain.Position.C};

    /** Ten players per roster, two at each position: ids "r{roster}p{0..9}", position FIVE[i / 2]. */
    private static Map<String, com.ballknowers.draftsim.domain.Player> nbaPlayers(int rosters) {
        return nbaPlayers(rosters, Map.of());
    }

    /** As above, with Sleeper injury tags ({@code injury_status}) set on the named players. */
    private static Map<String, com.ballknowers.draftsim.domain.Player> nbaPlayers(int rosters,
                                                                                  Map<String, String> tags) {
        Map<String, com.ballknowers.draftsim.domain.Player> out = new HashMap<>();
        for (int r = 1; r <= rosters; r++) {
            for (int i = 0; i < 10; i++) {
                String id = "r" + r + "p" + i;
                out.put(id, new com.ballknowers.draftsim.domain.Player(id.hashCode(), Sport.NBA, id, id,
                        List.of(FIVE[i / 2]), "ATL", "Active", tags.get(id), null, null));
            }
        }
        return out;
    }

    private static List<String> rosterIds(int r, int... skip) {
        List<String> out = new ArrayList<>();
        outer:
        for (int i = 0; i < 10; i++) {
            for (int s : skip) if (s == i) continue outer;
            out.add("r" + r + "p" + i);
        }
        return out;
    }

    private static LeagueAnalysisService.RosterInput input(int r, List<String> players, List<String> reserve) {
        return new LeagueAnalysisService.RosterInput(r, "owner" + r, (long) r, "mgr" + r, null, players, reserve);
    }

    private static List<RosterProjection> rankNba(List<LeagueAnalysisService.RosterInput> inputs,
                                                   Map<String, Double> points) {
        return rankNba(inputs, points, Map.of());
    }

    private static List<RosterProjection> rankNba(List<LeagueAnalysisService.RosterInput> inputs,
                                                   Map<String, Double> points, Map<String, String> tags) {
        // Through the static core and rankAll with the REAL BasketballRules: the ordering, not the wiring.
        return LeagueAnalysisService.rankAll(LeagueAnalysisService.projectRosters(inputs, nbaPlayers(inputs.size(), tags),
                points, NBA_RULES, NBA_SETTINGS, NBA_GROUPS, NBA_SLOT_LIST, "owner4"), NBA_GROUPS);
    }

    /** A per-game line {@code {pts: p}} scored under {pts: 1.0} by the real GameScoringService. */
    private static Map<String, Double> pointsFor(Map<String, Double> pts) {
        GameScoringService scorer = new GameScoringService();
        Map<String, Double> out = new HashMap<>();
        pts.forEach((id, p) -> out.put(id, scorer.score(Map.of("pts", 1.0), Map.of("pts", p))));
        return out;
    }

    private static Map<String, Double> fourRosterPoints() {
        Map<String, Double> pts = new HashMap<>();
        double[] base = {0, 20, 22, 18, 16};
        for (int r = 1; r <= 4; r++) for (String id : rosterIds(r)) pts.put(id, base[r]);
        pts.put("r1p0", 50.0);   // the star on roster 1, a PG
        return pointsFor(pts);
    }

    /**
     * SC-004, AGENTS.md bug class 1: structure tests do not catch a sign inversion, only a test that asserts
     * preference ordering does. Moving the single best player from the top roster to the bottom one must
     * raise the bottom roster's total and rank, and lower the top one's.
     */
    @Test
    void sc004_movingTheBestPlayerFromRankOneToRankFourRaisesRankFourAndLowersRankOne() {
        Map<String, Double> points = fourRosterPoints();
        List<RosterProjection> before = rankNba(List.of(
                input(1, rosterIds(1), List.of()), input(2, rosterIds(2), List.of()),
                input(3, rosterIds(3), List.of()), input(4, rosterIds(4), List.of())), points);
        assertEquals(List.of(1, 2, 3, 4), before.stream().map(RosterProjection::rosterId).toList());
        RosterProjection topBefore = before.get(0);
        RosterProjection bottomBefore = before.get(3);
        assertEquals(210.0, topBefore.total(), 1e-9);
        assertEquals(144.0, bottomBefore.total(), 1e-9);

        List<String> r1After = new ArrayList<>(rosterIds(1, 0));
        List<String> r4After = new ArrayList<>(rosterIds(4));
        r4After.add("r1p0");
        Map<String, com.ballknowers.draftsim.domain.Player> players = nbaPlayers(4);
        List<RosterProjection> after = LeagueAnalysisService.rankAll(LeagueAnalysisService.projectRosters(List.of(
                input(1, r1After, List.of()), input(2, rosterIds(2), List.of()),
                input(3, rosterIds(3), List.of()), input(4, r4After, List.of())),
                players, points, NBA_RULES, NBA_SETTINGS, NBA_GROUPS, NBA_SLOT_LIST, "owner4"), NBA_GROUPS);

        RosterProjection topAfter = after.stream().filter(r -> r.rosterId() == 1).findFirst().orElseThrow();
        RosterProjection bottomAfter = after.stream().filter(r -> r.rosterId() == 4).findFirst().orElseThrow();
        assertTrue(bottomAfter.total() > bottomBefore.total(), "rank 4's roster must gain: " + bottomAfter.total());
        assertTrue(bottomAfter.rank() < bottomBefore.rank(), "rank 4's roster must climb");
        assertTrue(topAfter.total() < topBefore.total(), "rank 1's roster must lose: " + topAfter.total());
        assertEquals(180.0, topAfter.total(), 1e-9);
        assertEquals(178.0, bottomAfter.total(), 1e-9);
        assertTrue(bottomAfter.isMe(), "owner4 is the caller");
    }

    @Test
    void anStarterIsOneProjectedGameScoredUnderTheLeaguesScoring() {
        // A real-shaped line scored by hand under a league scoring: 31.88*0.5 + 7.71 + 8.17 + 1.59*2 + 0.48*2
        // - 3.77 + 3.76*0.5 + 0.56*2 + 0.14*3 = 15.94 + 7.71 + 8.17 + 3.18 + 0.96 - 3.77 + 1.88 + 1.12 + 0.42
        Map<String, Double> scoring = new HashMap<>(Map.of("pts", 0.5, "reb", 1.0, "ast", 1.0, "stl", 2.0,
                "blk", 2.0, "to", -1.0, "tpm", 0.5, "dd", 2.0, "td", 3.0));
        Map<String, Double> line = Map.of("pts", 31.88, "reb", 7.71, "ast", 8.17, "stl", 1.59, "blk", 0.48,
                "to", 3.77, "tpm", 3.76, "dd", 0.56, "td", 0.14);
        double byHand = 15.94 + 7.71 + 8.17 + 3.18 + 0.96 - 3.77 + 1.88 + 1.12 + 0.42;
        assertEquals(byHand, new GameScoringService().score(scoring, line), 0.01);

        Map<String, Double> points = new HashMap<>();
        for (String id : rosterIds(1)) points.put(id, 1.0);
        points.put("r1p0", new GameScoringService().score(scoring, line));
        RosterProjection r = rankNba(List.of(input(1, rosterIds(1), List.of()), input(2, rosterIds(2), List.of()),
                input(3, rosterIds(3), List.of()), input(4, rosterIds(4), List.of())), points).stream()
                .filter(x -> x.rosterId() == 1).findFirst().orElseThrow();
        LineupPlayer star = r.starters().stream().filter(s -> s.sleeperPlayerId().equals("r1p0")).findFirst().orElseThrow();
        assertEquals(byHand, star.points(), 0.06, "one game, rounded to a tenth, not multiplied by games in a week");
        assertTrue(r.byWeek().isEmpty(), "a flat per-week series would imply it was computed week by week");
    }

    private static RosterProjection roster1(List<RosterProjection> ranked) {
        return ranked.stream().filter(r -> r.rosterId() == 1).findFirst().orElseThrow();
    }

    private static List<LeagueAnalysisService.RosterInput> fourRosters(List<String> r1Reserve) {
        return List.of(input(1, rosterIds(1), r1Reserve), input(2, rosterIds(2), List.of()),
                input(3, rosterIds(3), List.of()), input(4, rosterIds(4), List.of()));
    }

    /**
     * Amended after the 2026-10-12 live check: this league has IR+, so the IR slot says nothing about
     * availability. A day-to-day star parked there is started at full value and flagged onReserve.
     */
    @Test
    void anIrPlusSlottedDayToDayStarIsStartedAndFlaggedOnReserve() {
        List<RosterProjection> ranked = rankNba(fourRosters(List.of("r1p0")), fourRosterPoints(),
                Map.of("r1p0", "DTD"));

        RosterProjection r1 = roster1(ranked);
        LineupPlayer star = r1.starters().stream().filter(s -> s.sleeperPlayerId().equals("r1p0"))
                .findFirst().orElseThrow();
        assertTrue(star.onReserve());
        assertEquals("DTD", star.injuryStatus());
        assertEquals(50.0, star.points(), 1e-9, "no discount: counted in full");
        assertEquals(0, r1.unavailable());
        assertEquals(210.0, r1.total(), 1e-9);
        assertTrue(r1.starters().stream().filter(s -> !s.sleeperPlayerId().equals("r1p0")).noneMatch(LineupPlayer::onReserve));
    }

    /** An Out tag excludes wherever he sits: here NOT in reserve. */
    @Test
    void anOutTaggedPlayerNotInReserveIsExcludedCountedAndBenchedAsOutAtZero() {
        List<RosterProjection> ranked = rankNba(fourRosters(List.of()), fourRosterPoints(),
                Map.of("r1p0", "Out"));

        RosterProjection r1 = roster1(ranked);
        assertEquals(1, r1.unavailable());
        assertTrue(r1.starters().stream().noneMatch(s -> s.sleeperPlayerId().equals("r1p0")));
        LineupPlayer out = r1.bench().stream().filter(b -> b.sleeperPlayerId().equals("r1p0")).findFirst().orElseThrow();
        assertEquals("OUT", out.slot());
        assertEquals(0.0, out.points(), 1e-9);
        assertFalse(out.onReserve());
        assertEquals(180.0, r1.total(), 1e-9, "nine remaining players at 20");
        assertEquals(0, r1.missing(), "left out on purpose, not missing a projection");
        assertEquals(0, ranked.stream().filter(r -> r.rosterId() != 1).mapToInt(RosterProjection::unavailable).sum());
    }

    @Test
    void anOutPlayerInReserveIsLikewiseExcluded() {
        RosterProjection r1 = roster1(rankNba(fourRosters(List.of("r1p0")), fourRosterPoints(),
                Map.of("r1p0", "Out")));

        assertEquals(1, r1.unavailable());
        assertTrue(r1.starters().stream().noneMatch(s -> s.sleeperPlayerId().equals("r1p0")));
        LineupPlayer out = r1.bench().stream().filter(b -> b.sleeperPlayerId().equals("r1p0")).findFirst().orElseThrow();
        assertEquals("OUT", out.slot());
        assertTrue(out.onReserve());
        assertEquals(180.0, r1.total(), 1e-9);
    }

    @Test
    void aSuspendedAndAnIrTaggedPlayerAreExcludedCaseInsensitively() {
        RosterProjection sus = roster1(rankNba(fourRosters(List.of()), fourRosterPoints(), Map.of("r1p0", "Sus")));
        assertEquals(1, sus.unavailable());
        assertEquals("OUT", sus.bench().stream().filter(b -> b.sleeperPlayerId().equals("r1p0")).findFirst().orElseThrow().slot());
        RosterProjection ir = roster1(rankNba(fourRosters(List.of()), fourRosterPoints(), Map.of("r1p0", "ir")));
        assertEquals(1, ir.unavailable());
        assertEquals(180.0, ir.total(), 1e-9);
    }

    @Test
    void anUntaggedPlayerInReserveCountsAndIsFlagged() {
        RosterProjection r1 = roster1(rankNba(fourRosters(List.of("r1p0")), fourRosterPoints()));

        assertEquals(0, r1.unavailable());
        LineupPlayer star = r1.starters().stream().filter(s -> s.sleeperPlayerId().equals("r1p0")).findFirst().orElseThrow();
        assertTrue(star.onReserve());
        assertEquals(210.0, r1.total(), 1e-9);
    }

    private record NbaMocks(com.ballknowers.draftsim.ingest.SleeperClient sleeper,
                            com.ballknowers.draftsim.store.PlayerSeasonProjectionRepository season,
                            LeagueAnalysisService svc, LeagueRepository.LeagueRow league,
                            LeagueRepository leagues) {}

    private static NbaMocks nbaService(List<Map<String, Object>> sleeperRosters,
                                       Map<String, Map<String, Double>> lines) {
        var leagues = Mockito.mock(LeagueRepository.class);
        var weekPoints = Mockito.mock(RosterWeekPointsRepository.class);
        var players = Mockito.mock(com.ballknowers.draftsim.store.PlayerRepository.class);
        var managers = Mockito.mock(com.ballknowers.draftsim.store.ManagerRepository.class);
        var rosterSeasons = Mockito.mock(RosterSeasonRepository.class);
        var sleeper = Mockito.mock(com.ballknowers.draftsim.ingest.SleeperClient.class);
        var season = Mockito.mock(com.ballknowers.draftsim.store.PlayerSeasonProjectionRepository.class);
        LeagueRepository.LeagueRow league = new LeagueRepository.LeagueRow(5L, Sport.NBA, "LNBA", "Ball Knowers",
                2026, 4, NBA_SLOT_LIST, 0.0, null, "in_season");
        Mockito.when(weekPoints.storedWeeks(5L)).thenReturn(java.util.Set.of());
        Mockito.when(leagues.playoffFormat(5L)).thenReturn(java.util.Optional.of(
                new LeagueRepository.PlayoffFormat(6, 20, 0, false, false, 0)));
        Mockito.when(leagues.scoringOf(5L)).thenReturn(Map.of("pts", 1.0, "ff", -2.0));
        Mockito.when(season.statsByPlayer("nba", 2026)).thenReturn(lines);
        Mockito.when(season.provenance("nba", 2026)).thenReturn(java.util.Optional.empty());
        Mockito.when(sleeper.rosters("LNBA")).thenReturn(sleeperRosters);
        Mockito.when(players.findAll(Sport.NBA)).thenReturn(new ArrayList<>(nbaPlayers(4, Map.of("r1p0", "Out")).values()));
        Mockito.when(managers.idsBySleeperUserId()).thenReturn(Map.of());
        Mockito.when(rosterSeasons.forLeague(5L)).thenReturn(List.of());
        var svc = new LeagueAnalysisService(leagues, rosterSeasons, weekPoints, null, players, managers, null,
                sleeper, new com.ballknowers.draftsim.sport.SportRulesRegistry(List.of(NBA_RULES)), null,
                new LetterGrades(LADDER), season, new GameScoringService(),
                new com.ballknowers.draftsim.config.PreseasonProperties(3.0, 3), null, false);
        return new NbaMocks(sleeper, season, svc, league, leagues);
    }

    private static Map<String, Object> sleeperRoster(int id, Object players, Object reserve) {
        Map<String, Object> m = new HashMap<>();
        m.put("roster_id", id);
        m.put("owner_id", "owner" + id);
        m.put("players", players);
        m.put("reserve", reserve);
        return m;
    }

    private static Map<String, Map<String, Double>> lines() {
        Map<String, Map<String, Double>> out = new HashMap<>();
        fourRosterPoints().forEach((id, p) -> out.put(id, Map.of("pts", p)));
        return out;
    }

    @Test
    void basketballThroughTheServiceRanksIsPerWeekAndListsOnlyScoredKeysNoLineCarries() {
        var m = nbaService(List.of(
                sleeperRoster(1, rosterIds(1), List.of("r1p0")), sleeperRoster(2, rosterIds(2), null),
                sleeperRoster(3, rosterIds(3), null), sleeperRoster(4, rosterIds(4), List.of())), lines());

        var read = m.svc().projectionsRead(m.league(), "owner4");
        var p = read.projections();

        assertTrue(p.available(), p.reason());
        assertNull(p.reasonCode());
        assertEquals("PER_WEEK", p.unit());
        assertEquals("ONE_AVERAGE_GAME_PER_STARTER", p.basisNote());
        assertEquals(List.of("ff"), p.unprojectedCategories(), "ff is scored but on no line; pts is on every line");
        assertEquals(3, p.staleAfterDays());
        assertTrue(p.rosters().stream().allMatch(r -> r.byWeek().isEmpty()));
        // Roster 1's star is tagged Out, so the order is 2, 1, 3, 4 (198, 180, 162, 144).
        assertEquals(List.of(2, 1, 3, 4), p.rosters().stream().map(RosterProjection::rosterId).toList());
        assertEquals(1, p.rosters().get(1).unavailable());
    }

    @Test
    void basketballWithNoStoredLinesIsUnavailableWithACodeAndNoZeros() {
        var m = nbaService(List.of(sleeperRoster(1, rosterIds(1), List.of())), Map.of());

        var p = m.svc().projectionsRead(m.league(), "owner4").projections();

        assertFalse(p.available());
        assertEquals("PROJECTIONS_NOT_LOADED", p.reasonCode());
        assertTrue(p.reason().contains("2026"), "an English sentence, not the code: " + p.reason());
        assertTrue(p.rosters().isEmpty());
        assertEquals("PER_WEEK", p.unit());
        assertEquals(List.of(), p.unprojectedCategories());
    }

    @Test
    void rostersThatAreAllEmptyOrNullAreRostersEmptyNotARankingOfZeros() {
        var m = nbaService(List.of(sleeperRoster(1, List.of(), null), sleeperRoster(2, null, null)), lines());

        var p = m.svc().projectionsRead(m.league(), "owner4").projections();

        assertFalse(p.available());
        assertEquals("ROSTERS_EMPTY", p.reasonCode());
        assertTrue(p.rosters().isEmpty());
    }

    @Test
    void aLeagueWithADifferentStartingLineupIsUnsupportedSlots() {
        var m = nbaService(List.of(sleeperRoster(1, rosterIds(1), List.of())), lines());
        LeagueRepository.LeagueRow odd = new LeagueRepository.LeagueRow(5L, Sport.NBA, "LNBA", "Ball Knowers",
                2026, 4, List.of("PG", "SG", "SF", "PF", "C", "UTIL", "BN"), 0.0, null, "in_season");

        var p = m.svc().projectionsRead(odd, "owner4").projections();

        assertFalse(p.available());
        assertEquals("UNSUPPORTED_SLOTS", p.reasonCode());
        assertTrue(p.reason().contains("PG, SG, SF, PF, C, UTIL"), p.reason());
    }

    @Test
    void anEmptyScoringMapIsScoringNotLoadedNotProjectionsNotLoaded() {
        var m = nbaService(List.of(sleeperRoster(1, rosterIds(1), List.of())), lines());
        Mockito.when(m.leagues().scoringOf(5L)).thenReturn(Map.of());

        var p = m.svc().projectionsRead(m.league(), "owner4").projections();

        assertFalse(p.available());
        assertEquals("SCORING_NOT_LOADED", p.reasonCode());
        assertTrue(p.reason().contains("scoring"), p.reason());
    }

    @Test
    void aZeroWeightScoringKeyIsNotListedAsNotProjected() {
        var m = nbaService(List.of(sleeperRoster(1, rosterIds(1), List.of()), sleeperRoster(2, rosterIds(2), null)), lines());
        Mockito.when(m.leagues().scoringOf(5L)).thenReturn(Map.of("pts", 1.0, "ff", 0.0, "tf", -2.0));

        var p = m.svc().projectionsRead(m.league(), "owner4").projections();

        assertTrue(p.available(), p.reason());
        assertEquals(List.of("tf"), p.unprojectedCategories());
    }

    @Test
    void theSameNineSlotsInAnotherOrderAreStillSupported() {
        var m = nbaService(List.of(sleeperRoster(1, rosterIds(1), List.of()), sleeperRoster(2, rosterIds(2), null)), lines());
        LeagueRepository.LeagueRow reordered = new LeagueRepository.LeagueRow(5L, Sport.NBA, "LNBA", "Ball Knowers",
                2026, 4, List.of("UTIL", "C", "PG", "SG", "G", "SF", "PF", "F", "UTIL", "BN", "BN"), 0.0, null, "in_season");

        var p = m.svc().projectionsRead(reordered, "owner4").projections();

        assertTrue(p.available(), p.reason());
    }

    @Test
    void basketballDoesNotNeedAPlayoffWeekStart() {
        var m = nbaService(List.of(sleeperRoster(1, rosterIds(1), List.of()), sleeperRoster(2, rosterIds(2), null)), lines());
        Mockito.when(m.leagues().playoffFormat(5L)).thenReturn(java.util.Optional.empty());

        var p = m.svc().projectionsRead(m.league(), "owner4").projections();

        assertTrue(p.available(), p.reason());
    }

    @Test
    void aCompleteBasketballLeagueIsSeasonOver() {
        var m = nbaService(List.of(sleeperRoster(1, rosterIds(1), List.of())), lines());
        LeagueRepository.LeagueRow done = new LeagueRepository.LeagueRow(5L, Sport.NBA, "LNBA", "Ball Knowers",
                2026, 4, NBA_SLOT_LIST, 0.0, null, "complete");

        var p = m.svc().projectionsRead(done, "owner4").projections();

        assertFalse(p.available());
        assertEquals("SEASON_OVER", p.reasonCode());
    }
}
