package com.ballknowers.draftsim.api;

import com.ballknowers.draftsim.engine.ScoreOverrideService;
import com.ballknowers.draftsim.store.LeagueMemberRepository;
import com.ballknowers.draftsim.store.LeagueMembership;
import com.ballknowers.draftsim.store.LeagueRepository;
import com.fasterxml.jackson.databind.JsonNode;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;

/**
 * The commissioner's score entry (spec 057). Per league-SEASON row like the weekly pot: {@code
 * membership.visibleLeague} addresses the exact row in the URL, and the caller supplies the Sleeper id the
 * weekly report actually showed (the report can fall back to an earlier season than the page's route).
 *
 * <p>Same honour system as the conduct list and the pot: the identity is a header. The body is read as a tree
 * so a points value is checked as the number it is (refused, never rounded). Every check runs before the DB.
 */
@RestController
@RequestMapping("/api")
public class ScoreOverrideController {

    /** numeric(8,2) holds below 1,000,000; anything at or above is refused rather than failing in the DB. */
    static final BigDecimal MAX_POINTS = new BigDecimal("1000000");

    private final ScoreOverrideService service;
    private final LeagueMembership membership;
    private final LeagueMemberRepository leagueMembers;

    public ScoreOverrideController(ScoreOverrideService service, LeagueMembership membership,
                                   LeagueMemberRepository leagueMembers) {
        this.service = service;
        this.membership = membership;
        this.leagueMembers = leagueMembers;
    }

    @GetMapping("/leagues/{sleeperId}/score-overrides/{week}")
    public ResponseEntity<?> get(@PathVariable String sleeperId, @PathVariable int week,
                                 @RequestHeader(value = "X-Sleeper-User", required = false) String sleeperUserId) {
        Optional<LeagueRepository.LeagueRow> found = membership.visibleLeague(sleeperId, sleeperUserId);
        if (found.isEmpty()) return ResponseEntity.notFound().build();
        if (week < 1) return bad("week must be 1 or more");
        return ResponseEntity.ok(service.view(found.get(), week, sleeperUserId));
    }

    @PutMapping("/leagues/{sleeperId}/score-overrides/{week}")
    public ResponseEntity<?> save(@PathVariable String sleeperId, @PathVariable int week,
                                  @RequestBody(required = false) JsonNode body,
                                  @RequestHeader(value = "X-Sleeper-User", required = false) String sleeperUserId) {
        Optional<LeagueRepository.LeagueRow> found = membership.visibleLeague(sleeperId, sleeperUserId);
        if (found.isEmpty()) return ResponseEntity.notFound().build();
        LeagueRepository.LeagueRow row = found.get();
        if (!membership.canCommission(row.id(), sleeperUserId)) return forbidden(row);
        if (week < 1) return bad("week must be 1 or more");
        if (!service.isFinal(row.id(), week)) {
            return bad("week " + week + " is not final yet; scores can be entered once the week is closed");
        }

        JsonNode scores = body == null ? null : body.get("scores");
        if (scores == null || !scores.isArray()) return bad("scores is required: a list of {rosterId, points}");
        Map<Integer, BigDecimal> parsed = new TreeMap<>();
        for (JsonNode s : scores) {
            JsonNode rosterId = s.get("rosterId");
            JsonNode points = s.get("points");
            if (rosterId == null || !rosterId.isIntegralNumber() || !rosterId.canConvertToInt()) {
                return bad("every score needs a whole-number rosterId");
            }
            int id = rosterId.intValue();
            if (points == null || !points.isNumber()) return bad("roster " + id + ": points is required and must be a number");
            if (points.isFloatingPointNumber() && !Double.isFinite(points.doubleValue())) {
                return bad("roster " + id + ": points is too large");
            }
            BigDecimal value;
            try {
                value = points.decimalValue();
            } catch (NumberFormatException e) {
                return bad("roster " + id + ": points must be a finite number");
            }
            if (value.signum() < 0) return bad("roster " + id + ": points cannot be negative");
            if (value.stripTrailingZeros().scale() > 2) {
                return bad("roster " + id + ": points can have at most 2 decimal places (nothing is rounded)");
            }
            if (value.compareTo(MAX_POINTS) >= 0) return bad("roster " + id + ": points is too large");
            if (parsed.put(id, value.stripTrailingZeros().setScale(2)) != null) return bad("roster " + id + " appears more than once");
        }

        Set<Integer> stored = new TreeSet<>(service.storedRosters(row.id(), week));
        if (stored.isEmpty()) return bad("week " + week + " has no stored scores to replace");
        Set<Integer> missing = new TreeSet<>(stored);
        missing.removeAll(parsed.keySet());
        Set<Integer> extra = new TreeSet<>(parsed.keySet());
        extra.removeAll(stored);
        if (!missing.isEmpty() || !extra.isEmpty()) {
            StringBuilder m = new StringBuilder("a week is saved whole: send every team that played");
            if (!missing.isEmpty()) m.append("; missing roster(s) ").append(missing);
            if (!extra.isEmpty()) m.append("; roster(s) ").append(extra).append(" did not play that week");
            return bad(m.toString());
        }

        service.save(row, week, parsed, sleeperUserId);
        return ResponseEntity.ok(service.view(row, week, sleeperUserId));
    }

    @DeleteMapping("/leagues/{sleeperId}/score-overrides/{week}")
    public ResponseEntity<?> clear(@PathVariable String sleeperId, @PathVariable int week,
                                   @RequestHeader(value = "X-Sleeper-User", required = false) String sleeperUserId) {
        Optional<LeagueRepository.LeagueRow> found = membership.visibleLeague(sleeperId, sleeperUserId);
        if (found.isEmpty()) return ResponseEntity.notFound().build();
        LeagueRepository.LeagueRow row = found.get();
        if (!membership.canCommission(row.id(), sleeperUserId)) return forbidden(row);
        if (week < 1) return bad("week must be 1 or more");
        service.clear(row, week);
        return ResponseEntity.ok(service.view(row, week, sleeperUserId));
    }

    private static ResponseEntity<?> bad(String message) {
        Map<String, Object> response = new LinkedHashMap<>();
        response.put("message", message);
        return ResponseEntity.badRequest().body(response);
    }

    /** Same message shape as {@code WeeklyPotController.forbidden}, so the endpoints can't disagree. */
    private ResponseEntity<?> forbidden(LeagueRepository.LeagueRow row) {
        boolean commissionerKnown = leagueMembers.anyCommissioner(row.id());
        String message = commissionerKnown
                ? "only this league's Sleeper commissioner may enter weekly scores"
                : "No commissioner is recorded for this league yet.";
        Map<String, Object> response = new LinkedHashMap<>();
        response.put("message", message);
        response.put("commissionerKnown", commissionerKnown);
        return ResponseEntity.status(403).body(response);
    }
}
