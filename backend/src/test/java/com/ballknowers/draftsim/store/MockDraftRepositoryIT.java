package com.ballknowers.draftsim.store;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Real-Postgres coverage for MockDraftRepository (claude/next-features-roadmap.md
 * §4, Phase 3) -- same reused-real-schema/gated-skip convention as
 * DraftRepositoryUpsertPicksIT.
 */
@SpringBootTest
class MockDraftRepositoryIT {

    private static final String JDBC_URL = "jdbc:postgresql://localhost:5433/draftsim";
    private static final String USER = "draftsim";
    private static final String PASSWORD = "draftsim";

    @BeforeAll
    static void requiresLocalPostgres() {
        boolean reachable;
        try (Connection c = DriverManager.getConnection(JDBC_URL, USER, PASSWORD)) {
            reachable = true;
        } catch (SQLException e) {
            reachable = false;
        }
        Assumptions.assumeTrue(reachable,
                "no local Postgres reachable at " + JDBC_URL + " -- skipping MockDraftRepository integration test");
    }

    @Autowired private MockDraftRepository mockDrafts;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private PlatformTransactionManager txManager;

    private long sessionId;
    private final java.util.Set<String> testPlayerSleeperIds = new java.util.HashSet<>();

    @BeforeEach
    void setUp() {
        sessionId = mockDrafts.createSession(8, 15, List.of("QB", "BN"), 1.0,
                "[{\"slot\":1,\"type\":\"USER\",\"managerId\":null}]", 1, 42L);
    }

    @AfterEach
    void tearDown() {
        // Session first (cascades its picks) -- a fixture player still referenced
        // by a mock_draft_pick row cannot be deleted, per mock_draft_pick's own
        // FK, so this order matters.
        jdbc.update("delete from mock_draft_session where id = ?", sessionId);
        for (String sleeperId : testPlayerSleeperIds) {
            jdbc.update("delete from player where sport = 'nfl' and sleeper_id = ?", sleeperId);
        }
    }

    @Test
    void createSessionRoundTripsThroughFind() {
        MockDraftRepository.SessionRow row = mockDrafts.find(sessionId).orElseThrow();

        assertEquals("IN_PROGRESS", row.status());
        assertEquals(8, row.teams());
        assertEquals(15, row.rounds());
        assertEquals(List.of("QB", "BN"), row.rosterPositions());
        assertEquals(1.0, row.pointsPerReception(), 0.001);
        assertEquals(1, row.userSlot());
        assertEquals(42L, row.rngSeed());
        assertEquals(1, row.currentPickNo());
        assertTrue(row.seatsJson().contains("USER"));
    }

    @Test
    void findReturnsEmptyForAnUnknownId() {
        assertTrue(mockDrafts.find(-1L).isEmpty());
    }

    @Test
    void advanceCurrentPickUpdatesStatusAndPickNo() {
        mockDrafts.advanceCurrentPick(sessionId, 9, "COMPLETE");

        MockDraftRepository.SessionRow row = mockDrafts.find(sessionId).orElseThrow();
        assertEquals(9, row.currentPickNo());
        assertEquals("COMPLETE", row.status());
    }

    @Test
    void insertPicksAndReadThemBackInPickOrder() {
        long p1 = insertTestPlayer("mdr-it-p1");
        long p2 = insertTestPlayer("mdr-it-p2");
        mockDrafts.insertPicks(sessionId, List.of(
                new MockDraftRepository.PickRow(sessionId, 2, 1, 2, "BOT", null, p2, "BOT"),
                new MockDraftRepository.PickRow(sessionId, 1, 1, 1, "USER", null, p1, "USER")));

        List<MockDraftRepository.PickRow> picks = mockDrafts.picks(sessionId);
        assertEquals(2, picks.size());
        assertEquals(1, picks.get(0).pickNo(), "must come back ordered by pick_no");
        assertEquals(p1, picks.get(0).playerId());
        assertEquals("USER", picks.get(0).source());
        assertEquals(2, picks.get(1).pickNo());
    }

    @Test
    void insertPicksRefusesADuplicatePickNoForTheSameSession() {
        long p1 = insertTestPlayer("mdr-it-dup-1");
        long p2 = insertTestPlayer("mdr-it-dup-2");
        mockDrafts.insertPicks(sessionId, List.of(
                new MockDraftRepository.PickRow(sessionId, 1, 1, 1, "USER", null, p1, "USER")));

        assertThrows(DuplicateKeyException.class, () -> mockDrafts.insertPicks(sessionId, List.of(
                new MockDraftRepository.PickRow(sessionId, 1, 1, 1, "BOT", null, p2, "BOT"))));
    }

    @Test
    void deletingTheSessionCascadesToItsPicks() {
        long p1 = insertTestPlayer("mdr-it-cascade");
        mockDrafts.insertPicks(sessionId, List.of(
                new MockDraftRepository.PickRow(sessionId, 1, 1, 1, "USER", null, p1, "USER")));
        assertEquals(1, mockDrafts.picks(sessionId).size());

        jdbc.update("delete from mock_draft_session where id = ?", sessionId);

        assertTrue(mockDrafts.picks(sessionId).isEmpty(), "picks must be gone once the session cascade-deletes");
    }

    @Test
    void allSessionsIncludesANewlyCreatedSessionNewestFirst() {
        List<MockDraftRepository.SessionSummary> all = mockDrafts.allSessions();
        assertTrue(all.stream().anyMatch(s -> s.id() == sessionId));
        assertEquals(sessionId, all.get(0).id(), "newest session (just created) must sort first");
    }

    /**
     * The concurrency guarantee the roadmap flags as this phase's own priority:
     * two transactions racing lockForUpdate on the same session must serialize,
     * not interleave. One thread holds the lock and sleeps briefly while the
     * other blocks on its own lockForUpdate call; an order-tracking list proves
     * the second transaction's read only happens after the first commits.
     */
    @Test
    void lockForUpdateSerializesTwoConcurrentTransactionsOnTheSameSession() throws InterruptedException {
        TransactionTemplate tx = new TransactionTemplate(txManager);
        List<String> order = java.util.Collections.synchronizedList(new java.util.ArrayList<>());
        CountDownLatch firstHasLock = new CountDownLatch(1);
        AtomicInteger errors = new AtomicInteger();

        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            pool.submit(() -> tx.execute(status -> {
                mockDrafts.lockForUpdate(sessionId);
                order.add("first-locked");
                firstHasLock.countDown();
                try {
                    Thread.sleep(300);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
                order.add("first-committing");
                return null;
            }));

            pool.submit(() -> {
                try {
                    firstHasLock.await(2, TimeUnit.SECONDS);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
                try {
                    tx.execute(status -> {
                        mockDrafts.lockForUpdate(sessionId);   // blocks until the first tx commits
                        order.add("second-locked");
                        return null;
                    });
                } catch (Exception e) {
                    errors.incrementAndGet();
                }
            });

            pool.shutdown();
            assertTrue(pool.awaitTermination(10, TimeUnit.SECONDS), "both transactions should finish well within 10s");
        } finally {
            pool.shutdownNow();
        }

        assertEquals(0, errors.get());
        assertEquals(List.of("first-locked", "first-committing", "second-locked"), order,
                "the second transaction's lock attempt must not proceed until the first releases its lock");
    }

    private long insertTestPlayer(String sleeperId) {
        testPlayerSleeperIds.add(sleeperId);
        return jdbc.queryForObject(
                "insert into player (sport, sleeper_id, name, positions) values ('nfl', ?, ?, '{RB}') returning id",
                Long.class, sleeperId, "IT Player " + sleeperId);
    }
}
