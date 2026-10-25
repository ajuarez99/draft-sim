package com.ballknowers.draftsim.engine;

import com.ballknowers.draftsim.store.LeagueLosersBracketRepository.Match;
import com.ballknowers.draftsim.store.LeagueRepository.PlayoffFormat;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * specs/047 T009: one test per refusal reason, a toilet-bowl and a consolation success, and the four measured
 * seasons. The fixtures under {@code fixtures/047/} are Sleeper's real {@code losers_bracket} for each league
 * plus the matching stored week points (research R2 amended): FBK 2025 and West Coast 2025 are the two
 * directions, NBA 2024/2025 are the mixed ones the rule must refuse.
 */
class LastPlaceRuleTest {

    /** The fixtures carry no pairing data; the real pairing is exercised in HallOfFameServiceIT. */
    private static final LastPlaceRule.Paired ALL_MET = (w, a, b) -> true;

    private record Fixture(List<Match> matches, PlayoffFormat format, int totalRosters, Set<Integer> nonPlayoff,
                           Map<String, Double> points) {
        LastPlaceRule.WeekPoints lookup() {
            return (week, roster) -> points.get(week + ":" + roster);
        }

        LastPlaceRule.Result evaluate() {
            return LastPlaceRule.evaluate(matches, format, totalRosters, nonPlayoff, lookup(), ALL_MET);
        }
    }

    private static Fixture load(String name) throws IOException {
        try (InputStream in = LastPlaceRuleTest.class.getResourceAsStream("/fixtures/047/" + name + ".json")) {
            JsonNode root = new ObjectMapper().readTree(in);
            List<Match> matches = new ArrayList<>();
            for (JsonNode m : root.get("losersBracket")) {
                matches.add(new Match(1L, m.get("m").asInt(), m.get("r").asInt(), intOrNull(m.get("t1")),
                        intOrNull(m.get("t2")), intOrNull(m.get("w")), intOrNull(m.get("l")), intOrNull(m.get("p"))));
            }
            Set<Integer> non = new HashSet<>();
            root.get("nonPlayoffRosters").forEach(n -> non.add(n.asInt()));
            Map<String, Double> pts = new HashMap<>();
            root.get("weekPoints").fields().forEachRemaining(e -> pts.put(e.getKey(), e.getValue().asDouble()));
            PlayoffFormat fmt = new PlayoffFormat(root.get("playoffTeams").asInt(), root.get("playoffWeekStart").asInt(),
                    0, false, false, intOrNull(root.get("playoffRoundType")));
            return new Fixture(matches, fmt, root.get("totalRosters").asInt(), non, pts);
        }
    }

    private static Integer intOrNull(JsonNode n) {
        return n == null || n.isNull() || !n.isInt() ? null : n.asInt();
    }

    // ------------------------------------------------------------------ the four measured seasons

    @Test
    void footballBallKnowers2025IsAToiletBowlNamingRoster2() throws IOException {
        LastPlaceRule.Result r = load("fbk2025").evaluate();
        assertEquals(2, r.rosterId());
        assertEquals(LastPlaceRule.TOILET_BOWL, r.bracket());
        assertNull(r.reason());
    }

    @Test
    void westCoast2025IsAConsolationBracketNamingTheP5Loser() throws IOException {
        Fixture f = load("wc2025");
        LastPlaceRule.Result r = f.evaluate();
        // The p=5 match is m5 (10 v 9); the winner (9) advanced out of the bottom, the loser (10) is last.
        assertEquals(10, r.rosterId());
        assertEquals(LastPlaceRule.CONSOLATION, r.bracket());
        assertNull(r.reason());
    }

    @Test
    void nba2024AndNba2025AreRefusedAsMixedDirection() throws IOException {
        for (String name : List.of("nba2024", "nba2025")) {
            LastPlaceRule.Result r = load(name).evaluate();
            assertNull(r.rosterId(), name);
            assertEquals(LastPlaceRule.MIXED_DIRECTION, r.reason(), name);
        }
    }

    // ------------------------------------------------------------------ one test per refusal reason

    @Test
    void noLosersBracketIsRefused() throws IOException {
        Fixture f = load("fbk2025");
        assertEquals(LastPlaceRule.NO_LOSERS_BRACKET,
                LastPlaceRule.evaluate(List.of(), f.format(), 12, f.nonPlayoff(), f.lookup(), ALL_MET).reason());
    }

    @Test
    void noWinnersBracketIsRefused() throws IOException {
        Fixture f = load("fbk2025");
        assertEquals(LastPlaceRule.NO_WINNERS_BRACKET,
                LastPlaceRule.evaluate(f.matches(), f.format(), 12, null, f.lookup(), ALL_MET).reason());
    }

    @Test
    void anUndecidedMatchIsIncomplete() throws IOException {
        Fixture f = load("fbk2025");
        List<Match> ms = new ArrayList<>(f.matches());
        Match last = ms.get(5);
        ms.set(5, new Match(last.leagueId(), last.matchId(), last.round(), last.t1RosterId(), last.t2RosterId(),
                null, null, last.placement()));
        assertEquals(LastPlaceRule.INCOMPLETE,
                LastPlaceRule.evaluate(ms, f.format(), 12, f.nonPlayoff(), f.lookup(), ALL_MET).reason());
    }

    @Test
    void rostersThatAreNotExactlyTheNonPlayoffSetAreRefused() throws IOException {
        Fixture f = load("fbk2025");
        Set<Integer> missingOne = new HashSet<>(f.nonPlayoff());
        missingOne.remove(10);
        assertEquals(LastPlaceRule.UNCOVERED_ROSTERS,
                LastPlaceRule.evaluate(f.matches(), f.format(), 12, missingOne, f.lookup(), ALL_MET).reason());
        Set<Integer> extra = new HashSet<>(f.nonPlayoff());
        extra.add(99);
        assertEquals(LastPlaceRule.UNCOVERED_ROSTERS,
                LastPlaceRule.evaluate(f.matches(), f.format(), 12, extra, f.lookup(), ALL_MET).reason());
    }

