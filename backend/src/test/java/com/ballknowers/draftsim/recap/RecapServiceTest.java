package com.ballknowers.draftsim.recap;

import com.ballknowers.draftsim.domain.Sport;
import com.ballknowers.draftsim.engine.LeagueSeasonResolver;
import com.ballknowers.draftsim.engine.WeeklyReportService;
import com.ballknowers.draftsim.engine.WeeklyReportService.Award;
import com.ballknowers.draftsim.engine.WeeklyReportService.Matchup;
import com.ballknowers.draftsim.engine.WeeklyReportService.Performer;
import com.ballknowers.draftsim.engine.WeeklyReportService.Result;
import com.ballknowers.draftsim.engine.WeeklyReportService.Side;
import com.ballknowers.draftsim.recap.RecapView.State;
import com.ballknowers.draftsim.refresh.LeagueRefreshService;
import com.ballknowers.draftsim.store.LeagueFeatureRepository;
import com.ballknowers.draftsim.store.LeagueRecapRepository;
import com.ballknowers.draftsim.store.LeagueRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.UnaryOperator;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * The recap's read path and flight (specs/020-ai-weekly-recap T024), against an in-memory store,
 * a counting fake model and a movable clock. Nothing here touches the network or a database.
 */
class RecapServiceTest {

    private static final String MODEL = "claude-haiku-4-5";
    private static final Instant T0 = Instant.parse("2026-10-06T12:00:00Z");
    private static final long LEAGUE = 211L;

    // ---- doubles ------------------------------------------------------------------------------

    static final class TestClock extends Clock {
        Instant now = T0;
        @Override public ZoneId getZone() { return ZoneOffset.UTC; }
        @Override public Clock withZone(ZoneId zone) { return this; }
        @Override public Instant instant() { return now; }
    }

    /** The scripted model: pops one outcome per call (a RecapCallResult or a RuntimeException). */
    static final class FakeClient implements RecapClient {
        final Deque<Object> script = new ArrayDeque<>();
        final AtomicInteger calls = new AtomicInteger();
        final List<String> userTurns = new ArrayList<>();
        RecapCallResult dflt = good();

        @Override
        public synchronized RecapCallResult call(String model, int maxTokens, String system, String user, boolean low) {
            calls.incrementAndGet();
            userTurns.add(user);
            Object next = script.isEmpty() ? dflt : script.poll();
            if (next instanceof RuntimeException e) throw e;
            return (RecapCallResult) next;
        }
    }

    /** In-memory {@link LeagueRecapRepository}; mirrors the SQL's two-group semantics. */
    static final class FakeRecaps extends LeagueRecapRepository {
        final Map<String, Row> rows = new HashMap<>();
        final List<Object[]> calls = new ArrayList<>(); // {leagueId, Instant}
        int rowsWritten;
        boolean failNextWriteReady;
        /** The first find() answers "no row" (a GET that read just before a flight finished, R8). */
        boolean hideNextFind;
        /** When set, callsSinceGlobal() on any other thread waits here after counting (R9's race window). */
        java.util.concurrent.CyclicBarrier raceBarrier;
        Thread testThread = Thread.currentThread();

        FakeRecaps() { super(null); }

        @Override public synchronized Optional<Row> find(long leagueId, int week) {
            if (hideNextFind) {
                hideNextFind = false;
                return Optional.empty();
            }
            return Optional.ofNullable(rows.get(leagueId + ":" + week));
        }

        @Override public synchronized void reroll(long leagueId, int week) {
            Row o = rows.get(leagueId + ":" + week);
            if (o == null) return;
            rows.put(leagueId + ":" + week, new Row(o.id(), leagueId, week, null, null, null, null, null, null,
                    null, null, null, o.revision(), null, null, null, null, null, null, null, null, null));
        }

        @Override
        public synchronized void writeReady(long leagueId, int week, String readyKey, String numbersHash,
                                            String inputJson, String headline, String sectionsJson, String model,
                                            String promptVersion, int inTok, int outTok, String revisionReason,
                                            String stopReason, Instant now) {
            if (failNextWriteReady) {
                failNextWriteReady = false;
                throw new IllegalStateException("db down");
            }
            Row o = rows.get(leagueId + ":" + week);
            rows.put(leagueId + ":" + week, new Row(o == null ? rows.size() + 1 : o.id(), leagueId, week,
                    readyKey, numbersHash, inputJson, headline, sectionsJson, model, promptVersion, inTok, outTok,
                    (o == null ? 0 : o.revision()) + 1, revisionReason, now,
                    readyKey, "READY", null, null, stopReason, null, now));
            rowsWritten++;
        }

