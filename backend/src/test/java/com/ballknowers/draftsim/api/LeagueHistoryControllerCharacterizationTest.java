package com.ballknowers.draftsim.api;

import com.ballknowers.draftsim.config.OwnerProperties;
import com.ballknowers.draftsim.domain.Position;
import com.ballknowers.draftsim.domain.Sport;
import com.ballknowers.draftsim.engine.LeagueRecordService;
import com.ballknowers.draftsim.engine.ManagerCareerService;
import com.ballknowers.draftsim.engine.MemberRankingService;
import com.ballknowers.draftsim.engine.PlayoffOddsService;
import com.ballknowers.draftsim.engine.PowerRankingService;
import com.ballknowers.draftsim.engine.ScoredWeeks;
import com.ballknowers.draftsim.engine.TransactionAnalysisService;
import com.ballknowers.draftsim.ingest.SleeperClient;
import com.ballknowers.draftsim.profile.ManagerProfile;
import com.ballknowers.draftsim.profile.ProfileService;
import com.ballknowers.draftsim.profile.Provenance;
import com.ballknowers.draftsim.store.LeagueMemberRepository;
import com.ballknowers.draftsim.store.LeagueMembership;
import com.ballknowers.draftsim.store.LeagueRepository;
import com.ballknowers.draftsim.store.ManagerRepository;
import com.ballknowers.draftsim.store.PlayoffOddsRepository;
import com.ballknowers.draftsim.store.PowerRankingRepository;
import com.ballknowers.draftsim.store.RankingBallotRepository;
import com.ballknowers.draftsim.store.RosterSeasonRepository;
import com.ballknowers.draftsim.store.RosterSeasonRepository.StandingRow;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

/**
 * specs/021-codebase-cleanup T027, the oracle for converting LeagueHistoryController's
 * hand-built maps into response records (contracts/C2, as amended after review).
 *
 * <p>It goes through the real HTTP layer (standalone MockMvc: Spring's message
 * converters and Jackson) with every collaborator mocked. That means no Spring context
 * and no Postgres, so it can never skip, and no database ids, which change from run to
 * run and would make a golden file meaningless. It was written and made green against
 * the map-building code before any of that code changed. The conversion commit must
 * leave this file and every golden under {@code golden/league-history/} untouched.
 *
 * <p>Cases are chosen for the conversion's known failure modes (plan-review findings 1,
 * 2, 7):
 * <ul>
 *   <li>{@code makesPlayoffsPct} is always present: null on an entry with no odds, a
 *       number on one with odds.</li>
 *   <li>{@code finalRank} is present-null on a history row. Career rows share the base
 *       builder but carry {@code counted} instead of {@code teamName}/{@code isMe}.</li>
 *   <li>{@code positionalTilt} is a dynamic-key map, asserted order-sensitively.</li>
 *   <li>Every list appears both populated and empty, and every nullable appears both
 *       null and set.</li>
 * </ul>
 */
class LeagueHistoryControllerCharacterizationTest {

    private static final String LEAGUE = "L-2026";
    private static final String PREV = "L-2025";
    private static final String ME = "u-me";
    private static final long ME_ID = 11L;

    private LeagueRepository leagues;
    private RosterSeasonRepository rosterSeasons;
    private PowerRankingService power;
    private ProfileService profiles;
    private LeagueMembership membership;
    private SleeperClient sleeper;
    private ManagerRepository managers;
    private LeagueMemberRepository leagueMembers;
    private RankingBallotRepository ballots;
    private MemberRankingService memberRankings;
    private PlayoffOddsService playoffOdds;
    private LeagueRecordService records;
    private ManagerCareerService careers;
    private ScoredWeeks scoredWeeks;
    private MockMvc mvc;