    @Test
    void aBracketWithNoUsableScoresCannotBeReadAndIsRefused() throws IOException {
        Fixture f = load("fbk2025");
        assertEquals(LastPlaceRule.NO_SCORES,
                LastPlaceRule.evaluate(f.matches(), f.format(), 12, f.nonPlayoff(), (w, r) -> null, ALL_MET).reason());
    }

    @Test
    void aConsolationBracketThatDoesNotFillTheLeagueIsUnsupportedShape() throws IOException {
        Fixture f = load("wc2025");
        // playoff_teams 6 + max p 5 + 1 = 12; a 14-team league with the same bracket doesn't add up.
        LastPlaceRule.Result r = LastPlaceRule.evaluate(f.matches(), f.format(), 14, f.nonPlayoff(), f.lookup(), ALL_MET);
        assertEquals(LastPlaceRule.UNSUPPORTED_SHAPE, r.reason());
        assertEquals(LastPlaceRule.CONSOLATION, r.bracket());
        assertNull(r.rosterId());
    }

    @Test
    void aToiletBowlWithoutAPlacementOneMatchIsUnsupportedShape() throws IOException {
        Fixture f = load("fbk2025");
        List<Match> ms = new ArrayList<>();
        for (Match m : f.matches()) {
            ms.add(m.placement() != null && m.placement() == 1
                    ? new Match(m.leagueId(), m.matchId(), m.round(), m.t1RosterId(), m.t2RosterId(),
                    m.winnerRosterId(), m.loserRosterId(), null)
                    : m);
        }
        assertEquals(LastPlaceRule.UNSUPPORTED_SHAPE,
                LastPlaceRule.evaluate(ms, f.format(), 12, f.nonPlayoff(), f.lookup(), ALL_MET).reason());
    }

    @Test
    void anUnmappableRoundTypeIsUnsupportedShape() throws IOException {
        Fixture f = load("fbk2025");
        PlayoffFormat odd = new PlayoffFormat(6, f.format().playoffWeekStart(), 0, false, false, 7);
        assertEquals(LastPlaceRule.UNSUPPORTED_SHAPE,
                LastPlaceRule.evaluate(f.matches(), odd, 12, f.nonPlayoff(), f.lookup(), ALL_MET).reason());
    }

    // ------------------------------------------------------------------ amended after code review (S2, S3)

    @Test
    void oneUnscoredMatchRefusesTheWholeSeasonInsteadOfLettingTheOthersVote() throws IOException {
        Fixture f = load("fbk2025");
        Match first = f.matches().get(0);
        LastPlaceRule.WeekPoints missingOne = (wk, r) ->
                r == first.winnerRosterId() && wk == f.format().playoffWeekStart() ? null : f.points().get(wk + ":" + r);
        LastPlaceRule.Result r = LastPlaceRule.evaluate(f.matches(), f.format(), 12, f.nonPlayoff(), missingOne, ALL_MET);
        assertNull(r.rosterId());
        assertEquals(LastPlaceRule.NO_SCORES, r.reason());
    }

    @Test
    void oneTiedMatchRefusesTheWholeSeason() throws IOException {
        Fixture f = load("fbk2025");
        Match first = f.matches().get(0);
        int wk0 = f.format().playoffWeekStart();
        LastPlaceRule.WeekPoints tied = (wk, r) -> {
            if (wk == wk0 && r == first.winnerRosterId()) return f.points().get(wk + ":" + first.loserRosterId());
            return f.points().get(wk + ":" + r);
        };
        LastPlaceRule.Result r = LastPlaceRule.evaluate(f.matches(), f.format(), 12, f.nonPlayoff(), tied, ALL_MET);
        assertNull(r.rosterId());
        assertEquals(LastPlaceRule.MIXED_DIRECTION, r.reason());
    }

    @Test
    void aMatchWhoseRostersDidNotMeetInAMappedWeekIsRefusedAsUnsupportedShape() throws IOException {
        for (String name : List.of("fbk2025", "wc2025")) {
            Fixture f = load(name);
            Match first = f.matches().get(0);
            // misaligned weeks: the winners and losers of the first match were never paired
            LastPlaceRule.Paired misaligned = (wk, a, b) -> !(a == first.winnerRosterId() && b == first.loserRosterId());
            LastPlaceRule.Result r = LastPlaceRule.evaluate(f.matches(), f.format(), f.totalRosters(), f.nonPlayoff(),
                    f.lookup(), misaligned);
            assertNull(r.rosterId(), name);
            assertEquals(LastPlaceRule.UNSUPPORTED_SHAPE, r.reason(), name);
        }
        // and no pairing data at all is a refusal too, never a guess
        Fixture f = load("fbk2025");
        assertEquals(LastPlaceRule.UNSUPPORTED_SHAPE,
                LastPlaceRule.evaluate(f.matches(), f.format(), 12, f.nonPlayoff(), f.lookup(), (w, a, b) -> false).reason());
    }

    // ------------------------------------------------------------------ the answer is exactly one of the two

    @Test
    void exactlyOneOfRosterOrReasonIsSet() throws IOException {
        for (String name : List.of("fbk2025", "wc2025", "nba2024", "nba2025")) {
            LastPlaceRule.Result r = load(name).evaluate();
            assertEquals(r.rosterId() == null, r.reason() != null, name);
        }
    }
}
