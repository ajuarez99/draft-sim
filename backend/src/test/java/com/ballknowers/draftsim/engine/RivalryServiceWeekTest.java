package com.ballknowers.draftsim.engine;

import com.ballknowers.draftsim.config.RivalryProperties;
import com.ballknowers.draftsim.domain.Sport;
import com.ballknowers.draftsim.engine.RivalryService.Recommendation;
import com.ballknowers.draftsim.engine.RivalryService.WeekOption;
import com.ballknowers.draftsim.engine.RivalryService.WeekRecommendation;
import com.ballknowers.draftsim.store.LeagueBracketRepository;
import com.ballknowers.draftsim.store.LeagueMatchupRepository;
import com.ballknowers.draftsim.store.LeagueMatchupRepository.PairedGame;
import com.ballknowers.draftsim.store.LeagueMatchupRepository.ScheduledPair;
import com.ballknowers.draftsim.store.LeagueMemberRepository;
import com.ballknowers.draftsim.store.LeagueRepository;
import com.ballknowers.draftsim.store.LeagueRepository.LeagueRow;
import com.ballknowers.draftsim.store.LeagueRepository.PlayoffFormat;
import com.ballknowers.draftsim.store.ManagerRepository;
import com.ballknowers.draftsim.store.RosterSeasonRepository;
import com.ballknowers.draftsim.store.RosterSeasonRepository.StandingRow;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalInt;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * specs/037 T009/T014/T018: the week pick wired through {@link RivalryService}, against mocked repositories.
 *
 * <p>World W: managers 11-14 (roster n = manager 10+n), regular season weeks 1-5 ({@code playoff_week_start 6}),
 * cap 2. The 2026 schedule round-robins with a repeat so every pair meets once or twice:
 * wk1 1-2,3-4; wk2 1-3,2-4; wk3 1-4,2-3; wk4 1-2,3-4; wk5 1-3,2-4; wk6 (a playoff week, stored) 1-2,3-4.
 * 2025 history makes 1-2 and 3-4 the strong rivalries and 1-4 / 2-3 a weak one, so the history-best set is
 * {1-2, 3-4}.
 */
class RivalryServiceWeekTest {

    private LeagueRepository leagues;
    private RosterSeasonRepository rosterSeasons;
    private LeagueMatchupRepository fixtures;
    private LeagueBracketRepository brackets;
    private LeagueMemberRepository members;
    private ManagerRepository managers;
    private RivalryProperties props = new RivalryProperties(3, 1.5, 2.5, 16, 2);

    private static final PlayoffFormat FMT = new PlayoffFormat(4, 6, 0, false, false, 0);

    @BeforeEach
    void setUp() {
        leagues = mock(LeagueRepository.class);
        rosterSeasons = mock(RosterSeasonRepository.class);
        fixtures = mock(LeagueMatchupRepository.class);
        brackets = mock(LeagueBracketRepository.class);
        members = mock(LeagueMemberRepository.class);
        managers = mock(ManagerRepository.class);
        when(brackets.forLeagues(anyCollection())).thenReturn(List.of());
        when(managers.idsBySleeperUserId()).thenReturn(Map.of());
    }

    private RivalryService service() {
        return new RivalryService(leagues, rosterSeasons, fixtures, brackets, members, managers, props);
    }

    // ---------------------------------------------------------------- fixtures

    private static LeagueRow row(long id, int season, String sleeperId, String prev, String status) {
        return new LeagueRow(id, Sport.NFL, sleeperId, "IT league", season, 8, List.of("QB"), 0.0, prev, status);
    }

    private static StandingRow standing(long leagueId, int roster, Long manager) {
        return new StandingRow(leagueId, roster, manager, manager == null ? null : "M" + manager, null,
                0, 0, 0, 0.0, 0.0, null, null, null, Sport.NFL, "IT", null);
    }

