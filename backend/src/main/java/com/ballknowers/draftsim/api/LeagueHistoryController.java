package com.ballknowers.draftsim.api;

import com.ballknowers.draftsim.domain.Sport;
import com.ballknowers.draftsim.engine.PowerRankingService;
import com.ballknowers.draftsim.profile.ManagerProfile;
import com.ballknowers.draftsim.profile.ProfileService;
import com.ballknowers.draftsim.store.LeagueMembership;
import com.ballknowers.draftsim.store.LeagueRepository;
import com.ballknowers.draftsim.store.RosterSeasonRepository;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.*;

/**
 * claude/league-suite.md Phase A: read-only league history and the power-rankings
 * page. Still no authentication here -- see the plan's auth-split table (mode 2,
 * league-member ballots, is Phase B and lives nowhere in this file) -- but every
 * league-addressed route is now scoped to the requesting Sleeper user's own
 * leagues via {@link LeagueMembership}, which is a different thing: it stops the
 * app answering for a league the caller has nothing to do with, and it does not
 * pretend to stop anyone determined.
 */
@RestController
@RequestMapping("/api")
public class LeagueHistoryController {

    private final LeagueRepository leagues;
    private final RosterSeasonRepository rosterSeasons;
    private final PowerRankingService power;
    private final ProfileService profiles;
    private final LeagueMembership membership;

    public LeagueHistoryController(LeagueRepository leagues, RosterSeasonRepository rosterSeasons,
                                   PowerRankingService power, ProfileService profiles,
                                   LeagueMembership membership) {
        this.leagues = leagues;
        this.rosterSeasons = rosterSeasons;
        this.power = power;
        this.profiles = profiles;
        this.membership = membership;
    }

    /**
     * This league, if the caller may see it -- empty for both "no such league"
     * and "not yours", which the callers turn into the same 404 for the same
     * reason {@code LeagueController.visibleDraft} does.
     */
    private Optional<LeagueRepository.LeagueRow> visibleLeague(String sleeperId, String sleeperUserId) {
        Optional<LeagueRepository.LeagueRow> league = leagues.bySleeperId(sleeperId);
        if (league.isEmpty()) return league;
        return membership.canSee(sleeperUserId, league.get().id()) ? league : Optional.empty();
    }

    /**
     * Every season this DB has ingested for this league's chain, newest first,
     * each with its standings. Walked locally ({@link LeagueRepository#chainBySleeperId})
     * rather than re-hitting Sleeper -- run {@code POST /api/ingest/league-history/{id}}
     * first if a season is missing.
     */
    @GetMapping("/leagues/{sleeperId}/history")
    public ResponseEntity<?> history(@PathVariable String sleeperId,
                                     @RequestHeader(value = "X-Sleeper-User", required = false) String sleeperUserId) {
        // Scoped on the league the caller actually asked for. The chain it
        // expands to is by definition that league's own predecessor seasons, so
        // membership in the head is what governs -- and LeagueMembership's own
        // walk already treats predecessors as yours.
        if (visibleLeague(sleeperId, sleeperUserId).isEmpty()) return ResponseEntity.notFound().build();

        List<LeagueRepository.LeagueRow> chain = leagues.chainBySleeperId(sleeperId);
        if (chain.isEmpty()) return ResponseEntity.notFound().build();

        List<Map<String, Object>> seasons = new ArrayList<>();
        for (LeagueRepository.LeagueRow league : chain) {
            List<Map<String, Object>> standings = rosterSeasons.forLeague(league.id()).stream()
                    .map(LeagueHistoryController::standingRow)
                    .toList();
            Map<String, Object> season = new LinkedHashMap<>();
            season.put("season", league.season());
            season.put("leagueId", league.id());
            season.put("sleeperLeagueId", league.sleeperId());
            season.put("name", league.name());
            season.put("standings", standings);
            seasons.add(season);
        }
        return ResponseEntity.ok(Map.of("sleeperLeagueId", sleeperId, "seasons", seasons));
    }

