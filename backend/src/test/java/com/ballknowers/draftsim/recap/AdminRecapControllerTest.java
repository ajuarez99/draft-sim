package com.ballknowers.draftsim.recap;

import com.ballknowers.draftsim.domain.Sport;
import com.ballknowers.draftsim.engine.LeagueSeasonResolver;
import com.ballknowers.draftsim.engine.WeeklyReportService;
import com.ballknowers.draftsim.refresh.LeagueRefreshService;
import com.ballknowers.draftsim.store.LeagueRecapRepository;
import com.ballknowers.draftsim.store.LeagueRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.http.ResponseEntity;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

/** The operator's regenerate and preview routes (specs/020-ai-weekly-recap T031). */
class AdminRecapControllerTest {

    private static final long LEAGUE = 211L;

    private RecapServiceTest.FakeRecaps fake;
    private RecapServiceTest.FakeClient client;
    private ObjectProvider<RecapClient> provider;
    private LeagueSeasonResolver resolver;
    private WeeklyReportService weekly;
    private RecapServiceTest.TestClock clock;
    private RecapProperties props;

    @SuppressWarnings("unchecked")
    @BeforeEach
    void setUp() {
        fake = new RecapServiceTest.FakeRecaps();
        client = new RecapServiceTest.FakeClient();
        clock = new RecapServiceTest.TestClock();
        props = new RecapProperties(true, "claude-haiku-4-5", 10, 50, 15, 60, 2048);
        provider = mock(ObjectProvider.class);
        when(provider.getIfAvailable()).thenReturn(client);
        var row = new LeagueRepository.LeagueRow(LEAGUE, Sport.NFL, "L1", "Test", 2026, 12, List.of(), 0.5, null, null);
        resolver = mock(LeagueSeasonResolver.class);
        when(resolver.resolve(anyString())).thenReturn(Optional.of(new LeagueSeasonResolver.Resolved(row, null)));
        weekly = mock(WeeklyReportService.class);
        when(weekly.forWeek(anyString(), anyInt(), isNull())).thenReturn(Optional.of(RecapFixtures.nfl()));
    }

    private RecapService service(LeagueRecapRepository repo) {
        var features = new RecapServiceTest.FakeFeatures();
        features.granted.add(LEAGUE);
        return new RecapService(provider, weekly, resolver, features, repo, mock(LeagueRefreshService.class),
                props, new RecapInputBuilder(), clock);
    }

    private AdminRecapController admin(RecapService svc, LeagueRecapRepository repo) {
        return new AdminRecapController(resolver, new RecapServiceTest.FakeFeatures(), repo, svc);
    }

    @Test
    void regenerateClearsAFailedAttemptSoTheNextGetGenerates() {
        RecapService svc = service(fake);
        AdminRecapController admin = admin(svc, fake);
        client.script.add(new RecapCallResult("refusal", null, null, 100, 0, 5));
        svc.view("L1", 3);
        svc.awaitFlights();
        assertEquals(RecapView.State.FAILED, svc.view("L1", 3).state());
        assertEquals(1, client.calls.get());

        assertEquals(204, admin.regenerate("L1", 3).getStatusCode().value());

        assertEquals(RecapView.State.GENERATING, svc.view("L1", 3).state());
        svc.awaitFlights();
        assertEquals(2, client.calls.get());
        assertEquals(RecapView.State.READY, svc.view("L1", 3).state());
    }

    @Test
    void regenerateLeavesTheReadyBodyAlone() {
        RecapService svc = service(fake);
        svc.view("L1", 3);
        svc.awaitFlights();
        String headline = fake.find(LEAGUE, 3).orElseThrow().headline();
        admin(svc, fake).regenerate("L1", 3);
        assertEquals(headline, fake.find(LEAGUE, 3).orElseThrow().headline());
        assertEquals(RecapView.State.READY, svc.view("L1", 3).state());
    }