    private static PairedGame game(int season, int week, int ra, long ma, double pa, int rb, long mb, double pb) {
        return new PairedGame(season, week, ra, ma, "M" + ma, null, BigDecimal.valueOf(pa),
                rb, mb, "M" + mb, null, BigDecimal.valueOf(pb));
    }

    private static ScheduledPair sched(int week, int ra, int rb) {
        return new ScheduledPair(2026, week, ra, 10L + ra, rb, 10L + rb);
    }

    private static List<ScheduledPair> baseSchedule() {
        return new ArrayList<>(List.of(
                sched(1, 1, 2), sched(1, 3, 4),
                sched(2, 1, 3), sched(2, 2, 4),
                sched(3, 1, 4), sched(3, 2, 3),
                sched(4, 1, 2), sched(4, 3, 4),
                sched(5, 1, 3), sched(5, 2, 4),
                sched(6, 1, 2), sched(6, 3, 4)));
    }

    private static List<StandingRow> fourManagers(long leagueId) {
        return List.of(standing(leagueId, 1, 11L), standing(leagueId, 2, 12L), standing(leagueId, 3, 13L),
                standing(leagueId, 4, 14L));
    }

    private static List<PairedGame> history2025() {
        List<PairedGame> g = new ArrayList<>();
        for (int w = 1; w <= 4; w++) {   // 1-2 and 3-4: a 2-2 series each
            boolean oddWeek = w % 2 == 1;
            g.add(game(2025, w, 1, 11L, oddWeek ? 100 : 90, 2, 12L, oddWeek ? 90 : 100));
            g.add(game(2025, w, 3, 13L, oddWeek ? 100 : 90, 4, 14L, oddWeek ? 90 : 100));
        }
        g.add(game(2025, 5, 1, 11L, 100, 4, 14L, 90));   // 1-4: one meeting
        g.add(game(2025, 5, 2, 12L, 100, 3, 13L, 90));   // 2-3: one meeting
        return g;
    }

    private static List<PairedGame> played2026(int through) {
        if (through < 1) return List.of();
        List<PairedGame> g = new ArrayList<>(List.of(game(2026, 1, 1, 11L, 120, 2, 12L, 100),
                game(2026, 1, 3, 13L, 60, 4, 14L, 70)));
        if (through >= 3) g.add(game(2026, 3, 1, 11L, 110, 4, 14L, 100));   // a later scored week, for the boundary
        return g;
    }

    private record World(OptionalInt leg, int scoredThrough, List<ScheduledPair> schedule, PlayoffFormat fmt,
                         List<StandingRow> standings26, String status26, boolean withHistory) {
        static World base() {
            return new World(OptionalInt.of(2), 1, baseSchedule(), FMT, fourManagers(2), "in_season", true);
        }
        World leg(OptionalInt l) { return new World(l, scoredThrough, schedule, fmt, standings26, status26, withHistory); }
        World scored(int t) { return new World(leg, t, schedule, fmt, standings26, status26, withHistory); }
        World schedule(List<ScheduledPair> s) { return new World(leg, scoredThrough, s, fmt, standings26, status26, withHistory); }
        World fmt(PlayoffFormat f) { return new World(leg, scoredThrough, schedule, f, standings26, status26, withHistory); }
        World standings(List<StandingRow> s) { return new World(leg, scoredThrough, schedule, fmt, s, status26, withHistory); }
        World status(String s) { return new World(leg, scoredThrough, schedule, fmt, standings26, s, withHistory); }
        World noHistory() { return new World(leg, scoredThrough, schedule, fmt, standings26, status26, false); }
    }

