package com.ballknowers.draftsim.engine;

import com.ballknowers.draftsim.engine.NbaGameLines.Line;
import com.ballknowers.draftsim.store.PlayerGameRepository.SeasonGame;
import com.ballknowers.draftsim.store.PlayerGameRepository.TeamGame;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/** The join rules of data-model "NbaGameLines" (spec 022 T008), over synthetic rows. */
class NbaGameLinesTest {

    private static final LocalDate BASE = LocalDate.parse("2025-11-01");

    private final List<SeasonGame> games = new ArrayList<>();
    private final List<TeamGame> teams = new ArrayList<>();

    private void game(String gid, int day, String home, String away) {
        teams.add(new TeamGame(home, gid, BASE.plusDays(day), away, Map.of("sp", 14400, "pts", 100)));
        teams.add(new TeamGame(away, gid, BASE.plusDays(day), home, Map.of("sp", 14400, "pts", 90)));
    }

    private void play(String pid, String gid, int day, String opp, Boolean isAway, Object sp) {
        games.add(new SeasonGame(pid, gid, BASE.plusDays(day), opp, Map.of("sp", sp, "pts", 10), 3, isAway));
    }

    private NbaGameLines lines() {
        return NbaGameLines.of(games, teams, java.util.Set.of());
    }

    @Test
    void teamRowsAreNeverLines() {
        game("g1", 0, "AAA", "BBB");
        play("TEAM_AAA", "g1", 0, "BBB", false, 14400);
        play("p1", "g1", 0, "BBB", false, 1800);
        assertEquals(Set.of("p1"), lines().byPlayer().keySet());
    }

    @Test
    void theBareTeamRowAndAnAllStarGameAreDropped() {
        game("g1", 0, "AAA", "BBB");
        teams.add(new TeamGame("", "asg", BASE.plusDays(5), "", Map.of("sp", 14400)));
        play("p1", "g1", 0, "BBB", false, 1800);
        play("p1", "asg", 5, "USA", null, 1200);      // opponent is not a season team code
        play("p2", "asg", 5, null, null, 1200);       // no opponent at all
        NbaGameLines l = lines();
        assertEquals(1, l.byPlayer().get("p1").size());
        assertFalse(l.byPlayer().containsKey("p2"));
        assertEquals(Set.of("AAA", "BBB"), l.teamCodes());
        assertFalse(l.teamGames().containsKey(""));
    }

    @Test
    void aGameWithNoSecondsPlayedIsNotAGame() {
        game("g1", 0, "AAA", "BBB");
        play("p1", "g1", 0, "BBB", false, 0);
        play("p2", "g1", 0, "BBB", false, 1800);
        assertFalse(lines().byPlayer().containsKey("p1"));
        assertTrue(lines().byPlayer().containsKey("p2"));
    }

    @Test
    void teamRowIsTheSameGameRowThatIsNotTheOpponent() {
        game("g1", 0, "AAA", "BBB");
        play("p1", "g1", 0, "BBB", false, 1800);       // plays for AAA against BBB
        Line line = lines().byPlayer().get("p1").get(0);
        assertEquals("AAA", line.teamRow().code());
        assertEquals("BBB", line.oppRow().code());
        assertEquals("AAA", line.team());
        assertEquals("BBB", line.opponent());
        assertEquals(30.0, line.minutes(), 0.0);
        assertEquals(3, line.week());
    }

    @Test
    void linesAreOrderedByDateThenGameId() {
        game("g-b", 2, "AAA", "BBB");
        game("g-a", 2, "AAA", "CCC");
        game("g-z", 0, "AAA", "BBB");
        play("p1", "g-b", 2, "BBB", false, 1800);
        play("p1", "g-a", 2, "CCC", false, 1800);
        play("p1", "g-z", 0, "BBB", false, 1800);
        assertEquals(List.of("g-z", "g-a", "g-b"),
                lines().byPlayer().get("p1").stream().map(Line::gameId).toList());
    }

