package com.ballknowers.draftsim.api;

import com.ballknowers.draftsim.config.OwnerProperties;
import com.ballknowers.draftsim.domain.Sport;
import com.ballknowers.draftsim.ingest.BoardService;
import com.ballknowers.draftsim.ingest.LiveDraftPoller;
import com.ballknowers.draftsim.profile.ProfileService;
import com.ballknowers.draftsim.store.DraftRepository;
import com.ballknowers.draftsim.store.LeagueMembership;
import com.ballknowers.draftsim.store.LeagueRepository;
import com.ballknowers.draftsim.store.ManagerRepository;
import com.ballknowers.draftsim.store.PlayerRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.ResponseEntity;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

/**
 * POST /api/drafts/{id}/picks -- the manual escape hatch for a pick Allan can
 * already see in Sleeper's UI but the poller hasn't caught up to.
 */
@ExtendWith(MockitoExtension.class)
class LeagueControllerManualPickTest {

    @Mock private LeagueRepository leagues;
    @Mock private DraftRepository drafts;
    @Mock private ProfileService profiles;
    @Mock private BoardService boards;
    @Mock private LiveDraftPoller poller;
    @Mock private ManagerRepository managers;
    @Mock private PlayerRepository players;
    @Mock private OwnerProperties owner;
    @Mock private LeagueMembership membership;

    private LeagueController controller() {
        // These tests are about each endpoint's own behavior, not about scoping,
        // so the caller can always see the draft. LeagueMembership has its own
        // tests; a mock left unstubbed answers Optional.empty(), which would 404
        // every one of these for the wrong reason.
        //
        // Delegates to the mocked DraftRepository rather than returning a fixed
        // row, so each test's own when(drafts.bySleeperId(...)) still decides
        // what the draft is -- and the "unknown draft is 404" cases keep working,
        // since an id nobody stubbed comes back empty from there too.
        lenient().when(membership.visibleDraft(any(), any()))
                .thenAnswer(inv -> drafts.bySleeperId(inv.getArgument(1)));
        return new LeagueController(leagues, drafts, profiles, boards, poller, managers, players, owner,
                membership);
    }

    /** 14 teams, slots 1 and 3 mapped; slot 12 deliberately is not. */
    private static DraftRepository.DraftRow row() {
        return new DraftRepository.DraftRow(1L, 10L, "d1", 2026, 15, 14, "drafting",
                Map.of("1", 101, "3", 103, "12", 112));
    }

    @SuppressWarnings("unchecked")
    private DraftRepository.PickRow capturedRow() {
        ArgumentCaptor<List<DraftRepository.PickRow>> captor = ArgumentCaptor.forClass(List.class);
        verify(drafts).upsertPicks(eq(1L), captor.capture());
        assertEquals(1, captor.getValue().size());
        return captor.getValue().get(0);
    }

    /**
     * Round 2 runs backwards. At 14 teams pick 17 is the third pick of round 2, so
     * it belongs to slot 12 (14 - 3 + 1) -- getting this wrong writes the pick onto
     * the wrong manager's roster, which is worse than not writing it at all.
     */
    @Test
    void aRoundTwoPickIsSnakeReversedAndResolvesItsManager() {
        when(drafts.bySleeperId("d1")).thenReturn(Optional.of(row()));
        when(players.idsBySleeperId(Sport.NFL)).thenReturn(Map.of("4046", 55L));

        ResponseEntity<?> response = controller()
                .recordPick("d1", new LeagueController.ManualPick(17, "4046"), null);

        assertEquals(200, response.getStatusCode().value());
        DraftRepository.PickRow written = capturedRow();
        assertEquals(17, written.pickNo());
        assertEquals(2, written.round());
        assertEquals(12, written.draftSlot());
        assertEquals(112L, written.managerId(), "resolved through the stored slot map");
        assertEquals(55L, written.playerId());
        assertNull(written.adpAtTime(), "BoardService backfills this, the same as every other path");
    }

