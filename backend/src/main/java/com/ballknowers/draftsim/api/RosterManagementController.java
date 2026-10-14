package com.ballknowers.draftsim.api;

import com.ballknowers.draftsim.engine.OwnershipTimeline;
import com.ballknowers.draftsim.engine.RosterManagementService;
import com.ballknowers.draftsim.engine.SeasonWindow;
import com.ballknowers.draftsim.engine.TradeVerdictService;
import com.ballknowers.draftsim.engine.TransactionAnalysisService;
import com.ballknowers.draftsim.store.LeagueMembership;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestHeader;
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
    private final LeagueMembership membership;

    public RosterManagementController(RosterManagementService rosterManagement,
                                      TransactionAnalysisService transactions,
                                      LeagueMembership membership) {
        this.rosterManagement = rosterManagement;
        this.transactions = transactions;
        this.membership = membership;
    }

    @GetMapping("/leagues/{sleeperId}/roster-management")
    public ResponseEntity<Map<String, Object>> rosterManagement(@PathVariable String sleeperId,
                                                                @RequestHeader(value = "X-Sleeper-User", required = false) String sleeperUserId) {
        // Scoped like every other league route: no identity, or an identity that is not in
        // this league, is the same 404 as a league that does not exist. This route had no
        // scoping at all before claude/audit-2026-09-28/01, so ANY caller could read it.
        if (membership.visibleLeague(sleeperId, sleeperUserId).isEmpty()) return ResponseEntity.notFound().build();
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
        // Spec 013: grades are badged early while fewer than SeasonWindow.EARLY_THRESHOLD_WEEKS are scored.
        out.put("gradesEarly", SeasonWindow.isEarly(r.weeksScored()));
        // Spec 013: the threshold travels with the flag so the page can state the rule without its own copy.
        out.put("earlyThresholdWeeks", SeasonWindow.EARLY_THRESHOLD_WEEKS);
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
            row.put("username", t.username());
            row.put("avatarId", t.avatarId());
            row.put("totalPoints", t.totalPoints());
            row.put("potentialPoints", t.potentialPoints());
            // null, not 1.0, when there is no potential to divide by.
            row.put("efficiency", t.efficiency());
            // Spec 013: null with no efficiency or when no grade cutoffs are configured.
            row.put("grade", t.grade());
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
    public ResponseEntity<Map<String, Object>> transactions(@PathVariable String sleeperId,
                                                            @RequestHeader(value = "X-Sleeper-User", required = false) String sleeperUserId) {
        // Scoped like every other league route: no identity, or an identity that is not in
        // this league, is the same 404 as a league that does not exist. This route had no
        // scoping at all before claude/audit-2026-09-28/01, so ANY caller could read it.
        if (membership.visibleLeague(sleeperId, sleeperUserId).isEmpty()) return ResponseEntity.notFound().build();
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
        // Spec 033: "so far" vs "final", the last week any verdict could count, and whether every
        // stored trade carries its picks and FAAB (false until the V32 backfill has run).
        out.put("seasonFinal", r.seasonFinal());
        out.put("tradeAssetsLoaded", r.tradeAssetsLoaded());
        out.put("throughWeek", r.throughWeek());
        out.put("reconciliation", reconciliationBody(r.reconciliation()));
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

        // Built mutably, never Map.of: winnerRosterId, margin, position, firstWeek and the like
        // are legitimately null, and Map.of throws on a null value.
        List<Map<String, Object>> trades = new ArrayList<>();
        for (TradeVerdictService.TradeResult t : r.trades()) trades.add(tradeBody(t));
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

    private static Map<String, Object> tradeBody(TradeVerdictService.TradeResult t) {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("txId", t.txId());
        row.put("week", t.week());
        row.put("acceptedAt", t.acceptedAt() == null ? null : t.acceptedAt().toString());
        row.put("assetsLoaded", t.assetsLoaded());
        Map<String, Object> verdict = new LinkedHashMap<>();
        verdict.put("state", t.verdict().state());
        verdict.put("winnerRosterId", t.verdict().winnerRosterId());
        verdict.put("margin", t.verdict().margin());
        verdict.put("weeksCounted", t.verdict().weeksCounted());
        verdict.put("final", t.verdict().isFinal());
        row.put("verdict", verdict);
        List<Map<String, Object>> sides = new ArrayList<>();
        for (TradeVerdictService.SideResult side : t.sides()) {
            Map<String, Object> s = new LinkedHashMap<>();
            s.put("rosterId", side.rosterId());
            s.put("teamName", side.teamName());
            s.put("startedTotal", side.startedTotal());
            s.put("benchTotal", side.benchTotal());
            s.put("weeksCounted", side.weeksCounted());
            List<Map<String, Object>> assets = new ArrayList<>();
            for (TradeVerdictService.AssetResult a : side.assets()) assets.add(assetBody(a));
            s.put("assets", assets);
            sides.add(s);
        }
        row.put("sides", sides);
        return row;
    }

    private static Map<String, Object> assetBody(TradeVerdictService.AssetResult asset) {
        Map<String, Object> out = new LinkedHashMap<>();
        switch (asset) {
            case TradeVerdictService.PlayerResult p -> {
                out.put("kind", "PLAYER");
                out.putAll(playerFields(p));
            }
            case TradeVerdictService.PickResult k -> {
                out.put("kind", "PICK");
                out.put("season", k.season());
                out.put("round", k.round());
                out.put("originalRosterId", k.originalRosterId());
                out.put("status", k.status());
                out.put("pickNo", k.pickNo());
                out.put("player", k.player() == null ? null : assetBody(k.player()));
            }
            case TradeVerdictService.FaabResult f -> {
                out.put("kind", "FAAB");
                out.put("amount", f.amount());
                out.put("fromRosterId", f.fromRosterId());
                out.put("fromTeamName", f.fromTeamName());
            }
        }
        return out;
    }

    private static Map<String, Object> playerFields(TradeVerdictService.PlayerResult p) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("playerId", p.playerId());
        out.put("playerName", p.playerName());
        out.put("position", p.position());
        out.put("status", p.status());
        out.put("firstWeek", p.firstWeek());
        out.put("lastWeek", p.lastWeek());
        out.put("startedPoints", p.startedPoints());
        out.put("benchPoints", p.benchPoints());
        out.put("weeksCounted", p.weeksCounted());
        out.put("duplicateWeeks", p.duplicateWeeks());
        out.put("postMovePositionalRank", p.postMoveRank());
        List<Map<String, Object>> stretches = new ArrayList<>();
        for (TradeVerdictService.StretchResult st : p.stretches()) {
            Map<String, Object> s = new LinkedHashMap<>();
            s.put("rosterId", st.rosterId());
            s.put("teamName", st.teamName());
            s.put("firstWeek", st.firstWeek());
            s.put("lastWeek", st.lastWeek());
            s.put("how", st.how());
            s.put("viaTxId", st.viaTxId());
            s.put("startedPoints", st.startedPoints());
            s.put("benchPoints", st.benchPoints());
            s.put("duplicateWeeks", st.duplicateWeeks());
            s.put("leadingUnstarted", st.leadingUnstarted());
            stretches.add(s);
        }
        out.put("stretches", stretches);
        return out;
    }

    private static Map<String, Object> reconciliationBody(OwnershipTimeline.Reconciliation rec) {
        if (rec == null) return null;
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("playerWeeksChecked", rec.playerWeeksChecked());
        out.put("unexplainedStretches", rec.unexplainedStretches());
        out.put("sameWeekInAndOut", rec.sameWeekInAndOut());
        out.put("neverOnAScoredRoster", rec.neverOnAScoredRoster());
        out.put("examples", rec.examples());
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
