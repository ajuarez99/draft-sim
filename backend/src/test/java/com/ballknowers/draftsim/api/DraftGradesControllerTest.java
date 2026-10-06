package com.ballknowers.draftsim.api;

import com.ballknowers.draftsim.config.DraftGradeProperties;
import com.ballknowers.draftsim.config.GradeProperties;
import com.ballknowers.draftsim.domain.Player;
import com.ballknowers.draftsim.domain.Position;
import com.ballknowers.draftsim.domain.Sport;
import com.ballknowers.draftsim.engine.DraftGradesService;
import com.ballknowers.draftsim.engine.DraftGradesService.DraftGrades;
import com.ballknowers.draftsim.engine.GameScoringService;
import com.ballknowers.draftsim.engine.LetterGrades;
import com.ballknowers.draftsim.engine.ScoredWeeks;
import com.ballknowers.draftsim.engine.SeasonWindow;
import com.ballknowers.draftsim.sport.SportRules;
import com.ballknowers.draftsim.sport.SportRulesRegistry;
import com.ballknowers.draftsim.store.DraftRepository;
import com.ballknowers.draftsim.store.LeagueMembership;
import com.ballknowers.draftsim.store.LeagueMatchupRepository;
import com.ballknowers.draftsim.store.LeagueRepository;
import com.ballknowers.draftsim.store.ManagerRepository;
import com.ballknowers.draftsim.store.PlayerGameRepository;
import com.ballknowers.draftsim.store.PlayerRepository;
import com.ballknowers.draftsim.store.RosterSeasonRepository;
import com.ballknowers.draftsim.store.RosterWeekPointsRepository;
import com.ballknowers.draftsim.store.SportWeekStatsRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.ResponseEntity;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * Spec 018 T015: the check order of data-model "States" (N13), with mocked collaborators, plus the
 * controller's scoping 404.
 */
@ExtendWith(MockitoExtension.class)
class DraftGradesControllerTest {

    private static final long LEAGUE_ID = 10L;

    @Mock private DraftRepository drafts;
    @Mock private PlayerRepository players;
    @Mock private PlayerGameRepository playerGames;
    @Mock private SportWeekStatsRepository weekStats;
    @Mock private LeagueRepository leagues;
    @Mock private ScoredWeeks scoredWeeks;
    @Mock private ManagerRepository managers;
    @Mock private SportRulesRegistry rules;
    @Mock private SportRules footballRules;
    @Mock private LeagueMembership membership;
    @Mock private RosterSeasonRepository rosterSeasons;
    @Mock private RosterWeekPointsRepository rosterWeekPoints;
    @Mock private LeagueMatchupRepository matchups;

    private static final GradeProperties LADDER = new GradeProperties(List.of(
            new GradeProperties.Cutoff(50, "A"), new GradeProperties.Cutoff(100, "B")));

    @BeforeEach
    void leagueAndRules() {
        lenient().when(leagues.byId(LEAGUE_ID)).thenReturn(Optional.of(new LeagueRepository.LeagueRow(
                LEAGUE_ID, Sport.NFL, "sl", "L", 2025, 12, List.of(), 1.0, null, "complete")));
        lenient().when(leagues.scoringOf(LEAGUE_ID)).thenReturn(Map.of("rec", 1.0));
        lenient().when(rules.get(Sport.NFL)).thenReturn(footballRules);
        lenient().when(footballRules.playsMultipleGamesPerScoringPeriod()).thenReturn(false);
        lenient().when(managers.names()).thenReturn(Map.of());
        lenient().when(managers.avatarIds()).thenReturn(Map.of());
    }

    private DraftGradesService service(Integer neighbors) {
        return new DraftGradesService(new LetterGrades(LADDER), new DraftGradeProperties(neighbors), drafts,
                players, playerGames, weekStats, leagues, scoredWeeks, new GameScoringService(), rules, managers,
                rosterSeasons, rosterWeekPoints, matchups);
    }

    private static DraftRepository.DraftRow draft(String status) {
        return new DraftRepository.DraftRow(1L, LEAGUE_ID, "d1", 2025, 3, 2, status, Map.of());
    }