        @Override
        public synchronized void writeFailure(long leagueId, int week, String attemptKey, String reason,
                                              String detailJson, String stopReason, Instant retryAfter, Instant now) {
            Row o = rows.get(leagueId + ":" + week);
            if (o == null) {
                o = new Row(rows.size() + 1, leagueId, week, null, null, null, null, null, null, null, null, null,
                        0, null, null, null, null, null, null, null, null, null);
            }
            rows.put(leagueId + ":" + week, new Row(o.id(), leagueId, week, o.readyKey(), o.readyNumbersHash(),
                    o.readyInputJson(), o.headline(), o.sectionsJson(), o.model(), o.promptVersion(),
                    o.inputTokens(), o.outputTokens(), o.revision(), o.revisionReason(), o.generatedAt(),
                    attemptKey, "FAILED", reason, detailJson, stopReason, retryAfter, now));
        }

        @Override public synchronized void clearAttempt(long leagueId, int week) {
            Row o = rows.get(leagueId + ":" + week);
            if (o == null) return;
            rows.put(leagueId + ":" + week, new Row(o.id(), leagueId, week, o.readyKey(), o.readyNumbersHash(),
                    o.readyInputJson(), o.headline(), o.sectionsJson(), o.model(), o.promptVersion(),
                    o.inputTokens(), o.outputTokens(), o.revision(), o.revisionReason(), o.generatedAt(),
                    null, null, null, null, null, null, null));
        }

        @Override public synchronized int callsSince(long leagueId, Instant from) {
            return (int) calls.stream().filter(c -> (long) c[0] == leagueId && !((Instant) c[1]).isBefore(from)).count();
        }

        @Override public int callsSinceGlobal(Instant from) {
            int n;
            synchronized (this) {
                n = (int) calls.stream().filter(c -> !((Instant) c[1]).isBefore(from)).count();
            }
            if (raceBarrier != null && Thread.currentThread() != testThread) {
                try {
                    raceBarrier.await(500, java.util.concurrent.TimeUnit.MILLISECONDS);
                } catch (Exception ignored) {
                    // alone in the window (the cap check and the log are one step): fine
                }
            }
            return n;
        }

        @Override public synchronized void logCall(long leagueId, Instant at) { calls.add(new Object[]{leagueId, at}); }
    }

    static final class FakeFeatures extends LeagueFeatureRepository {
        final Set<Long> granted = new HashSet<>();
        FakeFeatures() { super(null); }
        @Override public boolean has(long leagueId, String feature) { return granted.contains(leagueId); }
    }

    // ---- fixtures -----------------------------------------------------------------------------

    private static RecapCallResult good() {
        return new RecapCallResult("end_turn", output(), null, 1000, 500, 10);
    }

    private static RecapOutput output() {
        return new RecapOutput("Master Bates hits 188.48 and stays perfect", List.of(
                new RecapOutput.Section("Top score",
                        "Master Bates scored 188.48, the top total of week 3, and beat Khatt Stafford by 67.58 to move "
                                + "to 3-0. Jahmyr Gibbs led every player with 41.4 against NYJ.",
                        List.of("/matchups/3", "/weekHigh", "/topPerformers/0")),
                new RecapOutput.Section("Unlucky",
                        "Khatt Stafford outscored 6 of 11 other teams and still lost. They're 1-2.",
                        List.of("/awards/1", "/matchups/3")),
                new RecapOutput.Section("The margin", "Master Bates won by 67.58.", List.of("/matchups/3"))));
    }

    private static RecapCallResult ungrounded() {
        return new RecapCallResult("end_turn", new RecapOutput("Week 3", List.of(
                new RecapOutput.Section("A", "Khatt Stafford finished 3rd in weekly scoring.", List.of("/awards/1")),
                new RecapOutput.Section("B", "Master Bates won by 67.58.", List.of("/matchups/3")),
                new RecapOutput.Section("C", "Master Bates scored 188.48.", List.of("/weekHigh")))), null, 900, 400, 10);
    }

