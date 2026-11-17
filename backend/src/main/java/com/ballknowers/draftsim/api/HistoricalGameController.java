package com.ballknowers.draftsim.api;

import com.ballknowers.draftsim.historical.ClaimResult;
import com.ballknowers.draftsim.historical.HistoricalDailyService;
import com.ballknowers.draftsim.historical.HistoricalGameService;
import com.ballknowers.draftsim.historical.HistoricalGameState;
import com.ballknowers.draftsim.historical.HistoricalSport;
import com.ballknowers.draftsim.historical.Identity;
import com.ballknowers.draftsim.historical.HistoricalSports;
import com.ballknowers.draftsim.historical.HistoricalDataRepository.SeasonRow;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The historical draft game's user API (contracts/api.md). Spec 053 T014 added the first route,
 * {@code GET /api/history/seasons}; the game routes (T018) belong in this same class.
 *
 * <p>Every route takes {@code X-Sleeper-User}; a missing one is a 401 (plan F18), the same as {@code /api/mocks}.
 */
@RestController
@RequestMapping("/api/history")
public class HistoricalGameController {

    private static final TypeReference<Map<String, String>> STRING_MAP = new TypeReference<>() {};
    private static final TypeReference<Map<String, Double>> DOUBLE_MAP = new TypeReference<>() {};

    private final HistoricalSports sports;
    private final HistoricalGameService service;
    private final HistoricalDailyService daily;
    private final ObjectMapper json;

    public HistoricalGameController(HistoricalSports sports, HistoricalGameService service,
                                    HistoricalDailyService daily, ObjectMapper json) {
        this.sports = sports;
        this.service = service;
        this.daily = daily;
        this.json = json;
    }

    /** One offered season, for the "about this game" panel. {@code dataNotes} is never null (spec 056 FR-014). */
    public record SeasonSummary(String sport, int seasonStart, String label, String source, List<String> countedStats,
                                Map<String, String> excludedStats, Map<String, Double> statCoverage,
                                String rankingSource, String settingsSource, List<String> dataNotes) {}

    /**
     * The offered seasons of one sport whose rows and prior-season card lines both committed (plan F14), ascending.
     * {@code sport} is REQUIRED, with no default: missing or unknown is a 400 (spec 056 contracts).
     */
    @GetMapping("/seasons")
    public List<SeasonSummary> seasons(
            @RequestParam(value = "sport", required = false) String sportCode,
            @RequestHeader(value = "X-Sleeper-User", required = false) String sleeperUserId,
            @RequestHeader(value = "X-Guest-Id", required = false) String guestId) {
        Identity.resolve(sleeperUserId, guestId);
        HistoricalSport sport = sports.get(sportCode);
        return sport.offeredSeasons().stream().map(s -> summary(s, sport)).toList();
    }

    // ---- games (T018) -----------------------------------------------------------------------------------

    /**
     * @param sport    "NBA" or "NFL", required: no default, because a defaulted sport asserts a rule
     * @param userSlot optional, 1..teams for that sport; random when absent
     * @param seasonStart optional (spec 074): the season the player picked, by its start year. Absent means a random
     *                 season, which a signed-out player may not ask for. A chosen season never counts toward
     *                 achievements or records.
     */
    public record CreateRequest(String sport, Integer userSlot, Integer seasonStart) {}

    public record PickRequest(Long playerId) {}

    public record AutoRequest(HistoricalGameService.Scope scope) {}

    /**
     * Starts a game on a random season, or on the one named by {@code seasonStart}. 201 with the state, the bots
     * already advanced to the user's first turn. A signed-out player gets 403 {@code GUEST_LIMIT} for a random season
     * and for a second chosen one.
     */
    @PostMapping("/games")
    public ResponseEntity<HistoricalGameState> create(
            @RequestBody(required = false) CreateRequest body,
            @RequestHeader(value = "X-Sleeper-User", required = false) String sleeperUserId,
            @RequestHeader(value = "X-Guest-Id", required = false) String guestId) {
        String owner = Identity.resolve(sleeperUserId, guestId);
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(service.createFor(owner, body == null ? null : body.sport(),
                        body == null ? null : body.userSlot(), body == null ? null : body.seasonStart()));
    }

    /** The caller's games, newest first. */
    @GetMapping("/games")
    public List<HistoricalGameState.GameSummary> list(
            @RequestHeader(value = "X-Sleeper-User", required = false) String sleeperUserId,
            @RequestHeader(value = "X-Guest-Id", required = false) String guestId) {
        String owner = Identity.resolve(sleeperUserId, guestId);
        return service.list(owner);
    }

    /** Another user's game is a 404, the same as one that does not exist. */
    @GetMapping("/games/{id}")
    public ResponseEntity<HistoricalGameState> get(
            @PathVariable long id,
            @RequestHeader(value = "X-Sleeper-User", required = false) String sleeperUserId,
            @RequestHeader(value = "X-Guest-Id", required = false) String guestId) {
        String owner = Identity.resolve(sleeperUserId, guestId);
        return service.get(id, owner).map(ResponseEntity::ok).orElseGet(() -> ResponseEntity.notFound().build());
    }

