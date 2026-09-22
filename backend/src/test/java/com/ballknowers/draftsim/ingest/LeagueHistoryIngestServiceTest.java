package com.ballknowers.draftsim.ingest;

import com.ballknowers.draftsim.domain.Sport;
import com.ballknowers.draftsim.store.LeagueMatchupRepository;
import com.ballknowers.draftsim.store.LeagueMemberRepository;
import com.ballknowers.draftsim.store.LeagueRepository;
import com.ballknowers.draftsim.store.ManagerRepository;
import com.ballknowers.draftsim.store.RosterSeasonRepository;
import com.ballknowers.draftsim.store.RosterWeekPointsRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.*;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/**
 * claude/league-suite.md Phase A. Finding 2/5 of claude/plan-review-league-suite.md
 * are both regression-shaped bugs (loop past last_scored_leg; refetch everything
 * every time) -- pinned here rather than only caught by eye against a live league.
 */
@ExtendWith(MockitoExtension.class)
class LeagueHistoryIngestServiceTest {

    @Mock private SleeperClient sleeper;
    @Mock private LeagueRepository leagues;
    @Mock private ManagerRepository managers;
    @Mock private LeagueMemberRepository leagueMembers;
    @Mock private RosterSeasonRepository rosterSeasons;
    @Mock private RosterWeekPointsRepository weekPoints;
    @Mock private LeagueMatchupRepository fixtures;
    @Mock private TransactionIngestService transactions;

    private LeagueHistoryIngestService service;

    @BeforeEach
    void setUp() {
        service = new LeagueHistoryIngestService(sleeper, leagues, managers, leagueMembers, rosterSeasons,
                weekPoints, fixtures, transactions);
        lenient().when(leagues.upsert(any(), anyInt(), any(), any(), any(), anyInt(), any(), any(), any(), any()))
                .thenReturn(55L);
    }

    private static Map<String, Object> leagueObject(String leagueId, int season, Map<String, Object> settings,
                                                     Map<String, Object> metadata) {
        return leagueObject(leagueId, season, settings, metadata, null);
    }

    /**
     * specs/006-deeper-history-both-sports T023: {@code status} is now the
     * gate on the champion write, so a test that cares about
     * {@code finalPlacement} has to state it explicitly rather than rely on
     * the 4-arg overload's default of {@code null} (not yet known -- which
     * {@link LeagueRepository.LeagueRow#isComplete} treats as NOT complete,
     * same as every other unfinished state).
     */
    private static Map<String, Object> leagueObject(String leagueId, int season, Map<String, Object> settings,
                                                     Map<String, Object> metadata, String status) {
        Map<String, Object> l = new HashMap<>();
        l.put("league_id", leagueId);
        l.put("season", season);
        l.put("previous_league_id", null);
        l.put("settings", settings);
        l.put("metadata", metadata);
        l.put("status", status);
        return l;
    }

    private static Map<String, Object> rosterObject(int rosterId, String ownerId, int wins, int losses,
                                                     int fpts, int fptsDecimal) {
        Map<String, Object> settings = new HashMap<>();
        settings.put("wins", wins);
        settings.put("losses", losses);
        settings.put("fpts", fpts);
        settings.put("fpts_decimal", fptsDecimal);
        Map<String, Object> r = new HashMap<>();
        r.put("roster_id", rosterId);
        r.put("owner_id", ownerId);
        r.put("settings", settings);
        return r;
    }

    @Test
    void standingsCombineWholeAndDecimalPointsAndFlagTheChampion() {
        Map<String, Object> metadata = Map.of("latest_league_winner_roster_id", "2");
        // status: "complete" -- T023 gates the champion write on it. Without
        // this, latest_league_winner_roster_id is not trustworthy (see
        // ChampionOnlyWhenCompleteTest and this file's own re-ingest case
        // below), so this test states the one status under which the
        // metadata key IS the right answer.
        Map<String, Object> league = leagueObject("L1", 2025, Map.of("last_scored_leg", 0), metadata, "complete");
        when(sleeper.leagueChain("L1")).thenReturn(List.of(league));
        when(sleeper.leagueUsers("L1")).thenReturn(List.of(Map.of("user_id", "u1", "display_name", "Alice"),
                Map.of("user_id", "u2", "display_name", "Bob")));
        when(managers.upsert("u1", "Alice", null)).thenReturn(101L);
        when(managers.upsert("u2", "Bob", null)).thenReturn(102L);
        when(sleeper.rosters("L1")).thenReturn(List.of(
                rosterObject(1, "u1", 8, 6, 1500, 42),
                rosterObject(2, "u2", 10, 4, 1600, 5)));

        service.ingestChain(Sport.NFL, "L1");

        ArgumentCaptor<List<RosterSeasonRepository.Upsert>> captor = ArgumentCaptor.forClass(List.class);
        verify(rosterSeasons).upsertAll(captor.capture());
        List<RosterSeasonRepository.Upsert> rows = captor.getValue();
        assertEquals(2, rows.size());

        RosterSeasonRepository.Upsert roster1 = rows.stream().filter(r -> r.rosterId() == 1).findFirst().orElseThrow();
        assertEquals(101L, roster1.managerId());
        assertEquals(1500.42, roster1.pointsFor(), 1e-9);
        assertNull(roster1.finalPlacement(), "roster 1 is not the champion");

        RosterSeasonRepository.Upsert roster2 = rows.stream().filter(r -> r.rosterId() == 2).findFirst().orElseThrow();
        assertEquals(1600.05, roster2.pointsFor(), 1e-9);
        assertEquals(1, roster2.finalPlacement(), "metadata.latest_league_winner_roster_id says roster 2 won it");
    }

