package com.ballknowers.draftsim.api;

import com.ballknowers.draftsim.domain.Sport;
import com.ballknowers.draftsim.ingest.SleeperClient;
import com.ballknowers.draftsim.store.LeagueRepository;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Read-only Sleeper passthrough for the username-identity gate
 * (claude/user-identity-and-onboarding.md §4a) -- nothing here writes to the
 * database. {@link SleeperClient#user} and {@link SleeperClient#leagues} both
 * already existed with no caller before this.
 */
@RestController
@RequestMapping("/api/sleeper")
public class SleeperUserController {

    private final SleeperClient sleeper;
    private final LeagueRepository leagues;

    public SleeperUserController(SleeperClient sleeper, LeagueRepository leagues) {
        this.sleeper = sleeper;
        this.leagues = leagues;
    }

    /** Sleeper answers 200 with a JSON null body for an unknown username -- not a 404. */
    @GetMapping("/user/{usernameOrId}")
    public ResponseEntity<Map<String, Object>> user(@PathVariable String usernameOrId) {
        Map<String, Object> u = sleeper.user(usernameOrId);
        if (u == null) return ResponseEntity.notFound().build();

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("sleeperUserId", u.get("user_id"));
        out.put("username", u.get("username"));
        out.put("displayName", u.get("display_name"));
        out.put("avatar", u.get("avatar"));
        return ResponseEntity.ok(out);
    }

    /**
     * Both sports, one mixed sport-tagged list -- matches the picker, which is
     * already one mixed list with a sport pill and no switcher. Season comes
     * from {@link SleeperClient#state}'s {@code league_season}, not the
     * calendar: the two sports' current seasons disagree during the offseason,
     * which is exactly when someone opens this app to set up next year's league.
     */
    @GetMapping("/users/{userId}/leagues")
    public List<Map<String, Object>> leagues(@PathVariable String userId) {
        List<Map<String, Object>> out = new ArrayList<>();
        for (Sport sport : Sport.values()) {
            int season = currentSeason(sport);
            for (Map<String, Object> l : sleeper.leagues(userId, sport.code(), season)) {
                String sleeperLeagueId = String.valueOf(l.get("league_id"));

                Map<String, Object> row = new LinkedHashMap<>();
                row.put("sleeperLeagueId", sleeperLeagueId);
                row.put("name", l.get("name"));
                row.put("sport", sport);
                row.put("season", season);
                row.put("totalRosters", l.get("total_rosters"));
                row.put("draftId", l.get("draft_id"));
                row.put("status", l.get("status"));
                row.put("previousLeagueId", l.get("previous_league_id"));
                row.put("ingested", leagues.bySleeperId(sleeperLeagueId).isPresent());
                out.add(row);
            }
        }
        return out;
    }

    private int currentSeason(Sport sport) {
        Object leagueSeason = sleeper.state(sport.code()).get("league_season");
        return Integer.parseInt(String.valueOf(leagueSeason));
    }
}
