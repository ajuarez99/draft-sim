package com.ballknowers.draftsim.engine;

import com.ballknowers.draftsim.domain.Sport;
import com.ballknowers.draftsim.store.SportTrendingRepository;
import org.springframework.stereotype.Component;

import java.util.Optional;

/**
 * The one {@link SportLeagueSeasonSource}: Sleeper's {@code state.league_season} as stored by
 * the trending fetch ({@code sport_trending_fetch.league_season}, research R8). Empty when the
 * sport has no row yet or the stored season is null -- "not known", so the spotlight falls back
 * to the newest season in its own chain.
 */
@Component
public class StoredSportLeagueSeasonSource implements SportLeagueSeasonSource {

    private final SportTrendingRepository trending;

    public StoredSportLeagueSeasonSource(SportTrendingRepository trending) {
        this.trending = trending;
    }

    @Override
    public Optional<Integer> leagueSeason(Sport sport) {
        return trending.read(sport.code())
                .map(SportTrendingRepository.Snapshot::leagueSeason);
    }
}
