package com.ballknowers.draftsim.recap;

import com.ballknowers.draftsim.domain.Sport;
import com.ballknowers.draftsim.engine.LeagueSeasonResolver;
import com.ballknowers.draftsim.engine.WeeklyReportService;
import com.ballknowers.draftsim.recap.RecapView.State;
import com.ballknowers.draftsim.refresh.LeagueRefreshService;
import com.ballknowers.draftsim.refresh.SingleFlight;
import com.ballknowers.draftsim.store.LeagueFeatureRepository;
import com.ballknowers.draftsim.store.LeagueRecapRepository;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;

/**
 * The weekly recap's read path and its background generation (specs/020-ai-weekly-recap,
 * contracts/api.md). {@link #view} never calls the model on the request thread: when a recap is
 * needed it starts (or joins) a single flight and answers {@code GENERATING}.
 *
 * <p>Everything keys on the RESOLVED league row ({@link LeagueSeasonResolver}), not on the id in
 * the URL, so one season-week is one stored row and one paid call whichever id reached it (F5).
 */
@Service
public class RecapService {

    private static final Logger log = LoggerFactory.getLogger(RecapService.class);
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final Set<String> TRANSIENT = Set.of("API_ERROR", "RATE_LIMITED_UPSTREAM");
    private static final int MIN_SECTIONS = 3;
    private static final int MAX_SECTIONS = 5;
    private static final int ATTEMPTS = 2; // the first call plus one grounding retry
    static final int THINKING_MODEL_MAX_TOKENS = 16_000;

    /**
     * Haiku (any dated id) is the only family that rejects the effort parameter and has no adaptive
     * thinking. One rule for generate and preview (R6): Haiku gets the configured max-tokens and no
     * effort; every other model gets {@code effort: low} and room for thinking.
     */
    static boolean isHaikuModel(String model) {
        return model != null && model.startsWith("claude-haiku");
    }

    private int maxTokensFor(String model) {
        return isHaikuModel(model) ? props.maxTokens() : THINKING_MODEL_MAX_TOKENS;
    }

    private final ObjectProvider<RecapClient> clientProvider;
    private final WeeklyReportService weeklyReport;
    private final LeagueSeasonResolver resolver;
    private final LeagueFeatureRepository features;
    private final LeagueRecapRepository recaps;
    private final LeagueRefreshService refresh;
    private final RecapProperties props;
    private final RecapInputBuilder builder;
    private final Clock clock;

    /**
     * A NEW instance, never refresh's: SingleFlight's javadoc warns that a run waiting on another
     * flight must not share one. Its cap of 2 concurrent runs is inherited, not chosen here.
     */
    private final SingleFlight flight = new SingleFlight();
    private final Set<CompletableFuture<?>> pending = ConcurrentHashMap.newKeySet();
    /**
     * Makes "cap not spent, then log the call" one step (R9). A JVM lock, not a database one: a
     * single replica is the stated assumption (a second one could overshoot by one call per race).
     */
    private final Object capLock = new Object();

    /** Thrown by {@link #preview} when the daily caps are spent; the admin route answers 429. */
    public static final class CapSpentException extends RuntimeException {
        public CapSpentException() {
            super("recap call caps spent for today");
        }
    }

    public RecapService(ObjectProvider<RecapClient> clientProvider, WeeklyReportService weeklyReport,
                        LeagueSeasonResolver resolver, LeagueFeatureRepository features,
                        LeagueRecapRepository recaps, LeagueRefreshService refresh,
                        RecapProperties props, RecapInputBuilder builder, Clock clock) {
        this.clientProvider = clientProvider;
        this.weeklyReport = weeklyReport;
        this.resolver = resolver;
        this.features = features;
        this.recaps = recaps;
        this.refresh = refresh;
        this.props = props;
        this.builder = builder;
        this.clock = clock;
    }

