package com.ballknowers.draftsim.api;

import com.ballknowers.draftsim.domain.Sport;
import com.ballknowers.draftsim.engine.AdminDigestService;
import com.ballknowers.draftsim.engine.SportLeagueSeasonSource;
import com.ballknowers.draftsim.ingest.PlayerGameIngestService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.Clock;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * The morning tweet digest (specs/043-tweet-digest-admin; contracts/admin-digest-api.md).
 *
 * <p>Admin gating is {@code WebConfig}'s {@code /api/admin/**} interceptor, not a check here: a
 * second one would be a second definition of "admin" that could drift from the first.
 */
@RestController
@RequestMapping("/api/admin/digest")
public class AdminDigestController {

    private static final Logger log = LoggerFactory.getLogger(AdminDigestController.class);

    private final AdminDigestService digest;
    private final PlayerGameIngestService playerGameIngest;
    private final SportLeagueSeasonSource currentSeason;
    private final Clock clock;

    public AdminDigestController(AdminDigestService digest, PlayerGameIngestService playerGameIngest,
                                 SportLeagueSeasonSource currentSeason, Clock clock) {
        this.digest = digest;
        this.playerGameIngest = playerGameIngest;
        this.currentSeason = currentSeason;
        this.clock = clock;
    }

    /** Read-only. {@code date} (YYYY-MM-DD) asks every sport to cover that date instead of last night. */
    @GetMapping
    public ResponseEntity<?> get(@RequestParam(value = "date", required = false) String date) {
        LocalDate parsed = null;
        if (date != null && !date.isBlank()) {
            try {
                parsed = LocalDate.parse(date.trim());
            } catch (DateTimeParseException e) {
                // Mutable map, not Map.of: house rule for response bodies.
                Map<String, Object> body = new LinkedHashMap<>();
                body.put("code", "bad_date");
                body.put("message", "date must be YYYY-MM-DD");
                return ResponseEntity.badRequest().body(body);
            }
        }
        return ResponseEntity.ok(digest.digest(parsed));
    }

    /**
     * The one write on this page's behalf (plan-review F2): nothing on a schedule fetches the
     * night's stat lines, so the page can ask for it. Single-flighted and skips final weeks inside
     * {@code refreshSportSeason}. One sport failing does not fail the other.
     */
    @PostMapping("/refresh-stats")
    public Map<String, Object> refreshStats() {
        List<Map<String, Object>> results = new ArrayList<>();
        for (Sport sport : List.of(Sport.NBA, Sport.NFL)) {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("sport", sport.code());
            Optional<Integer> season = Optional.empty();
            try {
                season = currentSeason.leagueSeason(sport);
            } catch (RuntimeException e) {
                log.warn("admin digest refresh: could not read the {} season", sport, e);
            }
            row.put("season", season.orElse(null));
            row.put("weeksFetched", 0);
            row.put("gamesStored", 0);
            row.put("weeksFailed", 0);
            row.put("scheduleStoreFailed", false);
            row.put("error", null);
            if (season.isEmpty()) {
                row.put("error", "no current season known");
            } else {
                try {
                    PlayerGameIngestService.Result r =
                            playerGameIngest.refreshSportSeason(sport, season.get(), clock.instant());
                    row.put("weeksFetched", r.weeksFetched());
                    row.put("gamesStored", r.gamesStored());
                    row.put("weeksFailed", r.weeksFailed());
                    row.put("scheduleStoreFailed", r.scheduleStoreFailed());
                } catch (RuntimeException e) {
                    log.warn("admin digest refresh: {} {} failed", sport, season.get(), e);
                    row.put("error", String.valueOf(e.getMessage()));
                }
            }
            results.add(row);
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("results", results);
        return out;
    }
}
