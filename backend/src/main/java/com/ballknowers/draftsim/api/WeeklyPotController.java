package com.ballknowers.draftsim.api;

import com.ballknowers.draftsim.engine.WeeklyPotService;
import com.ballknowers.draftsim.store.LeagueMemberRepository;
import com.ballknowers.draftsim.store.LeagueMembership;
import com.ballknowers.draftsim.store.LeagueRepository;
import com.ballknowers.draftsim.store.LeagueWeeklyPotRepository;
import com.ballknowers.draftsim.store.ManagerRepository;
import com.fasterxml.jackson.databind.JsonNode;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

/**
 * The weekly high-score pot (specs/048-weekly-high-score-ledger, contracts/weekly-pot-api.md). Per
 * league-SEASON like the conduct list: {@code membership.visibleLeague} addresses the exact row in the URL,
 * with no {@code LeagueSeasonResolver} fallback (research R8), because the on/off switch is per row.
 *
 * <p>The service's records are the response. They carry legitimately-null fields, so nothing here is built
 * with {@code Map.of} on a value that may be null.
 */
@RestController
@RequestMapping("/api")
public class WeeklyPotController {

    /** Hand-set sanity ceiling on a weekly stake ($1,000); arbitrary, mirrors the V41 check constraint. */
    static final int MAX_STAKE_CENTS = 100000;

    private final WeeklyPotService pot;
    private final LeagueMembership membership;
    private final LeagueWeeklyPotRepository settings;
    private final LeagueMemberRepository leagueMembers;
    private final ManagerRepository managers;

    public WeeklyPotController(WeeklyPotService pot, LeagueMembership membership,
                               LeagueWeeklyPotRepository settings, LeagueMemberRepository leagueMembers,
                               ManagerRepository managers) {
        this.pot = pot;
        this.membership = membership;
        this.settings = settings;
        this.leagueMembers = leagueMembers;
        this.managers = managers;
    }

    @GetMapping("/leagues/{sleeperId}/weekly-pot")
    public ResponseEntity<?> weeklyPot(@PathVariable String sleeperId,
                                       @RequestHeader(value = "X-Sleeper-User", required = false) String sleeperUserId) {
        Optional<LeagueRepository.LeagueRow> found = membership.visibleLeague(sleeperId, sleeperUserId);
        if (found.isEmpty()) return ResponseEntity.notFound().build();
        return ResponseEntity.ok(pot.view(found.get(), sleeperUserId));
    }

    /**
     * Commissioner-only, on the same honour system as the conduct list (the identity is a header). The body is
     * read as a tree, not a record: Jackson would coerce {@code 150.7} into an {@code int} stake of 150 and
     * hide the mistake, so a non-integral number is refused here (plan A11). Every check runs before the DB.
     */
    @PutMapping("/leagues/{sleeperId}/weekly-pot/settings")
    public ResponseEntity<?> saveSettings(@PathVariable String sleeperId,
                                          @RequestBody(required = false) JsonNode body,
                                          @RequestHeader(value = "X-Sleeper-User", required = false) String sleeperUserId) {
        Optional<LeagueRepository.LeagueRow> found = membership.visibleLeague(sleeperId, sleeperUserId);
        if (found.isEmpty()) return ResponseEntity.notFound().build();
        LeagueRepository.LeagueRow row = found.get();
        if (!membership.canCommission(row.id(), sleeperUserId)) return forbidden(row);

        JsonNode enabled = body == null ? null : body.get("enabled");
        JsonNode stake = body == null ? null : body.get("stakeCents");
        if (enabled == null || !enabled.isBoolean()) return bad("enabled is required (true or false)");
        if (stake == null || !stake.isIntegralNumber() || !stake.canConvertToInt()) {
            return bad("stakeCents is required and must be a whole number of cents");
        }
        int cents = stake.intValue();
        if (cents < 1 || cents > MAX_STAKE_CENTS) return bad("stakeCents must be between 1 and " + MAX_STAKE_CENTS);

        settings.upsert(row.id(), enabled.booleanValue(), cents, ManagerRepository.caller(managers, row.id(), sleeperUserId));
        return ResponseEntity.ok(pot.view(row, sleeperUserId));
    }

    private static ResponseEntity<?> bad(String message) {
        Map<String, Object> response = new LinkedHashMap<>();
        response.put("message", message);
        return ResponseEntity.badRequest().body(response);
    }

    /** Same message shape as {@code SuperlativesController.forbidden}, so the two endpoints can't disagree. */
    private ResponseEntity<?> forbidden(LeagueRepository.LeagueRow row) {
        boolean commissionerKnown = leagueMembers.anyCommissioner(row.id());
        String message = commissionerKnown
                ? "only this league's Sleeper commissioner may change the weekly pot"
                : "No commissioner is recorded for this league yet.";
        Map<String, Object> response = new LinkedHashMap<>();
        response.put("message", message);
        response.put("commissionerKnown", commissionerKnown);
        return ResponseEntity.status(403).body(response);
    }
}