    @Test
    void aRoundOnePickRunsForwards() {
        when(drafts.bySleeperId("d1")).thenReturn(Optional.of(row()));
        when(players.idsBySleeperId(Sport.NFL)).thenReturn(Map.of("4046", 55L));

        controller().recordPick("d1", new LeagueController.ManualPick(3, "4046"), null);

        DraftRepository.PickRow written = capturedRow();
        assertEquals(1, written.round());
        assertEquals(3, written.draftSlot());
        assertEquals(103L, written.managerId());
    }

    /**
     * A silent player_id null would look like a successful pick in the UI while
     * producing a row the engine and the board both ignore -- the same shape of
     * failure as the manager_id nulls that made every seat a bot.
     */
    @Test
    void anUnknownSleeperPlayerIdIs400NotASilentNull() {
        when(drafts.bySleeperId("d1")).thenReturn(Optional.of(row()));
        when(players.idsBySleeperId(Sport.NFL)).thenReturn(Map.of("4046", 55L));

        ResponseEntity<?> response = controller()
                .recordPick("d1", new LeagueController.ManualPick(17, "not-a-player"), null);

        assertEquals(400, response.getStatusCode().value());
        verify(drafts, never()).upsertPicks(anyLong(), any());
    }

    /** Re-posting the same pick must be a no-op, not a duplicate row. */
    @Test
    void rePostingTheSamePickIsIdempotent() {
        when(drafts.bySleeperId("d1")).thenReturn(Optional.of(row()));
        when(players.idsBySleeperId(Sport.NFL)).thenReturn(Map.of("4046", 55L));

        LeagueController controller = controller();
        ResponseEntity<?> first = controller.recordPick("d1", new LeagueController.ManualPick(17, "4046"), null);
        ResponseEntity<?> second = controller.recordPick("d1", new LeagueController.ManualPick(17, "4046"), null);

        assertEquals(200, first.getStatusCode().value());
        assertEquals(200, second.getStatusCode().value());
        assertEquals(first.getBody(), second.getBody());

        // Idempotence lives in the SQL: upsertPicks is an ON CONFLICT upsert keyed
        // on (draft_id, pick_no), so identical rows twice is one row.
        ArgumentCaptor<List<DraftRepository.PickRow>> captor = captorOfLists();
        verify(drafts, times(2)).upsertPicks(eq(1L), captor.capture());
        assertEquals(captor.getAllValues().get(0), captor.getAllValues().get(1));
    }

    @SuppressWarnings("unchecked")
    private static ArgumentCaptor<List<DraftRepository.PickRow>> captorOfLists() {
        return ArgumentCaptor.forClass(List.class);
    }

    @Test
    void aPickNumberPastTheEndOfTheDraftIs400() {
        when(drafts.bySleeperId("d1")).thenReturn(Optional.of(row()));

        // 14 teams x 15 rounds = 210.
        assertEquals(400, controller()
                .recordPick("d1", new LeagueController.ManualPick(211, "4046"), null)
                .getStatusCode().value());
        verify(drafts, never()).upsertPicks(anyLong(), any());
    }

    /**
     * Only the seat's own owner may fill it in.
     *
     * This endpoint writes into real {@code draft_pick}, and every league member
     * could previously write any pick number -- so two people hand-recording the
     * same pick differently was a silent last-writer-wins on a live board with
     * nobody told. Slot 3 is manager 103's; "u-alice" is manager 103.
     */
    @Test
    void theSeatsOwnerMayRecordTheirOwnPick() {
        when(drafts.bySleeperId("d1")).thenReturn(Optional.of(row()));
        when(players.idsBySleeperId(Sport.NFL)).thenReturn(Map.of("4046", 55L));
        when(managers.idsBySleeperUserId()).thenReturn(Map.of("u-alice", 103L, "u-bob", 101L));

        ResponseEntity<?> response = controller()
                .recordPick("d1", new LeagueController.ManualPick(3, "4046"), "u-alice");

        assertEquals(200, response.getStatusCode().value());
        assertEquals(103L, capturedRow().managerId());
    }