    private static RecapCallResult sections(int n) {
        List<RecapOutput.Section> s = new ArrayList<>();
        for (int i = 0; i < n; i++) s.add(new RecapOutput.Section("S" + i, "Master Bates scored 188.48.", List.of("/weekHigh")));
        return new RecapCallResult("end_turn", new RecapOutput("Week 3", s), null, 900, 400, 10);
    }

    private static Result withWeekFinal(Result r, boolean f) {
        return new Result(r.available(), r.reason(), r.season(), r.requestedSeason(), r.week(), r.sport(),
                r.playersPlayMultiplePerPeriod(), r.matchups(), r.topPerformers(), r.bestNights(), r.bestWeek(),
                r.basis(), r.sectionsUnavailable(), r.awards(), r.awardsOmitted(), r.latestScoredWeek(),
                r.latestFinalWeek(), f);
    }

    private static Result mapSides(Result r, UnaryOperator<Side> f) {
        List<Matchup> ms = new ArrayList<>();
        for (Matchup m : r.matchups()) ms.add(new Matchup(f.apply(m.home()), f.apply(m.away())));
        return new Result(r.available(), r.reason(), r.season(), r.requestedSeason(), r.week(), r.sport(),
                r.playersPlayMultiplePerPeriod(), ms, r.topPerformers(), r.bestNights(), r.bestWeek(), r.basis(),
                r.sectionsUnavailable(), r.awards(), r.awardsOmitted(), r.latestScoredWeek(),
                r.latestFinalWeek(), r.weekFinal());
    }

    /** A point change on one roster that none of the good output's cited items depends on. */
    private static Result bumped(Result r) {
        return mapSides(r, s -> s.rosterId() == 1
                ? new Side(s.rosterId(), s.teamName(), s.username(), s.avatarId(), s.record(), s.points() + 1, s.isMe())
                : s);
    }

    private static Result renamed(Result r, String from, String to) {
        UnaryOperator<String> n = s -> s == null ? null : s.replace(from, to);
        List<Matchup> ms = new ArrayList<>();
        for (Matchup m : r.matchups()) ms.add(new Matchup(side(m.home(), n), side(m.away(), n)));
        List<Performer> ps = r.topPerformers().stream()
                .map(p -> new Performer(p.playerId(), p.playerName(), p.position(), n.apply(p.teamName()),
                        p.points(), p.team(), p.opponent(), p.isAway(), p.avatarId())).toList();
        List<Award> as = r.awards().stream()
                .map(a -> new Award(a.kind(), n.apply(a.teamName()), n.apply(a.detail()))).toList();
        return new Result(r.available(), r.reason(), r.season(), r.requestedSeason(), r.week(), r.sport(),
                r.playersPlayMultiplePerPeriod(), ms, ps, r.bestNights(), r.bestWeek(), r.basis(),
                r.sectionsUnavailable(), as, r.awardsOmitted(), r.latestScoredWeek(), r.latestFinalWeek(),
                r.weekFinal());
    }

    private static Side side(Side s, UnaryOperator<String> n) {
        return new Side(s.rosterId(), n.apply(s.teamName()), s.username(), s.avatarId(), s.record(), s.points(), s.isMe());
    }

    // ---- harness ------------------------------------------------------------------------------

    private TestClock clock;
    private FakeClient client;
    private FakeRecaps recaps;
    private FakeFeatures features;
    private WeeklyReportService weekly;
    private LeagueSeasonResolver resolver;
    private LeagueRefreshService refresh;
    private ObjectProvider<RecapClient> provider;
    private Result current;
    private RecapService svc;

    private static RecapProperties props(String model, int leagueCap, int globalCap) {
        return new RecapProperties(true, model, leagueCap, globalCap, 15, 60, 2048);
    }

    private RecapService service(RecapProperties p) {
        return new RecapService(provider, weekly, resolver, features, recaps, refresh, p, new RecapInputBuilder(), clock);
    }

