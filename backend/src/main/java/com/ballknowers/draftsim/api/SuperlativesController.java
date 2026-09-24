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
 * the same way {@code ExpectedWinsController} does. Every response is built with
 * a mutable {@link LinkedHashMap}, never {@code Map.of}, because several fields
 * here (reason, coverage, emptyReason, value, unit, regularSeasonEnd,
 * managerId, avatarId) are legitimately null (AGENTS.md hard rule).
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
                .map(r -> ResponseEntity.ok(body(r)))
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

        if (!membership.canCommission(row.id(), sleeperUserId)) return forbidden(row);

        boolean deleted = conduct.delete(row.id(), entryId);
        return deleted ? ResponseEntity.noContent().build() : ResponseEntity.notFound().build();
    }

    /** Same message shape and reasoning as {@code POST /power/commissioner}'s 403, so the two endpoints can't disagree. */
    private ResponseEntity<?> forbidden(LeagueRepository.LeagueRow row) {
        boolean commissionerKnown = leagueMembers.anyCommissioner(row.id());
        String message = commissionerKnown
                ? "only this league's Sleeper commissioner may edit the conduct list"
                : "no commissioner detected for this league -- re-run league ingest";
        Map<String, Object> response = new LinkedHashMap<>();
        response.put("message", message);
        response.put("commissionerKnown", commissionerKnown);
        return ResponseEntity.status(403).body(response);
    }

    private Map<String, Object> conductListBody(LeagueRepository.LeagueRow row, String sleeperUserId) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("canEdit", membership.canCommission(row.id(), sleeperUserId));
        out.put("commissionerKnown", leagueMembers.anyCommissioner(row.id()));
        List<LeagueConductRepository.Entry> conductEntries = conduct.forLeague(row.id());
        // Coordinator follow-up 2026-09-23, item 7: one batched lookup for
        // every entry's player name, not one bySleeperId call per entry --
        // the N+1 that grows with the conduct list's own length.
        Map<String, Player> playersById = players.byIds(row.sport(),
                conductEntries.stream().map(LeagueConductRepository.Entry::playerId).collect(Collectors.toSet()));
        List<Map<String, Object>> entries = new ArrayList<>();
        for (LeagueConductRepository.Entry e : conductEntries) {
            entries.add(conductEntryRow(e, playersById));
        }
        out.put("entries", entries);
        return out;
    }

    /** Single-entry form for the POST response, where one lookup is already the minimum. */
    private Map<String, Object> conductEntryRow(LeagueConductRepository.Entry e, Sport sport) {
        return conductEntryRow(e, players.bySleeperId(sport, e.playerId())
                .map(p -> Map.of(e.playerId(), p)).orElse(Map.of()));
    }

    private Map<String, Object> conductEntryRow(LeagueConductRepository.Entry e, Map<String, Player> playersById) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", e.id());
        m.put("playerId", e.playerId());
        Player p = playersById.get(e.playerId());
        m.put("playerName", p == null ? "Unknown player" : p.name());
        m.put("reason", e.reason());
        m.put("appliesFromWeek", e.appliesFromWeek());
        m.put("addedBy", e.addedByManagerId() == null ? null : managers.displayName(e.addedByManagerId()).orElse(null));
        m.put("createdAt", e.createdAt().toString());
        return m;
    }

    private static Map<String, Object> body(SeasonSuperlativesService.Result r) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("available", r.available());
        out.put("reason", r.reason());
        out.put("season", r.season());
        out.put("requestedSeason", r.requestedSeason());
        out.put("sport", r.sport().code());
        out.put("throughWeek", r.throughWeek());
        out.put("weeksScored", r.weeksScored());
        out.put("regularSeasonEnd", r.regularSeasonEnd());
        out.put("early", r.early());
        out.put("earlyThresholdWeeks", r.earlyThresholdWeeks());
        out.put("closeGameMargin", r.closeGameMargin());
        out.put("suspensionWeeksObserved", r.suspensionWeeksObserved());
        out.put("commissionerListAvailable", r.commissionerListAvailable());
        out.put("leagueSleeperId", r.leagueSleeperId());
        List<Map<String, Object>> superlatives = new ArrayList<>();
        for (SeasonSuperlativesService.Superlative s : r.superlatives()) {
            superlatives.add(superlativeRow(s));
        }
        out.put("superlatives", superlatives);
        return out;
    }

    private static Map<String, Object> superlativeRow(SeasonSuperlativesService.Superlative s) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("kind", s.kind().name());
        m.put("available", s.available());
        m.put("reason", s.reason());
        m.put("early", s.early());
        m.put("value", s.value());
        m.put("unit", s.unit());
        List<Map<String, Object>> holders = new ArrayList<>();
        for (SeasonSuperlativesService.Holder h : s.holders()) holders.add(holderRow(h));
        m.put("holders", holders);
        m.put("emptyReason", s.emptyReason());
        List<Map<String, Object>> detail = new ArrayList<>();
        for (SeasonSuperlativesService.DetailRow d : s.detail()) detail.add(detailRow(d));
        m.put("detail", detail);
        m.put("coverage", s.coverage() == null ? null : coverageRow(s.coverage()));
        return m;
    }

    private static Map<String, Object> holderRow(SeasonSuperlativesService.Holder h) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("rosterId", h.rosterId());
        m.put("managerId", h.managerId());
        m.put("teamName", h.teamName());
        m.put("avatarId", h.avatarId());
        return m;
    }

    private static Map<String, Object> coverageRow(SeasonSuperlativesService.Coverage c) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("weeksCovered", c.weeksCovered());
        m.put("weeksExcluded", c.weeksExcluded());
        m.put("reasons", c.reasons());
        return m;
    }

    /**
     * One shape per {@code type} discriminator (contracts/superlatives-api.md's
     * detail table). Only {@code WEEK_SCORE} and {@code GAME} are ever produced
     * by this pass (US1); the other branches exist so the shape is ready for
     * the phases that wire {@code LUCK}/{@code BENCH_TOTAL}/{@code PICKUP}/
     * {@code ABSENCE}/{@code CONDUCT} in.
     */
    private static Map<String, Object> detailRow(SeasonSuperlativesService.DetailRow d) {
        Map<String, Object> m = new LinkedHashMap<>();
        if (d instanceof SeasonSuperlativesService.WeekScoreDetail w) {
            m.put("type", "WEEK_SCORE");
            m.put("week", w.week());
            m.put("rosterId", w.rosterId());
            m.put("points", w.points());
        } else if (d instanceof SeasonSuperlativesService.GameDetail g) {
            m.put("type", "GAME");
            m.put("week", g.week());
            m.put("rosterId", g.rosterId());
            m.put("opponentRosterId", g.opponentRosterId());
            m.put("opponentTeamName", g.opponentTeamName());
            m.put("points", g.points());
            m.put("opponentPoints", g.opponentPoints());
            m.put("margin", g.margin());
        } else if (d instanceof SeasonSuperlativesService.LuckDetail l) {
            m.put("type", "LUCK");
            m.put("rosterId", l.rosterId());
            m.put("actualWins", l.actualWins());
            m.put("expectedWins", l.expectedWins());
            m.put("winsAboveExpected", l.winsAboveExpected());
            m.put("swingWeeks", l.swingWeeks());
            m.put("fromWeek", l.fromWeek());
            m.put("throughWeek", l.throughWeek());
            m.put("reading", l.reading());
        } else if (d instanceof SeasonSuperlativesService.BenchTotalDetail b) {
            m.put("type", "BENCH_TOTAL");
            m.put("rosterId", b.rosterId());
            m.put("pointsLeft", b.pointsLeft());
            m.put("weeksCounted", b.weeksCounted());
            m.put("fromWeek", b.fromWeek());
            m.put("throughWeek", b.throughWeek());
            Map<String, Object> biggest = null;
            if (b.biggestWeek() != null) {
                biggest = new LinkedHashMap<>();
                biggest.put("week", b.biggestWeek().week());
                biggest.put("pointsLeft", b.biggestWeek().pointsLeft());
            }
            m.put("biggestWeek", biggest);
        } else if (d instanceof SeasonSuperlativesService.PickupDetail p) {
            m.put("type", "PICKUP");
            m.put("playerId", p.playerId());
            m.put("playerName", p.playerName());
            m.put("position", p.position());
            m.put("rosterId", p.rosterId());
            m.put("addedWeek", p.addedWeek());
            m.put("addType", p.addType());
            m.put("startedWeeks", p.startedWeeks());
            m.put("points", p.points());
        } else if (d instanceof SeasonSuperlativesService.AbsenceDetail a) {
            m.put("type", "ABSENCE");
            m.put("playerId", a.playerId());
            m.put("playerName", a.playerName());
            m.put("position", a.position());
            m.put("rosterId", a.rosterId());
            m.put("gamesMissed", a.gamesMissed());
            m.put("weeksAffected", a.weeksAffected());
            m.put("pointsPerGame", a.pointsPerGame());
            m.put("estimatedPointsLost", a.estimatedPointsLost());
            m.put("estimated", a.estimated());
        } else if (d instanceof SeasonSuperlativesService.ConductDetail c) {
            m.put("type", "CONDUCT");
            m.put("playerId", c.playerId());
            m.put("playerName", c.playerName());
            m.put("rosterId", c.rosterId());
            m.put("source", c.source());
            m.put("weeks", c.weeks());
            m.put("reason", c.reason());
        }
        return m;
    }
}
