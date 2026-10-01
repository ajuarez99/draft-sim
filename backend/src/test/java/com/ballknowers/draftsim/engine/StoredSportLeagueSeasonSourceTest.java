package com.ballknowers.draftsim.engine;

import com.ballknowers.draftsim.domain.Sport;
import com.ballknowers.draftsim.store.SportTrendingRepository;
import org.junit.jupiter.api.Test;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class StoredSportLeagueSeasonSourceTest {

    private static SportTrendingRepository.Snapshot snapshot(Integer season) {
        return new SportTrendingRepository.Snapshot(OffsetDateTime.now(), 24, season, null, null, null, List.of());
    }

    @Test
    void returnsTheStoredLeagueSeason() {
        SportTrendingRepository repo = mock(SportTrendingRepository.class);
        when(repo.read("nfl")).thenReturn(Optional.of(snapshot(2026)));

        assertEquals(Optional.of(2026), new StoredSportLeagueSeasonSource(repo).leagueSeason(Sport.NFL));
    }

    @Test
    void isEmptyWhenTheSportHasNoRow() {
        SportTrendingRepository repo = mock(SportTrendingRepository.class);
        when(repo.read("nba")).thenReturn(Optional.empty());

        assertTrue(new StoredSportLeagueSeasonSource(repo).leagueSeason(Sport.NBA).isEmpty());
    }

    @Test
    void isEmptyWhenTheStoredSeasonIsNull() {
        SportTrendingRepository repo = mock(SportTrendingRepository.class);
        when(repo.read("nba")).thenReturn(Optional.of(snapshot(null)));

        assertTrue(new StoredSportLeagueSeasonSource(repo).leagueSeason(Sport.NBA).isEmpty(),
                "a null season means not known, not a season");
    }
}