    private Recommendation run(World w) {
        LeagueRow l26 = row(2, 2026, "s26", w.withHistory() ? "s25" : null, w.status26());
        LeagueRow l25 = row(1, 2025, "s25", null, "complete");
        when(leagues.chainBySleeperId("s26")).thenReturn(w.withHistory() ? List.of(l26, l25) : List.of(l26));
        when(leagues.bySleeperId("s26")).thenReturn(Optional.of(l26));
        when(leagues.playoffFormat(2L)).thenReturn(Optional.ofNullable(w.fmt()));
        when(leagues.currentLeg(2L)).thenReturn(w.leg());
        when(rosterSeasons.forLeague(2L)).thenReturn(w.standings26());
        when(fixtures.pairedWithScores(eq(List.of(2L)), any())).thenReturn(played2026(w.scoredThrough()));
        when(fixtures.scheduledPairs(2L)).thenReturn(w.schedule());
        if (w.withHistory()) {
            when(leagues.playoffFormat(1L)).thenReturn(Optional.empty());
            when(leagues.currentLeg(1L)).thenReturn(OptionalInt.empty());
            when(rosterSeasons.forLeague(1L)).thenReturn(fourManagers(1));
            when(fixtures.pairedWithScores(eq(List.of(1L)), any())).thenReturn(history2025());
            when(fixtures.scheduledPairs(1L)).thenReturn(List.of());
        }
        return service().forLeague("s26", null).orElseThrow().recommendation();
    }

    private static List<Integer> offeredWeeks(WeekRecommendation w) {
        List<Integer> out = new ArrayList<>();
        out.add(w.recommended().week());
        w.others().forEach(o -> out.add(o.week()));
        out.sort(Integer::compare);
        return out;
    }

    private static WeekOption option(WeekRecommendation w, int week) {
        if (w.recommended().week() == week) return w.recommended();
        return w.others().stream().filter(o -> o.week() == week).findFirst().orElseThrow();
    }

    // ---------------------------------------------------------------- T009: eligibility and wiring

    @Test
    void eligibleWeeksAreAfterTheCurrentAndLastScoredWeekAndBeforeThePlayoffs() {
        // leg 2, scored through 1, playoff_week_start 6 -> 3, 4, 5 (week 6 is a stored playoff week).
        WeekRecommendation w = run(World.base()).week();
        assertEquals("OK", w.status());
        assertEquals(List.of(3, 4, 5), offeredWeeks(w));
        assertEquals(2, w.asOfWeek());
    }

    @Test
    void aNullLegFallsBackToTheLastScoredWeekAlone() {
        WeekRecommendation w = run(World.base().leg(OptionalInt.empty())).week();
        assertEquals(List.of(2, 3, 4, 5), offeredWeeks(w));
        assertEquals(1, w.asOfWeek(), "no leg, week 1 scored: the boundary is the last scored week");
        assertNull(run(World.base().leg(OptionalInt.empty()).scored(0)).week().asOfWeek());
    }

    @Test
    void asOfWeekIsTheEligibilityBoundaryNotTheRawLeg() {
        WeekRecommendation w = run(World.base().leg(OptionalInt.of(1)).scored(3)).week();   // S3
        assertEquals(List.of(4, 5), offeredWeeks(w));
        assertEquals(3, w.asOfWeek());
    }

    @Test
    void weekOneIsExcludedWhenLegIsOne() {
        WeekRecommendation w = run(World.base().leg(OptionalInt.of(1)).scored(0)).week();
        assertEquals(List.of(2, 3, 4, 5), offeredWeeks(w));
    }

    @Test
    void aStaleLegBelowTheLastScoredWeekDoesNotOfferAPlayedWeek() {
        WeekRecommendation w = run(World.base().leg(OptionalInt.of(1)).scored(1)).week();
        assertFalse(offeredWeeks(w).contains(1));
        assertEquals(List.of(2, 3, 4, 5), offeredWeeks(w));
    }

    @Test
    void aStoredPlayoffWeekIsNeverOfferedAndNeverCountsAsAMeeting() {
        // Week 6 holds 1-2 and 3-4. If it counted, reg(1-2) would be 3 and week 4 could not keep them.
        WeekRecommendation w = run(World.base()).week();
        assertFalse(offeredWeeks(w).contains(6));
        assertEquals(4, w.recommended().week());
        assertEquals("ALL_BEST_FIT", w.recommended().reason().code());
    }

