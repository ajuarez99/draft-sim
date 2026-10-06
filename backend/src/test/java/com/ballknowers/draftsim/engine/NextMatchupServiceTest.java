package com.ballknowers.draftsim.engine;

import com.ballknowers.draftsim.domain.Sport;
import com.ballknowers.draftsim.store.LeagueMatchupRepository;
import com.ballknowers.draftsim.store.LeagueMatchupRepository.Fixture;
import com.ballknowers.draftsim.store.LeagueMemberRepository;
import com.ballknowers.draftsim.store.LeagueRepository;
import com.ballknowers.draftsim.store.LeagueRepository.LeagueRow;
import com.ballknowers.draftsim.store.LeagueRepository.PlayoffFormat;
import com.ballknowers.draftsim.store.ManagerRepository;
import com.ballknowers.draftsim.store.RosterSeasonRepository;
import com.ballknowers.draftsim.store.RosterSeasonRepository.StandingRow;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Constructor;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalInt;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * specs/017-nba-schedule-grid T033/T034: one test per row of data-model's next-matchup state
 * table, with the repositories mocked. F1: the URL's league row, never the resolver.
 */
class NextMatchupServiceTest {

    private static final long LEAGUE_ID = 210L;
    private static final String CALLER = "sleeper-me";
    private static final long ME_MANAGER = 7L;

    private LeagueRepository leagues;
    private LeagueMatchupRepository matchups;
    private RosterSeasonRepository rosters;
    private LeagueMemberRepository members;
    private ManagerRepository managers;
    private NextMatchupService svc;

    @BeforeEach
    void setUp() {
        leagues = mock(LeagueRepository.class);
        matchups = mock(LeagueMatchupRepository.class);
        rosters = mock(RosterSeasonRepository.class);
        members = mock(LeagueMemberRepository.class);
        managers = mock(ManagerRepository.class);
        svc = new NextMatchupService(leagues, matchups, rosters, members, managers);
        when(leagues.bySleeperId("L2026")).thenReturn(Optional.of(league(2026, "pre_draft", 210L)));
        when(leagues.currentLeg(LEAGUE_ID)).thenReturn(OptionalInt.of(1));
        when(leagues.playoffFormat(LEAGUE_ID)).thenReturn(Optional.of(format(20)));
        when(managers.idsBySleeperUserId()).thenReturn(Map.of(CALLER, ME_MANAGER));
        when(members.forLeague(LEAGUE_ID)).thenReturn(List.of());
        when(rosters.forLeague(LEAGUE_ID)).thenReturn(List.of());
        when(matchups.between(anyLong(), anyInt(), anyInt(), anyInt())).thenReturn(List.of());
    }

    private static LeagueRow league(int season, String status, long id) {
        return new LeagueRow(id, Sport.NBA, "L" + season, "Ball Knowers", season, 12, List.of("PG"),
                0.0, null, status);
    }

    private static PlayoffFormat format(int start) {
        return new PlayoffFormat(6, start, 0, false, false, 0);
    }

    private static StandingRow standing(int rosterId, Long managerId, String name, String avatar) {
        return new StandingRow(LEAGUE_ID, rosterId, managerId, name, avatar, 0, 0, 0, 0.0, 0.0, null, 2026,
                "L2026", Sport.NBA, "Ball Knowers", false);
    }

    private static LeagueMemberRepository.MemberRow member(long managerId, String name, String team) {
        return new LeagueMemberRepository.MemberRow(managerId, name, null, false, team);
    }

    private void fixtures(int week, Fixture... f) {
        when(matchups.between(LEAGUE_ID, 2026, week, week)).thenReturn(List.of(f));
    }

    // ---------------------------------------------------------------- state table

    @Test
    void noLegIsUnavailableWithNullWeek() {
        when(leagues.currentLeg(LEAGUE_ID)).thenReturn(OptionalInt.empty());
        NextMatchupService.Result r = svc.forLeague("L2026", CALLER).orElseThrow();
        assertFalse(r.available());
        assertNull(r.week());
        assertEquals("Sleeper hasn't said which week this league is in yet.", r.reason());
        assertNull(r.me());
        assertNull(r.opponent());
    }

    @Test
    void legAtOrPastThePlayoffStartIsRegularSeasonOver() {
        when(leagues.currentLeg(LEAGUE_ID)).thenReturn(OptionalInt.of(20));
        NextMatchupService.Result r = svc.forLeague("L2026", CALLER).orElseThrow();
        assertFalse(r.available());
        assertEquals(20, r.week());
        assertEquals("The regular season is over.", r.reason());
        assertNull(r.me());
    }

