package com.ballknowers.draftsim.api;

import com.ballknowers.draftsim.historical.HistoricalDataRepository;
import com.ballknowers.draftsim.historical.HistoricalGameService;
import com.ballknowers.draftsim.historical.HistoricalGameState;
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

    /** What the screen prints as where the settings come from (FR-002, plan F13). */
    public static final String SETTINGS_SOURCE = HistoricalGameState.SETTINGS_SOURCE;

    private static final TypeReference<Map<String, String>> STRING_MAP = new TypeReference<>() {};
    private static final TypeReference<Map<String, Double>> DOUBLE_MAP = new TypeReference<>() {};

    private final HistoricalDataRepository data;
    private final HistoricalGameService service;
    private final ObjectMapper json;

    public HistoricalGameController(HistoricalDataRepository data, HistoricalGameService service,
                                    ObjectMapper json) {
        this.data = data;
        this.service = service;
        this.json = json;
    }

    /** One offered season, for the "about this game" panel. */
    public record SeasonSummary(int seasonStart, String label, String source, List<String> countedStats,
                                Map<String, String> excludedStats, Map<String, Double> statCoverage,
                                String rankingSource, String settingsSource) {}

    /** Seasons 1970..2025 whose rows and prior-season card lines both committed (plan F14), ascending. */
    @GetMapping("/seasons")
    public List<SeasonSummary> seasons(
            @RequestHeader(value = "X-Sleeper-User", required = false) String sleeperUserId) {
        requireIdentity(sleeperUserId);
        return data.offeredSeasons().stream().map(this::summary).toList();
    }

    // ---- games (T018) -----------------------------------------------------------------------------------

    /** @param userSlot optional, 1..teams; random when absent */
    public record CreateRequest(Integer userSlot) {}

    public record PickRequest(Long playerId) {}

    public record AutoRequest(HistoricalGameService.Scope scope) {}

    /** Starts a game on a random season. 201 with the state, the bots already advanced to the user's first turn. */
    @PostMapping("/games")
    public ResponseEntity<HistoricalGameState> create(
            @RequestBody(required = false) CreateRequest body,
            @RequestHeader(value = "X-Sleeper-User", required = false) String sleeperUserId) {
        requireIdentity(sleeperUserId);
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(service.create(sleeperUserId, body == null ? null : body.userSlot(), null));
    }

    /** The caller's games, newest first. */
    @GetMapping("/games")
    public List<HistoricalGameState.GameSummary> list(
            @RequestHeader(value = "X-Sleeper-User", required = false) String sleeperUserId) {
        requireIdentity(sleeperUserId);
        return service.list(sleeperUserId);
    }

    /** Another user's game is a 404, the same as one that does not exist. */
    @GetMapping("/games/{id}")
    public ResponseEntity<HistoricalGameState> get(
            @PathVariable long id,
            @RequestHeader(value = "X-Sleeper-User", required = false) String sleeperUserId) {
        requireIdentity(sleeperUserId);
        return service.get(id, sleeperUserId).map(ResponseEntity::ok).orElseGet(() -> ResponseEntity.notFound().build());
    }

    /** 409 when it is not the user's turn, the game is not DRAFTING, or the player is taken. */
    @PostMapping("/games/{id}/pick")
    public ResponseEntity<HistoricalGameState> pick(
            @PathVariable long id, @RequestBody(required = false) PickRequest body,
            @RequestHeader(value = "X-Sleeper-User", required = false) String sleeperUserId) {
        requireIdentity(sleeperUserId);
        if (body == null || body.playerId() == null) throw new IllegalArgumentException("playerId is required");
        return service.pick(id, body.playerId(), sleeperUserId).map(ResponseEntity::ok)
                .orElseGet(() -> ResponseEntity.notFound().build());
    }

    @PostMapping("/games/{id}/auto")
    public ResponseEntity<HistoricalGameState> auto(
            @PathVariable long id, @RequestBody(required = false) AutoRequest body,
            @RequestHeader(value = "X-Sleeper-User", required = false) String sleeperUserId) {
        requireIdentity(sleeperUserId);
        if (body == null || body.scope() == null) throw new IllegalArgumentException("scope is required: PICK or FINISH");
        return service.auto(id, body.scope(), sleeperUserId).map(ResponseEntity::ok)
                .orElseGet(() -> ResponseEntity.notFound().build());
    }

    @PostMapping("/games/{id}/abandon")
    public ResponseEntity<Void> abandon(
            @PathVariable long id,
            @RequestHeader(value = "X-Sleeper-User", required = false) String sleeperUserId) {
        requireIdentity(sleeperUserId);
        return service.abandon(id, sleeperUserId) ? ResponseEntity.noContent().build()
                : ResponseEntity.notFound().build();
    }

    private SeasonSummary summary(SeasonRow s) {
        return new SeasonSummary(s.seasonStart(), s.label(), s.source(), s.countedStats(),
                read(s.excludedStatsJson(), STRING_MAP), read(s.statCoverageJson(), DOUBLE_MAP),
                s.rankingSource(), SETTINGS_SOURCE);
    }

    private <T> Map<String, T> read(String text, TypeReference<Map<String, T>> type) {
        if (text == null || text.isBlank()) return new LinkedHashMap<>();
        try {
            return json.readValue(text, type);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("stored season metadata is not valid JSON: " + e.getOriginalMessage());
        }
    }

    static void requireIdentity(String sleeperUserId) {
        if (sleeperUserId == null || sleeperUserId.isBlank()) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED,
                    "X-Sleeper-User is required for the historical draft game");
        }
    }
}
