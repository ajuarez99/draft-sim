package com.ballknowers.draftsim.api;

import com.ballknowers.draftsim.domain.Sport;
import com.ballknowers.draftsim.ingest.BoardService;
import com.ballknowers.draftsim.ingest.FfcAdpService;
import com.ballknowers.draftsim.ingest.LeagueIngestService;
import com.ballknowers.draftsim.ingest.PlayerIngestService;
import com.ballknowers.draftsim.profile.ProfileService;
import org.springframework.web.bind.annotation.*;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Ingest is manual and idempotent. Order matters the first time:
 * players -> leagues -> adp -> board (board reads the latest adp/ffc snapshot).
 */
@RestController
@RequestMapping("/api/ingest")
public class IngestController {

    private final PlayerIngestService playerIngest;
    private final LeagueIngestService leagueIngest;
    private final FfcAdpService ffcAdp;
    private final BoardService boards;
    private final ProfileService profiles;

    public IngestController(PlayerIngestService playerIngest, LeagueIngestService leagueIngest,
                            FfcAdpService ffcAdp, BoardService boards, ProfileService profiles) {
        this.playerIngest = playerIngest;
        this.leagueIngest = leagueIngest;
        this.ffcAdp = ffcAdp;
        this.boards = boards;
        this.profiles = profiles;
    }

    /** FFC ADP for the league shape configured in weights.yml. See claude/adp-sources.md. */
    @PostMapping("/adp")
    public FfcAdpService.Result adp() {
        return ffcAdp.ingest(Sport.NFL);
    }

    /** ~5MB from Sleeper. Once a day is plenty. */
    @PostMapping("/players")
    public PlayerIngestService.Result players() {
        return playerIngest.ingest(Sport.NFL);
    }

    /** Walks previous_league_id backwards and ingests every season it finds. */
    @PostMapping("/league/{sleeperLeagueId}")
    public LeagueIngestService.Result league(@PathVariable String sleeperLeagueId) {
        return leagueIngest.ingestChain(Sport.NFL, sleeperLeagueId);
    }

    /**
     * Rebuilds the blended board, backfills adp_at_time onto historical picks, then
     * writes the fitted half of every manager profile. Never touches manual_json.
     */
    @PostMapping("/board")
    public Map<String, Object> board() {
        FfcAdpService.Result adp = ffcAdp.ingest(Sport.NFL);
        BoardService.Result result = boards.rebuild(Sport.NFL);
        int written = profiles.persistFitted(Sport.NFL);
        return Map.of("adp", adp, "board", result, "profilesWritten", written);
    }

    /** Everything, in order. Safe to re-run. */
    @PostMapping("/all/{sleeperLeagueId}")
    public Map<String, Object> all(@PathVariable String sleeperLeagueId) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("players", playerIngest.ingest(Sport.NFL));
        out.put("league", leagueIngest.ingestChain(Sport.NFL, sleeperLeagueId));
        out.put("adp", ffcAdp.ingest(Sport.NFL));
        out.put("board", boards.rebuild(Sport.NFL));
        out.put("profilesWritten", profiles.persistFitted(Sport.NFL));
        return out;
    }
}
