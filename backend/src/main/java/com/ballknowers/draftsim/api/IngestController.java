package com.ballknowers.draftsim.api;

import com.ballknowers.draftsim.domain.Sport;
import com.ballknowers.draftsim.ingest.BoardService;
import com.ballknowers.draftsim.ingest.LeagueIngestService;
import com.ballknowers.draftsim.ingest.PlayerIngestService;
import org.springframework.web.bind.annotation.*;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Ingest is manual and idempotent. Order matters the first time:
 * players -> leagues -> board.
 */
@RestController
@RequestMapping("/api/ingest")
public class IngestController {

    private final PlayerIngestService playerIngest;
    private final LeagueIngestService leagueIngest;
    private final BoardService boards;

    public IngestController(PlayerIngestService playerIngest, LeagueIngestService leagueIngest,
                            BoardService boards) {
        this.playerIngest = playerIngest;
        this.leagueIngest = leagueIngest;
        this.boards = boards;
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

    /** Rebuilds the blended board and backfills adp_at_time onto historical picks. */
    @PostMapping("/board")
    public BoardService.Result board() {
        return boards.rebuild(Sport.NFL);
    }

    /** Everything, in order. Safe to re-run. */
    @PostMapping("/all/{sleeperLeagueId}")
    public Map<String, Object> all(@PathVariable String sleeperLeagueId) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("players", playerIngest.ingest(Sport.NFL));
        out.put("league", leagueIngest.ingestChain(Sport.NFL, sleeperLeagueId));
        out.put("board", boards.rebuild(Sport.NFL));
        return out;
    }
}
