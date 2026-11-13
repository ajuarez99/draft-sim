package com.ballknowers.draftsim.historical;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Who is a rookie on a Time Machine board. The live draft rooms read Sleeper's {@code years_exp}
 * ({@code Player.isRookie()}); that figure is today's and says nothing about 1996, so a past season is
 * decided from the historical tables themselves.
 *
 * <p>The rule, measured against the imported data on 2026-11-13:
 * <ul>
 *   <li>A player is a rookie when he has no season line before the drafted season.</li>
 *   <li>That is only knowable when the board has somebody who DOES have an earlier line. In the first
 *       season a source covers, everyone is "new" (basketball 1969: 171 of 171), so nobody is marked and the
 *       answer is null, not false.</li>
 *   <li>Football also has the real draft table, and it wins: a drafted player is a rookie in the season he was
 *       drafted and in no other, which keeps a player who sat his first year from reading as a rookie in his
 *       second. Only an undrafted player falls back to the first-line rule.</li>
 * </ul>
 *
 * <p>Known wrong cases, accepted: a player whose earlier career is outside the data (the 1976-77 ABA arrivals
 * read as first-season, 110 that year against a usual 45 to 80), and a veteran who missed every season the data
 * holds before this one.
 */
public final class HistoricalRookies {

    private HistoricalRookies() {}

    /**
     * @param cards        the built board
     * @param veterans     ids with at least one season line before the drafted season
     * @param draftSeasons football: player id to the season he was drafted in; empty for basketball
     * @param season       the drafted season
     */
    public static List<HistoricalPlayerCard> mark(List<HistoricalPlayerCard> cards, Set<Long> veterans,
                                                  Map<Long, Integer> draftSeasons, int season) {
        boolean comparable = false;
        for (HistoricalPlayerCard c : cards) {
            if (veterans.contains(c.id())) {
                comparable = true;
                break;
            }
        }
        List<HistoricalPlayerCard> out = new ArrayList<>(cards.size());
        for (HistoricalPlayerCard c : cards) {
            Integer drafted = draftSeasons.get(c.id());
            Boolean rookie;
            if (drafted != null) rookie = drafted == season;
            else rookie = comparable ? !veterans.contains(c.id()) : null;
            out.add(c.withRookie(rookie));
        }
        return out;
    }
}
