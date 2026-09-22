package com.ballknowers.draftsim.api;

import com.ballknowers.draftsim.domain.Sport;
import com.ballknowers.draftsim.engine.HeadToHeadService;
import com.ballknowers.draftsim.engine.ManagerCareerService;
import com.ballknowers.draftsim.store.LeagueMembership;
import com.ballknowers.draftsim.store.RosterSeasonRepository;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;

/**
 * {@code GET /api/managers/{aId}/versus/{bId}} -- specs/006-deeper-history-both-sports
 * US4. The head-to-head record ffwrapped leaves permanently at {@code 0-0},
 * now that {@link HeadToHeadService} can actually derive one, plus a
 * side-by-side career comparison so the page needs one call, not three.
 *
 * <p><b>Both managers are gated, not just the one named first in the URL</b>
 * (T053). Same reason {@code LeagueHistoryController#managerHistory} gates
 * its own single id: "a manager id is a small integer, which makes every
 * person in the database walkable by counting upwards." A comparison
 * endpoint that only checked {@code aId} would let anyone who can see ANY
 * manager use {@code bId} to walk the whole table one response at a time.
 */
@RestController
@RequestMapping("/api")
public class ManagerComparisonController {

    private final LeagueMembership membership;
    private final HeadToHeadService headToHead;
    private final ManagerCareerService careers;
    private final RosterSeasonRepository rosterSeasons;

    public ManagerComparisonController(LeagueMembership membership, HeadToHeadService headToHead,
                                       ManagerCareerService careers, RosterSeasonRepository rosterSeasons) {
        this.membership = membership;
        this.headToHead = headToHead;
        this.careers = careers;
        this.rosterSeasons = rosterSeasons;
    }

    @GetMapping("/managers/{aId}/versus/{bId}")
    public ResponseEntity<?> versus(@PathVariable long aId, @PathVariable long bId,
                                    @RequestHeader(value = "X-Sleeper-User", required = false) String sleeperUserId) {
        if (!membership.canSeeManager(sleeperUserId, aId) || !membership.canSeeManager(sleeperUserId, bId)) {
            return ResponseEntity.notFound().build();
        }

        List<RosterSeasonRepository.StandingRow> aSeasons = rosterSeasons.forManager(aId);
        List<RosterSeasonRepository.StandingRow> bSeasons = rosterSeasons.forManager(bId);
        if (aSeasons.isEmpty() || bSeasons.isEmpty()) return ResponseEntity.notFound().build();

        HeadToHeadService.Result result = headToHead.compute(aId, bId);

        // T054: each side of `comparison` is read straight off ManagerCareerService
        // -- the exact service /managers/{id}/history's own career panel reads --
        // so this page and that one can never print two different numbers for
        // the same manager (contract's own field rule).
        Map<Sport, ManagerCareerService.CareerProfile> aCareers = bySport(careers.forManager(aId));
        Map<Sport, ManagerCareerService.CareerProfile> bCareers = bySport(careers.forManager(bId));

        Map<String, Object> response = new LinkedHashMap<>();
        response.put("a", managerRef(aId, aSeasons.get(0)));
        response.put("b", managerRef(bId, bSeasons.get(0)));
        response.put("sports", result.sports().stream()
                .map(s -> sportRow(s, aCareers.get(s.sport()), bCareers.get(s.sport())))
                .toList());
        response.put("sharedNothing", result.sharedNothing());
        return ResponseEntity.ok(response);
    }

    private static Map<Sport, ManagerCareerService.CareerProfile> bySport(List<ManagerCareerService.CareerProfile> list) {
        Map<Sport, ManagerCareerService.CareerProfile> out = new EnumMap<>(Sport.class);
        for (ManagerCareerService.CareerProfile c : list) out.put(c.sport(), c);
        return out;
    }

