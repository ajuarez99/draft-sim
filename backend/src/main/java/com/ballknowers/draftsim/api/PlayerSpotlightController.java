package com.ballknowers.draftsim.api;

import com.ballknowers.draftsim.engine.PlayerSpotlightService;
import com.ballknowers.draftsim.engine.SpotlightOwnership.Ownership;
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
 * The league home's player spotlight (specs/014-home-player-spotlight;
 * contracts/player-spotlight-api.md).
 */
@RestController
@RequestMapping("/api")
public class PlayerSpotlightController {

    private final PlayerSpotlightService spotlight;
    private final LeagueMembership membership;

    public PlayerSpotlightController(PlayerSpotlightService spotlight, LeagueMembership membership) {
        this.spotlight = spotlight;
        this.membership = membership;
    }

    @GetMapping("/leagues/{sleeperId}/player-spotlight")
    public ResponseEntity<Map<String, Object>> playerSpotlight(
            @PathVariable String sleeperId,
            @RequestHeader(value = "X-Sleeper-User", required = false) String sleeperUserId) {
        // Scoped like every league route (audit 2026-09-28 root cause): no identity, or one that is
        // not in this league, is the same 404 as a league that does not exist. Never fails open.
        if (membership.visibleLeague(sleeperId, sleeperUserId).isEmpty()) return ResponseEntity.notFound().build();
        return spotlight.forLeague(sleeperId, sleeperUserId)
                .map(r -> ResponseEntity.ok(body(r)))
                .orElseGet(() -> ResponseEntity.notFound().build());
    }

    /*
     * Package-private so PlayerSpotlightShapeTest can pin the shape without a database. Built by
     * hand on LinkedHashMap, never Map.of: opponent, isAway and teamName are legitimately null
     * and Map.of throws on a null value. The absence rules below are decided here, in one place.
     */
    static Map<String, Object> body(PlayerSpotlightService.Result r) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("applies", r.applies());
        out.put("reason", r.reason());
        out.put("season", r.season());
        out.put("sport", r.sport().code());
        if (!r.applies()) return out;

        // Stated by the server so the client never infers the sport's rules.
        out.put("playersPlayMultiplePerPeriod", r.playersPlayMultiplePerPeriod());
        out.put("period", period(r.period()));
        out.put("periodUnavailable", r.periodUnavailable());
        out.put("laterNightInProgress", r.laterNightInProgress() == null ? null : r.laterNightInProgress().toString());
        out.put("seasonStartDate", r.seasonStartDate() == null ? null : r.seasonStartDate().toString());

        // ABSENT (not null, not []) when the sport has one game per period: absence means "does
        // not apply here", an empty list means "applies, nothing to show, and here is why".
        if (r.topOfNight() != null) out.put("topOfNight", performances(r.topOfNight()));
        if (r.trending() != null) out.put("trending", trending(r.trending()));
        if (r.rookieWatch() != null) out.put("rookieWatch", performances(r.rookieWatch()));
        return out;
    }

    private static Map<String, Object> period(PlayerSpotlightService.Period p) {
        if (p == null) return null;
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("kind", p.kind().name());
        // Exactly one shape per kind.
        if (p.kind() == PlayerSpotlightService.Kind.NIGHT) {
            out.put("date", p.date().toString());
            out.put("gamesCount", p.gamesCount());
        } else {
            out.put("week", p.week());
            out.put("weekFinal", p.weekFinal());
        }
        return out;
    }

    private static Map<String, Object> performances(PlayerSpotlightService.Section s) {
        Map<String, Object> out = new LinkedHashMap<>();
        List<Map<String, Object>> entries = new ArrayList<>();
        for (PlayerSpotlightService.Performance p : s.entries()) {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("playerId", p.playerId());
            row.put("name", p.name());
            row.put("position", p.position());
            row.put("team", p.team());
            // Nullable on purpose: unknown is unknown, never guessed.
            row.put("opponent", p.opponent());
            row.put("isAway", p.isAway());
            row.put("points", p.points());
            row.put("ownership", ownership(p.ownership()));
            entries.add(row);
        }
        out.put("entries", entries);
        out.put("unavailable", s.unavailable());
        return out;
    }

    private static Map<String, Object> trending(PlayerSpotlightService.TrendingSection t) {
        Map<String, Object> out = new LinkedHashMap<>();
        List<Map<String, Object>> entries = new ArrayList<>();
        for (PlayerSpotlightService.TrendingEntry e : t.entries()) {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("rank", e.rank());
            row.put("playerId", e.playerId());
            row.put("name", e.name());
            row.put("position", e.position());
            row.put("team", e.team());
            row.put("addCount", e.addCount());
            row.put("ownership", ownership(e.ownership()));
            row.put("outcome", e.outcome());
            // points/opponent/isAway ride only on PLAYED: a non-game must never read as a zero
            // (invariant 1), so for any other outcome the keys do not exist at all.
            if ("PLAYED".equals(e.outcome())) {
                row.put("points", e.points());
                row.put("opponent", e.opponent());
                row.put("isAway", e.isAway());
            }
            entries.add(row);
        }
        out.put("entries", entries);
        out.put("lookbackHours", t.lookbackHours());
        out.put("fetchedAt", t.fetchedAt() == null ? null : t.fetchedAt().toString());
        out.put("stale", t.stale());
        out.put("omittedUnknownPlayers", t.omittedUnknownPlayers());
        out.put("unavailable", t.unavailable());
        return out;
    }

    private static Map<String, Object> ownership(Ownership o) {
        Map<String, Object> out = new LinkedHashMap<>();
        boolean rostered = o != null && o.rostered();
        out.put("rostered", rostered);
        // Unrostered carries only the flag, per the contract.
        if (rostered) {
            out.put("teamName", o.teamName());
            out.put("isMe", o.isMe());
            out.put("avatarId", o.avatarId());
        }
        return out;
    }
}
