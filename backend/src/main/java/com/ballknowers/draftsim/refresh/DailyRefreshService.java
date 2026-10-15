package com.ballknowers.draftsim.refresh;

import com.ballknowers.draftsim.domain.Sport;
import com.ballknowers.draftsim.ingest.BoardRefresh;
import com.ballknowers.draftsim.engine.ProjectionSeason;
import com.ballknowers.draftsim.engine.SeasonStateService;
import com.ballknowers.draftsim.ingest.PlayerIngestService;
import com.ballknowers.draftsim.ingest.ProjectionSnapshotService;
import com.ballknowers.draftsim.ingest.SeasonProjectionIngestService;
import com.ballknowers.draftsim.store.DailyCaptureRepository;
import com.ballknowers.draftsim.store.LeagueRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.OptionalInt;

/**
 * The scheduled job's work (specs/009-auto-data-refresh US3, contracts/refresh-api.md):
 * for each sport, the player list (which also captures suspensions), then the
 * board (FFC ADP, board rebuild, fitted profiles, via {@link BoardRefresh}).
 *
 * <p>Each step that succeeds records a {@code daily_capture} row, and a step whose
 * row already exists for today's UTC date is skipped, so a hand re-run costs
 * nothing (FR-009). A step that throws is recorded as {@code FAILED} in the
 * result, writes no row, and does not stop the next step. FFC's best-effort
 * failure inside {@code BOARD} is {@link Outcome#DONE_ADP_FAILED_BEST_EFFORT}: the
 * board was still rebuilt, so it does not count as a failure (spec amendment 8).
 *
 * <p>{@code TRENDING} (spec 014) is best-effort: see {@link #trending}.
 *
 * <p>{@code SEASON_PROJECTIONS} (spec 030) is best-effort too: see {@link #seasonProjections}.
 *
 * <p>{@code PROJECTION_SNAPSHOT} (spec 036) is best-effort and runs for both sports, after the
 * steps above it so the NBA season-average line it copies is that morning's: see {@link #projectionSnapshot}.
 *
 * <p>{@code BOARD} is not skipped by a same-day row: the contract skips only
 * {@code PLAYERS} ("skipped if today's row exists"), and the board must reflect a
 * re-run after a failed ADP fetch. It still records its row for the day-gap
 * check (SC-006).
 */
@Service
public class DailyRefreshService {

    private static final Logger log = LoggerFactory.getLogger(DailyRefreshService.class);

    static final String KIND_PLAYERS = "PLAYERS";
    static final String KIND_BOARD = "BOARD";
    static final String KIND_TRENDING = "TRENDING";
    static final String KIND_SEASON_PROJECTIONS = "SEASON_PROJECTIONS";
    static final String KIND_PROJECTION_SNAPSHOT = "PROJECTION_SNAPSHOT";

    public enum Outcome {
        DONE, DONE_ADP_FAILED_BEST_EFFORT,
        /** Trending is decoration (spec 014): its failure is reported but never fails the run. */
        DONE_TRENDING_FAILED_BEST_EFFORT,
        /**
         * Spec 030 (plan review N7): the source is an undocumented Sleeper endpoint, so a wobble is
         * reported but must not make the daily refresh answer 500.
         */
        DONE_SEASON_PROJECTIONS_FAILED_BEST_EFFORT,
        /**
         * Spec 036: the snapshot reads two undocumented Sleeper endpoints, so a wobble is reported but
         * must not make the run answer 500. Stored snapshots are untouched by a failed run.
         */
        DONE_PROJECTION_SNAPSHOT_FAILED_BEST_EFFORT,
        SKIPPED_ALREADY_TODAY, FAILED
    }

    /** {@code detail} is nullable. */
    public record StepResult(Sport sport, String kind, Outcome outcome, String detail) {}

    public record DailyResult(LocalDate date, List<StepResult> steps) {
        /** Failure iff any non-skipped step is FAILED. */
        public boolean failed() {
            return steps.stream().anyMatch(s -> s.outcome() == Outcome.FAILED);
        }
    }

