package com.ballknowers.draftsim.store;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.time.Instant;
import java.time.temporal.ChronoUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * V29's three tables against real Postgres (specs/020-ai-weekly-recap). SKIPS when the local
 * Postgres is unreachable, so the caller must read the skip count.
 */
@SpringBootTest
class LeagueRecapRepositoryIT {

    private static final String JDBC_URL = "jdbc:postgresql://localhost:5433/draftsim";
    static final String LEAGUE = "it-recap-league";
    static final String OTHER = "it-recap-other";

    @BeforeAll
    static void requiresLocalPostgres() {
        boolean reachable;
        try (Connection c = DriverManager.getConnection(JDBC_URL, "draftsim", "draftsim")) {
            reachable = true;
        } catch (SQLException e) {
            reachable = false;
        }
        Assumptions.assumeTrue(reachable,
                "no local Postgres reachable at " + JDBC_URL + " -- skipping recap repository IT");
    }

    @Autowired private JdbcTemplate jdbc;
    @Autowired private LeagueFeatureRepository features;
    @Autowired private LeagueRecapRepository recaps;

    private long leagueId;
    private long otherId;

    @BeforeEach
    void setUp() {
        tearDown();
        leagueId = newLeague(LEAGUE);
        otherId = newLeague(OTHER);
    }

    @AfterEach
    void tearDown() {
        jdbc.update("delete from league where sleeper_id in (?, ?)", LEAGUE, OTHER);
    }

    private long newLeague(String sleeperId) {
        return jdbc.queryForObject(
                "insert into league (sport, season, sleeper_id, name, total_rosters) values ('nfl', 2026, ?, 'Recap IT', 10) returning id",
                Long.class, sleeperId);
    }

    @Test
    void grantHasRevokeRoundTripAndGrantIsIdempotent() {
        assertFalse(features.has(leagueId, "RECAP"));
        features.grant(leagueId, "RECAP", "first");
        features.grant(leagueId, "RECAP", "second");
        assertTrue(features.has(leagueId, "RECAP"));
        assertFalse(features.has(otherId, "RECAP"));
        assertEquals("first", jdbc.queryForObject(
                "select note from league_feature where league_id = ?", String.class, leagueId));
        features.revoke(leagueId, "RECAP");
        features.revoke(leagueId, "RECAP");
        assertFalse(features.has(leagueId, "RECAP"));
    }

    @Test
    void aFailureNeverTouchesTheReadyBodyAndAReadyBumpsTheRevision() {
        Instant t0 = Instant.parse("2026-10-06T12:00:00Z");
        String sections = "[{\"title\":\"A\",\"body\":\"b\",\"cites\":[\"/matchups/0\"]}]";
        recaps.writeReady(leagueId, 3, "k1", "n1", "{\"week\":3}", "Headline", sections,
                "claude-haiku-4-5", "pv1", 1200, 300, null, "end_turn", t0);

        var r1 = recaps.find(leagueId, 3).orElseThrow();
        assertEquals(1, r1.revision());
        assertNull(r1.revisionReason());
        assertEquals("READY", r1.attemptStatus());
        assertEquals("k1", r1.attemptKey());
        assertEquals(t0, r1.generatedAt());

        Instant t1 = t0.plus(1, ChronoUnit.HOURS);
        String detail = "{\"unmatched\":[\"3rd\"],\"badCites\":[]}";
        recaps.writeFailure(leagueId, 3, "k2", "UNGROUNDED", detail, "end_turn", null, t1);

        var r2 = recaps.find(leagueId, 3).orElseThrow();
        assertEquals("Headline", r2.headline());
        assertEquals("k1", r2.readyKey());
        assertEquals("n1", r2.readyNumbersHash());
        assertEquals("{\"week\":3}", r2.readyInputJson());
        assertEquals("claude-haiku-4-5", r2.model());
        assertEquals(1200, r2.inputTokens());
        assertEquals(t0, r2.generatedAt());
        assertEquals(1, r2.revision());
        assertEquals("FAILED", r2.attemptStatus());
        assertEquals("k2", r2.attemptKey());
        assertEquals("UNGROUNDED", r2.failureReason());
        assertNull(r2.retryAfter());
        assertEquals(t1, r2.attemptedAt());
        // jsonb comes back normalised, so compare it as JSON rather than as text.
        assertEquals(json(detail), json(r2.failureDetailJson()));
        assertEquals(json(sections), json(r2.sectionsJson()));

        Instant retry = t1.plus(15, ChronoUnit.MINUTES);
        recaps.writeFailure(leagueId, 3, "k2", "API_ERROR", null, null, retry, t1);
        var r3 = recaps.find(leagueId, 3).orElseThrow();
        assertEquals(retry, r3.retryAfter());
        assertNull(r3.failureDetailJson());

        recaps.writeReady(leagueId, 3, "k3", "n2", "{\"week\":3,\"x\":1}", "Headline 2", sections,
                "claude-haiku-4-5", "pv1", 1300, 310, "NUMBERS_CHANGED", "end_turn", t1.plusSeconds(60));
        var r4 = recaps.find(leagueId, 3).orElseThrow();
        assertEquals(2, r4.revision());
        assertEquals("NUMBERS_CHANGED", r4.revisionReason());
        assertEquals("Headline 2", r4.headline());
        assertEquals("READY", r4.attemptStatus());
        assertNull(r4.failureReason());
        assertNull(r4.retryAfter());
    }