    private static Map<String, Object> managerRef(long managerId, RosterSeasonRepository.StandingRow row) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("managerId", managerId);
        m.put("manager", row.managerName());
        m.put("avatarId", row.avatarId());
        return m;
    }

    private static Map<String, Object> sportRow(HeadToHeadService.SportHeadToHead s,
                                                ManagerCareerService.CareerProfile aCareer,
                                                ManagerCareerService.CareerProfile bCareer) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("sport", s.sport());
        m.put("aWins", s.aWins());
        m.put("bWins", s.bWins());
        m.put("ties", s.ties());
        m.put("meetings", s.meetings().stream().map(ManagerComparisonController::meetingRow).toList());
        m.put("seasonsExcluded", s.seasonsExcluded().stream().map(ManagerComparisonController::excludedRow).toList());
        m.put("comparison", comparisonBlock(aCareer, bCareer));
        return m;
    }

    private static Map<String, Object> meetingRow(HeadToHeadService.Meeting m) {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("season", m.season());
        row.put("week", m.week());
        row.put("leagueName", m.leagueName());
        row.put("sleeperLeagueId", m.sleeperLeagueId());
        row.put("aPoints", m.aPoints());
        row.put("bPoints", m.bPoints());
        row.put("winner", m.winner());
        return row;
    }

    private static Map<String, Object> excludedRow(HeadToHeadService.SeasonExcluded e) {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("season", e.season());
        row.put("leagueName", e.leagueName());
        row.put("reason", e.reason());
        return row;
    }

    /**
     * contracts/head-to-head-api.md's {@code comparison} block, each side
     * read straight off {@link ManagerCareerService.CareerProfile} rather
     * than re-derived here -- a second implementation of "how many games did
     * they win" is exactly the defect class {@code OneEfficiencyImplementationTest}
     * (Phase 5) exists to catch one level down.
     *
     * <p>Guarded rather than asserted non-null: a sport can appear in
     * {@code sports[]} (both managers share a league-season there) while, in
     * principle, a CareerProfile lookup for it comes back empty for one side
     * -- {@code careers.forManager} only lists sports with an actual
     * roster-season, and a shared league-season IS a roster-season, so this
     * should never happen in practice. It is guarded anyway because a null
     * here must degrade the one `comparison` block, never 500 the whole
     * endpoint over a row this feature did not anticipate.
     */
    private static Map<String, Object> comparisonBlock(ManagerCareerService.CareerProfile a,
                                                        ManagerCareerService.CareerProfile b) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("titles", pair(a, b, ManagerCareerService.CareerProfile::titles));
        m.put("record", pair(a, b, ManagerComparisonController::record));
        m.put("pointsFor", pair(a, b, ManagerCareerService.CareerProfile::pointsFor));
        // pointsPerGame: pointsFor / (wins + losses + ties), NOT / weeks -- the
        // contract's own field rule, so it reconciles with the record beside
        // it rather than with averageEfficiency's own week-counted divisor.
        m.put("pointsPerGame", pair(a, b, ManagerComparisonController::pointsPerGame));
        m.put("winsAboveExpected", pair(a, b, ManagerCareerService.CareerProfile::winsAboveExpected));
        m.put("averageEfficiency", pair(a, b, ManagerCareerService.CareerProfile::averageEfficiency));
        m.put("seasonsCounted", pair(a, b, ManagerCareerService.CareerProfile::seasonsCounted));
        return m;
    }

    private static String record(ManagerCareerService.CareerProfile c) {
        return c.wins() + "-" + c.losses() + (c.ties() > 0 ? "-" + c.ties() : "");
    }

    private static Double pointsPerGame(ManagerCareerService.CareerProfile c) {
        int games = c.wins() + c.losses() + c.ties();
        return games > 0 ? round2(c.pointsFor() / games) : null;
    }

    private static <T> Map<String, Object> pair(ManagerCareerService.CareerProfile a, ManagerCareerService.CareerProfile b,
                                                 Function<ManagerCareerService.CareerProfile, T> f) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("a", a == null ? null : f.apply(a));
        m.put("b", b == null ? null : f.apply(b));
        return m;
    }

    private static double round2(double d) {
        return Math.round(d * 100.0) / 100.0;
    }
}
