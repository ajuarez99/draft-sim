package com.ballknowers.draftsim.api;

import com.ballknowers.draftsim.historical.HistoricalAchievementService;
import com.ballknowers.draftsim.historical.HistoricalAchievementService.AwardView;
import com.ballknowers.draftsim.historical.HistoricalAchievementService.ProfileView;
import com.ballknowers.draftsim.historical.HistoricalAchievementService.SeenRequest;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Time Machine achievements and levels (spec 064). Every route needs {@code X-Sleeper-User}: missing or blank is a
 * 401, the same as the rest of {@code /api/history}. Another user's game is a 404, decided before any 409.
 */
@RestController
@RequestMapping("/api/history")
public class HistoricalAchievementController {

    private final HistoricalAchievementService service;

    public HistoricalAchievementController(HistoricalAchievementService service) {
        this.service = service;
    }

    /** The caller's level, XP, totals and the whole badge catalog. Settles any finished games first. */
    @GetMapping("/profile")
    public ProfileView profile(@RequestHeader(value = "X-Sleeper-User", required = false) String sleeperUserId) {
        HistoricalGameController.requireIdentity(sleeperUserId);
        return service.profile(sleeperUserId);
    }

    /** One finished game's award. 404 for an unknown or someone else's game, 409 when it is not COMPLETE. */
    @GetMapping("/games/{id}/award")
    public ResponseEntity<AwardView> award(
            @PathVariable long id,
            @RequestHeader(value = "X-Sleeper-User", required = false) String sleeperUserId) {
        HistoricalGameController.requireIdentity(sleeperUserId);
        return service.award(id, sleeperUserId).map(ResponseEntity::ok)
                .orElseGet(() -> ResponseEntity.notFound().build());
    }

    @PostMapping("/profile/seen")
    public ResponseEntity<Void> seen(
            @RequestBody(required = false) SeenRequest body,
            @RequestHeader(value = "X-Sleeper-User", required = false) String sleeperUserId) {
        HistoricalGameController.requireIdentity(sleeperUserId);
        service.markSeen(sleeperUserId, body);
        return ResponseEntity.noContent().build();
    }
}