    @Test
    void theWeekIsNullWheneverTheRecommendationIsNotOk() {
        Recommendation noHistory = run(World.base().noHistory());
        assertEquals("NO_HISTORY", noHistory.status());
        assertNull(noHistory.week());
        props = new RivalryProperties(null, null, null, null, 2);
        Recommendation missing = run(World.base());
        assertEquals("CONFIG_MISSING", missing.status());
        assertNull(missing.week());
    }

    @Test
    void theRecommendedWeekIsThePickersAndSpec034sPairingsAreUnmoved() {
        Recommendation with = run(World.base());
        assertEquals("OK", with.status());
        WeekRecommendation w = with.week();
        assertEquals("OK", w.status());
        assertEquals(2, w.cap());
        assertEquals("league rule", w.capLabel());
        assertNotNull(w.recommended());
        assertEquals(4, w.recommended().week());

        // The same world with the cap absent: the week pick is off, spec 034's answer must be identical.
        props = new RivalryProperties(3, 1.5, 2.5, 16, null);
        Recommendation without = run(World.base());
        assertEquals("OK", without.status());
        assertEquals(without.pairings(), with.pairings(), "rec.pairings is spec 034's set, untouched by the week pick");
        assertEquals(without.unpaired(), with.unpaired());
        assertEquals(without.thinCount(), with.thinCount());
    }

    @Test
    void aMissingCapIsConfigMissingForTheWeekAloneAndTheRecommendationStaysOk() {
        props = new RivalryProperties(3, 1.5, 2.5, 16, null);
        Recommendation rec = run(World.base());
        assertEquals("OK", rec.status());
        assertFalse(rec.pairings().isEmpty());
        assertEquals("CONFIG_MISSING", rec.week().status());
        assertNull(rec.week().cap());
        assertNull(rec.week().capLabel());
        assertNull(rec.week().recommended());
    }

    @Test
    void theLegIsReadOnceForTheWholePage() {
        run(World.base());
        verify(leagues, times(1)).currentLeg(2L);
    }

    // ---------------------------------------------------------------- reasons (F1)

    @Test
    void anAllBestFitWeekSaysSoWithEveryNumberInTheNumbersMap() {
        WeekOption w4 = run(World.base()).week().recommended();
        assertEquals("ALL_BEST_FIT", w4.reason().code());
        assertEquals("All 2 of the best pairings fit week 4 without any pair meeting more than 2 times.", w4.reason().text());
        assertEquals(2, w4.reason().numbers().get("cap"));
        assertEquals(0, w4.reason().numbers().get("heldBack"));
        assertEquals(2, w4.reason().numbers().get("bestPairings"));
        assertEquals(1, w4.reason().numbers().get("tiedWeeks"));
        assertEquals(w4.total(), w4.bestTotal(), 1e-9);
        assertTrue(w4.heldBack().isEmpty());
    }

    @Test
    void aLowerTotalWeekIsBestUnderCapAndNamesTheHeldBackPairsWithTheirWeeks() {
        WeekRecommendation w = run(World.base()).week();
        WeekOption w3 = option(w, 3);
        assertEquals("BEST_UNDER_CAP", w3.reason().code());
        assertTrue(w3.total() < w3.bestTotal());
        assertEquals(2, w3.heldBack().size());
        assertEquals(2, w3.reason().numbers().get("heldBack"));
        assertTrue(w3.reason().text().startsWith("2 of the best pairings would go over the 2-meeting limit in week 3, "
                + "so they're swapped out. This set scores "));
        var h12 = w3.heldBack().stream().filter(h -> h.aManagerId() == 11 && h.bManagerId() == 12).findFirst().orElseThrow();
        assertEquals(List.of(1, 4), h12.meetsInWeeks());
        assertEquals(3, h12.wouldMeet());
    }

