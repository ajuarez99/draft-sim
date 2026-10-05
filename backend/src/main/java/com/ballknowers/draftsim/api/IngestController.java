package com.ballknowers.draftsim.api;

import com.ballknowers.draftsim.domain.Sport;
import com.ballknowers.draftsim.ingest.BoardRefresh;
import com.ballknowers.draftsim.ingest.BoardService;
import com.ballknowers.draftsim.ingest.FfcAdpService;
import com.ballknowers.draftsim.ingest.LeagueHistoryIngestService;
import com.ballknowers.draftsim.ingest.PlayerGameIngestService;
import com.ballknowers.draftsim.ingest.TransactionIngestService;
import com.ballknowers.draftsim.ingest.LeagueIngestService;
import com.ballknowers.draftsim.ingest.PlayerIngestService;
import com.ballknowers.draftsim.ingest.ProjectionIngestService;
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
    private final ProjectionIngestService projectionIngest;
    private final TransactionIngestService transactionIngest;
    private final PlayerGameIngestService playerGameIngest;
    private final BoardRefresh boardRefresh;

    public IngestController(PlayerIngestService playerIngest, LeagueIngestService leagueIngest,
                            LeagueHistoryIngestService leagueHistoryIngest,
                            FfcAdpService ffcAdp, BoardService boards, ProfileService profiles,
                            ProjectionIngestService projectionIngest,
                            TransactionIngestService transactionIngest,
                            PlayerGameIngestService playerGameIngest,
                            BoardRefresh boardRefresh) {
        this.playerIngest = playerIngest;
        this.leagueIngest = leagueIngest;
        this.leagueHistoryIngest = leagueHistoryIngest;
        this.ffcAdp = ffcAdp;
        this.boards = boards;
        this.profiles = profiles;
        this.projectionIngest = projectionIngest;
        this.transactionIngest = transactionIngest;
        this.playerGameIngest = playerGameIngest;
        this.boardRefresh = boardRefresh;
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
    /**
     * specs/004-ffwrapped-feature-parity US6: roster moves.
     *
     * <p>Its own route rather than folded into /all, because it is the one
     * ingest that walks a call per week and a caller should be able to refresh
     * transactions without re-walking players, leagues and the board.
     */
    /**
     * Per-game stat lines for one sport-season (specs/005, US1; rebuilt in
     * specs/009-auto-data-refresh, research R6/R9).
     *
     * <p>Resolves the league's sport and season and refreshes that sport-season:
     * one Sleeper call per week not yet final, plus one schedule call, shared by
     * every league in the sport. The response's {@code weeksFetched} was
     * {@code playersWalked} and {@code weeksFailed} was {@code playersFailed}
     * when the unit of work was a player. Still its own endpoint, never part of
     * {@code /all} or {@code /league-history}.
     */
    @PostMapping("/player-games/{sleeperLeagueId}")
    public PlayerGameIngestService.Result playerGames(@PathVariable String sleeperLeagueId,
                                                      @RequestParam(required = false) Integer season) {
        return playerGameIngest.ingest(sleeperLeagueId, season);
    }

    @PostMapping("/transactions/{sleeperLeagueId}")
    public java.util.Map<String, Object> transactions(@PathVariable String sleeperLeagueId) {
        return java.util.Map.of("stored", transactionIngest.ingest(sleeperLeagueId));
    }

    @PostMapping("/league-history/{sleeperLeagueId}")
    public LeagueHistoryIngestService.Result leagueHistory(@PathVariable String sleeperLeagueId) {
        Sport sport = leagueIngest.inferSport(sleeperLeagueId);
        // A manual re-ingest skips nothing: it is how a suspect season gets rebuilt.
        return leagueHistoryIngest.ingestChain(sport, sleeperLeagueId, java.util.Set.of());
    }

    /**
     * claude/league-analysis.md Phase 1: weekly projections for a window of
     * weeks, the one source the League analysis page's roster projections read.
     *
     * A full rest-of-season refresh is ~13 calls and ~27 MB, which is why it
     * lives here rather than behind the page's own GET. Weeks refreshed within
     * the staleness window are skipped unless {@code force}.
     *
     * {@code sport} is required and rejected unless it is football, rather than
     * defaulted to nfl like the older routes on this controller. Those default
     * a *value*; this one would be defaulting a *rule* -- the stat keys here
     * (pts_ppr and friends) are football's, and a basketball caller silently
     * getting football projections is the shape of bug that has now shipped
     * three times in this repo.
     */
    @PostMapping("/projections")
    public ProjectionIngestService.Result projections(@RequestParam String sport,
                                                      @RequestParam int season,
                                                      @RequestParam int fromWeek,
                                                      @RequestParam int toWeek,
                                                      @RequestParam(defaultValue = "false") boolean force) {
        Sport s = Sport.fromCode(sport);
        if (s != Sport.NFL) {
            throw new IllegalArgumentException(
                    "projections are football-only for now: the stored columns are Sleeper's "
                            + "pts_ppr / pts_half_ppr / pts_std, which are football scoring totals. "
                            + "Basketball projections need per-game stat lines scored with each league's "
                            + "settings, which isn't built yet (claude/projection-tools.md).");
        }
        return projectionIngest.refresh(s.code(), season, fromWeek, toWeek, force);
    }

    /**
     * Rebuilds the blended board, backfills adp_at_time onto historical picks, then
     * writes the fitted half of every manager profile. Never touches manual_json.
     */
    @PostMapping("/board")
    public Map<String, Object> board(@RequestParam(defaultValue = "nfl") String sport) {
        Sport s = Sport.fromCode(sport);
        BoardRefresh.Result r = boardRefresh.run(s);
        return Map.of("adp", r.adp(), "board", r.board(), "profilesWritten", r.profilesWritten());
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
        // Same sequence as /board and the daily job (specs/009 research R15): one
        // implementation, so the three can't drift apart.
        BoardRefresh.Result board = boardRefresh.run(sport);
        out.put("adp", board.adp());
        out.put("board", board.board());
        out.put("profilesWritten", board.profilesWritten());
        return out;
    }
}
