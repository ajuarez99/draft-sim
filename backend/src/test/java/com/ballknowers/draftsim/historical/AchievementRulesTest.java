package com.ballknowers.draftsim.historical;

import com.ballknowers.draftsim.historical.AchievementRules.BadgeEarn;
import com.ballknowers.draftsim.historical.AchievementRules.CareerTotals;
import com.ballknowers.draftsim.historical.AchievementRules.GameAward;
import com.ballknowers.draftsim.historical.AchievementRules.GameFacts;
import com.ballknowers.draftsim.historical.AchievementRules.Moment;
import com.ballknowers.draftsim.historical.AchievementRules.PickFact;
import com.ballknowers.draftsim.historical.AchievementRules.PickFacts;
import com.ballknowers.draftsim.historical.HistoricalResult.Bust;
import com.ballknowers.draftsim.historical.HistoricalResult.Format;
import com.ballknowers.draftsim.historical.HistoricalResult.Hindsight;
import com.ballknowers.draftsim.historical.HistoricalResult.PlayoffMatchup;
import com.ballknowers.draftsim.historical.HistoricalResult.Playoffs;
import com.ballknowers.draftsim.historical.HistoricalResult.Round;
import com.ballknowers.draftsim.historical.HistoricalResult.Seed;
import com.ballknowers.draftsim.historical.HistoricalResult.Standing;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Spec 064: every rule pinned in BOTH directions, because a well-formed rule can point backwards (lesson class 1).
 * Teams = 12, rounds = 14, so one round is 12 picks, 168 players are drafted, and the user is slot 1 unless a
 * test says otherwise. Rank 1 = best season value, so a smaller rank is better.
 */
class AchievementRulesTest {

    private static final int TEAMS = 12, ROUNDS = 14, ME = 1;

    // ---- builders -------------------------------------------------------------------------------------

    private static List<Standing> standings(int wins, int losses, int ties, int myPlace) {
        List<Standing> s = new ArrayList<>();
        s.add(new Standing(ME, wins, losses, ties, 5000, myPlace));
        for (int slot = 2; slot <= TEAMS; slot++) s.add(new Standing(slot, 7, 7, 0, 4000, slot));
        return s;
    }

    private static Format format() {
        return new Format(2, 19, 14, 14, List.of(15, 16, 17), 2, false);
    }

    private static Hindsight hindsight(boolean userBeatIt) {
        return new Hindsight("greedy hindsight, bots unchanged", userBeatIt ? 6000 : 7000, userBeatIt ? 7000 : 6000,
                userBeatIt, List.of());
    }

    private static Bust bust(int pickNo, int rank) {
        return new Bust(pickNo, HistoricalTestSupport.card(pickNo), rank, 100);
    }

    private static PlayoffMatchup win(int highSlot, int lowSlot, int winner) {
        return new PlayoffMatchup(1, 2, highSlot, lowSlot, 100, 90, winner, false);
    }

    /** A new-format result with no playoffs and a plain 8-6 record unless overridden. */
    private static HistoricalResult result(List<Standing> standings, int finish, Bust bust, Hindsight h,
                                           Format fmt, Playoffs po, String outcome) {
        return new HistoricalResult(List.of(), standings, finish, null, h, bust, null, fmt, po, null, outcome, null, null);
    }

    private static HistoricalResult plain() {
        return result(standings(8, 6, 0, 5), 5, bust(70, 150), hindsight(false), format(), null,
                HistoricalResult.MISSED_PLAYOFFS);
    }

    private static GameFacts facts(HistoricalResult r, PickFact... picks) {
        return new GameFacts("NBA", TEAMS, ROUNDS, ME, r,
                picks.length == 0 ? null : new PickFacts(19, List.of(picks)));
    }

    private static PickFact pick(int pickNo, int boardRank, int valueRank, Integer lastWeek) {
        int round = (pickNo - 1) / TEAMS + 1;
        return new PickFact(pickNo, round, 1000 + pickNo, "Player " + pickNo, boardRank, valueRank, 100.0, lastWeek);
    }

    private static Set<Badge> badgesOf(GameAward a) {
        return a.badges().stream().map(BadgeEarn::badge).collect(Collectors.toSet());
    }

    private static Set<String> momentCodes(GameAward a) {
        return a.moments().stream().map(Moment::code).collect(Collectors.toSet());
    }