    private static final LeagueRepository.LeagueRow CURRENT = new LeagueRepository.LeagueRow(
            100L, Sport.NFL, LEAGUE, "Ball Knowers", 2026, 4, List.of("QB"), 1.0, PREV, "in_season");
    private static final LeagueRepository.LeagueRow PREVIOUS = new LeagueRepository.LeagueRow(
            99L, Sport.NFL, PREV, "Ball Knowers", 2025, 4, List.of("QB"), 1.0, null, "complete");

    @BeforeEach
    void setUp() {
        leagues = mock(LeagueRepository.class);
        rosterSeasons = mock(RosterSeasonRepository.class);
        power = mock(PowerRankingService.class);
        profiles = mock(ProfileService.class);
        membership = mock(LeagueMembership.class);
        sleeper = mock(SleeperClient.class);
        managers = mock(ManagerRepository.class);
        leagueMembers = mock(LeagueMemberRepository.class);
        ballots = mock(RankingBallotRepository.class);
        memberRankings = mock(MemberRankingService.class);
        playoffOdds = mock(PlayoffOddsService.class);
        records = mock(LeagueRecordService.class);
        careers = mock(ManagerCareerService.class);
        scoredWeeks = mock(ScoredWeeks.class);
        mvc = MockMvcBuilders.standaloneSetup(new LeagueHistoryController(
                leagues, rosterSeasons, power, profiles, membership, sleeper, managers, leagueMembers,
                ballots, memberRankings, new OwnerProperties(null), playoffOdds, records, careers, scoredWeeks))
                .build();

        when(managers.idsBySleeperUserId()).thenReturn(Map.of(ME, ME_ID));
        when(managers.names()).thenReturn(Map.of(ME_ID, "popsharky", 12L, "kieriskash"));
        when(managers.avatarIds()).thenReturn(Map.of(ME_ID, "av-me"));
        when(leagueMembers.forLeague(anyLong())).thenReturn(List.of(
                new LeagueMemberRepository.MemberRow(ME_ID, "popsharky", "av-me", true, "Sharks"),
                new LeagueMemberRepository.MemberRow(12L, "kieriskash", null, false, "   "),
                new LeagueMemberRepository.MemberRow(13L, "jstrobe", null, false, null)));
    }

    private static MockHttpServletRequestBuilder as(MockHttpServletRequestBuilder r, String who) {
        return who == null ? r : r.header("X-Sleeper-User", who);
    }

    /** Performs the request and asserts status and body against the golden file. */
    private void check(MockHttpServletRequestBuilder req, int status, String golden, String... orderSensitive) throws Exception {
        MvcResult res = mvc.perform(req).andReturn();
        assertEquals(status, res.getResponse().getStatus(), golden + " status");
        String body = res.getResponse().getContentAsString();
        if (body.isEmpty()) {
            assertEquals("", body, golden + " expected an empty body");
            return;
        }
        GoldenJson.assertJsonMatchesGolden(body, "league-history/" + golden, orderSensitive);
    }

    private static StandingRow standing(long leagueId, int roster, Long managerId, String name, Integer placement,
                                        Integer season, String sleeperId, Sport sport, String leagueName, Boolean complete) {
        return new StandingRow(leagueId, roster, managerId, name, managerId == null ? null : "av" + managerId,
                7, 6, roster == 2 ? 1 : 0, 1450.25 + roster, 1399.5, placement, season, sleeperId, sport, leagueName, complete);
    }

    // ------------------------------------------------------------------ history

