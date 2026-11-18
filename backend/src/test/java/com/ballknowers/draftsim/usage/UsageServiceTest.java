package com.ballknowers.draftsim.usage;

import com.ballknowers.draftsim.config.OwnerProperties;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/** The collection path with a mocked repository: what costs a database connection and what does not. */
class UsageServiceTest {

    private static final ZoneId ET = ZoneId.of("America/New_York");
    private static final LocalDate DAY = LocalDate.of(1990, 3, 12);

    private final MutableClock clock = new MutableClock(Instant.parse("1990-03-12T17:00:00Z"), ET);
    private final UsageRepository repo = mock(UsageRepository.class);
    private UsageService service;
    private String route;

    @BeforeEach
    void setUp() {
        UsageVocabulary vocab = new UsageVocabulary();
        route = vocab.routes("leagues").stream().sorted().findFirst().orElseThrow();
        service = new UsageService(vocab, new UsageLimiter(clock), repo, new OwnerProperties(null), clock);
    }

    private static byte[] b(String s) {
        return s.getBytes(StandardCharsets.UTF_8);
    }

    private byte[] view() {
        return b("{\"kind\":\"view\",\"site\":\"leagues\",\"route\":\"" + route + "\"}");
    }

    @Test
    void aValidViewIsOneUpsertOnTheEasternDay() {
        service.collect(view(), "Mozilla/5.0", "1.1.1.1");
        verify(repo).increment(DAY, "leagues", "view", route, "", "", "", "");
    }

    @Test
    void theDayIsTheEasternDayNotTheUtcDay() {
        clock.set(Instant.parse("1990-03-13T03:30:00Z")); // 22:30 on the 12th in New York
        service.collect(view(), "Mozilla/5.0", "1.1.1.1");
        verify(repo).increment(eq(DAY), any(), any(), any(), any(), any(), any(), any());
    }

    @Test
    void refusedSendsNeverTouchTheRepository() {
        service.collect(b("{\"site\":\"leagues\",\"route\":\"/nope\"}"), "Mozilla/5.0", "1.1.1.1");  // vocabulary
        service.collect(b("{\"site\":\"nope\",\"route\":\"/\"}"), "Mozilla/5.0", "1.1.1.1");           // unknown site
        service.collect(b("not json"), "Mozilla/5.0", "1.1.1.1");                                       // unparsable
        service.collect(new byte[2000], "Mozilla/5.0", "1.1.1.1");                                      // oversize
        service.collect(null, "Mozilla/5.0", "1.1.1.1");                                                // controller said too big
        service.collect(view(), "Googlebot/2.1", "1.1.1.1");                                            // crawler
        service.collect(view(), "HeadlessChrome/120", "1.1.1.1");
        verifyNoInteractions(repo);
    }

    @Test
    void aLimitedAddressCostsNoConnectionAndJunkCountsTowardTheLimit() {
        for (int i = 0; i < UsageLimiter.PER_ADDRESS_PER_WINDOW; i++) {
            service.collect(b("{\"site\":\"leagues\",\"route\":\"/junk\"}"), "Mozilla/5.0", "9.9.9.9");
        }
        service.collect(view(), "Mozilla/5.0", "9.9.9.9");   // the 61st call: limited though it is valid
        verifyNoInteractions(repo);
    }

    @Test
    void theGlobalCeilingStopsWritesFromManyAddresses() {
        for (int i = 0; i < UsageLimiter.GLOBAL_WRITES_PER_WINDOW + 40; i++) {
            service.collect(view(), "Mozilla/5.0", "10.0." + (i / 200) + "." + (i % 200));
        }
        verify(repo, times(UsageLimiter.GLOBAL_WRITES_PER_WINDOW))
                .increment(any(), anyString(), anyString(), anyString(), anyString(), anyString(), anyString(), anyString());
    }