    public RecapView view(String sleeperId, int week) {
        RecapClient client = clientProvider.getIfAvailable();
        if (client == null) return RecapView.bare(State.FEATURE_OFF, null, week);

        var resolved = resolver.resolve(sleeperId);
        if (resolved.isEmpty()) return RecapView.bare(State.NOT_ENTITLED, null, week);
        long leagueId = resolved.get().league().id();
        if (!features.has(leagueId, LeagueFeatureRepository.RECAP)) {
            return RecapView.bare(State.NOT_ENTITLED, resolved.get().league().season(), week);
        }

        // One Result feeds both the finality check and the input (F7): no second snapshot.
        WeeklyReportService.Result result = weeklyReport.forWeek(sleeperId, week, null).orElse(null);
        if (result == null || !result.available() || !result.weekFinal()) {
            return RecapView.bare(State.WEEK_NOT_FINAL,
                    result == null ? resolved.get().league().season() : result.season(), week);
        }
        int season = result.season();

        RecapInput input = builder.build(result);
        String canonical = builder.canonicalJson(input);
        String key = builder.cacheKey(canonical, props.model(), builder.promptVersion());
        String numbersHash = builder.numbersHash(canonical);
        LeagueRecapRepository.Row row = recaps.find(leagueId, week).orElse(null);
        Instant now = clock.instant();

        if (row != null && key.equals(row.readyKey())) return body(State.READY, season, week, row, false, null);

        if (row != null && key.equals(row.attemptKey()) && "FAILED".equals(row.attemptStatus())) {
            boolean transientFailure = TRANSIENT.contains(row.failureReason());
            if (!transientFailure || (row.retryAfter() != null && now.isBefore(row.retryAfter()))) {
                return body(State.FAILED, season, week, row, true, row.failureReason());
            }
        }

        String fk = flightKey(leagueId, week);
        boolean inFlight = flight.isRunning(fk);

        boolean refreshing = refresh.status(sleeperId)
                .map(s -> s.state() == LeagueRefreshService.State.RUNNING).orElse(false);
        if (refreshing && !inFlight) {
            // Torn-read guard (F7): no new generation mid-refresh. Serve the stored body, or keep the card polling.
            return hasBody(row) ? body(State.GENERATING, season, week, row, true, null)
                    : RecapView.bare(State.GENERATING, season, week);
        }

        if (!inFlight && capsSpent(leagueId, now)) {
            return hasBody(row) ? body(State.RATE_LIMITED, season, week, row, true, null)
                    : RecapView.bare(State.RATE_LIMITED, season, week);
        }

        CompletableFuture<Void> f = flight.run(fk, () -> {
            generate(client, leagueId, week, input, canonical, key, numbersHash);
            return null;
        });
        if (pending.add(f)) {
            f.whenComplete((v, t) -> {
                pending.remove(f);
                if (t != null) log.warn("recap generation for {} ended with {}", fk, t.getClass().getSimpleName());
            });
        }
        return hasBody(row) ? body(State.GENERATING, season, week, row, true, null)
                : RecapView.bare(State.GENERATING, season, week);
    }

    /** Whether recap is on at all, that is, a {@link RecapClient} bean exists. */
    public boolean enabled() {
        return clientProvider.getIfAvailable() != null;
    }

    /** Test seam: blocks until every flight started so far has finished. */
    void awaitFlights() {
        for (CompletableFuture<?> f : List.copyOf(pending)) {
            try {
                f.join();
            } catch (RuntimeException ignored) {
                // already logged by whenComplete
            }
        }
    }

    // ---- operator preview (F2) ----------------------------------------------------------------

    /** One non-stored model call: what the bake-off compares. {@code output} is null when it did not parse. */
    public record Preview(RecapOutput output, GroundingResult grounding, int inputTokens, int outputTokens,
                          String stopReason, long latencyMs, String parseError) {}

    /**
     * Builds the input for the resolved row, calls {@code model}, and grounds the answer. Never reads
     * or writes {@code league_recap}; it logs one call against the caps and is refused with
     * {@link CapSpentException} when they are spent. Empty when the league or week is unknown. Throws
     * {@link IllegalStateException} when recap is off.
     */
    public Optional<Preview> preview(String sleeperId, int week, String model) {
        RecapClient client = clientProvider.getIfAvailable();
        if (client == null) throw new IllegalStateException("recap is disabled");
        var resolved = resolver.resolve(sleeperId);
        if (resolved.isEmpty()) return Optional.empty();
        var result = weeklyReport.forWeek(sleeperId, week, null);
        if (result.isEmpty() || !result.get().available()) return Optional.empty();

        RecapInput input = builder.build(result.get());
        String canonical = builder.canonicalJson(input);
        boolean thinks = !isHaikuModel(model);
        if (!reserveCall(resolved.get().league().id(), clock.instant())) throw new CapSpentException();
        RecapCallResult res = client.call(model, maxTokensFor(model), builder.systemPrompt(),
                builder.userTurn(canonical), thinks);
        GroundingResult g = res.output() == null ? null
                : GroundingCheck.check(res.output(), canonical, Sport.fromCode(input.sport()));
        return Optional.of(new Preview(res.output(), g, res.inputTokens(), res.outputTokens(),
                res.stopReason(), res.latencyMs(), res.parseError()));
    }

