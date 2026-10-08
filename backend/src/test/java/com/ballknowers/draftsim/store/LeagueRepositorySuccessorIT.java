package com.ballknowers.draftsim.store;

import com.ballknowers.draftsim.engine.LeagueSeasonResolver;
import com.ballknowers.draftsim.engine.LeagueSeasonResolver.SeasonOption;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Real-Postgres coverage for {@code LeagueRepository.successorOf} and
 * {@code LeagueSeasonResolver.seasons} (spec 022 T016, review F4) against the local NBA chain
 * 2024 -> 2025 -> 2026. Fails (does not skip) if the database is unreachable or lacks the chain.
 */
@SpringBootTest
class LeagueRepositorySuccessorIT {

    private static final String NBA_2024 = "1141438340626231296";
    private static final String NBA_2025 = "1229352720222134272";
    private static final String NBA_2026 = "1339351318115946496";

    @Autowired LeagueRepository leagues;
    @Autowired LeagueSeasonResolver resolver;

    @Test
    void successorOf_walksOneStepForward() {
        var next = leagues.successorOf(NBA_2024);
        assertTrue(next.isPresent());
        assertEquals(NBA_2025, next.get().sleeperId());
        assertEquals(2025, next.get().season());
        assertEquals(NBA_2026, leagues.successorOf(NBA_2025).orElseThrow().sleeperId());
    }

    @Test
    void successorOf_isEmptyAtTheChainHeadAndForUnknownIds() {
        assertTrue(leagues.successorOf(NBA_2026).isEmpty());
        assertTrue(leagues.successorOf("no-such-league").isEmpty());
    }

    @Test
    void seasons_from2024ListsNewestFirstWithGamesFlags() {
        List<SeasonOption> out = resolver.seasons(NBA_2024);
        assertEquals(List.of(2026, 2025, 2024), out.stream().map(SeasonOption::season).toList());
        assertEquals(List.of(NBA_2026, NBA_2025, NBA_2024), out.stream().map(SeasonOption::sleeperLeagueId).toList());
        assertTrue(out.get(2).hasGames(), "2024 has player_game rows locally");
        assertTrue(out.get(1).hasGames(), "2025 has player_game rows locally");
        System.out.println("T016 measured: 2026 hasGames=" + out.get(0).hasGames());
    }

    @Test
    void seasons_isTheSameFromAnyPointInTheChain() {
        assertEquals(resolver.seasons(NBA_2024), resolver.seasons(NBA_2026));
    }
}
