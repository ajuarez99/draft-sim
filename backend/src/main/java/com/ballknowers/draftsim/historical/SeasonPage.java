package com.ballknowers.draftsim.historical;

import java.util.List;
import java.util.Map;

/**
 * One season, summarised for the public Redraft season page (spec 074, {@code GET /api/history/public/seasons/{sport}/{slug}}).
 * Computed once per season per JVM by {@link SeasonSummaryService} from the same board builder and player-week
 * scoring a game uses, and cached; nothing here describes any player's game. Mirrored field for field, in this order,
 * in {@code web/src/api.ts}.
 *
 * <p>Everything is "among the players who appeared that season": the board holds only players with at least one game,
 * so a star who missed the whole year is not on it ({@code boardPlayers} says how many are).
 *
 * @param slug            the address segment, equal to {@code label} ("2016-17", "2007")
 * @param prevSlug        the previous offered season's slug; null for the first
 * @param nextSlug        the next offered season's slug; null for the last
 * @param teams           league size of the game this season is drafted in
 * @param rounds          rounds of that draft; also the size of {@code dreamRoster}
 * @param scoringNote     {@link HistoricalSport#settingsSource()}
 * @param pointsNote      {@link HistoricalSport#pointsNote()}: what a "points" figure here is. For basketball a
 *                        player's week is his best single game, so it is NOT a season fantasy total
 * @param rankingLabel    what the going-in order is called; never "ADP"
 * @param dataNotes       never null
 * @param excludedStats   stats the era did not track, with the reason, in canonical stat order (empty for football)
 * @param statCoverage    for each tracked stat, the share of box-score rows carrying it, in canonical stat order
 * @param attribution     the data-licence line; null for basketball
 * @param boardPlayers    every player on the board (appeared at least once that season)
 * @param rankedPlayers   how many of them have a going-in rank; the rest are unranked (their order is a seed's)
 * @param topOverall      the 12 best by season points
 * @param topByPosition   the 5 best at each position that has players, by PRIMARY position, in the sport's position
 *                        order. In these rows {@code finishRank} is the rank WITHIN that position, the same number a
 *                        game reports as {@code finishPositionRank}; {@code goingInRank} stays the overall board rank
 * @param busts           up to 5: going in among the first five rounds' worth of players, finishing further down
 * @param steals          up to 5: finishing among the first five rounds' worth, ranked going in further down
 * @param unrankedLeftOut unranked players who finished among the first five rounds' worth: no going-in rank, so they
 *                        are in neither list above
 * @param dreamRoster     the roster a greedy search found, best first, no draft order and no slots (a weekly lineup
 *                        is re-seated every week)
 * @param dreamRosterPoints the best-ball team total ({@code TeamScorer.season}) of that roster, not the sum of its
 *                        players' season points
 */
public record SeasonPage(String sport, int seasonStart, String label, String slug, String prevSlug, String nextSlug,
                         int teams, int rounds, List<String> rosterPositions, String scoringNote, String pointsNote,
                         String rankingLabel, List<String> dataNotes, Map<String, String> excludedStats,
                         Map<String, Double> statCoverage, String attribution, int boardPlayers, int rankedPlayers,
                         List<Finisher> topOverall, Map<String, List<Finisher>> topByPosition, List<Mover> busts,
                         List<Mover> steals, int unrankedLeftOut, List<DreamPlayer> dreamRoster,
                         double dreamRosterPoints) {

    /**
     * @param team        the PRIOR season's team the board card shows; null for a rookie
     * @param finishRank  1 + how many players scored strictly more, so level scores share a rank
     * @param points      season points under this season's scoring ({@code pointsNote} says what that is)
     * @param weeksPlayed weeks with a game
     * @param goingInRank the going-in board rank; null when unranked
     */
    public record Finisher(long playerId, String name, List<String> positions, String team, int finishRank,
                           double points, int weeksPlayed, Integer goingInRank) {}

    /** A bust or a steal: both ranks are overall among the board. */
    public record Mover(long playerId, String name, List<String> positions, String team, int goingInRank,
                        int finishRank, double points, int weeksPlayed) {}

    public record DreamPlayer(long playerId, String name, List<String> positions, double points) {}
}
