package com.ballknowers.draftsim.engine;

import com.ballknowers.draftsim.domain.Sport;
import com.ballknowers.draftsim.store.LeagueRepository;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.OptionalInt;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Spec 046 live verification: the ADP compare page's season ignores leagues implausibly far in the future.
 * Measured first on the scratch DB, where an IT fixture league at season 2099 made every 2026 capture refuse.
 */
class AdpSeasonTest {

    private static final Clock OCT_2026 = Clock.fixed(Instant.parse("2026-10-23T12:00:00Z"), ZoneOffset.UTC);

    private static LeagueRepository.LeagueRow league(long id, Sport sport, int season) {
        return new LeagueRepository.LeagueRow(id, sport, "s" + id, "L" + id, season, 12, List.of(), 1.0, null, null);
    }

    private static LeagueRepository repo(LeagueRepository.LeagueRow... rows) {
        LeagueRepository leagues = mock(LeagueRepository.class);
        when(leagues.all()).thenReturn(List.of(rows));
        return leagues;
    }

    @Test
    void aStrayFarFutureLeagueDoesNotMoveTheSeason() {
        var leagues = repo(league(1, Sport.NBA, 2026), league(2, Sport.NBA, 2025), league(3, Sport.NBA, 2099));
        assertThat(AdpSeason.current(leagues, Sport.NBA, OCT_2026)).isEqualTo(OptionalInt.of(2026));
    }

    @Test
    void aLeagueRenewedIntoNextYearStillCounts() {
        // Sleeper renews a dynasty league into next year's season in the offseason; that IS the season a
        // dynasty startup or rookie draft then means.
        var leagues = repo(league(1, Sport.NBA, 2026), league(2, Sport.NBA, 2027));
        assertThat(AdpSeason.current(leagues, Sport.NBA, OCT_2026)).isEqualTo(OptionalInt.of(2027));
    }

    @Test
    void otherSportsAndAnEmptySetGiveNothing() {
        assertThat(AdpSeason.current(repo(league(1, Sport.NFL, 2026)), Sport.NBA, OCT_2026)).isEmpty();
        assertThat(AdpSeason.current(repo(league(1, Sport.NBA, 2099)), Sport.NBA, OCT_2026)).isEmpty();
    }
}