    private final PlayerIngestService playerIngest;
    private final BoardRefresh boardRefresh;
    private final DailyCaptureRepository captures;
    private final TrendingRefresh trendingRefresh;
    private final SeasonProjectionIngestService seasonProjectionIngest;
    private final LeagueRepository leagues;
    private final SeasonStateService seasonState;
    private final ProjectionSnapshotService projectionSnapshots;

    public DailyRefreshService(PlayerIngestService playerIngest, BoardRefresh boardRefresh,
                               DailyCaptureRepository captures, TrendingRefresh trendingRefresh,
                               SeasonProjectionIngestService seasonProjectionIngest,
                               LeagueRepository leagues, SeasonStateService seasonState,
                               ProjectionSnapshotService projectionSnapshots) {
        this.projectionSnapshots = projectionSnapshots;
        this.seasonProjectionIngest = seasonProjectionIngest;
        this.leagues = leagues;
        this.seasonState = seasonState;
        this.trendingRefresh = trendingRefresh;
        this.playerIngest = playerIngest;
        this.boardRefresh = boardRefresh;
        this.captures = captures;
    }

    public DailyResult runAll() {
        LocalDate today = today();
        List<StepResult> steps = new ArrayList<>();
        for (Sport sport : Sport.values()) {
            steps.add(players(sport));
            steps.add(board(sport));
            steps.add(trending(sport));
            // Basketball only for now: football's projections are weekly and refreshed by hand.
            if (sport == Sport.NBA) steps.add(seasonProjections(sport));
            steps.add(projectionSnapshot(sport));
        }
        return new DailyResult(today, steps);
    }

    /** The {@code PLAYERS} step for one sport; also what {@code POST /api/refresh/players} runs. */
    public StepResult players(Sport sport) {
        LocalDate today = today();
        try {
            if (captures.exists(sport, today, KIND_PLAYERS)) {
                return new StepResult(sport, KIND_PLAYERS, Outcome.SKIPPED_ALREADY_TODAY, null);
            }
            PlayerIngestService.Result r = playerIngest.ingest(sport);
            String detail = "playersWritten=" + r.playersWritten() + " suspendedCount=" + r.suspendedCount();
            captures.record(sport, today, KIND_PLAYERS, Instant.now(), detail);
            return new StepResult(sport, KIND_PLAYERS, Outcome.DONE, detail);
        } catch (Exception e) {
            return failed(sport, KIND_PLAYERS, e);
        }
    }

    private StepResult board(Sport sport) {
        LocalDate today = today();
        try {
            BoardRefresh.Result r = boardRefresh.run(sport);
            String detail = "entries=" + r.board().entries() + " profilesWritten=" + r.profilesWritten()
                    + " adpRows=" + r.adp().rows();
            // FFC's football-only skip for another sport reads enabled=false and is plain DONE.
            Outcome outcome = r.adp().fetchFailed() ? Outcome.DONE_ADP_FAILED_BEST_EFFORT : Outcome.DONE;
            if (outcome == Outcome.DONE_ADP_FAILED_BEST_EFFORT) detail += " adp=" + r.adp().derivation();
            captures.record(sport, today, KIND_BOARD, Instant.now(), detail);
            return new StepResult(sport, KIND_BOARD, outcome, detail);
        } catch (Exception e) {
            return failed(sport, KIND_BOARD, e);
        }
    }

    /**
     * Best-effort (specs/014-home-player-spotlight): {@code DONE} on a fetch, {@code
     * SKIPPED_ALREADY_TODAY} when the stored list is still fresh, and a failure is
     * {@link Outcome#DONE_TRENDING_FAILED_BEST_EFFORT}, never {@code FAILED}. Records no
     * {@code daily_capture} row: its freshness gate is the stored {@code fetched_at}.
     */
    StepResult trending(Sport sport) {
        TrendingRefresh.Outcome r = trendingRefresh.refreshIfStale(sport, Instant.now());
        return switch (r) {
            case DONE -> new StepResult(sport, KIND_TRENDING, Outcome.DONE, null);
            case SKIPPED_FRESH -> new StepResult(sport, KIND_TRENDING, Outcome.SKIPPED_ALREADY_TODAY, null);
            case FAILED -> new StepResult(sport, KIND_TRENDING, Outcome.DONE_TRENDING_FAILED_BEST_EFFORT,
                    "trending fetch failed; the stored list, if any, is unchanged");
        };
    }