    /**
     * specs/006-deeper-history-both-sports T022, research R2 -- the crux of
     * the whole fix. Gating the champion WRITE (T023) is not enough on its
     * own if the gate could ALSO cause the roster to be dropped from the
     * upsert batch entirely: a skip gate in front of a column never repairs
     * it, which is precisely how {@code adp_at_time} and, separately, the
     * {@code league_matchup} fixture gate each shipped as real bugs in this
     * repo (see this file's own javadoc, and the pairings/starters tests
     * above, for the second one). Reuses L1's exact shape --
     * {@code latest_league_winner_roster_id: "2"} -- so this is
     * unmistakably the SAME league {@code standingsCombineWholeAndDecimalPointsAndFlagTheChampion}
     * proved WOULD be crowned under {@code status: "complete"}; here the
     * status is {@code "in_season"} instead, simulating a re-ingest of a
     * league whose {@code roster_season.final_placement = 1} is already
     * wrongly stored from before T023 existed (baseline.md T003: exactly
     * this shape, for popsharky and gregmullen).
     */
    @Test
    void reIngestClearsAPreviouslyStoredChampionRatherThanSkippingTheRow() {
        Map<String, Object> metadata = Map.of("latest_league_winner_roster_id", "2");
        Map<String, Object> league = leagueObject("L1b", 2026, Map.of("last_scored_leg", 0), metadata, "in_season");
        when(sleeper.leagueChain("L1b")).thenReturn(List.of(league));
        when(sleeper.leagueUsers("L1b")).thenReturn(List.of(Map.of("user_id", "u1", "display_name", "Alice"),
                Map.of("user_id", "u2", "display_name", "Bob")));
        when(managers.upsert("u1", "Alice", null)).thenReturn(101L);
        when(managers.upsert("u2", "Bob", null)).thenReturn(102L);
        when(sleeper.rosters("L1b")).thenReturn(List.of(
                rosterObject(1, "u1", 8, 6, 1500, 42),
                rosterObject(2, "u2", 1, 0, 190, 0)));

        service.ingestChain(Sport.NFL, "L1b");

        ArgumentCaptor<List<RosterSeasonRepository.Upsert>> captor = ArgumentCaptor.forClass(List.class);
        verify(rosterSeasons).upsertAll(captor.capture());
        List<RosterSeasonRepository.Upsert> rows = captor.getValue();

        // Not skipped: roster 2 -- the one metadata still names as the
        // "winner" -- is still present in the batch handed to upsertAll.
        // A skip gate would have simply omitted this row from the list,
        // leaving whatever the DB already had untouched forever.
        assertEquals(2, rows.size(), "every roster is still written, not omitted from the batch");

        RosterSeasonRepository.Upsert roster2 = rows.stream().filter(r -> r.rosterId() == 2).findFirst().orElseThrow();
        assertNull(roster2.finalPlacement(),
                "status: in_season -- a previously-stored champion must be CLEARED (written as null), "
                        + "not left alone by skipping the row");
    }

    @Test
    void weeklyIngestNeverFetchesPastLastScoredLeg() {
        Map<String, Object> league = leagueObject("L2", 2025, Map.of("last_scored_leg", 3), Map.of());
        when(sleeper.leagueChain("L2")).thenReturn(List.of(league));
        when(sleeper.leagueUsers("L2")).thenReturn(List.of());
        when(sleeper.rosters("L2")).thenReturn(List.of());
        when(weekPoints.storedWeeks(55L)).thenReturn(Set.of());
        when(sleeper.matchups(eq("L2"), anyInt())).thenReturn(List.of());

        service.ingestChain(Sport.NFL, "L2");

        verify(sleeper, times(1)).matchups("L2", 1);
        verify(sleeper, times(1)).matchups("L2", 2);
        verify(sleeper, times(1)).matchups("L2", 3);
        verify(sleeper, never()).matchups(eq("L2"), intThat(w -> w > 3));
    }