    private void stubHistory(boolean emptyRecords) {
        when(membership.visibleLeague(eq(LEAGUE), any())).thenReturn(Optional.of(CURRENT));
        when(leagues.chainBySleeperId(LEAGUE)).thenReturn(List.of(CURRENT, PREVIOUS));
        when(membership.canCommission(eq(100L), any())).thenReturn(true);
        // Current season in progress: no ranks yet.
        when(power.finalRankForSeason(100L, true)).thenReturn(
                new PowerRankingService.SeasonRanks(PowerRankingService.RankStatus.IN_PROGRESS, null, Map.of()));
        // Previous season ranked, but roster 4 missing from the snapshot, so UNAVAILABLE.
        when(power.finalRankForSeason(99L, false)).thenReturn(
                new PowerRankingService.SeasonRanks(PowerRankingService.RankStatus.RANKED, 17, Map.of(1, 2, 2, 1, 3, 3)));
        when(rosterSeasons.forLeague(100L)).thenReturn(List.of(
                standing(100L, 1, ME_ID, "popsharky", null, null, null, null, null, null),
                standing(100L, 2, 12L, "kieriskash", 1, null, null, null, null, null)));
        when(rosterSeasons.forLeague(99L)).thenReturn(List.of(
                standing(99L, 1, ME_ID, "popsharky", 2, null, null, null, null, null),
                standing(99L, 2, 12L, "kieriskash", 1, null, null, null, null, null),
                standing(99L, 3, 13L, "jstrobe", 3, null, null, null, null, null),
                standing(99L, 4, null, null, null, null, null, null, null, null)));
        LeagueRecordService.Side w = new LeagueRecordService.Side(1, ME_ID, "popsharky", "av-me", new BigDecimal("150.10"));
        LeagueRecordService.Side l = new LeagueRecordService.Side(2, 12L, "kieriskash", null, new BigDecimal("150.00"));
        LeagueRecordService.RecordBook book = emptyRecords
                ? new LeagueRecordService.RecordBook(5, List.of(), List.of(), List.of(), List.of(),
                        "no week has pairings yet", List.of(), List.of(), List.of())
                : new LeagueRecordService.RecordBook(5,
                        List.of(new LeagueRecordService.WeeklyScoreRecord(2025, 3, 1, ME_ID, "popsharky", "av-me", new BigDecimal("205.04"))),
                        List.of(new LeagueRecordService.WeeklyScoreRecord(2025, 9, 4, null, null, null, new BigDecimal("41.20"))),
                        List.of(new LeagueRecordService.MarginRecord(2025, 5, new BigDecimal("0.10"), w, l)),
                        List.of(new LeagueRecordService.MarginRecord(2025, 6, new BigDecimal("88.00"), l, w)),
                        null,
                        List.of(new LeagueRecordService.PointsLeaderRecord(1, ME_ID, "popsharky", "av-me", new BigDecimal("2901.55"), List.of(2025, 2026))),
                        List.of(new LeagueRecordService.StreakRecord(1, ME_ID, "popsharky", "av-me", 6, List.of(2025), 2, 7, true)),
                        List.of(new LeagueRecordService.StreakRecord(4, null, null, null, 3, List.of(2025, 2026), 13, 2, false)));
        when(records.forChain(any(), any())).thenReturn(book);
    }

    @Test
    void historyForAMemberWithRecords() throws Exception {
        stubHistory(false);
        check(as(get("/api/leagues/" + LEAGUE + "/history"), ME), 200, "history-member");
    }

    @Test
    void historyAnonymousWithAnEmptyRecordBook() throws Exception {
        stubHistory(true);
        when(membership.canCommission(eq(100L), any())).thenReturn(false);
        check(get("/api/leagues/" + LEAGUE + "/history"), 200, "history-anonymous-empty-records");
    }

    @Test
    void historyNotVisibleIsAnEmpty404() throws Exception {
        check(as(get("/api/leagues/nope/history"), ME), 404, "history-404");
    }

    // ------------------------------------------------------------------ manager history

