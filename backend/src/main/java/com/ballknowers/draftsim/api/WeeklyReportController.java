package com.ballknowers.draftsim.api;

import com.ballknowers.draftsim.engine.WeeklyReportService;
import com.ballknowers.draftsim.store.LeagueMembership;
import com.ballknowers.draftsim.api.dto.WeeklyReportResponses.WeeklyReportBody;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;


/**
 * One week's digest (specs/004-ffwrapped-feature-parity, US5;
 * contracts/league-analytics-api.md).
 */
@RestController
@RequestMapping("/api")
public class WeeklyReportController {

    private final WeeklyReportService weeklyReport;
    private final LeagueMembership membership;

    public WeeklyReportController(WeeklyReportService weeklyReport, LeagueMembership membership) {
        this.weeklyReport = weeklyReport;
        this.membership = membership;
    }

    @GetMapping("/leagues/{sleeperId}/weekly-report/{week}")
    public ResponseEntity<WeeklyReportBody> weeklyReport(@PathVariable String sleeperId,
                                                            @PathVariable int week,
                                                            @RequestHeader(value = "X-Sleeper-User", required = false) String sleeperUserId) {
        // Scoped like every other league route: no identity, or an identity that is not in
        // this league, is the same 404 as a league that does not exist. This route had no
        // scoping at all before claude/audit-2026-09-28/01, so ANY caller could read it.
        if (membership.visibleLeague(sleeperId, sleeperUserId).isEmpty()) return ResponseEntity.notFound().build();
        return weeklyReport.forWeek(sleeperId, week, sleeperUserId)
                .map(r -> ResponseEntity.ok(body(r)))
                .orElseGet(() -> ResponseEntity.notFound().build());
    }

    /* package-private so WeeklyReportShapeTest can pin the response shape without a database. */
    static WeeklyReportBody body(WeeklyReportService.Result r) {
        return WeeklyReportBody.of(r);
    }
}
