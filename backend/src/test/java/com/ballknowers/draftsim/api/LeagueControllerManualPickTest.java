package com.ballknowers.draftsim.api;

import com.ballknowers.draftsim.config.OwnerProperties;
import com.ballknowers.draftsim.domain.Sport;
import com.ballknowers.draftsim.ingest.BoardService;
import com.ballknowers.draftsim.ingest.LiveDraftPoller;
import com.ballknowers.draftsim.profile.ProfileService;
import com.ballknowers.draftsim.store.DraftRepository;
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

    private LeagueController controller() {
        return new LeagueController(leagues, drafts, profiles, boards, poller, managers, players, owner);
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
                .recordPick("d1", new LeagueController.ManualPick(17, "4046"));

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

        controller().recordPick("d1", new LeagueController.ManualPick(3, "4046"));

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
                .recordPick("d1", new LeagueController.ManualPick(17, "not-a-player"));

        assertEquals(400, response.getStatusCode().value());
        verify(drafts, never()).upsertPicks(anyLong(), any());
    }

    /** Re-posting the same pick must be a no-op, not a duplicate row. */
    @Test
    void rePostingTheSamePickIsIdempotent() {
        when(drafts.bySleeperId("d1")).thenReturn(Optional.of(row()));
        when(players.idsBySleeperId(Sport.NFL)).thenReturn(Map.of("4046", 55L));

        LeagueController controller = controller();
        ResponseEntity<?> first = controller.recordPick("d1", new LeagueController.ManualPick(17, "4046"));
        ResponseEntity<?> second = controller.recordPick("d1", new LeagueController.ManualPick(17, "4046"));

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
                .recordPick("d1", new LeagueController.ManualPick(211, "4046"))
                .getStatusCode().value());
        verify(drafts, never()).upsertPicks(anyLong(), any());
    }

    @Test
    void anUnknownDraftIs404() {
        when(drafts.bySleeperId("nope")).thenReturn(Optional.empty());
        assertEquals(404, controller()
                .recordPick("nope", new LeagueController.ManualPick(1, "4046"))
                .getStatusCode().value());
    }
}
