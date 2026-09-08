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
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * The {@code requireNflForNow} guard these three {@code ?sport=}-parameterised
 * routes ({@code /adp}, {@code /players}, {@code /board}) used to carry was a
 * TEMPORARY measure, deleted per its own javadoc once
 * claude/multi-sport-and-rebrand.md Phase 5 landed basketball ingest: FFC is
 * now skipped for basketball inside {@link FfcAdpService} itself (rather than
 * refused here), {@code allCompletedPicks} is sport-filtered (Phase 2), and
 * {@code PlayerIngestService.fantasyPositions} handles basketball positions.
 * This now pins the opposite of what the deleted guard's own test used to pin:
 * {@code ?sport=nba} reaches the real ingest call exactly like {@code nfl}
 * does, for all three routes.
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
    void adpWithNbaReachesFfcAdpServiceRatherThanBeingRefused() {
        FfcAdpService.Result stub = new FfcAdpService.Result(false, 0, 0, 0, 0, false,
                "fantasyfootballcalculator.com is football-only; skipped for sport 'nba'", List.of());
        when(ffcAdp.ingest(Sport.NBA)).thenReturn(stub);

        FfcAdpService.Result result = controller().adp("nba");

        assertSame(stub, result);
        verify(ffcAdp).ingest(Sport.NBA);
    }

    /** Covers both an omitted {@code ?sport=} (the default) and an explicit {@code ?sport=nfl}. */
    @Test
    void adpWithNflStillIngestsExactlyAsBefore() {
        FfcAdpService.Result stub = new FfcAdpService.Result(true, 10, 9, 1, 5, false, "ok", List.of());
        when(ffcAdp.ingest(Sport.NFL)).thenReturn(stub);

        FfcAdpService.Result result = controller().adp("nfl");

        assertSame(stub, result);
        verify(ffcAdp).ingest(Sport.NFL);
    }

    // ---- POST /api/ingest/players ----

    @Test
    void playersWithNbaReachesPlayerIngestServiceRatherThanBeingRefused() {
        PlayerIngestService.Result stub = new PlayerIngestService.Result(2000, 1900);
        when(playerIngest.ingest(Sport.NBA)).thenReturn(stub);

        PlayerIngestService.Result result = controller().players("nba");

        assertSame(stub, result);
        verify(playerIngest).ingest(Sport.NBA);
    }

    /** Covers both an omitted {@code ?sport=} (the default) and an explicit {@code ?sport=nfl}. */
    @Test
    void playersWithNflStillIngestsExactlyAsBefore() {
        PlayerIngestService.Result stub = new PlayerIngestService.Result(500, 480);
        when(playerIngest.ingest(Sport.NFL)).thenReturn(stub);

        PlayerIngestService.Result result = controller().players("nfl");

        assertSame(stub, result);
        verify(playerIngest).ingest(Sport.NFL);
    }

    // ---- POST /api/ingest/board ----

    @Test
    void boardWithNbaReachesBoardServiceRatherThanBeingRefused() {
        FfcAdpService.Result adpStub = new FfcAdpService.Result(false, 0, 0, 0, 0, false,
                "fantasyfootballcalculator.com is football-only; skipped for sport 'nba'", List.of());
        BoardService.Result boardStub = new BoardService.Result(168, 0, 0, 0, 0);
        when(ffcAdp.ingest(Sport.NBA)).thenReturn(adpStub);
        when(boards.rebuild(Sport.NBA)).thenReturn(boardStub);
        when(profiles.persistFitted(Sport.NBA)).thenReturn(12);

        Map<String, Object> response = controller().board("nba");

        assertSame(adpStub, response.get("adp"));
        assertSame(boardStub, response.get("board"));
        assertEquals(12, response.get("profilesWritten"));
        verify(ffcAdp).ingest(Sport.NBA);
        verify(boards).rebuild(Sport.NBA);
        verify(profiles).persistFitted(Sport.NBA);
    }

    /** Covers both an omitted {@code ?sport=} (the default) and an explicit {@code ?sport=nfl}. */
    @Test
    void boardWithNflStillIngestsExactlyAsBefore() {
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