    @Test
    void anEqualTotalButDifferentSetWeekIsNeverAllBestFit() {
        // Every pair has exactly one 1-0 meeting in 2025, so every full matching scores the same total.
        List<PairedGame> flat = new ArrayList<>();
        flat.add(game(2025, 1, 1, 11L, 100, 2, 12L, 90));
        flat.add(game(2025, 1, 3, 13L, 100, 4, 14L, 90));
        flat.add(game(2025, 2, 1, 11L, 100, 3, 13L, 90));
        flat.add(game(2025, 2, 2, 12L, 100, 4, 14L, 90));
        flat.add(game(2025, 3, 1, 11L, 100, 4, 14L, 90));
        flat.add(game(2025, 3, 2, 12L, 100, 3, 13L, 90));
        LeagueRow l26 = row(2, 2026, "s26", "s25", "in_season");
        LeagueRow l25 = row(1, 2025, "s25", null, "complete");
        when(leagues.chainBySleeperId("s26")).thenReturn(List.of(l26, l25));
        when(leagues.bySleeperId("s26")).thenReturn(Optional.of(l26));
        when(leagues.playoffFormat(2L)).thenReturn(Optional.of(FMT));
        when(leagues.playoffFormat(1L)).thenReturn(Optional.empty());
        when(leagues.currentLeg(2L)).thenReturn(OptionalInt.of(2));
        when(rosterSeasons.forLeague(2L)).thenReturn(fourManagers(2));
        when(rosterSeasons.forLeague(1L)).thenReturn(fourManagers(1));
        when(fixtures.pairedWithScores(eq(List.of(2L)), any())).thenReturn(List.of());
        when(fixtures.pairedWithScores(eq(List.of(1L)), any())).thenReturn(flat);
        when(fixtures.scheduledPairs(2L)).thenReturn(baseSchedule());
        when(fixtures.scheduledPairs(1L)).thenReturn(List.of());
        WeekRecommendation w = service().forLeague("s26", null).orElseThrow().recommendation().week();
        // Week 4 keeps the history-best set; weeks 3 and 5 reach an equal total with a different set.
        assertEquals("ALL_BEST_FIT", w.recommended().reason().code());
        assertEquals(4, w.recommended().week());
        for (WeekOption o : w.others()) {
            assertEquals("EQUAL_UNDER_CAP", o.reason().code(), "week " + o.week());
            assertEquals(o.bestTotal(), o.total(), 1e-9);
            assertTrue(o.reason().text().contains("an equally strong set replaces"));
            assertNotEquals(0, o.heldBack().size());
        }
    }

    private static List<ScheduledPair> overCapSchedule() {
        List<ScheduledPair> s = baseSchedule();
        s.removeIf(p -> p.week() == 5);
        s.add(sched(5, 1, 2));
        s.add(sched(5, 3, 4));   // 1-2 and 3-4 meet in weeks 1, 4 and 5
        return s;
    }

    /** B1/S2: within one tie group only the first week claims "earliest"; the rest name that week. */
    private static void assertTieWording(WeekRecommendation w) {
        List<WeekOption> all = new ArrayList<>();
        all.add(w.recommended());
        all.addAll(w.others());
        all.sort(java.util.Comparator.comparingInt(WeekOption::week));
        java.util.Map<String, List<WeekOption>> groups = new java.util.LinkedHashMap<>();
        for (WeekOption o : all) {
            if (((Number) o.reason().numbers().get("tiedWeeks")).intValue() > 1) {
                groups.computeIfAbsent(o.reason().code() + "|" + o.total() + "|" + o.heldBack().size(),
                        k -> new ArrayList<>()).add(o);
            }
        }
        for (List<WeekOption> g : groups.values()) {
            WeekOption first = g.get(0);
            assertTrue(first.reason().text().contains("It's the earliest of "), first.reason().text());
            for (WeekOption o : g.subList(1, g.size())) {
                assertFalse(o.reason().text().contains("earliest"), o.reason().text());
                assertTrue(o.reason().text().contains("Ties with week " + first.week() + " on every count"),
                        o.reason().text());
                assertEquals(first.week(), o.reason().numbers().get("firstTiedWeek"));
            }
        }
    }

