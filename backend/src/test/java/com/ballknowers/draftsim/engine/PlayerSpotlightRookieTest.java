package com.ballknowers.draftsim.engine;

import com.ballknowers.draftsim.config.ScoringProperties;
import com.ballknowers.draftsim.domain.Player;
import com.ballknowers.draftsim.domain.Position;
import com.ballknowers.draftsim.domain.Sport;
import com.ballknowers.draftsim.sport.BasketballRules;
import com.ballknowers.draftsim.sport.FootballRules;
import com.ballknowers.draftsim.sport.SportRules;
import com.ballknowers.draftsim.engine.PlayerSpotlightService.PeriodRows;
import com.ballknowers.draftsim.engine.PlayerSpotlightService.Section;
import com.ballknowers.draftsim.engine.SpotlightOwnership.Ownership;
import com.ballknowers.draftsim.store.PlayerGameRepository;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static com.ballknowers.draftsim.engine.PlayerSpotlightTopOfNightTest.*;
import static org.junit.jupiter.api.Assertions.*;

/** Rookie watch (specs/014 T036): years_exp == 0 exactly, ordered by points, a reason when empty. */
class PlayerSpotlightRookieTest {

    private static final ScoringProperties.SportScoring SCORING_CFG = new ScoringProperties.SportScoring(
            new ScoringProperties.Weights(1.0, 0.35, 0.5, 0.25),
            12.0, 3.0, 60.0, 0.15, 6, 0.85,
            Map.of("K", 3, "DEF", 4), 1.0, 30);
    private static final ScoringProperties PROPS = new ScoringProperties(SCORING_CFG, SCORING_CFG);
    private static final SportRules BASKETBALL = new BasketballRules(PROPS);
    private static final SportRules FOOTBALL = new FootballRules(PROPS);

    private static Player at(String id, Position pos, Integer yearsExp) {
        return new Player(Math.abs(id.hashCode()), Sport.NFL, id, "Name " + id,
                List.of(pos), "TM", "Active", null, 22, yearsExp);
    }

    @Test
    void onlyYearsExpZeroIsARookieRosteredOrNotAndOrderedByPoints() {
        List<PlayerGameRepository.Row> games = List.of(
                game("r1", "g1", D, 20), game("r2", "g1", D, 31),
                game("vet", "g1", D, 60), game("unk", "g1", D, 70),
                game("r3", "g2", D, 31)); // exact tie with r2: id order decides
        Map<String, Player> byId = players(player("r1", 0), player("r2", 0), player("r3", 0),
                player("vet", 1), player("unk", null));
        Map<String, Ownership> owners = Map.of("r1", rostered("Team A"));

        Section s = PlayerSpotlightService.rookieWatch(NIGHT, rows(games, byId), owners, BASKETBALL);

        assertNull(s.unavailable());
        assertEquals(List.of("r2", "r3", "r1"), ids(s));
        assertTrue(s.entries().get(2).ownership().rostered());
        assertFalse(s.entries().get(0).ownership().rostered(), "an unrostered rookie is a free agent here");
    }

    @Test
    void limitIsTen() {
        List<PlayerGameRepository.Row> games = new ArrayList<>();
        Map<String, Player> byId = new HashMap<>();
        for (int i = 0; i < 13; i++) {
            String id = "r" + String.format("%02d", i);
            games.add(game(id, "g" + i, D, 10 + i));
            byId.put(id, player(id, 0));
        }
        Section s = PlayerSpotlightService.rookieWatch(NIGHT, rows(games, byId), Map.of(), BASKETBALL);
        assertEquals(10, s.entries().size());
        assertEquals("r12", s.entries().get(0).playerId());
        assertEquals("r03", s.entries().get(9).playerId());
    }

    @Test
    void rowsButNoRookieIsNoRookiePlayed() {
        Section s = PlayerSpotlightService.rookieWatch(NIGHT,
                rows(List.of(game("vet", "g1", D, 25)), players(player("vet", 4))), Map.of(), BASKETBALL);
        assertTrue(s.entries().isEmpty());
        assertEquals("NO_ROOKIE_PLAYED", s.unavailable());
    }

    /** Preference ordering, not structure: the K has the most points and must still be left out. */
    @Test
    void footballExcludesAKickerWithTheMostPointsAndADefWithNullYearsExpButKeepsAReceiver() {
        List<PlayerGameRepository.Row> games = List.of(
                game("k", "g1", D, 40), game("def", "g2", D, 50), game("def0", "g3", D, 45),
                game("wr", "g4", D, 12));
        Map<String, Player> byId = players(at("k", Position.K, 0), at("def", Position.DEF, null),
                at("def0", Position.DEF, 0), at("wr", Position.WR, 0));

        Section s = PlayerSpotlightService.rookieWatch(NIGHT, rows(games, byId), Map.of(), FOOTBALL);

        assertEquals(List.of("wr"), ids(s), "K and DEF are out whatever their years_exp");

        Section basketball = PlayerSpotlightService.rookieWatch(NIGHT, rows(games, byId), Map.of(), BASKETBALL);
        assertEquals(List.of("def0", "k", "wr"), ids(basketball), "basketball excludes no position (null years_exp still is not a rookie)");
    }

    @Test
    void aSectionOfOnlyExcludedPositionsIsNoRookiePlayedNotAnEmptyUnexplainedList() {
        Section s = PlayerSpotlightService.rookieWatch(NIGHT,
                rows(List.of(game("k", "g1", D, 40)), players(at("k", Position.K, 0))), Map.of(), FOOTBALL);
        assertEquals("NO_ROOKIE_PLAYED", s.unavailable());
    }

    @Test
    void noPeriodIsNoPeriod() {
        Section s = PlayerSpotlightService.rookieWatch(null, new PeriodRows(List.of(), List.of()), Map.of(), BASKETBALL);
        assertEquals("NO_PERIOD", s.unavailable());
        assertTrue(s.entries().isEmpty());
    }
}
