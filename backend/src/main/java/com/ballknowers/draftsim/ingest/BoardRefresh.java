package com.ballknowers.draftsim.ingest;

import com.ballknowers.draftsim.domain.Sport;
import com.ballknowers.draftsim.profile.ProfileService;
import org.springframework.stereotype.Service;

/**
 * The board rebuild sequence, in one place: FFC ADP, then the blended board, then
 * the fitted half of every manager profile
 * (specs/009-auto-data-refresh T055, research R15).
 *
 * <p>Shared by {@code POST /api/ingest/board} and the daily job's {@code BOARD}
 * step so the two cannot drift apart. The order matters: the board reads the ADP
 * snapshot just written, and profiles read the board's {@code adp_at_time}.
 */
@Service
public class BoardRefresh {

    private final FfcAdpService ffcAdp;
    private final BoardService boards;
    private final ProfileService profiles;

    public BoardRefresh(FfcAdpService ffcAdp, BoardService boards, ProfileService profiles) {
        this.ffcAdp = ffcAdp;
        this.boards = boards;
        this.profiles = profiles;
    }

    public record Result(FfcAdpService.Result adp, BoardService.Result board, int profilesWritten) {}

    public Result run(Sport sport) {
        FfcAdpService.Result adp = ffcAdp.ingest(sport);
        BoardService.Result board = boards.rebuild(sport);
        int written = profiles.persistFitted(sport);
        return new Result(adp, board, written);
    }
}
