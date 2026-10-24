package com.ballknowers.draftsim.engine;

import com.ballknowers.draftsim.domain.Platform;
import com.ballknowers.draftsim.domain.Player;
import com.ballknowers.draftsim.domain.Position;
import com.ballknowers.draftsim.domain.Sport;
import com.ballknowers.draftsim.store.BoardRepository;
import com.ballknowers.draftsim.store.DraftFixtures;
import com.ballknowers.draftsim.store.DraftRepository;
import com.ballknowers.draftsim.store.LeagueRepository;
import com.ballknowers.draftsim.store.ManagerRepository;
import com.ballknowers.draftsim.store.PlayerRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/** Spec 046 T023: the compare payload's rules that a shared database can't pin deterministically. */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class AdpCompareServiceTest {

    @Mock private BoardRepository boards;
    @Mock private PlayerRepository players;
    @Mock private DraftRepository drafts;
    @Mock private ManagerRepository managers;

    private AdpCompareService service;

    private static LeagueRepository.LeagueRow league(Sport sport, Platform platform) {
        return new LeagueRepository.LeagueRow(7L, sport, "L7", "Ball Knowers", 2026, 10, List.of("PG"), 0.0,
                null, "complete", platform, null, List.of(), List.of());
    }

    @BeforeEach
    void setUp() {
        service = new AdpCompareService(boards, players, drafts, managers);
        when(boards.latestCaptureDetail(any(), any())).thenReturn(Optional.empty());
        when(drafts.forLeague(7L)).thenReturn(Optional.empty());
    }

    @Test
    void withNoCaptureAtAllAllThreeSourcesStillListAsUnavailableAndTheCurveIsEmpty() {
        var r = service.forLeague(league(Sport.NBA, Platform.SLEEPER)).orElseThrow();

        assertEquals(List.of("sleeper_dynasty", "sleeper_redraft", "yahoo"),
                r.sources().stream().map(AdpCompareService.Source::key).toList());
        for (var s : r.sources()) {
            assertNull(s.capturedOn(), s.key());
            assertEquals(0, s.ranked(), s.key());
        }
        assertEquals(0, r.sources().get(2).unmatched());
        assertNull(r.sources().get(2).deepestAdp());
        assertEquals(0.5, r.sources().get(2).minPercentDrafted());
        assertEquals(List.of(), r.costCurve(), "no Yahoo capture: no curve, so the client shows Blend $ as unavailable");
        assertEquals(List.of(), r.players());
        assertNull(r.draft());
        verifyNoInteractions(players);
    }

    @Test
    void theCostCurveIsFlooredAtOneDollarThenSortedDescendingAndSkipsNullCosts() {
        LocalDate day = LocalDate.of(2026, 10, 23);
        when(boards.latestCaptureDetail(Sport.NBA, "yahoo")).thenReturn(Optional.of(new BoardRepository.LatestCapture(day,
                List.of(new BoardRepository.CaptureRow(1, 90.0, 0.4, 0.2),
                        new BoardRepository.CaptureRow(2, 1.0, 80.9, 1.0),
                        new BoardRepository.CaptureRow(3, 5.0, null, 1.0)),
                List.of(new BoardRepository.UnmatchedRow("Nobody", "BOS", "PG", 40.0, 12.5, 0.9)))));
        when(players.findAll(Sport.NBA)).thenReturn(List.of(
                new Player(1, Sport.NBA, "s1", "A", List.of(Position.PG), "BOS", null, null, 22, 1),
                new Player(2, Sport.NBA, "s2", "B", List.of(Position.C), "BOS", null, null, 22, 1),
                new Player(3, Sport.NBA, "s3", "C", List.of(Position.SF), "BOS", null, null, 22, 1)));

        var r = service.forLeague(league(Sport.NBA, Platform.SLEEPER)).orElseThrow();

        assertEquals(List.of(80.9, 12.5, 1.0), r.costCurve());
        assertEquals(4, r.players().size(), "three matched plus the unmatched Yahoo row");
        var yahoo = r.sources().get(2);
        assertEquals(day, yahoo.capturedOn());
        assertEquals(4, yahoo.ranked());
        assertEquals(1, yahoo.unmatched());
        assertEquals(90.0, yahoo.deepestAdp());
        assertTrue(r.players().stream().anyMatch(p -> p.playerId() == null && p.name().equals("Nobody")));
    }

    @Test
    void anEspnLeagueNeverReadsADraftEvenWhenOneIsStored() {
        when(drafts.forLeague(7L)).thenReturn(Optional.of(
                DraftFixtures.draftRow(1, 7L, "espn-1", 2026, 13, 10, "complete", java.util.Map.of())));

        var r = service.forLeague(league(Sport.NBA, Platform.ESPN)).orElseThrow();

        assertNull(r.draft());
        verify(drafts, org.mockito.Mockito.never()).forLeague(7L);
    }

    @Test
    void aSleeperLeaguesDraftCarriesIdentityShapeAndBudget() {
        when(drafts.forLeague(7L)).thenReturn(Optional.of(
                DraftFixtures.auctionDraftRow(1, 7L, "d1", 2026, 23, 10, "drafting", 200)));

        var d = service.forLeague(league(Sport.NBA, Platform.SLEEPER)).orElseThrow().draft();

        assertEquals("d1", d.sleeperDraftId());
        assertEquals("auction", d.type());
        assertEquals("drafting", d.status());
        assertEquals(10, d.teams());
        assertEquals(23, d.rounds());
        assertEquals(200, d.budget());
    }

    @Test
    void aSnakeDraftCarriesNoBudgetCardsOrSales() {
        when(drafts.forLeague(7L)).thenReturn(Optional.of(
                DraftFixtures.draftRow(1, 7L, "d1", 2026, 13, 10, "drafting", java.util.Map.of())));

        var d = service.forLeague(league(Sport.NBA, Platform.SLEEPER)).orElseThrow().draft();

        assertNull(d.teamsState(), "omitted, not an empty list, so a snake page can't render an empty grid");
        assertNull(d.sales());
        verify(drafts, org.mockito.Mockito.never()).picks(org.mockito.ArgumentMatchers.anyLong());
    }

    @Test
    void anAuctionWithNoOrderYetStillShowsEverySlotsFullBudget() {
        when(drafts.forLeague(7L)).thenReturn(Optional.of(
                DraftFixtures.auctionDraftRow(1, 7L, "d1", 2026, 23, 10, "pre_draft", 200)));
        when(drafts.picks(1L)).thenReturn(List.of());

        var d = service.forLeague(league(Sport.NBA, Platform.SLEEPER)).orElseThrow().draft();

        assertEquals(10, d.teamsState().size());
        for (var t : d.teamsState()) {
            assertNull(t.managerId());
            assertEquals(200, t.left());
            assertEquals(23, t.spotsLeft());
            assertEquals(178, t.maxBid());
        }
        assertEquals(List.of(), d.sales());
    }

    @Test
    void footballHasNoCompareSources() {
        assertTrue(service.forLeague(league(Sport.NFL, Platform.SLEEPER)).isEmpty());
    }
}
