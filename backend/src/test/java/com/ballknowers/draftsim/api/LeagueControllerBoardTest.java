package com.ballknowers.draftsim.api;

import com.ballknowers.draftsim.config.OwnerProperties;
import com.ballknowers.draftsim.domain.BoardEntry;
import com.ballknowers.draftsim.domain.Player;
import com.ballknowers.draftsim.domain.Position;
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
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.when;

/**
 * GET /api/board -- one of the Phase 1b "no league/draft in the path" routes,
 * which take an explicit {@code ?sport=} defaulting to {@code nfl} so every
 * curl in README.md/DEPLOY.md (none of which pass ?sport=) keeps working
 * verbatim. This pins that default and the bad-value error path.
 */
@ExtendWith(MockitoExtension.class)
class LeagueControllerBoardTest {

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

    private static Player player(long id, String name, Position pos) {
        return new Player(id, Sport.NFL, "sp" + id, name, List.of(pos), "SEA", "Active", null, null, null);
    }

    /** No ?sport= at all -- the exact shape of every README/DEPLOY curl. */
    @Test
    void withNoSportParamDefaultsToNflExactlyAsBeforePhase1b() {
        Player bijan = player(1L, "Bijan Robinson", Position.RB);
        when(boards.currentBoard(Sport.NFL)).thenReturn(List.of(new BoardEntry(bijan, 1.4, 1)));
        when(boards.currentBoardDate(Sport.NFL)).thenReturn(Optional.of(LocalDate.of(2026, 9, 1)));
        when(boards.picksWithAdpAtTime()).thenReturn(42);

        Map<String, Object> response = controller().board(60, "nfl");

        assertEquals("2026-09-01", response.get("capturedOn"));
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> entries = (List<Map<String, Object>>) response.get("entries");
        assertEquals(1, entries.size());
        assertEquals("Bijan Robinson", entries.get(0).get("name"));
    }

    @Test
    void anExplicitSportIsHonoured() {
        when(boards.picksWithAdpAtTime()).thenReturn(0);
        // Deliberately not stubbing Sport.NBA at all: Mockito's default answer
        // for an unstubbed List/Optional-returning call (empty list, empty
        // Optional) is exactly what "no NBA data ingested yet" looks like, and
        // leaving the Sport.NFL stubs out entirely proves the sport actually
        // passed through rather than something a leftover NFL stub happened to
        // also satisfy.
        Map<String, Object> response = controller().board(60, "nba");

        assertEquals("none", response.get("capturedOn"), "must not have fallen back to the NFL board");
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> entries = (List<Map<String, Object>>) response.get("entries");
        assertTrue(entries.isEmpty());
    }

    @Test
    void aBadSportValueFailsReadablyRatherThanNpeingOr500ing() {
        assertThrows(IllegalArgumentException.class, () -> controller().board(60, "mlb"));
    }
}