    /** "u-bob" is manager 101, who holds slot 1. Pick 3 is slot 3's, not his. */
    @Test
    void someoneElsesSeatIs403AndWritesNothing() {
        when(drafts.bySleeperId("d1")).thenReturn(Optional.of(row()));
        when(players.idsBySleeperId(Sport.NFL)).thenReturn(Map.of("4046", 55L));
        when(managers.idsBySleeperUserId()).thenReturn(Map.of("u-alice", 103L, "u-bob", 101L));

        ResponseEntity<?> response = controller()
                .recordPick("d1", new LeagueController.ManualPick(3, "4046"), "u-bob");

        assertEquals(403, response.getStatusCode().value());
        // 403 rather than this file's usual 404: those hide whether a draft
        // exists, and this caller has already been shown the whole board.
        verify(drafts, never()).upsertPicks(anyLong(), any());
    }

    /**
     * The pre-identity contract every route keeps, and what DEPLOY.md's curl
     * escape hatches run on. Asserted explicitly rather than left implied by the
     * other tests all passing null, so that narrowing it later is a deliberate
     * act rather than an accident.
     */
    @Test
    void aCallerWithNoIdentityHeaderMayStillRecordAnySeat() {
        when(drafts.bySleeperId("d1")).thenReturn(Optional.of(row()));
        when(players.idsBySleeperId(Sport.NFL)).thenReturn(Map.of("4046", 55L));

        assertEquals(200, controller()
                .recordPick("d1", new LeagueController.ManualPick(3, "4046"), null)
                .getStatusCode().value());
    }

    /**
     * Slot 2 is deliberately absent from the stored slot map. Sleeper returns a
     * null draft_order until the commissioner sets it, so refusing an unmapped
     * seat would make the seats nobody can be shown to own also the seats nobody
     * can repair -- a lockout in exactly the state the escape hatch exists for.
     */
    @Test
    void aSeatSleeperHasNotMappedYetStaysRecordableByAnyMember() {
        when(drafts.bySleeperId("d1")).thenReturn(Optional.of(row()));
        when(players.idsBySleeperId(Sport.NFL)).thenReturn(Map.of("4046", 55L));

        ResponseEntity<?> response = controller()
                .recordPick("d1", new LeagueController.ManualPick(2, "4046"), "u-bob");

        assertEquals(200, response.getStatusCode().value());
        assertNull(capturedRow().managerId(), "unattributed, exactly like an autopick");
        // Not merely allowed -- allowed without the caller ever being looked up.
        // An unowned seat cannot be someone else's, so there is nobody to check
        // against, and that short-circuit is what keeps this path working when
        // the seat map is the thing that is broken.
        verify(managers, never()).idsBySleeperUserId();
    }

    /** Signed in as someone with no manager row at all: owns no seat, so no seat. */
    @Test
    void aCallerWithNoManagerRowOwnsNoSeat() {
        when(drafts.bySleeperId("d1")).thenReturn(Optional.of(row()));
        when(players.idsBySleeperId(Sport.NFL)).thenReturn(Map.of("4046", 55L));
        when(managers.idsBySleeperUserId()).thenReturn(Map.of("u-alice", 103L));

        assertEquals(403, controller()
                .recordPick("d1", new LeagueController.ManualPick(3, "4046"), "u-stranger")
                .getStatusCode().value());
        verify(drafts, never()).upsertPicks(anyLong(), any());
    }

    @Test
    void anUnknownDraftIs404() {
        when(drafts.bySleeperId("nope")).thenReturn(Optional.empty());
        assertEquals(404, controller()
                .recordPick("nope", new LeagueController.ManualPick(1, "4046"), null)
                .getStatusCode().value());
    }
}
