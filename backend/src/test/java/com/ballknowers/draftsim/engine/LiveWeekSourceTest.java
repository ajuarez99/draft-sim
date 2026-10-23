package com.ballknowers.draftsim.engine;

import com.ballknowers.draftsim.config.LockInProperties;
import com.ballknowers.draftsim.domain.Platform;
import com.ballknowers.draftsim.domain.Sport;
import com.ballknowers.draftsim.engine.LiveWeekSource.Live;
import com.ballknowers.draftsim.store.LeagueRepository.LeagueRow;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.ResourceAccessException;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Spec 045 T015: {@link LiveWeekSource} -- the bounded, cached live read of Sleeper's current week. Time is a
 * mutable {@link Clock} so TTL expiry is tested by advancing it, not by sleeping.
 */
class LiveWeekSourceTest {

    private static final LockInProperties PROPS =
            new LockInProperties(10, 15, 5, List.of(0.0, 0.1), 3, 4, 60, 15);

    /** A clock the test moves. */
    private static final class MutableClock extends Clock {
        final AtomicReference<Instant> now = new AtomicReference<>(Instant.parse("2026-10-23T12:00:00Z"));
        @Override public java.time.ZoneId getZone() { return ZoneOffset.UTC; }
        @Override public Clock withZone(java.time.ZoneId zone) { return this; }
        @Override public Instant instant() { return now.get(); }
        void advance(long seconds) { now.set(now.get().plusSeconds(seconds)); }
    }

    private final LiveWeekSource.Reader sleeper = mock(LiveWeekSource.Reader.class);
    private final MutableClock clock = new MutableClock();
    private final LiveWeekSource source = new LiveWeekSource(sleeper, PROPS, clock, 400);

    private static LeagueRow row(String status, Platform platform) {
        return new LeagueRow(210L, Sport.NBA, "L26", "n", 2026, 12, List.of(), 0, null, status, platform,
                platform == Platform.SLEEPER ? null : "999", List.of(), List.of());
    }

    private static Map<String, Object> league(int leg) {
        return Map.of("status", "in_season", "settings", Map.of("leg", leg));
    }

    private static Map<String, Object> m(int roster, Integer matchup, double pts) {
        Map<String, Object> o = new java.util.HashMap<>();
        o.put("roster_id", roster);
        o.put("matchup_id", matchup);
        o.put("points", pts);
        return o;
    }

    private static HttpClientErrorException notFound() {
        return HttpClientErrorException.create(HttpStatus.NOT_FOUND, "Not Found", HttpHeaders.EMPTY, new byte[0], null);
    }

    @Test
    void readsTheLiveLegAndEveryRostersPoints() {
        when(sleeper.league("L26")).thenReturn(league(1));
        when(sleeper.matchups("L26", 1)).thenReturn(List.of(m(1, 1, 201.5), m(5, 1, 192.5), m(3, null, 0)));

        Live live = source.current(row("in_season", Platform.SLEEPER)).orElseThrow();

        assertEquals(LiveWeekSource.Status.OK, live.status());
        assertEquals(1, live.week());
        assertEquals(3, live.rows().size());
        assertEquals(new LiveWeekSource.Row(1, 1, 201.5), live.rows().getFirst());
        assertNull(live.rows().get(2).matchupId(), "an unpaired roster keeps a null matchup id");
    }

    @Test
    void aSleeper404IsNoLiveWeek_notAFailure() {
        when(sleeper.league("L26")).thenThrow(notFound());
        assertTrue(source.current(row("in_season", Platform.SLEEPER)).isEmpty());
    }

    @Test
    void aNullLeagueBodyIsNoLiveWeek() {
        when(sleeper.league("L26")).thenReturn(null);
        assertTrue(source.current(row("in_season", Platform.SLEEPER)).isEmpty());
    }

    @Test
    void aCompleteLeagueAndAnEspnLeagueNeverCallSleeper() {
        assertTrue(source.current(row("complete", Platform.SLEEPER)).isEmpty());
        assertTrue(source.current(row("in_season", Platform.ESPN)).isEmpty());
        verify(sleeper, never()).league(anyString());
    }

