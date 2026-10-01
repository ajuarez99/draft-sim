package com.ballknowers.draftsim.engine;

import com.ballknowers.draftsim.domain.Sport;
import com.ballknowers.draftsim.engine.PlayerSpotlightService.Kind;
import com.ballknowers.draftsim.engine.PlayerSpotlightService.PeriodChoice;
import com.ballknowers.draftsim.sport.SportRules;
import com.ballknowers.draftsim.sport.SportRulesRegistry;
import com.ballknowers.draftsim.store.LeagueRepository;
import com.ballknowers.draftsim.store.PlayerGameRepository;
import com.ballknowers.draftsim.store.PlayerGameRepository.DateRow;
import com.ballknowers.draftsim.store.SportWeekStatsRepository;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Period selection and the current-season rule for the player spotlight
 * (specs/014-home-player-spotlight, T008, research R6/R8/R9). Pure and mock-based: no database.
 * The 10:00 UTC cutoff is an assumption (R6), so these tests pin the rule as written, not a
 * measured fact about when a real slate ends.
 */
class PlayerSpotlightPeriodTest {

    private static final LocalDate D = LocalDate.parse("2026-10-21");
    private static final Instant FAR_FUTURE = Instant.parse("2027-01-01T00:00:00Z");

    private static PeriodChoice night(List<DateRow> dates, Map<Integer, Instant> fetched, Instant now) {
        return PlayerSpotlightService.choosePeriod(true, dates, fetched, Map.of(), now);
    }

    @Test
    void aWeekFetchedJustBeforeTheCutoffIsNotCompleteAndAtTheCutoffItIs() {
        List<DateRow> dates = List.of(new DateRow(D, 1, 11));

        PeriodChoice early = night(dates, Map.of(1, Instant.parse("2026-10-22T09:59:00Z")), FAR_FUTURE);
        assertNull(early.period());
        assertEquals("NO_COMPLETE_NIGHT_YET", early.unavailable());

        PeriodChoice onTime = night(dates, Map.of(1, Instant.parse("2026-10-22T10:00:00Z")), FAR_FUTURE);
        assertEquals(Kind.NIGHT, onTime.period().kind());
        assertEquals(D, onTime.period().date());
        assertEquals(11, onTime.period().gamesCount());
        assertNull(onTime.unavailable());
    }

    @Test
    void aLaterInProgressDateIsReportedWhilePeriodStaysOnTheCompleteNight() {
        LocalDate later = D.plusDays(1);
        List<DateRow> dates = List.of(new DateRow(D, 1, 11), new DateRow(later, 1, 9));
        // the week was fetched after D's cutoff but before the later date's cutoff
        Map<Integer, Instant> fetched = Map.of(1, Instant.parse("2026-10-22T12:00:00Z"));

        PeriodChoice c = night(dates, fetched, FAR_FUTURE);

        assertEquals(D, c.period().date());
        assertEquals(later, c.laterNightInProgress());
    }

    @Test
    void theNewestCompleteNightWinsAndNothingIsInProgressAfterIt() {
        List<DateRow> dates = List.of(new DateRow(D, 1, 11), new DateRow(D.plusDays(1), 1, 9));
        Map<Integer, Instant> fetched = Map.of(1, Instant.parse("2026-10-23T11:00:00Z"));

        PeriodChoice c = night(dates, fetched, FAR_FUTURE);

        assertEquals(D.plusDays(1), c.period().date());
        assertEquals(9, c.period().gamesCount());
        assertNull(c.laterNightInProgress());
    }

    @Test
    void aNightWhoseCutoffIsStillInTheFutureIsNeverComplete() {
        List<DateRow> dates = List.of(new DateRow(D, 1, 11));
        // a row stamped after the cutoff, but the pinned clock says it is not yet the cutoff
        PeriodChoice c = night(dates, Map.of(1, Instant.parse("2026-10-22T10:30:00Z")),
                Instant.parse("2026-10-22T09:00:00Z"));
        assertNull(c.period());
    }