    @Test
    void onlyTheFirstWeekOfAnAllBestFitTieGroupSaysEarliest() {
        props = new RivalryProperties(3, 1.5, 2.5, 16, 3);   // cap 3: every week keeps the best set
        WeekRecommendation w = run(World.base()).week();
        assertEquals(3, w.recommended().week());
        assertEquals("ALL_BEST_FIT", w.recommended().reason().code());
        assertTrue(w.recommended().reason().text().endsWith("It's the earliest of 3 weeks where they all fit."));
        assertEquals(2, w.others().size());
        for (WeekOption o : w.others()) {
            assertEquals("ALL_BEST_FIT", o.reason().code());
            assertFalse(o.reason().text().contains("earliest"), o.reason().text());
            assertTrue(o.reason().text().endsWith("Ties with week 3 on every count; week 3 comes first."));
        }
        assertTieWording(w);
    }

    @Test
    void equalAndBestUnderCapTiesAlsoNameTheEarliestOnlyOnTheFirstWeek() {
        assertTieWording(run(World.base()).week());   // cap 2
        assertTieWording(run(World.base().schedule(overCapSchedule())).week());
    }

    @Test
    void theCapWordsTheReasonAndHeldBackCarriesTheRealMeetingCount() {
        props = new RivalryProperties(3, 1.5, 2.5, 16, 3);
        WeekRecommendation w = run(World.base().schedule(overCapSchedule())).week();
        assertEquals(3, w.cap());
        WeekOption w3 = option(w, 3);   // 1-2 and 3-4 already meet 3 times in other weeks -> a fourth meeting
        assertEquals(3, w3.reason().numbers().get("cap"));
        assertTrue(w3.reason().text().contains("go over the 3-meeting limit in week 3"), w3.reason().text());
        assertFalse(w3.reason().text().contains("third"));
        assertEquals(2, w3.heldBack().size());
        for (var h : w3.heldBack()) {
            assertEquals(4, h.wouldMeet());
            assertEquals(List.of(1, 4, 5), h.meetsInWeeks());
        }
        WeekOption w4 = option(w, 4);
        assertEquals("ALL_BEST_FIT", w4.reason().code());
        assertTrue(w4.reason().text().contains("more than 3 times"), w4.reason().text());
        assertFalse(w4.reason().text().contains("third"));
    }

    @Test
    void anOverCapPairPrintsItsRealCountAndNeverCountsThisWeekInMeetsInWeeks() {
        WeekRecommendation w = run(World.base().schedule(overCapSchedule())).week();   // cap 2
        WeekOption w3 = option(w, 3);
        var h = w3.heldBack().stream().filter(x -> x.aManagerId() == 11 && x.bManagerId() == 12).findFirst().orElseThrow();
        assertEquals(4, h.wouldMeet(), "reg 3, not scheduled in week 3, so a fourth meeting");
        assertEquals(List.of(1, 4, 5), h.meetsInWeeks());
        WeekOption w4 = option(w, 4);   // their own week: reg 3 - 1 + 1 = 3, still over 2
        var own = w4.heldBack().stream().filter(x -> x.aManagerId() == 11 && x.bManagerId() == 12).findFirst().orElseThrow();
        assertEquals(3, own.wouldMeet());
        assertEquals(List.of(1, 5), own.meetsInWeeks(), "week 4 itself is excluded");
    }

    @Test
    void aSingleBestPairingIsWordedInTheSingular() {
        // Three managers: one pair plays, one sits out. Cap 2.
        List<ScheduledPair> s = List.of(sched(1, 1, 2), sched(2, 1, 3), sched(3, 2, 3), sched(4, 1, 2),
                sched(5, 1, 3));
        List<StandingRow> three = List.of(standing(2, 1, 11L), standing(2, 2, 12L), standing(2, 3, 13L));
        WeekRecommendation w = run(World.base().schedule(s).standings(three)).week();
        assertEquals("OK", w.status());
        assertTrue(option(w, 4).reason().text().startsWith("The best pairing fits week 4 without any pair meeting"));
        WeekOption any = w.recommended();
        assertFalse(any.reason().text().contains("All 1"), any.reason().text());
        for (WeekOption o : w.others()) assertFalse(o.reason().text().contains("All 1"), o.reason().text());
    }