    private void stubManagerHistory() {
        when(membership.canSeeManager(any(), eq(ME_ID))).thenReturn(true);
        when(rosterSeasons.forManager(ME_ID)).thenReturn(List.of(
                standing(100L, 1, ME_ID, "popsharky", null, 2026, LEAGUE, Sport.NFL, "Ball Knowers", null)));
        // Insertion order RB, QB, WR (not alphabetical, not enum order), so a reorder shows.
        Map<Position, Double> tilt = new LinkedHashMap<>();
        tilt.put(Position.RB, 1.31);
        tilt.put(Position.QB, 0.62);
        tilt.put(Position.WR, 1.0);
        ManagerProfile nfl = new ManagerProfile(ME_ID, "popsharky", -3.456, tilt, 1.0, null, 2, 30, Provenance.FITTED, "av-me");
        when(profiles.fit(Sport.NFL)).thenReturn(new ProfileService.Fit(Map.of(ME_ID, nfl), null, 300,
                Map.of(), Map.of(ME_ID, -1.234), Map.of(ME_ID, 0.456)));
        // Basketball: fitted but with no relative reach (null), Provenance NEUTRAL-ish counts.
        ManagerProfile nba = new ManagerProfile(ME_ID, "popsharky", 0.0, Map.of(), 1.0, null, 1, 0, Provenance.STATED, "av-me");
        when(profiles.fit(Sport.NBA)).thenReturn(new ProfileService.Fit(Map.of(ME_ID, nba), null, 0,
                Map.of(), Map.of(), Map.of()));
        StandingRow done = standing(99L, 1, ME_ID, "popsharky", 1, 2025, PREV, Sport.NFL, "Ball Knowers", true);
        StandingRow live = standing(100L, 1, ME_ID, "popsharky", 1, 2026, LEAGUE, Sport.NFL, "Ball Knowers", null);
        TransactionAnalysisService.WaiverTendency withFaab = new TransactionAnalysisService.WaiverTendency(12.5, 2,
                new TransactionAnalysisService.FaabTendency(0.0412, 0.31, 0.88, 7.5, 0.6667),
                List.of(new TransactionAnalysisService.ExcludedSeason(2024, "Old League", "no FAAB that season")));
        TransactionAnalysisService.WaiverTendency noFaab = new TransactionAnalysisService.WaiverTendency(0.0, 0, null, List.of());
        when(careers.forManager(ME_ID)).thenReturn(List.of(
                new ManagerCareerService.CareerProfile(Sport.NFL, 2,
                        List.of(new ManagerCareerService.SeasonEntry(done, true), new ManagerCareerService.SeasonEntry(live, false)),
                        14, 12, 1, 0.5370, 2900.5, 2799.25, 1450.25, 0.9123, 26, 1, 1.5, 1,
                        List.of(new ManagerCareerService.Unavailable("winsAboveExpected", "one season unscored")),
                        List.of(new ManagerCareerService.Rank("pointsFor", 1, 12, "Ball Knowers", PREV)),
                        withFaab),
                new ManagerCareerService.CareerProfile(Sport.NBA, 0, List.of(), 0, 0, 0, null, 0.0, 0.0, null,
                        null, 0, 0, null, 0, List.of(), List.of(), noFaab)));
    }

    @Test
    void managerHistoryAcrossBothSports() throws Exception {
        stubManagerHistory();
        check(as(get("/api/managers/" + ME_ID + "/history"), ME), 200, "manager-history",
                "/draftHistory/*/positionalTilt");
    }

    @Test
    void managerHistoryOmitsASportWithNoDraftsObserved() throws Exception {
        stubManagerHistory();
        ManagerProfile none = new ManagerProfile(ME_ID, "popsharky", 0.0, Map.of(), 1.0, null, 0, 0, Provenance.NEUTRAL, null);
        when(profiles.fit(Sport.NBA)).thenReturn(new ProfileService.Fit(Map.of(ME_ID, none), null, 0, Map.of(), Map.of(), Map.of()));
        check(as(get("/api/managers/" + ME_ID + "/history"), ME), 200, "manager-history-nfl-only",
                "/draftHistory/*/positionalTilt");
    }

    @Test
    void managerHistoryHiddenAndEmptyAre404s() throws Exception {
        check(as(get("/api/managers/999/history"), ME), 404, "manager-history-404-hidden");
        when(membership.canSeeManager(any(), eq(998L))).thenReturn(true);
        check(as(get("/api/managers/998/history"), ME), 404, "manager-history-404-empty");
    }