    @Test
    void noCompleteNightStillNamesTheNewestDateAsInProgress() {
        LocalDate later = D.plusDays(1);
        List<DateRow> dates = List.of(new DateRow(D, 1, 11), new DateRow(later, 1, 9));

        PeriodChoice c = night(dates, Map.of(), FAR_FUTURE);

        assertNull(c.period());
        assertEquals("NO_COMPLETE_NIGHT_YET", c.unavailable());
        assertEquals(later, c.laterNightInProgress());
    }

    @Test
    void aWeekWithNoStatsRowIsNotComplete() {
        PeriodChoice c = night(List.of(new DateRow(D, 7, 3)), Map.of(), FAR_FUTURE);
        assertEquals("NO_COMPLETE_NIGHT_YET", c.unavailable());
    }

    @Test
    void noRowsIsNoGamesYet() {
        PeriodChoice c = night(List.of(), Map.of(), FAR_FUTURE);
        assertNull(c.period());
        assertEquals("NO_GAMES_YET", c.unavailable());
    }

    @Test
    void aWeekKindLeagueGetsTheNewestFinalStatsWeekEvenWhenALaterWeekIsStored() {
        // Stats weeks 1-2 final, 3 stored but not final: the newest FINAL week wins, not the newest stored.
        PeriodChoice c = PlayerSpotlightService.choosePeriod(false, List.of(), Map.of(),
                Map.of(1, true, 2, true, 3, false), FAR_FUTURE);

        assertEquals(Kind.WEEK, c.period().kind());
        assertEquals(2, c.period().week());
        assertTrue(c.period().weekFinal());
    }

    @Test
    void aWeekKindLeagueWithNoFinalWeekGetsTheNewestStoredOneMarkedNotFinal() {
        PeriodChoice p = PlayerSpotlightService.choosePeriod(false, List.of(), Map.of(),
                Map.of(1, false, 2, false), FAR_FUTURE);
        assertEquals(2, p.period().week());
        assertFalse(p.period().weekFinal());
    }

    @Test
    void aWeekKindLeagueWithNothingScoredHasNoPeriod() {
        PeriodChoice c = PlayerSpotlightService.choosePeriod(false, List.of(), Map.of(), Map.of(), FAR_FUTURE);
        assertNull(c.period());
        assertEquals("NO_WEEK_SCORED", c.unavailable());
    }

    // ---- the service around it: current-season rule and the sport rule seam ----

    private static LeagueRepository.LeagueRow row(long id, Sport sport, String sleeperId, int season, String previous) {
        return new LeagueRepository.LeagueRow(id, sport, sleeperId, "L", season, 10,
                List.of("QB"), 0, previous, "in_season");
    }

    private static PlayerSpotlightService service(LeagueRepository leagues, boolean multiple,
                                                  Optional<Integer> storedSeason) {
        return service(leagues, multiple, (SportLeagueSeasonSource) sport -> storedSeason);
    }

    private static PlayerSpotlightService service(LeagueRepository leagues, boolean multiple,
                                                  SportLeagueSeasonSource source) {
        SportRules rules = mock(SportRules.class);
        when(rules.playsMultipleGamesPerScoringPeriod()).thenReturn(multiple);
        SportRulesRegistry registry = mock(SportRulesRegistry.class);
        when(registry.get(org.mockito.ArgumentMatchers.any())).thenReturn(rules);
        PlayerGameRepository games = mock(PlayerGameRepository.class);
        SportWeekStatsRepository stats = mock(SportWeekStatsRepository.class);
        // Stats weeks 1-2 final, 3 stored but not final: the spotlight's week is 2.
        when(stats.forSeason(org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.anyInt()))
                .thenReturn(List.of(
                        new SportWeekStatsRepository.Row(Sport.NFL, 2026, 1, Instant.EPOCH, true),
                        new SportWeekStatsRepository.Row(Sport.NFL, 2026, 2, Instant.EPOCH, true),
                        new SportWeekStatsRepository.Row(Sport.NFL, 2026, 3, Instant.EPOCH, false)));
        return new PlayerSpotlightService(leagues, registry, games, stats,
                source, Clock.fixed(FAR_FUTURE, ZoneOffset.UTC),
                mock(com.ballknowers.draftsim.store.PlayerRepository.class), new GameScoringService(),
                mock(SpotlightOwnership.class), mock(com.ballknowers.draftsim.store.SportTrendingRepository.class));
    }

