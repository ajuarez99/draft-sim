package com.ballknowers.draftsim.engine;

import com.ballknowers.draftsim.domain.Player;
import com.ballknowers.draftsim.domain.Position;
import com.ballknowers.draftsim.domain.Sport;
import com.ballknowers.draftsim.engine.PlayerSpotlightService.Performance;
import com.ballknowers.draftsim.engine.PlayerSpotlightService.PeriodRows;
import com.ballknowers.draftsim.engine.PlayerSpotlightService.Section;
import com.ballknowers.draftsim.engine.SpotlightOwnership.Ownership;
import com.ballknowers.draftsim.store.PlayerGameRepository;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Preference-ordering test for the top players of the night (specs/014 T016, AGENTS.md lesson 1).
 * Structure tests cannot catch a ranking pointed backwards; these assert who beats whom.
 */
class PlayerSpotlightTopOfNightTest {

    static final LocalDate D = LocalDate.parse("2026-10-21");
    static final PlayerSpotlightService.Period NIGHT = PlayerSpotlightService.Period.night(D, 5);
    static final Map<String, Double> SCORING = Map.of("pts", 1.0, "reb", 1.2, "ast", 1.5);
    static final GameScoringService SCORER = new GameScoringService();

    static Player player(String id, Integer yearsExp) {
        return new Player(Math.abs(id.hashCode()), Sport.NBA, id, "Name " + id,
                List.of(Position.PG), "TM", "Active", null, 25, yearsExp);
    }

    static PlayerGameRepository.Row game(String playerId, String gameId, LocalDate date, int pts) {
        return new PlayerGameRepository.Row(Sport.NBA, 2026, 1, playerId, gameId, date, "OPP", true,
                "{\"pts\": " + pts + "}");
    }

    static PeriodRows rows(List<PlayerGameRepository.Row> rows, Map<String, Player> players) {
        return new PeriodRows(rows, PlayerSpotlightService.scoreAll(rows, SCORING, SCORER, players));
    }

    static Map<String, Player> players(Player... ps) {
        Map<String, Player> m = new HashMap<>();
        for (Player p : ps) m.put(p.sleeperId(), p);
        return m;
    }

    static Ownership rostered(String team) {
        return new Ownership(true, team, false, null);
    }

    static List<String> ids(Section s) {
        return s.entries().stream().map(Performance::playerId).toList();
    }

    @Test
    void ordersByPointsDescendingThenPlayerIdAndExcludesTheUnrostered() {
        // Input order is deliberately wrong: a 40-point line must beat a 35-point one.
        List<PlayerGameRepository.Row> night = List.of(
                game("p1", "g1", D, 35),
                game("p2", "g1", D, 40),
                game("p4", "g2", D, 20),
                game("p3", "g2", D, 20),     // exact tie with p4: id order decides
                game("p5", "g3", D, 10),
                game("free", "g3", D, 99));  // unrostered: excluded despite the best score
        Map<String, Player> byId = players(player("p1", 3), player("p2", 3), player("p3", 3),
                player("p4", 3), player("p5", 3), player("free", 3));
        Map<String, Ownership> owners = new HashMap<>();
        for (String id : List.of("p1", "p2", "p3", "p4", "p5")) owners.put(id, rostered("Team " + id));

        Section s = PlayerSpotlightService.topOfNight(NIGHT, rows(night, byId), owners);

        assertNull(s.unavailable());
        assertEquals(List.of("p2", "p1", "p3", "p4", "p5"), ids(s));
        assertEquals(40.0, s.entries().get(0).points());
        assertEquals(35.0, s.entries().get(1).points());
        assertEquals(ids(s), ids(PlayerSpotlightService.topOfNight(NIGHT, rows(night, byId), owners)),
                "two calls on unchanged data order identically");
    }

    @Test
    void limitIsTenAndPointsEqualTheScoringServiceResult() {
        List<PlayerGameRepository.Row> games = new ArrayList<>();
        Map<String, Ownership> owners = new HashMap<>();
        List<Player> ps = new ArrayList<>();
        for (int i = 0; i < 14; i++) {
            String id = "q" + String.format("%02d", i);
            games.add(new PlayerGameRepository.Row(Sport.NBA, 2026, 1, id, "g" + i, D, "OPP", false,
                    "{\"pts\": " + (10 + i) + ", \"reb\": 5, \"ast\": 2, \"stl\": 9}"));
            owners.put(id, rostered("T"));
            ps.add(player(id, 1));
        }
        Section s = PlayerSpotlightService.topOfNight(NIGHT,
                rows(games, players(ps.toArray(new Player[0]))), owners);

        assertEquals(10, s.entries().size());
        Performance best = s.entries().get(0);
        assertEquals("q13", best.playerId());
        // stl is not scored by this map, so it contributes nothing: 23 + 5*1.2 + 2*1.5
        assertEquals(SCORER.score(SCORING, Map.of("pts", 23, "reb", 5, "ast", 2)), best.points());
        assertEquals(32.0, best.points());
        for (int i = 1; i < s.entries().size(); i++) {
            assertTrue(s.entries().get(i - 1).points() > s.entries().get(i).points());
        }
    }

    @Test
    void anUnresolvablePlayerIsSkippedNotCrashed() {
        Section s = PlayerSpotlightService.topOfNight(NIGHT,
                rows(List.of(game("ghost", "g1", D, 50), game("p1", "g1", D, 10)), players(player("p1", 2))),
                Map.of("ghost", rostered("X"), "p1", rostered("Y")));
        assertEquals(List.of("p1"), ids(s));
    }

    @Test
    void noPeriodIsNoPeriod() {
        Section s = PlayerSpotlightService.topOfNight(null, new PeriodRows(List.of(), List.of()), Map.of());
        assertTrue(s.entries().isEmpty());
        assertEquals("NO_PERIOD", s.unavailable());
    }

    @Test
    void aPeriodWithNoRosteredPlayerHavingAGameIsNoRosteredPlayed() {
        Map<String, Player> byId = players(player("free", 3));
        Section s = PlayerSpotlightService.topOfNight(NIGHT,
                rows(List.of(game("free", "g1", D, 30)), byId), Map.of());
        assertTrue(s.entries().isEmpty());
        assertEquals("NO_ROSTERED_PLAYED", s.unavailable());

        Section none = PlayerSpotlightService.topOfNight(NIGHT, new PeriodRows(List.of(), List.of()), Map.of());
        assertEquals("NO_ROSTERED_PLAYED", none.unavailable(), "an empty list always carries a reason");
    }
}
