package com.ballknowers.draftsim.ingest;

import com.ballknowers.draftsim.domain.Sport;
import com.ballknowers.draftsim.store.BoardRepository;
import com.ballknowers.draftsim.store.LeagueRepository;
import com.ballknowers.draftsim.store.PlayerRepository;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.io.InputStream;
import java.time.LocalDate;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Spec 046 T017: the Sleeper dynasty/redraft capture, against the REAL measured nba/2026 season payload
 * (fixtures/046/sleeper-nba-2026-season.json: 2,132 rows, 817 with adp_dynasty below 999, 903 with adp_std
 * below 999, 334 of the dynasty rows being ADP-only stubs with no stat line).
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class SleeperAdpCaptureServiceTest {

    @Mock private SleeperProjectionClient client;
    @Mock private PlayerRepository players;
    @Mock private BoardRepository boards;
    @Mock private LeagueRepository leagues;

    private SleeperAdpCaptureService service;
    private List<Map<String, Object>> payload;
    private Map<String, Long> ids;

    @BeforeEach
    void setUp() throws Exception {
        service = new SleeperAdpCaptureService(client, players, boards, leagues);
        when(leagues.all()).thenReturn(List.of(nbaLeague(2025), nbaLeague(2026)));
        try (InputStream in = getClass().getResourceAsStream("/fixtures/046/sleeper-nba-2026-season.json")) {
            payload = new ObjectMapper().readValue(in, new TypeReference<List<Map<String, Object>>>() {});
        }
        ids = new HashMap<>();
        long next = 1;
        for (Map<String, Object> r : payload) ids.put(String.valueOf(r.get("player_id")), next++);
        when(players.idsBySleeperId(Sport.NBA)).thenReturn(ids);
        when(boards.latestCapture(any(), anyString())).thenReturn(Optional.empty());
    }

    private static LeagueRepository.LeagueRow nbaLeague(int season) {
        return new LeagueRepository.LeagueRow(season, Sport.NBA, "L" + season, "Ball Knowers", season, 12,
                List.of("PG"), 0.0, null, "in_season");
    }

    @SuppressWarnings("unchecked")
    private List<BoardRepository.CaptureRow> written(String source, String scoring) {
        ArgumentCaptor<List<BoardRepository.CaptureRow>> rows = ArgumentCaptor.forClass(List.class);
        verify(boards).saveCapture(eq(Sport.NBA), eq(source), any(LocalDate.class), eq(scoring), rows.capture(), eq(List.of()));
        return rows.getValue();
    }

    @Test
    void writesEveryRankedRowIncludingTheStubsThatHaveNoStatLine() {
        var r = service.capture(Sport.NBA, 2026, payload);

        assertEquals(817, r.dynastyRows());
        assertEquals(903, r.redraftRows());
        assertEquals(0, r.unmapped());
        assertNull(r.rejectedReason());
        List<BoardRepository.CaptureRow> dyn = written("sleeper_dynasty", "dynasty");
        assertEquals(817, dyn.size());
        // The stubs are the point (research R1): ranked by dynasty drafters, absent from the projection ingest.
        long stubs = payload.stream()
                .filter(p -> ((Map<?, ?>) p.get("stats")).get("pts") == null
                        && ((Map<?, ?>) p.get("stats")).get("adp_dynasty") instanceof Number n && n.doubleValue() < 999)
                .count();
        assertEquals(334, stubs);
        assertEquals(903, written("sleeper_redraft", "redraft").size());
    }

    @Test
    void anUndraftedNineNineNineIsNeverWritten() {
        service.capture(Sport.NBA, 2026, payload);

        assertTrue(written("sleeper_dynasty", "dynasty").stream().allMatch(r -> r.adp() < 999 && r.adp() > 0));
        // Yahoo-only columns stay null on Sleeper rows.
        assertTrue(written("sleeper_dynasty", "dynasty").stream().allMatch(r -> r.avgCost() == null && r.percentDrafted() == null));
    }

    @Test
    void unmappedSleeperIdsAreCountedNotWritten() {
        ids.remove(String.valueOf(payload.get(0).get("player_id")));

        var r = service.capture(Sport.NBA, 2026, payload);

        assertTrue(r.unmapped() >= 1);
        assertTrue(r.dynastyRows() < 817 || r.redraftRows() < 903);
    }

    @Test
    void aCaptureThatWouldShrinkBelowHalfOfTheStoredOneIsRefusedAndTheStoredOneStays() {
        LocalDate yesterday = LocalDate.now().minusDays(1);
        when(boards.latestCapture(Sport.NBA, "sleeper_dynasty")).thenReturn(Optional.of(yesterday));
        when(boards.captureSize(Sport.NBA, "sleeper_dynasty", yesterday)).thenReturn(2000);   // 817 < 0.5 * 2000

        var r = service.capture(Sport.NBA, 2026, payload);

        assertEquals(0, r.dynastyRows());
        assertEquals(903, r.redraftRows(), "the redraft source has its own guard and its own stored size");
        assertNotNull(r.rejectedReason());
        assertTrue(r.rejectedReason().contains("sleeper_dynasty"));
        verify(boards, never()).saveCapture(any(), eq("sleeper_dynasty"), any(), anyString(), anyList(), anyList());
    }

    @Test
    void theGuardReusesTheProjectionIngestsConstantNotACopy() {
        // Exactly half is allowed (strict less-than), so 817 against 1634 stored writes.
        LocalDate yesterday = LocalDate.now().minusDays(1);
        when(boards.latestCapture(Sport.NBA, "sleeper_dynasty")).thenReturn(Optional.of(yesterday));
        when(boards.captureSize(Sport.NBA, "sleeper_dynasty", yesterday)).thenReturn(1634);
        assertEquals(0.5, SeasonProjectionIngestService.MIN_KEPT_SHARE_OF_STORED);

        assertEquals(817, service.capture(Sport.NBA, 2026, payload).dynastyRows());
    }

    @Test
    void anEmptyPayloadNeverWritesAnEmptyCaptureOverARealOne() {
        var r = service.capture(Sport.NBA, 2026, List.of());

        assertEquals(0, r.dynastyRows());
        verify(boards, never()).saveCapture(any(), anyString(), any(), anyString(), anyList(), anyList());
    }

    @Test
    void fetchAndCaptureReadsTheSeasonThenCaptures() {
        when(client.season("nba", 2026)).thenReturn(payload);

        assertEquals(817, service.fetchAndCapture(Sport.NBA, 2026).dynastyRows());
    }

    @Test
    void aMissingBodyKeepsTheStoredCapturesAndSaysSo() {
        when(client.season("nba", 2026)).thenReturn(null);

        var r = service.fetchAndCapture(Sport.NBA, 2026);

        assertEquals("Sleeper returned no body", r.rejectedReason());
        verify(boards, never()).saveCapture(any(), anyString(), any(), anyString(), anyList(), anyList());
    }

    @Test
    void footballIsRefused() {
        assertThrows(IllegalArgumentException.class, () -> service.capture(Sport.NFL, 2026, payload));
    }

    // ---- Spec 046 C3: a capture is stored as TODAY's, so it must be the compare page's season ----

    @Test
    void aCaptureForAnotherSeasonWritesNothingAndLeavesTodaysCaptureIntact() {
        // 2025's payload would pass the shrink guard (530 vs 817 stored) -- the season rule is what stops it.
        var r = service.capture(Sport.NBA, 2025, payload);

        assertEquals(0, r.dynastyRows());
        assertEquals(0, r.redraftRows());
        assertNotNull(r.rejectedReason());
        assertTrue(r.rejectedReason().contains("2025") && r.rejectedReason().contains("2026"));
        verify(boards, never()).saveCapture(any(), anyString(), any(), anyString(), anyList(), anyList());
    }

    @Test
    void fetchAndCaptureForAnotherSeasonDoesNotEvenFetch() {
        var r = service.fetchAndCapture(Sport.NBA, 2025);

        assertNotNull(r.rejectedReason());
        verify(client, never()).season(anyString(), org.mockito.ArgumentMatchers.anyInt());
        verify(boards, never()).saveCapture(any(), anyString(), any(), anyString(), anyList(), anyList());
    }

    @Test
    void withNoNbaLeagueNoSeasonIsTheCompareSeasonAndNothingIsWritten() {
        when(leagues.all()).thenReturn(List.of());

        assertNotNull(service.capture(Sport.NBA, 2026, payload).rejectedReason());
        verify(boards, never()).saveCapture(any(), anyString(), any(), anyString(), anyList(), anyList());
    }

    // ---- Spec 046 C11: force bypasses the shrink guard only ----

    @Test
    void forceWritesACaptureTheShrinkGuardWouldRefuse() {
        LocalDate yesterday = LocalDate.now().minusDays(1);
        when(boards.latestCapture(Sport.NBA, "sleeper_dynasty")).thenReturn(Optional.of(yesterday));
        when(boards.captureSize(Sport.NBA, "sleeper_dynasty", yesterday)).thenReturn(2000);

        assertEquals(0, service.capture(Sport.NBA, 2026, payload).dynastyRows());
        assertEquals(817, service.capture(Sport.NBA, 2026, payload, true).dynastyRows());
    }

    @Test
    void forceDoesNotBypassTheSeasonRule() {
        var r = service.capture(Sport.NBA, 2025, payload, true);

        assertNotNull(r.rejectedReason());
        verify(boards, never()).saveCapture(any(), anyString(), any(), anyString(), anyList(), anyList());
    }
}