    @Test
    void reRunningSkipsAlreadyStoredWeeksExceptTheMostRecentlyScoredOne() {
        Map<String, Object> league = leagueObject("L3", 2025, Map.of("last_scored_leg", 4), Map.of());
        when(sleeper.leagueChain("L3")).thenReturn(List.of(league));
        when(sleeper.leagueUsers("L3")).thenReturn(List.of());
        when(sleeper.rosters("L3")).thenReturn(List.of());
        // Weeks 1-3 fully cached -- BOTH scores and pairings. Before
        // specs/002-league-history-record-book this test stubbed only the
        // scores, which made it pass while pairings were silently never
        // backfilled; a week is settled only when both tables hold it.
        when(weekPoints.storedWeeks(55L)).thenReturn(Set.of(1, 2, 3));
        when(fixtures.scheduledWeeks(55L, 2025)).thenReturn(Set.of(1, 2, 3));
        // And starters, for the same reason the pairings stub was added:
        // specs/004-ffwrapped-feature-parity V18 put a third thing in a
        // roster-week, so "settled" means all three are present.
        when(weekPoints.weeksWithStarters(55L)).thenReturn(Set.of(1, 2, 3));
        when(sleeper.matchups(eq("L3"), anyInt())).thenReturn(List.of());

        service.ingestChain(Sport.NFL, "L3");

        verify(sleeper, never()).matchups("L3", 1);
        verify(sleeper, never()).matchups("L3", 2);
        verify(sleeper, never()).matchups("L3", 3);
        verify(sleeper, times(1)).matchups("L3", 4);
    }

    /**
     * specs/002-league-history-record-book FR-007, research D2.
     *
     * <p>The bug this pins: league_matchup was added after roster_week_points,
     * so every season ingested before it has complete scores and no pairings.
     * With the gate keyed on scores alone, those weeks were skipped forever and
     * re-running the ingest could never repair them -- the skip was keyed on
     * the table that was already full. Measured live on (Foot) Ball Knowers
     * 2025: 204 scores across 17 weeks, pairings for one.
     *
     * <p>Nothing throws in that state. The page just quietly computes its
     * "biggest blowout" from one week.
     */
    @Test
    void reRunningBackfillsPairingsForWeeksWhoseScoresAreAlreadyCached() {
        Map<String, Object> league = leagueObject("L3b", 2025, Map.of("last_scored_leg", 4), Map.of());
        when(sleeper.leagueChain("L3b")).thenReturn(List.of(league));
        when(sleeper.leagueUsers("L3b")).thenReturn(List.of());
        when(sleeper.rosters("L3b")).thenReturn(List.of());
        // Scores for every week, pairings for none -- the state this database
        // was actually in.
        when(weekPoints.storedWeeks(55L)).thenReturn(Set.of(1, 2, 3, 4));
        when(fixtures.scheduledWeeks(55L, 2025)).thenReturn(Set.of());
        when(sleeper.matchups(eq("L3b"), anyInt())).thenReturn(List.of());

        service.ingestChain(Sport.NFL, "L3b");

        verify(sleeper, times(1)).matchups("L3b", 1);
        verify(sleeper, times(1)).matchups("L3b", 2);
        verify(sleeper, times(1)).matchups("L3b", 3);
        verify(sleeper, times(1)).matchups("L3b", 4);
    }

    /**
     * specs/004-ffwrapped-feature-parity FR-010, research R6.
     *
     * <p>The same bug as the pairings case above, one column later. V18 added
     * roster_week_points.starters; with the gate keyed on scores and pairings
     * alone, every week already stored would be skipped forever and the new
     * column would stay null no matter how many times ingest was re-run --
     * because the skip is keyed on the tables that are already full.
     *
     * <p>Nothing throws in that state either. The weekly report just quietly
     * omits an award for every week of the season.
     */
    @Test
    void reRunningBackfillsStartersForWeeksWhoseScoresAndPairingsAreAlreadyCached() {
        Map<String, Object> league = leagueObject("L3d", 2025, Map.of("last_scored_leg", 4), Map.of());
        when(sleeper.leagueChain("L3d")).thenReturn(List.of(league));
        when(sleeper.leagueUsers("L3d")).thenReturn(List.of());
        when(sleeper.rosters("L3d")).thenReturn(List.of());
        // Scores and pairings for every week, starters for none -- the state
        // the database is in the moment V18 is applied.
        when(weekPoints.storedWeeks(55L)).thenReturn(Set.of(1, 2, 3, 4));
        when(fixtures.scheduledWeeks(55L, 2025)).thenReturn(Set.of(1, 2, 3, 4));
        when(weekPoints.weeksWithStarters(55L)).thenReturn(Set.of());
        when(sleeper.matchups(eq("L3d"), anyInt())).thenReturn(List.of());

        service.ingestChain(Sport.NFL, "L3d");

        verify(sleeper, times(1)).matchups("L3d", 1);
        verify(sleeper, times(1)).matchups("L3d", 2);
        verify(sleeper, times(1)).matchups("L3d", 3);
        verify(sleeper, times(1)).matchups("L3d", 4);
    }

