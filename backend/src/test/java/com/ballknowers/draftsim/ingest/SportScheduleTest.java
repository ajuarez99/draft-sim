package com.ballknowers.draftsim.ingest;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.io.InputStream;
import java.time.LocalDate;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * specs/017-nba-schedule-grid T005: the lifted schedule parser. Both payload shapes,
 * de-duplication by game_id (last wins), and which statuses count as a scheduled game.
 */
class SportScheduleTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private static List<Map<String, Object>> fixture(String name) throws Exception {
        try (InputStream in = SportScheduleTest.class.getResourceAsStream("/sleeper/" + name)) {
            assertNotNull(in, "missing fixture " + name);
            return MAPPER.readValue(in, new TypeReference<>() {});
        }
    }

    private static Map<String, Object> row(String id, int week, String date, String status, Object home, Object away) {
        Map<String, Object> g = new HashMap<>();
        g.put("game_id", id);
        g.put("week", week);
        g.put("date", date);
        g.put("status", status);
        g.put("home", home);
        g.put("away", away);
        return g;
    }

    @Test
    void parsesTheNestedHomeAwayShapeOfTheRealNbaFixture() throws Exception {
        List<Map<String, Object>> raw = fixture("nba-schedule-2025.json");

        SportSchedule s = SportSchedule.parse(raw);

        assertEquals(raw.size(), s.games().size(), "the fixture has no repeated game_id");
        for (SportSchedule.Game g : s.games()) {
            assertNotNull(g.home(), "home team on " + g.gameId());
            assertNotNull(g.away(), "away team on " + g.gameId());
        }
        Map<String, Object> first = raw.get(0);
        @SuppressWarnings("unchecked")
        String expectedHome = (String) ((Map<String, Object>) first.get("home")).get("team");
        SportSchedule.Game parsed = s.games().stream()
                .filter(g -> g.gameId().equals(String.valueOf(first.get("game_id")))).findFirst().orElseThrow();
        assertEquals(expectedHome, parsed.home());
        assertEquals(((Number) first.get("week")).intValue(), parsed.week());
    }

    @Test
    void parsesTheBareStringShape() {
        SportSchedule s = SportSchedule.parse(List.of(row("g1", 4, "2025-10-05", "complete", "KC", "BUF")));

        SportSchedule.Game g = s.games().getFirst();
        assertEquals("KC", g.home());
        assertEquals("BUF", g.away());
        assertEquals(LocalDate.of(2025, 10, 5), g.date());
    }

    @Test
    void aRepeatedGameIdIsDeduplicatedAndTheLastRowWins() {
        SportSchedule s = SportSchedule.parse(List.of(
                row("g1", 1, "2025-10-01", "pre_game", "AAA", "BBB"),
                row("g2", 1, "2025-10-01", "pre_game", "CCC", "DDD"),
                row("g1", 2, "2025-10-09", "complete", "EEE", "FFF")));

        List<SportSchedule.Game> games = s.games();

        assertEquals(2, games.size());
        SportSchedule.Game g1 = games.stream().filter(g -> g.gameId().equals("g1")).findFirst().orElseThrow();
        assertEquals(2, g1.week());
        assertEquals("EEE", g1.home());
        assertEquals("complete", g1.status());
    }

    @Test
    void countsExcludesOnlyPostponedAndCanceled() {
        assertFalse(SportSchedule.counts("postponed"));
        assertFalse(SportSchedule.counts("canceled"));
        assertTrue(SportSchedule.counts("complete"));
        assertTrue(SportSchedule.counts("pre_game"));
        assertTrue(SportSchedule.counts("in_game"));
        assertTrue(SportSchedule.counts(null), "a game with no status is still a scheduled game");
    }
}
