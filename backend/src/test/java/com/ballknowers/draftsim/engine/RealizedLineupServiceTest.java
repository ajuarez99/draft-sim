package com.ballknowers.draftsim.engine;

import com.ballknowers.draftsim.config.ScoringProperties;
import com.ballknowers.draftsim.domain.*;
import com.ballknowers.draftsim.sport.BasketballRules;
import com.ballknowers.draftsim.sport.FootballRules;
import com.ballknowers.draftsim.sport.SportRules;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * {@link RealizedLineupService} is a pure function of a Sleeper
 * {@code players_points} blob plus a player table, so it is pinned here
 * without a database -- the same split {@code LeagueAnalysisServiceTest}
 * describes: pure rules tested directly, repository walks left to integration
 * tests rather than mocked into a shape that only proves the mocks were wired.
 *
 * <p>specs/004-ffwrapped-feature-parity US2.
 */
class RealizedLineupServiceTest {

    private static final List<String> NFL_SLOTS = List.of(
            "QB", "RB", "RB", "WR", "WR", "TE", "FLEX", "K", "DEF", "BN", "BN", "BN", "BN");
    private static final List<String> NBA_SLOTS = List.of(
            "PG", "SG", "G", "SF", "PF", "F", "C", "UTIL", "UTIL", "BN", "BN", "BN", "BN", "BN");

    private static final LeagueSettings NFL =
            new LeagueSettings(Sport.NFL, 12, 13, NFL_SLOTS, 1.0);
    private static final LeagueSettings NBA =
            new LeagueSettings(Sport.NBA, 12, 14, NBA_SLOTS, 0.0);

    private static ScoringProperties.SportScoring scoring() {
        return new ScoringProperties.SportScoring(
                new ScoringProperties.Weights(1.0, 0.35, 0.5, 0.25),
                12.0, 3.0, 60.0, 0.15, 6, 0.85, Map.of(), 1.0, 30);
    }

    private final RealizedLineupService service = new RealizedLineupService();
    private final SportRules football = new FootballRules(new ScoringProperties(scoring(), null));
    private final SportRules basketball = new BasketballRules(new ScoringProperties(null, scoring()));

    private final Map<String, Player> nflPlayers = new HashMap<>();
    private final Map<String, Player> nbaPlayers = new HashMap<>();

    private Player nfl(String sleeperId, String name, Position pos) {
        Player p = new Player(sleeperId.hashCode(), Sport.NFL, sleeperId, name, List.of(pos),
                null, "Active", null, null, null);
        nflPlayers.put(sleeperId, p);
        return p;
    }

    private Player nba(String sleeperId, String name, List<Position> pos) {
        Player p = new Player(sleeperId.hashCode(), Sport.NBA, sleeperId, name, pos,
                null, "Active", null, null, null);
        nbaPlayers.put(sleeperId, p);
        return p;
    }

    /** A week where nobody was scored is not a week of zeros (FR-007, US2.5). */
    @Test
    void anEmptyBreakdownIsAbsentRatherThanZero() {
        for (String blob : new String[]{null, "", "{}"}) {
            RealizedLineupService.WeekLineup w =
                    service.bestLineup(blob, nflPlayers, NFL, football);
            assertFalse(w.valid(), "blob " + blob + " should not produce a valid week");
            assertTrue(w.lineup().isEmpty());
            assertNotNull(w.reason(), "an absent week must say why");
        }
    }

    /** Points for players this app has no row for cannot be seated. */
    @Test
    void aBreakdownOfEntirelyUnknownPlayersIsAbsent() {
        RealizedLineupService.WeekLineup w = service.bestLineup(
                "{\"999999\":42.0}", nflPlayers, NFL, football);
        assertFalse(w.valid());
        assertNotNull(w.reason());
    }

    /**
     * The point of the whole feature: the optimal lineup is chosen by points
     * ACTUALLY SCORED, not by draft position. Here the bench RB outscored both
     * starters, so he has to be in the lineup.
     */
    @Test
    void theOptimalLineupIsChosenByRealizedPointsNotDraftOrder() {
        nfl("qb", "QB", Position.QB);
        nfl("rb1", "RB One", Position.RB);
        nfl("rb2", "RB Two", Position.RB);
        nfl("rb3", "RB Three", Position.RB);
        nfl("wr1", "WR One", Position.WR);
        nfl("wr2", "WR Two", Position.WR);
        nfl("te", "TE", Position.TE);
        nfl("k", "K", Position.K);
        nfl("def", "DEF", Position.DEF);

        // rb3 is the best RB this week by a mile.
        String blob = """
                {"qb":20.0,"rb1":5.0,"rb2":4.0,"rb3":30.0,
                 "wr1":12.0,"wr2":11.0,"te":8.0,"k":7.0,"def":6.0}
                """;

        RealizedLineupService.WeekLineup w = service.bestLineup(blob, nflPlayers, NFL, football);

        assertTrue(w.valid());
        List<String> seated = w.lineup().stream().map(a -> a.entry().player().sleeperId()).toList();
        assertTrue(seated.contains("rb3"),
                "the highest-scoring RB must start; seated=" + seated);

        // Sum matches the assignment it reports.
        double sum = 0;
        for (SportRules.Assigned a : w.lineup()) sum += a.value();
        assertEquals(sum, w.points(), 1e-9);
    }

