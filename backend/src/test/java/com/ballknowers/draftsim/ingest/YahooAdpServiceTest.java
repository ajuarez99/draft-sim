package com.ballknowers.draftsim.ingest;

import com.ballknowers.draftsim.domain.Player;
import com.ballknowers.draftsim.domain.Position;
import com.ballknowers.draftsim.domain.Sport;
import com.ballknowers.draftsim.store.BoardRepository;
import com.ballknowers.draftsim.store.PlayerRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** Spec 046 T020: matching, the unmatched rows, the shrink guard, and "a failed fetch keeps the previous capture". */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class YahooAdpServiceTest {

    @Mock private YahooAdpClient client;
    @Mock private PlayerRepository players;
    @Mock private BoardRepository boards;

    private YahooAdpService service;

    private static Player nba(long id, String name, Position... pos) {
        return new Player(id, Sport.NBA, "s" + id, name, List.of(pos), "BOS", "Active", null, 25, 3);
    }

    private static YahooAdpClient.Player yahoo(String name, String pos, double adp, Double cost, Double pct) {
        return new YahooAdpClient.Player(name, "BOS", pos, adp, cost, pct);
    }

    @BeforeEach
    void setUp() {
        service = new YahooAdpService(client, players, boards);
        when(players.findAll(Sport.NBA)).thenReturn(List.of(
                nba(1, "Nikola Jokic", Position.C),
                nba(2, "Luka Doncic", Position.PG, Position.SG),
                nba(3, "Jaren Jackson Jr.", Position.PF, Position.C)));
        when(boards.latestCapture(any(), anyString())).thenReturn(Optional.empty());
    }

    @SuppressWarnings("unchecked")
    @Test
    void matchesByNormalisedNameAndAnyOfYahoosPositionsAndStoresTheRestAsUnmatched() {
        when(client.fetchAll()).thenReturn(List.of(
                yahoo("Nikola Jokić", "C", 1.4, 77.0, 1.0),          // accent
                yahoo("Luka Dončić", "SG,PG", 3.0, 70.0, 1.0),  // accent + position list in another order
                yahoo("Jaren Jackson Jr", "PF,C", 20.0, 25.0, 0.9),       // suffix punctuation
                yahoo("Nobody Known", "PG", 90.0, 2.0, 0.6)));

        var r = service.ingest(Sport.NBA);

        assertNull(r.rejectedReason());
        assertEquals(4, r.fetched());
        assertEquals(3, r.matched());
        assertEquals(3, r.rows());
        assertEquals(1, r.unmatched());
        ArgumentCaptor<List<BoardRepository.CaptureRow>> rows = ArgumentCaptor.forClass(List.class);
        ArgumentCaptor<List<BoardRepository.UnmatchedRow>> miss = ArgumentCaptor.forClass(List.class);
        verify(boards).saveCapture(eq(Sport.NBA), eq("yahoo"), any(LocalDate.class), eq("redraft"), rows.capture(), miss.capture());
        assertEquals(List.of(1L, 2L, 3L), rows.getValue().stream().map(BoardRepository.CaptureRow::playerId).toList());
        assertEquals(77.0, rows.getValue().get(0).avgCost());
        assertEquals(1.0, rows.getValue().get(0).percentDrafted());
        assertEquals(1, miss.getValue().size());
        assertEquals("Nobody Known", miss.getValue().get(0).name());
        assertEquals(90.0, miss.getValue().get(0).adp());
        assertEquals(2.0, miss.getValue().get(0).avgCost());
    }

    @Test
    void aClientThatThrowsKeepsThePreviousCaptureUntouched() {
        when(client.fetchAll()).thenThrow(new IllegalStateException("Yahoo response had no fantasy_content.league.players"));

        var r = service.ingest(Sport.NBA);

        assertNotNull(r.rejectedReason());
        assertTrue(r.rejectedReason().contains("Yahoo fetch failed"));
        assertEquals(0, r.rows());
        verify(boards, never()).saveCapture(any(), anyString(), any(), anyString(), anyList(), anyList());
    }

    @Test
    void anEmptyFetchIsRefusedNotWrittenOverTheRealCapture() {
        when(client.fetchAll()).thenReturn(List.of());

        var r = service.ingest(Sport.NBA);

        assertNotNull(r.rejectedReason());
        verify(boards, never()).saveCapture(any(), anyString(), any(), anyString(), anyList(), anyList());
    }

    @Test
    void aCaptureThatWouldKeepUnderHalfOfTheStoredRowsIsRefused() {
        LocalDate yesterday = LocalDate.now().minusDays(1);
        when(boards.latestCapture(Sport.NBA, "yahoo")).thenReturn(Optional.of(yesterday));
        when(boards.captureSize(Sport.NBA, "yahoo", yesterday)).thenReturn(186);
        when(client.fetchAll()).thenReturn(List.of(yahoo("Nikola Jokic", "C", 1.4, 77.0, 1.0)));

        var r = service.ingest(Sport.NBA);

        assertTrue(r.rejectedReason().contains("kept the stored capture"));
        verify(boards, never()).saveCapture(any(), anyString(), any(), anyString(), anyList(), anyList());
    }

    @SuppressWarnings("unchecked")
    @Test
    void twoYahooEntriesForOnePlayerKeepTheBetterAdpAndSendTheOtherToUnmatched() {
        when(client.fetchAll()).thenReturn(List.of(
                yahoo("Nikola Jokic", "C", 1.4, 77.0, 1.0),
                yahoo("Nikola Jokic", "C", 55.0, 9.0, 0.2)));

        var r = service.ingest(Sport.NBA);

        // Spec 046 C4: never silently dropped -- fetched 2 = 1 row + 1 unmatched.
        assertEquals(1, r.rows());
        assertEquals(1, r.unmatched());
        ArgumentCaptor<List<BoardRepository.CaptureRow>> rows = ArgumentCaptor.forClass(List.class);
        ArgumentCaptor<List<BoardRepository.UnmatchedRow>> miss = ArgumentCaptor.forClass(List.class);
        verify(boards).saveCapture(eq(Sport.NBA), eq("yahoo"), any(LocalDate.class), eq("redraft"), rows.capture(), miss.capture());
        assertEquals(1.4, rows.getValue().get(0).adp());
        assertEquals(55.0, miss.getValue().get(0).adp());
    }

    private static Player nbaOn(long id, String name, String team, String status, Position... pos) {
        return new Player(id, Sport.NBA, "s" + id, name, List.of(pos), team, status, null, 25, 3);
    }

    /** Spec 046 C4: Jaylin Williams exists twice (OKC C, and a free-agent PF); the old matcher took whichever came first. */
    @SuppressWarnings("unchecked")
    @Test
    void twoSameNamePlayersOnDifferentTeamsAreTiedBrokenByTeamNotByFirstSeen() {
        when(players.findAll(Sport.NBA)).thenReturn(List.of(
                nbaOn(10, "Jaylin Williams", null, "Inactive", Position.PF, Position.SF),   // free agent, listed first
                nbaOn(11, "Jaylin Williams", "OKC", "Active", Position.C)));
        when(client.fetchAll()).thenReturn(List.of(
                new YahooAdpClient.Player("Jaylin Williams", "OKC", "PF,C", 150.0, 1.0, 0.1)));

        service.ingest(Sport.NBA);

        ArgumentCaptor<List<BoardRepository.CaptureRow>> rows = ArgumentCaptor.forClass(List.class);
        verify(boards).saveCapture(eq(Sport.NBA), eq("yahoo"), any(LocalDate.class), eq("redraft"), rows.capture(), eq(List.of()));
        assertEquals(List.of(11L), rows.getValue().stream().map(BoardRepository.CaptureRow::playerId).toList());
    }

    @SuppressWarnings("unchecked")
    @Test
    void aSharedNameTheTeamCannotSplitIsUnmatchedNeverTheFirstId() {
        when(players.findAll(Sport.NBA)).thenReturn(List.of(
                nbaOn(10, "Chris Smith", "LAL", "Active", Position.SF),
                nbaOn(11, "Chris Smith", "BKN", "Active", Position.SF)));
        when(client.fetchAll()).thenReturn(List.of(
                new YahooAdpClient.Player("Chris Smith", "MIA", "SF", 140.0, 1.0, 0.1)));

        var r = service.ingest(Sport.NBA);

        assertEquals(0, r.rows());
        assertEquals(1, r.unmatched());
        ArgumentCaptor<List<BoardRepository.UnmatchedRow>> miss = ArgumentCaptor.forClass(List.class);
        verify(boards).saveCapture(eq(Sport.NBA), eq("yahoo"), any(LocalDate.class), eq("redraft"), eq(List.of()), miss.capture());
        assertEquals("Chris Smith", miss.getValue().get(0).name());
    }

    // ---- Spec 046 C5: "never throws" and range checks ----

    @Test
    void aSaveCaptureFailureComesBackAsARejectedReasonNotAnException() {
        when(client.fetchAll()).thenReturn(List.of(yahoo("Nikola Jokic", "C", 1.4, 77.0, 1.0)));
        org.mockito.Mockito.doThrow(new IllegalStateException("db down")).when(boards)
                .saveCapture(any(), anyString(), any(), anyString(), anyList(), anyList());

        var r = assertDoesNotThrow(() -> service.ingest(Sport.NBA));

        assertNotNull(r.rejectedReason());
        assertTrue(r.rejectedReason().contains("db down"));
        assertEquals(0, r.rows());
    }

    @Test
    void aPercentDraftedOutsideZeroToOneRejectsTheWholeFetch() {
        when(client.fetchAll()).thenReturn(List.of(
                yahoo("Nikola Jokic", "C", 1.4, 77.0, 1.0),
                yahoo("Luka Doncic", "PG", 3.0, 70.0, 98.0)));   // Yahoo switching to percents

        var r = service.ingest(Sport.NBA);

        assertNotNull(r.rejectedReason());
        assertTrue(r.rejectedReason().contains("Luka Doncic"));
        verify(boards, never()).saveCapture(any(), anyString(), any(), anyString(), anyList(), anyList());
    }

    @Test
    void aNonPositiveAdpRejectsTheWholeFetch() {
        when(client.fetchAll()).thenReturn(List.of(
                yahoo("Nikola Jokic", "C", 1.4, 77.0, 1.0),
                yahoo("Luka Doncic", "PG", 0.0, 70.0, 1.0)));

        assertNotNull(service.ingest(Sport.NBA).rejectedReason());
        verify(boards, never()).saveCapture(any(), anyString(), any(), anyString(), anyList(), anyList());
    }

    // ---- Spec 046 C11 ----

    @Test
    void forceWritesACaptureTheShrinkGuardWouldRefuse() {
        LocalDate yesterday = LocalDate.now().minusDays(1);
        when(boards.latestCapture(Sport.NBA, "yahoo")).thenReturn(Optional.of(yesterday));
        when(boards.captureSize(Sport.NBA, "yahoo", yesterday)).thenReturn(186);
        when(client.fetchAll()).thenReturn(List.of(yahoo("Nikola Jokic", "C", 1.4, 77.0, 1.0)));

        assertNotNull(service.ingest(Sport.NBA).rejectedReason());
        var forced = service.ingest(Sport.NBA, true);

        assertNull(forced.rejectedReason());
        assertEquals(1, forced.rows());
    }

    @Test
    void footballIsRefused() {
        assertThrows(IllegalArgumentException.class, () -> service.ingest(Sport.NFL));
    }
}
