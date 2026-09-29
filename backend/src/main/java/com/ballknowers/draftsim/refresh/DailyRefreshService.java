package com.ballknowers.draftsim.refresh;

import com.ballknowers.draftsim.domain.Sport;
import com.ballknowers.draftsim.ingest.BoardRefresh;
import com.ballknowers.draftsim.ingest.PlayerIngestService;
import com.ballknowers.draftsim.store.DailyCaptureRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;

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

    public enum Outcome { DONE, DONE_ADP_FAILED_BEST_EFFORT, SKIPPED_ALREADY_TODAY, FAILED }

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

    public DailyRefreshService(PlayerIngestService playerIngest, BoardRefresh boardRefresh,
                               DailyCaptureRepository captures) {
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

    private static StepResult failed(Sport sport, String kind, Exception e) {
        log.warn("daily refresh {} {} failed: {}", sport.code(), kind, e.toString());
        return new StepResult(sport, kind, Outcome.FAILED, e.getClass().getSimpleName() + ": " + e.getMessage());
    }

    /** The capture date is UTC, so the job's day doesn't move with the server's zone. */
    private static LocalDate today() {
        return LocalDate.now(ZoneOffset.UTC);
    }
}