    @Test
    void aTradedPlayerLinesCarryEachGameOwnTeam() {
        game("g1", 0, "AAA", "BBB");
        game("g2", 3, "CCC", "BBB");
        play("p1", "g1", 0, "BBB", false, 1800);
        play("p1", "g2", 3, "BBB", false, 1800);
        List<Line> l = lines().byPlayer().get("p1");
        assertEquals(List.of("AAA", "CCC"), l.stream().map(Line::team).toList());
    }

    @Test
    void isHomeIsNotIsAwayAndNullWhenUnknown() {
        game("g1", 0, "AAA", "BBB");
        game("g2", 1, "AAA", "BBB");
        game("g3", 2, "AAA", "BBB");
        play("p1", "g1", 0, "BBB", true, 1800);
        play("p1", "g2", 1, "BBB", false, 1800);
        play("p1", "g3", 2, "BBB", null, 1800);
        List<Line> l = lines().byPlayer().get("p1");
        assertEquals(Boolean.FALSE, l.get(0).isHome());
        assertEquals(Boolean.TRUE, l.get(1).isHome());
        assertNull(l.get(2).isHome());
    }

    @Test
    void aGameWithoutTeamRowsKeepsNullTeamAndRows() {
        teams.add(new TeamGame("BBB", "g9", BASE, "AAA", Map.of("sp", 14400)));   // only the opponent row
        play("p1", "g9", 0, "BBB", false, 1800);
        Line line = lines().byPlayer().get("p1").get(0);
        assertNull(line.teamRow());
        assertNull(line.team());
        assertEquals("BBB", line.oppRow().code());
    }

    @Test
    void theResultIsImmutable() {
        game("g1", 0, "AAA", "BBB");
        play("p1", "g1", 0, "BBB", false, 1800);
        NbaGameLines l = lines();
        assertThrows(UnsupportedOperationException.class, () -> l.byPlayer().put("x", List.of()));
        assertThrows(UnsupportedOperationException.class, () -> l.byPlayer().get("p1").clear());
        assertThrows(UnsupportedOperationException.class, () -> l.byPlayer().get("p1").sort(null));
        assertThrows(UnsupportedOperationException.class, () -> l.teamGames().put("x", List.of()));
        assertThrows(UnsupportedOperationException.class, () -> l.teamGames().get("AAA").clear());
        assertThrows(UnsupportedOperationException.class, () -> l.teamCodes().add("x"));
    }

    @Test
    void anExcludedGameDropsTheLineAndBothTeamRowsAndNothingElse() {
        game("cup", 0, "AAA", "BBB");
        game("reg", 1, "AAA", "BBB");
        play("p1", "cup", 0, "BBB", false, 14400);
        play("p1", "reg", 1, "BBB", false, 14400);
        play("p2", "cup", 0, "AAA", true, 14400);
        NbaGameLines l = NbaGameLines.of(games, teams, Set.of("cup"));
        assertEquals(List.of("reg"), l.byPlayer().get("p1").stream().map(Line::gameId).toList());
        assertNull(l.byPlayer().get("p2"), "his only game was the excluded one");
        assertEquals(1, l.teamGames().get("AAA").size(), "team game count returns to the official figure");
        assertEquals(1, l.teamGames().get("BBB").size());
        assertEquals("reg", l.teamGames().get("AAA").get(0).gameId());
        // the same rows with nothing excluded keep everything
        NbaGameLines all = NbaGameLines.of(games, teams, Set.of());
        assertEquals(2, all.byPlayer().get("p1").size());
        assertEquals(2, all.teamGames().get("AAA").size());
        assertNotNull(all.byPlayer().get("p2"));
    }

    @Test
    void anExcludedIdThatMatchesNoGameChangesNothing() {
        game("g1", 0, "AAA", "BBB");
        play("p1", "g1", 0, "BBB", false, 14400);
        NbaGameLines l = NbaGameLines.of(games, teams, Set.of("no-such-game"));
        assertEquals(1, l.byPlayer().get("p1").size());
        assertEquals(1, l.teamGames().get("AAA").size());
    }
}
