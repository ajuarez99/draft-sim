package com.ballknowers.draftsim.api;

import com.ballknowers.draftsim.domain.Player;
import com.ballknowers.draftsim.domain.Sport;
import com.ballknowers.draftsim.engine.SeasonSuperlativesService;
import com.ballknowers.draftsim.store.LeagueConductRepository;
import com.ballknowers.draftsim.store.LeagueMemberRepository;
import com.ballknowers.draftsim.store.LeagueMembership;
import com.ballknowers.draftsim.store.LeagueRepository;
import com.ballknowers.draftsim.store.ManagerRepository;
import com.ballknowers.draftsim.store.PlayerRepository;
import com.ballknowers.draftsim.api.dto.SuperlativeResponses.ConductEntryRow;
import com.ballknowers.draftsim.api.dto.SuperlativeResponses.ConductListResponse;
import com.ballknowers.draftsim.api.dto.SuperlativeResponses.SuperlativesResponse;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;

/**
 * Season superlatives (specs/008-season-superlatives, contracts/superlatives-api.md).
 *
 * <p>No {@code ?season=} parameter: {@link SeasonSuperlativesService#forLeague}
 * resolves the season through {@link com.ballknowers.draftsim.engine.LeagueSeasonResolver},
 * the same way {@code ExpectedWinsController} does. Success bodies are the records in
 * {@link com.ballknowers.draftsim.api.dto.SuperlativeResponses} (specs/021-codebase-cleanup);
 * the error bodies stay maps.
 */
@RestController
@RequestMapping("/api")
public class SuperlativesController {

    private final SeasonSuperlativesService superlatives;
    private final LeagueMembership membership;
    private final LeagueConductRepository conduct;
    private final LeagueMemberRepository leagueMembers;
    private final PlayerRepository players;
    private final ManagerRepository managers;

    public SuperlativesController(SeasonSuperlativesService superlatives, LeagueMembership membership,
                                  LeagueConductRepository conduct, LeagueMemberRepository leagueMembers,
                                  PlayerRepository players, ManagerRepository managers) {
        this.superlatives = superlatives;
        this.membership = membership;
        this.conduct = conduct;
        this.leagueMembers = leagueMembers;
        this.players = players;
        this.managers = managers;
    }

    @GetMapping("/leagues/{sleeperId}/superlatives")
    public ResponseEntity<?> superlatives(@PathVariable String sleeperId,
                                          @RequestHeader(value = "X-Sleeper-User", required = false) String sleeperUserId) {
        if (membership.visibleLeague(sleeperId, sleeperUserId).isEmpty()) return ResponseEntity.notFound().build();
        return superlatives.forLeague(sleeperId)
                .map(r -> ResponseEntity.ok(SuperlativesResponse.of(r)))
                .orElseGet(() -> ResponseEntity.notFound().build());
    }

    // ------------------------------------------------- the conduct list (US6, T055)

    /**
     * Per league-SEASON, not the league chain (spec amendment 9): {@code
     * membership.visibleLeague} addresses the league row for this exact
     * Sleeper id (contract header) -- it does NOT go through {@code
     * LeagueSeasonResolver} the way {@link #superlatives} does, so a
     * commissioner editing a new season's list before week 1 edits that
     * season, not last season's.
     */
    @GetMapping("/leagues/{sleeperId}/conduct-list")
    public ResponseEntity<?> conductList(@PathVariable String sleeperId,
                                         @RequestHeader(value = "X-Sleeper-User", required = false) String sleeperUserId) {
        Optional<LeagueRepository.LeagueRow> found = membership.visibleLeague(sleeperId, sleeperUserId);
        if (found.isEmpty()) return ResponseEntity.notFound().build();
        return ResponseEntity.ok(conductListBody(found.get(), sleeperUserId));
    }

    public record ConductEntryRequest(String playerId, String reason, Integer appliesFromWeek) {}

