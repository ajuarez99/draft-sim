package com.ballknowers.draftsim.historical;

import java.util.List;
import java.util.Map;

/**
 * One player as the draft room shows him (contracts/api.md, spec 053). Also the board SNAPSHOT entry written to
 * {@code historical_game.board} at creation (plan F9): everything a later call needs to rebuild a
 * {@code BoardEntry} is here, so a re-import of the reference tables can never change a game in progress.
 *
 * <p>Nothing here describes the DRAFTED season (FR-003). {@code team} and {@code age} are the prior season's
 * (null for a rookie), {@code positions} come from the prior season's line (the drafted season's only for a
 * player with no prior line, a documented small leak, plan F5), and {@code prior} is a past season's line.
 *
 * @param rank           1-based board position, ascending: 1 = the best (what the engine reads as adp)
 * @param positionalRank rank within the primary position, in board order
 * @param rookiePick     football only (spec 056 F14): the overall NFL draft pick of a rookie with no prior line;
 *                       null for veterans and always null for basketball. A rookie's {@code prior} stays null.
 * @param rookie         whether the drafted season is this player's first ({@link HistoricalRookies} has the
 *                       rule). NULL means not known, never "no": a board snapshot written before this field
 *                       existed, or a season with no earlier data to compare against.
 */
public record HistoricalPlayerCard(long id, String name, List<String> positions, String team, Integer age,
                                   int rank, int positionalRank, Prior prior, Integer rookiePick, Boolean rookie) {

    /** As the board builder makes it: rookie status is decided afterwards, by {@link HistoricalRookies}. */
    public HistoricalPlayerCard(long id, String name, List<String> positions, String team, Integer age,
                                int rank, int positionalRank, Prior prior, Integer rookiePick) {
        this(id, name, positions, team, age, rank, positionalRank, prior, rookiePick, null);
    }

    public HistoricalPlayerCard withRookie(Boolean value) {
        return new HistoricalPlayerCard(id, name, positions, team, age, rank, positionalRank, prior, rookiePick, value);
    }

    /** Basketball and veterans: no rookie pick. Absence of a pick is not a rule, so this is not a defaulted sport. */
    public HistoricalPlayerCard(long id, String name, List<String> positions, String team, Integer age,
                                int rank, int positionalRank, Prior prior) {
        this(id, name, positions, team, age, rank, positionalRank, prior, null, null);
    }

    /**
     * A prior-season line, counted stats only and excluding bonuses (plan F12).
     *
     * @param seasonLabel  which season these numbers are from, e.g. "1994-95"
     * @param games        games played that season
     * @param perGame      counted counting stats per game (pts, reb, ast, ... whichever the drafted season counts)
     * @param fantasyPoints season total of those counting stats under the drafted season's scoring
     * @param bestOfTwo    the season the RANKING used (the better of the last two with enough games), or null
     *                     when no prior season had enough games and the player is ranked after everyone who did
     * @param totals       football only (spec 056 F16): season totals of the keys that are too rare to show per game
     *                     (st_td, fum_lost, the 2-point keys), nonzero keys only. Never null: {@code {}} when there
     *                     are none, and always {@code {}} for basketball.
     */
    public record Prior(String seasonLabel, int games, Map<String, Double> perGame, double fantasyPoints,
                        String bestOfTwo, Map<String, Double> totals) {
        public Prior {
            perGame = perGame == null ? null : StatOrder.sorted(perGame);   // jsonb reorders keys (finding 11)
            // A snapshot written before V46 has no totals key: it reads as empty, never null.
            totals = totals == null ? new java.util.LinkedHashMap<>() : StatOrder.sorted(totals);
        }
    }
}