    private static Playoffs bracket(List<Round> rounds, int champion) {
        List<Seed> seeds = new ArrayList<>();
        for (int i = 1; i <= 6; i++) seeds.add(new Seed(i, i));   // slot 1 is the top seed
        return new Playoffs(seeds, rounds, champion);
    }

    // ---- XP lines -------------------------------------------------------------------------------------

    @Test
    void aChampionsLinesSumToTheXpAndCountEveryWin() {
        Playoffs po = bracket(List.of(
                new Round(1, 15, "SEMIFINAL", List.of(), List.of(win(1, 4, 1))),
                new Round(2, 16, "FINAL", List.of(), List.of(win(1, 2, 1)))), 1);
        GameAward a = AchievementRules.evaluate(facts(result(standings(12, 2, 0, 1), 1, bust(70, 150),
                hindsight(false), format(), po, HistoricalResult.CHAMPION)));
        // 100 game + 12 regular wins * 10 + 2 playoff wins * 50 + 250 title
        assertEquals(100 + 120 + 100 + 250, a.xp());
        assertEquals(a.xp(), a.lines().stream().mapToInt(AchievementRules.XpLine::xp).sum(), "lines sum to xp");
        assertEquals(14, a.weeklyWins(), "12 regular-season wins plus 2 playoff wins");
        assertEquals(2, a.playoffWins());
        assertTrue(a.title());
        assertTrue(badgesOf(a).containsAll(Set.of(Badge.CHAMPION, Badge.PLAYOFFS, Badge.PLAYOFF_WIN, Badge.TOP_SEED)));
    }

    @Test
    void aLossInThePlayoffsDoesNotCountAsAWin() {
        Playoffs po = bracket(List.of(new Round(1, 15, "SEMIFINAL", List.of(), List.of(win(1, 4, 4)))), 4);
        GameAward a = AchievementRules.evaluate(facts(result(standings(10, 4, 0, 1), 3, bust(70, 150),
                hindsight(false), format(), po, HistoricalResult.LOST_SEMIFINAL)));
        assertEquals(0, a.playoffWins());
        assertEquals(10, a.weeklyWins());
        assertFalse(a.title());
        assertTrue(badgesOf(a).contains(Badge.PLAYOFFS), "he was a seed");
        assertFalse(badgesOf(a).contains(Badge.PLAYOFF_WIN));
        assertFalse(badgesOf(a).contains(Badge.CHAMPION));
    }

    // ---- amendment A8 and FR-008 ---------------------------------------------------------------------

    @Test
    void firstPlaceInASeasonWithNoPlayoffsIsTheBestRecordAndNotATitle() {
        GameAward a = AchievementRules.evaluate(facts(result(standings(10, 4, 0, 1), 1, bust(70, 150),
                hindsight(false), new Format(2, 12, 14, 12, List.of(), 0, true), null,
                HistoricalResult.NO_PLAYOFFS)));
        assertFalse(a.title());
        assertTrue(badgesOf(a).contains(Badge.TOP_SEED));
        assertFalse(badgesOf(a).contains(Badge.CHAMPION));
        assertFalse(badgesOf(a).contains(Badge.PLAYOFFS), "no bracket, so he did not reach one");
        assertTrue(a.lines().stream().noneMatch(l -> l.label().equals("Title")));
        assertEquals(100 + 100, a.xp());
    }

    @Test
    void anOldFormatResultEarnsWeeklyWinXpAndNoPlayoffBadges() throws IOException {
        ObjectMapper om = new ObjectMapper().configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);
        HistoricalResult old = om.readValue(getClass().getResourceAsStream("/historical/result-062.json"),
                HistoricalResult.class);
        assertNull(old.format(), "the fixture is the pre-063 shape");