    @Test
    void rerollPullsAReadyBodySoTheNextGetGeneratesFresh() {
        RecapService svc = service(fake);
        AdminRecapController admin = admin(svc, fake);
        svc.view("L1", 3);
        svc.awaitFlights();
        assertEquals(RecapView.State.READY, svc.view("L1", 3).state());

        assertEquals(204, admin.reroll("L1", 3).getStatusCode().value());
        assertEquals(null, fake.find(LEAGUE, 3).orElseThrow().readyKey());

        assertEquals(RecapView.State.GENERATING, svc.view("L1", 3).state());
        svc.awaitFlights();
        assertEquals(2, client.calls.get());
        RecapView v = svc.view("L1", 3);
        assertEquals(RecapView.State.READY, v.state());
        assertEquals(2, v.revision());
    }

    @Test
    void previewIsRefusedWith429WhenTheCapsAreSpent() {
        props = new RecapProperties(true, "claude-haiku-4-5", 10, 1, 15, 60, 2048);
        fake.logCall(999L, clock.instant());
        RecapService svc = service(fake);
        ResponseEntity<Map<String, Object>> res = admin(svc, fake).preview("L1", 3, "claude-sonnet-5-5");
        assertEquals(429, res.getStatusCode().value());
        assertEquals("rate_limited", res.getBody().get("error"));
        assertEquals(0, client.calls.get());
    }

    @Test
    void aDatedHaikuIdGetsNoEffortInPreviewEither() {
        RecapClient spy = mock(RecapClient.class);
        when(spy.call(anyString(), anyInt(), anyString(), anyString(), anyBoolean()))
                .thenReturn(new RecapCallResult("end_turn", null, null, 1, 1, 1));
        when(provider.getIfAvailable()).thenReturn(spy);
        admin(service(fake), fake).preview("L1", 3, "claude-haiku-4-5-20251001");
        verify(spy).call(eq("claude-haiku-4-5-20251001"), eq(2048), anyString(), anyString(), eq(false));
    }

    @Test
    void previewNeverReadsOrWritesTheRecapRowsButLogsOneCall() {
        LeagueRecapRepository repo = mock(LeagueRecapRepository.class);
        RecapService svc = service(repo);

        ResponseEntity<Map<String, Object>> res = admin(svc, repo).preview("L1", 3, "claude-sonnet-5-5");

        assertEquals(200, res.getStatusCode().value());
        Map<String, Object> body = res.getBody();
        assertNotNull(body);
        assertTrue(body.keySet().containsAll(List.of("output", "grounding", "usage", "stopReason", "latencyMs")));
        assertNotNull(body.get("grounding"));
        verify(repo).callsSince(eq(LEAGUE), org.mockito.ArgumentMatchers.any()); // the cap check (R9)
        verify(repo).callsSinceGlobal(org.mockito.ArgumentMatchers.any());
        verify(repo).logCall(eq(LEAGUE), org.mockito.ArgumentMatchers.any());
        verifyNoMoreInteractions(repo); // no find, no writeReady, no writeFailure, no clearAttempt
        assertEquals(1, client.calls.get());
    }

    @Test
    void previewSendsLowEffortAndTheBigTokenBudgetOnlyForNonHaikuModels() {
        RecapClient spy = mock(RecapClient.class);
        when(spy.call(anyString(), anyInt(), anyString(), anyString(), anyBoolean()))
                .thenReturn(new RecapCallResult("end_turn", null, null, 1, 1, 1));
        when(provider.getIfAvailable()).thenReturn(spy);
        RecapService svc = service(fake);
        AdminRecapController admin = admin(svc, fake);

        admin.preview("L1", 3, "claude-sonnet-5-5");
        verify(spy).call(eq("claude-sonnet-5-5"), eq(16_000), anyString(), anyString(), eq(true));
        admin.preview("L1", 3, "claude-haiku-4-5");
        verify(spy).call(eq("claude-haiku-4-5"), eq(2048), anyString(), anyString(), eq(false));
        assertTrue(fake.rows.isEmpty());
    }

    @Test
    void previewWithoutAClientBeanIs409RecapDisabled() {
        when(provider.getIfAvailable()).thenReturn(null);
        LeagueRecapRepository repo = mock(LeagueRecapRepository.class);
        ResponseEntity<Map<String, Object>> res = admin(service(repo), repo).preview("L1", 3, "claude-haiku-4-5");
        assertEquals(409, res.getStatusCode().value());
        assertEquals("recap_disabled", res.getBody().get("error"));
        verifyNoInteractions(repo);
    }
}