    // ---------------------------------------------------------------- T014: US2 mappings

    @Test
    void alreadyScheduledIsTrueExactlyForPairsThatAreOnThatWeeksSchedule() {
        WeekOption w3 = option(run(World.base()).week(), 3);   // chosen 1-4 + 2-3, both scheduled in week 3
        assertEquals(2, w3.pairings().size());
        assertTrue(w3.pairings().stream().allMatch(RivalryService.WeekPairing::alreadyScheduled));
        assertTrue(w3.displaced().isEmpty());
        WeekOption w5 = option(run(World.base()).week(), 5);   // chosen 1-4 + 2-3, scheduled are 1-3 + 2-4
        assertTrue(w5.pairings().stream().noneMatch(RivalryService.WeekPairing::alreadyScheduled));
    }

    @Test
    void displacedSplitsMetFromStillMeetsAroundTheAsOfWeekAndExcludesThisWeek() {
        // leg 2: 1-3 meets in weeks 2 and 5. For W=5 the week-2 meeting is already past.
        WeekOption w5 = option(run(World.base()).week(), 5);
        var d13 = w5.displaced().stream().filter(d -> d.aManagerId() == 11 && d.bManagerId() == 13).findFirst().orElseThrow();
        assertEquals(List.of(2), d13.metInWeeks());
        assertEquals(List.of(), d13.stillMeetsInWeeks());
        // leg 1, nothing scored: the week-2 meeting is still ahead.
        WeekOption early = option(run(World.base().leg(OptionalInt.of(1)).scored(0)).week(), 5);
        var e13 = early.displaced().stream().filter(d -> d.aManagerId() == 11 && d.bManagerId() == 13).findFirst().orElseThrow();
        assertEquals(List.of(), e13.metInWeeks());
        assertEquals(List.of(2), e13.stillMeetsInWeeks());
    }

    @Test
    void aDisplacedPairWhoseOnlyOtherMeetingIsPastHasNothingStillAhead() {
        // Week 5 schedules 1-3 and 2-4; the chosen 1-4 + 2-3 displaces them, and each met only in week 2.
        WeekOption w5 = option(run(World.base()).week(), 5);
        var d24 = w5.displaced().stream().filter(d -> d.aManagerId() == 12 && d.bManagerId() == 14).findFirst().orElseThrow();
        assertEquals(List.of(2), d24.metInWeeks(), "2-4 met in week 2 and is scheduled here");
        assertEquals(List.of(), d24.stillMeetsInWeeks());
    }

    @Test
    void aPairAlreadyAboveTheCapIsNamedInTheOverCapNote() {
        List<ScheduledPair> s = baseSchedule();
        s.removeIf(p -> p.week() == 5);
        s.add(sched(5, 1, 2));
        s.add(sched(5, 3, 4));   // 1-2 and 3-4 now meet in weeks 1, 4 and 5
        WeekRecommendation w = run(World.base().schedule(s)).week();
        assertNotNull(w.overCapNote());
        assertTrue(w.overCapNote().contains("M11 and M12 meet 3 times"));
        assertTrue(w.overCapNote().contains("M13 and M14 meet 3 times"));
        for (int week : offeredWeeks(w)) {
            WeekOption o = option(w, week);
            assertTrue(o.pairings().stream().noneMatch(p -> p.pairing().aManagerId() == 11 && p.pairing().bManagerId() == 12),
                    "week " + week);
        }
        assertNull(run(World.base()).week().overCapNote());
    }

