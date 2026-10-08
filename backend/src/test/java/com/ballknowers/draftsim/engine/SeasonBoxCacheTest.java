package com.ballknowers.draftsim.engine;

import com.ballknowers.draftsim.domain.Sport;
import com.ballknowers.draftsim.store.PlayerGameRepository.SeasonGame;
import com.ballknowers.draftsim.store.PlayerGameRepository.SeasonToken;
import com.ballknowers.draftsim.store.PlayerGameRepository.TeamGame;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

/** Spec 022 T013, against a fake loader. */
class SeasonBoxCacheTest {

    private static final LocalDate D = LocalDate.parse("2025-11-01");
    private static final OffsetDateTime T0 = OffsetDateTime.parse("2025-11-02T00:00:00Z");

    /** A loader whose token and rows the test controls, and which counts row loads. */
    private static final class Fake implements SeasonBoxCache.Source {
        final AtomicReference<SeasonToken> token = new AtomicReference<>(new SeasonToken(2, T0));
        final AtomicInteger loads = new AtomicInteger();
        volatile Runnable betweenTokenAndRows = () -> {};
        volatile CountDownLatch gate = null;
        volatile CountDownLatch entered = null;

        @Override public SeasonToken token(Sport sport, int season) {
            return token.get();
        }

        @Override public List<SeasonGame> games(Sport sport, int season) {
            loads.incrementAndGet();
            if (entered != null) entered.countDown();
            if (gate != null) {
                try {
                    assertTrue(gate.await(10, TimeUnit.SECONDS));
                } catch (InterruptedException e) {
                    throw new IllegalStateException(e);
                }
            }
            betweenTokenAndRows.run();
            Map<String, Object> stats = new HashMap<>();
            stats.put("pts", 20);
            stats.put("sp", 1800);
            stats.put("ast", 0);
            stats.put("note", "text");        // non-numeric: not kept
            return List.of(new SeasonGame("p1", "g1", D, "BBB", stats, 3, true),
                    new SeasonGame("p1", "g2", D.plusDays(1), "BBB", Map.of("pts", 7.5, "sp", 600), 3, null));
        }

        @Override public List<TeamGame> teamGames(Sport sport, int season) {
            return List.of(new TeamGame("AAA", "g1", D, "BBB", Map.of("sp", 14400, "fga", 88)),
                    new TeamGame("BBB", "g1", D, "AAA", Map.of("sp", 14400, "fga", 80)));
        }
    }

    @Test
    void theSameTokenDoesNotReload() {
        Fake f = new Fake();
        SeasonBoxCache c = new SeasonBoxCache(f);
        SeasonBoxCache.Season a = c.get(Sport.NBA, 2025);
        SeasonBoxCache.Season b = c.get(Sport.NBA, 2025);
        assertSame(a, b);
        assertEquals(1, f.loads.get());
    }

    @Test
    void aChangedTokenReloads() {
        Fake f = new Fake();
        SeasonBoxCache c = new SeasonBoxCache(f);
        SeasonBoxCache.Season a = c.get(Sport.NBA, 2025);
        f.token.set(new SeasonToken(3, T0.plusHours(1)));
        SeasonBoxCache.Season b = c.get(Sport.NBA, 2025);
        assertNotSame(a, b);
        assertEquals(2, f.loads.get());
        assertEquals(new SeasonToken(3, T0.plusHours(1)), b.token());
    }

    @Test
    void seasonsAndSportsAreSeparateKeys() {
        Fake f = new Fake();
        SeasonBoxCache c = new SeasonBoxCache(f);
        c.get(Sport.NBA, 2025);
        c.get(Sport.NBA, 2024);
        c.get(Sport.NFL, 2025);
        assertEquals(3, f.loads.get());
    }

    @Test
    void theTokenIsReadBeforeTheRowsSoAWriteInBetweenStillReloadsNextTime() {
        Fake f = new Fake();
        SeasonToken before = f.token.get();
        // a write lands after the token read and before the rows are read
        f.betweenTokenAndRows = () -> f.token.set(new SeasonToken(3, T0.plusMinutes(5)));
        SeasonBoxCache c = new SeasonBoxCache(f);
        SeasonBoxCache.Season first = c.get(Sport.NBA, 2025);
        assertEquals(before, first.token(), "the stored token is the one read BEFORE the rows");
        f.betweenTokenAndRows = () -> {};
        c.get(Sport.NBA, 2025);
        assertEquals(2, f.loads.get(), "the next get sees a newer token and reloads");
    }

    @Test
    void invalidateForcesAReload() {
        Fake f = new Fake();
        SeasonBoxCache c = new SeasonBoxCache(f);
        c.get(Sport.NBA, 2025);
        c.invalidate(Sport.NBA, 2025);
        c.get(Sport.NBA, 2025);
        assertEquals(2, f.loads.get());
        c.get(Sport.NBA, 2025);
        assertEquals(2, f.loads.get(), "and only once");
    }