    private static void assertEmptyUnavailable(DraftGrades g, String reason) {
        assertFalse(g.available());
        assertEquals(reason, g.reason());
        assertEquals(0, g.weeksCounted());
        assertTrue(g.countedWeeks().isEmpty());
        assertTrue(g.picks().isEmpty());
        assertTrue(g.teams().isEmpty());
        assertTrue(g.steals().isEmpty());
        assertTrue(g.busts().isEmpty());
        assertNull(g.averageTeamRawValue());
        assertEquals(SeasonWindow.EARLY_THRESHOLD_WEEKS, g.earlyThresholdWeeks());
        assertTrue(g.gradesEarly());
    }

    // ---------------------------------------------------------------- the check order

    @Test
    void absentConfigAnswersNotConfiguredBeforeAnythingElseIsLookedAt() {
        // The draft is not even complete: config is checked first, so this is still NOT_CONFIGURED.
        DraftGrades g = service(null).read(draft("pre_draft"));
        assertEmptyUnavailable(g, "NOT_CONFIGURED");
        assertNull(g.minPicksPerPosition());
        verifyNoInteractions(scoredWeeks, weekStats, playerGames);
    }

    @Test
    void aPreDraftDraftIsNotComplete() {
        DraftGrades g = service(3).read(draft("pre_draft"));
        assertEmptyUnavailable(g, "DRAFT_NOT_COMPLETE");
        assertEquals(3, g.minPicksPerPosition());
        assertEquals("WEEKLY_GAME", g.productionBasis().name());
        assertEquals(Sport.NFL, g.sport());
        verifyNoInteractions(scoredWeeks, weekStats, playerGames);
    }

    @Test
    void aNullStatusIsNotComplete() {
        assertEmptyUnavailable(service(3).read(draft(null)), "DRAFT_NOT_COMPLETE");
    }

    @Test
    void aCompleteDraftWithNoFinalWeeksHasNoScoredWeeks() {
        when(scoredWeeks.of(LEAGUE_ID)).thenReturn(new ScoredWeeks.Snapshot(0, 0, Set.of()));
        when(weekStats.forSeason(Sport.NFL, 2025)).thenReturn(List.of());
        DraftGrades g = service(3).read(draft("complete"));
        assertEmptyUnavailable(g, "NO_SCORED_WEEKS");
        assertTrue(g.weeksMissingGameData().isEmpty());
    }

    @Test
    void finalWeeksWithoutPerGameDataAreNamedNotReadAsEveryoneSittingOut() {
        when(scoredWeeks.of(LEAGUE_ID)).thenReturn(new ScoredWeeks.Snapshot(3, 3, Set.of(3, 1, 2)));
        when(weekStats.forSeason(Sport.NFL, 2025)).thenReturn(List.of());
        DraftGrades g = service(3).read(draft("complete"));
        assertEmptyUnavailable(g, "NO_SCORED_WEEKS");
        assertEquals(List.of(1, 2, 3), g.weeksMissingGameData());
        verifyNoInteractions(playerGames);
    }

    // ---------------------------------------------------------------- the available path

    @Test
    void anAvailableDraftCountsOnlyWeeksWithGameDataAndExcludesPicksItCannotGrade() {
        when(scoredWeeks.of(LEAGUE_ID)).thenReturn(new ScoredWeeks.Snapshot(3, 3, Set.of(1, 2, 3)));
        when(weekStats.forSeason(Sport.NFL, 2025)).thenReturn(List.of(
                new SportWeekStatsRepository.Row(Sport.NFL, 2025, 1, Instant.EPOCH, true),
                new SportWeekStatsRepository.Row(Sport.NFL, 2025, 2, Instant.EPOCH, true),
                new SportWeekStatsRepository.Row(Sport.NFL, 2025, 9, Instant.EPOCH, true)));   // not a final league week
        when(drafts.picks(1L)).thenReturn(List.of(
                new DraftRepository.PickRow(1L, 1, 1, 1, null, 101L, null),
                new DraftRepository.PickRow(1L, 2, 1, 2, null, 102L, null),
                new DraftRepository.PickRow(1L, 3, 2, 2, null, null, null),      // no player id
                new DraftRepository.PickRow(1L, 4, 2, 1, null, 999L, null)));    // player row is gone
        when(players.findAll(Sport.NFL)).thenReturn(List.of(
                new Player(101L, Sport.NFL, "a", "Alpha", List.of(Position.WR), "SEA", null, null, null, null),
                new Player(102L, Sport.NFL, "b", "Bravo", List.of(Position.WR), "SEA", null, null, null, null)));
        when(playerGames.forPlayers(any(), anyInt(), anyCollection())).thenReturn(List.of(
                game(1, "a", 5), game(2, "a", 5), game(3, "a", 500),   // week 3 has no per-game data: ignored
                game(1, "b", 2)));

        DraftGrades g = service(3).read(draft("complete"));

        assertTrue(g.available());
        assertNull(g.reason());
        assertEquals(List.of(1, 2), g.countedWeeks());
        assertEquals(2, g.weeksCounted());
        assertEquals(List.of(3), g.weeksMissingGameData());
        assertTrue(g.gradesEarly());
        assertEquals(2, g.picks().size());
        assertEquals(2, g.excludedPicks());
        assertEquals(g.picks().size() + g.excludedPicks(), 4);
        assertEquals(10.0, g.picks().get(0).production(), 1e-9);
        assertEquals(2, g.picks().get(0).weeksPlayed());
        assertEquals(2.0, g.picks().get(1).production(), 1e-9);
        assertEquals(1, g.picks().get(1).weeksPlayed());
    }