    @Test
    void legZeroOrAbsentIsNoLiveWeek_andMatchupsAreNotRead() {
        when(sleeper.league("L26")).thenReturn(league(0));
        assertTrue(source.current(row("in_season", Platform.SLEEPER)).isEmpty());
        verify(sleeper, never()).matchups(anyString(), anyInt());
    }

    @Test
    void anExceptionIsFailed_neverThrown() {
        when(sleeper.league("L26")).thenThrow(new ResourceAccessException("connection reset"));
        Live live = source.current(row("in_season", Platform.SLEEPER)).orElseThrow();
        assertEquals(LiveWeekSource.Status.FAILED, live.status());
        assertTrue(live.rows().isEmpty());
    }

    @Test
    void aSlowSleeperIsFailedWithinRoughlyTheTimeout() {
        when(sleeper.league("L26")).thenAnswer(i -> {
            Thread.sleep(3000);
            return LiveWeekSourceTest.league(1);
        });
        long t0 = System.nanoTime();
        Live live = source.current(row("in_season", Platform.SLEEPER)).orElseThrow();
        long ms = Duration.ofNanos(System.nanoTime() - t0).toMillis();

        assertEquals(LiveWeekSource.Status.FAILED, live.status());
        assertTrue(ms < 400 + 500, "timeout 400 ms + 500 ms slack, took " + ms);
    }

    @Test
    void anAnswerIsCachedForTheLiveTtlAndReadAgainAfterIt() {
        when(sleeper.league("L26")).thenReturn(league(1));
        when(sleeper.matchups("L26", 1)).thenReturn(List.of(m(1, 1, 10)));
        LeagueRow r = row("in_season", Platform.SLEEPER);

        source.current(r);
        clock.advance(59);
        source.current(r);
        verify(sleeper, times(1)).league("L26");

        clock.advance(2);   // 61 s
        source.current(r);
        verify(sleeper, times(2)).league("L26");
    }

    @Test
    void anEmptyAnswerIsCachedForTheLiveTtlToo() {
        when(sleeper.league("L26")).thenThrow(notFound());
        LeagueRow r = row("in_season", Platform.SLEEPER);

        source.current(r);
        clock.advance(30);
        source.current(r);
        verify(sleeper, times(1)).league("L26");
    }

    @Test
    void aFailureIsCachedOnlyForTheShorterFailureTtl() {
        when(sleeper.league("L26")).thenThrow(new ResourceAccessException("down"));
        LeagueRow r = row("in_season", Platform.SLEEPER);

        source.current(r);
        clock.advance(10);                    // inside 15 s: served from the negative cache
        source.current(r);
        verify(sleeper, times(1)).league("L26");

        clock.advance(6);                     // 16 s: past the failure TTL, well inside the 60 s one
        source.current(r);
        verify(sleeper, times(2)).league("L26");
    }

    @Test
    void theCacheIsPerLeague() {
        when(sleeper.league("L26")).thenReturn(league(1));
        when(sleeper.league("OTHER")).thenReturn(league(2));
        when(sleeper.matchups(anyString(), anyInt())).thenReturn(List.of(m(1, 1, 5)));
        LeagueRow other = new LeagueRow(211L, Sport.NBA, "OTHER", "n", 2026, 12, List.of(), 0, null, "in_season");

        assertEquals(1, source.current(row("in_season", Platform.SLEEPER)).orElseThrow().week());
        assertEquals(2, source.current(other).orElseThrow().week());
        assertEquals(Optional.empty(), source.current(row("complete", Platform.SLEEPER)));
    }

    // ---- S1: the real client has its own timeouts, so a hung socket ends by itself ----------------------

    @Test
    void s1_aHungSocketEndsOnItsOwnWithinTheReadTimeout() throws Exception {
        try (java.net.ServerSocket server = new java.net.ServerSocket(0, 1, java.net.InetAddress.getLoopbackAddress())) {
            // accepts the connection (the backlog does) and never answers
            LiveWeekSource.Reader real = LiveWeekSource.sleeperReader("http://127.0.0.1:" + server.getLocalPort(), 300);
            long t0 = System.nanoTime();
            assertThrows(ResourceAccessException.class, () -> real.league("L26"));
            long ms = Duration.ofNanos(System.nanoTime() - t0).toMillis();
            assertTrue(ms < 2000, "a 300 ms read timeout ended the call itself, took " + ms);
        }
    }