    /**
     * US2.3 / SC-002. The same call, the same code path, for basketball -- and
     * with multi-position players, which is the case football's shape does not
     * answer and the reason {@code BasketballRules} needed its own solver.
     */
    @Test
    void basketballSeatsAMultiPositionRosterThroughTheSameCall() {
        nba("pg1", "PG One", List.of(Position.PG));
        nba("sg1", "SG One", List.of(Position.SG, Position.PG));
        nba("sf1", "SF One", List.of(Position.SF));
        nba("pf1", "PF One", List.of(Position.PF, Position.C));
        nba("c1", "C One", List.of(Position.C));
        nba("g2", "G Two", List.of(Position.PG, Position.SG));
        nba("f2", "F Two", List.of(Position.SF, Position.PF));
        nba("c2", "C Two", List.of(Position.C));
        nba("sg2", "SG Two", List.of(Position.SG));
        nba("bench", "Bench", List.of(Position.SF));

        String blob = """
                {"pg1":30.0,"sg1":28.0,"sf1":26.0,"pf1":24.0,"c1":22.0,
                 "g2":20.0,"f2":18.0,"c2":16.0,"sg2":14.0,"bench":1.0}
                """;

        RealizedLineupService.WeekLineup w = service.bestLineup(blob, nbaPlayers, NBA, basketball);

        assertTrue(w.valid(), "basketball must answer, not throw -- this is what US1 unlocked");
        assertEquals(9, w.lineup().size(), "nine starting slots should all be filled");

        List<String> seated = w.lineup().stream().map(a -> a.entry().player().sleeperId()).toList();
        assertFalse(seated.contains("bench"),
                "the 1-point player should not displace a 14-point one; seated=" + seated);
        assertEquals(seated.size(), seated.stream().distinct().count(),
                "nobody may occupy two slots");

        double sum = 0;
        for (SportRules.Assigned a : w.lineup()) sum += a.value();
        assertEquals(sum, w.points(), 1e-9);
    }

    /**
     * Five pure centres can only fill C + the two UTIL slots -- the case
     * {@code nba-greedy-optimality.py}'s oracle calls out by name. With
     * realized points driving the choice, the three seated must be the three
     * highest SCORERS, not the three earliest picks.
     */
    @Test
    void fivePureCentresSeatTheThreeHighestScorersNotTheEarliestPicks() {
        nba("cA", "C A", List.of(Position.C));
        nba("cB", "C B", List.of(Position.C));
        nba("cC", "C C", List.of(Position.C));
        nba("cD", "C D", List.of(Position.C));
        nba("cE", "C E", List.of(Position.C));

        String blob = "{\"cA\":1.0,\"cB\":2.0,\"cC\":3.0,\"cD\":40.0,\"cE\":50.0}";

        RealizedLineupService.WeekLineup w = service.bestLineup(blob, nbaPlayers, NBA, basketball);

        assertTrue(w.valid());
        assertEquals(3, w.lineup().size(), "only C, UTIL, UTIL accept a pure centre");
        List<String> seated = w.lineup().stream().map(a -> a.entry().player().sleeperId()).sorted().toList();
        assertEquals(List.of("cC", "cD", "cE"), seated,
                "the three highest scorers must be seated, not the three lowest ids");
        assertEquals(93.0, w.points(), 1e-9);
    }

    /** A partially-filled roster still answers; it just seats fewer players. */
    @Test
    void aShortRosterSeatsWhoItCanWithoutFailing() {
        nfl("qb", "QB", Position.QB);
        nfl("rb1", "RB One", Position.RB);

        RealizedLineupService.WeekLineup w = service.bestLineup(
                "{\"qb\":20.0,\"rb1\":10.0}", nflPlayers, NFL, football);

        assertTrue(w.valid());
        assertEquals(30.0, w.points(), 1e-9);
        assertEquals(2, w.lineup().size());
    }
}
