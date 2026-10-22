package com.ballknowers.draftsim.ingest;

import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * specs/042 T003: the next-games rule. The exhibition filter, postponed games, today's
 * {@code pre_game}, and an undated row, plus the ET "today".
 */
class NextGamesTest {

    private static final LocalDate TODAY = LocalDate.of(2026, 10, 21);

    private static SportSchedule.Game g(String id, int week, String date, String status, String home, String away) {
        return new SportSchedule.Game(id, week, date == null ? null : LocalDate.parse(date), status, home, away);
    }

    /** Franchises that each play many games, so a 1-game side is plainly an exhibition team. */
    private static List<SportSchedule.Game> filler() {
        List<SportSchedule.Game> out = new ArrayList<>();
        String[] teams = {"AAA", "BBB", "CCC", "DDD"};
        for (int i = 0; i < 20; i++) {
            out.add(g("f" + i, 1, "2026-10-01", "complete", teams[i % 4], teams[(i + 1) % 4]));
        }
        return out;
    }

    @Test
    void todaysPreGameIsIncludedAndOrderedEarliestFirst() {
        List<SportSchedule.Game> games = filler();
        games.add(g("b", 1, "2026-10-23", "pre_game", "AAA", "BBB"));
        games.add(g("a", 1, "2026-10-21", "pre_game", "CCC", "AAA"));

        List<SportSchedule.Game> next = NextGames.forTeam(games, "AAA", TODAY, 5);

        assertEquals(List.of("a", "b"), next.stream().map(SportSchedule.Game::gameId).toList());
    }

    @Test
    void aPostponedAndASettledGameAreSkipped() {
        List<SportSchedule.Game> games = filler();
        games.add(g("pp", 1, "2026-10-22", "postponed", "AAA", "BBB"));
        games.add(g("done", 1, "2026-10-21", "complete", "AAA", "CCC"));
        games.add(g("ok", 1, "2026-10-25", "pre_game", "AAA", "DDD"));

        List<SportSchedule.Game> next = NextGames.forTeam(games, "AAA", TODAY, 5);

        assertEquals(List.of("ok"), next.stream().map(SportSchedule.Game::gameId).toList());
    }

    @Test
    void theAllStarExhibitionIsNotATeamsNextGame() {
        List<SportSchedule.Game> games = filler();
        games.add(g("asg", 1, "2026-10-22", "pre_game", "AAA", "STP"));   // STP plays one game all season

        assertTrue(NextGames.forTeam(games, "AAA", TODAY, 5).isEmpty());

        games.add(g("asg2", 7, "2026-10-22", "pre_game", "STP", "STR"));
        assertFalse(NextGames.hasCountedGameInWeek(games, "STP", 7));
    }

    @Test
    void aNullDateIsSkippedAndAPastGameIsNotNext() {
        List<SportSchedule.Game> games = filler();
        games.add(g("nodate", 1, null, "pre_game", "AAA", "BBB"));
        games.add(g("yesterday", 1, "2026-10-20", "pre_game", "AAA", "BBB"));

        assertTrue(NextGames.forTeam(games, "AAA", TODAY, 5).isEmpty());
    }

    @Test
    void limitAndNullTeamAreHonoured() {
        List<SportSchedule.Game> games = filler();
        for (int i = 0; i < 4; i++) games.add(g("n" + i, 1, "2026-10-2" + (2 + i), "pre_game", "AAA", "BBB"));

        assertEquals(2, NextGames.forTeam(games, "AAA", TODAY, 2).size());
        assertTrue(NextGames.forTeam(games, null, TODAY, 2).isEmpty());
    }

    @Test
    void aCountedGameInTheWeekCountsEvenIfAlreadyPlayedButAPostponedOneDoesNot() {
        List<SportSchedule.Game> games = filler();
        games.add(g("p", 5, "2026-11-01", "postponed", "AAA", "BBB"));
        assertFalse(NextGames.hasCountedGameInWeek(games, "AAA", 5));

        games.add(g("c", 5, "2026-11-02", "complete", "AAA", "CCC"));
        assertTrue(NextGames.hasCountedGameInWeek(games, "AAA", 5));
        assertFalse(NextGames.hasCountedGameInWeek(games, "DDD", 5));
    }

    @Test
    void todayIsReadInEasternTimeNotUtc() {
        // 01:30 UTC on the 22nd is still the 21st in New York.
        Clock c = Clock.fixed(Instant.parse("2026-10-22T01:30:00Z"), ZoneOffset.UTC);
        assertEquals(LocalDate.of(2026, 10, 21), NextGames.today(c));
    }
}