    @Test
    void whileRefreshingAChangedTokenServesTheCurrentEntry() {
        Fake f = new Fake();
        SeasonBoxCache c = new SeasonBoxCache(f);
        SeasonBoxCache.Season a = c.get(Sport.NBA, 2025);
        c.markRefreshing(Sport.NBA, 2025, true);
        f.token.set(new SeasonToken(9, T0.plusDays(1)));
        assertSame(a, c.get(Sport.NBA, 2025));
        assertSame(a, c.get(Sport.NBA, 2025));
        assertEquals(1, f.loads.get());

        c.markRefreshing(Sport.NBA, 2025, false);
        assertNotSame(a, c.get(Sport.NBA, 2025), "once the refresh ends the token is honoured again");
        assertEquals(2, f.loads.get());
    }

    @Test
    void whileRefreshingWithNoEntryItStillLoads() {
        Fake f = new Fake();
        SeasonBoxCache c = new SeasonBoxCache(f);
        c.markRefreshing(Sport.NBA, 2025, true);
        assertNotNull(c.get(Sport.NBA, 2025));
        assertEquals(1, f.loads.get());
    }

    @Test
    void invalidateWinsOverRefreshing() {
        Fake f = new Fake();
        SeasonBoxCache c = new SeasonBoxCache(f);
        c.get(Sport.NBA, 2025);
        c.markRefreshing(Sport.NBA, 2025, true);
        c.invalidate(Sport.NBA, 2025);
        c.get(Sport.NBA, 2025);
        assertEquals(2, f.loads.get());
    }

    @Test
    void overlappingRefreshMarksAreCountedSoOneEndingKeepsTheSeasonRefreshing() {
        Fake f = new Fake();
        SeasonBoxCache c = new SeasonBoxCache(f);
        SeasonBoxCache.Season a = c.get(Sport.NBA, 2025);
        c.markRefreshing(Sport.NBA, 2025, true);
        c.markRefreshing(Sport.NBA, 2025, true);
        c.markRefreshing(Sport.NBA, 2025, false);
        f.token.set(new SeasonToken(9, T0.plusDays(1)));
        assertSame(a, c.get(Sport.NBA, 2025), "one mark is still outstanding");
        assertEquals(1, f.loads.get());
        c.markRefreshing(Sport.NBA, 2025, false);
        assertNotSame(a, c.get(Sport.NBA, 2025), "both ended: the token is honoured");
        assertEquals(2, f.loads.get());
    }

    @Test
    void anExtraClearNeverGoesBelowZero() {
        Fake f = new Fake();
        SeasonBoxCache c = new SeasonBoxCache(f);
        c.markRefreshing(Sport.NBA, 2025, false);       // nothing to clear
        c.markRefreshing(Sport.NBA, 2025, true);
        SeasonBoxCache.Season a = c.get(Sport.NBA, 2025);
        f.token.set(new SeasonToken(9, T0.plusDays(1)));
        assertSame(a, c.get(Sport.NBA, 2025), "the later mark counts, the stray clear did not go negative");
    }

    @Test
    void aGetThatJoinsALoadStartedBeforeAnInvalidateReloadsOnceInsteadOfReturningItsRows() throws Exception {
        Fake f = new Fake();
        f.gate = new CountDownLatch(1);
        f.entered = new CountDownLatch(1);
        SeasonBoxCache c = new SeasonBoxCache(f);
        try (ExecutorService ex = Executors.newVirtualThreadPerTaskExecutor()) {
            Future<SeasonBoxCache.Season> first = ex.submit(() -> c.get(Sport.NBA, 2025));
            assertTrue(f.entered.await(10, TimeUnit.SECONDS), "the first load is running");
            c.invalidate(Sport.NBA, 2025);
            Future<SeasonBoxCache.Season> second = ex.submit(() -> c.get(Sport.NBA, 2025));
            Thread.sleep(150);                      // the second joins the running (pre-invalidate) load
            f.gate.countDown();
            SeasonBoxCache.Season preInvalidate = first.get(10, TimeUnit.SECONDS);
            SeasonBoxCache.Season fresh = second.get(10, TimeUnit.SECONDS);
            assertNotSame(preInvalidate, fresh, "the joiner did not take the pre-invalidate rows");
        }
        assertEquals(2, f.loads.get(), "one load for the first caller, one retry for the joiner");
        c.get(Sport.NBA, 2025);
        assertEquals(2, f.loads.get(), "the retry's entry is current");
    }

    @Test
    void twoConcurrentColdGetsLoadOnce() throws Exception {
        Fake f = new Fake();
        f.gate = new CountDownLatch(1);
        f.entered = new CountDownLatch(1);
        SeasonBoxCache c = new SeasonBoxCache(f);
        try (ExecutorService ex = Executors.newVirtualThreadPerTaskExecutor()) {
            Future<SeasonBoxCache.Season> first = ex.submit(() -> c.get(Sport.NBA, 2025));
            assertTrue(f.entered.await(10, TimeUnit.SECONDS), "the first get is inside the loader");
            Future<SeasonBoxCache.Season> second = ex.submit(() -> c.get(Sport.NBA, 2025));
            Thread.sleep(150);                      // let the second reach the single flight and wait
            assertFalse(second.isDone());
            f.gate.countDown();
            assertSame(first.get(10, TimeUnit.SECONDS), second.get(10, TimeUnit.SECONDS));
        }
        assertEquals(1, f.loads.get());
    }

