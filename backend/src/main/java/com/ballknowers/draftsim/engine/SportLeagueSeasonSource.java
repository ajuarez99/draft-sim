package com.ballknowers.draftsim.engine;

import com.ballknowers.draftsim.domain.Sport;

import java.util.Optional;

/**
 * The sport's current {@code league_season} as Sleeper last reported it, for the player
 * spotlight's current-season-only rule (specs/014-home-player-spotlight, research R8).
 *
 * <p>A seam rather than a direct read so the service can be tested without a database.
 * {@link StoredSportLeagueSeasonSource} is the one implementation, reading
 * {@code sport_trending_fetch.league_season}. Empty always means "not known", never "no
 * current season"; the service then falls back to "newest season in its own chain".
 */
public interface SportLeagueSeasonSource {

    Optional<Integer> leagueSeason(Sport sport);
}
