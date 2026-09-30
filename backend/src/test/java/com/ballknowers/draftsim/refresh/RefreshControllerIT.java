package com.ballknowers.draftsim.refresh;

import com.ballknowers.draftsim.ingest.LeagueHistoryIngestService;
import com.ballknowers.draftsim.ingest.PlayerGameIngestService;
import com.ballknowers.draftsim.store.LeagueMemberRepository;
import com.ballknowers.draftsim.store.LeagueRefreshRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.time.Instant;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

/**
 * specs/009-auto-data-refresh T023, the visit half: real Postgres, Sleeper-facing
 * ingest services mocked. Refresh-on-visit is switched ON explicitly here because
 * the test profile turns it off globally (research R12); the off case is
 * {@link RefreshControllerOnVisitDisabledIT}. Calls the controller directly, like
 * {@code SuperlativesControllerIT}: the CORS preflight is a browser concern for
 * the live check (T029), not something a JVM-side test can exercise.
 *
 * <p>SKIPS when the local Postgres is unreachable, so the caller must read the skip count.
 */
@SpringBootTest(properties = "refresh.on-visit.enabled=true")
class RefreshControllerIT {

    private static final String JDBC_URL = "jdbc:postgresql://localhost:5433/draftsim";
    private static final String USER = "draftsim";
    private static final String PASSWORD = "draftsim";

    static final String LEAGUE = "it-009-refresh-league";
    static final String MEMBER = "it-009-refresh-member";
    static final String STRANGER = "it-009-refresh-stranger";

    @BeforeAll
    static void requiresLocalPostgres() {
        boolean reachable;
        try (Connection c = DriverManager.getConnection(JDBC_URL, USER, PASSWORD)) {
            reachable = true;
        } catch (SQLException e) {
            reachable = false;
        }
        Assumptions.assumeTrue(reachable,
                "no local Postgres reachable at " + JDBC_URL + " -- skipping refresh controller IT");
    }

    @MockitoBean private LeagueHistoryIngestService history;
    @MockitoBean private PlayerGameIngestService playerGames;
    @Autowired private RefreshController controller;
    @Autowired private LeagueMemberRepository leagueMembers;
    @Autowired private LeagueRefreshRepository refreshes;
    @Autowired private JdbcTemplate jdbc;

    private long leagueId;
    private long managerId;

    @BeforeEach
    void setUp() {
        tearDown();
        managerId = jdbc.queryForObject(
                "insert into manager (sleeper_user_id, display_name) values (?, ?) returning id",
                Long.class, MEMBER, "IT Member");
        leagueId = insertLeague("in_season");
        leagueMembers.upsert(leagueId, managerId, false, "IT Team");
        when(playerGames.refreshSportSeason(any(), anyInt(), any()))
                .thenReturn(new PlayerGameIngestService.Result(0, 0, 0, 0, 0));
    }

    @AfterEach
    void tearDown() {
        // league_refresh, league_member cascade from league.
        jdbc.update("delete from league where sleeper_id = ?", LEAGUE);
        jdbc.update("delete from manager where sleeper_user_id in (?, ?)", MEMBER, STRANGER);
        jdbc.update("delete from sport_week_stats where sport = 'nba' and season = 1998");
    }

    /** One per-game week for the IT's sport-season, final or not (review fix 2026-09-28). */
    private void perGameWeek(boolean fin) {
        jdbc.update("insert into sport_week_stats (sport, season, week, fetched_at, final) "
                + "values ('nba', 1998, 1, now(), ?) on conflict (sport, season, week) do update set final = excluded.final", fin);
    }

    private long insertLeague(String status) {
        return jdbc.queryForObject(
                "insert into league (sport, season, sleeper_id, name, total_rosters, status) "
                        + "values ('nba', 1998, ?, 'IT refresh league', 12, ?) returning id",
                Long.class, LEAGUE, status);
    }

    /**
     * Several tests below drive the NEWER season of a chain (1999) as a caller with no
     * identity. That used to work because a blank identity saw every league. It no
     * longer does (claude/audit-2026-09-28/01), and MEMBER cannot see a successor
     * season (membership walks previous_league_id backwards only), so those calls run
     * as the operator: the request carries the admin token, the one caller for whom
     * "no identity" still reaches the league.
     */
    private static <T> T asOperator(java.util.function.Supplier<T> call) {
        try (var admin = com.ballknowers.draftsim.TestAdmin.asAdmin()) {
            return call.get();
        }
    }

    @Test
    void aStrangerGets404OnBothEndpointsAndStartsNothing() {
        assertEquals(404, controller.trigger(LEAGUE, STRANGER).getStatusCode().value());
        assertEquals(404, controller.status(LEAGUE, STRANGER).getStatusCode().value());
        assertEquals(404, controller.trigger("it-009-no-such-league", MEMBER).getStatusCode().value());
        verifyNoInteractions(history);
    }

