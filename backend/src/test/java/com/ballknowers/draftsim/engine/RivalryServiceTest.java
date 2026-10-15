package com.ballknowers.draftsim.engine;

import com.ballknowers.draftsim.config.RivalryProperties;
import com.ballknowers.draftsim.domain.Sport;
import com.ballknowers.draftsim.engine.RivalryService.Cell;
import com.ballknowers.draftsim.engine.RivalryService.PairRecord;
import com.ballknowers.draftsim.engine.RivalryService.Response;
import com.ballknowers.draftsim.store.LeagueBracketRepository;
import com.ballknowers.draftsim.store.LeagueBracketRepository.Match;
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
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalInt;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * specs/034 T015: the service against mocked repositories, one tiny world per behaviour. The same
 * {@link HeadToHeadService#outcome} and {@code pairedWithScores} shape the production path uses.
 */
class RivalryServiceTest {

    private LeagueRepository leagues;
    private RosterSeasonRepository rosterSeasons;
    private LeagueMatchupRepository fixtures;
    private LeagueBracketRepository brackets;
    private LeagueMemberRepository members;
    private ManagerRepository managers;
    private RivalryProperties props = new RivalryProperties(3, 1.5, 2.5, 16, 2);

    private static final ObjectMapper JSON = new ObjectMapper();

    @BeforeEach
    void setUp() {
        leagues = mock(LeagueRepository.class);
        rosterSeasons = mock(RosterSeasonRepository.class);
        fixtures = mock(LeagueMatchupRepository.class);
        brackets = mock(LeagueBracketRepository.class);
        members = mock(LeagueMemberRepository.class);
        managers = mock(ManagerRepository.class);
        when(brackets.forLeagues(anyCollection())).thenReturn(List.of());
        when(managers.idsBySleeperUserId()).thenReturn(Map.of("u12", 12L));
    }

    private RivalryService service() {
        return new RivalryService(leagues, rosterSeasons, fixtures, brackets, members, managers, props);
    }

    // ---------------------------------------------------------------- fixture helpers

    private static LeagueRow row(long id, int season, String sleeperId, String prev) {
        return new LeagueRow(id, Sport.NFL, sleeperId, "IT league", season, 8, List.of("QB"), 0.0, prev,
                season < 2026 ? "complete" : "in_season");
    }

    /** Manager id = {@code base + roster}. */
    private static StandingRow standing(long leagueId, int roster, Long manager) {
        return new StandingRow(leagueId, roster, manager, manager == null ? null : "M" + manager, null,
                0, 0, 0, 0.0, 0.0, null, null, null, Sport.NFL, "IT", null);
    }

    private static PairedGame game(int season, int week, int ra, Long ma, double pa, int rb, Long mb, double pb) {
        return new PairedGame(season, week, ra, ma, ma == null ? null : "M" + ma, null, BigDecimal.valueOf(pa),
                rb, mb, mb == null ? null : "M" + mb, null, BigDecimal.valueOf(pb));
    }

    private static ScheduledPair sched(int season, int week, int ra, Long ma, int rb, Long mb) {
        return new ScheduledPair(season, week, ra, ma, rb, mb);
    }

    private void stubLeague(LeagueRow l, List<StandingRow> standings, List<PairedGame> played,
                            List<ScheduledPair> scheduled, PlayoffFormat fmt, OptionalInt leg) {
        when(leagues.bySleeperId(l.sleeperId())).thenReturn(Optional.of(l));
        when(leagues.playoffFormat(l.id())).thenReturn(Optional.ofNullable(fmt));
        when(leagues.currentLeg(l.id())).thenReturn(leg);
        when(rosterSeasons.forLeague(l.id())).thenReturn(standings);
        when(fixtures.pairedWithScores(eq(List.of(l.id())), any())).thenReturn(played);
        when(fixtures.scheduledPairs(l.id())).thenReturn(scheduled);
    }

    private static PairRecord pair(Response r, long a, long b) {
        return r.matrix().pairs().stream()
                .filter(p -> p.aManagerId() == Math.min(a, b) && p.bManagerId() == Math.max(a, b))
                .findFirst().orElseThrow();
    }

    private static Cell cell(Response r, long manager, int week) {
        return r.seasonGrid().cells().stream()
                .filter(c -> c.managerId() == manager && c.week() == week).findFirst().orElseThrow();
    }

    /**
     * World A: managers 11-14 (roster n = manager 10+n) in 2025 and 2026, plus manager 15 new in 2026. 2025 is
     * fully scored; 2026 has week 1 scored (when {@code scored2026}) and weeks 2-3 only scheduled.
     */
    private Response worldA(boolean scored2026) {
        LeagueRow l25 = row(1, 2025, "s25", null);
        LeagueRow l26 = row(2, 2026, "s26", "s25");
        when(leagues.chainBySleeperId("s26")).thenReturn(List.of(l26, l25));
        List<StandingRow> st25 = List.of(standing(1, 1, 11L), standing(1, 2, 12L), standing(1, 3, 13L), standing(1, 4, 14L));
        List<StandingRow> st26 = List.of(standing(2, 1, 11L), standing(2, 2, 12L), standing(2, 3, 13L),
                standing(2, 4, 14L), standing(2, 5, 15L));
        List<PairedGame> p25 = List.of(
                game(2025, 1, 1, 11L, 100, 2, 12L, 90), game(2025, 1, 3, 13L, 70, 4, 14L, 60),
                game(2025, 2, 1, 11L, 50, 3, 13L, 60), game(2025, 2, 2, 12L, 80, 4, 14L, 80),
                game(2025, 3, 1, 11L, 90, 2, 12L, 100));
        List<PairedGame> p26 = scored2026
                ? List.of(game(2026, 1, 1, 11L, 120, 2, 12L, 100), game(2026, 1, 3, 13L, 60, 4, 14L, 70))
                : List.of();
        List<ScheduledPair> s26 = List.of(
                sched(2026, 1, 1, 11L, 2, 12L), sched(2026, 1, 3, 13L, 4, 14L),
                sched(2026, 2, 1, 11L, 4, 14L), sched(2026, 2, 2, 12L, 3, 13L),
                sched(2026, 3, 1, 11L, 3, 13L), sched(2026, 3, 2, 12L, 4, 14L));
        stubLeague(l25, st25, p25, List.of(), null, OptionalInt.empty());
        stubLeague(l26, st26, p26, s26, null, scored2026 ? OptionalInt.of(2) : OptionalInt.empty());
        return service().forLeague("s26", "u12").orElseThrow();
    }

    // ---------------------------------------------------------------- matrix

    @Test
    void theMatrixCountsAreRightAndTheTotalsAddUp() {
        Response r = worldA(true);
        PairRecord p1112 = pair(r, 11, 12);
        // 2025: 11 beat 12 (100-90), 12 beat 11 (100-90 wk3); 2026 wk1: 11 beat 12 (120-100).
        assertEquals(3, p1112.meetings());
        assertEquals(2, p1112.aWins());
        assertEquals(1, p1112.bWins());
        assertEquals(0, p1112.ties());
        assertEquals("MET", p1112.status());
        assertEquals(p1112.meetings(),
                p1112.byKind().values().stream().mapToInt(k -> k.aWins() + k.bWins() + k.ties()).sum(),
                "byKind sums to the total (SC-002)");
        assertEquals(3, p1112.games().size());
        assertEquals(1, pair(r, 12, 14).ties(), "a tie is its own outcome");
    }

    @Test
    void aSinglePairAppearsOnceAndALookupMirrors() {
        Response r = worldA(true);
        int n = r.matrix().rows().size();
        assertEquals(5, n);
        assertEquals(n * (n - 1) / 2, r.matrix().pairs().size(), "unordered: no mirrored duplicate on the wire");
        assertTrue(r.matrix().pairs().stream().allMatch(p -> p.aManagerId() < p.bManagerId()));
    }

    @Test
    void statusesNotYetMetAndNoSharedHistoryAreNotNeverMet() {
        Response r = worldA(true);
        // 11 vs 14 never played in 2025, and the 2026 schedule has them in week 2.
        assertEquals("NOT_YET_MET", pair(r, 11, 14).status());
        assertEquals(0, pair(r, 11, 14).meetings());
        assertEquals("NOT_YET_MET", pair(r, 12, 13).status());
        // With 2026 week 1 scored, 15 owns a roster in a scored season and never met 11: NEVER_MET.
        assertEquals("NEVER_MET", pair(r, 11, 15).status());
        // With no 2026 week scored, 15's only season is unscored: nothing shared yet.
        assertEquals("NO_SHARED_HISTORY", pair(worldA(false), 11, 15).status());
    }

    @Test
    void rowsCarryTheCallerFlagAndTheUsernameFallbackForTheTeamName() {
        Response r = worldA(true);
        assertTrue(r.matrix().rows().stream().filter(x -> x.managerId() == 12).findFirst().orElseThrow().isMe());
        assertEquals(1, r.matrix().rows().stream().filter(RivalryService.Row::isMe).count());
        assertEquals("M12", r.matrix().rows().stream().filter(x -> x.managerId() == 12).findFirst().orElseThrow().teamName());
        when(members.teamNamesByManager(2L)).thenReturn(Map.of(12L, "Gridiron Gang"));
        Response again = service().forLeague("s26", "u12").orElseThrow();
        assertEquals("Gridiron Gang", again.matrix().rows().stream().filter(x -> x.managerId() == 12).findFirst().orElseThrow().teamName());
    }

    @Test
    void aSelfPairAndAGameBetweenTwoUnnamedRostersAreDroppedAndAnUnnamedOpponentStillCountsForAllOpponents() {
        LeagueRow l = row(1, 2025, "s1", null);
        when(leagues.chainBySleeperId("s1")).thenReturn(List.of(l));
        List<StandingRow> st = List.of(standing(1, 1, 11L), standing(1, 2, 12L), standing(1, 3, 11L), standing(1, 4, null),
                standing(1, 5, null));
        List<PairedGame> played = List.of(
                game(2025, 1, 1, 11L, 100, 3, 11L, 90),     // manager 11 on both rosters: dropped
                game(2025, 1, 4, null, 80, 5, null, 70),    // neither named: dropped
                game(2025, 2, 1, 11L, 100, 4, null, 90),    // an unnamed opponent: counts for 11 only
                game(2025, 3, 1, 11L, 100, 2, 12L, 90));
        stubLeague(l, st, played, List.of(), null, OptionalInt.empty());

        Response r = service().forLeague("s1", null).orElseThrow();

        assertEquals(2, r.matrix().rows().size(), "one row per manager; the duplicate roster and unnamed rosters add none");
        var r11 = r.matrix().rows().stream().filter(x -> x.managerId() == 11).findFirst().orElseThrow();
        assertEquals(2, r11.allOpponents().meetings(), "vs the unnamed opponent and vs 12");
        assertEquals(1, r11.formerOpponentMeetings(), "the unnamed opponent is not a current manager");
        assertEquals(1, pair(r, 11, 12).meetings());
    }

    // ---------------------------------------------------------------- the two-week final (F3)

    private static final PlayoffFormat TYPE1 = new PlayoffFormat(6, 15, 0, false, false, 1);

    private List<Match> westCoastBracket() {
        return List.of(
                new Match(1, 1, 1, 1, 2, 1, 2, null), new Match(1, 2, 1, 4, 5, 4, 5, null),
                new Match(1, 3, 2, 6, 4, 6, 4, null), new Match(1, 4, 2, 3, 1, 3, 1, null),
                new Match(1, 5, 2, 2, 5, 2, 5, 5),
                new Match(1, 6, 3, 6, 3, 6, 3, 1), new Match(1, 7, 3, 4, 1, 4, 1, 3));
    }

    private Response westCoast() {
        LeagueRow l = row(1, 2025, "wc", null);
        when(leagues.chainBySleeperId("wc")).thenReturn(List.of(l));
        List<StandingRow> st = new ArrayList<>();
        for (int i = 1; i <= 8; i++) st.add(standing(1, i, 100L + i));
        List<PairedGame> played = List.of(
                game(2025, 15, 1, 101L, 100, 2, 102L, 90), game(2025, 15, 4, 104L, 100, 5, 105L, 90),
                game(2025, 15, 7, 107L, 100, 8, 108L, 90),
                game(2025, 16, 4, 104L, 90, 6, 106L, 100), game(2025, 16, 1, 101L, 90, 3, 103L, 100),
                game(2025, 16, 2, 102L, 100, 5, 105L, 90),
                game(2025, 17, 3, 103L, 111.96, 6, 106L, 129.30), game(2025, 17, 1, 101L, 80, 4, 104L, 90),
                game(2025, 18, 3, 103L, 142.56, 6, 106L, 139.74), game(2025, 18, 1, 101L, 80, 4, 104L, 90));
        List<ScheduledPair> sched = new ArrayList<>();
        for (PairedGame g : played) sched.add(sched(2025, g.week(), g.aRosterId(), g.aManagerId(), g.bRosterId(), g.bManagerId()));
        when(brackets.forLeagues(anyCollection())).thenReturn(westCoastBracket());
        stubLeague(l, st, played, sched, TYPE1, OptionalInt.of(18));
        return service().forLeague("wc", null).orElseThrow();
    }

    @Test
    void theWestCoastTwoWeekFinalIsOneFinalWonByTheBracketWinnerAndTheLegsSplitOneOne() {
        Response r = westCoast();
        PairRecord p = pair(r, 103, 106);
        assertEquals(2, p.meetings(), "per-week totals keep parity with the versus page");
        assertEquals(1, p.aWins());
        assertEquals(1, p.bWins(), "read per week the final is 1-1");
        assertEquals(1, p.finals().size(), "...but it is ONE final");
        var f = p.finals().get(0);
        assertEquals(2025, f.season());
        assertEquals(254.52, f.aPoints(), 1e-9, "103 is A (lower id): summed legs 111.96 + 142.56");
        assertEquals(269.04, f.bPoints(), 1e-9);
        assertEquals("B", f.winner(), "the bracket's w is roster 6 = manager 106");
        assertTrue(f.twoWeek());
        assertEquals(2, p.byKind().get("PLAYOFF").aWins() + p.byKind().get("PLAYOFF").bWins());
    }

    @Test
    void theFinalRankedFirstAndAPlayoffBonusIsPerBracketMatchNotPerWeek() {
        Response r = westCoast();
        // 2025 only has one season in the chain, so the recommendation is NO_HISTORY: score it directly.
        assertEquals("NO_HISTORY", r.recommendation().status());
        PairRecord p = pair(r, 103, 106);
        var stats = new RivalryMatching.PairStats(p.meetings(), p.aWins(), p.bWins(), p.ties(), 1, true);
        assertEquals(2.0 + 1.5 + 2.5, RivalryMatching.score(stats, 1.5, 2.5), 1e-9,
                "a two-week final counts one playoff meeting, not two (F3)");
    }

    @Test
    void placementGamesGetTheirOwnKindAndNeverCountAsPlayoffMeetings() {
        Response r = westCoast();
        PairRecord p = pair(r, 102, 105);   // the 5th-place game, week 16
        assertEquals(1, p.meetings());
        assertEquals(1, p.byKind().get("PLACEMENT").aWins());
        assertEquals(0, p.byKind().get("PLAYOFF").aWins());
        assertTrue(p.finals().isEmpty());
    }

    @Test
    void consolationGamesAreSplitOutOfThePlayoffWeeks() {
        Response r = westCoast();
        assertEquals(1, pair(r, 107, 108).byKind().get("CONSOLATION").aWins());
    }

    @Test
    void playoffWeekCellsSayOutNotByeUnlessTheBracketSaysBye() {
        Response r = westCoast();
        // Round-1 bye: 6 and 3 hold round-2 slots and no round-1 slot.
        assertEquals("BYE", cell(r, 106, 15).state());
        assertEquals("BYE", cell(r, 103, 15).state());
        // Week 17: 7 and 8 never made the bracket, and 2 and 5 were out after round 2.
        for (long m : new long[]{102, 105, 107, 108}) assertEquals("NO_GAME", cell(r, m, 17).state(), "manager " + m);
        assertEquals("OPPONENT", cell(r, 106, 17).state());
        assertEquals("SCHEDULE", cell(r, 106, 17).source());
        assertNotNull(r.seasonGrid().playoffWindowNote(), "round type 1 can't say how many playoff weeks there are");
    }

    // ---------------------------------------------------------------- season grid

    @Test
    void everyOpponentCellIsMirroredAndNamesTheSamePair() {
        Response r = worldA(true);
        for (Cell c : r.seasonGrid().cells()) {
            if (!"OPPONENT".equals(c.state())) continue;
            Cell back = cell(r, c.opponentManagerId(), c.week());
            assertEquals("OPPONENT", back.state());
            assertEquals(c.managerId(), back.opponentManagerId(), "SC-003: if A's cell names B, B's cell names A");
        }
    }

    @Test
    void thePriorRecordCountsOnlyMeetingsStrictlyBeforeTheCell() {
        Response r = worldA(true);
        // Week 1 of 2026, 11 v 12: the whole 2025 record (1-1) and nothing from week 1 itself.
        var wk1 = cell(r, 11, 1);
        assertEquals(1, wk1.priorRecord().wins());
        assertEquals(1, wk1.priorRecord().losses());
        assertEquals(2, wk1.priorRecord().meetings());
        assertNotNull(wk1.result());
        assertEquals("W", wk1.result().outcome());
        assertEquals(120.0, wk1.result().points(), 1e-9);
        // 12's week-1 cell mirrors: loss now, same prior record from the other side.
        assertEquals("L", cell(r, 12, 1).result().outcome());
        // 11 v 14 in week 2: never met, and not scored.
        var wk2 = cell(r, 11, 2);
        assertEquals(14L, wk2.opponentManagerId());
        assertEquals(0, wk2.priorRecord().meetings());
        assertNull(wk2.result(), "an unplayed week has no result");
    }

    @Test
    void aWeekAfterAScoredGameCountsItInThePriorRecord() {
        Response r = worldA(true);
        // 11 v 13 in week 3: 2025 wk2 (13 won) and nothing in 2026 yet.
        var c = cell(r, 11, 3);
        assertEquals(13L, c.opponentManagerId());
        assertEquals(0, c.priorRecord().wins());
        assertEquals(1, c.priorRecord().losses());
        // 12 v 14 in week 3: tied in 2025.
        assertEquals(1, cell(r, 12, 3).priorRecord().ties());
    }

    @Test
    void aManagerWithNoGameInAStoredRegularWeekIsOnABye() {
        Response r = worldA(true);
        assertEquals("BYE", cell(r, 15, 1).state(), "15 has no pairing in a week that has others");
        assertNull(cell(r, 15, 1).opponentManagerId());
    }

    /** F9: the newest season has no scores and no leg; the answer is still about the URL's league. */
    @Test
    void aNewestSeasonWithNoScoresStillAnswersForTheUrlsLeague() {
        Response r = worldA(false);
        assertEquals(2026, r.season());
        assertEquals(List.of(2026, 2025), r.seasonsCovered());
        assertNull(r.seasonGrid().currentWeek(), "leg is never defaulted");
        assertEquals("OK", r.seasonGrid().scheduleStatus());
        assertEquals("OPPONENT", cell(r, 11, 1).state());
        assertNull(cell(r, 11, 1).result());
        assertEquals(2, cell(r, 11, 1).priorRecord().meetings(), "prior record still reads the 2025 season");
        verify(leagues).bySleeperId("s26");
    }

    @Test
    void anUnpublishedScheduleIsNotPublishedWithNoCells() {
        LeagueRow l = row(1, 2026, "s1", null);
        when(leagues.chainBySleeperId("s1")).thenReturn(List.of(l));
        stubLeague(l, List.of(standing(1, 1, 11L), standing(1, 2, 12L)), List.of(), List.of(), null, OptionalInt.empty());
        Response r = service().forLeague("s1", null).orElseThrow();
        assertEquals("NOT_PUBLISHED", r.seasonGrid().scheduleStatus());
        assertTrue(r.seasonGrid().cells().isEmpty());
        assertTrue(r.seasonGrid().weeks().isEmpty());
    }

    /** F7: the playoff week being played has no stored pairing; a settled bracket row names the opponent. */
    @Test
    void theCurrentPlayoffWeekTakesOpponentsFromTheBracketOnlyOncePlayoffsHaveBegun() {
        LeagueRow l = row(1, 2026, "s1", null);
        when(leagues.chainBySleeperId("s1")).thenReturn(List.of(l));
        List<StandingRow> st = new ArrayList<>();
        for (int i = 1; i <= 8; i++) st.add(standing(1, i, 100L + i));
        List<ScheduledPair> sched = new ArrayList<>();
        for (int w = 1; w <= 14; w++) {
            sched.add(sched(2026, w, 1, 101L, 2, 102L));
            sched.add(sched(2026, w, 3, 103L, 4, 104L));
        }
        PlayoffFormat type0 = new PlayoffFormat(6, 15, 0, false, false, 0);
        when(brackets.forLeagues(anyCollection())).thenReturn(westCoastBracket());

        stubLeague(l, st, List.of(), sched, type0, OptionalInt.of(15));
        Response begun = service().forLeague("s1", null).orElseThrow();
        assertEquals("OPPONENT", cell(begun, 101, 15).state());
        assertEquals("BRACKET", cell(begun, 101, 15).source());
        assertEquals(102L, cell(begun, 101, 15).opponentManagerId());
        assertEquals("BYE", cell(begun, 106, 15).state(), "the bracket seeds 6 straight into round 2");
        assertEquals("TBD", cell(begun, 107, 15).state(), "a consolation game isn't named by the winners bracket");
        assertNotNull(begun.seasonGrid().tbdNote());
        assertFalse(begun.seasonGrid().tbdNote().toLowerCase().contains("ingest"));

        stubLeague(l, st, List.of(), sched, type0, OptionalInt.of(8));
        Response early = service().forLeague("s1", null).orElseThrow();
        assertEquals("TBD", cell(early, 101, 15).state(),
                "before the playoffs the bracket is a provisional seeding that moves with the standings");
    }

    // ---------------------------------------------------------------- recommendation

    @Test
    void aLeagueWithNoEarlierSeasonHasNoHistory() {
        LeagueRow l = row(1, 2026, "s1", null);
        when(leagues.chainBySleeperId("s1")).thenReturn(List.of(l));
        stubLeague(l, List.of(standing(1, 1, 11L), standing(1, 2, 12L)),
                List.of(game(2026, 1, 1, 11L, 100, 2, 12L, 90)), List.of(), null, OptionalInt.empty());
        var rec = service().forLeague("s1", null).orElseThrow().recommendation();
        assertEquals("NO_HISTORY", rec.status());
        assertTrue(rec.pairings().isEmpty());
        assertEquals(RivalryService.RECOMMENDATION_NOTE, rec.note());
    }

    @Test
    void aMissingOrPartialConfigBlockIsConfigMissing() {
        props = new RivalryProperties(null, null, null, null, 2);
        assertEquals("CONFIG_MISSING", worldA(true).recommendation().status());
        props = new RivalryProperties(3, 1.5, null, 16, 2);
        assertEquals("CONFIG_MISSING", worldA(true).recommendation().status(), "one null key is not loaded (F17)");
        assertNull(worldA(true).recommendation().minMeetings());
    }

    @Test
    void tooManyAndTooFewManagersAreRefusedWithAReason() {
        props = new RivalryProperties(3, 1.5, 2.5, 4, 2);
        assertEquals("TOO_MANY_MANAGERS", worldA(true).recommendation().status());
        props = new RivalryProperties(3, 1.5, 2.5, 16, 2);
        LeagueRow l = row(1, 2026, "s1", "s0");
        when(leagues.chainBySleeperId("s1")).thenReturn(List.of(l, row(0, 2025, "s0", null)));
        stubLeague(l, List.of(standing(1, 1, 11L)), List.of(), List.of(), null, OptionalInt.empty());
        assertEquals("TOO_FEW_MANAGERS", service().forLeague("s1", null).orElseThrow().recommendation().status());
    }

    @Test
    void anOddLeagueSitsOneOutWithAnHonestReasonAndEveryoneElseIsPairedOnce() {
        var rec = worldA(true).recommendation();
        assertEquals("OK", rec.status());
        assertEquals(3, rec.minMeetings());
        assertEquals("hand-set", rec.minMeetingsLabel());
        assertEquals(2, rec.pairings().size());
        assertNotNull(rec.unpaired());
        List<Long> seen = new ArrayList<>();
        rec.pairings().forEach(p -> { seen.add(p.aManagerId()); seen.add(p.bManagerId()); });
        seen.add(rec.unpaired().managerId());
        assertEquals(5, seen.stream().distinct().count());
        assertTrue(rec.unpaired().why().startsWith("Leaving M"));
        assertTrue(rec.unpaired().why().contains("gives the highest total"));
        assertFalse(rec.unpaired().why().contains("fewest"), "it does not claim a rule the DP doesn't apply (F12)");
    }

    @Test
    void thinMeansFewerThanTheMinimumAndReasonNumbersEqualTheMatrix() {
        Response r = worldA(true);
        var rec = r.recommendation();
        assertEquals(rec.pairings().stream().filter(RivalryService.Pairing::thin).count(), rec.thinCount());
        for (var p : rec.pairings()) {
            PairRecord pr = pair(r, p.aManagerId(), p.bManagerId());
            assertEquals(pr.meetings() < 3, p.thin(), "thin iff meetings < N (hand-set 3)");
            assertEquals(pr.meetings(), p.reason().numbers().get("meetings"), "SC-004: the text prints matrix numbers");
            assertEquals(pr.aWins(), p.reason().numbers().get("aWins"));
            assertEquals(pr.bWins(), p.reason().numbers().get("bWins"));
            assertEquals(pr.ties(), p.reason().numbers().get("ties"));
            assertEquals(pr.meetings(), p.meetings());
        }
        // 11 v 12 is the only 3-meeting pair (2-1): an even series by the |w-l| <= 1 rule.
        var top = rec.pairings().stream().filter(p -> p.aManagerId() == 11 && p.bManagerId() == 12).findFirst().orElseThrow();
        assertEquals("EVEN", top.reason().code());
        assertFalse(top.thin());
        assertTrue(top.reason().text().startsWith("Separated by one game after 3 meetings: M11 2, M12 1"),
                top.reason().text());
    }

    @Test
    void theReasonFollowsThePriorityOrderForANeverMetPair() {
        // A league where the only history is one season and two managers never met.
        LeagueRow l25 = row(1, 2025, "s25", null);
        LeagueRow l26 = row(2, 2026, "s26", "s25");
        when(leagues.chainBySleeperId("s26")).thenReturn(List.of(l26, l25));
        stubLeague(l25, List.of(standing(1, 1, 11L), standing(1, 2, 12L), standing(1, 3, 13L), standing(1, 4, 14L)),
                List.of(game(2025, 1, 1, 11L, 100, 2, 12L, 90)),
                List.of(), null, OptionalInt.empty());
        stubLeague(l26, List.of(standing(2, 1, 11L), standing(2, 2, 12L), standing(2, 3, 13L), standing(2, 4, 14L)),
                List.of(), List.of(), null, OptionalInt.empty());
        var rec = service().forLeague("s26", null).orElseThrow().recommendation();
        assertEquals("OK", rec.status());
        assertEquals(2, rec.pairings().size());
        var met = rec.pairings().stream().filter(p -> p.meetings() == 1).findFirst().orElseThrow();
        assertEquals("THIN", met.reason().code());
        assertEquals("Met once: M11 won 100.00 to 90.00.", met.reason().text());
        var never = rec.pairings().stream().filter(p -> p.meetings() == 0).findFirst().orElseThrow();
        assertEquals("NEVER_MET", never.reason().code());
        assertTrue(never.thin(), "no meetings is thin too (FR-011)");
    }

    /**
     * Measured on NBA 2024 and 2025 (live data, 2026-10-14): the bracket's winner can have FEWER stored points
     * in the final week. The reason must say both rather than print a false "beat".
     */
    @Test
    void aFinalWhoseBracketWinnerHasFewerStoredPointsIsWordedHonestly() {
        LeagueRow l25 = row(1, 2025, "s25", null);
        LeagueRow l26 = row(2, 2026, "s26", "s25");
        when(leagues.chainBySleeperId("s26")).thenReturn(List.of(l26, l25));
        when(brackets.forLeagues(anyCollection())).thenReturn(List.of(new Match(1, 1, 1, 1, 2, 1, 2, 1)));
        stubLeague(l25, List.of(standing(1, 1, 11L), standing(1, 2, 12L)),
                List.of(game(2025, 15, 1, 11L, 199, 2, 12L, 234)), List.of(),
                new PlayoffFormat(2, 15, 0, false, false, 0), OptionalInt.empty());
        stubLeague(l26, List.of(standing(2, 1, 11L), standing(2, 2, 12L)), List.of(), List.of(), null, OptionalInt.empty());

        var p = service().forLeague("s26", null).orElseThrow().recommendation().pairings().get(0);

        assertEquals("CHAMPIONSHIP_REMATCH", p.reason().code());
        assertEquals(false, p.reason().numbers().get("scoresAgreeWithWinner"));
        assertFalse(p.reason().text().contains(" beat "), p.reason().text());
        assertTrue(p.reason().text().contains("gave to M11"), p.reason().text());
        assertTrue(p.reason().text().contains("M11 199.00, M12 234.00"), p.reason().text());
    }

    /** B3: sentences use the same team-name-first lookup the rows carry. */
    @Test
    void reasonAndUnpairedTextUseTeamNames() {
        when(members.teamNamesByManager(2L)).thenReturn(Map.of(11L, "Gridiron Gang", 12L, "Bye Week Heroes"));
        Response r = worldA(true);
        var top = r.recommendation().pairings().stream()
                .filter(p -> p.aManagerId() == 11 && p.bManagerId() == 12).findFirst().orElseThrow();
        assertTrue(top.reason().text().contains("Gridiron Gang 2"), top.reason().text());
        assertFalse(top.reason().text().contains("M11"), top.reason().text());
        var row11 = r.matrix().rows().stream().filter(x -> x.managerId() == 11).findFirst().orElseThrow();
        assertEquals("Gridiron Gang", row11.teamName());
        assertEquals("M11", row11.managerName());
    }

    /** Level wins (2-2 over four meetings) are the only case worded "even". */
    @Test
    void evenIsOnlySaidWhenTheWinsAreLevel() {
        LeagueRow l25 = row(1, 2025, "s25", null);
        LeagueRow l26 = row(2, 2026, "s26", "s25");
        when(leagues.chainBySleeperId("s26")).thenReturn(List.of(l26, l25));
        stubLeague(l25, List.of(standing(1, 1, 11L), standing(1, 2, 12L)),
                List.of(game(2025, 1, 1, 11L, 100, 2, 12L, 90), game(2025, 2, 1, 11L, 90, 2, 12L, 100),
                        game(2025, 3, 1, 11L, 100, 2, 12L, 90), game(2025, 4, 1, 11L, 90, 2, 12L, 100)),
                List.of(), null, OptionalInt.empty());
        stubLeague(l26, List.of(standing(2, 1, 11L), standing(2, 2, 12L)), List.of(), List.of(), null, OptionalInt.empty());
        var p = service().forLeague("s26", null).orElseThrow().recommendation().pairings().get(0);
        assertEquals("EVEN", p.reason().code());
        assertEquals("An even series after 4 meetings: M11 2, M12 2.", p.reason().text());
    }

    /** B4: the sentence says current managers, because former managers are not in the comparison. */
    @Test
    void mostMeetingsIsWordedForCurrentManagers() {
        LeagueRow l25 = row(1, 2025, "s25", null);
        LeagueRow l26 = row(2, 2026, "s26", "s25");
        when(leagues.chainBySleeperId("s26")).thenReturn(List.of(l26, l25));
        stubLeague(l25, List.of(standing(1, 1, 11L), standing(1, 2, 12L), standing(1, 3, 13L), standing(1, 4, 14L)),
                List.of(game(2025, 1, 1, 11L, 100, 2, 12L, 90), game(2025, 2, 1, 11L, 100, 2, 12L, 95)),
                List.of(), null, OptionalInt.empty());
        stubLeague(l26, List.of(standing(2, 1, 11L), standing(2, 2, 12L), standing(2, 3, 13L), standing(2, 4, 14L)),
                List.of(), List.of(), null, OptionalInt.empty());
        var p = service().forLeague("s26", null).orElseThrow().recommendation().pairings().stream()
                .filter(x -> x.meetings() == 2).findFirst().orElseThrow();
        assertEquals("MOST_MEETINGS", p.reason().code());
        assertTrue(p.reason().text().startsWith("The most meetings of any two current managers"), p.reason().text());
    }

    /** B8: a manager on two rosters is named in a note, not silently shown once. */
    @Test
    void aManagerWithTwoRostersGetsAGridNote() {
        LeagueRow l = row(1, 2026, "s1", null);
        when(leagues.chainBySleeperId("s1")).thenReturn(List.of(l));
        stubLeague(l, List.of(standing(1, 1, 11L), standing(1, 2, 12L), standing(1, 3, 11L)), List.of(),
                List.of(sched(2026, 1, 1, 11L, 2, 12L)), null, OptionalInt.empty());
        var g = service().forLeague("s1", null).orElseThrow().seasonGrid();
        assertEquals(RivalryService.ROSTER_NOTE, g.rosterNote());
        assertNull(worldA(true).seasonGrid().rosterNote());
    }

    /** B9: undecided bracket matches are named so the counts add up. */
    @Test
    void playoffHistoryNamesUndecidedMatches() {
        LeagueRow l25 = row(1, 2025, "s25", null);
        LeagueRow l26 = row(2, 2026, "s26", "s25");
        when(leagues.chainBySleeperId("s26")).thenReturn(List.of(l26, l25));
        when(brackets.forLeagues(anyCollection())).thenReturn(List.of(new Match(1, 1, 1, 1, 2, null, null, null)));
        stubLeague(l25, List.of(standing(1, 1, 11L), standing(1, 2, 12L)),
                List.of(game(2025, 15, 1, 11L, 100, 2, 12L, 90)), List.of(),
                new PlayoffFormat(2, 15, 0, false, false, 0), OptionalInt.empty());
        stubLeague(l26, List.of(standing(2, 1, 11L), standing(2, 2, 12L)), List.of(), List.of(), null, OptionalInt.empty());
        var p = service().forLeague("s26", null).orElseThrow().recommendation().pairings().get(0);
        assertEquals("PLAYOFF_HISTORY", p.reason().code());
        assertTrue(p.reason().text().contains("1 undecided"), p.reason().text());
    }

    /** B10: a pair scheduled in a week already past (never scored) is NEVER_MET, not NOT_YET_MET. */
    @Test
    void notYetMetNeedsAScheduledWeekAfterTheLastScoredWeek() {
        LeagueRow l25 = row(1, 2025, "s25", null);
        LeagueRow l26 = row(2, 2026, "s26", "s25");
        when(leagues.chainBySleeperId("s26")).thenReturn(List.of(l26, l25));
        stubLeague(l25, List.of(standing(1, 1, 11L), standing(1, 2, 12L), standing(1, 3, 13L), standing(1, 4, 14L)),
                List.of(game(2025, 1, 1, 11L, 100, 2, 12L, 90)), List.of(), null, OptionalInt.empty());
        stubLeague(l26, List.of(standing(2, 1, 11L), standing(2, 2, 12L), standing(2, 3, 13L), standing(2, 4, 14L)),
                List.of(game(2026, 1, 1, 11L, 100, 2, 12L, 90), game(2026, 3, 1, 11L, 100, 2, 12L, 90)),
                List.of(sched(2026, 2, 3, 13L, 4, 14L), sched(2026, 4, 1, 11L, 3, 13L)),
                null, OptionalInt.of(3));
        Response r = service().forLeague("s26", null).orElseThrow();
        assertEquals("NEVER_MET", pair(r, 13, 14).status(), "week 2 is behind the last scored week 3");
        assertEquals("NOT_YET_MET", pair(r, 11, 13).status(), "week 4 is still ahead");
    }

    /** B2: a final whose stored scores contradict the bracket winner is flagged on the wire. */
    @Test
    void aDisagreeingFinalIsFlaggedOnTheFinalAndOnTheGridCell() {
        LeagueRow l = row(1, 2025, "s1", null);
        when(leagues.chainBySleeperId("s1")).thenReturn(List.of(l));
        when(brackets.forLeagues(anyCollection())).thenReturn(List.of(new Match(1, 1, 1, 1, 2, 1, 2, 1)));
        stubLeague(l, List.of(standing(1, 1, 11L), standing(1, 2, 12L)),
                List.of(game(2025, 15, 1, 11L, 199, 2, 12L, 234)),
                List.of(sched(2025, 15, 1, 11L, 2, 12L)),
                new PlayoffFormat(2, 15, 0, false, false, 0), OptionalInt.empty());
        Response r = service().forLeague("s1", null).orElseThrow();
        assertEquals(Boolean.FALSE, pair(r, 11, 12).finals().get(0).scoresAgreeWithWinner());
        assertTrue(cell(r, 11, 15).result().bracketDisagrees());
        assertTrue(cell(r, 12, 15).result().bracketDisagrees());
        stubLeague(l, List.of(standing(1, 1, 11L), standing(1, 2, 12L)),
                List.of(game(2025, 15, 1, 11L, 250, 2, 12L, 234)),
                List.of(sched(2025, 15, 1, 11L, 2, 12L)),
                new PlayoffFormat(2, 15, 0, false, false, 0), OptionalInt.empty());
        Response ok = service().forLeague("s1", null).orElseThrow();
        assertEquals(Boolean.TRUE, pair(ok, 11, 12).finals().get(0).scoresAgreeWithWinner());
        assertFalse(cell(ok, 11, 15).result().bracketDisagrees());
    }

    // ---------------------------------------------------------------- split notes + wire shape

    @Test
    void anUnsplitPostseasonIsNamedAndNeverHintsAtRunningAnything() {
        LeagueRow l = row(1, 2025, "s1", null);
        when(leagues.chainBySleeperId("s1")).thenReturn(List.of(l));
        stubLeague(l, List.of(standing(1, 1, 11L), standing(1, 2, 12L)),
                List.of(game(2025, 15, 1, 11L, 100, 2, 12L, 90)), List.of(), new PlayoffFormat(6, 15, 0, false, false, 0),
                OptionalInt.empty());
        Response r = service().forLeague("s1", null).orElseThrow();
        assertEquals(1, r.matrix().splitNotes().size());
        assertEquals("NO_BRACKET", r.matrix().splitNotes().get(0).reason());
        assertEquals(1, pair(r, 11, 12).byKind().get("POSTSEASON_UNSPLIT").aWins());
        String text = r.matrix().splitNotes().get(0).text().toLowerCase();
        assertFalse(text.contains("ingest") || text.contains("/api"), text);
    }

    @Test
    void theJsonUsesTheContractFieldNamesAndNeverFailsOnNulls() throws Exception {
        JsonNode j = JSON.readTree(JSON.writeValueAsString(worldA(true)));
        assertEquals("nfl", j.get("sport").asText());
        assertTrue(j.get("matrix").get("rows").get(0).has("isMe"));
        assertTrue(j.get("seasonGrid").get("weeks").get(0).has("isPlayoffWeek"));
        JsonNode oppCell = j.get("seasonGrid").get("cells").get(0);
        assertTrue(oppCell.has("opponentManagerId"));
        assertTrue(j.get("recommendation").get("unpaired").has("why"));
        assertTrue(j.get("matrix").get("pairs").get(0).get("byKind").has("POSTSEASON_UNSPLIT"));
        assertTrue(j.get("seasonGrid").has("currentWeek"));
    }

    @Test
    void anUnknownLeagueIsEmpty() {
        when(leagues.bySleeperId("nope")).thenReturn(Optional.empty());
        assertTrue(service().forLeague("nope", null).isEmpty());
    }

    @Test
    void outcomeIsTheSharedRule() {
        assertEquals("A", HeadToHeadService.outcome(2, 1));
        assertEquals("B", HeadToHeadService.outcome(1, 2));
        assertEquals("TIE", HeadToHeadService.outcome(1.5, 1.5));
    }
}
