package com.ballknowers.draftsim.historical;

import com.ballknowers.draftsim.domain.Sport;
import com.ballknowers.draftsim.historical.HistoricalDataRepository.SeasonRow;

import java.time.LocalDate;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;

/**
 * Everything the historical draft game needs to know about one sport (spec 056 T009): the league shape, the
 * scoring, how a season is labelled and ranked, where its reference data lives, and what the screen says about it.
 * The generic game flow in {@link HistoricalGameService} reads these and nothing else, so a sport is added by
 * adding an implementation, not by adding a branch to the service.
 *
 * <p>One bean per sport; {@link HistoricalSports} looks them up by {@link #code()}.
 */
public interface HistoricalSport {

    /** "NBA" or "NFL": the value stored in {@code historical_game.sport} and {@code historical_season.sport}. */
    String code();

    /** The engine's own sport, for positions, rules, league settings and positional priors. */
    Sport sport();

    // ---- the league ----------------------------------------------------------------------------------

    int teams();

    int rounds();

    /** 0 is a plain snake (F8). */
    int reversalRound();

    List<String> rosterPositions();

    /** The full scoring map, keyed by Sleeper stat name; a season restricts it to the keys it counts. */
    Map<String, Double> baseScoring();

    /** The stats a card may show and the ranking may count, in no particular display order. */
    List<String> countingKeys();

    // ---- seasons -------------------------------------------------------------------------------------

    int firstOffered();

    int lastOffered();

    /** "1995-96" for basketball, "2007" for football. */
    String label(int seasonStart);

    /** The seasons a user may start (committed AND prior-season lines present), ascending. */
    List<SeasonRow> offeredSeasons();

    /** The same rule over an explicit range (tests and tooling use seasons outside the real one). */
    List<SeasonRow> offeredSeasons(int from, int to);

    Optional<SeasonRow> findSeason(int seasonStart);

    /**
     * The scoring snapshot frozen into a game: the base map restricted to this season's counted keys, keys sorted so
     * the order {@code GameScoringService} sums in never depends on the JVM's hash seed.
     */
    default Map<String, Double> scoringFor(SeasonRow season) {
        Map<String, Double> scoring = new TreeMap<>();
        for (String k : season.countedStats()) {
            if (baseScoring().containsKey(k)) scoring.put(k, baseScoring().get(k));
        }
        return scoring;
    }

    // ---- the board and the replay --------------------------------------------------------------------

    /** The rank-ascending board for one season, built from PRIOR seasons only (FR-004). */
    List<HistoricalPlayerCard> buildBoard(SeasonRow season, long seed, Map<String, Double> scoring);

    /** The played games of the drafted season for the given players, ordered for a deterministic replay. */
    List<? extends WeekStatLine> playedWeeks(int seasonStart, Collection<Long> playerIds);

    /** First game date seen in each week; empty for football (no dates, F4). */
    Map<Integer, LocalDate> weekStarts(int seasonStart);

    // ---- what the screen says ------------------------------------------------------------------------

    /** Where the league settings come from (FR-002). */
    String settingsSource();

    /** What to call the board order. Never "ADP" (FR-009). */
    String rankingLabel();

    /** The data-licence line to show, or null when none is required (basketball). */
    String attribution();

    /**
     * Plain-language caveats about the data behind one season (FR-014), empty when there is nothing to say. Never
     * null. Frozen into the board snapshot at creation, so a re-import cannot change what a game disclosed.
     */
    List<String> dataNotes(SeasonRow season);
}