    @SuppressWarnings("unchecked")
    @BeforeEach
    void setUp() {
        clock = new TestClock();
        client = new FakeClient();
        recaps = new FakeRecaps();
        features = new FakeFeatures();
        features.granted.add(LEAGUE);
        current = RecapFixtures.nfl();
        weekly = mock(WeeklyReportService.class);
        when(weekly.forWeek(anyString(), anyInt(), isNull())).thenAnswer(i -> Optional.of(current));
        var row = new LeagueRepository.LeagueRow(LEAGUE, Sport.NFL, "L1", "Test", 2026, 12, List.of(), 0.5, null, null);
        resolver = mock(LeagueSeasonResolver.class);
        when(resolver.resolve(anyString())).thenReturn(Optional.of(new LeagueSeasonResolver.Resolved(row, null)));
        refresh = mock(LeagueRefreshService.class);
        provider = mock(ObjectProvider.class);
        when(provider.getIfAvailable()).thenReturn(client);
        svc = service(props(MODEL, 10, 50));
    }

    private RecapView view() { return svc.view("L1", 3); }

    private RecapView generate() {
        RecapView first = view();
        assertEquals(State.GENERATING, first.state());
        svc.awaitFlights();
        return view();
    }

    // ---- gates --------------------------------------------------------------------------------

    @Test
    void noClientBeanIsFeatureOffWithZeroCallsAndNoLookups() {
        when(provider.getIfAvailable()).thenReturn(null);
        RecapView v = view();
        assertEquals(State.FEATURE_OFF, v.state());
        assertEquals(0, client.calls.get());
        verifyNoInteractions(resolver, weekly);
    }

    @Test
    void notEntitledShowsNothingAndCallsNothing() {
        features.granted.clear();
        assertEquals(State.NOT_ENTITLED, view().state());
        assertEquals(0, client.calls.get());
        verifyNoInteractions(weekly);
    }

    @Test
    void weekNotFinalCallsNothing() {
        current = withWeekFinal(current, false);
        assertEquals(State.WEEK_NOT_FINAL, view().state());
        assertEquals(0, client.calls.get());
    }

    // ---- generation ---------------------------------------------------------------------------

    @Test
    void firstGetGeneratesInTheBackgroundAndASecondIsFree() {
        RecapView first = view();
        assertEquals(State.GENERATING, first.state());
        assertNull(first.headline());
        svc.awaitFlights();
        assertEquals(1, client.calls.get());

        RecapView ready = view();
        assertEquals(State.READY, ready.state());
        assertEquals(MODEL, ready.model());
        assertEquals(2026, ready.season());
        assertEquals(1, ready.revision());
        assertNull(ready.revisionReason());
        assertFalse(ready.stale());
        assertEquals(3, ready.sections().size());
        assertNotNull(ready.generatedAt());

        assertEquals(State.READY, view().state());
        assertEquals(1, client.calls.get(), "a second GET makes no new call");
    }

    @Test
    void twoSleeperIdsResolvingToOneRowShareOneRowAndOneCall() {
        assertEquals(State.GENERATING, svc.view("L-2026", 3).state());
        svc.awaitFlights();
        assertEquals(State.READY, svc.view("L-2025", 3).state());
        assertEquals(1, client.calls.get());
        assertEquals(1, recaps.rows.size());
    }

    // ---- regeneration reasons -----------------------------------------------------------------

    @Test
    void changedPointsRegenerateAsNumbersChanged() {
        generate();
        current = bumped(current);
        RecapView v = generate();
        assertEquals(State.READY, v.state(), String.valueOf(recaps.rows.values()));
        assertEquals(2, v.revision());
        assertEquals("NUMBERS_CHANGED", v.revisionReason());
        assertEquals(2, client.calls.get());
    }

    @Test
    void aRenameAloneIsNamesChanged() {
        generate();
        current = renamed(current, "Likely Have Downs", "Likely Renamed");
        RecapView v = generate();
        assertEquals(State.READY, v.state(), String.valueOf(recaps.rows.values()));
        assertEquals(2, v.revision());
        assertEquals("NAMES_CHANGED", v.revisionReason());
    }

    @Test
    void aChangedModelIsModelOrPromptChanged() {
        generate();
        svc = service(props("claude-sonnet-5-5", 10, 50));
        RecapView v = generate();
        assertEquals(State.READY, v.state());
        assertEquals(2, v.revision());
        assertEquals("MODEL_OR_PROMPT_CHANGED", v.revisionReason());
    }

    // ---- grounding ----------------------------------------------------------------------------