    // ------------------------------------------------------------------ power

    private void stubPower(boolean withOdds) {
        when(membership.visibleLeague(eq(LEAGUE), any())).thenReturn(Optional.of(CURRENT));
        when(power.sportState(Sport.NFL)).thenReturn(new PowerRankingService.SportState(4, "2026", "2026-09-04", true));
        when(scoredWeeks.of(100L)).thenReturn(new ScoredWeeks.Snapshot(3, 2, Set.of(1, 2)));
        when(power.snapshots(100L)).thenReturn(List.of(
                new PowerRankingRepository.SnapshotRow(2026, 0, "COMPUTED_PRESEASON", 1, ME_ID, "popsharky", "av-me", 1, 98.5, "baseline"),
                new PowerRankingRepository.SnapshotRow(2026, 2, "COMPUTED_REALIZED", 2, 12L, "kieriskash", null, 1, 3.25, null),
                // Week 3 is past latestFinal (2): this one must be filtered out.
                new PowerRankingRepository.SnapshotRow(2026, 3, "COMPUTED_REALIZED", 2, 12L, "kieriskash", null, 1, 9.0, "partial"),
                new PowerRankingRepository.SnapshotRow(2026, 2, "COMMISSIONER", 4, null, null, null, 4, null, null)));
        when(memberRankings.weeksWithBallots(100L)).thenReturn(List.of(2));
        when(memberRankings.forWeek(100L, LEAGUE, 2)).thenReturn(Optional.of(new MemberRankingService.WeekRankings(2, 3, List.of(
                new MemberRankingService.Entry(1, ME_ID, 1, 1.33, 1, 2, 0.47, 3, false, -1, null),
                new MemberRankingService.Entry(3, 13L, 2, 2.0, 2, 2, null, 3, true, null, "thin")))));
        if (withOdds) {
            when(playoffOdds.madePctByWeek(100L, 2026)).thenReturn(Map.of(2, Map.of(1, 71.5, 2, 12.25)));
            when(playoffOdds.summary(100L, 2026)).thenReturn(Optional.of(new PlayoffOddsService.Summary(2, 10000, "elo-lite", 2)));
        }
    }

    @Test
    void powerRankingsWithOdds() throws Exception {
        stubPower(true);
        check(as(get("/api/leagues/" + LEAGUE + "/power"), ME), 200, "power-with-odds");
    }

    @Test
    void powerRankingsWithoutOdds() throws Exception {
        stubPower(false);
        check(as(get("/api/leagues/" + LEAGUE + "/power"), ME), 200, "power-no-odds");
    }

    // ------------------------------------------------------------------ ballot (GET)

    private void stubBallot() {
        when(membership.visibleLeague(eq(LEAGUE), any())).thenReturn(Optional.of(CURRENT));
        when(power.sportState(Sport.NFL)).thenReturn(new PowerRankingService.SportState(4, "2026", "2026-09-04", true));
        when(leagueMembers.isMember(100L, ME_ID)).thenReturn(true);
        when(leagueMembers.anyCommissioner(100L)).thenReturn(true);
        when(memberRankings.members(eq(100L), eq(LEAGUE), any())).thenReturn(List.of(
                new MemberRankingService.BallotMember(1, ME_ID, "popsharky", "av-me", "Sharks", true),
                new MemberRankingService.BallotMember(4, null, null, null, null, false)));
        when(ballots.forWeek(100L, 4)).thenReturn(List.of(
                new RankingBallotRepository.Ballot(1, ME_ID, Instant.parse("2026-10-01T12:00:00Z"), List.of())));
        when(ballots.find(100L, 4, ME_ID)).thenReturn(Optional.of(new RankingBallotRepository.Ballot(1, ME_ID,
                Instant.parse("2026-10-01T12:00:00Z"), List.of(
                        new RankingBallotRepository.BallotEntry(4, 2),
                        new RankingBallotRepository.BallotEntry(1, 1)))));
    }

