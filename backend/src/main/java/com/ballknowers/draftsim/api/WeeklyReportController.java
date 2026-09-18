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

    private static Map<String, Object> body(WeeklyReportService.Result r) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("available", r.available());
        out.put("season", r.season());
        out.put("week", r.week());
        out.put("sport", r.sport().code());
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