    @Test
    void failingGroundingTwiceIsUngroundedAfterTwoCallsAndKeepsTheTokens() {
        client.script.add(ungrounded());
        client.script.add(ungrounded());
        RecapView v = generate();
        assertEquals(State.FAILED, v.state());
        assertEquals("UNGROUNDED", v.failureReason());
        assertEquals(2, client.calls.get());
        assertTrue(recaps.find(LEAGUE, 3).orElseThrow().failureDetailJson().contains("3rd"));
        assertTrue(client.userTurns.get(1).contains("3rd"), "the retry lists the failures");
        assertEquals(2, recaps.callsSince(LEAGUE, T0.minus(Duration.ofHours(1))), "a retry logs a second call");
    }

    @Test
    void failingOnceThenPassingIsReadyAfterTwoCalls() {
        client.script.add(ungrounded());
        RecapView v = generate();
        assertEquals(State.READY, v.state());
        assertEquals(2, client.calls.get());
    }

    // ---- other failures -----------------------------------------------------------------------

    @Test
    void aRefusalIsRefusedAndStaysFailedUntilTheAttemptIsCleared() {
        client.script.add(new RecapCallResult("refusal", null, null, 100, 0, 5));
        RecapView v = generate();
        assertEquals(State.FAILED, v.state());
        assertEquals("REFUSED", v.failureReason());
        assertEquals(State.FAILED, view().state());
        assertEquals(State.FAILED, view().state());
        assertEquals(1, client.calls.get());

        recaps.clearAttempt(LEAGUE, 3);
        assertEquals(State.READY, generate().state());
        assertEquals(2, client.calls.get());
    }

    @Test
    void maxTokensIsTruncated() {
        client.script.add(new RecapCallResult("max_tokens", null, null, 100, 2048, 5));
        RecapView v = generate();
        assertEquals("TRUNCATED", v.failureReason());
    }

    @Test
    void twoOrSixSectionsAreMalformed() {
        client.script.add(sections(2));
        assertEquals("MALFORMED", generate().failureReason());
        recaps.clearAttempt(LEAGUE, 3);
        client.script.add(sections(6));
        assertEquals("MALFORMED", generate().failureReason());
    }

    @Test
    void aParseErrorIsMalformed() {
        client.script.add(new RecapCallResult("end_turn", null, "response did not parse", 100, 50, 5));
        assertEquals("MALFORMED", generate().failureReason());
    }

    // ---- transient failures (F1) --------------------------------------------------------------

    @Test
    void anApiErrorRetriesOnlyAfterRetryAfter() {
        client.script.add(new RecapUpstreamException(RecapUpstreamException.Kind.API_ERROR, "boom"));
        RecapView v = generate();
        assertEquals(State.FAILED, v.state());
        assertEquals("API_ERROR", v.failureReason());
        assertEquals(1, client.calls.get());

        clock.now = T0.plus(Duration.ofMinutes(14));
        assertEquals(State.FAILED, view().state());
        assertEquals(1, client.calls.get(), "before retry_after: no call");

        clock.now = T0.plus(Duration.ofMinutes(16));
        assertEquals(State.GENERATING, view().state());
        svc.awaitFlights();
        assertEquals(2, client.calls.get());
        assertEquals(State.READY, view().state());
    }

    @Test
    void anUpstreamRateLimitIsTransientToo() {
        client.script.add(new RecapUpstreamException(RecapUpstreamException.Kind.RATE_LIMITED_UPSTREAM, "429"));
        assertEquals("RATE_LIMITED_UPSTREAM", generate().failureReason());
        assertNotNull(recaps.find(LEAGUE, 3).orElseThrow().retryAfter());
    }

    // ---- F11: a failed regeneration keeps the good body ---------------------------------------

    @Test
    void aFailedRegenerationKeepsTheOldBodyAsStale() {
        RecapView first = generate();
        current = bumped(current);
        client.script.add(new RecapCallResult("refusal", null, null, 100, 0, 5));
        RecapView v = generate();
        assertEquals(State.FAILED, v.state());
        assertTrue(v.stale());
        assertEquals("REFUSED", v.failureReason());
        assertEquals(first.headline(), v.headline());
        assertEquals(first.model(), v.model());
        assertEquals(first.generatedAt(), v.generatedAt());
        assertEquals(1, v.revision());
    }