    @Test
    void rejectedSendsAreCountedInMemoryAndWrittenOncePerMinuteByAnAcceptedSend() {
        for (int i = 0; i < 5; i++) {
            service.collect(b("{\"site\":\"leagues\",\"route\":\"/junk\"}"), "Mozilla/5.0", "2.2.2." + i);
        }
        service.collect(b("{\"site\":\"timemachine\",\"route\":\"/junk\"}"), "Mozilla/5.0", "2.2.3.1");
        verifyNoInteractions(repo);                        // nothing written yet, no connection taken

        service.collect(view(), "Mozilla/5.0", "3.3.3.1");  // first accepted send: the flush is due
        verify(repo).addRejected(DAY, "leagues", 5);
        verify(repo).addRejected(DAY, "timemachine", 1);

        service.collect(b("{\"site\":\"leagues\",\"route\":\"/junk\"}"), "Mozilla/5.0", "2.2.4.1");
        service.collect(view(), "Mozilla/5.0", "3.3.3.2");  // 0 seconds later: not due
        verify(repo, times(2)).addRejected(any(), anyString(), anyLong());   // still only the first two

        clock.advance(Duration.ofSeconds(61));
        service.collect(view(), "Mozilla/5.0", "3.3.3.3");  // due again
        verify(repo).addRejected(DAY, "leagues", 1);
    }

    @Test
    void anUnknownLeagueIsRejectedNotStored() {
        String id = "{\"kind\":\"league-view\",\"site\":\"leagues\",\"route\":\"" + route + "\",\"league\":\"99999\"}";
        when(repo.leagueSport("99999")).thenReturn(Optional.empty());
        service.collect(b(id), "Mozilla/5.0", "4.4.4.4");
        verify(repo, never()).increment(any(), any(), any(), any(), any(), any(), any(), any());
        service.collect(view(), "Mozilla/5.0", "4.4.4.5");
        verify(repo).addRejected(DAY, "leagues", 1);
    }

    @Test
    void aKnownLeagueStoresTheSportFromTheTableLowercased() {
        when(repo.leagueSport("12345")).thenReturn(Optional.of("NFL"));
        service.collect(b("{\"site\":\"leagues\",\"route\":\"" + route + "\",\"league\":\"12345\"}"), "Mozilla/5.0", "5.5.5.5");
        verify(repo).increment(DAY, "leagues", "league-view", route, "", "", "12345", "nfl");
    }

    @Test
    void aDatabaseFailureNeverEscapes() {
        Mockito.doThrow(new org.springframework.dao.DataAccessResourceFailureException("pool exhausted"))
                .when(repo).increment(any(), any(), any(), any(), any(), any(), any(), any());
        assertDoesNotThrow(() -> service.collect(view(), "Mozilla/5.0", "6.6.6.6"));
        assertDoesNotThrow(() -> service.collect(view(), "Mozilla/5.0", "6.6.6.7"));
    }

    @Test
    void anEmptyStoreReportsNullsAndEmptyLists() {
        when(repo.countingSince()).thenReturn(List.of());
        when(repo.gamesSince()).thenReturn(Optional.empty());
        when(repo.viewsByDay(any(), any())).thenReturn(List.of());
        when(repo.viewsByRoute(any(), any())).thenReturn(List.of());
        when(repo.leagueViews(any(), any())).thenReturn(List.of());
        when(repo.events(any(), any())).thenReturn(List.of());
        when(repo.funnel(any(), any(), any())).thenReturn(List.of());
        UsageReport r = service.report(DAY.minusDays(29), DAY);
        assertTrue(r.countingSince().isEmpty());
        assertNull(r.gamesSince());
        assertFalse(r.ownerConfigured());
        assertEquals("America/New_York", r.timezone());
        assertTrue(r.viewsByDay().isEmpty() && r.viewsByRoute().isEmpty() && r.leagueViews().isEmpty()
                && r.events().isEmpty() && r.funnel().isEmpty());
        assertEquals(0, r.rejected());
    }

    @Test
    void aLeagueViewForADeletedLeagueKeepsANullNameAndEventPropsComeBackAsAMap() {
        when(repo.countingSince()).thenReturn(List.of());
        when(repo.gamesSince()).thenReturn(Optional.empty());
        when(repo.viewsByDay(any(), any())).thenReturn(List.of());
        when(repo.viewsByRoute(any(), any())).thenReturn(List.of());
        when(repo.leagueViews(any(), any())).thenReturn(List.of(new UsageRepository.LeagueViews("1", null, "NBA", 3)));
        when(repo.events(any(), any())).thenReturn(
                List.of(new UsageRepository.EventCount("timemachine", "result-shared", "daily=yes;sport=nba;who=guest", 2)));
        when(repo.funnel(any(), any(), any())).thenReturn(List.of());
        UsageReport r = service.report(DAY, DAY);
        assertNull(r.leagueViews().get(0).leagueName());
        assertEquals("nba", r.leagueViews().get(0).sport());
        assertEquals("nba", r.events().get(0).props().get("sport"));
        assertEquals(3, r.events().get(0).props().size());
    }
}