        GameAward a = AchievementRules.evaluate(new GameFacts("NBA", TEAMS, ROUNDS, 5, old, null));
        // slot 5 won 7 in the fixture: 100 + 7 * 10, labelled "wins" not "regular-season wins"
        assertEquals(170, a.xp());
        assertEquals(7, a.weeklyWins());
        assertEquals(0, a.playoffWins());
        assertFalse(a.title());
        assertEquals("7 wins", a.lines().get(1).label());
        assertEquals(a.xp(), a.lines().stream().mapToInt(AchievementRules.XpLine::xp).sum());
        Set<Badge> earned = badgesOf(a);
        assertFalse(earned.contains(Badge.CHAMPION));
        assertFalse(earned.contains(Badge.PLAYOFFS));
        assertFalse(earned.contains(Badge.PLAYOFF_WIN));
        assertFalse(earned.contains(Badge.TOP_SEED), "slot 5 was 11th; slot 7 held first place");
        assertTrue(a.fromSavedResult());
        assertTrue(a.moments().isEmpty());
        assertFalse(earned.contains(Badge.BIG_BUST), "his biggest bust was a round-6 pick, outside rounds 1 and 2");
    }

    @Test
    void aFirstPlaceInAnOldResultIsNotATitle() {
        HistoricalResult old = new HistoricalResult(List.of(), standings(14, 8, 0, 1), 1, null, hindsight(false),
                bust(70, 150), null, null, null, null, null, null, null);
        GameAward a = AchievementRules.evaluate(facts(old));
        assertFalse(a.title());
        assertFalse(badgesOf(a).contains(Badge.CHAMPION));
        assertTrue(badgesOf(a).contains(Badge.TOP_SEED));
    }

    // ---- stored badges: one direction each ---------------------------------------------------------

    @Test
    void aPerfectSeasonNeedsTenWeeksAndNoLossesOrTies() {
        assertTrue(badgesOf(AchievementRules.evaluate(facts(result(standings(14, 0, 0, 1), 1, bust(70, 150),
                hindsight(false), format(), null, HistoricalResult.NO_PLAYOFFS)))).contains(Badge.PERFECT_SEASON));
        assertFalse(badgesOf(AchievementRules.evaluate(facts(result(standings(13, 0, 1, 1), 1, bust(70, 150),
                hindsight(false), format(), null, HistoricalResult.NO_PLAYOFFS)))).contains(Badge.PERFECT_SEASON),
                "a tie spoils it");
        assertFalse(badgesOf(AchievementRules.evaluate(facts(result(standings(9, 0, 0, 1), 1, bust(70, 150),
                hindsight(false), format(), null, HistoricalResult.NO_PLAYOFFS)))).contains(Badge.PERFECT_SEASON),
                "9 weeks is too short to mean anything");
    }

    @Test
    void beatingHindsightNeedsTheUserToHaveScoredMore() {
        assertTrue(badgesOf(AchievementRules.evaluate(facts(result(standings(8, 6, 0, 5), 5, bust(70, 150),
                hindsight(true), format(), null, HistoricalResult.MISSED_PLAYOFFS)))).contains(Badge.BEAT_HINDSIGHT));
        assertFalse(badgesOf(AchievementRules.evaluate(facts(plain()))).contains(Badge.BEAT_HINDSIGHT));
    }

    @Test
    void aCleanSheetNeedsNoBustAtAll() {
        HistoricalResult noBust = result(standings(8, 6, 0, 5), 5, null, hindsight(false), format(), null,
                HistoricalResult.MISSED_PLAYOFFS);
        assertTrue(badgesOf(AchievementRules.evaluate(facts(noBust))).contains(Badge.CLEAN_SHEET));
        assertFalse(badgesOf(AchievementRules.evaluate(facts(plain()))).contains(Badge.CLEAN_SHEET));
    }

    @Test
    void theWoodenSpoonIsLastPlaceAndSecondToLastIsNot() {
        assertTrue(badgesOf(AchievementRules.evaluate(facts(result(standings(2, 12, 0, 12), 12, bust(70, 150),
                hindsight(false), format(), null, HistoricalResult.MISSED_PLAYOFFS)))).contains(Badge.WOODEN_SPOON));
        assertFalse(badgesOf(AchievementRules.evaluate(facts(result(standings(3, 11, 0, 11), 11, bust(70, 150),
                hindsight(false), format(), null, HistoricalResult.MISSED_PLAYOFFS)))).contains(Badge.WOODEN_SPOON));
    }

    // ---- BIG_BUST (stored + new) ----------------------------------------------------------------------

    @Test
    void pickThreeFinishing150thIsABustAndPick160FinishingSecondNeverIs() {
        GameAward a = AchievementRules.evaluate(facts(plain(), pick(3, 3, 150, 19), pick(160, 160, 2, 19)));
        assertTrue(badgesOf(a).contains(Badge.BIG_BUST));
        assertEquals(Set.of("BIG_BUST", "LATE_GEM"), momentCodes(a));
        Moment bust = a.moments().stream().filter(m -> m.code().equals("BIG_BUST")).findFirst().orElseThrow();
        assertEquals(3, bust.pickNo());
        assertTrue(bust.text().contains("150th of 168"), bust.text());
        assertTrue(bust.text().contains("147 places below"), bust.text());
    }

    @Test
    void aBustNeedsRoundOneOrTwoAndAFullThreeRoundGap() {
        // pick 24 (round 2) at rank 59: gap 35 < 36, not a bust; at rank 60: gap 36, a bust
        assertFalse(momentCodes(AchievementRules.evaluate(facts(plain(), pick(24, 24, 59, 19)))).contains("BIG_BUST"));
        assertTrue(momentCodes(AchievementRules.evaluate(facts(plain(), pick(24, 24, 60, 19)))).contains("BIG_BUST"));
        // pick 25 is round 3: even a 100-place fall is outside the rule
        assertFalse(momentCodes(AchievementRules.evaluate(facts(plain(), pick(25, 25, 125, 19)))).contains("BIG_BUST"));
    }

    @Test
    void anOldGameChecksOnlyItsStoredBiggestBust() {
        HistoricalResult early = result(standings(8, 6, 0, 5), 5, bust(5, 100), hindsight(false), format(), null,
                HistoricalResult.MISSED_PLAYOFFS);
        GameAward a = AchievementRules.evaluate(facts(early));
        assertTrue(badgesOf(a).contains(Badge.BIG_BUST));
        assertTrue(a.moments().isEmpty(), "no pick facts, so no per-pick moments");
        assertTrue(a.fromSavedResult());
        assertFalse(badgesOf(AchievementRules.evaluate(facts(plain()))).contains(Badge.BIG_BUST), "pick 70 is round 6");
    }

    // ---- REACH (new games) ----------------------------------------------------------------------------

    @Test
    void aReachThatPaidOffEarnsCalledItAndNotGalaxyBrain() {
        // pick 20 (round 2) taken 20 spots ahead of board rank 40, he finished 5th
        GameAward a = AchievementRules.evaluate(facts(plain(), pick(20, 40, 5, 19)));
        assertEquals(Set.of("REACH_HIT"), momentCodes(a));
        assertTrue(badgesOf(a).contains(Badge.REACH_HIT));
        assertFalse(badgesOf(a).contains(Badge.REACH_MISS));
        assertTrue(a.moments().get(0).text().contains("20 picks ahead of his board rank (40)"));
    }

    @Test
    void aReachThatFlopsEarnsGalaxyBrainAndNotCalledIt() {
        GameAward a = AchievementRules.evaluate(facts(plain(), pick(20, 40, 50, 19)));   // 50 - 20 = 30 >= 24, but under the 36 a bust needs
        assertEquals(Set.of("REACH_MISS"), momentCodes(a));
        assertTrue(badgesOf(a).contains(Badge.REACH_MISS));
        assertFalse(badgesOf(a).contains(Badge.REACH_HIT));
        // and just inside the line is not a miss
        assertTrue(momentCodes(AchievementRules.evaluate(facts(plain(), pick(20, 40, 43, 19)))).isEmpty());
    }

    @Test
    void aHitNeedsAFullRoundOfMarginNotJustTheSlot() {
        // pick 30 (round 3): margin is a full round (12), so a hit needs rank <= 18. Finishing 30th, exactly his
        // slot, is not a hit; 19th is not; 18th is.
        assertTrue(momentCodes(AchievementRules.evaluate(facts(plain(), pick(30, 50, 30, 19)))).isEmpty());
        assertTrue(momentCodes(AchievementRules.evaluate(facts(plain(), pick(30, 50, 19, 19)))).isEmpty());
        assertEquals(Set.of("REACH_HIT"),
                momentCodes(AchievementRules.evaluate(facts(plain(), pick(30, 50, 18, 19)))));
    }

    @Test
    void aRoundTwoHitBeforePickTwentyFourNeedsHalfThePickNumberNotAFullRound() {
        // CHANGED by CR-03: pick 20's margin is min(12, 20/2) = 10, so a hit needs rank <= 10 (it was <= 8).
        assertTrue(momentCodes(AchievementRules.evaluate(facts(plain(), pick(20, 40, 11, 19)))).isEmpty());
        assertEquals(Set.of("REACH_HIT"),
                momentCodes(AchievementRules.evaluate(facts(plain(), pick(20, 40, 10, 19)))));
        // pick 24 is the first pick whose margin is the full round: rank <= 12
        assertTrue(momentCodes(AchievementRules.evaluate(facts(plain(), pick(24, 50, 13, 19)))).isEmpty());
        assertEquals(Set.of("REACH_HIT"),
                momentCodes(AchievementRules.evaluate(facts(plain(), pick(24, 50, 12, 19)))));
    }

    @Test
    void aRoundOneReachCanBeAHitByFinishingInTheTopHalfOfItsPickNumber() {
        // CR-03: pick 12 (round 1) taken 28 early (board rank 40). Margin = min(12, 6) = 6: 6th hits, 7th does not.
        assertEquals(Set.of("REACH_HIT"),
                momentCodes(AchievementRules.evaluate(facts(plain(), pick(12, 40, 6, 19)))));
        assertTrue(momentCodes(AchievementRules.evaluate(facts(plain(), pick(12, 40, 7, 19)))).isEmpty(),
                "7th is outside the top half of pick 12");
        // and a round-1 flop is still a miss, unchanged
        assertEquals(Set.of("REACH_MISS"),
                momentCodes(AchievementRules.evaluate(facts(plain(), pick(12, 40, 36, 19)))));
    }

    @Test
    void aTakeThatIsLessThanARoundAheadOfTheBoardIsNotAReach() {
        // board rank 31 at pick 20: only 11 ahead, so a great finish is not "Called it"
        assertTrue(momentCodes(AchievementRules.evaluate(facts(plain(), pick(20, 31, 2, 19)))).isEmpty());
        assertEquals(Set.of("REACH_HIT"),
                momentCodes(AchievementRules.evaluate(facts(plain(), pick(20, 32, 2, 19)))));
    }

    @Test
    void reachOnlyAppliesToTheFirstHalfOfTheDraft() {
        // pick 90 is round 8: a 30-spot reach that finished 10th is a late gem, never "Called it"
        Set<String> codes = momentCodes(AchievementRules.evaluate(facts(plain(), pick(90, 120, 10, 19))));
        assertEquals(Set.of("LATE_GEM"), codes);
        // pick 84 is round 7, the last first-half round
        assertTrue(momentCodes(AchievementRules.evaluate(facts(plain(), pick(84, 120, 10, 19)))).contains("REACH_HIT"));
    }

    // ---- LATE_GEM -------------------------------------------------------------------------------------

    @Test
    void aLateGemIsRoundEightOrLaterAndTopRByValue() {
        assertEquals(Set.of("LATE_GEM"),
                momentCodes(AchievementRules.evaluate(facts(plain(), pick(100, 100, 12, 19)))));
        assertTrue(momentCodes(AchievementRules.evaluate(facts(plain(), pick(100, 100, 13, 19)))).isEmpty(),
                "13th is outside the top 12");
        assertTrue(momentCodes(AchievementRules.evaluate(facts(plain(), pick(80, 80, 5, 19)))).isEmpty(),
                "pick 80 is round 7, a first-half pick");
    }

    // ---- GONE_EARLY -----------------------------------------------------------------------------------

    @Test
    void aRoundOnePickWhoseLastBoxScoreWasWeekTwoIsGoneEarly() {
        GameAward a = AchievementRules.evaluate(facts(plain(), pick(4, 4, 80, 2)));
        assertTrue(momentCodes(a).contains("GONE_EARLY"));
        Moment m = a.moments().stream().filter(x -> x.code().equals("GONE_EARLY")).findFirst().orElseThrow();
        assertTrue(m.text().contains("his last box score in our data came in week 2"), m.text());
        assertFalse(m.text().toLowerCase().contains("injur"), "there is no injury data, so none is claimed");
        assertFalse(m.text().toLowerCase().contains("hurt"));
    }

    @Test
    void aRoundSixPickGoneInWeekTwoIsNotGoneEarly() {
        assertFalse(momentCodes(AchievementRules.evaluate(facts(plain(), pick(70, 70, 100, 2)))).contains("GONE_EARLY"));
    }

    @Test
    void goneEarlyNeedsWeekThreeOrEarlierAndALongEnoughSeason() {
        assertTrue(momentCodes(AchievementRules.evaluate(facts(plain(), pick(30, 30, 80, 3)))).contains("GONE_EARLY"));
        assertFalse(momentCodes(AchievementRules.evaluate(facts(plain(), pick(30, 30, 80, 4)))).contains("GONE_EARLY"));
        assertFalse(momentCodes(AchievementRules.evaluate(facts(plain(), pick(37, 37, 80, 2)))).contains("GONE_EARLY"),
                "pick 37 is round 4");
        // a 9-week season: not enough weeks for "early" to mean anything
        GameFacts shortSeason = new GameFacts("NBA", TEAMS, ROUNDS, ME, plain(),
                new PickFacts(9, List.of(pick(4, 4, 80, 2))));
        assertFalse(momentCodes(AchievementRules.evaluate(shortSeason)).contains("GONE_EARLY"));
        // a player with no box score row at all is not claimed as anything
        assertFalse(momentCodes(AchievementRules.evaluate(facts(plain(), pick(4, 4, 80, null)))).contains("GONE_EARLY"));
    }

    // ---- moments vs badges (A4) -----------------------------------------------------------------------

    @Test
    void everyInstanceIsAMomentButTheBadgeIsEarnedOncePerGame() {
        GameAward a = AchievementRules.evaluate(facts(plain(), pick(20, 40, 5, 19), pick(21, 45, 6, 19)));
        assertEquals(2, a.moments().stream().filter(m -> m.code().equals("REACH_HIT")).count());
        assertEquals(1, a.badges().stream().filter(b -> b.badge() == Badge.REACH_HIT).count());
        assertEquals(20, a.moments().get(0).pickNo(), "moments come in pick order");
    }

    // ---- cumulative -----------------------------------------------------------------------------------

    @Test
    void winMilestonesUnlockAtTheirThresholdAndNotBefore() {
        assertEquals(Set.of(Badge.FIRST_GAME), cumulativeSet(new CareerTotals(1, 0, 0, 0, 0)));
        assertEquals(Set.of(Badge.FIRST_GAME, Badge.WINS_1), cumulativeSet(new CareerTotals(1, 9, 0, 0, 0)));
        assertEquals(Set.of(Badge.FIRST_GAME, Badge.WINS_1, Badge.WINS_10), cumulativeSet(new CareerTotals(2, 10, 0, 0, 0)));
        assertEquals(Set.of(Badge.FIRST_GAME, Badge.WINS_1, Badge.WINS_10, Badge.WINS_25, Badge.WINS_50, Badge.WINS_100),
                cumulativeSet(new CareerTotals(9, 100, 0, 0, 0)));
        assertTrue(AchievementRules.cumulative(new CareerTotals(0, 0, 0, 0, 0)).isEmpty());
    }

    @Test
    void dynastyNeedsThreeTitlesAndTwoSportNeedsBothSports() {
        assertFalse(cumulativeSet(new CareerTotals(5, 40, 2, 2, 0)).contains(Badge.TITLES_3));
        assertTrue(cumulativeSet(new CareerTotals(5, 40, 3, 3, 0)).contains(Badge.TITLES_3));
        assertFalse(cumulativeSet(new CareerTotals(5, 40, 3, 3, 0)).contains(Badge.BOTH_SPORTS),
                "three basketball titles are not a football title");
        assertTrue(cumulativeSet(new CareerTotals(2, 20, 2, 1, 1)).contains(Badge.BOTH_SPORTS));
    }

    private static Set<Badge> cumulativeSet(CareerTotals t) {
        return AchievementRules.cumulative(t).stream().map(BadgeEarn::badge).collect(Collectors.toSet());
    }

    // ---- honesty and copy -----------------------------------------------------------------------------

    @Test
    void noBadgeStringHasAnEmDashAndNoneClaimsAnInjury() {
        for (Badge b : Badge.values()) {
            for (String s : List.of(b.displayName(), b.rule())) {
                assertFalse(s.contains("—") || s.contains("–"), b + ": " + s);
                assertFalse(s.toLowerCase().contains("injur"), b + ": " + s);
                assertFalse(s.toLowerCase().contains("acl"), b + ": " + s);
            }
        }
        GameAward a = AchievementRules.evaluate(facts(plain(), pick(4, 4, 80, 2), pick(20, 40, 5, 19),
                pick(3, 3, 150, 19), pick(100, 100, 2, 19)));
        for (Moment m : a.moments()) assertFalse(m.text().contains("—"), m.text());
        for (BadgeEarn e : a.badges()) assertFalse(String.valueOf(e.detail().get("text")).contains("—"));
    }

    @Test
    void everyBadgeHasAnXpAndTheCatalogIsComplete() {
        assertEquals(21, Badge.values().length);
        for (Badge b : Badge.values()) assertTrue(b.xp() > 0, b.name());
    }
}
