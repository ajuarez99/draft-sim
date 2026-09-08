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
        requireNflForNow(s, Route.ADP);
        return ffcAdp.ingest(s);
    }

    /** ~5MB from Sleeper. Once a day is plenty. */
    @PostMapping("/players")
    public PlayerIngestService.Result players(@RequestParam(defaultValue = "nfl") String sport) {
        Sport s = Sport.fromCode(sport);
        requireNflForNow(s, Route.PLAYERS);
        return playerIngest.ingest(s);
    }

    /** Walks previous_league_id backwards and ingests every season it finds. */
    @PostMapping("/league/{sleeperLeagueId}")
    public LeagueIngestService.Result league(@PathVariable String sleeperLeagueId) {
        Sport sport = leagueIngest.inferSport(sleeperLeagueId);
        requireNflForNow(sport, sleeperLeagueId);
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
        requireNflForNow(sport, sleeperLeagueId);
        return leagueHistoryIngest.ingestChain(sport, sleeperLeagueId);
    }

    /**
     * Rebuilds the blended board, backfills adp_at_time onto historical picks, then
     * writes the fitted half of every manager profile. Never touches manual_json.
     */
    @PostMapping("/board")
    public Map<String, Object> board(@RequestParam(defaultValue = "nfl") String sport) {
        Sport s = Sport.fromCode(sport);
        requireNflForNow(s, Route.BOARD);
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
        requireNflForNow(sport, sleeperLeagueId);

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("players", playerIngest.ingest(sport));
        out.put("league", leagueIngest.ingestChain(sport, sleeperLeagueId));
        out.put("adp", ffcAdp.ingest(sport));
        out.put("board", boards.rebuild(sport));
        out.put("profilesWritten", profiles.persistFitted(sport));
        return out;
    }

    /**
     * TEMPORARY GUARD -- delete this method, the {@link Route} enum, and all six
     * call sites above once Phase 5 of claude/multi-sport-and-rebrand.md lands
     * basketball ingest (BasketballRules, PlayerIngestService's NBA
     * fantasyPositions cases, DraftSlot's reversal-round handling). Until then,
     * letting an inferred or requested NBA sport past this point would corrupt
     * data in a different way at every call site:
     *
     * <ul>
     *   <li>{@link #league}/{@link #leagueHistory}/{@link #all}: sport is
     *   inferred from Sleeper, not chosen by the caller, so there is no
     *   "don't pass ?sport=nba" escape hatch -- the guard is the only thing
     *   standing between a basketball league id and everything below.
     *   <li>{@link #adp} (and the ADP step inside {@link #board}): runs
     *   {@link FfcAdpService}, which fetches from fantasyfootballcalculator.com,
     *   a football-only vendor, and matches the result against {@code
     *   players.findAll(NBA)}. Sleeper reuses its numeric player-id space across
     *   sports (2092 of 2109 NBA ids collide with an NFL id) and real people
     *   share names across sports, so "it just won't match anything" is not a
     *   safe assumption -- this would write real {@code adp_snapshot} rows
     *   tagged {@code nba} built from football numbers.
     *   <li>{@link #board}: after that same ADP ingest, runs {@code
     *   boards.rebuild(NBA)} then {@code profiles.persistFitted(NBA)} before
     *   Phase 2's contamination fix exists -- {@code DraftRepository
     *   .allCompletedPicks()} has no sport filter, so fitting "basketball"
     *   profiles would fit them from football picks and persist them as
     *   {@code manager_profile} rows tagged {@code nba}, inflating {@code
     *   observed} for every manager who also plays football (10 of the 12 Ball
     *   Knowers managers do) and silently under-shrinking their football
     *   profiles too.
     *   <li>{@link #players}: {@code PlayerIngestService.fantasyPositions()} has
     *   no basketball cases yet (that's Phase 5 work), so NBA positions would be
     *   dropped or mis-mapped into the {@code positions text[]} column.
     * </ul>
     *
     * Refuse rather than corrupt.
     */
    private static void requireNflForNow(Sport sport, String sleeperLeagueId) {
        if (sport != Sport.NFL) {
            throw new IllegalArgumentException("league " + sleeperLeagueId + " is sport '" + sport.code()
                    + "', but ingest only supports football for now -- basketball ingest is gated on Phase 5"
                    + " of claude/multi-sport-and-rebrand.md");
        }
    }

    /**
     * Same refusal as {@link #requireNflForNow(Sport, String)}, for the three
     * {@code ?sport=}-parameterised routes that have no league id to name --
     * the message instead names the route so the reader knows which one
     * refused. A distinct enum-typed overload rather than a same-erasure
     * {@code String} overload, since {@code (Sport, String)} is already taken.
     */
    private static void requireNflForNow(Sport sport, Route route) {
        if (sport != Sport.NFL) {
            throw new IllegalArgumentException(route.path + " was called with sport '" + sport.code()
                    + "', but ingest only supports football for now -- basketball ingest is gated on Phase 5"
                    + " of claude/multi-sport-and-rebrand.md");
        }
    }

    /** The three {@code ?sport=}-parameterised routes {@link #requireNflForNow(Sport, Route)} guards. */
    private enum Route {
        ADP("POST /api/ingest/adp"), PLAYERS("POST /api/ingest/players"), BOARD("POST /api/ingest/board");

        private final String path;

        Route(String path) {
            this.path = path;
        }
    }
}