    /**
     * Spec 030 C4: refresh the sport's whole-season projections when it has a current-season league
     * that is not finished. "Current" is the newest season any league of the sport is in; a league
     * that is {@code COMPLETE} no longer needs them. Best-effort: a failure is {@link
     * Outcome#DONE_SEASON_PROJECTIONS_FAILED_BEST_EFFORT}, never {@code FAILED}. A set that is still
     * fresh is a {@code DONE} with detail "fresh". Records no {@code daily_capture} row: its
     * freshness gate is the stored {@code fetched_at}, as with trending.
     */
    StepResult seasonProjections(Sport sport) {
        try {
            // The gate is shared with PROJECTION_SNAPSHOT (spec 036), so the two can't drift.
            OptionalInt current = ProjectionSeason.current(leagues, seasonState, sport);
            if (current.isEmpty()) {
                return new StepResult(sport, KIND_SEASON_PROJECTIONS, Outcome.DONE,
                        "no current-season league needs projections");
            }
            int season = current.getAsInt();
            SeasonProjectionIngestService.Result r = seasonProjectionIngest.refresh(sport, season, false);
            if (r.rejectedReason() != null) {
                return new StepResult(sport, KIND_SEASON_PROJECTIONS, Outcome.DONE_SEASON_PROJECTIONS_FAILED_BEST_EFFORT,
                        r.rejectedReason());
            }
            String detail = r.fresh() ? "fresh" : "rowsStored=" + r.rowsStored() + " rowsDeleted=" + r.rowsDeleted();
            return new StepResult(sport, KIND_SEASON_PROJECTIONS, Outcome.DONE, detail);
        } catch (Exception e) {
            log.warn("daily refresh {} {} failed: {}", sport.code(), KIND_SEASON_PROJECTIONS, e.toString());
            return new StepResult(sport, KIND_SEASON_PROJECTIONS, Outcome.DONE_SEASON_PROJECTIONS_FAILED_BEST_EFFORT,
                    "season projections fetch failed; the stored set, if any, is unchanged");
        }
    }

    /**
     * Spec 036 US1: capture each upcoming game's pre-game projection and freeze it at the game's
     * cutoff. Best-effort, and it records no {@code daily_capture} row: its idempotence is the
     * guarded upsert, so a hand re-run the same morning only refreshes rows whose cutoff has not
     * passed. A sport with no current-season league that needs projections is a plain {@code DONE}.
     */
    StepResult projectionSnapshot(Sport sport) {
        try {
            ProjectionSnapshotService.Result r = projectionSnapshots.capture(sport, Instant.now());
            if (r.season() == null) {
                return new StepResult(sport, KIND_PROJECTION_SNAPSHOT, Outcome.DONE,
                        "no current-season league needs projections");
            }
            return new StepResult(sport, KIND_PROJECTION_SNAPSHOT, Outcome.DONE,
                    "weeks=" + r.weeks() + " rowsWritten=" + r.rowsWritten()
                            + " rowsFrozenSkipped=" + r.rowsFrozenSkipped() + " rowsNotPreGame=" + r.rowsNotPreGame());
        } catch (Exception e) {
            log.warn("daily refresh {} {} failed: {}", sport.code(), KIND_PROJECTION_SNAPSHOT, e.toString());
            return new StepResult(sport, KIND_PROJECTION_SNAPSHOT, Outcome.DONE_PROJECTION_SNAPSHOT_FAILED_BEST_EFFORT,
                    "projection snapshot failed (" + e.getClass().getSimpleName()
                            + "); stored snapshots are unchanged");
        }
    }

    private static StepResult failed(Sport sport, String kind, Exception e) {
        log.warn("daily refresh {} {} failed: {}", sport.code(), kind, e.toString());
        return new StepResult(sport, kind, Outcome.FAILED, e.getClass().getSimpleName() + ": " + e.getMessage());
    }

    /** The capture date is UTC, so the job's day doesn't move with the server's zone. */
    private static LocalDate today() {
        return LocalDate.now(ZoneOffset.UTC);
    }
}
