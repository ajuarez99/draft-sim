package com.ballknowers.draftsim.refresh;

import com.ballknowers.draftsim.ingest.LeagueHistoryIngestService;
import com.ballknowers.draftsim.ingest.PlayerGameIngestService;
import com.ballknowers.draftsim.ingest.SleeperClient;
import com.ballknowers.draftsim.store.LeagueMemberRepository;
import com.ballknowers.draftsim.store.LeagueRefreshRepository;
import com.ballknowers.draftsim.store.SportTrendingRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

/**
 * specs/014 T031 (quickstart V1.5): a trending failure must not touch a league refresh.
 * Real Postgres and the real {@code LeagueRefreshService} / {@code TrendingRefresh}; the
 * Sleeper client, the other ingest services and the trending repository are mocked, so the
 * shared local {@code sport_trending} rows are neither read nor overwritten.
 *
 * <p>SKIPS when the local Postgres is unreachable, so the caller must read the skip count.
 */
@SpringBootTest(properties = "refresh.on-visit.enabled=true")
class TrendingRefreshIsolationIT {

    private static final String JDBC_URL = "jdbc:postgresql://localhost:5433/draftsim";
    private static final String USER = "draftsim";
    private static final String PASSWORD = "draftsim";

    static final String LEAGUE = "it-014-trending-league";
    static final String MEMBER = "it-014-trending-member";

    @BeforeAll
    static void requiresLocalPostgres() {
        boolean reachable;
        try (Connection c = DriverManager.getConnection(JDBC_URL, USER, PASSWORD)) {
            reachable = true;
        } catch (SQLException e) {
            reachable = false;
        }
        Assumptions.assumeTrue(reachable,
                "no local Postgres reachable at " + JDBC_URL + " -- skipping trending isolation IT");
    }

    @MockitoBean private LeagueHistoryIngestService history;
    @MockitoBean private PlayerGameIngestService playerGames;
    @MockitoBean private SleeperClient sleeper;
    @MockitoBean private SportTrendingRepository trendingRepo;
    @Autowired private RefreshController controller;
    @Autowired private LeagueMemberRepository leagueMembers;
    @Autowired private LeagueRefreshRepository refreshes;
    @Autowired private JdbcTemplate jdbc;

    private long leagueId;

    @BeforeEach
    void setUp() {
        tearDown();
        long managerId = jdbc.queryForObject(
                "insert into manager (sleeper_user_id, display_name) values (?, ?) returning id",
                Long.class, MEMBER, "IT Trending Member");
        leagueId = jdbc.queryForObject(
                "insert into league (sport, season, sleeper_id, name, total_rosters, status) "
                        + "values ('nba', 1997, ?, 'IT trending league', 12, 'in_season') returning id",
                Long.class, LEAGUE);
        leagueMembers.upsert(leagueId, managerId, false, "IT Team");
        when(history.ingestChain(any(), eq(LEAGUE), any()))
                .thenReturn(new LeagueHistoryIngestService.Result(1, 0, 0, 0, 0));
        when(sleeper.trendingAdds(anyString(), anyInt(), anyInt())).thenThrow(new IllegalStateException("trending 503"));
    }

    @AfterEach
    void tearDown() {
        jdbc.update("delete from league where sleeper_id = ?", LEAGUE);
        jdbc.update("delete from manager where sleeper_user_id = ?", MEMBER);
    }

    private LeagueRefreshRepository.Row awaitRow(boolean wantSuccess) throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(15);
        while (System.nanoTime() < deadline) {
            var row = refreshes.find(leagueId);
            if (row.isPresent() && (wantSuccess ? row.get().lastSuccessAt() != null : row.get().lastFailureAt() != null)) {
                return row.get();
            }
            Thread.sleep(25);
        }
        fail("league_refresh never recorded a " + (wantSuccess ? "success" : "failure"));
        return null;
    }

    @Test
    void aTrendingFailureStillLeavesTheLeagueRefreshSuccessful() throws Exception {
        when(playerGames.refreshSportSeason(any(), anyInt(), any()))
                .thenReturn(new PlayerGameIngestService.Result(0, 0, 0, 0, 0, false));

        controller.trigger(LEAGUE, MEMBER);
        LeagueRefreshRepository.Row row = awaitRow(true);

        assertNotNull(row.lastSuccessAt(), "last_success_at must be set despite the trending failure");
        assertNull(row.lastFailureAt(), "trending's failure must not be recorded against the league");
        verify(sleeper, atLeastOnce()).trendingAdds("nba", 24, 25);
        verify(trendingRepo).recordFailure(eq("nba"), any(), argThat(r -> r.contains("trending 503")));
    }

    @Test
    void trendingStillRunsWhenPerGameFetchingFailedAndDoesNotChangeThatOutcome() throws Exception {
        // weeksFailed = 1: the refresh fails on its own account, and trending is attempted first.
        when(playerGames.refreshSportSeason(any(), anyInt(), any()))
                .thenReturn(new PlayerGameIngestService.Result(0, 0, 1, 0, 0, false));

        controller.trigger(LEAGUE, MEMBER);
        LeagueRefreshRepository.Row row = awaitRow(false);

        assertNotNull(row.lastFailure());
        assertTrue(row.lastFailure().contains("per-game week"), "the failure is the per-game one: " + row.lastFailure());
        verify(sleeper, atLeastOnce()).trendingAdds("nba", 24, 25);
    }
}
