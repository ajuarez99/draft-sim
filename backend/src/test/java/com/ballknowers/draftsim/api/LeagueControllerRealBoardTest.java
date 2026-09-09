package com.ballknowers.draftsim.api;

import com.ballknowers.draftsim.config.OwnerProperties;
import com.ballknowers.draftsim.domain.BoardEntry;
import com.ballknowers.draftsim.domain.Player;
import com.ballknowers.draftsim.domain.Position;
import com.ballknowers.draftsim.domain.Sport;
import com.ballknowers.draftsim.engine.SimulationResult;
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
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.ResponseEntity;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.when;

/**
 * GET /api/drafts/{id}/board -- the real picks that already happened, as
 * opposed to /api/sims, which always predicts. Backs the picker's "Draft
 * board" link for a completed league.
 */
@ExtendWith(MockitoExtension.class)
class LeagueControllerRealBoardTest {

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
        // so the caller can always see the league. LeagueMembership has its own
        // tests; a mock left unstubbed would answer false and fail every one of
        // these for the wrong reason.
        lenient().when(membership.canSee(any(), anyLong())).thenReturn(true);
        return new LeagueController(leagues, drafts, profiles, boards, poller, managers, players, owner,
                membership);
    }

    private static DraftRepository.DraftRow row() {
        return new DraftRepository.DraftRow(1L, 10L, "d1", 2026, 15, 14, "complete",
                Map.of("1", 101, "3", 103));
    }

    private static Player player(long id, String sleeperId, String name, Position pos) {
        return new Player(id, Sport.NFL, sleeperId, name, List.of(pos), "SEA", "Active", null, null, null);
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> onlyPick(ResponseEntity<?> response) {
        Map<String, Object> body = (Map<String, Object>) response.getBody();
        List<Map<String, Object>> picks = (List<Map<String, Object>>) body.get("picks");
        assertEquals(1, picks.size());
        return picks.get(0);
    }

    @Test
    void anUnknownDraftIs404() {
        when(drafts.bySleeperId("nope")).thenReturn(Optional.empty());
        assertEquals(404, controller().realBoard("nope", null).getStatusCode().value());
    }

    /** The common case: the drafted player is still on today's board, so adp/positionalRank are real. */
    @Test
    void aPickIsJoinedToItsPlayerAndManager() {
        when(drafts.bySleeperId("d1")).thenReturn(Optional.of(row()));
        when(drafts.picks(1L)).thenReturn(List.of(
                new DraftRepository.PickRow(1L, 1, 1, 1, 101L, 55L, 1.0)));
        Player bijan = player(55L, "4046", "Bijan Robinson", Position.RB);
        when(boards.currentBoard(Sport.NFL)).thenReturn(List.of(new BoardEntry(bijan, 1.4, 1)));
        when(players.findAll(Sport.NFL)).thenReturn(List.of(bijan));
        when(managers.names()).thenReturn(Map.of(101L, "Allan"));

        ResponseEntity<?> response = controller().realBoard("d1", null);

        assertEquals(200, response.getStatusCode().value());
        Map<String, Object> pick = onlyPick(response);
        assertEquals(1, pick.get("pickNo"));
        assertEquals(1, pick.get("round"));
        assertEquals(1, pick.get("slot"));
        assertEquals("Allan", pick.get("manager"));
        SimulationResult.PlayerRef ref = (SimulationResult.PlayerRef) pick.get("player");
        assertEquals("Bijan Robinson", ref.name());
        assertEquals("RB", ref.position());
        assertEquals(1, ref.positionalRank());
    }

    /** No manager mapped for this pick's slot -- still renders, labeled by slot rather than dropped. */
    @Test
    void anUnmappedManagerFallsBackToASlotLabel() {
        when(drafts.bySleeperId("d1")).thenReturn(Optional.of(row()));
        when(drafts.picks(1L)).thenReturn(List.of(
                new DraftRepository.PickRow(1L, 5, 1, 5, null, 55L, null)));
        Player p = player(55L, "4046", "Someone", Position.WR);
        when(boards.currentBoard(Sport.NFL)).thenReturn(List.of(new BoardEntry(p, 10.0, 3)));
        when(players.findAll(Sport.NFL)).thenReturn(List.of(p));
        when(managers.names()).thenReturn(Map.of());

        Map<String, Object> pick = onlyPick(controller().realBoard("d1", null));
        assertEquals("Slot 5", pick.get("manager"));
    }

    /**
     * The drafted player has since fallen off the current board entirely (e.g.
     * retired) -- the pick still has to render as something instead of quietly
     * disappearing from an already-completed draft.
     */
    @Test
    void aPlayerNoLongerOnTheCurrentBoardStillRendersViaTheFallback() {
        when(drafts.bySleeperId("d1")).thenReturn(Optional.of(row()));
        when(drafts.picks(1L)).thenReturn(List.of(
                new DraftRepository.PickRow(1L, 8, 1, 8, 103L, 99L, null)));
        Player gone = player(99L, "1234", "Long Retired", Position.TE);
        when(boards.currentBoard(Sport.NFL)).thenReturn(List.of()); // not on today's board
        when(players.findAll(Sport.NFL)).thenReturn(List.of(gone));
        when(managers.names()).thenReturn(Map.of(103L, "Sam"));

        Map<String, Object> pick = onlyPick(controller().realBoard("d1", null));
        SimulationResult.PlayerRef ref = (SimulationResult.PlayerRef) pick.get("player");
        assertEquals("Long Retired", ref.name());
        assertEquals("TE", ref.position());
        assertEquals(999, ref.positionalRank(), "BoardService's own no-rank sentinel");
    }

    /** A pick slot with no resolved player yet is left out rather than rendered as a hole. */
    @Test
    void aPickWithNoPlayerIdIsOmitted() {
        when(drafts.bySleeperId("d1")).thenReturn(Optional.of(row()));
        when(drafts.picks(1L)).thenReturn(List.of(
                new DraftRepository.PickRow(1L, 9, 1, 9, 101L, null, null)));
        when(boards.currentBoard(Sport.NFL)).thenReturn(List.of());
        when(players.findAll(Sport.NFL)).thenReturn(List.of());
        when(managers.names()).thenReturn(Map.of());

        @SuppressWarnings("unchecked")
        Map<String, Object> body = (Map<String, Object>) controller().realBoard("d1", null).getBody();
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> picks = (List<Map<String, Object>>) body.get("picks");
        assertTrue(picks.isEmpty());
    }
}
