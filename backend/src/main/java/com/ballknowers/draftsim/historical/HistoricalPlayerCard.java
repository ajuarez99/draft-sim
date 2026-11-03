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
 */
public record HistoricalPlayerCard(long id, String name, List<String> positions, String team, Integer age,
                                   int rank, int positionalRank, Prior prior) {

    /**
     * A prior-season line, counted stats only and excluding bonuses (plan F12).
     *
     * @param seasonLabel  which season these numbers are from, e.g. "1994-95"
     * @param games        games played that season
     * @param perGame      counted counting stats per game (pts, reb, ast, ... whichever the drafted season counts)
     * @param fantasyPoints season total of those counting stats under the drafted season's scoring
     * @param bestOfTwo    the season the RANKING used (the better of the last two with enough games), or null
     *                     when no prior season had enough games and the player is ranked after everyone who did
     */
    public record Prior(String seasonLabel, int games, Map<String, Double> perGame, double fantasyPoints,
                        String bestOfTwo) {
        public Prior {
            perGame = perGame == null ? null : StatOrder.sorted(perGame);   // jsonb reorders keys (finding 11)
        }
    }
}