    @Test
    void ballotForAMemberWithASubmittedBallot() throws Exception {
        stubBallot();
        check(as(get("/api/leagues/" + LEAGUE + "/ballot"), ME), 200, "ballot-member");
    }

    @Test
    void ballotForAPastWeekAnonymously() throws Exception {
        stubBallot();
        check(get("/api/leagues/" + LEAGUE + "/ballot").param("week", "0"), 200, "ballot-anonymous-week0");
    }

    // ------------------------------------------------------------------ ballot (POST): errors and success

    @Test
    void submitBallotEveryOutcome() throws Exception {
        when(leagues.bySleeperId(LEAGUE)).thenReturn(Optional.of(CURRENT));
        when(leagueMembers.isMember(100L, ME_ID)).thenReturn(true);
        when(power.sportState(Sport.NFL)).thenReturn(new PowerRankingService.SportState(4, "2026", "2026-09-04", true));
        when(sleeper.rosters(LEAGUE)).thenReturn(List.of(Map.of("roster_id", 1), Map.of("roster_id", 2)));
        String url = "/api/leagues/" + LEAGUE + "/ballot";
        check(post(url).contentType("application/json").content("{\"week\":4,\"rosterIds\":[1,2]}"), 401, "submit-401");
        check(as(post(url), "u-stranger").contentType("application/json").content("{\"week\":4,\"rosterIds\":[1,2]}"), 403, "submit-403");
        check(as(post(url), ME).contentType("application/json").content("{\"week\":4,\"rosterIds\":[]}"), 400, "submit-400-ids");
        check(as(post(url), ME).contentType("application/json").content("{\"rosterIds\":[1,2]}"), 400, "submit-400-week");
        check(as(post(url), ME).contentType("application/json").content("{\"week\":3,\"rosterIds\":[1,2]}"), 400, "submit-400-backdate");
        check(as(post(url), ME).contentType("application/json").content("{\"week\":4,\"rosterIds\":[1,1]}"), 400, "submit-400-coverage");
        check(as(post(url), ME).contentType("application/json").content("{\"week\":4,\"rosterIds\":[2,1]}"), 200, "submit-ok");
    }

    // ------------------------------------------------------------------ backfill

    @Test
    void backfillEveryOutcome() throws Exception {
        String url = "/api/leagues/" + LEAGUE + "/power/backfill";
        when(membership.visibleLeague(eq(LEAGUE), any())).thenReturn(Optional.of(CURRENT));
        check(as(post(url), ME), 403, "backfill-403");
        when(membership.canCommission(eq(100L), any())).thenReturn(true);
        when(leagues.chainBySleeperId(LEAGUE)).thenReturn(List.of(CURRENT, PREVIOUS));
        check(as(post(url).param("season", "2019"), ME), 400, "backfill-400-season");
        when(power.backfillFinalRanks(any(), isNull())).thenReturn(List.of(
                new PowerRankingService.BackfilledSeason(2025, 17, 12, null),
                new PowerRankingService.BackfilledSeason(2026, null, 0, "season still in progress")));
        check(as(post(url), ME), 200, "backfill-ok");
        when(power.backfillFinalRanks(any(), eq(2026))).thenReturn(List.of());
        check(as(post(url).param("season", "2026"), ME), 200, "backfill-empty");
    }

    // ------------------------------------------------------------------ compute

    private static PowerRankingRepository.Entry[] entries(int n) {
        PowerRankingRepository.Entry[] out = new PowerRankingRepository.Entry[n];
        for (int i = 0; i < n; i++) out[i] = new PowerRankingRepository.Entry(i + 1, null, i + 1, 1.0, null);
        return out;
    }

