package com.ballknowers.draftsim.api;

import com.ballknowers.draftsim.domain.Sport;
import com.ballknowers.draftsim.ingest.BoardService;
import com.ballknowers.draftsim.ingest.FfcAdpService;
import com.ballknowers.draftsim.ingest.LeagueHistoryIngestService;
import com.ballknowers.draftsim.ingest.LeagueIngestService;
import com.ballknowers.draftsim.ingest.PlayerIngestService;
import com.ballknowers.draftsim.profile.ProfileService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Phase 1b's {@code requireNflForNow} guard originally covered only the three
 * league-scoped routes ({@code /league/{id}}, {@code /league-history/{id}},
 * {@code /all/{id}}), which infer their sport from Sleeper. The three
 * {@code ?sport=}-parameterised routes ({@code /adp}, {@code /players},
 * {@code /board}) were left unguarded despite each being a live corruption
 * path once basketball leagues exist -- see {@code IngestController
 * .requireNflForNow}'s own javadoc for why. This pins that all three now
 * refuse {@code ?sport=nba}, and that {@code nfl} -- explicit or the
 * {@code @RequestParam} default an omitted {@code ?sport=} resolves to --
 * still reaches the real ingest call exactly as it did before this guard
 * existed.
 */
@ExtendWith(MockitoExtension.class)
class IngestControllerTest {

    @Mock private PlayerIngestService playerIngest;
    @Mock private LeagueIngestService leagueIngest;
    @Mock private LeagueHistoryIngestService leagueHistoryIngest;
    @Mock private FfcAdpService ffcAdp;
    @Mock private BoardService boards;
    @Mock private ProfileService profiles;

    private IngestController controller() {
        return new IngestController(playerIngest, leagueIngest, leagueHistoryIngest, ffcAdp, boards, profiles);
    }

    // ---- POST /api/ingest/adp ----

    @Test
    void adpRefusesNbaRatherThanMatchingFootballAdpAgainstBasketballPlayers() {
        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                () -> controller().adp("nba"));

        assertTrue(ex.getMessage().contains("POST /api/ingest/adp"), ex.getMessage());
        assertTrue(ex.getMessage().contains("nba"), ex.getMessage());
        verify(ffcAdp, never()).ingest(any(Sport.class));
    }

    /** Covers both an omitted {@code ?sport=} (the default) and an explicit {@code ?sport=nfl}. */
    @Test
    void adpWithNflStillIngestsExactlyAsBeforeThisGuard() {
        FfcAdpService.Result stub = new FfcAdpService.Result(true, 10, 9, 1, 5, false, "ok", List.of());
        when(ffcAdp.ingest(Sport.NFL)).thenReturn(stub);

        FfcAdpService.Result result = controller().adp("nfl");

        assertSame(stub, result);
        verify(ffcAdp).ingest(Sport.NFL);
    }

    // ---- POST /api/ingest/players ----

    @Test
    void playersRefusesNbaRatherThanDroppingOrMismappingBasketballPositions() {
        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                () -> controller().players("nba"));

        assertTrue(ex.getMessage().contains("POST /api/ingest/players"), ex.getMessage());
        assertTrue(ex.getMessage().contains("nba"), ex.getMessage());
        verify(playerIngest, never()).ingest(any(Sport.class));
    }

    /** Covers both an omitted {@code ?sport=} (the default) and an explicit {@code ?sport=nfl}. */
    @Test
    void playersWithNflStillIngestsExactlyAsBeforeThisGuard() {
        PlayerIngestService.Result stub = new PlayerIngestService.Result(500, 480);
        when(playerIngest.ingest(Sport.NFL)).thenReturn(stub);

        PlayerIngestService.Result result = controller().players("nfl");

        assertSame(stub, result);
        verify(playerIngest).ingest(Sport.NFL);
    }

    // ---- POST /api/ingest/board ----

    @Test
    void boardRefusesNbaRatherThanFittingProfilesFromUnfilteredFootballPicks() {
        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                () -> controller().board("nba"));

        assertTrue(ex.getMessage().contains("POST /api/ingest/board"), ex.getMessage());
        assertTrue(ex.getMessage().contains("nba"), ex.getMessage());
        verify(ffcAdp, never()).ingest(any(Sport.class));
        verify(boards, never()).rebuild(any(Sport.class));
        verify(profiles, never()).persistFitted(any(Sport.class));
    }

    /** Covers both an omitted {@code ?sport=} (the default) and an explicit {@code ?sport=nfl}. */
    @Test
    void boardWithNflStillIngestsExactlyAsBeforeThisGuard() {
        FfcAdpService.Result adpStub = new FfcAdpService.Result(true, 10, 9, 1, 5, false, "ok", List.of());
        BoardService.Result boardStub = new BoardService.Result(60, 40, 10, 3, 2);
        when(ffcAdp.ingest(Sport.NFL)).thenReturn(adpStub);
        when(boards.rebuild(Sport.NFL)).thenReturn(boardStub);
        when(profiles.persistFitted(Sport.NFL)).thenReturn(12);

        Map<String, Object> response = controller().board("nfl");

        assertSame(adpStub, response.get("adp"));
        assertSame(boardStub, response.get("board"));
        assertEquals(12, response.get("profilesWritten"));
        verify(ffcAdp).ingest(Sport.NFL);
        verify(boards).rebuild(Sport.NFL);
        verify(profiles).persistFitted(Sport.NFL);
    }
}
