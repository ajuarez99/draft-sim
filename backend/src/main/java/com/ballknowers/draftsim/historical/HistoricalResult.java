package com.ballknowers.draftsim.historical;

import java.util.List;
import java.util.Map;

/**
 * The frozen results of one finished game (contracts/api.md, spec 053), written once to
 * {@code historical_game.result} at the last pick and never recomputed on read (FR-013).
 *
 * <p>Everything nullable is documented where it is declared; none of it may be built with {@code Map.of}.
 *
 * <p>Spec 063 appended {@code format}, {@code playoffs}, {@code finalPlaces} and {@code userOutcome}. A result
 * stored before it has none of them (they read back null), and {@code format == null} is the one test for "old
 * format". {@code standings} is the regular season only; {@code userFinish} is the user's final place after the
 * playoffs (the standings rank in an old result or when there are no playoffs).
 *
 * @param format      null only in an old stored result; always set by the replay
 * @param playoffs    null when the season had no playoff weeks (and in an old result)
 * @param finalPlaces every team ordered by final place; null only in an old stored result
 * @param userOutcome CHAMPION, LOST_FINAL, LOST_SEMIFINAL, LOST_ROUND_ONE, MISSED_PLAYOFFS or NO_PLAYOFFS; null
 *                    only in an old stored result
 * @param pickFinishes spec 065: each of the user's picks, where he was drafted at his position and where he
 *                    finished at it, in pick order. Null in a result stored before spec 065 (a stored result is
 *                    never recomputed, so those games simply do not have it).
 */
public record HistoricalResult(List<Week> weeks, List<Standing> standings, int userFinish,
                               Map<String, List<LineupWeek>> lineups, Hindsight hindsight, Bust biggestBust,
                               Steal bestStealPassedOn, Format format, Playoffs playoffs,
                               List<FinalPlace> finalPlaces, String userOutcome,
                               List<PickFinish> pickFinishes) {

    public static final String CHAMPION = "CHAMPION";
    public static final String LOST_FINAL = "LOST_FINAL";
    public static final String LOST_SEMIFINAL = "LOST_SEMIFINAL";
    public static final String LOST_ROUND_ONE = "LOST_ROUND_ONE";
    public static final String MISSED_PLAYOFFS = "MISSED_PLAYOFFS";
    public static final String NO_PLAYOFFS = "NO_PLAYOFFS";

    /**
     * @param startDate the Monday of the week's first game date, ISO; null only if no board player played
     * @param phase     "REGULAR" or "PLAYOFF"; null only in an old stored result. A playoff week holds only that
     *                  week's playoff matchups, home = the better seed.
     */
    public record Week(int week, String startDate, List<Matchup> matchups, String phase) {}

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

    // ---- spec 063: the season's shape and the bracket ---------------------------------------------------

    /** Built from {@link SeasonFormat#toResult()}. {@code playoffWeeks} is empty when there is no bracket. */
    public record Format(int version, int seasonWeeks, int configuredRegularSeasonWeeks, int regularSeasonWeeks,
                         List<Integer> playoffWeeks, int unusedWeeks, boolean shortened) {}

    /** The six-team bracket. Seeds are the regular-season standings places 1 to 6. */
    public record Playoffs(List<Seed> seeds, List<Round> rounds, int championSlot) {}

    public record Seed(int seed, int slot) {}

    /** @param name ROUND_ONE, SEMIFINAL or FINAL; {@code byeSlots} is empty after round one */
    public record Round(int round, int week, String name, List<Integer> byeSlots, List<PlayoffMatchup> matchups) {}

    /**
     * One playoff game. {@code high} is the better (lower-numbered) seed. {@code winnerSlot} is the higher score;
     * level to the hundredth, the high seed advances and {@code decidedBySeed} is true.
     */
    public record PlayoffMatchup(int highSeed, int lowSeed, int highSlot, int lowSlot, double highPoints,
                                 double lowPoints, int winnerSlot, boolean decidedBySeed) {}

    /** @param outcome uses the {@code userOutcome} vocabulary, for every team */
    public record FinalPlace(int slot, int place, String outcome) {}

    // ---- spec 065: drafted as, finished as -------------------------------------------------------------

    /**
     * One of the user's picks, at his primary position (the first one on his card).
     *
     * @param draftedPositionRank 1 = the first player at this position taken in THIS game's draft, by any team
     *                            ("the second RB off the board" is 2)
     * @param finishPositionRank  1 = the highest season value among every player at this position on the game's
     *                            board, drafted or not. Level values share a rank (1 + how many scored strictly
     *                            more), so players who never played all share the last one.
     * @param positionPlayers     how many players at this position are on the board: the "of N" for the finish
     * @param seasonValue         the sum of his weekly scores over the whole real season, as in the comparisons
     * @param weeksPlayed         weeks of the real season in which he has a box score. There is no injury data; a
     *                            low number says only that he did not play.
     */
    public record PickFinish(int pickNo, HistoricalPlayerCard player, String position, int draftedPositionRank,
                             int finishPositionRank, int positionPlayers, double seasonValue, int weeksPlayed) {}
}