    // ---- S2: single-flight ------------------------------------------------------------------------------

    @Test
    void s2_concurrentColdCallsShareOneRead() throws Exception {
        AtomicInteger calls = new AtomicInteger();
        CountDownLatch release = new CountDownLatch(1);
        LiveWeekSource.Reader gated = new LiveWeekSource.Reader() {
            public Map<String, Object> league(String id) {
                calls.incrementAndGet();
                try { release.await(); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
                return LiveWeekSourceTest.league(1);
            }
            public List<Map<String, Object>> matchups(String id, int leg) { return List.of(m(1, 1, 10)); }
        };
        LiveWeekSource src = new LiveWeekSource(gated, PROPS, clock, 5000);
        LeagueRow r = row("in_season", Platform.SLEEPER);
        int n = 12;
        java.util.concurrent.ExecutorService pool = java.util.concurrent.Executors.newFixedThreadPool(n);
        List<java.util.concurrent.Future<Optional<Live>>> out = new java.util.ArrayList<>();
        for (int i = 0; i < n; i++) out.add(pool.submit(() -> src.current(r)));
        Thread.sleep(300);          // let every caller reach the in-flight read
        release.countDown();
        for (var f : out) assertEquals(1, f.get().orElseThrow().week());
        pool.shutdown();

        assertEquals(1, calls.get(), "12 concurrent cold calls made exactly one league() call");
    }

    // ---- S4: peek never waits -----------------------------------------------------------------------------

    @Test
    void s4_peekWithNothingCachedReturnsEmptyAtOnce_andRefreshesInTheBackground() throws Exception {
        CountDownLatch release = new CountDownLatch(1);
        AtomicInteger calls = new AtomicInteger();
        LiveWeekSource.Reader gated = new LiveWeekSource.Reader() {
            public Map<String, Object> league(String id) {
                calls.incrementAndGet();
                try { release.await(); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
                return LiveWeekSourceTest.league(2);
            }
            public List<Map<String, Object>> matchups(String id, int leg) { return List.of(m(1, 1, 10)); }
        };
        LiveWeekSource src = new LiveWeekSource(gated, PROPS, clock, 5000);
        LeagueRow r = row("in_season", Platform.SLEEPER);

        long t0 = System.nanoTime();
        assertTrue(src.peek(r).isEmpty());
        assertTrue(src.peek(r).isEmpty(), "still reading: a second peek does not start a second read");
        assertTrue(Duration.ofNanos(System.nanoTime() - t0).toMillis() < 500, "peek did not wait on the hung read");

        release.countDown();
        Optional<Live> later = Optional.empty();
        for (int i = 0; i < 50 && later.isEmpty(); i++) {
            Thread.sleep(50);
            later = src.peek(r);
        }
        assertEquals(2, later.orElseThrow().week(), "the background refresh landed in the cache");
        assertEquals(1, calls.get());
    }

    @Test
    void s4_peekServesTheCacheWithoutCallingSleeper_andRefreshesWhenExpired() throws Exception {
        when(sleeper.league("L26")).thenReturn(league(1));
        when(sleeper.matchups("L26", 1)).thenReturn(List.of(m(1, 1, 10)));
        LeagueRow r = row("in_season", Platform.SLEEPER);
        source.current(r);
        verify(sleeper, times(1)).league("L26");

        assertEquals(1, source.peek(r).orElseThrow().week());
        verify(sleeper, times(1)).league("L26");

        clock.advance(61);          // past the live TTL
        assertTrue(source.peek(r).isEmpty(), "expired: empty now, refresh in the background");
        for (int i = 0; i < 50; i++) {
            try { verify(sleeper, times(2)).league("L26"); break; } catch (AssertionError e) { Thread.sleep(50); }
        }
        verify(sleeper, times(2)).league("L26");
    }
}
