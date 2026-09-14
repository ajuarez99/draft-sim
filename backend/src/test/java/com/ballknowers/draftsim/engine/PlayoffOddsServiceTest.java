package com.ballknowers.draftsim.engine;

import com.ballknowers.draftsim.store.LeagueMatchupRepository;
import com.ballknowers.draftsim.store.LeagueRepository;
import com.ballknowers.draftsim.store.PlayoffOddsRepository;
import com.ballknowers.draftsim.store.RosterSeasonRepository;
import com.ballknowers.draftsim.store.RosterWeekPointsRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * The rules about whether to answer at all (claude/playoff-odds.md "Honesty
 * rules"). Every one of these is a case where the simulator would happily
 * return numbers and the numbers would be worthless.
 */
@ExtendWith(MockitoExtension.class)
class PlayoffOddsServiceTest {

    @Mock private LeagueRepository leagues;
    @Mock private RosterSeasonRepository rosterSeasons;
    @Mock private RosterWeekPointsRepository weekPoints;
    @Mock private LeagueMatchupRepository fixtures;
    @Mock private PlayoffOddsRepository odds;

    private PlayoffOddsService service;

    private static final long LEAGUE = 4L;

    @BeforeEach
    void setUp() {
        service = new PlayoffOddsService(leagues, rosterSeasons, weekPoints, fixtures, odds);
    }

    private static LeagueRepository.PlayoffFormat plainFormat() {
        return new LeagueRepository.PlayoffFormat(6, 15, 0, false, false);
    }

    private static RosterSeasonRepository.StandingRow standing(int rosterId, int wins, double pointsFor) {
        return new RosterSeasonRepository.StandingRow(LEAGUE, rosterId, null, null, null,
                wins, 0, 0, pointsFor, 0.0, null, 2026, null);
    }

    private static List<RosterSeasonRepository.StandingRow> twelveTeams() {
        List<RosterSeasonRepository.StandingRow> out = new ArrayList<>();
        for (int i = 1; i <= 12; i++) out.add(standing(i, 1, 100.0 + i));
        return out;
    }

    private static List<RosterWeekPointsRepository.WeekPoint> oneScoredWeek() {
        List<RosterWeekPointsRepository.WeekPoint> out = new ArrayList<>();
        for (int i = 1; i <= 12; i++) out.add(new RosterWeekPointsRepository.WeekPoint(1, i, 90.0 + i * 3));
        return out;
    }

    private static List<LeagueMatchupRepository.Fixture> weekOfFixtures(int week) {
        List<LeagueMatchupRepository.Fixture> out = new ArrayList<>();
        for (int i = 1; i <= 12; i++) out.add(new LeagueMatchupRepository.Fixture(week, i, (i + 1) / 2));
        return out;
    }

    /**
     * The one a live preseason league found: with no scored games there is no
     * mean and no variance, so every simulated matchup ends 0-0, every team
     * finishes identical, and the sort hands the six playoff spots to whoever
     * sorted first -- 100% and 0%, stated with total confidence, from nothing.
     */
    @Test
    void aLeagueWithNoScoredGamesGetsNoSnapshot() {
        when(leagues.playoffFormat(LEAGUE)).thenReturn(Optional.of(plainFormat()));
        when(rosterSeasons.forLeague(LEAGUE)).thenReturn(twelveTeams());
        when(weekPoints.through(LEAGUE, 1)).thenReturn(List.of());

        assertTrue(service.compute(LEAGUE, 2026, 1).isEmpty());

        verify(odds, never()).save(anyLong(), anyInt(), anyInt(), anyInt(), any(), any());
        // ...and it clears any answer a previous run left behind.
        verify(odds).deleteWeek(LEAGUE, 2026, 1);
    }

    /** A stored 0.0 is a week that was not played, not a week someone scored nothing. */
    @Test
    void zeroPointWeeksDoNotCountAsScoring() {
        when(leagues.playoffFormat(LEAGUE)).thenReturn(Optional.of(plainFormat()));
        when(rosterSeasons.forLeague(LEAGUE)).thenReturn(twelveTeams());
        List<RosterWeekPointsRepository.WeekPoint> allZero = new ArrayList<>();
        for (int i = 1; i <= 12; i++) allZero.add(new RosterWeekPointsRepository.WeekPoint(1, i, 0.0));
        when(weekPoints.through(LEAGUE, 1)).thenReturn(allZero);

        assertTrue(service.compute(LEAGUE, 2026, 1).isEmpty());
        verify(odds, never()).save(anyLong(), anyInt(), anyInt(), anyInt(), any(), any());
    }

    /** Divisions: the seeding is not the plain ladder this app models, so there is no number to give. */
    @Test
    void aDivisionLeagueGetsNoSnapshot() {
        when(leagues.playoffFormat(LEAGUE))
                .thenReturn(Optional.of(new LeagueRepository.PlayoffFormat(6, 15, 0, true, false)));

        assertTrue(service.compute(LEAGUE, 2026, 1).isEmpty());

        verify(odds, never()).save(anyLong(), anyInt(), anyInt(), anyInt(), any(), any());
        verify(rosterSeasons, never()).forLeague(anyLong());
    }

    /** A custom playoff_seed_type is the same story: a format we do not reproduce. */
    @Test
    void aCustomSeedTypeGetsNoSnapshot() {
        when(leagues.playoffFormat(LEAGUE))
                .thenReturn(Optional.of(new LeagueRepository.PlayoffFormat(6, 15, 1, false, false)));

        assertTrue(service.compute(LEAGUE, 2026, 1).isEmpty());
        verify(odds, never()).save(anyLong(), anyInt(), anyInt(), anyInt(), any(), any());
    }

    /** The happy path: one scored week is enough to stand on, and the snapshot is written. */
    @Test
    void oneScoredWeekIsEnoughToAnswer() {
        when(leagues.playoffFormat(LEAGUE)).thenReturn(Optional.of(plainFormat()));
        when(rosterSeasons.forLeague(LEAGUE)).thenReturn(twelveTeams());
        when(weekPoints.through(LEAGUE, 1)).thenReturn(oneScoredWeek());
        when(fixtures.between(LEAGUE, 2026, 2, 14)).thenReturn(weekOfFixtures(2));

        var entries = service.compute(LEAGUE, 2026, 1);

        assertEquals(12, entries.size());
        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<PlayoffOddsRepository.Entry>> saved = ArgumentCaptor.forClass(List.class);
        verify(odds).save(eq(LEAGUE), eq(2026), eq(1), eq(PlayoffOddsService.ITERATIONS),
                eq(PlayoffOddsSimulator.MODEL), saved.capture());
        double sum = saved.getValue().stream().mapToDouble(PlayoffOddsRepository.Entry::madePct).sum();
        assertEquals(600.0, sum, 0.01, "six playoff spots, so the percentages add to 600");
    }

    /** Odds come from the stored snapshot on read -- never recomputed on a page load. */
    @Test
    void readingOddsNeverRecomputes() {
        when(odds.madePctByWeek(LEAGUE, 2026)).thenReturn(java.util.Map.of(1, java.util.Map.of(3, 62.5)));

        assertEquals(62.5, service.madePctByWeek(LEAGUE, 2026).get(1).get(3));

        verifyNoInteractions(leagues, rosterSeasons, fixtures);
    }
}