    @Test
    void manyConcurrentColdGetsLoadOnce() throws Exception {
        Fake f = new Fake();
        f.gate = new CountDownLatch(1);
        SeasonBoxCache c = new SeasonBoxCache(f);
        List<Future<SeasonBoxCache.Season>> all = new ArrayList<>();
        try (ExecutorService ex = Executors.newVirtualThreadPerTaskExecutor()) {
            for (int i = 0; i < 16; i++) all.add(ex.submit(() -> c.get(Sport.NBA, 2025)));
            Thread.sleep(150);
            f.gate.countDown();
            SeasonBoxCache.Season s = all.get(0).get(10, TimeUnit.SECONDS);
            for (Future<SeasonBoxCache.Season> x : all) assertSame(s, x.get(10, TimeUnit.SECONDS));
        }
        assertEquals(1, f.loads.get());
    }

    @Test
    void aFailedLoadReachesTheCallerAndDoesNotPoisonTheKey() {
        Fake f = new Fake();
        f.betweenTokenAndRows = () -> { throw new IllegalStateException("boom"); };
        SeasonBoxCache c = new SeasonBoxCache(f);
        assertThrows(IllegalStateException.class, () -> c.get(Sport.NBA, 2025));
        f.betweenTokenAndRows = () -> {};
        assertNotNull(c.get(Sport.NBA, 2025));
    }

    @Test
    void everyListAndMapInAnEntryIsUnmodifiable() {
        SeasonBoxCache.Season s = new SeasonBoxCache(new Fake()).get(Sport.NBA, 2025);
        assertThrows(UnsupportedOperationException.class, () -> s.games().clear());
        assertThrows(UnsupportedOperationException.class, () -> s.games().sort(null));
        assertThrows(UnsupportedOperationException.class, () -> s.teamGames().clear());
        assertThrows(UnsupportedOperationException.class, () -> s.games().get(0).stats().put("pts", 1.0));
        assertThrows(UnsupportedOperationException.class, () -> s.games().get(0).stats().remove("pts"));
        assertThrows(UnsupportedOperationException.class, () -> s.games().get(0).stats().clear());
        assertThrows(UnsupportedOperationException.class, () -> s.teamGames().get(0).stats().put("sp", 1.0));
        assertThrows(UnsupportedOperationException.class, () -> {
            var it = s.games().get(0).stats().entrySet().iterator();
            it.next();
            it.remove();
        });
        assertThrows(UnsupportedOperationException.class, () -> s.lines().byPlayer().clear());
        assertThrows(UnsupportedOperationException.class, () -> s.lines().byPlayer().get("p1").clear());
        assertThrows(UnsupportedOperationException.class, () -> s.lines().teamGames().clear());
        assertThrows(UnsupportedOperationException.class, () -> s.lines().teamCodes().clear());
    }

    @Test
    void theCompactViewReturnsEverySourceValueAsADoubleAndNullForAbsentKeys() {
        SeasonBoxCache.Season s = new SeasonBoxCache(new Fake()).get(Sport.NBA, 2025);
        Map<String, Object> st = s.games().get(0).stats();
        assertEquals(20.0, st.get("pts"));
        assertEquals(1800.0, st.get("sp"));
        assertEquals(0.0, st.get("ast"), "a stored zero is a real zero");
        assertInstanceOf(Double.class, st.get("pts"));
        assertNull(st.get("reb"), "an absent key is null, never 0");
        assertNull(st.get("note"), "a non-numeric value is not kept");
        assertNull(st.get(42));
        assertTrue(st.containsKey("ast"));
        assertFalse(st.containsKey("reb"));
        assertEquals(3, st.size());

        Map<String, Double> seen = new HashMap<>();
        for (Map.Entry<String, Object> e : st.entrySet()) seen.put(e.getKey(), (Double) e.getValue());
        assertEquals(Map.of("pts", 20.0, "sp", 1800.0, "ast", 0.0), seen);

        assertEquals(7.5, s.games().get(1).stats().get("pts"), "fractional values survive as doubles");
        assertEquals(88.0, s.teamGames().get(0).stats().get("fga"));
    }

    @Test
    void rawRowsAreAvailableAlongsideTheLines() {
        SeasonBoxCache.Season s = new SeasonBoxCache(new Fake()).get(Sport.NBA, 2025);
        assertEquals(2, s.games().size());
        assertEquals(2, s.teamGames().size());
        assertEquals(Boolean.TRUE, s.games().get(0).isAway());
        assertNull(s.games().get(1).isAway());
        assertEquals(3, s.games().get(0).week());
        // the lines are over the same rows: p1 has two lines, g2 has no team rows
        assertEquals(2, s.lines().byPlayer().get("p1").size());
        assertEquals("AAA", s.lines().byPlayer().get("p1").get(0).team());
        assertNull(s.lines().byPlayer().get("p1").get(1).teamRow());
    }
}
