package com.ballknowers.draftsim.api;

import com.ballknowers.draftsim.engine.ExpectedWinsService;
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
 * Expected wins and schedule luck (specs/004-ffwrapped-feature-parity, US3;
 * contracts/league-analytics-api.md).
 */
@RestController
@RequestMapping("/api")
public class ExpectedWinsController {

    private final ExpectedWinsService expectedWins;

    public ExpectedWinsController(ExpectedWinsService expectedWins) {
        this.expectedWins = expectedWins;
    }

    @GetMapping("/leagues/{sleeperId}/expected-wins")
    public ResponseEntity<Map<String, Object>> expectedWins(@PathVariable String sleeperId) {
        return expectedWins.forLeague(sleeperId)
                .map(r -> ResponseEntity.ok(body(r)))
                .orElseGet(() -> ResponseEntity.notFound().build());
    }

    private static Map<String, Object> body(ExpectedWinsService.Result r) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("available", r.available());
        out.put("season", r.season());
        out.put("sport", r.sport().code());
        out.put("weeksScored", r.weeksScored());
        out.put("leagueAveragePpg", r.leagueAveragePpg());
        if (!r.available()) {
            out.put("reason", r.reason());
            out.put("teams", List.of());
            return out;
        }
        List<Map<String, Object>> teams = new ArrayList<>();
        for (ExpectedWinsService.TeamRow t : r.teams()) {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("rosterId", t.rosterId());
            row.put("managerId", t.managerId());
            row.put("teamName", t.teamName());
            row.put("avatarId", t.avatarId());
            row.put("expectedWins", t.expectedWins());
            row.put("actualWins", t.actualWins());
            row.put("winsAboveExpected", t.winsAboveExpected());
            row.put("strengthOfSchedule", t.strengthOfSchedule());
            // A discriminator, not a pair of optional blocks: swingWeeks is
            // non-empty only for SWING_WEEKS, so the page renders one
            // explanation or the other and never both (US3.4).
            row.put("luckSource", t.luckSource().name());
            List<Map<String, Object>> swings = new ArrayList<>();
            for (ExpectedWinsService.SwingWeek s : t.swingWeeks()) {
                Map<String, Object> sw = new LinkedHashMap<>();
                sw.put("week", s.week());
                sw.put("result", s.won() ? "WON" : "LOST");
                sw.put("points", s.points());
                sw.put("weeklyRank", s.weeklyRank());
                sw.put("opponent", s.opponent());
                swings.add(sw);
            }
            row.put("swingWeeks", swings);
            teams.add(row);
        }
        out.put("teams", teams);
        return out;
    }
}
