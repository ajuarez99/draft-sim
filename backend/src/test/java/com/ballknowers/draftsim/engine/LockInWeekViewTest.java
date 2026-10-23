package com.ballknowers.draftsim.engine;

import com.ballknowers.draftsim.config.LockInProperties;
import com.ballknowers.draftsim.config.PlayerTrendsProperties;
import com.ballknowers.draftsim.domain.Sport;
import com.ballknowers.draftsim.engine.LockInService.Computed;
import com.ballknowers.draftsim.engine.LockInService.LockInReport;
import com.ballknowers.draftsim.store.LeagueRepository.LeagueRow;
import com.ballknowers.draftsim.store.PlayerGameRepository.SeasonGame;
import com.ballknowers.draftsim.store.PlayerGameRepository.TeamGame;
import com.ballknowers.draftsim.store.RosterWeekPointsRepository.WeekBreakdown;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.OptionalInt;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/** Spec 045 US4 (T026): the past-week view, {@code weekCovered}, and that the key is absent without {@code week} (A8). */
class LockInWeekViewTest {

    private static final LocalDate D0 = LocalDate.of(2025, 10, 20);
    private static final Map<String, Double> PTS = Map.of("pts", 1.0);
    private final LockInProperties props = new LockInProperties(2, 15, 5, List.of(0.0, 0.10, 0.25, 0.50), 3, 4, 60, 15);
    private final PlayerTrendsProperties trends = new PlayerTrendsProperties(6, 5, 5, 3, 14, 10, 20, 0.9);
    private final LockInService service = new LockInService(null, null, null, null, null, null, null, null,
            new GameScoringService(), props, trends);

    private static LeagueRow nba() {
        return new LeagueRow(1L, Sport.NBA, "sleeper-1", "L", 2025, 12, List.of(), 0.0, null, "complete");
    }

    private static SeasonGame game(String pid, String id, int week, int day, double pts) {
        return new SeasonGame(pid, id, D0.plusDays(day), "BBB", Map.of("pts", pts, "sp", 1800.0), week, true);
    }

    /** Week 7: p1 scores 15 then 5, credited 15 (a lock). Week 8: p2 scores 20 then 6, credited 20 (a lock). */
    private Computed computed(OptionalInt mode) {
        List<SeasonGame> games = new ArrayList<>();
        for (int i = 0; i < 6; i++) games.add(game("p1", "a" + i, i + 1, i, 10));
        games.add(game("p1", "g7a", 7, 20, 15));
        games.add(game("p1", "g7b", 7, 21, 5));
        for (int i = 0; i < 6; i++) games.add(game("p2", "b" + i, i + 1, i, 10));
        games.add(game("p2", "g8a", 8, 27, 20));
        games.add(game("p2", "g8b", 8, 28, 6));
        List<TeamGame> teams = List.of(new TeamGame("AAA", "t1", D0, "BBB", Map.of()),
                new TeamGame("BBB", "t1", D0, "AAA", Map.of()));
        List<WeekBreakdown> weeks = List.of(
                new WeekBreakdown(7, 1, 0, "{\"p1\":15.0}", "[\"p1\"]"),
                new WeekBreakdown(8, 1, 0, "{\"p2\":20.0}", "[\"p2\"]"));
        LockInService.Inputs in = new LockInService.Inputs(nba(), mode, PTS, Set.of(7, 8), games, teams, weeks,
                new HashMap<>(Map.of(1, 100L)), Map.of(1, "Roster One"), Map.of(), 0, List.of(), 0.0, ids -> {
                    Map<String, String> out = new HashMap<>();
                    ids.forEach(id -> out.put(id, "Player " + id));
                    return out;
                });
        return service.compute(in);
    }

    @Test
    void theWeekViewIsTheLockListFilteredToThatWeek() {
        Computed c = computed(OptionalInt.of(1));
        assertEquals("MEASURED", c.report().status());
        assertEquals(2, c.allLocks().size());
        LockInReport v = LockInService.viewWeek(c, 7);
        assertEquals(c.allLocks().stream().filter(l -> l.week() == 7).toList(), v.locks());
        assertEquals(1, v.locks().size());
        assertTrue(v.managers().isEmpty());
        assertTrue(v.players().isEmpty());
        assertTrue(v.payoff().values().stream().allMatch(List::isEmpty));
        assertNull(v.live());
        assertEquals(Boolean.TRUE, v.weekCovered());
    }

    @Test
    void weekCoveredIsFalseForAnUncomputedWeekAndForARefusal() {
        Computed c = computed(OptionalInt.of(1));
        LockInReport notComputed = LockInService.viewWeek(c, 3);
        assertEquals(Boolean.FALSE, notComputed.weekCovered());
        assertTrue(notComputed.locks().isEmpty());

        Computed refused = computed(OptionalInt.of(2));          // game mode 2 is not a lock-in league
        assertEquals("GAME_MODE", refused.report().status());
        assertEquals(Boolean.FALSE, LockInService.viewWeek(refused, 7).weekCovered());
    }

    @Test
    void noRideOutEverAppearsInTheWeekView() {
        Computed c = computed(OptionalInt.of(1));
        for (int w : new int[]{7, 8}) {
            LockInService.Lock l = LockInService.viewWeek(c, w).locks().get(0);
            assertFalse(l.beatenByLast(), "the week view lists provable locks, not rides-out");
        }
    }

    @Test
    void weekCoveredIsOmittedFromTheJsonUnlessAWeekWasAsked() {
        ObjectMapper m = new ObjectMapper().findAndRegisterModules();
        Computed c = computed(OptionalInt.of(1));
        JsonNode plain = m.valueToTree(c.report());
        assertFalse(plain.has("weekCovered"), "the no-week report must not carry the key at all");
        JsonNode player = m.valueToTree(LockInService.view(c, "p1"));
        assertFalse(player.has("weekCovered"));
        JsonNode week = m.valueToTree(LockInService.viewWeek(c, 7));
        assertTrue(week.has("weekCovered"));
        assertTrue(week.get("weekCovered").asBoolean());
        JsonNode notCovered = m.valueToTree(LockInService.viewWeek(c, 3));
        assertTrue(notCovered.has("weekCovered"));
        assertFalse(notCovered.get("weekCovered").asBoolean());
    }
}