    private static Map<String, Object> standingRow(RosterSeasonRepository.StandingRow r) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("rosterId", r.rosterId());
        m.put("managerId", r.managerId());
        m.put("manager", r.managerName());
        m.put("wins", r.wins());
        m.put("losses", r.losses());
        m.put("ties", r.ties());
        m.put("pointsFor", r.pointsFor());
        m.put("pointsAgainst", r.pointsAgainst());
        m.put("champion", r.finalPlacement() != null && r.finalPlacement() == 1);
        // Null from history()'s per-league call (the page already knows both);
        // populated from managerHistory(), which spans several leagues/seasons
        // and has no other way to tell its rows apart or link back to one.
        m.put("season", r.season());
        m.put("sleeperLeagueId", r.sleeperLeagueId());
        return m;
    }

    /**
     * One manager's record across every ingested season, plus their career-wide
     * draft-side numbers -- reach bias, positional tilt -- from
     * {@link ProfileService#fit}, the same fitted values the simulator itself
     * uses. Per-season draft splits are not built; the plan's acceptance
     * criteria only ask for the two kept visually distinct (Phase A AC4), which
     * this endpoint does by nesting them under separate keys rather than
     * blending "what happened" (record, points) with "what this app thinks
     * about it" (reach, tilt) into one flat shape.
     */
    @GetMapping("/managers/{managerId}/history")
    public ResponseEntity<?> managerHistory(@PathVariable long managerId,
                                            @RequestHeader(value = "X-Sleeper-User", required = false) String sleeperUserId) {
        // Scoped to managers the caller shares a league with. This page is only
        // ever reached from a standings row or a seat popover, so anyone with a
        // legitimate route here already passes -- and without it, a manager id
        // is a small integer, which makes every person in the database walkable
        // by counting upwards.
        if (!membership.canSeeManager(sleeperUserId, managerId)) return ResponseEntity.notFound().build();

        List<RosterSeasonRepository.StandingRow> seasons = rosterSeasons.forManager(managerId);
        if (seasons.isEmpty()) return ResponseEntity.notFound().build();

        Map<String, Object> record = new LinkedHashMap<>();
        record.put("managerId", managerId);
        record.put("manager", seasons.get(0).managerName());
        record.put("seasons", seasons.stream().map(LeagueHistoryController::standingRow).toList());

        // One entry per sport this manager has actually drafted in, rather than
        // one object fitted from Sport.NFL unconditionally.
        //
        // A manager is not a football manager -- they are a manager, and
        // profiles are fitted per (manager, sport). Ten of the twelve Ball
        // Knowers managers are the same Sleeper user id in both leagues, so the
        // old single object put this person's FOOTBALL reach bias and tilt on a
        // page that a basketball league's standings row links to
        // (claude/merge-review-multi-sport.md S1). Answering with the list
        // means no caller has to know a sport to ask, and a manager who plays
        // both gets both -- which is the true answer, not a chosen one.
        //
        // Sports with no drafts observed are omitted rather than sent as
        // zeroes: an empty list is "nothing to say", and a caller that renders
        // one block per entry then needs no per-sport emptiness check.
        //
        // Two fits per request, one per sport. ProfileService's own comment is
        // explicit that fitting is cheap at this data size and deliberately
        // uncached; the seats endpoint already pays for one on every call.
        List<Map<String, Object>> draftHistory = new ArrayList<>();
        for (Sport sport : Sport.values()) {
            ManagerProfile fitted = profiles.fit(sport).profiles().get(managerId);
            if (fitted == null || fitted.draftsObserved() == 0) continue;
            Map<String, Object> derived = new LinkedHashMap<>();
            derived.put("sport", sport);
            derived.put("reachBias", round2(fitted.reachBias()));
            derived.put("positionalTilt", fitted.positionalTilt());
            derived.put("draftsObserved", fitted.draftsObserved());
            // Carried so the client can tell "drafts the board" from "no reach
            // signal exists" -- 0 here means reachBias is the league mean
            // wearing this manager's name, which is every basketball manager,
            // permanently (multi-sport-and-rebrand.md, "Basketball has no
            // reach signal").
            derived.put("picksScored", fitted.picksScored());
            derived.put("provenance", fitted.provenance().name());
            draftHistory.add(derived);
        }
        record.put("draftHistory", draftHistory);

        return ResponseEntity.ok(record);
    }

    /** nflState + every stored power-ranking snapshot for the league, one payload for the client-side toggle. */
    @GetMapping("/leagues/{sleeperId}/power")
    public ResponseEntity<?> powerRankings(@PathVariable String sleeperId,
                                           @RequestHeader(value = "X-Sleeper-User", required = false) String sleeperUserId) {
        Optional<LeagueRepository.LeagueRow> league = visibleLeague(sleeperId, sleeperUserId);
        if (league.isEmpty()) return ResponseEntity.notFound().build();

        PowerRankingService.NflState state = power.nflState();
        var snapshots = power.snapshots(league.get().id());

        Map<String, Object> nflState = new LinkedHashMap<>();
        nflState.put("week", state.week());
        nflState.put("season", state.season());
        nflState.put("seasonStartDate", state.seasonStartDate());
        nflState.put("started", state.started());

        Map<String, Object> response = new LinkedHashMap<>();
        response.put("sleeperLeagueId", sleeperId);
        response.put("nflState", nflState);
        response.put("entries", snapshots.stream().map(LeagueHistoryController::snapshotRow).toList());
        return ResponseEntity.ok(response);
    }

    private static Map<String, Object> snapshotRow(com.ballknowers.draftsim.store.PowerRankingRepository.SnapshotRow r) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("season", r.season());
        m.put("week", r.week());
        m.put("kind", r.kind());
        m.put("rosterId", r.rosterId());
        m.put("managerId", r.managerId());
        m.put("manager", r.managerName());
        m.put("rank", r.rank());
        m.put("score", r.score());
        m.put("note", r.note());
        return m;
    }

    /**
     * (Re)computes both no-auth computed modes for one week and stores them as
     * a snapshot -- safe to re-run; only the current week's snapshot is meant
     * to move (claude/league-suite.md's storage sketch).
     */
    @PostMapping("/leagues/{sleeperId}/power/compute")
    public ResponseEntity<?> compute(@PathVariable String sleeperId, @RequestParam int season,
                                     @RequestParam int week,
                                     @RequestHeader(value = "X-Sleeper-User", required = false) String sleeperUserId) {
        Optional<LeagueRepository.LeagueRow> league = visibleLeague(sleeperId, sleeperUserId);
        if (league.isEmpty()) return ResponseEntity.notFound().build();

        var marketValue = power.computeMarketValue(league.get().id(), sleeperId, season, week);
        var realized = power.computeRealized(league.get().id(), season, week);
        return ResponseEntity.ok(Map.of(
                "marketValue", marketValue.length,
                "realized", realized.length));
    }

    public record CommissionerRanking(Integer season, Integer week, List<Integer> rosterIds) {}

    /** Allan's own ordering for one week -- {@code rosterIds[0]} is 1st, and so on. */
    @PostMapping("/leagues/{sleeperId}/power/commissioner")
    public ResponseEntity<?> commissioner(@PathVariable String sleeperId,
                                          @RequestBody CommissionerRanking body,
                                          @RequestHeader(value = "X-Sleeper-User", required = false) String sleeperUserId) {
        Optional<LeagueRepository.LeagueRow> league = visibleLeague(sleeperId, sleeperUserId);
        if (league.isEmpty()) return ResponseEntity.notFound().build();
        if (body == null || body.rosterIds() == null || body.rosterIds().isEmpty()) {
            return ResponseEntity.badRequest().body(Map.of("message", "rosterIds is required"));
        }
        if (body.season() == null || body.week() == null) {
            return ResponseEntity.badRequest().body(Map.of("message", "season and week are required"));
        }
        var entries = power.saveCommissionerRanking(league.get().id(), body.season(), body.week(), body.rosterIds());
        return ResponseEntity.ok(Map.of("saved", entries.length));
    }

    private static double round2(double d) {
        return Math.round(d * 100.0) / 100.0;
    }
}
