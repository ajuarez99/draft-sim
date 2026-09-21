package com.ballknowers.draftsim.api;

import com.ballknowers.draftsim.engine.WeeklyReportService;
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
 * One week's digest (specs/004-ffwrapped-feature-parity, US5;
 * contracts/league-analytics-api.md).
 */
@RestController
@RequestMapping("/api")
public class WeeklyReportController {

    private final WeeklyReportService weeklyReport;

    public WeeklyReportController(WeeklyReportService weeklyReport) {
        this.weeklyReport = weeklyReport;
    }

    @GetMapping("/leagues/{sleeperId}/weekly-report/{week}")
    public ResponseEntity<Map<String, Object>> weeklyReport(@PathVariable String sleeperId,
                                                            @PathVariable int week) {
        return weeklyReport.forWeek(sleeperId, week)
                .map(r -> ResponseEntity.ok(body(r)))
                .orElseGet(() -> ResponseEntity.notFound().build());
    }

    /* package-private so WeeklyReportShapeTest can pin the response shape without a database. */
    static Map<String, Object> body(WeeklyReportService.Result r) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("available", r.available());
        out.put("season", r.season());
        out.put("requestedSeason", r.requestedSeason());
        out.put("week", r.week());
        out.put("sport", r.sport().code());
        // Stated rather than inferred: the client renders from a fact the server
        // asserts, not by guessing the sport's rules from which arrays it finds.
        out.put("playersPlayMultiplePerPeriod", r.playersPlayMultiplePerPeriod());
        if (!r.available()) {
            out.put("reason", r.reason());
            out.put("matchups", List.of());
            out.put("topPerformers", List.of());
            out.put("awards", List.of());
            out.put("awardsOmitted", List.of());
            return out;
        }

        List<Map<String, Object>> games = new ArrayList<>();
        for (WeeklyReportService.Matchup m : r.matchups()) {
            Map<String, Object> g = new LinkedHashMap<>();
            g.put("home", side(m.home()));
            g.put("away", side(m.away()));
            games.add(g);
        }
        out.put("matchups", games);

        // EITHER the single list OR the pair, never both, and the inapplicable
        // side is left OUT rather than sent empty: an empty array says "we
        // looked and found none", absence says "this does not apply here".
        //
        // Built by hand here rather than serialized off the record, so the
        // absence is this method's decision and visible in one place.
        if (r.topPerformers() != null) {
            List<Map<String, Object>> performers = new ArrayList<>();
            for (WeeklyReportService.Performer p : r.topPerformers()) {
                Map<String, Object> row = new LinkedHashMap<>();
                row.put("playerId", p.playerId());
                row.put("playerName", p.playerName());
                row.put("position", p.position());
                row.put("teamName", p.teamName());
                row.put("points", p.points());
                performers.add(row);
            }
            out.put("topPerformers", performers);
        }

        if (r.bestNights() != null) {
            List<Map<String, Object>> nights = new ArrayList<>();
            for (WeeklyReportService.NightPerformance n : r.bestNights()) {
                Map<String, Object> row = new LinkedHashMap<>();
                row.put("playerId", n.playerId());
                row.put("playerName", n.playerName());
                row.put("position", n.position());
                row.put("teamName", n.teamName());
                row.put("points", n.points());
                row.put("date", n.date().toString());
                row.put("opponent", n.opponent());
                row.put("isAway", n.isAway());
                nights.add(row);
            }
            out.put("bestNights", nights);
        }

        if (r.bestWeek() != null) {
            List<Map<String, Object>> weeks = new ArrayList<>();
            for (WeeklyReportService.PlayerWeek w : r.bestWeek()) {
                Map<String, Object> row = new LinkedHashMap<>();
                row.put("playerId", w.playerId());
                row.put("playerName", w.playerName());
                row.put("position", w.position());
                row.put("teamName", w.teamName());
                row.put("totalPoints", w.totalPoints());
                // Always beside the total: 182.0 means nothing without knowing
                // it covers four games.
                row.put("gamesPlayed", w.gamesPlayed());
                weeks.add(row);
            }
            out.put("bestWeek", weeks);
        }

        // Required on the pair shape, never defaulted: the page's FR-005
        // disclosure is driven by this rather than by hardcoded prose.
        if (r.basis() != null) out.put("basis", r.basis());

        if (r.sectionsUnavailable() != null) {
            List<Map<String, Object>> gaps = new ArrayList<>();
            for (WeeklyReportService.SectionUnavailable u : r.sectionsUnavailable()) {
                Map<String, Object> row = new LinkedHashMap<>();
                row.put("section", u.section());
                row.put("reason", u.reason());
                gaps.add(row);
            }
            out.put("sectionsUnavailable", gaps);
        }

        List<Map<String, Object>> awards = new ArrayList<>();
        for (WeeklyReportService.Award a : r.awards()) {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("kind", a.kind());
            row.put("teamName", a.teamName());
            row.put("detail", a.detail());
            awards.add(row);
        }
        out.put("awards", awards);

        // The honesty mechanism: an award that could not be computed says so
        // rather than simply not appearing, which is indistinguishable from
        // "nobody qualified this week" (US5.4).
        List<Map<String, Object>> omitted = new ArrayList<>();
        for (WeeklyReportService.OmittedAward o : r.awardsOmitted()) {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("kind", o.kind());
            row.put("reason", o.reason());
            omitted.add(row);
        }
        out.put("awardsOmitted", omitted);
        return out;
    }

    private static Map<String, Object> side(WeeklyReportService.Side s) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("rosterId", s.rosterId());
        out.put("teamName", s.teamName());
        out.put("avatarId", s.avatarId());
        out.put("record", s.record());
        out.put("points", s.points());
        return out;
    }
}
