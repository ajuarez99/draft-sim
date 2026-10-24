package com.ballknowers.draftsim.api;

import com.ballknowers.draftsim.domain.Sport;
import com.ballknowers.draftsim.ingest.BoardService;
import com.ballknowers.draftsim.ingest.FfcAdpService;
import com.ballknowers.draftsim.ingest.LeagueHistoryIngestService;
import com.ballknowers.draftsim.ingest.LeagueIngestService;
import com.ballknowers.draftsim.ingest.PlayerIngestService;
import com.ballknowers.draftsim.ingest.ProjectionIngestService;
import com.ballknowers.draftsim.profile.ProfileService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
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
    @Mock private ProjectionIngestService projectionIngest;
    @Mock private com.ballknowers.draftsim.ingest.TransactionIngestService transactionIngest;
    @Mock private com.ballknowers.draftsim.ingest.PlayerGameIngestService playerGameIngest;
    @Mock private com.ballknowers.draftsim.ingest.SeasonProjectionIngestService seasonProjectionIngest;
    @Mock private com.ballknowers.draftsim.ingest.BracketIngestService bracketIngest;
    @Mock private com.ballknowers.draftsim.ingest.ProjectionSnapshotService projectionSnapshots;
    @Mock private com.ballknowers.draftsim.engine.ProjectionParityService projectionParity;
    @Mock private com.ballknowers.draftsim.ingest.SleeperAdpCaptureService sleeperAdpCapture;
    @Mock private com.ballknowers.draftsim.ingest.YahooAdpService yahooAdp;

    private IngestController controller() {
        return new IngestController(playerIngest, leagueIngest, leagueHistoryIngest, ffcAdp, boards, profiles,
                projectionIngest, transactionIngest, playerGameIngest,
                new com.ballknowers.draftsim.ingest.BoardRefresh(ffcAdp, boards, profiles), seasonProjectionIngest,
                bracketIngest, projectionSnapshots, projectionParity, sleeperAdpCapture, yahooAdp);
    }

    // ---- POST /api/ingest/adp/yahoo and /adp/sleeper (spec 046) ----

    @Test
    void yahooAdpRouteRunsTheCaptureForTheRequestedSport() {
        var stub = new com.ballknowers.draftsim.ingest.YahooAdpService.Result(186, 184, 184, 2, null);
        when(yahooAdp.ingest(Sport.NBA, false)).thenReturn(stub);

        assertSame(stub, controller().yahooAdp("nba", false));
    }

    @Test
    void sleeperAdpRouteFetchesAndCapturesTheNamedSeason() {
        var stub = new com.ballknowers.draftsim.ingest.SleeperAdpCaptureService.Result(817, 903, 1, null);
        when(sleeperAdpCapture.fetchAndCapture(Sport.NBA, 2026, false)).thenReturn(stub);

        assertSame(stub, controller().sleeperAdp("nba", 2026, false));
    }

    /** Spec 046 C11: force reaches the services, which is where the shrink guard lives. */
    @Test
    void forceIsPassedThroughToBothAdpCaptures() {
        var y = new com.ballknowers.draftsim.ingest.YahooAdpService.Result(186, 184, 184, 2, null);
        var s = new com.ballknowers.draftsim.ingest.SleeperAdpCaptureService.Result(817, 903, 1, null);
        when(yahooAdp.ingest(Sport.NBA, true)).thenReturn(y);
        when(sleeperAdpCapture.fetchAndCapture(Sport.NBA, 2026, true)).thenReturn(s);

        assertSame(y, controller().yahooAdp("nba", true));
        assertSame(s, controller().sleeperAdp("nba", 2026, true));
        verify(yahooAdp, org.mockito.Mockito.never()).ingest(Sport.NBA, false);
    }

    // ---- POST /api/ingest/projection-snapshot (spec 036) ----

    @Test
    void projectionSnapshotRunsTheCaptureForTheRequestedSportWithTheCurrentInstant() {
        var stub = new com.ballknowers.draftsim.ingest.ProjectionSnapshotService.Result("nba", 2026, List.of(1), 5, 0, 0);
        when(projectionSnapshots.capture(org.mockito.ArgumentMatchers.eq(Sport.NBA), org.mockito.ArgumentMatchers.any())).thenReturn(stub);

        assertSame(stub, controller().projectionSnapshot("nba"));
    }

    // ---- GET /api/ingest/projection-parity (spec 036 US3) ----

    @Test
    void projectionParityPassesTheOptionalLeagueThrough() {
        var stub = new com.ballknowers.draftsim.engine.ProjectionParityService.Result("nfl", 2026, 7, "x", 1, 0, 1, 0, 0.0, List.of());
        when(projectionParity.check("nfl", 2026, 7, "L1")).thenReturn(stub);

        assertSame(stub, controller().projectionParity("nfl", 2026, 7, "L1"));
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
        PlayerIngestService.Result stub = new PlayerIngestService.Result(2000, 1900, true, 0);
        when(playerIngest.ingest(Sport.NBA)).thenReturn(stub);

        PlayerIngestService.Result result = controller().players("nba");

        assertSame(stub, result);
        verify(playerIngest).ingest(Sport.NBA);
    }

    /** Covers both an omitted {@code ?sport=} (the default) and an explicit {@code ?sport=nfl}. */
    @Test
    void playersWithNflStillIngestsExactlyAsBefore() {
        PlayerIngestService.Result stub = new PlayerIngestService.Result(500, 480, true, 0);
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

    // ---- POST /api/ingest/projections ----

    /**
     * specs/016: basketball projection ingest is still refused -- the stored
     * columns are football scoring totals -- but no longer on the false ground
     * that Sleeper has "no basketball equivalent". It serves per-game NBA
     * projections (measured 2026-10-05, claude/projection-tools.md).
     */
    @Test
    void projectionsWithNbaAreStillRefusedButNotForWantOfASource() {
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                () -> controller().projections("nba", 2026, 1, 1, false));
        assertEquals("projections are football-only for now: the stored columns are Sleeper's "
                + "pts_ppr / pts_half_ppr / pts_std, which are football scoring totals. "
                + "Basketball projections need per-game stat lines scored with each league's "
                + "settings, which isn't built yet (claude/projection-tools.md).", e.getMessage());
        assertFalse(e.getMessage().contains("equivalent"), e.getMessage());
        assertFalse(e.getMessage().contains("/api/ingest"), e.getMessage());
        assertFalse(e.getMessage().contains("POST /api/"), e.getMessage());
        verifyNoInteractions(projectionIngest);
    }

    // ---- POST /api/ingest/season-projections (specs/030 C3) ----

    @Test
    void seasonProjectionsWithNbaReachesTheIngestService() {
        var stub = new com.ballknowers.draftsim.ingest.SeasonProjectionIngestService.Result(
                true, false, 529, 0, 1591, "rotowire", java.time.Instant.parse("2026-10-11T00:00:00Z"), null, 0);
        when(seasonProjectionIngest.refresh(Sport.NBA, 2026, false)).thenReturn(stub);

        assertSame(stub, controller().seasonProjections("nba", 2026, false));
        verify(seasonProjectionIngest).refresh(Sport.NBA, 2026, false);
    }

    /** The 400 comes from the service's IllegalArgumentException through ErrorHandler. */
    @Test
    void seasonProjectionsWithNflIsRefusedByTheService() {
        when(seasonProjectionIngest.refresh(Sport.NFL, 2026, true))
                .thenThrow(new IllegalArgumentException("season projections aren't wired up for nfl leagues"));

        assertThrows(IllegalArgumentException.class, () -> controller().seasonProjections("nfl", 2026, true));
    }

    // ---- POST /api/ingest/brackets/{id} (specs/034 C2) ----

    @Test
    void bracketsReturnsThePerSeasonCountsAndPassesForceThrough() {
        when(bracketIngest.backfillChain("L1", true)).thenReturn(List.of(
                new com.ballknowers.draftsim.ingest.BracketIngestService.SeasonResult(2025, "L1", 7, false)));

        var resp = controller().brackets("L1", true);

        assertEquals(200, resp.getStatusCode().value());
        assertEquals(List.of(new com.ballknowers.draftsim.ingest.BracketIngestService.SeasonResult(2025, "L1", 7, false)),
                resp.getBody().get("seasons"));
        verify(bracketIngest).backfillChain("L1", true);
    }

    @Test
    void bracketsOnAnUnstoredLeagueIs404() {
        when(bracketIngest.backfillChain("nope", false)).thenReturn(List.of());
        assertEquals(404, controller().brackets("nope", false).getStatusCode().value());
    }
}