    // ---------------------------------------------------------------- counted for the drafting team (read path)

    private void forYouFixture(List<RosterSeasonRepository.StandingRow> rosterRows,
                               List<RosterWeekPointsRepository.WeekBreakdown> breakdowns,
                               List<LeagueMatchupRepository.Fixture> fixtures) {
        when(scoredWeeks.of(LEAGUE_ID)).thenReturn(new ScoredWeeks.Snapshot(3, 3, Set.of(1, 2, 3)));
        when(weekStats.forSeason(Sport.NFL, 2025)).thenReturn(List.of(
                new SportWeekStatsRepository.Row(Sport.NFL, 2025, 1, Instant.EPOCH, true),
                new SportWeekStatsRepository.Row(Sport.NFL, 2025, 2, Instant.EPOCH, true),
                new SportWeekStatsRepository.Row(Sport.NFL, 2025, 3, Instant.EPOCH, true)));
        when(drafts.picks(1L)).thenReturn(List.of(
                new DraftRepository.PickRow(1L, 1, 1, 1, 7L, 101L, null),
                new DraftRepository.PickRow(1L, 2, 1, 2, 8L, 102L, null)));
        when(players.findAll(Sport.NFL)).thenReturn(List.of(
                new Player(101L, Sport.NFL, "a", "Alpha", List.of(Position.WR), "SEA", null, null, null, null),
                new Player(102L, Sport.NFL, "b", "Bravo", List.of(Position.WR), "SEA", null, null, null, null)));
        when(playerGames.forPlayers(any(), anyInt(), anyCollection())).thenReturn(List.of(
                game(1, "a", 5), game(2, "a", 5), game(3, "a", 5),
                game(1, "b", 4), game(2, "b", 4), game(3, "b", 4)));
        when(rosterSeasons.forLeague(LEAGUE_ID)).thenReturn(rosterRows);
        when(rosterWeekPoints.breakdownsFor(LEAGUE_ID, 2025)).thenReturn(breakdowns);
        when(matchups.between(LEAGUE_ID, 2025, 1, 3)).thenReturn(fixtures);
    }

    private static RosterSeasonRepository.StandingRow roster(int rosterId, Long managerId) {
        return new RosterSeasonRepository.StandingRow(LEAGUE_ID, rosterId, managerId, null, null, null, null,
                null, null, null, null, 2025, null, Sport.NFL, null, null);
    }

    private static RosterWeekPointsRepository.WeekBreakdown week(int week, int rosterId, String points,
                                                                String starters) {
        return new RosterWeekPointsRepository.WeekBreakdown(week, rosterId, 0.0, points, starters);
    }

