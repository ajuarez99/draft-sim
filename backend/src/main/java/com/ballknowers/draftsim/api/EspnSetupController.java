package com.ballknowers.draftsim.api;

import com.ballknowers.draftsim.domain.Sport;
import com.ballknowers.draftsim.ingest.espn.EspnAccessDenied;
import com.ballknowers.draftsim.ingest.espn.EspnAddFailure;
import com.ballknowers.draftsim.ingest.espn.EspnClient;
import com.ballknowers.draftsim.ingest.espn.EspnCredential;
import com.ballknowers.draftsim.ingest.espn.EspnException;
import com.ballknowers.draftsim.ingest.espn.EspnHttpError;
import com.ballknowers.draftsim.ingest.espn.EspnLeagueIngestService;
import com.ballknowers.draftsim.ingest.espn.EspnLeagueRef;
import com.ballknowers.draftsim.ingest.espn.EspnNotFound;
import com.ballknowers.draftsim.ingest.espn.EspnSeasons;
import com.ballknowers.draftsim.ingest.espn.EspnShapeChanged;
import com.ballknowers.draftsim.ingest.espn.EspnUnsupported;
import com.ballknowers.draftsim.security.CredentialCipher;
import com.ballknowers.draftsim.store.LeagueCredentialRepository;
import com.ballknowers.draftsim.store.LeagueMemberRepository;
import com.ballknowers.draftsim.store.LeagueMembership;
import com.ballknowers.draftsim.store.LeagueRefreshRepository;
import com.ballknowers.draftsim.store.LeagueRepository;
import com.ballknowers.draftsim.store.ManagerLinkRepository;
import com.ballknowers.draftsim.store.ManagerRepository;
import com.ballknowers.draftsim.store.RosterSeasonRepository;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * {@code /api/setup/espn/**} (spec 035 contracts/espn-setup.md). The same identity gate as the other
 * {@code /api/setup/*} routes: {@code X-Sleeper-User} is required.
 *
 * <p>Hygiene (plan F14): the request record holds an {@link EspnCredential}, never two strings; every
 * error body is a contract code plus fixed text, never {@code getMessage()} of anything wrapped; every
 * response map is a {@link LinkedHashMap} because nullable values are present.
 */
@RestController
@RequestMapping("/api/setup/espn")
public class EspnSetupController {

    /**
     * @param link        a pasted fantasy.espn.com link, or null
     * @param leagueId    with {@code sport} and {@code season}, the alternative to a link; {@code season} is
     *                    the APP season (see EspnSeasons), not ESPN's
     * @param credential  optional cookies; stored encrypted for this league after a successful add
     * @param claimRosterId the team the caller says is theirs; absent means "show me the teams, write nothing"
     */
    public record AddRequest(String link, String leagueId, String sport, Integer season,
                             EspnCredential credential, Integer claimRosterId) {}

    public record ClaimRequest(Integer rosterId) {}

    private final EspnLeagueIngestService ingest;
    private final EspnClient client;
    private final LeagueRepository leagues;
    private final LeagueMembership membership;
    private final ManagerRepository managers;
    private final ManagerLinkRepository links;
    private final RosterSeasonRepository rosterSeasons;
    private final LeagueCredentialRepository credentials;
    private final CredentialCipher cipher;
    private final LeagueRefreshRepository refreshes;
    private final LeagueMemberRepository leagueMembers;

    public EspnSetupController(EspnLeagueIngestService ingest, EspnClient client, LeagueRepository leagues,
                               LeagueMembership membership, ManagerRepository managers, ManagerLinkRepository links,
                               RosterSeasonRepository rosterSeasons, LeagueCredentialRepository credentials,
                               CredentialCipher cipher, LeagueRefreshRepository refreshes,
                               LeagueMemberRepository leagueMembers) {
        this.refreshes = refreshes;
        this.leagueMembers = leagueMembers;
        this.ingest = ingest;
        this.client = client;
        this.leagues = leagues;
        this.membership = membership;
        this.managers = managers;
        this.links = links;
        this.rosterSeasons = rosterSeasons;
        this.credentials = credentials;
        this.cipher = cipher;
    }

    @PostMapping("/league")
    public ResponseEntity<?> add(@RequestBody AddRequest body,
                                 @RequestHeader(value = "X-Sleeper-User", required = false) String sleeperUserId) {
        if (LeagueMembership.isAnonymous(sleeperUserId)) return error("SIGN_IN_REQUIRED", 401);
        try {
            EspnLeagueRef ref = refOf(body);
            return ResponseEntity.ok(ingest.add(ref, body.credential(), sleeperUserId, body.claimRosterId()));
        } catch (RuntimeException e) {
            return refusal(e);
        }
    }

    private EspnLeagueRef refOf(AddRequest body) {
        if (body == null) throw new IllegalArgumentException(EspnLeagueRef.MALFORMED_REF);
        if (body.link() != null && !body.link().isBlank()) {
            // The default season depends on the sport, which only the link says; parse once with a
            // placeholder and again with the sport's own default only when the link had no season.
            EspnLeagueRef probe = EspnLeagueRef.parse(body.link(), 2000);
            if (body.link().contains("seasonId=")) return probe;
            return EspnLeagueRef.parse(body.link(), ingest.defaultSeason(probe.sport()));
        }
        if (body.leagueId() == null || body.sport() == null || body.season() == null) {
            throw new IllegalArgumentException(EspnLeagueRef.MALFORMED_REF);
        }
        Sport sport;
        try {
            sport = Sport.fromCode(body.sport());
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException(EspnLeagueRef.MALFORMED_REF);
        }
        return EspnLeagueRef.of(body.leagueId(), sport, body.season());
    }

    @PostMapping("/league/{leagueKey}/claim")
    public ResponseEntity<?> claim(@PathVariable String leagueKey, @RequestBody ClaimRequest body,
                                   @RequestHeader(value = "X-Sleeper-User", required = false) String sleeperUserId) {
        if (LeagueMembership.isAnonymous(sleeperUserId)) return error("SIGN_IN_REQUIRED", 401);
        Optional<LeagueRepository.LeagueRow> league = membership.visibleLeague(leagueKey, sleeperUserId)
                .filter(l -> !l.isSleeper());
        if (league.isEmpty() || body == null || body.rosterId() == null) return error("NOT_FOUND", 404);
        Optional<Long> target = rosterSeasons.forLeague(league.get().id()).stream()
                .filter(r -> r.rosterId() == body.rosterId() && r.managerId() != null)
                .map(RosterSeasonRepository.StandingRow::managerId).findFirst();
        if (target.isEmpty()) return error("NOT_FOUND", 404);
        Long me = managers.idsBySleeperUserId().get(sleeperUserId);
        if (me == null) return error("NOT_FOUND", 404);
        // S7: the team's owners are its primary owner plus any co-owner listed under the same team name in
        // this league (league_member keeps no roster id; a duplicated team name could over-include, which is
        // the same unverified-claim model as everything here). Link to one nobody else holds.
        List<Long> owners = new java.util.ArrayList<>();
        owners.add(target.get());
        String teamName = leagueMembers.forLeague(league.get().id()).stream()
                .filter(m -> m.managerId() == target.get()).map(LeagueMemberRepository.MemberRow::teamName)
                .filter(java.util.Objects::nonNull).findFirst().orElse(null);
        if (teamName != null) {
            for (LeagueMemberRepository.MemberRow m : leagueMembers.forLeague(league.get().id())) {
                if (teamName.equals(m.teamName()) && !owners.contains(m.managerId())) owners.add(m.managerId());
            }
        }
        Optional<Long> linkTo = links.claimable(me, owners);
        if (linkTo.isEmpty()) return error("ALREADY_CLAIMED", 409);
        try {
            links.unlinkInLeague(me, league.get().id());
            links.link(me, linkTo.get(), league.get().id());
        } catch (ManagerLinkRepository.AlreadyClaimed e) {
            return error("ALREADY_CLAIMED", 409);
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("leagueKey", leagueKey);
        out.put("rosterId", body.rosterId());
        out.put("managerId", linkTo.get());
        return ResponseEntity.ok(out);
    }

    @PutMapping("/league/{leagueKey}/credential")
    public ResponseEntity<?> putCredential(@PathVariable String leagueKey, @RequestBody EspnCredential body,
                                           @RequestHeader(value = "X-Sleeper-User", required = false) String sleeperUserId) {
        if (LeagueMembership.isAnonymous(sleeperUserId)) return error("SIGN_IN_REQUIRED", 401);
        Optional<LeagueRepository.LeagueRow> league = membership.visibleLeague(leagueKey, sleeperUserId)
                .filter(l -> !l.isSleeper());
        if (league.isEmpty()) return error("NOT_FOUND", 404);
        if (!cipher.isConfigured()) return error("CREDENTIALS_NOT_CONFIGURED", 503);
        LeagueRepository.LeagueRow row = league.get();
        Long me = managers.idsBySleeperUserId().get(sleeperUserId);
        Optional<Long> owner = credentials.owner(row.sport().code(), row.platformLeagueId());
        if (me == null || (owner.isPresent() && !owner.get().equals(me))) return error("NOT_OWNER", 403);
        try {
            // The body IS an EspnCredential (redacted toString): a plain record of two Strings here printed both
            // cookies in Spring's own DEBUG line "Read application/json to [...]" (found by CredentialLeakIT).
            EspnCredential cred = body;
            if (cred == null) throw new IllegalArgumentException("MALFORMED_CREDENTIAL");
            // Test-read before storing: cookies that ESPN rejects are never kept.
            client.league(EspnLeagueRef.gameOf(row.sport()), row.platformLeagueId(),
                    EspnSeasons.toEspn(row.sport(), row.season()), Set.of("mSettings"), cred);
            credentials.put(row.sport().code(), row.platformLeagueId(), me, cred);
            // Working cookies are a reason to retry now: forget a refusal the old cookies caused, so the
            // next visit does not wait out the failure back-off.
            for (LeagueRepository.LeagueRow season : leagues.espnSeasons(row.sport(), row.platformLeagueId())) {
                refreshes.clearFailureIf(season.id(), "ESPN_ACCESS_DENIED");
            }
            return ResponseEntity.noContent().build();
        } catch (EspnAccessDenied e) {
            return error("ESPN_CREDENTIAL_REJECTED", 403);
        } catch (RuntimeException e) {
            return refusal(e);
        }
    }

    @DeleteMapping("/league/{leagueKey}/credential")
    public ResponseEntity<?> deleteCredential(@PathVariable String leagueKey,
                                              @RequestHeader(value = "X-Sleeper-User", required = false) String sleeperUserId) {
        if (LeagueMembership.isAnonymous(sleeperUserId)) return error("SIGN_IN_REQUIRED", 401);
        Optional<LeagueRepository.LeagueRow> league = membership.visibleLeague(leagueKey, sleeperUserId)
                .filter(l -> !l.isSleeper());
        if (league.isEmpty()) return error("NOT_FOUND", 404);
        LeagueRepository.LeagueRow row = league.get();
        Long me = managers.idsBySleeperUserId().get(sleeperUserId);
        Optional<Long> owner = credentials.owner(row.sport().code(), row.platformLeagueId());
        if (owner.isPresent() && !owner.get().equals(me)) return error("NOT_OWNER", 403);
        credentials.delete(row.sport().code(), row.platformLeagueId());
        return ResponseEntity.noContent().build();
    }

    // ---- error mapping: code + fixed text, never a wrapped exception's message -------------------

    private static final Map<String, String> TEXT = Map.ofEntries(
            Map.entry("MALFORMED_REF", "That doesn't look like an ESPN fantasy league link."),
            Map.entry("ESPN_NOT_FOUND", "ESPN has no such league."),
            Map.entry("ESPN_PRIVATE_NO_ACCESS", "This ESPN league is private. Add your ESPN login cookies to read it."),
            Map.entry("ESPN_CREDENTIAL_REJECTED", "ESPN rejected those cookies for this league."),
            Map.entry("UNSUPPORTED_SCORING", "Ball Knowers only supports ESPN points leagues."),
            Map.entry("CREDENTIALS_NOT_CONFIGURED", "Private ESPN leagues are not enabled on this server."),
            Map.entry("ESPN_SHAPE_CHANGED", "ESPN sent data in a shape Ball Knowers does not understand."),
            Map.entry("ALREADY_CLAIMED", "Someone else has already claimed that team."),
            Map.entry("NOT_OWNER", "Only the person who added the cookies can change them."),
            Map.entry("NOT_FOUND", "Not found."),
            Map.entry("TEAM_NOT_FOUND", "That team is not in this league."),
            Map.entry("SIGN_IN_REQUIRED", "Sign in first."),
            Map.entry("ESPN_HTTP_ERROR", "ESPN could not be reached."));

    private ResponseEntity<?> refusal(RuntimeException e) {
        if (e instanceof EspnAddFailure f) return error(f.code(), f.status());
        if (e instanceof ManagerLinkRepository.AlreadyClaimed) return error("ALREADY_CLAIMED", 409);
        if (e instanceof IllegalArgumentException) {
            // MALFORMED_REF / MALFORMED_CREDENTIAL carry fixed text and never echo the input.
            return error("MALFORMED_CREDENTIAL".equals(e.getMessage()) ? "MALFORMED_CREDENTIAL" : "MALFORMED_REF", 400);
        }
        if (e instanceof EspnNotFound) return error("ESPN_NOT_FOUND", 404);
        if (e instanceof EspnAccessDenied) return error("ESPN_PRIVATE_NO_ACCESS", 403);
        if (e instanceof EspnUnsupported u) return error(u.code(), 422, "Ball Knowers only supports ESPN points leagues (this one is " + u.detail() + ").");
        if (e instanceof EspnShapeChanged s) return error("ESPN_SHAPE_CHANGED", 502, "ESPN sent data Ball Knowers could not read (field " + s.fieldPath() + ").");
        if (e instanceof EspnHttpError h) return error(h.code(), 502, TEXT.get("ESPN_HTTP_ERROR"));
        if (e instanceof EspnException x) return error(x.code(), 502, TEXT.get("ESPN_HTTP_ERROR"));
        if (e instanceof IllegalStateException && "CREDENTIALS_NOT_CONFIGURED".equals(e.getMessage())) {
            return error("CREDENTIALS_NOT_CONFIGURED", 503);
        }
        throw e;
    }

    private static ResponseEntity<?> error(String code, int status) {
        return error(code, status, TEXT.getOrDefault(code, code));
    }

    private static ResponseEntity<?> error(String code, int status, String message) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("error", code);
        body.put("message", message);
        return ResponseEntity.status(status).body(body);
    }
}