    // ---------------------------------------------------------------- T018: statuses

    private void assertRefusal(WeekRecommendation w, String status) {
        assertEquals(status, w.status());
        assertNull(w.recommended());
        assertTrue(w.others().isEmpty());
    }

    @Test
    void anEmptyScheduleIsNotPublished() {
        assertRefusal(run(World.base().schedule(List.of())).week(), "NOT_PUBLISHED");
    }

    @Test
    void aRegularSeasonWeekWithMissingGamesIsNotPublished() {
        List<ScheduledPair> s = baseSchedule();
        s.removeIf(p -> p.week() == 4);   // week 4 absent entirely
        assertRefusal(run(World.base().schedule(s)).week(), "NOT_PUBLISHED");
        List<ScheduledPair> short4 = baseSchedule();
        short4.removeIf(p -> p.week() == 4 && p.aRosterId() == 3);   // week 4 has one game instead of two
        assertRefusal(run(World.base().schedule(short4)).week(), "NOT_PUBLISHED");
    }

    @Test
    void aMissingSeasonFormatOrPlayoffStartBelowTwoIsNoSeasonFormat() {
        assertRefusal(run(World.base().fmt(null)).week(), "NO_SEASON_FORMAT");
        assertRefusal(run(World.base().fmt(new PlayoffFormat(4, 1, 0, false, false, 0))).week(), "NO_SEASON_FORMAT");
        assertRefusal(run(World.base().fmt(new PlayoffFormat(0, 0, 0, false, false, null))).week(), "NO_SEASON_FORMAT");
    }

    @Test
    void aRosterWithNoManagerOrAManagerWithTwoRostersIsUnsupportedRosters() {
        List<StandingRow> orphan = List.of(standing(2, 1, 11L), standing(2, 2, 12L), standing(2, 3, 13L),
                standing(2, 4, 14L), standing(2, 5, null));
        // 5 rosters: floor(5/2) = 2 games a week still matches the schedule, so the roster gate is what refuses.
        assertRefusal(run(World.base().standings(orphan)).week(), "UNSUPPORTED_ROSTERS");
        List<StandingRow> twice = List.of(standing(2, 1, 11L), standing(2, 2, 12L), standing(2, 3, 13L),
                standing(2, 4, 11L));
        assertRefusal(run(World.base().standings(twice)).week(), "UNSUPPORTED_ROSTERS");
    }

    @Test
    void noWeekAfterTheCurrentOneIsNoEligibleWeek() {
        assertRefusal(run(World.base().leg(OptionalInt.of(5))).week(), "NO_ELIGIBLE_WEEK");
    }

    @Test
    void aCompleteSeasonIsNoEligibleWeekWhateverTheLegSays() {
        // leg 1 would otherwise offer weeks 2-5; the season being complete overrides it.
        assertRefusal(run(World.base().leg(OptionalInt.of(1)).scored(0).status("complete")).week(), "NO_ELIGIBLE_WEEK");
    }

    @Test
    void whenEveryEligibleWeekBreaksTheCapNoWeekFitsAndSpec034sPairingsStillShow() {
        // cap 1, leg 3: weeks 4 and 5 remain. Every pair meets at least once already, and none of them is
        // scheduled in a way that lets a full set stay at one meeting (week 3, which would, is past).
        props = new RivalryProperties(3, 1.5, 2.5, 16, 1);
        Recommendation rec = run(World.base().leg(OptionalInt.of(3)));
        assertEquals("OK", rec.status());
        assertRefusal(rec.week(), "NO_WEEK_FITS");
        assertEquals(List.of(4, 5), rec.week().unfitWeeks());
        assertFalse(rec.pairings().isEmpty());
        assertEquals(1, rec.week().cap());
    }

    @Test
    void thereIsNoNoteFieldOnTheWeekRecordSoRecNoteIsRenderedOnce() {
        assertThrows(NoSuchMethodException.class, () -> WeekRecommendation.class.getMethod("note"));
    }
}