    @Test
    void anOlderSeasonWithASuccessorIsPastSeason() {
        LeagueRepository leagues = mock(LeagueRepository.class);
        LeagueRepository.LeagueRow old = row(1, Sport.NBA, "old", 2025, null);
        LeagueRepository.LeagueRow current = row(2, Sport.NBA, "cur", 2026, "old");
        when(leagues.chainBySleeperId("old")).thenReturn(List.of(old));
        when(leagues.all()).thenReturn(List.of(current, old));

        PlayerSpotlightService.Result r = service(leagues, true, Optional.empty())
                .forLeague("old", null).orElseThrow();

        assertFalse(r.applies());
        assertEquals("PAST_SEASON", r.reason());
        assertEquals(2025, r.season());
    }

    @Test
    void theNewestSeasonInItsChainAppliesAndChoosesTheWeekForAOnePerPeriodSport() {
        LeagueRepository leagues = mock(LeagueRepository.class);
        LeagueRepository.LeagueRow current = row(2, Sport.NFL, "cur", 2026, "old");
        when(leagues.chainBySleeperId("cur")).thenReturn(List.of(current));
        when(leagues.all()).thenReturn(List.of(current));

        PlayerSpotlightService.Result r = service(leagues, false, Optional.empty())
                .forLeague("cur", null).orElseThrow();

        assertTrue(r.applies());
        assertNull(r.topOfNight(), "football has no top-of-night section");
        assertEquals(Kind.WEEK, r.period().kind());
        assertEquals(2, r.period().week());
    }

    @Test
    void aStoredLeagueSeasonOverridesTheChainFallback() {
        LeagueRepository leagues = mock(LeagueRepository.class);
        LeagueRepository.LeagueRow current = row(2, Sport.NBA, "cur", 2026, null);
        when(leagues.chainBySleeperId("cur")).thenReturn(List.of(current));

        PlayerSpotlightService.Result r = service(leagues, true, Optional.of(2027))
                .forLeague("cur", null).orElseThrow();
        assertEquals("PAST_SEASON", r.reason());
    }

    @Test
    void anUnknownLeagueIsEmpty() {
        LeagueRepository leagues = mock(LeagueRepository.class);
        when(leagues.chainBySleeperId("nope")).thenReturn(List.of());
        assertTrue(service(leagues, true, Optional.empty()).forLeague("nope", null).isEmpty());
    }

    @Test
    void aBasketballLeagueWithNoGamesYetSaysSoAndKeepsEverySection() {
        LeagueRepository leagues = mock(LeagueRepository.class);
        LeagueRepository.LeagueRow current = row(2, Sport.NBA, "cur", 2026, null);
        when(leagues.chainBySleeperId("cur")).thenReturn(List.of(current));
        when(leagues.all()).thenReturn(List.of(current));

        PlayerSpotlightService.Result r = service(leagues, true, Optional.empty())
                .forLeague("cur", null).orElseThrow();

        assertTrue(r.applies());
        assertNull(r.period());
        assertEquals("NO_GAMES_YET", r.periodUnavailable());
        assertNotNull(r.topOfNight());
        assertNotNull(r.trending());
        assertNotNull(r.rookieWatch());
    }

    @Test
    void aThrowingSeasonSourceFallsBackToTheChainRuleInsteadOfFailingTheRoute() {
        SportLeagueSeasonSource boom = sport -> { throw new IllegalStateException("db down"); };

        LeagueRepository leagues = mock(LeagueRepository.class);
        LeagueRepository.LeagueRow old = row(1, Sport.NBA, "old", 2025, null);
        LeagueRepository.LeagueRow current = row(2, Sport.NBA, "cur", 2026, "old");
        when(leagues.chainBySleeperId("old")).thenReturn(List.of(old));
        when(leagues.chainBySleeperId("cur")).thenReturn(List.of(current));
        when(leagues.all()).thenReturn(List.of(current, old));

        assertEquals("PAST_SEASON", service(leagues, true, boom).forLeague("old", null).orElseThrow().reason());
        assertTrue(service(leagues, true, boom).forLeague("cur", null).orElseThrow().applies());
    }
}