    @Test
    void aCompleteLeagueIsRegularSeasonOverEvenWithLegBeforeStart() {
        when(leagues.bySleeperId("L2026")).thenReturn(Optional.of(league(2026, "complete", 210L)));
        NextMatchupService.Result r = svc.forLeague("L2026", CALLER).orElseThrow();
        assertEquals("The regular season is over.", r.reason());
        assertEquals(1, r.week());
    }

    @Test
    void aMissingPlayoffStartNeverMeansTheSeasonIsOver() {
        when(leagues.playoffFormat(LEAGUE_ID)).thenReturn(Optional.of(format(0)));
        NextMatchupService.Result r = svc.forLeague("L2026", CALLER).orElseThrow();
        assertEquals("Pairings for week 1 aren't out yet. Sleeper publishes them shortly before the week starts.",
                r.reason());
    }

    @Test
    void noFixturesForLegIsNotOutYet() {
        NextMatchupService.Result r = svc.forLeague("L2026", CALLER).orElseThrow();
        assertFalse(r.available());
        assertEquals(1, r.week());
        assertEquals("Pairings for week 1 aren't out yet. Sleeper publishes them shortly before the week starts.",
                r.reason());
        assertNull(r.me());
        assertNull(r.opponent());
    }

    @Test
    void aCallerWithNoRosterIsAvailableWithNoSides() {
        fixtures(1, new Fixture(1, 1, 1), new Fixture(1, 2, 1));
        when(rosters.forLeague(LEAGUE_ID)).thenReturn(List.of(
                standing(1, 100L, "a", null), standing(2, 101L, "b", null)));
        NextMatchupService.Result r = svc.forLeague("L2026", CALLER).orElseThrow();
        assertTrue(r.available());
        assertNull(r.reason());
        assertEquals(1, r.week());
        assertNull(r.me());
        assertNull(r.opponent());
    }

    @Test
    void anAnonymousOrUnknownCallerHasNoRoster() {
        fixtures(1, new Fixture(1, 1, 1), new Fixture(1, 2, 1));
        when(rosters.forLeague(LEAGUE_ID)).thenReturn(List.of(standing(1, ME_MANAGER, "me", null)));
        assertNull(svc.forLeague("L2026", null).orElseThrow().me());
        assertNull(svc.forLeague("L2026", "").orElseThrow().me());
        assertNull(svc.forLeague("L2026", "someone-else").orElseThrow().me());
    }

    @Test
    void byeWhenTheCallersRosterIsAbsentFromTheFixtures() {
        // between() filters null matchup_id, so a bye roster has no row at all
        fixtures(1, new Fixture(1, 2, 1), new Fixture(1, 3, 1));
        when(rosters.forLeague(LEAGUE_ID)).thenReturn(List.of(
                standing(1, ME_MANAGER, "me", "av1"), standing(2, 101L, "b", null), standing(3, 102L, "c", null)));
        NextMatchupService.Result r = svc.forLeague("L2026", CALLER).orElseThrow();
        assertTrue(r.available());
        assertNull(r.reason());
        assertNotNull(r.me());
        assertEquals(1, r.me().rosterId());
        assertNull(r.opponent());
    }

    @Test
    void byeWhenTheCallerIsAloneOnItsMatchupId() {
        fixtures(1, new Fixture(1, 1, 5), new Fixture(1, 2, 6), new Fixture(1, 3, 6));
        when(rosters.forLeague(LEAGUE_ID)).thenReturn(List.of(
                standing(1, ME_MANAGER, "me", null), standing(2, 101L, "b", null), standing(3, 102L, "c", null)));
        NextMatchupService.Result r = svc.forLeague("L2026", CALLER).orElseThrow();
        assertNotNull(r.me());
        assertNull(r.opponent());
    }