    // ---- caps (N7) ----------------------------------------------------------------------------

    @Test
    void theLeagueCapIsRateLimitedWithNoBodyWhenNoneExists() {
        recaps.logCall(LEAGUE, T0);
        recaps.logCall(LEAGUE, T0);
        svc = service(props(MODEL, 2, 50));
        RecapView v = view();
        assertEquals(State.RATE_LIMITED, v.state());
        assertNull(v.headline());
        assertEquals(0, client.calls.get());
    }

    @Test
    void theLeagueCapServesTheStaleBodyWhenOneExists() {
        svc = service(props(MODEL, 1, 50));
        RecapView first = generate();
        assertEquals(State.READY, first.state());
        current = bumped(current);
        RecapView v = view();
        assertEquals(State.RATE_LIMITED, v.state());
        assertTrue(v.stale());
        assertEquals(first.headline(), v.headline());
        assertEquals(first.model(), v.model());
        assertEquals(1, client.calls.get());
    }

    @Test
    void theGlobalCapIsTheSame() {
        recaps.logCall(999L, T0);
        recaps.logCall(998L, T0);
        svc = service(props(MODEL, 10, 2));
        assertEquals(State.RATE_LIMITED, view().state());
        assertEquals(0, client.calls.get());
    }

    @Test
    void aCapSpentBetweenTheCallsStopsTheRetry() {
        // One call left in the league's day: the first call is allowed, its grounding retry is not.
        svc = service(props(MODEL, 1, 50));
        client.script.add(ungrounded());
        view();
        svc.awaitFlights();
        assertEquals(1, client.calls.get());
        assertEquals(State.RATE_LIMITED, view().state());
    }

    @Test
    void theUtcDayRollingOverAllowsGenerationAgain() {
        clock.now = Instant.parse("2026-10-06T23:59:00Z");
        recaps.logCall(LEAGUE, clock.now);
        svc = service(props(MODEL, 1, 50));
        assertEquals(State.RATE_LIMITED, view().state());
        clock.now = Instant.parse("2026-10-07T00:01:00Z");
        assertEquals(State.GENERATING, view().state());
        svc.awaitFlights();
        assertEquals(1, client.calls.get());
    }

    // ---- mid-refresh (F7) ---------------------------------------------------------------------

    private void refreshRunning() {
        when(refresh.status(anyString())).thenReturn(Optional.of(
                new LeagueRefreshService.Status(LeagueRefreshService.State.RUNNING, "L1", 2026, null, null, List.of())));
    }

    @Test
    void midRefreshWithNoBodyIsGeneratingWithoutAFlight() {
        refreshRunning();
        RecapView v = view();
        assertEquals(State.GENERATING, v.state());
        assertNull(v.headline());
        svc.awaitFlights();
        assertEquals(0, client.calls.get());
        assertEquals(0, recaps.calls.size());
    }

    @Test
    void midRefreshServesTheStoredBodyStale() {
        RecapView first = generate();
        current = bumped(current);
        refreshRunning();
        RecapView v = view();
        assertTrue(v.stale());
        assertEquals(first.headline(), v.headline());
        assertEquals(first.model(), v.model());
        svc.awaitFlights();
        assertEquals(1, client.calls.get(), "no generation while the chain refreshes");
    }

    // ---- fix pass (code-review.md R4..R9) ---------------------------------------------------------

    private static Result mapPerformers(Result r, UnaryOperator<Performer> f) {
        List<Performer> ps = r.topPerformers().stream().map(f).toList();
        return new Result(r.available(), r.reason(), r.season(), r.requestedSeason(), r.week(), r.sport(),
                r.playersPlayMultiplePerPeriod(), r.matchups(), ps, r.bestNights(), r.bestWeek(), r.basis(),
                r.sectionsUnavailable(), r.awards(), r.awardsOmitted(), r.latestScoredWeek(),
                r.latestFinalWeek(), r.weekFinal());
    }

    @Test
    void aPositionOrOpponentChangeIsReportChangedNotAScoringCorrection() {
        generate();
        current = mapPerformers(current, p -> new Performer(p.playerId(), p.playerName(), "ZZ", p.teamName(),
                p.points(), p.team(), "ZZZ", p.isAway(), p.avatarId()));
        RecapView v = generate();
        assertEquals(State.READY, v.state(), String.valueOf(recaps.rows.values()));
        assertEquals(2, v.revision());
        assertEquals("REPORT_CHANGED", v.revisionReason());
    }

