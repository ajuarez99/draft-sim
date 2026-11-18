package com.ballknowers.draftsim.usage;

import com.ballknowers.draftsim.config.OwnerProperties;
import com.ballknowers.draftsim.historical.HistoricalDailyService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.LongAdder;

/**
 * Usage collection and the owner's report (spec 076).
 *
 * <p>{@link #collect} never throws and returns nothing: the caller answers 204 whatever happened. It reads no
 * identity and logs no body, address or user agent. Order of checks (contracts/usage-api.md): crawler user agent,
 * per-address limit, parse, vocabulary, league exists (league-view), global write ceiling, upsert. One deliberate
 * difference from that text: for a league-view the ceiling slot is taken BEFORE the league lookup, so the one
 * database read a junk league id costs is bounded by the same ceiling as a write.
 *
 * <p>A send from a known site that fails the vocabulary or names an unknown league adds 1 to an IN-MEMORY
 * {@code rejected} counter for that site; the counters are written at most once a minute, from a send that was
 * accepted or from {@link #report}. So a refused send never takes a database connection. A send with an unknown
 * site, an unparsable body, a crawler, a limited send or one over the ceiling is counted nowhere. The in-memory
 * counters are lost on a restart (best effort, and the page says so).
 */
@Service
public class UsageService {

    private static final Logger log = LoggerFactory.getLogger(UsageService.class);

    /** The largest body we read (1 KB). The controller reads one byte more than this to tell "too big". */
    public static final int MAX_BODY_BYTES = 1024;
    public static final String TIMEZONE = HistoricalDailyService.ZONE.getId();

    private static final long ONE_MINUTE = 60_000L;

    /**
     * Substrings of a user agent (lowercase) that mean "not a person". A short hand-set list, not exhaustive: a
     * crawler that does not say so is counted, which is why the page calls views "not people".
     */
    private static final List<String> CRAWLER_MARKERS = List.of(
            "bot", "crawl", "spider", "slurp", "headless", "lighthouse", "facebookexternalhit", "embedly",
            "uptime", "pingdom");

    private record RejectedKey(LocalDate day, String site) {}

    private final UsageVocabulary vocabulary;
    private final UsageLimiter limiter;
    private final UsageRepository repo;
    private final OwnerProperties owner;
    private final Clock clock;
    private final ObjectMapper json = new ObjectMapper();

    private final ConcurrentHashMap<RejectedKey, Long> rejected = new ConcurrentHashMap<>();
    private final AtomicLong lastFlush = new AtomicLong(Long.MIN_VALUE);
    private final AtomicLong lastWarn = new AtomicLong(Long.MIN_VALUE);

    // Refused-send counts that are written nowhere. Logged as counts only, at most once a minute, so the owed
    // post-deploy check (does X-Real-IP arrive, or does the whole site share one bucket?) has something to read.
    private final LongAdder limited = new LongAdder();
    private final LongAdder overCeiling = new LongAdder();
    private final LongAdder crawlers = new LongAdder();
    private final AtomicLong lastCounts = new AtomicLong(Long.MIN_VALUE);

    public UsageService(UsageVocabulary vocabulary, UsageLimiter limiter, UsageRepository repo,
                        OwnerProperties owner, Clock clock) {
        this.vocabulary = vocabulary;
        this.limiter = limiter;
        this.repo = repo;
        this.owner = owner;
        this.clock = clock;
    }

    /** Today in America/New_York: the day a send is counted under. */
    public LocalDate today() {
        return LocalDate.now(clock.withZone(HistoricalDailyService.ZONE));
    }

    /** The one place a sport is spelled for the report and for storage: lowercase. */
    static String sportKey(String sport) {
        return sport == null ? "" : sport.toLowerCase(Locale.ROOT);
    }

    /** Counts one send if it passes every check. Never throws. {@code body} is null when the caller found it too big. */
    public void collect(byte[] body, String userAgent, String address) {
        try {
            logCountsIfDue();
            if (isCrawler(userAgent)) { crawlers.increment(); return; }
            if (!limiter.permit(address)) { limited.increment(); return; }
            if (body == null || body.length == 0 || body.length > MAX_BODY_BYTES) return;
            JsonNode tree;
            try {
                tree = json.readTree(body);
            } catch (Exception e) {
                return; // unparsable: counted nowhere
            }
            UsageVocabulary.Result v = vocabulary.validate(tree);
            LocalDate day = today();
            switch (v.status()) {
                case IGNORED -> { return; }
                case REJECTED -> { noteRejected(day, v.site()); return; }
                case OK -> { /* below */ }
            }
            String sport = "";
            if (!limiter.tryWrite()) { overCeiling.increment(); return; } // over the global ceiling: counted nowhere
            if ("league-view".equals(v.kind())) {
                Optional<String> found = repo.leagueSport(v.leagueId());
                if (found.isEmpty()) {
                    noteRejected(day, v.site());
                    return;
                }
                sport = sportKey(found.get());
            }
            repo.increment(day, v.site(), v.kind(), v.route(), v.name(), v.props(), v.leagueId(), sport);
            flushRejectedIfDue(false);
        } catch (Throwable t) {
            warnOncePerMinute(t);
        }
    }

