package com.ballknowers.draftsim.engine;

import com.ballknowers.draftsim.store.PlayerProjectionRepository.ScoringKey;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The two rules in claude/league-analysis.md that are pure functions, pinned
 * without a database: ffwrapped's formula and the scoring-key derivation.
 *
 * The rest of {@link LeagueAnalysisService} is a walk over Sleeper's live
 * rosters and four repositories -- exercised by the live pass recorded in the
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
}