    /**
     * The mirror case: pairings present, scores missing. Fetching must still
     * happen -- the gate is an OR, and keying it on pairings alone would just
     * move the same bug to the other table.
     */
    @Test
    void reRunningFetchesWeeksWhosePairingsAreCachedButScoresAreNot() {
        Map<String, Object> league = leagueObject("L3c", 2025, Map.of("last_scored_leg", 2), Map.of());
        when(sleeper.leagueChain("L3c")).thenReturn(List.of(league));
        when(sleeper.leagueUsers("L3c")).thenReturn(List.of());
        when(sleeper.rosters("L3c")).thenReturn(List.of());
        when(weekPoints.storedWeeks(55L)).thenReturn(Set.of());
        when(fixtures.scheduledWeeks(55L, 2025)).thenReturn(Set.of(1, 2));
        when(sleeper.matchups(eq("L3c"), anyInt())).thenReturn(List.of());

        service.ingestChain(Sport.NFL, "L3c");

        verify(sleeper, times(1)).matchups("L3c", 1);
        verify(sleeper, times(1)).matchups("L3c", 2);
    }

    @Test
    void weeklyPointsAreStoredPerRosterFromThePointsField() {
        Map<String, Object> league = leagueObject("L4", 2025, Map.of("last_scored_leg", 1), Map.of());
        when(sleeper.leagueChain("L4")).thenReturn(List.of(league));
        when(sleeper.leagueUsers("L4")).thenReturn(List.of());
        when(sleeper.rosters("L4")).thenReturn(List.of());
        when(weekPoints.storedWeeks(55L)).thenReturn(Set.of());
        when(sleeper.matchups("L4", 1)).thenReturn(List.of(
                matchup(1, 101.5), matchup(2, 88.25)));

        service.ingestChain(Sport.NFL, "L4");

        ArgumentCaptor<RosterWeekPointsRepository.Row> captor = ArgumentCaptor.forClass(RosterWeekPointsRepository.Row.class);
        verify(weekPoints, times(2)).upsert(captor.capture());
        List<RosterWeekPointsRepository.Row> rows = captor.getAllValues();
        assertEquals(101.5, rows.stream().filter(r -> r.rosterId() == 1).findFirst().orElseThrow().startersPoints());
        assertEquals(88.25, rows.stream().filter(r -> r.rosterId() == 2).findFirst().orElseThrow().startersPoints());
    }

    private static Map<String, Object> matchup(int rosterId, double points) {
        Map<String, Object> m = new HashMap<>();
        m.put("roster_id", rosterId);
        // "points" is the roster's scored total for the week; starters_points is a
        // per-slot array, not a scalar -- see the ingest comment for how this was found.
        m.put("points", points);
        m.put("starters_points", List.of());
        m.put("players_points", Map.of());
        return m;
    }

    /**
     * specs/004-ffwrapped-feature-parity T125/T126.
     *
     * <p>TransactionIngestService was written, tested and then called by nothing
     * but a manual endpoint, so five of six real leagues held zero transactions
     * while the suite stayed green -- no test walked the chain as far as asking
     * whether transactions came with it. This is that test: once per season in
     * the chain, by the league id of that season rather than the one requested.
     */
    @Test
    void theChainWalkIngestsTransactionsForEverySeason() {
        Map<String, Object> older = leagueObject("L9-2024", 2024, Map.of("last_scored_leg", 1), Map.of());
        Map<String, Object> current = leagueObject("L9", 2025, Map.of("last_scored_leg", 1), Map.of());
        when(sleeper.leagueChain("L9")).thenReturn(List.of(current, older));
        when(sleeper.leagueUsers(anyString())).thenReturn(List.of());
        when(sleeper.rosters(anyString())).thenReturn(List.of());
        when(weekPoints.storedWeeks(55L)).thenReturn(Set.of());
        when(sleeper.matchups(anyString(), anyInt())).thenReturn(List.of());
        when(transactions.ingest("L9")).thenReturn(4);
        when(transactions.ingest("L9-2024")).thenReturn(7);

        LeagueHistoryIngestService.Result result = service.ingestChain(Sport.NFL, "L9");

        verify(transactions, times(1)).ingest("L9");
        verify(transactions, times(1)).ingest("L9-2024");
        // Counted and reported, not merely called: the endpoint's own response is
        // how anyone running an ingest finds out whether transactions arrived.
        assertEquals(11, result.transactionsIngested());
    }
}
