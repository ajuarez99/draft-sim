package com.ballknowers.draftsim.api;

import com.ballknowers.draftsim.engine.RosterManagementService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The Roster Management view's read endpoint
 * (specs/004-ffwrapped-feature-parity, US2;
 * contracts/league-analytics-api.md).
 *
 * <p>Named after the page rather than something like
 * {@code LeagueAnalyticsController}, which is one letter from the existing
 * {@link LeagueAnalysisController} and does something different. Two
 * near-identical names doing different things is the quiet-collision failure
 * this feature exists to clean up, so the split follows the existing
 * one-controller-per-page-family convention instead.
 */
@RestController
@RequestMapping("/api")
public class RosterManagementController {

    private final RosterManagementService rosterManagement;

    public RosterManagementController(RosterManagementService rosterManagement) {
        this.rosterManagement = rosterManagement;
    }

    @GetMapping("/leagues/{sleeperId}/roster-management")
    public ResponseEntity<Map<String, Object>> rosterManagement(@PathVariable String sleeperId) {
        return rosterManagement.forLeague(sleeperId)
                .map(r -> ResponseEntity.ok(body(r)))
                .orElseGet(() -> ResponseEntity.notFound().build());
    }

    /**
     * Hand-built rather than serialized off the record so the refusal case has
     * a shape a caller can branch on: {@code available:false} plus a reason,
     * never a table of zeros that reads as a real answer (US2.4).
     */
    private static Map<String, Object> body(RosterManagementService.Result r) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("available", r.available());
        out.put("season", r.season());
        out.put("sport", r.sport().code());
        out.put("weeksScored", r.weeksScored());
        if (!r.available()) {
            out.put("reason", r.reason());
            out.put("teams", List.of());
            return out;
        }
        List<Map<String, Object>> teams = new ArrayList<>();
        for (RosterManagementService.TeamRow t : r.teams()) {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("rosterId", t.rosterId());
            row.put("managerId", t.managerId());
            row.put("teamName", t.teamName());
            row.put("avatarId", t.avatarId());
            row.put("totalPoints", t.totalPoints());
            row.put("potentialPoints", t.potentialPoints());
            // null, not 1.0, when there is no potential to divide by.
            row.put("efficiency", t.efficiency());
            row.put("weeksCounted", t.weeksCounted());
            row.put("weeksExcluded", t.weeksExcluded());
            teams.add(row);
        }
        out.put("teams", teams);
        return out;
    }
}