    /**
     * The header-less case that used to be the way in: no X-Sleeper-User must 404 on both
     * routes and start nothing (claude/audit-2026-09-28/01, amendment (a)).
     */
    @Test
    void aCallerWithNoIdentityGets404OnBothEndpointsAndStartsNothing() {
        for (String blank : new String[] {null, "", "  "}) {
            assertEquals(404, controller.trigger(LEAGUE, blank).getStatusCode().value());
            assertEquals(404, controller.status(LEAGUE, blank).getStatusCode().value());
        }
        verifyNoInteractions(history);
    }

    @Test
    void thePlayersRouteRefusesACallerWithNoIdentity() {
        assertEquals(401, controller.players("nba", null).getStatusCode().value());
        assertEquals(401, controller.players("nba", " ").getStatusCode().value());
    }

    @Test
    void aStaleLeagueReturnsRunningAndASecondPostDuringTheRunStartsNoSecondRun() throws Exception {
        CountDownLatch started = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        when(history.ingestChain(any(), eq(LEAGUE), any())).thenAnswer(inv -> {
            started.countDown();
            assertTrue(release.await(15, TimeUnit.SECONDS));
            return new LeagueHistoryIngestService.Result(1, 0, 0, 0, 0);
        });

        Map<String, Object> first = body(controller.trigger(LEAGUE, MEMBER));
        assertEquals("RUNNING", first.get("state"));
        assertEquals(LEAGUE, first.get("leagueSleeperId"));
        assertEquals(1998, first.get("season"));
        assertNull(first.get("lastSuccessAt"));
        assertNull(first.get("lastFailureAt"));
        assertTrue(started.await(5, TimeUnit.SECONDS));

        Map<String, Object> second = body(controller.trigger(LEAGUE, MEMBER));
        assertEquals("RUNNING", second.get("state"));
        assertEquals("RUNNING", body(controller.status(LEAGUE, MEMBER)).get("state"));

        release.countDown();
        Map<String, Object> done = awaitState("FRESH");
        assertNotNull(done.get("lastSuccessAt"));

        verify(history, times(1)).ingestChain(any(), eq(LEAGUE), any());
        assertFalse(refreshes.find(leagueId).orElseThrow().loadedComplete(), "in_season never marks loaded_complete");

        // A fresh season is not started again on the next visit.
        assertEquals("FRESH", body(controller.trigger(LEAGUE, MEMBER)).get("state"));
        verify(history, times(1)).ingestChain(any(), eq(LEAGUE), any());
    }

    @Test
    void aCompleteSeasonIsMarkedLoadedCompleteAfterASuccessfulRunAndNeverFetchedAgain() throws Exception {
        jdbc.update("update league set status = 'complete' where id = ?", leagueId);
        perGameWeek(true);
        when(history.ingestChain(any(), eq(LEAGUE), any()))
                .thenReturn(new LeagueHistoryIngestService.Result(1, 0, 0, 0, 0));

        controller.trigger(LEAGUE, MEMBER);
        awaitState("COMPLETE");

        assertTrue(refreshes.find(leagueId).orElseThrow().loadedComplete());
        assertEquals("COMPLETE", body(controller.trigger(LEAGUE, MEMBER)).get("state"));
        verify(history, times(1)).ingestChain(any(), eq(LEAGUE), any());
    }

    @Test
    void aCompleteLeagueWithANonFinalPerGameWeekIsNotFrozen() throws Exception {
        // Review fix 2026-09-28: the league reads complete, but a per-game week is
        // still inside WEEK_FINAL_AFTER. loaded_complete must stay false, so a later
        // refresh picks up Sleeper's stat corrections.
        jdbc.update("update league set status = 'complete' where id = ?", leagueId);
        perGameWeek(false);
        when(history.ingestChain(any(), eq(LEAGUE), any()))
                .thenReturn(new LeagueHistoryIngestService.Result(1, 0, 0, 0, 0));

        controller.trigger(LEAGUE, MEMBER);
        awaitState("FRESH");

        assertFalse(refreshes.find(leagueId).orElseThrow().loadedComplete());
    }

    @Test
    void aFailedRunRecordsFailureNeverLoadedCompleteAndBacksOff() throws Exception {
        jdbc.update("update league set status = 'complete' where id = ?", leagueId);
        when(history.ingestChain(any(), eq(LEAGUE), any())).thenThrow(new IllegalStateException("sleeper 503"));

        controller.trigger(LEAGUE, MEMBER);
        Map<String, Object> failed = awaitState("FAILED");

        assertNotNull(failed.get("lastFailureAt"));
        LeagueRefreshRepository.Row row = refreshes.find(leagueId).orElseThrow();
        assertFalse(row.loadedComplete(), "a failed run must never set loaded_complete");
        assertNotNull(row.lastFailure());

        // Within the retry window a visit starts nothing, and the key was released.
        controller.trigger(LEAGUE, MEMBER);
        verify(history, times(1)).ingestChain(any(), eq(LEAGUE), any());
    }

