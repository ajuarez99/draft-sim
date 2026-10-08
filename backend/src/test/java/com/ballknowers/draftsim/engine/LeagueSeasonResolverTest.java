package com.ballknowers.draftsim.engine;

import com.ballknowers.draftsim.domain.Sport;
import com.ballknowers.draftsim.engine.LeagueSeasonResolver.Rule;
import com.ballknowers.draftsim.store.LeagueRepository;
import com.ballknowers.draftsim.store.LeagueRepository.LeagueRow;
import com.ballknowers.draftsim.store.PlayerGameRepository.SeasonToken;
import com.ballknowers.draftsim.store.RosterWeekPointsRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** specs/022-player-stat-analysis T015/T016: the two "played" rules and the season list. */
class LeagueSeasonResolverTest {

    private LeagueRepository leagues;
    private RosterWeekPointsRepository weekPoints;
    private SeasonBoxCache cache;
    private LeagueSeasonResolver resolver;

    private final LeagueRow y2024 = row(1, "L24", 2024, null);
    private final LeagueRow y2025 = row(2, "L25", 2025, "L24");
    private final LeagueRow y2026 = row(3, "L26", 2026, "L25");

    private static LeagueRow row(long id, String sleeperId, int season, String prev) {
        return new LeagueRow(id, Sport.NBA, sleeperId, "n", season, 12, List.of(), 0, prev, null);
    }

    @BeforeEach
    void setUp() {
        leagues = mock(LeagueRepository.class);
        weekPoints = mock(RosterWeekPointsRepository.class);
        cache = mock(SeasonBoxCache.class);
        resolver = new LeagueSeasonResolver(leagues, weekPoints, cache);
        when(leagues.bySleeperId("L24")).thenReturn(Optional.of(y2024));
        when(leagues.bySleeperId("L25")).thenReturn(Optional.of(y2025));
        when(leagues.bySleeperId("L26")).thenReturn(Optional.of(y2026));
        when(leagues.chainBySleeperId("L26")).thenReturn(List.of(y2026, y2025, y2024));
        when(leagues.chainBySleeperId("L25")).thenReturn(List.of(y2025, y2024));
        when(leagues.chainBySleeperId("L24")).thenReturn(List.of(y2024));
        when(leagues.successorOf("L24")).thenReturn(Optional.of(y2025));
        when(leagues.successorOf("L25")).thenReturn(Optional.of(y2026));
        when(leagues.successorOf("L26")).thenReturn(Optional.empty());
        when(weekPoints.storedWeeks(anyLong())).thenReturn(Set.of());
        for (int s : new int[]{2024, 2025, 2026}) games(s, 0);
    }

    private void games(int season, long count) {
        when(cache.token(Sport.NBA, season)).thenReturn(new SeasonToken(count, null));
    }

    @Test
    void playedWeeks_picksNewestSeasonWithScoredWeeks() {
        when(weekPoints.storedWeeks(2L)).thenReturn(Set.of(1, 2));
        var r = resolver.resolve("L26", Rule.PLAYED_WEEKS).orElseThrow();
        assertEquals(2025, r.league().season());
        assertEquals(2026, r.requestedSeason());
    }

    @Test
    void playedWeeks_ignoresStoredGames() {
        games(2026, 50);        // opening night: games stored, no scored week
        when(weekPoints.storedWeeks(2L)).thenReturn(Set.of(1));
        assertEquals(2025, resolver.resolve("L26", Rule.PLAYED_WEEKS).orElseThrow().league().season());
    }

    @Test
    void playedWeeks_fallsBackToRequestedWhenNothingPlayed() {
        var r = resolver.resolve("L26", Rule.PLAYED_WEEKS).orElseThrow();
        assertEquals(2026, r.league().season());
        assertNull(r.requestedSeason());
    }

    @Test
    void legacyResolveIsPlayedWeeks() {
        games(2026, 50);
        when(weekPoints.storedWeeks(2L)).thenReturn(Set.of(1));
        assertEquals(resolver.resolve("L26", Rule.PLAYED_WEEKS), resolver.resolve("L26"));
    }

    @Test
    void storedGames_picksNewerSeasonWithGamesButNoScoredWeeks() {
        when(weekPoints.storedWeeks(2L)).thenReturn(Set.of(1, 2, 3));   // 2025 played; 2026 has none
        games(2025, 900);
        games(2026, 12);                                               // opening night
        var r = resolver.resolve("L26", Rule.STORED_GAMES).orElseThrow();
        assertEquals(2026, r.league().season());
        assertNull(r.requestedSeason());
    }

    @Test
    void storedGames_walksBackAndReportsRequestedSeason() {
        games(2024, 100);
        var r = resolver.resolve("L26", Rule.STORED_GAMES).orElseThrow();
        assertEquals(2024, r.league().season());
        assertEquals(2026, r.requestedSeason());
    }

    @Test
    void storedGames_fallsBackToRequestedWhenNoneHaveGames() {
        var r = resolver.resolve("L26", Rule.STORED_GAMES).orElseThrow();
        assertEquals(2026, r.league().season());
        assertNull(r.requestedSeason());
    }

    @Test
    void unknownLeagueIsEmpty() {
        when(leagues.chainBySleeperId("nope")).thenReturn(List.of());
        assertTrue(resolver.resolve("nope", Rule.STORED_GAMES).isEmpty());
        assertTrue(resolver.resolve("nope", Rule.PLAYED_WEEKS).isEmpty());
        assertTrue(resolver.seasons("nope").isEmpty());
    }

    @Test
    void seasons_fromOldestWalksForwardThenListsNewestFirst() {
        games(2024, 10);
        games(2025, 20);
        var out = resolver.seasons("L24");
        assertEquals(List.of(
                new LeagueSeasonResolver.SeasonOption(2026, "L26", false),
                new LeagueSeasonResolver.SeasonOption(2025, "L25", true),
                new LeagueSeasonResolver.SeasonOption(2024, "L24", true)), out);
    }

    @Test
    void seasons_survivesACycleInSuccessors() {
        when(leagues.successorOf("L26")).thenReturn(Optional.of(y2024));   // 26 -> 24 loops back
        assertEquals(3, resolver.seasons("L24").size());
    }
}