    @Test
    void pairedHasBothSidesWithNamesFromLeagueMemberAndStandings() {
        fixtures(1, new Fixture(1, 4, 3), new Fixture(1, 9, 3), new Fixture(1, 1, 8), new Fixture(1, 2, 8));
        when(rosters.forLeague(LEAGUE_ID)).thenReturn(List.of(
                standing(4, ME_MANAGER, "popsharky", "abc123"),
                standing(9, 101L, "rival_user", null),
                standing(1, 102L, "x", null), standing(2, 103L, "y", null)));
        when(members.forLeague(LEAGUE_ID)).thenReturn(List.of(
                member(ME_MANAGER, "popsharky", "Dunk Tank"), member(101L, "rival_user", null)));
        NextMatchupService.Result r = svc.forLeague("L2026", CALLER).orElseThrow();
        assertTrue(r.available());
        assertEquals("Dunk Tank", r.me().teamName());
        assertEquals("popsharky", r.me().username());
        assertEquals("abc123", r.me().avatarId());
        assertEquals(4, r.me().rosterId());
        assertEquals(9, r.opponent().rosterId());
        assertNull(r.opponent().teamName(), "nullable names go out raw; the fallback is client-side");
        assertEquals("rival_user", r.opponent().username());
        assertNull(r.opponent().avatarId());
    }

    @Test
    void aBlankTeamNameIsNotInventedOrCoalesced() {
        fixtures(1, new Fixture(1, 4, 3), new Fixture(1, 9, 3));
        when(rosters.forLeague(LEAGUE_ID)).thenReturn(List.of(
                standing(4, ME_MANAGER, "me", null), standing(9, null, null, null)));
        NextMatchupService.Result r = svc.forLeague("L2026", CALLER).orElseThrow();
        assertEquals(9, r.opponent().rosterId());
        assertNull(r.opponent().username());
        assertNull(r.opponent().teamName());
    }

    @Test
    void twoRostersForTheCallerUsesTheLowestRosterId() {
        fixtures(1, new Fixture(1, 3, 1), new Fixture(1, 5, 1), new Fixture(1, 8, 2), new Fixture(1, 9, 2));
        when(rosters.forLeague(LEAGUE_ID)).thenReturn(List.of(
                standing(8, ME_MANAGER, "me", null), standing(3, ME_MANAGER, "me", null),
                standing(5, 101L, "b", null), standing(9, 102L, "c", null)));
        NextMatchupService.Result r = svc.forLeague("L2026", CALLER).orElseThrow();
        assertEquals(3, r.me().rosterId());
        assertEquals(5, r.opponent().rosterId());
    }

    @Test
    void opponentIsNeverSetWithoutMe() {
        List<List<StandingRow>> scenarios = List.of(
                List.of(), List.of(standing(1, 100L, "a", null)),
                List.of(standing(1, ME_MANAGER, "me", null), standing(2, 101L, "b", null)));
        for (List<StandingRow> rows : scenarios) {
            when(rosters.forLeague(LEAGUE_ID)).thenReturn(rows);
            for (boolean fx : new boolean[]{false, true}) {
                if (fx) fixtures(1, new Fixture(1, 1, 1), new Fixture(1, 2, 1));
                NextMatchupService.Result r = svc.forLeague("L2026", CALLER).orElseThrow();
                if (r.opponent() != null) assertNotNull(r.me());
                assertTrue(r.me() != null || r.opponent() == null);
            }
        }
    }

    @Test
    void unknownLeagueIsEmpty() {
        assertTrue(svc.forLeague("nope", CALLER).isEmpty());
    }

    // ---------------------------------------------------------------- F1

    @Test
    void f1_aTwoSeasonChainAnswersForTheUrlsSeasonNotThePreviousScoredOne() {
        // 2025 (id 209): complete, leg 21, scored. 2026 (id 210): leg 1, nothing scored yet.
        when(leagues.bySleeperId("L2025")).thenReturn(Optional.of(league(2025, "complete", 209L)));
        when(leagues.currentLeg(209L)).thenReturn(OptionalInt.of(21));
        when(leagues.playoffFormat(209L)).thenReturn(Optional.of(format(19)));

        NextMatchupService.Result r = svc.forLeague("L2026", CALLER).orElseThrow();

        assertEquals(2026, r.season());
        assertEquals(1, r.week());
        assertEquals("Pairings for week 1 aren't out yet. Sleeper publishes them shortly before the week starts.",
                r.reason());
        assertNotEquals("The regular season is over.", r.reason());
        verify(leagues, never()).currentLeg(209L);
        verify(matchups).between(eq(LEAGUE_ID), eq(2026), eq(1), eq(1));
    }

    @Test
    void f1_noLeagueSeasonResolverDependency() {
        for (Constructor<?> c : NextMatchupService.class.getConstructors()) {
            for (Class<?> p : c.getParameterTypes()) {
                assertNotEquals(LeagueSeasonResolver.class, p);
            }
        }
    }
}
