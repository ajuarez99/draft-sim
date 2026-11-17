package com.ballknowers.draftsim.historical;

import com.ballknowers.draftsim.historical.HistoricalDataRepository.SeasonRow;
import com.ballknowers.draftsim.historical.HistoricalGameRepository.DailyRow;
import com.ballknowers.draftsim.historical.HistoricalGameRepository.GameRow;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * The Time Machine game of the day (spec 071, claude/time-machine-daily.md): one season per sport per day, the
 * same for everyone, one game per person.
 *
 * <p>The day is a calendar date in {@link #ZONE} (Allan, 2026-11-14: midnight US Eastern). Every method that
 * depends on "today" also exists with the date passed in, so a test never has to move the clock or touch the real
 * day's row.
 *
 * <p>Not {@code @Transactional} itself: the puzzle insert is a single idempotent statement, and the game is
 * created in {@link HistoricalGameService}'s own transaction, so a lost race on the unique index rolls back only
 * that and this class can read the winner afterwards.
 */
@Service
public class HistoricalDailyService {

    public static final ZoneId ZONE = ZoneId.of("America/New_York");

    private final HistoricalGameRepository games;
    private final HistoricalGameService gameService;
    private final HistoricalSports sports;
    private final Clock clock;

    public HistoricalDailyService(HistoricalGameRepository games, HistoricalGameService gameService,
                                  HistoricalSports sports, Clock clock) {
        this.games = games;
        this.gameService = gameService;
        this.sports = sports;
        this.clock = clock;
    }

    /**
     * One sport's game of the day, as one player sees it. Mirrored in {@code web/src/api.ts}.
     *
     * @param date        the puzzle's ISO date (a day in US Eastern time)
     * @param seasonLabel the season everyone drafts today
     * @param gameId      the caller's game for today, or null if not started
     * @param gameStatus  DRAFTING or COMPLETE, or null if not started
     * @param draftScore  the caller's score once COMPLETE; null before, and for a result with no hindsight total
     * @param streak      finished days in a row, ending today or (if today is not finished) yesterday
     * @param bestStreak  the caller's longest run ever
     * @param nextAt      the ISO-8601 instant the next day's game starts (a string, so the wire form never
     *                    depends on how Jackson is configured to write dates)
     */
    public record DailyView(String sport, String date, String seasonLabel, Long gameId, String gameStatus,
                            Integer draftScore, int streak, int bestStreak, String nextAt) {}

    /**
     * Yesterday's game of the day for one sport, as anyone may read it (spec 074, no identity). {@code seasonLabel}
     * and {@code slug} (the season page's address segment, which is the label) are null when no puzzle was dealt for
     * that day. Mirrored in {@code web/src/api.ts}.
     */
    public record YesterdayDaily(String sport, String date, String seasonLabel, String slug) {}

    /** What {@link #start} did: the game, and whether this call created it. */
    public record Started(HistoricalGameState state, boolean created) {}

    public LocalDate today() {
        return LocalDate.now(clock.withZone(ZONE));
    }

    /**
     * Yesterday's puzzle per sport, in the registry's order. READ-ONLY: it never deals a puzzle (a day nobody asked
     * about stays empty), unlike {@link #status}, which deals today's.
     */
    public List<YesterdayDaily> yesterday() {
        LocalDate date = today().minusDays(1);
        List<YesterdayDaily> out = new ArrayList<>();
        for (HistoricalSport sport : sports.all()) {
            Optional<DailyRow> puzzle = games.findDailyPuzzle(sport.code(), date);
            String label = puzzle.map(p -> sport.label(p.seasonStart())).orElse(null);
            out.add(new YesterdayDaily(sport.code(), date.toString(), label, label));
        }
        return out;
    }

    /** Every sport that has seasons to offer, in the registry's order. A sport with none is left out. */
    public List<DailyView> status(String sleeperUserId) {
        return status(sleeperUserId, today());
    }

    public List<DailyView> status(String sleeperUserId, LocalDate date) {
        List<DailyView> out = new ArrayList<>();
        for (HistoricalSport sport : sports.all()) {
            Optional<DailyRow> puzzle = puzzle(sport, date);
            if (puzzle.isEmpty()) continue;
            out.add(view(sleeperUserId, sport, puzzle.get()));
        }
        return out;
    }

    /**
     * Starts the caller's game of the day for one sport, or returns the one already started.
     *
     * @throws IllegalArgumentException an unknown sport (400)
     * @throws IllegalStateException    the sport has no seasons to offer (409)
     */
    public Started start(String sleeperUserId, String sportCode) {
        return start(sleeperUserId, sportCode, today());
    }

    public Started start(String sleeperUserId, String sportCode, LocalDate date) {
        HistoricalSport sport = sports.get(sportCode);
        DailyRow puzzle = puzzle(sport, date).orElseThrow(() ->
                new IllegalStateException("no historical " + sport.code() + " seasons have been imported yet"));

        Optional<GameRow> mine = games.findDaily(sleeperUserId, sport.code(), date);
        if (mine.isPresent()) return existing(mine.get(), sleeperUserId);
        try {
            return new Started(gameService.createDaily(sleeperUserId, sport.code(), puzzle.seasonStart(),
                    puzzle.rngSeed(), date), true);
        } catch (DuplicateKeyException lostTheRace) {
            // Two tabs pressed Play together: the index let one through, and that one is the day's game.
            return existing(games.findDaily(sleeperUserId, sport.code(), date).orElseThrow(), sleeperUserId);
        }
    }

    private Started existing(GameRow row, String sleeperUserId) {
        return new Started(gameService.get(row.id(), sleeperUserId).orElseThrow(), false);
    }

    /** The day's dealt puzzle, dealing it first if nobody has asked yet. Empty when the sport offers no season. */
    private Optional<DailyRow> puzzle(HistoricalSport sport, LocalDate date) {
        Optional<DailyRow> found = games.findDailyPuzzle(sport.code(), date);
        if (found.isPresent()) return found;
        List<SeasonRow> offered = sport.offeredSeasons();
        if (offered.isEmpty()) return Optional.empty();
        DailyPuzzle.Deal deal = DailyPuzzle.deal(sport.code(), date, offered, sport.seasonSkew(),
                games.recentDailySeasons(sport.code(), date, DailyPuzzle.NO_REPEAT_DAYS));
        games.insertDailyPuzzleIfAbsent(new DailyRow(sport.code(), date, deal.seasonStart(), deal.seed()));
        return games.findDailyPuzzle(sport.code(), date);
    }

    private DailyView view(String sleeperUserId, HistoricalSport sport, DailyRow puzzle) {
        LocalDate date = puzzle.dailyDate();
        Optional<GameRow> mine = games.findDaily(sleeperUserId, sport.code(), date);
        Integer score = mine.filter(g -> "COMPLETE".equals(g.status())).map(g -> games.draftScore(g.id()))
                .orElse(null);
        DailyStreak streak = DailyStreak.of(games.completedDailyDates(sleeperUserId, sport.code()), date);
        Instant nextAt = date.plusDays(1).atStartOfDay(ZONE).toInstant();
        return new DailyView(sport.code(), date.toString(), sport.label(puzzle.seasonStart()),
                mine.map(GameRow::id).orElse(null), mine.map(GameRow::status).orElse(null), score,
                streak.current(), streak.best(), nextAt.toString());
    }
}