    /** 409 when it is not the user's turn, the game is not DRAFTING, or the player is taken. */
    @PostMapping("/games/{id}/pick")
    public ResponseEntity<HistoricalGameState> pick(
            @PathVariable long id, @RequestBody(required = false) PickRequest body,
            @RequestHeader(value = "X-Sleeper-User", required = false) String sleeperUserId,
            @RequestHeader(value = "X-Guest-Id", required = false) String guestId) {
        String owner = Identity.resolve(sleeperUserId, guestId);
        if (body == null || body.playerId() == null) throw new IllegalArgumentException("playerId is required");
        return service.pick(id, body.playerId(), owner).map(ResponseEntity::ok)
                .orElseGet(() -> ResponseEntity.notFound().build());
    }

    @PostMapping("/games/{id}/auto")
    public ResponseEntity<HistoricalGameState> auto(
            @PathVariable long id, @RequestBody(required = false) AutoRequest body,
            @RequestHeader(value = "X-Sleeper-User", required = false) String sleeperUserId,
            @RequestHeader(value = "X-Guest-Id", required = false) String guestId) {
        String owner = Identity.resolve(sleeperUserId, guestId);
        if (body == null || body.scope() == null) throw new IllegalArgumentException("scope is required: PICK or FINISH");
        return service.auto(id, body.scope(), owner).map(ResponseEntity::ok)
                .orElseGet(() -> ResponseEntity.notFound().build());
    }

    @PostMapping("/games/{id}/abandon")
    public ResponseEntity<Void> abandon(
            @PathVariable long id,
            @RequestHeader(value = "X-Sleeper-User", required = false) String sleeperUserId,
            @RequestHeader(value = "X-Guest-Id", required = false) String guestId) {
        String owner = Identity.resolve(sleeperUserId, guestId);
        return service.abandon(id, owner) ? ResponseEntity.noContent().build()
                : ResponseEntity.notFound().build();
    }

    // ---- sign-in (spec 074) -----------------------------------------------------------------------------

    /**
     * Moves the signed-out games of this browser's guest id to the Sleeper id that just signed in. BOTH headers are
     * required and well formed (400 otherwise): a Sleeper id that starts with {@code guest:} is as wrong as a missing
     * one. Idempotent: a second call moves nothing. {@code leftBehind} is the number of daily games that could not
     * move because the Sleeper id had already played that day's.
     */
    @PostMapping("/claim")
    public ClaimResult claim(
            @RequestHeader(value = "X-Sleeper-User", required = false) String sleeperUserId,
            @RequestHeader(value = "X-Guest-Id", required = false) String guestId) {
        if (sleeperUserId == null || sleeperUserId.isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "claim needs X-Sleeper-User");
        }
        String sleeper = Identity.sleeperId(sleeperUserId);
        String guest = Identity.guestKey(guestId);
        if (guest == null) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "claim needs a valid X-Guest-Id");
        return service.claim(guest, sleeper);
    }

    // ---- the game of the day (spec 071) -----------------------------------------------------------------

    /** @param sport "NBA" or "NFL", required: no default */
    public record DailyRequest(String sport) {}

    /**
     * Today's game for each sport that has seasons to offer: the season, the caller's game if started, their
     * score if finished, their streak, and when the next day starts. Deals the day's puzzle if nobody has asked
     * yet, which is one idempotent insert.
     */
    @GetMapping("/daily")
    public List<HistoricalDailyService.DailyView> daily(
            @RequestHeader(value = "X-Sleeper-User", required = false) String sleeperUserId,
            @RequestHeader(value = "X-Guest-Id", required = false) String guestId) {
        String owner = Identity.resolve(sleeperUserId, guestId);
        return daily.status(owner);
    }

    /** Starts the caller's game of the day (201), or returns the one they already started (200). */
    @PostMapping("/daily")
    public ResponseEntity<HistoricalGameState> startDaily(
            @RequestBody(required = false) DailyRequest body,
            @RequestHeader(value = "X-Sleeper-User", required = false) String sleeperUserId,
            @RequestHeader(value = "X-Guest-Id", required = false) String guestId) {
        String owner = Identity.resolve(sleeperUserId, guestId);
        HistoricalDailyService.Started s = daily.start(owner, body == null ? null : body.sport());
        return ResponseEntity.status(s.created() ? HttpStatus.CREATED : HttpStatus.OK).body(s.state());
    }

    private SeasonSummary summary(SeasonRow s, HistoricalSport sport) {
        return new SeasonSummary(s.sport(), s.seasonStart(), s.label(), s.source(), s.countedStats(),
                read(s.excludedStatsJson(), STRING_MAP), read(s.statCoverageJson(), DOUBLE_MAP),
                s.rankingSource(), sport.settingsSource(), sport.dataNotes(s));
    }

    private <T> Map<String, T> read(String text, TypeReference<Map<String, T>> type) {
        if (text == null || text.isBlank()) return new LinkedHashMap<>();
        try {
            return json.readValue(text, type);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("stored season metadata is not valid JSON: " + e.getOriginalMessage());
        }
    }

}