    @PostMapping("/leagues/{sleeperId}/conduct-list")
    public ResponseEntity<?> saveConductEntry(@PathVariable String sleeperId,
                                              @RequestBody(required = false) ConductEntryRequest body,
                                              @RequestHeader(value = "X-Sleeper-User", required = false) String sleeperUserId) {
        Optional<LeagueRepository.LeagueRow> found = membership.visibleLeague(sleeperId, sleeperUserId);
        if (found.isEmpty()) return ResponseEntity.notFound().build();
        LeagueRepository.LeagueRow row = found.get();

        // Commissioner-only writes, on an honour system since 2026-10-05 (claude/audit-2026-09-28/04, amended).
        // The identity is a header anyone can copy from Sleeper's public league-users
        // list, so this stops honest mistakes, not someone determined to get in.
        if (!membership.canCommission(row.id(), sleeperUserId)) return forbidden(row);

        if (body == null || body.playerId() == null || body.playerId().isBlank()) {
            return ResponseEntity.badRequest().body(Map.of("message", "playerId is required"));
        }
        String reason = body.reason() == null ? "" : body.reason().trim();
        if (reason.isEmpty() || reason.length() > 140) {
            return ResponseEntity.badRequest().body(Map.of("message", "reason must be 1-140 chars, trimmed"));
        }
        if (body.appliesFromWeek() == null || body.appliesFromWeek() < 1) {
            return ResponseEntity.badRequest().body(Map.of("message", "appliesFromWeek must be >= 1"));
        }
        if (players.bySleeperId(row.sport(), body.playerId()).isEmpty()) {
            return ResponseEntity.badRequest().body(Map.of("message", "unknown player for this league's sport"));
        }

        Long addedBy = managers.idsBySleeperUserId().get(sleeperUserId);
        LeagueConductRepository.Entry saved =
                conduct.upsert(row.id(), body.playerId(), reason, body.appliesFromWeek(), addedBy);
        return ResponseEntity.ok(conductEntryRow(saved, row.sport()));
    }

    @DeleteMapping("/leagues/{sleeperId}/conduct-list/{entryId}")
    public ResponseEntity<?> deleteConductEntry(@PathVariable String sleeperId, @PathVariable long entryId,
                                                @RequestHeader(value = "X-Sleeper-User", required = false) String sleeperUserId) {
        Optional<LeagueRepository.LeagueRow> found = membership.visibleLeague(sleeperId, sleeperUserId);
        if (found.isEmpty()) return ResponseEntity.notFound().build();
        LeagueRepository.LeagueRow row = found.get();

        // See saveConductEntry: commissioner identity only, an honour system.
        if (!membership.canCommission(row.id(), sleeperUserId)) return forbidden(row);

        boolean deleted = conduct.delete(row.id(), entryId);
        return deleted ? ResponseEntity.noContent().build() : ResponseEntity.notFound().build();
    }

    /** Same message shape and reasoning as {@code POST /power/commissioner}'s 403, so the two endpoints can't disagree. */
    private ResponseEntity<?> forbidden(LeagueRepository.LeagueRow row) {
        boolean commissionerKnown = leagueMembers.anyCommissioner(row.id());
        String message = commissionerKnown
                ? "only this league's Sleeper commissioner may edit the conduct list"
                : "No commissioner is recorded for this league yet.";
        Map<String, Object> response = new LinkedHashMap<>();
        response.put("message", message);
        response.put("commissionerKnown", commissionerKnown);
        return ResponseEntity.status(403).body(response);
    }

    private ConductListResponse conductListBody(LeagueRepository.LeagueRow row, String sleeperUserId) {
        boolean canEdit = membership.canCommission(row.id(), sleeperUserId);
        boolean commissionerKnown = leagueMembers.anyCommissioner(row.id());
        List<LeagueConductRepository.Entry> conductEntries = conduct.forLeague(row.id());
        // Coordinator follow-up 2026-09-23, item 7: one batched lookup for
        // every entry's player name, not one bySleeperId call per entry --
        // the N+1 that grows with the conduct list's own length.
        Map<String, Player> playersById = players.byIds(row.sport(),
                conductEntries.stream().map(LeagueConductRepository.Entry::playerId).collect(Collectors.toSet()));
        List<ConductEntryRow> entries = new ArrayList<>();
        for (LeagueConductRepository.Entry e : conductEntries) {
            entries.add(conductEntryRow(e, playersById));
        }
        return new ConductListResponse(canEdit, commissionerKnown, entries);
    }

    /** Single-entry form for the POST response, where one lookup is already the minimum. */
    private ConductEntryRow conductEntryRow(LeagueConductRepository.Entry e, Sport sport) {
        return conductEntryRow(e, players.bySleeperId(sport, e.playerId())
                .map(p -> Map.of(e.playerId(), p)).orElse(Map.of()));
    }

    private ConductEntryRow conductEntryRow(LeagueConductRepository.Entry e, Map<String, Player> playersById) {
        Player p = playersById.get(e.playerId());
        return new ConductEntryRow(e.id(), e.playerId(), p == null ? "Unknown player" : p.name(), e.reason(),
                e.appliesFromWeek(),
                e.addedByManagerId() == null ? null : managers.displayName(e.addedByManagerId()).orElse(null),
                e.createdAt().toString());
    }
}
