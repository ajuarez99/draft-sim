package com.ballknowers.draftsim.api;

import com.ballknowers.draftsim.domain.Sport;
import com.ballknowers.draftsim.ingest.BoardRefresh;
import com.ballknowers.draftsim.ingest.FfcAdpService;
import com.ballknowers.draftsim.ingest.LeagueHistoryIngestService;
import com.ballknowers.draftsim.ingest.LeagueIngestService;
import com.ballknowers.draftsim.ingest.SleeperClient;
import com.ballknowers.draftsim.store.LeagueMembership;
import com.ballknowers.draftsim.store.LeagueRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * The member-scoped twin of the operator's {@code /api/ingest/**} routes, for the
 * flows a signed-in person runs from the browser: "Add a league", the setup
 * stages, and "Load past seasons".
 *
 * <p>{@code /api/ingest/**} now needs the admin token (claude/audit-2026-09-28/01),
 * which a browser must never hold. These routes do the same work but are
 * authorised by <em>league membership</em> instead: the caller must be signed in
 * ({@code X-Sleeper-User}) and appear in the league they name -- either it is
 * already visible to them, or Sleeper's own public league-users list for it
 * contains their id. That last clause is what lets a brand-new league be added:
 * nothing is in our database yet, so Sleeper is the only source of truth for
 * "is this person in this league".
 *
 * <p><b>Scoping, not security</b>, exactly like every other route keyed on
 * {@code X-Sleeper-User}: the header is an unverified claim and league members'
 * ids are public. What this closes is that a header-less request, or a stranger
 * naming a league they are not in, can no longer make the server crawl an
 * arbitrary league. It does not stop someone who presents a real member's id.
 *
 * <p>{@code adp} and {@code board} are sport-wide rebuilds, not league-addressed,
 * so their gate is "signed in and known to be in at least one league here" (which
 * the setup flow satisfies, because it ingests the league first).
 */
@RestController
@RequestMapping("/api/setup")
public class MemberSetupController {

    private static final Logger log = LoggerFactory.getLogger(MemberSetupController.class);

    private final LeagueMembership membership;
    private final LeagueRepository leagues;
    private final SleeperClient sleeper;
    private final LeagueIngestService leagueIngest;
    private final LeagueHistoryIngestService leagueHistoryIngest;
    private final FfcAdpService ffcAdp;
    private final BoardRefresh boardRefresh;

    public MemberSetupController(LeagueMembership membership, LeagueRepository leagues, SleeperClient sleeper,
                                 LeagueIngestService leagueIngest, LeagueHistoryIngestService leagueHistoryIngest,
                                 FfcAdpService ffcAdp, BoardRefresh boardRefresh) {
        this.membership = membership;
        this.leagues = leagues;
        this.sleeper = sleeper;
        this.leagueIngest = leagueIngest;
        this.leagueHistoryIngest = leagueHistoryIngest;
        this.ffcAdp = ffcAdp;
        this.boardRefresh = boardRefresh;
    }

    /** Same work as {@code POST /api/ingest/league/{id}}: walks the chain and ingests every season. */
    @PostMapping("/league/{sleeperLeagueId}")
    public ResponseEntity<?> league(@PathVariable String sleeperLeagueId,
                                    @RequestHeader(value = "X-Sleeper-User", required = false) String sleeperUserId) {
        ResponseEntity<?> refusal = requireLeagueMember(sleeperLeagueId, sleeperUserId);
        if (refusal != null) return refusal;
        Sport sport = leagueIngest.inferSport(sleeperLeagueId);
        return ResponseEntity.ok(leagueIngest.ingestChain(sport, sleeperLeagueId));
    }

    /** Same work as {@code POST /api/ingest/league-history/{id}} ("Load past seasons"). */
    @PostMapping("/league-history/{sleeperLeagueId}")
    public ResponseEntity<?> leagueHistory(@PathVariable String sleeperLeagueId,
                                           @RequestHeader(value = "X-Sleeper-User", required = false) String sleeperUserId) {
        ResponseEntity<?> refusal = requireLeagueMember(sleeperLeagueId, sleeperUserId);
        if (refusal != null) return refusal;
        Sport sport = leagueIngest.inferSport(sleeperLeagueId);
        // A manual re-ingest skips nothing: it is how a suspect season gets rebuilt.
        return ResponseEntity.ok(leagueHistoryIngest.ingestChain(sport, sleeperLeagueId, Set.of()));
    }

    /** Same work as {@code POST /api/ingest/adp}. */
    @PostMapping("/adp")
    public ResponseEntity<?> adp(@RequestParam(defaultValue = "nfl") String sport,
                                 @RequestHeader(value = "X-Sleeper-User", required = false) String sleeperUserId) {
        ResponseEntity<?> refusal = requireKnownMember(sleeperUserId);
        if (refusal != null) return refusal;
        return ResponseEntity.ok(ffcAdp.ingest(Sport.fromCode(sport)));
    }

    /** Same work as {@code POST /api/ingest/board}. */
    @PostMapping("/board")
    public ResponseEntity<?> board(@RequestParam(defaultValue = "nfl") String sport,
                                   @RequestHeader(value = "X-Sleeper-User", required = false) String sleeperUserId) {
        ResponseEntity<?> refusal = requireKnownMember(sleeperUserId);
        if (refusal != null) return refusal;
        BoardRefresh.Result r = boardRefresh.run(Sport.fromCode(sport));
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("adp", r.adp());
        out.put("board", r.board());
        out.put("profilesWritten", r.profilesWritten());
        return ResponseEntity.ok(out);
    }

    private static ResponseEntity<?> signInRequired() {
        return ResponseEntity.status(401).body(Map.of("error", "X-Sleeper-User is required"));
    }

    /** Null when the caller may proceed; otherwise the refusal to return. */
    private ResponseEntity<?> requireKnownMember(String sleeperUserId) {
        if (LeagueMembership.isAnonymous(sleeperUserId)) return signInRequired();
        if (membership.leagueIdsFor(sleeperUserId).isEmpty()) {
            return ResponseEntity.status(403).body(Map.of("error",
                    "load one of your own leagues first -- this rebuild is for people in a league here"));
        }
        return null;
    }

    /**
     * Null when the caller is in this league, by our own database or by Sleeper's
     * league-users list; otherwise a 401 (no identity) or a 404 that does not say
     * whether the league exists.
     */
    private ResponseEntity<?> requireLeagueMember(String sleeperLeagueId, String sleeperUserId) {
        if (LeagueMembership.isAnonymous(sleeperUserId)) return signInRequired();

        Optional<LeagueRepository.LeagueRow> known = leagues.bySleeperId(sleeperLeagueId);
        if (known.isPresent() && membership.canSee(sleeperUserId, known.get().id())) return null;

        try {
            List<Map<String, Object>> users = sleeper.leagueUsers(sleeperLeagueId);
            if (users != null) {
                for (Map<String, Object> u : users) {
                    if (sleeperUserId.equals(String.valueOf(u.get("user_id")))) return null;
                }
            }
        } catch (RuntimeException e) {
            // Sleeper 404s on an unknown league and can also just be down; either way
            // the caller cannot be shown to belong, so refuse without a detail.
            log.warn("league-users lookup for {} failed during setup membership check: {}",
                    sleeperLeagueId, e.toString());
        }
        return ResponseEntity.status(404).body(Map.of("error",
                "league " + sleeperLeagueId + " was not found among your leagues"));
    }
}