    private static boolean isCrawler(String userAgent) {
        if (userAgent == null) return false;
        String ua = userAgent.toLowerCase(Locale.ROOT);
        for (String m : CRAWLER_MARKERS) if (ua.contains(m)) return true;
        return false;
    }

    private void noteRejected(LocalDate day, String site) {
        rejected.merge(new RejectedKey(day, site), 1L, Long::sum);
    }

    /** One INFO line a minute, only when something was refused. Counts only: no address, agent or body. */
    private void logCountsIfDue() {
        long now = clock.millis();
        long last = lastCounts.get();
        if (!(last == Long.MIN_VALUE || now - last >= ONE_MINUTE) || !lastCounts.compareAndSet(last, now)) return;
        long l = limited.sumThenReset();
        long o = overCeiling.sumThenReset();
        long c = crawlers.sumThenReset();
        if (l + o + c > 0) {
            log.info("usage sends not counted in the last minute: limited={}, overCeiling={}, crawler={}", l, o, c);
        }
    }

    /** Writes the in-memory rejected counts, at most once a minute unless forced, each write under the ceiling. */
    private void flushRejectedIfDue(boolean force) {
        long now = clock.millis();
        long last = lastFlush.get();
        boolean due = last == Long.MIN_VALUE || now - last >= ONE_MINUTE;
        if (!force && !due) return;
        if (!force && !lastFlush.compareAndSet(last, now)) return;
        if (force) lastFlush.set(now);
        for (RejectedKey key : new ArrayList<>(rejected.keySet())) {
            Long taken = rejected.remove(key); // atomic: an increment lands before the take or in a fresh entry
            if (taken == null) continue;
            long n = taken;
            if (n <= 0) continue;
            try {
                if (!force && !limiter.tryWrite()) {
                    rejected.merge(key, n, Long::sum); // keep it for the next flush
                    continue;
                }
                repo.addRejected(key.day(), key.site(), n);
            } catch (Exception e) {
                warnOncePerMinute(e); // the count is dropped: best effort
            }
        }
    }

    private void warnOncePerMinute(Throwable t) {
        long now = clock.millis();
        long last = lastWarn.get();
        if ((last == Long.MIN_VALUE || now - last >= ONE_MINUTE) && lastWarn.compareAndSet(last, now)) {
            // class name only: a message can carry a bind value
            log.warn("usage collection failed ({}); sends are dropped, not retried", t.getClass().getSimpleName());
        }
    }

    /** The owner's read over {@code from..to} inclusive (ET days). */
    public UsageReport report(LocalDate from, LocalDate to) {
        flushRejectedIfDue(true);
        String ownerId = owner.configured() ? owner.sleeperUserId() : null;

        List<UsageReport.ViewDay> byDay = repo.viewsByDay(from, to).stream()
                .map(r -> new UsageReport.ViewDay(r.day().toString(), r.site(), r.views())).toList();
        List<UsageReport.ViewRoute> byRoute = repo.viewsByRoute(from, to).stream()
                .map(r -> new UsageReport.ViewRoute(r.site(), r.route(), r.views())).toList();
        List<UsageReport.LeagueView> leagues = repo.leagueViews(from, to).stream()
                .map(r -> new UsageReport.LeagueView(r.leagueId(), r.leagueName(), sportKey(r.sport()), r.views())).toList();
        List<UsageReport.EventRow> events = repo.events(from, to).stream()
                .map(r -> new UsageReport.EventRow(r.site(), r.name(), parseProps(r.props()), r.count())).toList();
        List<UsageReport.FunnelDay> funnel = repo.funnel(from, to, ownerId).stream()
                .map(r -> new UsageReport.FunnelDay(r.day().toString(), sportKey(r.sport()), r.started(), r.finished(),
                        r.abandoned(), r.drafting(), r.guestStarted(), r.guestFinished(), r.unknownStarted(),
                        r.dailyStarted(), r.dailyFinished(), r.ownerStarted(), r.ownerFinished(),
                        r.ownerAbandoned(), r.ownerDrafting(), r.ownerDailyStarted(), r.ownerDailyFinished())).toList();
        List<UsageReport.SiteSince> since = repo.countingSince().stream()
                .map(r -> new UsageReport.SiteSince(r.site(), r.since().toString())).toList();

        return new UsageReport(TIMEZONE, from.toString(), to.toString(),
                since,
                repo.gamesSince().map(LocalDate::toString).orElse(null),
                owner.configured(), byDay, byRoute, leagues, events, repo.rejected(from, to), funnel);
    }

    /** {@code k=v;k=v} back to a map. Values came from the vocabulary, so they hold neither ';' nor '='. */
    static Map<String, String> parseProps(String props) {
        Map<String, String> out = new LinkedHashMap<>();
        if (props == null || props.isEmpty()) return out;
        for (String pair : props.split(";")) {
            int eq = pair.indexOf('=');
            if (eq > 0) out.put(pair.substring(0, eq), pair.substring(eq + 1));
        }
        return out;
    }
}