    @Test
    void aChainRunShowsRunningAtTheTopEvenWhenTheShownSeasonIsLoadedComplete() throws Exception {
        // Review fix 2026-09-28: stateOf returned COMPLETE before looking at chainRunning,
        // so the rail never saw RUNNING while a newer season refreshed behind a complete one.
        String newer = LEAGUE + "-2";
        jdbc.update("update league set status = 'complete' where id = ?", leagueId);
        Instant oldSuccess = Instant.now().minusSeconds(3600);
        refreshes.recordSuccess(leagueId, oldSuccess, true);
        jdbc.update("insert into league (sport, season, sleeper_id, previous_league_id, name, total_rosters, status) "
                + "values ('nba', 1999, ?, ?, 'IT refresh league 2', 12, 'in_season')", newer, LEAGUE);
        CountDownLatch started = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        try {
            when(history.ingestChain(any(), eq(newer), any())).thenAnswer(inv -> {
                started.countDown();
                release.await(20, TimeUnit.SECONDS);
                return new LeagueHistoryIngestService.Result(2, 0, 0, 0, 0);
            });
            asOperator(() -> controller.trigger(newer, null));
            assertTrue(started.await(5, TimeUnit.SECONDS));

            // The 1998 page shows 1998, which is loaded_complete -- yet the chain is running.
            Map<String, Object> during = body(controller.status(LEAGUE, MEMBER));
            assertEquals("RUNNING", during.get("state"));

            release.countDown();
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(15);
            Map<String, Object> after = during;
            while (System.nanoTime() < deadline && "RUNNING".equals(after.get("state"))) {
                Thread.sleep(25);
                after = body(controller.status(LEAGUE, MEMBER));
            }
            assertEquals("COMPLETE", after.get("state"));
            // lastSuccessAt follows the newest success in the chain, not the shown season's.
            assertTrue(Instant.parse((String) after.get("lastSuccessAt")).isAfter(oldSuccess));
        } finally {
            release.countDown();
            jdbc.update("delete from league where sleeper_id = ?", newer);
        }
    }

    @Test
    void aRunStartedFromTheNewerSeasonShowsRunningOnTheOlderSeasonsPage() throws Exception {
        // Live-verification fix 2026-09-28: the chain key must not depend on which
        // season's id a page uses, since chainBySleeperId only walks backwards.
        String newer = LEAGUE + "-2";
        jdbc.update("insert into league (sport, season, sleeper_id, previous_league_id, name, total_rosters, status) "
                + "values ('nba', 1999, ?, ?, 'IT refresh league 2', 12, 'in_season')", newer, LEAGUE);
        java.util.concurrent.CountDownLatch release = new java.util.concurrent.CountDownLatch(1);
        try {
            when(history.ingestChain(any(), eq(newer), any())).thenAnswer(inv -> {
                release.await(20, java.util.concurrent.TimeUnit.SECONDS);
                return new LeagueHistoryIngestService.Result(2, 0, 0, 0, 0);
            });

            asOperator(() -> controller.trigger(newer, null));
            assertEquals("RUNNING", body(controller.status(LEAGUE, MEMBER)).get("state"),
                    "the older season's page must see the chain run started from the newer one");

            release.countDown();
            awaitStateFor(newer, "FRESH");
        } finally {
            release.countDown();
            jdbc.update("delete from league where sleeper_id = ?", newer);
        }
    }

    @Test
    void completedSeasonsArePassedAsTheSkipSetAndNotWalked() throws Exception {
        // A newer season whose previous_league_id is the complete one.
        String newer = LEAGUE + "-2";
        jdbc.update("update league set status = 'complete' where id = ?", leagueId);
        refreshes.recordSuccess(leagueId, java.time.Instant.now(), true);
        jdbc.update("insert into league (sport, season, sleeper_id, previous_league_id, name, total_rosters, status) "
                + "values ('nba', 1999, ?, ?, 'IT refresh league 2', 12, 'in_season')", newer, LEAGUE);
        try {
            when(history.ingestChain(any(), eq(newer), any()))
                    .thenReturn(new LeagueHistoryIngestService.Result(2, 0, 0, 0, 0));

            asOperator(() -> controller.trigger(newer, null));
            awaitStateFor(newer, "FRESH");

            verify(history).ingestChain(any(), eq(newer), eq(Set.of(LEAGUE)));
            verify(playerGames, never()).refreshSportSeason(any(), eq(1998), any());
            verify(playerGames).refreshSportSeason(any(), eq(1999), any());
        } finally {
            jdbc.update("delete from league where sleeper_id = ?", newer);
        }
    }

    private Map<String, Object> awaitState(String state) throws InterruptedException {
        return awaitStateFor(LEAGUE, state);
    }

    private Map<String, Object> awaitStateFor(String leagueSleeperId, String state) throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(15);
        Map<String, Object> last = null;
        while (System.nanoTime() < deadline) {
            last = body(asOperator(() -> controller.status(leagueSleeperId, null)));
            if (state.equals(last.get("state"))) return last;
            Thread.sleep(25);
        }
        fail("state never reached " + state + "; last body " + last);
        return last;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> body(ResponseEntity<?> response) {
        assertEquals(200, response.getStatusCode().value());
        return (Map<String, Object>) response.getBody();
    }
}
