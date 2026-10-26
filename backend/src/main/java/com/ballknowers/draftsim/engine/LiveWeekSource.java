package com.ballknowers.draftsim.engine;

import com.ballknowers.draftsim.config.LockInProperties;
import com.ballknowers.draftsim.store.LeagueRepository.LeagueRow;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestClient;

import java.net.http.HttpClient;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * The week being played right now, read live from Sleeper (spec 045 R2, plan A4/A6).
 *
 * <p>Sleeper does not let the current week be stored until it ends ({@code last_scored_leg} is absent
 * through opening week -- measured 2026-10-23), so Matchups cannot read it from {@code roster_week_points}.
 * This reads {@code /league/{id}} for the live {@code leg}, then {@code /league/{id}/matchups/{leg}} for every
 * roster's points and pairing. <b>Nothing is stored</b>: that would bypass spec 009's finality rule, which
 * already shipped a partial-forever bug once.
 *
 * <p><b>The live week is Sleeper's {@code leg} at read time</b>, not the stored one, so a rollover between
 * refreshes does not label last week's final as in progress.
 *
 * <p><b>Bounded (A4, amended after code review S1).</b> Both calls run on a virtual thread under
 * {@code orTimeout}, and through this class's own {@code RestClient} with a connect and a read timeout, so a
 * hung socket ends by itself ({@code orTimeout} alone only fails the future; the thread kept blocking).
 * {@code SleeperClient} is not changed: its ingest callers rely on today's behaviour. A failure or a
 * timeout is {@link Status#FAILED}, never an exception and never a silent fall back to last season.
 *
 * <p><b>Single-flight (S2).</b> Concurrent misses for one league id share one in-flight read.
 *
 * <p><b>{@link #peek} (S4)</b> returns the cache only and refreshes in the background, for callers whose
 * answer does not depend on the live read.
 *
 * <p><b>Empty (not FAILED)</b> for: an ESPN league, a complete league, Sleeper {@code leg < 1}, a Sleeper
 * 404 and a null league body (A6) -- "there is no live week", which is a different claim from "I could not
 * find out". A 404 was measured for league 1414306784801239040.
 *
 * <p><b>Cached by time</b> per Sleeper league id, like {@code LockInService.liveCache}: {@code liveTtlSeconds}
 * for an answer (including empty), the shorter {@code liveFailureTtlSeconds} for a failure, so an outage is
 * not one Sleeper call per page view. Those two numbers are 044's, hand-set and arbitrary, reused here.
 */
@Component
public class LiveWeekSource {

    private static final Logger log = LoggerFactory.getLogger(LiveWeekSource.class);

    public enum Status { OK, FAILED }

    /** {@code matchupId} is null for an unpaired roster. {@code points} is Sleeper's {@code points}. */
    public record Row(int rosterId, Integer matchupId, double points) {}

    /**
     * @param week the live week, or 0 when a {@code FAILED} read never learned it (the league call itself
     *             failed or timed out)
     * @param rows empty when {@code FAILED}
     * @param at   read time
     */
    public record Live(int week, Status status, List<Row> rows, Instant at) {}

    private record Cached(Instant at, Optional<Live> result) {}

    /**
     * The two Sleeper reads this class makes, as a seam so tests need no network. The production
     * implementation is {@link #sleeperReader}: this class's own {@code RestClient}, with timeouts (S1).
     */
    interface Reader {
        Map<String, Object> league(String leagueId);

        List<Map<String, Object>> matchups(String leagueId, int leg);
    }

    private final Reader sleeper;
    private final LockInProperties props;
    private final Clock clock;
    private final long timeoutMs;
    private final ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();

    private final Map<String, Cached> cache = Collections.synchronizedMap(
            new LinkedHashMap<>(16, 0.75f, true) {
                @Override
                protected boolean removeEldestEntry(Map.Entry<String, Cached> eldest) {
                    return size() > 64;
                }
            });

    /** Reads in progress, per Sleeper league id: concurrent misses share one read (S2, single-flight). */
    private final ConcurrentHashMap<String, CompletableFuture<Optional<Live>>> inFlight = new ConcurrentHashMap<>();

    /**
     * @param baseUrl   {@code sleeper.base-url}, the same value {@code SleeperClient} is built from
     * @param timeoutMs {@code draftsim.live-week.timeout-ms}: how long both Sleeper calls together may take.
     *                  Hand-set and arbitrary (2500), not measured against Sleeper's latency tail. The same
     *                  number is also this class's HTTP connect timeout and its HTTP read timeout (S1) --
     *                  equally hand-set and arbitrary.
     */
    @Autowired
    public LiveWeekSource(@Value("${sleeper.base-url}") String baseUrl, LockInProperties props, Clock clock,
                          @Value("${draftsim.live-week.timeout-ms:2500}") long timeoutMs) {
        this(sleeperReader(baseUrl, timeoutMs), props, clock, timeoutMs);
    }

    /** For tests: supply the two reads directly. */
    LiveWeekSource(Reader sleeper, LockInProperties props, Clock clock, long timeoutMs) {
        this.sleeper = sleeper;
        this.props = props;
        this.clock = clock;
        this.timeoutMs = timeoutMs;
    }

    /**
     * This class's own {@code RestClient}. {@code SleeperClient} is deliberately untouched: its ingest callers
     * rely on today's no-timeout behaviour. The request factory is the JDK one (what a bare {@code RestClient}
     * resolves to on this classpath), now with a connect and a read timeout.
     */
    @SuppressWarnings({"unchecked", "rawtypes"})
    static Reader sleeperReader(String baseUrl, long timeoutMs) {
        Duration t = Duration.ofMillis(timeoutMs);
        JdkClientHttpRequestFactory factory = new JdkClientHttpRequestFactory(
                HttpClient.newBuilder().connectTimeout(t).build());
        factory.setReadTimeout(t);
        RestClient http = RestClient.builder().baseUrl(baseUrl).requestFactory(factory).build();
        return new Reader() {
            @Override
            public Map<String, Object> league(String leagueId) {
                return http.get().uri("/league/{id}", leagueId).retrieve().body(Map.class);
            }

            @Override
            public List<Map<String, Object>> matchups(String leagueId, int leg) {
                return http.get().uri("/league/{id}/matchups/{leg}", leagueId, leg).retrieve().body(List.class);
            }
        };
    }

    @PreDestroy
    void shutdown() {
        executor.shutdownNow();
    }

    /**
     * Whether the live week has STARTED (spec 045 A11 / FR-017; shared with spec 048 so the rule has one
     * implementation): some roster has points above 0, or the caller finds stored player games for that
     * sport/season/week. The games read is lazy -- it only runs when no roster has points yet.
     */
    public static boolean hasStarted(Live live, java.util.function.BooleanSupplier hasPlayerGames) {
        return live.rows().stream().anyMatch(r -> r.points() > 0) || hasPlayerGames.getAsBoolean();
    }

    /** The cached answer if it is still inside its TTL (outer Optional empty: nothing fresh). */
    private Optional<Optional<Live>> fresh(String id, Instant now) {
        Cached hit = cache.get(id);
        if (hit == null) return Optional.empty();
        boolean failed = hit.result().map(l -> l.status() == Status.FAILED).orElse(false);
        int ttl = failed ? props.liveFailureTtlSeconds() : props.liveTtlSeconds();
        return now.isBefore(hit.at().plusSeconds(ttl)) ? Optional.of(hit.result()) : Optional.empty();
    }

    /** Blocks on Sleeper (bounded by the timeouts) when nothing fresh is cached. */
    public Optional<Live> current(LeagueRow league) {
        if (!league.isSleeper() || league.complete()) return Optional.empty();
        String id = league.sleeperId();
        Instant now = clock.instant();
        Optional<Optional<Live>> hit = fresh(id, now);
        if (hit.isPresent()) return hit.get();
        try {
            return flight(id, now).get();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return Optional.of(failed(now));
        } catch (ExecutionException e) {
            // flight() never completes exceptionally; kept so a bug here cannot throw into a page request
            return Optional.of(failed(now));
        }
    }

    /**
     * The cached answer, never waiting on Sleeper (S4). With nothing fresh cached (none, or expired) it starts
     * a background refresh, single-flighted like {@link #current}, and returns empty. So a caller whose answer
     * does not depend on the live read is never held up by it; the cost is that the first view after a cold
     * start or an expiry sees "no live week" and the next one sees the value.
     */
    public Optional<Live> peek(LeagueRow league) {
        if (!league.isSleeper() || league.complete()) return Optional.empty();
        String id = league.sleeperId();
        Instant now = clock.instant();
        Optional<Optional<Live>> hit = fresh(id, now);
        if (hit.isPresent()) return hit.get();
        flight(id, now);
        return Optional.empty();
    }

    /** The one in-flight read for {@code id}, started if there is none. Never completes exceptionally. */
    private CompletableFuture<Optional<Live>> flight(String id, Instant now) {
        boolean[] created = {false};
        CompletableFuture<Optional<Live>> f = inFlight.computeIfAbsent(id, k -> {
            created[0] = true;
            return startRead(k, now);
        });
        // registered outside computeIfAbsent: an already-complete future would otherwise run the removal
        // inside the mapping function
        if (created[0]) f.whenComplete((r, e) -> inFlight.remove(id, f));
        return f;
    }

    private CompletableFuture<Optional<Live>> startRead(String id, Instant now) {
        return CompletableFuture
                .supplyAsync(() -> fetch(id, now), executor)
                .orTimeout(timeoutMs, TimeUnit.MILLISECONDS)
                .handle((result, error) -> {
                    Optional<Live> out;
                    if (error == null) {
                        out = result;
                    } else {
                        Throwable cause = error instanceof CompletionException && error.getCause() != null
                                ? error.getCause() : error;
                        log.warn("live week read failed for {}: {}", id,
                                cause instanceof TimeoutException ? "timed out after " + timeoutMs + " ms"
                                        : cause.getClass().getSimpleName());
                        out = Optional.of(failed(now));
                    }
                    cache.put(id, new Cached(now, out));
                    return out;
                });
    }

    private static Live failed(Instant now) {
        return new Live(0, Status.FAILED, List.of(), now);
    }

    @SuppressWarnings("unchecked")
    private Optional<Live> fetch(String id, Instant now) {
        try {
            Map<String, Object> league = sleeper.league(id);
            if (league == null) return Optional.empty();
            if ("complete".equals(league.get("status"))) return Optional.empty();
            Object settings = league.get("settings");
            int leg = settings instanceof Map<?, ?> s && ((Map<String, Object>) s).get("leg") instanceof Number n
                    ? n.intValue() : 0;
            if (leg < 1) return Optional.empty();
            List<Map<String, Object>> raw = sleeper.matchups(id, leg);
            List<Row> rows = new ArrayList<>();
            if (raw != null) {
                for (Map<String, Object> m : raw) {
                    if (m.get("roster_id") instanceof Number r) {
                        Integer matchupId = m.get("matchup_id") instanceof Number mid ? mid.intValue() : null;
                        double pts = m.get("points") instanceof Number p ? p.doubleValue() : 0.0;
                        rows.add(new Row(r.intValue(), matchupId, pts));
                    }
                }
            }
            return Optional.of(new Live(leg, Status.OK, List.copyOf(rows), now));
        } catch (HttpClientErrorException.NotFound e) {
            return Optional.empty();
        }
    }
}
