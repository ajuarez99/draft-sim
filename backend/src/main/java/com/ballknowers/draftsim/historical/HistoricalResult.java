package com.ballknowers.draftsim.historical;

import java.util.List;
import java.util.Map;

/**
 * The frozen results of one finished game (contracts/api.md, spec 053), written once to
 * {@code historical_game.result} at the last pick and never recomputed on read (FR-013).
 *
 * <p>Everything nullable is documented where it is declared; none of it may be built with {@code Map.of}.
 */
public record HistoricalResult(List<Week> weeks, List<Standing> standings, int userFinish,
                               Map<String, List<LineupWeek>> lineups, Hindsight hindsight, Bust biggestBust,
                               Steal bestStealPassedOn) {

    /** @param startDate the Monday of the week's first game date, ISO; null only if no board player played */
    public record Week(int week, String startDate, List<Matchup> matchups) {}

    public record Matchup(int homeSlot, int awaySlot, double homePoints, double awayPoints) {}

    /** Ordered W-L-T, then points for, then a seeded coin (plan F16). */
    public record Standing(int slot, int wins, int losses, int ties, double pointsFor, int place) {}

    /** One team-week's starters. Keyed in {@link #lineups} by the slot as a string; v1 stores the user's own. */
    public record LineupWeek(int week, List<Starter> starters) {}

    /** @param gameDate the date of the single game that was locked for this starter that week */
    public record Starter(long playerId, String slot, double points, String gameDate) {}

    /**
     * The greedy hindsight draft, scored on its own (plan F4). {@code userBeatIt} is true when it scored LESS than
     * the user, in which case the page says so rather than showing a negative gap.
     */
    public record Hindsight(String label, double pointsFor, double userPointsFor, boolean userBeatIt,
                            List<HindsightPick> picks) {}

    public record HindsightPick(int pickNo, HistoricalPlayerCard player, double seasonValue) {}

    /** The user's pick with the largest (season-value rank - pick number), and only if that is positive (F1). */
    public record Bust(int pickNo, HistoricalPlayerCard player, int seasonValueRank, double seasonValue) {}

    /** The best player available at one of the user's picks whom the user never drafted (F19). */
    public record Steal(int pickNo, HistoricalPlayerCard taken, double takenValue,
                        HistoricalPlayerCard passedOn, double passedOnValue) {}
}
