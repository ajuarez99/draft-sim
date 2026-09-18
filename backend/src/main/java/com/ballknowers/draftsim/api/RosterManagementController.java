package com.ballknowers.draftsim.api;

import com.ballknowers.draftsim.engine.RosterManagementService;
import com.ballknowers.draftsim.engine.TransactionAnalysisService;
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
    private final TransactionAnalysisService transactions;

    public RosterManagementController(RosterManagementService rosterManagement,
                                      TransactionAnalysisService transactions) {
        this.rosterManagement = rosterManagement;
        this.transactions = transactions;
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
        // Present and null unless the page moved to an earlier season, which it
        // must announce rather than silently show a different year.
        out.put("requestedSeason", r.requestedSeason());
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

    /**
     * Transactions extend the Roster Management PAGE rather than adding a route
     * of their own, matching ffwrapped, where the counts, trades and waiver
     * adds are sections of the same view (contracts/destinations.md).
     */
    @GetMapping("/leagues/{sleeperId}/transactions")
    public ResponseEntity<Map<String, Object>> transactions(@PathVariable String sleeperId) {
        return transactions.forLeague(sleeperId)
                .map(r -> ResponseEntity.ok(transactionsBody(r)))
                .orElseGet(() -> ResponseEntity.notFound().build());
    }

    private static Map<String, Object> transactionsBody(TransactionAnalysisService.Result r) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("available", r.available());
        out.put("season", r.season());
        out.put("sport", r.sport().code());
        // Stated, never implied: a 4 beside a name is good, and nothing on the
        // page says so unless this does (US6.3).
        out.put("rankDirection", r.rankDirection());
        if (!r.available()) {
            out.put("reason", r.reason());
            out.put("byManager", List.of());
            out.put("trades", List.of());
            out.put("adds", List.of());
            return out;
        }
        List<Map<String, Object>> managers = new ArrayList<>();
        for (TransactionAnalysisService.ManagerCounts m : r.byManager()) {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("managerId", m.managerId());
            row.put("teamName", m.teamName());
            row.put("counts", m.counts());
            row.put("total", m.total());
            managers.add(row);
        }
        out.put("byManager", managers);

        List<Map<String, Object>> trades = new ArrayList<>();
        for (TransactionAnalysisService.Trade t : r.trades()) {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("week", t.week());
            List<Map<String, Object>> sides = new ArrayList<>();
            for (TransactionAnalysisService.TradeSide side : t.sides()) {
                Map<String, Object> s = new LinkedHashMap<>();
                s.put("teamName", side.teamName());
                s.put("received", side.received().stream().map(RosterManagementController::movedBody).toList());
                sides.add(s);
            }
            row.put("sides", sides);
            trades.add(row);
        }
        out.put("trades", trades);

        List<Map<String, Object>> adds = new ArrayList<>();
        for (TransactionAnalysisService.Add a : r.adds()) {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("week", a.week());
            row.put("teamName", a.teamName());
            row.put("type", a.type());
            row.put("status", a.status());
            row.put("added", movedBody(a.added()));
            row.put("dropped", a.dropped() == null ? null : movedBody(a.dropped()));
            row.put("faabBid", a.faabBid());
            adds.add(row);
        }
        out.put("adds", adds);
        return out;
    }

    private static Map<String, Object> movedBody(TransactionAnalysisService.MovedPlayer p) {
        if (p == null) return null;
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("playerId", p.playerId());
        out.put("playerName", p.playerName());
        out.put("position", p.position());
        // Null when no week has been played since the move: ungraded, not bad.
        out.put("postMovePositionalRank", p.postMoveRank());
        out.put("weeksCounted", p.weeksCounted());
        return out;
    }
}