    @Test
    void aRenameThatAddsADigitInsideAwardProseIsStillNamesChanged() {
        generate();
        current = renamed(current, "Likely Have Downs", "Likely Have Downs 2");
        assertEquals("NAMES_CHANGED", generate().revisionReason());
    }

    // R6: one request-shape rule for generate and preview

    private RecapClient spyClient() {
        RecapClient spy = mock(RecapClient.class);
        when(spy.call(anyString(), anyInt(), anyString(), anyString(), org.mockito.ArgumentMatchers.anyBoolean()))
                .thenReturn(new RecapCallResult("end_turn", null, null, 1, 1, 1));
        when(provider.getIfAvailable()).thenReturn(spy);
        return spy;
    }

    @Test
    void generateWithASonnetModelSendsLowEffortAndTheBigTokenBudget() {
        RecapClient spy = spyClient();
        svc = service(props("claude-sonnet-5-5", 10, 50));
        view();
        svc.awaitFlights();
        org.mockito.Mockito.verify(spy).call(org.mockito.ArgumentMatchers.eq("claude-sonnet-5-5"),
                org.mockito.ArgumentMatchers.eq(16_000), anyString(), anyString(), org.mockito.ArgumentMatchers.eq(true));
    }

    @Test
    void aDatedHaikuIdIsStillHaikuForGenerate() {
        RecapClient spy = spyClient();
        svc = service(props("claude-haiku-4-5-20251001", 10, 50));
        view();
        svc.awaitFlights();
        org.mockito.Mockito.verify(spy).call(org.mockito.ArgumentMatchers.eq("claude-haiku-4-5-20251001"),
                org.mockito.ArgumentMatchers.eq(2048), anyString(), anyString(), org.mockito.ArgumentMatchers.eq(false));
        assertTrue(RecapService.isHaikuModel("claude-haiku-4-5"));
        assertFalse(RecapService.isHaikuModel("claude-sonnet-5-5"));
    }

    // R7: an exception after a paid call still records an attempt

    @Test
    void aPersistenceErrorAfterTheCallRecordsATransientAttemptSoPollsDoNotRepay() {
        recaps.failNextWriteReady = true;
        RecapView v = generate();
        assertEquals(State.FAILED, v.state());
        assertEquals("API_ERROR", v.failureReason());
        assertEquals(1, client.calls.get());
        assertNotNull(recaps.find(LEAGUE, 3).orElseThrow().retryAfter());

        assertEquals(State.FAILED, view().state());
        assertEquals(State.FAILED, view().state());
        assertEquals(1, client.calls.get(), "polls inside retry_after do not start a new call");
    }

    // R8: a flight that finds its key already READY does nothing

    @Test
    void aFlightWhoseKeyIsAlreadyReadyMakesNoCallAndNoRevisionBump() {
        generate();
        assertEquals(1, client.calls.get());
        recaps.hideNextFind = true; // the GET read just before the earlier flight stored its body
        view();
        svc.awaitFlights();
        assertEquals(1, client.calls.get(), "no second paid call");
        assertEquals(1, recaps.find(LEAGUE, 3).orElseThrow().revision(), "no revision bump");
        assertEquals(1, recaps.rowsWritten);
    }

    // R9: cap check and log are one step

    @Test
    void twoFlightsRacingForTheLastCallOnTheGlobalCapMakeOnlyOne() {
        long other = 212L;
        features.granted.add(other);
        var row2 = new LeagueRepository.LeagueRow(other, Sport.NFL, "L2", "Test2", 2026, 12, List.of(), 0.5, null, null);
        when(resolver.resolve("L2")).thenReturn(Optional.of(new LeagueSeasonResolver.Resolved(row2, null)));
        svc = service(props(MODEL, 10, 1));
        recaps.raceBarrier = new java.util.concurrent.CyclicBarrier(2);

        svc.view("L1", 3);
        svc.view("L2", 3);
        svc.awaitFlights();

        assertEquals(1, client.calls.get());
        assertEquals(1, recaps.calls.size(), "the cap is 1: never 2");
    }
}
