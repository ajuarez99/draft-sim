package com.ballknowers.draftsim.ingest;

import com.ballknowers.draftsim.engine.ScheduleGridService;

import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.Comparator;
import java.util.List;
import java.util.function.Predicate;

/**
 * "A team's next games" from the stored schedule (specs/042-fantasypros-design-features, T003).
 * Pure: the games are an argument, so it is testable against a handful of hand-built rows.
 *
 * <p><b>No rule is copied.</b> What counts as a scheduled game is {@link SportSchedule#counts}, what is
 * still to be played is the complement of {@link SportSchedule#isSettled}, and the All-Star exhibition is
 * {@link ScheduleGridService#exhibitionGames} (review F5a). A new status or a renamed franchise is handled
 * in those places once.
 *
 * <p><b>"Today" is {@code America/New_York}</b>, unlike {@code PlayerGameIngestService}, which asks UTC.
 * The schedule's {@code game_date} is a date with no time, in Sleeper's US-date convention (assumed, not
 * verified; checked in live verification), and an 8pm ET game on the 21st is the 22nd in UTC. Reading
 * UTC would drop tonight's game from "next game" for the evening it is played (F5c). A game played earlier
 * today can still read {@code pre_game} until the next schedule refresh, so callers show the schedule's
 * fetched-at.
 */
public final class NextGames {

    /** Sleeper's NBA and NFL schedule dates are US dates, so "today" is read in Eastern time. */
    public static final ZoneId TODAY_ZONE = ZoneId.of("America/New_York");

    private NextGames() {}

    /** The date in {@link #TODAY_ZONE} on {@code clock}. */
    public static LocalDate today(Clock clock) {
        return LocalDate.now(clock.withZone(TODAY_ZONE));
    }

    /**
     * The team's counted, unsettled games dated {@code today} or later, earliest first (then by game id),
     * at most {@code limit}. A game with no date can't be placed and is skipped; an exhibition side's
     * game is not the team's game.
     */
    public static List<SportSchedule.Game> forTeam(List<SportSchedule.Game> games, String team,
                                                   LocalDate today, int limit) {
        if (team == null || games == null || limit <= 0) return List.of();
        Predicate<SportSchedule.Game> exhibition = ScheduleGridService.exhibitionGames(games);
        return games.stream()
                .filter(g -> plays(g, team))
                .filter(g -> SportSchedule.counts(g.status()) && !SportSchedule.isSettled(g.status()))
                .filter(g -> g.date() != null && !g.date().isBefore(today))
                .filter(g -> !exhibition.test(g))
                .sorted(Comparator.comparing(SportSchedule.Game::date).thenComparing(SportSchedule.Game::gameId))
                .limit(limit)
                .toList();
    }

    /**
     * Whether the team has a counted game in the week at all, played or not. This is the meaning of
     * {@code ScheduleGridService.teamsWithoutGame} (specs/030 S5): under one-credited-game a starter whose
     * team doesn't play scores nothing, while "games left" is not value (F6).
     */
    public static boolean hasCountedGameInWeek(List<SportSchedule.Game> games, String team, int week) {
        if (team == null || games == null) return false;
        Predicate<SportSchedule.Game> exhibition = ScheduleGridService.exhibitionGames(games);
        return games.stream().anyMatch(g -> g.week() == week && plays(g, team)
                && SportSchedule.counts(g.status()) && !exhibition.test(g));
    }

    private static boolean plays(SportSchedule.Game g, String team) {
        return team.equals(g.home()) || team.equals(g.away());
    }
}