    @Test
    void computeEveryOutcome() throws Exception {
        String url = "/api/leagues/" + LEAGUE + "/power/compute";
        when(membership.visibleLeague(eq(LEAGUE), any())).thenReturn(Optional.of(CURRENT));
        check(as(post(url).param("season", "2026").param("week", "2"), ME), 403, "compute-403");
        when(membership.canCommission(eq(100L), any())).thenReturn(true);
        when(scoredWeeks.of(100L)).thenReturn(new ScoredWeeks.Snapshot(3, 2, Set.of(1, 2)));
        when(power.computeWeek0IfMissing(100L, LEAGUE, 2026)).thenReturn(new PowerRankingService.Week0Result(entries(0), null));
        when(power.computeRealized(100L, 2026, 2)).thenReturn(entries(4));
        when(playoffOdds.compute(anyLong(), anyInt(), anyInt())).thenReturn(List.of(
                new PlayoffOddsRepository.Entry(1, 50.0, null, null, 7.0, 1400.0, "{}", "{}")));
        // Week 2 final: realized written, odds through 2, no skip reasons.
        check(as(post(url).param("season", "2026").param("week", "2"), ME), 200, "compute-final-week");
        // Week 3 open: nothing realized, odds through the latest final week.
        check(as(post(url).param("season", "2026").param("week", "3"), ME), 200, "compute-open-week");
        // Final week with nothing to write: the gap is reported.
        when(power.computeRealized(100L, 2026, 1)).thenReturn(entries(0));
        when(power.realizedGap(100L, 1)).thenReturn("week 1 has no scored matchups yet");
        check(as(post(url).param("season", "2026").param("week", "1"), ME), 200, "compute-final-empty");
        // No final week at all, and a league that has not drafted: every skip reason.
        when(scoredWeeks.of(100L)).thenReturn(new ScoredWeeks.Snapshot(0, 0, Set.of()));
        when(power.computeWeek0IfMissing(100L, LEAGUE, 2026)).thenReturn(new PowerRankingService.Week0Result(entries(0), "the league has not drafted"));
        check(as(post(url).param("season", "2026").param("week", "1"), ME), 200, "compute-nothing-final");
    }

    // ------------------------------------------------------------------ commissioner

    @Test
    void commissionerEveryOutcome() throws Exception {
        String url = "/api/leagues/" + LEAGUE + "/power/commissioner";
        when(membership.visibleLeague(eq(LEAGUE), any())).thenReturn(Optional.of(CURRENT));
        when(power.sportState(Sport.NFL)).thenReturn(new PowerRankingService.SportState(4, "2026", "2026-09-04", true));
        check(as(post(url), ME).contentType("application/json").content("{\"season\":2026,\"week\":4,\"rosterIds\":[]}"), 400, "commissioner-400-ids");
        check(as(post(url), ME).contentType("application/json").content("{\"week\":4,\"rosterIds\":[1]}"), 400, "commissioner-400-season");
        check(as(post(url), ME).contentType("application/json").content("{\"season\":2026,\"week\":4,\"rosterIds\":[1]}"), 403, "commissioner-403-unknown");
        when(leagueMembers.anyCommissioner(100L)).thenReturn(true);
        check(as(post(url), ME).contentType("application/json").content("{\"season\":2026,\"week\":4,\"rosterIds\":[1]}"), 403, "commissioner-403-known");
        when(membership.canCommission(eq(100L), any())).thenReturn(true);
        check(as(post(url), ME).contentType("application/json").content("{\"season\":2026,\"week\":0,\"rosterIds\":[1]}"), 400, "commissioner-400-backdate");
        when(power.saveCommissionerRanking(anyLong(), anyString(), anyInt(), anyInt(), any())).thenReturn(entries(2));
        check(as(post(url), ME).contentType("application/json").content("{\"season\":2026,\"week\":4,\"rosterIds\":[1,2]}"), 200, "commissioner-ok");
    }
}