    @Test
    void theReadPathMapsRostersAndTreatsNullStartersEmptyPointsAndAnUnscheduledWeekAsUnknown() {
        forYouFixture(List.of(roster(1, 7L), roster(2, 8L)), List.of(
                week(1, 1, "{\"a\": 5.0}", "[\"a\"]"),
                week(2, 1, "{}", "[\"a\"]"),              // no answer
                week(3, 1, "{\"a\": 5.0}", null),         // no answer
                week(1, 2, "{\"b\": 4.0}", "[\"b\"]"),
                week(2, 2, "{\"b\": 4.0}", "[\"b\"]"),
                week(3, 2, "{\"b\": 4.0}", "[\"b\"]")), List.of(
                new LeagueMatchupRepository.Fixture(1, 1, 1), new LeagueMatchupRepository.Fixture(1, 2, 1),
                new LeagueMatchupRepository.Fixture(2, 1, 1), new LeagueMatchupRepository.Fixture(2, 2, 1),
                new LeagueMatchupRepository.Fixture(3, 1, 1), new LeagueMatchupRepository.Fixture(3, 2, 1)));

        DraftGrades g = service(3).read(draft("complete"));

        assertEquals(0, g.unmappedPicks());
        DraftGradesService.PickGrade a = g.picks().get(0);
        assertEquals(1, a.weeksStartedForYou());
        assertEquals(2, a.weeksUnknownForYou());
        assertEquals(5.0, a.countedForYou(), 1e-9);       // unknown weeks add nothing and are not zeroed in
        assertEquals(5.0, a.creditedForYou(), 1e-9);
        DraftGradesService.PickGrade b = g.picks().get(1);
        assertEquals(3, b.weeksStartedForYou());
        assertEquals(0, b.weeksUnknownForYou());
        assertEquals(12.0, b.countedForYou(), 1e-9);
        assertTrue(b.countedForYou() <= b.production() + 0.01);
    }

    @Test
    void aWeekTheRosterHadNoGameInIsNeitherStartedNorUnknownButAWeekNobodyHasAScheduleForIsUnknown() {
        forYouFixture(List.of(roster(1, 7L), roster(2, 8L)), List.of(
                week(1, 1, "{\"a\": 5.0}", "[\"a\"]"), week(2, 1, "{\"a\": 5.0}", "[\"a\"]"),
                week(3, 1, "{\"a\": 5.0}", "[\"a\"]"),
                week(1, 2, "{\"b\": 4.0}", "[\"b\"]"), week(2, 2, "{\"b\": 4.0}", "[\"b\"]"),
                week(3, 2, "{\"b\": 4.0}", "[\"b\"]")), List.of(
                new LeagueMatchupRepository.Fixture(1, 1, 1), new LeagueMatchupRepository.Fixture(1, 2, 1),
                new LeagueMatchupRepository.Fixture(2, 2, 1)));       // week 2: only roster 2 has a game; week 3: none

        DraftGrades g = service(3).read(draft("complete"));

        DraftGradesService.PickGrade a = g.picks().get(0);
        assertEquals(1, a.weeksStartedForYou());               // week 1 only
        assertEquals(1, a.weeksUnknownForYou());               // week 3: no schedule stored at all
        assertEquals(5.0, a.countedForYou(), 1e-9);
        DraftGradesService.PickGrade b = g.picks().get(1);
        assertEquals(2, b.weeksStartedForYou());               // weeks 1 and 2
        assertEquals(1, b.weeksUnknownForYou());
    }

    @Test
    void aManagerWithZeroOrTwoRosterRowsIsUnmapped() {
        forYouFixture(List.of(roster(1, 7L), roster(2, 7L), roster(3, 99L)), List.of(), List.of());   // 7 twice, 8 never

        DraftGrades g = service(3).read(draft("complete"));

        assertEquals(2, g.unmappedPicks());
        for (DraftGradesService.PickGrade p : g.picks()) {
            assertNull(p.countedForYou());
            assertNull(p.creditedForYou());
            assertNull(p.weeksStartedForYou());
            assertNull(p.weeksUnknownForYou());
        }
    }

    private static PlayerGameRepository.Row game(int week, String sleeperId, int receptions) {
        return new PlayerGameRepository.Row(Sport.NFL, 2025, week, sleeperId, "g" + week + sleeperId,
                LocalDate.of(2025, 9, 7), "DEN", false, "{\"rec\": " + receptions + "}");
    }

    // ---------------------------------------------------------------- the route

    @Test
    void aDraftTheCallerCannotSeeIs404() {
        when(membership.visibleDraft(any(), any())).thenReturn(Optional.empty());
        ResponseEntity<?> r = new DraftGradesController(service(3), membership).grades("d1", "someone");
        assertEquals(404, r.getStatusCode().value());
        assertNull(r.getBody());
    }

    @Test
    void aVisibleDraftIs200WithTheGradesBody() {
        when(membership.visibleDraft("me", "d1")).thenReturn(Optional.of(draft("pre_draft")));
        ResponseEntity<?> r = new DraftGradesController(service(3), membership).grades("d1", "me");
        assertEquals(200, r.getStatusCode().value());
        DraftGrades body = (DraftGrades) r.getBody();
        assertNotNull(body);
        assertEquals("d1", body.draftId());
        assertEquals("DRAFT_NOT_COMPLETE", body.reason());
    }
}