    // ---- the flight ---------------------------------------------------------------------------

    /**
     * Any exception that escapes the flight after this point (a persistence error, a grounding or
     * parsing bug) would leave no attempt row, and the card's next poll would start another paid
     * call (R7). So it is recorded as a transient API_ERROR, and only its class name is logged.
     */
    private void generate(RecapClient client, long leagueId, int week, RecapInput input,
                          String canonical, String key, String numbersHash) {
        try {
            generateOnce(client, leagueId, week, input, canonical, key, numbersHash);
        } catch (RuntimeException e) {
            log.warn("recap generation failed unexpectedly: {}", e.getClass().getSimpleName());
            try {
                fail(leagueId, week, key, "API_ERROR", null, null, true);
            } catch (RuntimeException again) {
                log.warn("could not record the failed attempt: {}", again.getClass().getSimpleName());
            }
        }
    }

    private void generateOnce(RecapClient client, long leagueId, int week, RecapInput input,
                              String canonical, String key, String numbersHash) {
        String model = props.model();
        String promptVersion = builder.promptVersion();
        String system = builder.systemPrompt();
        String userTurn = builder.userTurn(canonical);
        Sport sport = Sport.fromCode(input.sport());
        int inTokens = 0;
        int outTokens = 0;

        for (int attempt = 0; attempt < ATTEMPTS; attempt++) {
            // A GET racing the previous flight's completion can land here after the body is stored (R8):
            // re-read before EACH call so it neither pays again nor bumps the revision.
            if (recaps.find(leagueId, week).map(r -> key.equals(r.readyKey())).orElse(false)) return;
            // Re-check before EACH call (N7): a retry is a second paid call and the cap counts it.
            if (!reserveCall(leagueId, clock.instant())) return;

            RecapCallResult res;
            try {
                res = client.call(model, maxTokensFor(model), system, userTurn, !isHaikuModel(model));
            } catch (RecapUpstreamException e) {
                fail(leagueId, week, key, e.kind().name(), null, null, true);
                return;
            } catch (RuntimeException e) {
                log.warn("recap call failed: {}", e.getClass().getSimpleName());
                fail(leagueId, week, key, "API_ERROR", null, null, true);
                return;
            }
            inTokens += res.inputTokens();
            outTokens += res.outputTokens();

            if ("refusal".equals(res.stopReason())) {
                fail(leagueId, week, key, "REFUSED", null, res.stopReason(), false);
                return;
            }
            if ("max_tokens".equals(res.stopReason())) {
                fail(leagueId, week, key, "TRUNCATED", null, res.stopReason(), false);
                return;
            }
            RecapOutput out = res.output();
            if (res.parseError() != null || out == null || out.sections() == null
                    || out.sections().size() < MIN_SECTIONS || out.sections().size() > MAX_SECTIONS) {
                Map<String, Object> detail = new LinkedHashMap<>();
                detail.put("parseError", res.parseError());
                detail.put("sections", out == null || out.sections() == null ? null : out.sections().size());
                fail(leagueId, week, key, "MALFORMED", detail, res.stopReason(), false);
                return;
            }

            GroundingResult g = GroundingCheck.check(out, canonical, sport);
            if (g.ok()) {
                writeReady(leagueId, week, key, numbersHash, canonical, out, model, promptVersion,
                        inTokens, outTokens, res.stopReason());
                return;
            }
            if (attempt + 1 < ATTEMPTS) {
                userTurn = builder.userTurn(canonical) + "\n\nYour previous answer failed the grounding check. "
                        + "Rewrite it so none of these occur. " + describe(g);
                continue;
            }
            Map<String, Object> detail = new LinkedHashMap<>();
            detail.put("unmatched", g.unmatched());
            detail.put("badCites", g.badCites());
            detail.put("unboundNames", g.unboundNames());
            detail.put("basisViolations", g.basisViolations());
            fail(leagueId, week, key, "UNGROUNDED", detail, res.stopReason(), false);
        }
    }

