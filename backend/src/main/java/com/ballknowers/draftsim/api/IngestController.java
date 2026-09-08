package com.ballknowers.draftsim.api;

import com.ballknowers.draftsim.domain.Sport;
import com.ballknowers.draftsim.ingest.BoardService;
import com.ballknowers.draftsim.ingest.FfcAdpService;
import com.ballknowers.draftsim.ingest.LeagueHistoryIngestService;
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
    private final LeagueHistoryIngestService leagueHistoryIngest;
    private final FfcAdpService ffcAdp;
    private final BoardService boards;
    private final ProfileService profiles;

    public IngestController(PlayerIngestService playerIngest, LeagueIngestService leagueIngest,
                            LeagueHistoryIngestService leagueHistoryIngest,
                            FfcAdpService ffcAdp, BoardService boards, ProfileService profiles) {
        this.playerIngest = playerIngest;
        this.leagueIngest = leagueIngest;
        this.leagueHistoryIngest = leagueHistoryIngest;
        this.ffcAdp = ffcAdp;
        this.boards = boards;
        this.profiles = profiles;
    }

    /** FFC ADP for the league shape configured in weights.yml. See claude/adp-sources.md. */
    @PostMapping("/adp")
    public FfcAdpService.Result adp(@RequestParam(defaultValue = "nfl") String sport) {
        Sport s = Sport.fromCode(sport);
        return ffcAdp.ingest(s);
    }

    /** ~5MB from Sleeper. Once a day is plenty. */
    @PostMapping("/players")
    public PlayerIngestService.Result players(@RequestParam(defaultValue = "nfl") String sport) {
        Sport s = Sport.fromCode(sport);
        return playerIngest.ingest(s);
    }

    /** Walks previous_league_id backwards and ingests every season it finds. */
    @PostMapping("/league/{sleeperLeagueId}")
    public LeagueIngestService.Result league(@PathVariable String sleeperLeagueId) {
        Sport sport = leagueIngest.inferSport(sleeperLeagueId);
        return leagueIngest.ingestChain(sport, sleeperLeagueId);
    }

    /**
     * claude/league-suite.md Phase A: standings + weekly points for the same
     * league chain, independent of whether a draft has ever been ingested for
     * it. Idempotent; re-running only refetches weeks not already stored (plus
     * the most recently scored one) -- see LeagueHistoryIngestService.
     */
    @PostMapping("/league-history/{sleeperLeagueId}")
    public LeagueHistoryIngestService.Result leagueHistory(@PathVariable String sleeperLeagueId) {
        Sport sport = leagueIngest.inferSport(sleeperLeagueId);
        return leagueHistoryIngest.ingestChain(sport, sleeperLeagueId);
    }

    /**
     * Rebuilds the blended board, backfills adp_at_time onto historical picks, then
     * writes the fitted half of every manager profile. Never touches manual_json.
     */
    @PostMapping("/board")
    public Map<String, Object> board(@RequestParam(defaultValue = "nfl") String sport) {
        Sport s = Sport.fromCode(sport);
        FfcAdpService.Result adp = ffcAdp.ingest(s);
        BoardService.Result result = boards.rebuild(s);
        int written = profiles.persistFitted(s);
        return Map.of("adp", adp, "board", result, "profilesWritten", written);
    }

    /**
     * Everything, in order. Safe to re-run.
     *
     * Sport is inferred from Sleeper (the league's own {@code sport} field), not
     * a route parameter -- see {@link LeagueIngestService#inferSport}. Players
     * must be ingested before {@link #league}, so the sport has to be known
     * before that first call rather than read off whatever ingestChain fetches
     * -- one extra {@code GET /league/{id}} beyond what ingestChain's own chain
     * walk will fetch again, accepted as the cost of preserving the players-
     * before-leagues ordering.
     */
    @PostMapping("/all/{sleeperLeagueId}")
    public Map<String, Object> all(@PathVariable String sleeperLeagueId) {
        Sport sport = leagueIngest.inferSport(sleeperLeagueId);

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("players", playerIngest.ingest(sport));
        out.put("league", leagueIngest.ingestChain(sport, sleeperLeagueId));
        out.put("adp", ffcAdp.ingest(sport));
        out.put("board", boards.rebuild(sport));
        out.put("profilesWritten", profiles.persistFitted(sport));
        return out;
    }
}