    @Test
    void clearAttemptKeepsTheReadyBodyAndWorksOnAFailureOnlyRow() {
        Instant t = Instant.parse("2026-10-06T12:00:00Z");
        recaps.writeFailure(leagueId, 4, "k", "REFUSED", null, "refusal", null, t);
        assertEquals(0, recaps.find(leagueId, 4).orElseThrow().revision());
        assertNull(recaps.find(leagueId, 4).orElseThrow().headline());
        recaps.clearAttempt(leagueId, 4);
        assertNull(recaps.find(leagueId, 4).orElseThrow().attemptStatus());

        recaps.writeReady(leagueId, 5, "k", "n", "{}", "H", "[]", "m", "pv", 1, 1, null, "end_turn", t);
        recaps.clearAttempt(leagueId, 5);
        var r = recaps.find(leagueId, 5).orElseThrow();
        assertEquals("H", r.headline());
        assertNull(r.attemptStatus());
        assertTrue(recaps.find(leagueId, 99).isEmpty());
    }

    @Test
    void rerollClearsTheReadyBodyAndTheAttemptButKeepsTheRevision() {
        Instant t = Instant.parse("2026-10-06T12:00:00Z");
        recaps.writeReady(leagueId, 6, "k", "n", "{}", "H", "[]", "m", "pv", 1, 1, null, "end_turn", t);
        recaps.writeReady(leagueId, 6, "k2", "n2", "{}", "H2", "[]", "m", "pv", 1, 1, "NUMBERS_CHANGED", "end_turn", t);
        recaps.writeFailure(leagueId, 6, "k3", "REFUSED", null, "refusal", null, t);
        recaps.reroll(leagueId, 6);
        var r = recaps.find(leagueId, 6).orElseThrow();
        assertNull(r.readyKey());
        assertNull(r.headline());
        assertNull(r.sectionsJson());
        assertNull(r.model());
        assertNull(r.attemptKey());
        assertNull(r.attemptStatus());
        assertNull(r.revisionReason());
        assertEquals(2, r.revision());

        recaps.writeReady(leagueId, 6, "k4", "n4", "{}", "H4", "[]", "m", "pv", 1, 1, null, "end_turn", t);
        assertEquals(3, recaps.find(leagueId, 6).orElseThrow().revision());
        recaps.reroll(leagueId, 777); // no such row: harmless
    }

    @Test
    void callsAreCountedPerLeagueAndGloballyFromAUtcDayBoundary() {
        Instant day = Instant.parse("2026-10-06T00:00:00Z");
        long before = recaps.callsSinceGlobal(day);
        recaps.logCall(leagueId, day.minusSeconds(1));      // yesterday: not counted
        recaps.logCall(leagueId, day);                      // exactly the boundary: counted
        recaps.logCall(leagueId, day.plusSeconds(3600));
        recaps.logCall(otherId, day.plusSeconds(7200));
        assertEquals(2, recaps.callsSince(leagueId, day));
        assertEquals(1, recaps.callsSince(otherId, day));
        assertEquals(before + 3, recaps.callsSinceGlobal(day));
    }

    private static JsonNode json(String s) {
        try {
            return new ObjectMapper().readTree(s);
        } catch (Exception e) {
            throw new AssertionError(e);
        }
    }
}