    private static String describe(GroundingResult g) {
        StringBuilder sb = new StringBuilder();
        if (!g.unmatched().isEmpty()) sb.append("Numbers or tokens not found in the cited items: ").append(g.unmatched()).append(". ");
        if (!g.badCites().isEmpty()) sb.append("Bad cites: ").append(g.badCites()).append(". ");
        if (!g.unboundNames().isEmpty()) sb.append("Names used outside their cited items: ").append(g.unboundNames()).append(". ");
        if (!g.basisViolations().isEmpty()) sb.append("Wording the data basis forbids: ").append(g.basisViolations()).append(". ");
        return sb.toString().trim();
    }

    private void writeReady(long leagueId, int week, String key, String numbersHash, String canonical,
                            RecapOutput out, String model, String promptVersion, int in, int outTok, String stop) {
        var existing = recaps.find(leagueId, week).filter(r -> r.readyKey() != null);
        String reason = null;
        if (existing.isPresent()) {
            // Computed from the stored input JSON, not from a stored hash, so the labels never depend
            // on how an older row's hash was derived (R4).
            String old = existing.get().readyInputJson();
            if (old == null) reason = "REPORT_CHANGED";
            else if (!numbersHash.equals(builder.numbersHash(old))) reason = "NUMBERS_CHANGED";
            else if (canonical.equals(old)) reason = "MODEL_OR_PROMPT_CHANGED";
            else if (builder.namesBlankedHash(canonical).equals(builder.namesBlankedHash(old))) reason = "NAMES_CHANGED";
            else reason = "REPORT_CHANGED";
        }
        try {
            recaps.writeReady(leagueId, week, key, numbersHash, canonical, out.headline(),
                    JSON.writeValueAsString(out.sections()), model, promptVersion, in, outTok, reason, stop,
                    clock.instant());
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("could not serialize recap sections", e);
        }
    }

    private void fail(long leagueId, int week, String key, String reason, Map<String, Object> detail,
                      String stop, boolean transientFailure) {
        Instant now = clock.instant();
        Instant retryAfter = transientFailure ? now.plus(props.transientRetryMinutes(), ChronoUnit.MINUTES) : null;
        String detailJson = null;
        if (detail != null) {
            try {
                detailJson = JSON.writeValueAsString(detail);
            } catch (JsonProcessingException e) {
                detailJson = null;
            }
        }
        recaps.writeFailure(leagueId, week, key, reason, detailJson, stop, retryAfter, now);
    }

    // ---- helpers ------------------------------------------------------------------------------

    /** Checks the caps and logs the call as one step (R9). False when a cap is spent; nothing is logged then. */
    private boolean reserveCall(long leagueId, Instant now) {
        synchronized (capLock) {
            if (capsSpent(leagueId, now)) return false;
            recaps.logCall(leagueId, now);
            return true;
        }
    }

    private boolean capsSpent(long leagueId, Instant now) {
        Instant dayStart = LocalDate.ofInstant(now, ZoneOffset.UTC).atStartOfDay().toInstant(ZoneOffset.UTC);
        return recaps.callsSince(leagueId, dayStart) >= props.maxCallsPerLeaguePerDay()
                || recaps.callsSinceGlobal(dayStart) >= props.maxCallsPerDay();
    }

    private static String flightKey(long leagueId, int week) {
        return leagueId + ":" + week;
    }

    private static boolean hasBody(LeagueRecapRepository.Row row) {
        return row != null && row.readyKey() != null && row.headline() != null;
    }

    /** A body from the stored READY columns. A stale body always carries its own model and generatedAt (F11). */
    private RecapView body(State state, int season, int week, LeagueRecapRepository.Row row, boolean stale,
                           String failureReason) {
        if (!hasBody(row)) {
            return new RecapView(state, season, week, null, null, null, null, false, null, null, failureReason);
        }
        // Unreadable stored sections are null, not an empty list: the card then shows the headline only (R13).
        List<RecapOutput.Section> sections = null;
        try {
            sections = JSON.readValue(row.sectionsJson(), new TypeReference<List<RecapOutput.Section>>() {});
        } catch (JsonProcessingException | RuntimeException e) {
            log.warn("stored recap sections unreadable for league {} week {}", row.leagueId(), week);
        }
        return new RecapView(state, season, week, row.model(), row.generatedAt(), row.revision(),
                row.revisionReason(), stale, row.headline(), sections, failureReason);
    }
}
