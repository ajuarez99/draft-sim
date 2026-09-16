package com.ballknowers.draftsim.engine;

import com.ballknowers.draftsim.engine.LeagueAnalysisService.LineupPlayer;
import com.ballknowers.draftsim.engine.LeagueAnalysisService.Matchup;
import com.ballknowers.draftsim.engine.LeagueAnalysisService.RosterProjection;
import com.ballknowers.draftsim.store.LeagueMatchupRepository.Fixture;
import com.ballknowers.draftsim.store.PlayerProjectionRepository.ScoringKey;
import org.junit.jupiter.api.Test;

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
                "CIN", slot, points, null);
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
                Map.of(), Map.of(), List.of(starters), List.of(), List.of(), 0);
    }

    private static LineupPlayer starter(String id, double points) {
        return new LineupPlayer(id, id, "RB", "CIN", "RB", points, null);
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
                Map.of(), Map.of(), List.of(starter("a", 20.0)), List.of(), List.of(), 0);
        RosterProjection b = new RosterProjection(2, 2L, "b", null, 2, false, 20.0,
                Map.of(), Map.of(), List.of(starter("b", 20.0)), List.of(), List.of(), 0);
        RosterProjection c = new RosterProjection(3, 3L, "c", null, 3, false, 5.0,
                Map.of(), Map.of(), List.of(starter("c", 5.0)), List.of(), List.of(), 0);

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
                false, total, Map.of(), Map.of(), List.of(), List.of(), List.of(), 0);
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
}
