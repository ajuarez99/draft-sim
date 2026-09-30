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
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.ResponseEntity;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.when;

/** GET /api/drafts/{id}/pool -- the board head with ids, in board order, for the live room's scarcity meter. */
@ExtendWith(MockitoExtension.class)
class LeagueControllerPoolTest {

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
        // Same lenient delegation as LeagueControllerRealBoardTest: scoping has its own tests.
        lenient().when(membership.visibleDraft(any(), any()))
                .thenAnswer(inv -> drafts.bySleeperId(inv.getArgument(1)));
        return new LeagueController(leagues, drafts, profiles, boards, poller, managers, players, owner,
                membership);
    }

    private static DraftRepository.DraftRow row() {
        return new DraftRepository.DraftRow(1L, 10L, "d1", 2026, 15, 14, "drafting", Map.of());
    }

    private void league(Sport sport) {
        when(drafts.bySleeperId("d1")).thenReturn(Optional.of(row()));
        when(leagues.byId(10L)).thenReturn(Optional.of(new LeagueRepository.LeagueRow(
                10L, sport, "L1", "League", 2026, 14, List.of(), 1.0, null, "in_season")));
    }

    private static BoardEntry entry(long id, Sport sport, String team) {
        Player p = new Player(id, sport, "s" + id, "P" + id, List.of(Position.RB), team, "Active", null, null, null);
        return new BoardEntry(p, id, (int) id);
    }

    private static List<BoardEntry> board(Sport sport, int n) {
        List<BoardEntry> b = new ArrayList<>();
        for (int i = 1; i <= n; i++) b.add(entry(i, sport, "SEA"));
        return b;
    }

    @SuppressWarnings("unchecked")
    private static List<SimulationResult.PlayerRef> body(ResponseEntity<?> r) {
        assertEquals(200, r.getStatusCode().value());
        return (List<SimulationResult.PlayerRef>) r.getBody();
    }

    @Test
    void anInvisibleDraftIs404() {
        when(drafts.bySleeperId("nope")).thenReturn(Optional.empty());
        assertEquals(404, controller().pool("nope", 200, null).getStatusCode().value());
    }

    @Test
    void orderEqualsTheBoardForBothSportsUsingTheLeaguesSport() {
        for (Sport sport : Sport.values()) {
            league(sport);
            List<BoardEntry> b = board(sport, 5);
            when(boards.currentBoard(sport)).thenReturn(b);
            List<SimulationResult.PlayerRef> got = body(controller().pool("d1", 200, "u"));
            assertEquals(b.stream().map(SimulationResult.PlayerRef::from).toList(), got, "sport " + sport);
        }
    }

    @Test
    void limitClampsToOneAndFourHundred() {
        league(Sport.NFL);
        when(boards.currentBoard(Sport.NFL)).thenReturn(board(Sport.NFL, 500));
        assertEquals(1, body(controller().pool("d1", 0, "u")).size());
        assertEquals(1, body(controller().pool("d1", -5, "u")).size());
        assertEquals(400, body(controller().pool("d1", 10000, "u")).size());
        assertEquals(37, body(controller().pool("d1", 37, "u")).size());
    }

    @Test
    void theDefaultLimitIs200() {
        assertEquals(200, LeagueController.POOL_DEFAULT_LIMIT);
        assertEquals(400, LeagueController.POOL_MAX_LIMIT);
    }

    @Test
    void aNullTeamSerializesAsJsonNull() throws Exception {
        league(Sport.NFL);
        when(boards.currentBoard(Sport.NFL)).thenReturn(List.of(entry(1, Sport.NFL, null)));
        String json = new ObjectMapper().writeValueAsString(controller().pool("d1", 200, "u").getBody());
        assertTrue(json.contains("\"team\":null"), json);
    }

    /** The board is not draft-aware; the client subtracts landed picks, so nothing is removed here. */
    @Test
    void draftedPlayersAreNotFilteredOut() {
        league(Sport.NFL);
        when(boards.currentBoard(Sport.NFL)).thenReturn(board(Sport.NFL, 10));
        assertEquals(10, body(controller().pool("d1", 200, "u")).size());
        org.mockito.Mockito.verifyNoInteractions(players);
    }

    @Test
    void anEmptyBoardIsAnEmptyListNotAnException() {
        league(Sport.NBA);
        when(boards.currentBoard(Sport.NBA)).thenReturn(List.of());
        assertEquals(List.of(), body(controller().pool("d1", 200, "u")));
    }
}
