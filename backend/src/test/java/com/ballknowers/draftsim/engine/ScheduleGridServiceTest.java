package com.ballknowers.draftsim.engine;

import com.ballknowers.draftsim.domain.Sport;
import com.ballknowers.draftsim.ingest.SportSchedule;
import com.ballknowers.draftsim.store.LeagueRepository;
import com.ballknowers.draftsim.store.LeagueRepository.LeagueRow;
import com.ballknowers.draftsim.store.LeagueRepository.PlayoffFormat;
import com.ballknowers.draftsim.store.SportScheduleRepository;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.lang.reflect.Constructor;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * specs/017-nba-schedule-grid T017/T018/T028: the grid's pure core on the real 2025 and 2026
 * Sleeper schedules, plus the F1 chain test through {@link ScheduleGridService#forLeague}.
 */
class ScheduleGridServiceTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final OffsetDateTime FETCHED = OffsetDateTime.parse("2026-10-05T18:02:11Z");

    private static List<SportSchedule.Game> fixture(int season) {
        try (InputStream in = ScheduleGridServiceTest.class.getResourceAsStream(
                "/sleeper/nba-schedule-" + season + ".json")) {
            List<Map<String, Object>> raw = MAPPER.readValue(in, new TypeReference<>() {});
            return SportSchedule.parse(raw).games();
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    private static LeagueRow league(Sport sport, int season, String status) {
        return new LeagueRow(season == 2026 ? 210L : 209L, sport, "L" + season, "Ball Knowers", season,
                12, List.of("PG"), 0.0, null, status);
    }

    private static PlayoffFormat format(int start, int teams, Integer roundType) {
        return new PlayoffFormat(teams, start, 0, false, false, roundType);
    }

    private static ScheduleGridService.Result compute(LeagueRow l, OptionalInt leg, PlayoffFormat f,
                                                      List<SportSchedule.Game> games) {
        return ScheduleGridService.compute(l, leg, Optional.of(f), games,
                games.isEmpty() ? Optional.empty() : Optional.of(FETCHED));
    }

    // ---------------------------------------------------------------- SC-002 / SC-003

    @Test
    void sc002_week1Of2026HasFiveTwosTwentyFourThreesAndPhiAtFour() {
        ScheduleGridService.Result r = compute(league(Sport.NBA, 2026, "pre_draft"), OptionalInt.of(1),
                format(20, 6, 0), fixture(2026));
        assertTrue(r.available());
        assertEquals(30, r.teams().size());
        int idx = r.weeks().stream().map(ScheduleGridService.Week::week).toList().indexOf(1);
        Map<Integer, List<String>> byCount = r.teams().stream().collect(Collectors.groupingBy(
                t -> t.games()[idx], Collectors.mapping(ScheduleGridService.Team::team, Collectors.toList())));
        assertEquals(5, byCount.get(2).size());
        assertEquals(24, byCount.get(3).size());
        assertEquals(List.of("PHI"), byCount.get(4));
        for (ScheduleGridService.Team t : r.teams()) assertEquals(80, t.seasonTotal(), t.team());
    }

    @Test
    void sc003_2025HasThirtyTeamsWithNykAndSasAt83AndNoExhibitionRows() {
        ScheduleGridService.Result r = compute(league(Sport.NBA, 2025, "complete"), OptionalInt.of(21),
                format(19, 6, 0), fixture(2025));
        assertEquals(30, r.teams().size());
        for (ScheduleGridService.Team t : r.teams()) {
            int expected = t.team().equals("NYK") || t.team().equals("SAS") ? 83 : 82;
            assertEquals(expected, t.seasonTotal(), t.team());
        }
        assertTrue(r.teams().stream().noneMatch(t -> t.team().equals("STP") || t.team().equals("STR")));
        assertEquals(3, r.excluded().postponed());
        assertEquals(1, r.excluded().canceled());
        assertEquals(0, r.excluded().exhibition());
    }

    /**
     * Found in production 2026-10-07: Sleeper's 2024 schedule keeps the All-Star final (CHK vs SHQ)
     * as {@code complete}, and the grid showed 32 teams. The 2025 test above passed only because
     * its All-Star game happened to be {@code canceled}.
     */
    @Test
    void the2024AllStarFinalIsAnExhibitionNotTwoMoreTeams() {
        ScheduleGridService.Result r = compute(league(Sport.NBA, 2024, "complete"), OptionalInt.of(24),
                format(22, 6, 0), fixture(2024));
        assertEquals(30, r.teams().size());
        assertTrue(r.teams().stream().noneMatch(t -> t.team().equals("CHK") || t.team().equals("SHQ")));
        for (ScheduleGridService.Team t : r.teams()) {
            int expected = t.team().equals("MIL") || t.team().equals("OKC") ? 83 : 82;
            assertEquals(expected, t.seasonTotal(), t.team());
        }
        assertEquals(1, r.excluded().exhibition());
        assertEquals(5, r.excluded().postponed());
        assertEquals(0, r.excluded().canceled());
    }

    /** The floor is relative: a one-week schedule (every team 2-4 games) loses nobody. */
    @Test
    void aOneWeekScheduleExcludesNobody() {
        List<SportSchedule.Game> week1 = fixture(2026).stream().filter(g -> g.week() == 1).toList();
        ScheduleGridService.Result r = compute(league(Sport.NBA, 2026, "pre_draft"), OptionalInt.of(1),
                format(20, 6, 0), week1);
        assertEquals(30, r.teams().size());
        assertEquals(0, r.excluded().exhibition());
    }

    // ---------------------------------------------------------------- shape invariants

    @Test
    void gamesLengthMatchesWeeksWeeksAscendFrom1To25AndTeamsAreOrderedByCode() {
        ScheduleGridService.Result r = compute(league(Sport.NBA, 2025, "complete"), OptionalInt.of(21),
                format(19, 6, 0), fixture(2025));
        List<Integer> weeks = r.weeks().stream().map(ScheduleGridService.Week::week).toList();
        assertEquals(java.util.stream.IntStream.rangeClosed(1, 25).boxed().toList(), weeks);
        for (ScheduleGridService.Team t : r.teams()) {
            assertEquals(r.weeks().size(), t.games().length, t.team());
            assertEquals(t.seasonTotal(), java.util.Arrays.stream(t.games()).sum(), t.team());
        }
        List<String> codes = r.teams().stream().map(ScheduleGridService.Team::team).toList();
        assertEquals(codes.stream().sorted().toList(), codes, "the server encodes no ranking");
        ScheduleGridService.Week w1 = r.weeks().get(0);
        assertNotNull(w1.firstDate());
        assertFalse(w1.lastDate().isBefore(w1.firstDate()));
    }

    // ---------------------------------------------------------------- F2 league span

    @Test
    void f2_aFinished2025LeagueIsSeasonOverWithLastLeagueWeek21() {
        ScheduleGridService.Result r = compute(league(Sport.NBA, 2025, "complete"), OptionalInt.of(21),
                format(19, 6, 0), fixture(2025));
        assertEquals(21, r.lastLeagueWeek());
        assertTrue(r.seasonOver());
        assertEquals(21, r.currentWeek());
        assertEquals(2025, r.season());
    }

    @Test
    void f2_theUpcoming2026LeagueIsNotOverAndEndsAtWeek22() {
        ScheduleGridService.Result r = compute(league(Sport.NBA, 2026, "pre_draft"), OptionalInt.of(1),
                format(20, 6, 0), fixture(2026));
        assertEquals(22, r.lastLeagueWeek());
        assertFalse(r.seasonOver());
        assertEquals(1, r.currentWeek());
    }

    @Test
    void f2_aLeagueWithNoLegHasNullCurrentWeek() {
        ScheduleGridService.Result r = compute(league(Sport.NBA, 2026, "pre_draft"), OptionalInt.empty(),
                format(20, 6, 0), fixture(2026));
        assertNull(r.currentWeek());
        assertFalse(r.seasonOver());
        assertTrue(r.available());
    }

    @Test
    void aLegPastTheLastLeagueWeekIsSeasonOverEvenIfNotMarkedComplete() {
        ScheduleGridService.Result r = compute(league(Sport.NBA, 2026, "in_season"), OptionalInt.of(23),
                format(20, 6, 0), fixture(2026));
        assertTrue(r.seasonOver());
    }

    @Test
    void lastLeagueWeekFallsBackToTheWeekBeforePlayoffsThenToTheLastStoredWeek() {
        // round type unsupported: the playoff end is unknown, the regular season still ends at start - 1
        ScheduleGridService.Result r = compute(league(Sport.NBA, 2026, "in_season"), OptionalInt.of(1),
                format(15, 6, 1), fixture(2026));
        assertEquals(14, r.lastLeagueWeek());
        // no playoff start at all: the last stored week
        ScheduleGridService.Result none = compute(league(Sport.NBA, 2026, "in_season"), OptionalInt.of(1),
                format(0, 0, null), fixture(2026));
        assertEquals(none.weeks().get(none.weeks().size() - 1).week(), none.lastLeagueWeek());
    }

    @Test
    void r2_unknownPlayoffEndNeverReadsSeasonOverUntilTheLeagueIsComplete() {
        for (Integer roundType : new Integer[]{1, null}) {
            ScheduleGridService.Result r = compute(league(Sport.NBA, 2026, "in_season"), OptionalInt.of(16),
                    format(15, 6, roundType), fixture(2026));
            assertFalse(r.seasonOver(), "roundType " + roundType);
            assertEquals(14, r.lastLeagueWeek());
        }
        assertTrue(compute(league(Sport.NBA, 2026, "complete"), OptionalInt.of(16),
                format(15, 6, 1), fixture(2026)).seasonOver());
    }

    // ---------------------------------------------------------------- unavailable

    @Test
    void r1_aCompleteSeasonWithNoStoredScheduleDoesNotPromiseARefresh() {
        ScheduleGridService.Result r = compute(league(Sport.NBA, 2025, "complete"), OptionalInt.of(21),
                format(19, 6, 0), List.of());
        assertFalse(r.available());
        assertEquals("The 2025 NBA schedule wasn't saved for this season: "
                + "it finished before the app started storing schedules.", r.reason());
        assertFalse(r.reason().contains("/api/"));
        assertFalse(r.reason().contains("refresh"));
    }

    @Test
    void aFootballLeagueIsUnavailableWithTheContractReason() {
        ScheduleGridService.Result r = compute(league(Sport.NFL, 2026, "in_season"), OptionalInt.of(5),
                format(15, 6, 0), fixture(2026));
        assertFalse(r.available());
        assertEquals("The schedule grid is for basketball leagues: an NFL team plays once a week.", r.reason());
        assertTrue(r.weeks().isEmpty());
        assertTrue(r.teams().isEmpty());
        assertNull(r.fetchedAt());
        assertFalse(r.reason().contains("/api/"));
        assertEquals(17, r.playoff().endWeek(), "playoff still filled: it doesn't depend on the schedule");
    }

    @Test
    void anEmptyStoredScheduleIsUnavailableWithoutAnIngestHint() {
        ScheduleGridService.Result r = compute(league(Sport.NBA, 2026, "pre_draft"), OptionalInt.of(1),
                format(20, 6, 0), List.of());
        assertFalse(r.available());
        assertEquals("The 2026 NBA schedule hasn't been loaded yet. It loads with the league's next refresh.",
                r.reason());
        assertFalse(r.reason().contains("/api/"));
        assertTrue(r.weeks().isEmpty());
        assertNull(r.fetchedAt());
        assertEquals(22, r.playoff().endWeek());
    }

    // ---------------------------------------------------------------- US2 playoff window

    @Test
    void playoffWindowIs20To22For2026And19To21For2025() {
        ScheduleGridService.Result r26 = compute(league(Sport.NBA, 2026, "pre_draft"), OptionalInt.of(1),
                format(20, 6, 0), fixture(2026));
        assertEquals(new ScheduleGridService.Playoff(20, 22, null), r26.playoff());
        ScheduleGridService.Result r25 = compute(league(Sport.NBA, 2025, "complete"), OptionalInt.of(21),
                format(19, 6, 0), fixture(2025));
        assertEquals(new ScheduleGridService.Playoff(19, 21, null), r25.playoff());
    }

    @Test
    void anUnsupportedRoundTypeIsRefusedWithASentence() {
        ScheduleGridService.Playoff p = compute(league(Sport.NBA, 2026, "in_season"), OptionalInt.of(1),
                format(15, 6, 1), fixture(2026)).playoff();
        assertNull(p.endWeek());
        assertEquals(15, p.startWeek());
        assertEquals("This league's playoff rounds aren't one week each, and the grid only works out "
                + "one-week rounds so far.", p.reason());
    }

    @Test
    void aNullRoundTypeIsRefusedWithTheUnknownSentence() {
        ScheduleGridService.Playoff p = compute(league(Sport.NBA, 2026, "in_season"), OptionalInt.of(1),
                format(15, 6, null), fixture(2026)).playoff();
        assertNull(p.endWeek());
        assertEquals("This league's settings don't say how long each playoff round is.", p.reason());
    }

    @Test
    void noStartAndTooFewTeamsHaveTheirOwnSentences() {
        assertEquals("This league has no playoff start week in its settings.",
                compute(league(Sport.NBA, 2026, "in_season"), OptionalInt.of(1), format(0, 6, 0), fixture(2026))
                        .playoff().reason());
        assertEquals("This league's settings don't have enough playoff teams to make a bracket.",
                compute(league(Sport.NBA, 2026, "in_season"), OptionalInt.of(1), format(15, 1, 0), fixture(2026))
                        .playoff().reason());
        assertNull(compute(league(Sport.NBA, 2026, "in_season"), OptionalInt.of(1), format(0, 6, 0), fixture(2026))
                .playoff().startWeek());
    }

    @Test
    void playoffReasonIsNullExactlyWhenEndWeekIsPresentAndNoReasonHintsAtAnEndpoint() {
        PlayoffFormat[] formats = {
                format(20, 6, 0), format(15, 6, 1), format(15, 6, 2), format(15, 6, null),
                format(0, 6, 0), format(15, 1, 0), format(15, 2, 0), format(22, 6, 0)};
        for (PlayoffFormat f : formats) {
            ScheduleGridService.Playoff p = compute(league(Sport.NBA, 2026, "in_season"), OptionalInt.of(1), f,
                    fixture(2026)).playoff();
            assertEquals(p.endWeek() == null, p.reason() != null, f.toString());
            if (p.reason() != null) {
                assertFalse(p.reason().contains("/api/"), p.reason());
                assertFalse(p.reason().contains("ingest"), p.reason());
            }
        }
    }

    // ---------------------------------------------------------------- F1: the URL's league row

    @Test
    void f1_theServiceAnswersForTheLeagueRowTheUrlNamesNotTheNewestScoredSeason() {
        LeagueRepository leagues = mock(LeagueRepository.class);
        SportScheduleRepository schedules = mock(SportScheduleRepository.class);
        LeagueRow l2026 = league(Sport.NBA, 2026, "pre_draft");
        when(leagues.bySleeperId("L2026")).thenReturn(Optional.of(l2026));
        when(leagues.currentLeg(210L)).thenReturn(OptionalInt.of(1));
        when(leagues.playoffFormat(210L)).thenReturn(Optional.of(format(20, 6, 0)));
        when(schedules.forSeason("nba", 2026)).thenReturn(fixture(2026));
        when(schedules.fetchedAt("nba", 2026)).thenReturn(Optional.of(FETCHED));

        ScheduleGridService svc = new ScheduleGridService(leagues, schedules);
        ScheduleGridService.Result r = svc.forLeague("L2026").orElseThrow();

        assertEquals(2026, r.season());
        assertEquals(1, r.currentWeek());
        assertFalse(r.seasonOver());
        assertEquals(FETCHED, r.fetchedAt());
        verify(schedules).forSeason("nba", 2026);
        verify(schedules, never()).forSeason(anyString(), org.mockito.ArgumentMatchers.eq(2025));
        verify(leagues, never()).currentLeg(209L);
        assertTrue(svc.forLeague("nope").isEmpty());
    }

    @Test
    void f1_theServiceHasNoLeagueSeasonResolverDependency() {
        for (Constructor<?> c : ScheduleGridService.class.getConstructors()) {
            for (Class<?> p : c.getParameterTypes()) {
                assertNotEquals(LeagueSeasonResolver.class, p);
            }
        }
    }
}
