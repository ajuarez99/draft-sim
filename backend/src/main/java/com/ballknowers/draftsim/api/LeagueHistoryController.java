package com.ballknowers.draftsim.api;

import com.ballknowers.draftsim.config.OwnerProperties;
import com.ballknowers.draftsim.domain.Sport;
import com.ballknowers.draftsim.engine.MemberRankingService;
import com.ballknowers.draftsim.engine.PowerRankingService;
import com.ballknowers.draftsim.ingest.RosterOwnerMapper;
import com.ballknowers.draftsim.ingest.SleeperClient;
import com.ballknowers.draftsim.profile.ManagerProfile;
import com.ballknowers.draftsim.profile.ProfileService;
import com.ballknowers.draftsim.store.LeagueMemberRepository;
import com.ballknowers.draftsim.store.LeagueMembership;
import com.ballknowers.draftsim.store.LeagueRepository;
import com.ballknowers.draftsim.store.ManagerRepository;
import com.ballknowers.draftsim.store.RankingBallotRepository;
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
    private final SleeperClient sleeper;
    private final ManagerRepository managers;
    private final LeagueMemberRepository leagueMembers;
    private final RankingBallotRepository ballots;
    private final MemberRankingService memberRankings;
    private final OwnerProperties ownerProperties;

    public LeagueHistoryController(LeagueRepository leagues, RosterSeasonRepository rosterSeasons,
                                   PowerRankingService power, ProfileService profiles,
                                   LeagueMembership membership, SleeperClient sleeper, ManagerRepository managers,
                                   LeagueMemberRepository leagueMembers, RankingBallotRepository ballots,
                                   MemberRankingService memberRankings, OwnerProperties ownerProperties) {
        this.leagues = leagues;
        this.rosterSeasons = rosterSeasons;
        this.power = power;
        this.profiles = profiles;
        this.membership = membership;
        this.sleeper = sleeper;
        this.managers = managers;
        this.leagueMembers = leagueMembers;
        this.ballots = ballots;
        this.memberRankings = memberRankings;
        this.ownerProperties = ownerProperties;
    }

    /**
     * This league, if the caller may see it -- empty for both "no such league"
     * and "not yours", which the callers turn into the same 404 for the same
     * reason {@link LeagueMembership#visibleDraft} does.
     *
     * <p>Not itself a duplicate of that method: this one is addressed by a
     * league, that one by a draft. It stays local because nothing outside this
     * controller resolves a league this way -- the moment something does, it
     * belongs next to visibleDraft in LeagueMembership rather than copied.
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

    /**
     * nflState + every stored power-ranking snapshot for the league, one
     * payload for the client-side toggle -- plus, since
     * claude/power-rankings-ballots.md, one synthetic "MEMBER" snapshot per
     * week that actually has at least one submitted ballot. MEMBER entries
     * are computed on read every call, never written to {@code power_ranking}
     * (V9's migration comment explains why: a ballot's inputs are already
     * frozen the moment the week ends, so a stored average would be a second
     * copy of the truth that can disagree with the first). "Not building" /
     * NFL-only, by construction (nflState() itself hardcodes {@code "nfl"}):
     * a basketball league simply never gets MEMBER entries here.
     */
    @GetMapping("/leagues/{sleeperId}/power")
    public ResponseEntity<?> powerRankings(@PathVariable String sleeperId,
                                           @RequestHeader(value = "X-Sleeper-User", required = false) String sleeperUserId) {
        Optional<LeagueRepository.LeagueRow> league = visibleLeague(sleeperId, sleeperUserId);
        if (league.isEmpty()) return ResponseEntity.notFound().build();
        LeagueRepository.LeagueRow row = league.get();

        PowerRankingService.NflState state = power.nflState();
        var snapshots = power.snapshots(row.id());

        List<Map<String, Object>> entries = new ArrayList<>(
                snapshots.stream().map(LeagueHistoryController::snapshotRow).toList());

        if (row.sport() == Sport.NFL) {
            Map<Long, String> managerNames = managers.names();
            for (int week : memberRankings.weeksWithBallots(row.id())) {
                memberRankings.forWeek(row.id(), sleeperId, week).ifPresent(wr ->
                        wr.entries().forEach(e -> entries.add(memberRow(row.season(), week, e, managerNames))));
            }
        }

        Map<String, Object> nflState = new LinkedHashMap<>();
        nflState.put("week", state.week());
        nflState.put("season", state.season());
        nflState.put("seasonStartDate", state.seasonStartDate());
        nflState.put("started", state.started());

        Map<String, Object> response = new LinkedHashMap<>();
        response.put("sleeperLeagueId", sleeperId);
        response.put("nflState", nflState);
        response.put("entries", entries);
        return ResponseEntity.ok(response);
    }

    private static Map<String, Object> snapshotRow(com.ballknowers.draftsim.store.PowerRankingRepository.SnapshotRow r) {
        return entryRow(r.season(), r.week(), r.kind(), r.rosterId(), r.managerId(), r.managerName(),
                r.rank(), r.score(), r.note(), null, null, null, null, null);
    }

    private static Map<String, Object> memberRow(int season, int week, MemberRankingService.Entry e,
                                                  Map<Long, String> managerNames) {
        String manager = e.managerId() == null ? null : managerNames.get(e.managerId());
        return entryRow(season, week, "MEMBER", e.rosterId(), e.managerId(), manager, e.rank(), e.avgRank(),
                e.note(), e.bestRank(), e.worstRank(), e.stdev(), e.ballotCount(), e.selfRankBias());
    }

    /**
     * One shared row builder for every power-ranking-shaped entry, computed
     * or MEMBER alike. claude/plan-review-power-rankings-ballots.md finding
     * 7: bestRank/worstRank/stdev/ballotCount are real new wire fields, not
     * something a client can parse back out of {@code note} -- a spread bar
     * needs numbers, not prose. Always present, null on every non-MEMBER
     * kind, so the client's type can mark them optional once rather than
     * special-casing per kind. {@code selfRankBias} rides the same way,
     * MEMBER-only -- a per-entry field rather than a separate endpoint,
     * since it is exactly the difference between this entry's own avgRank
     * and this roster's own owner's individual vote, and no other member's
     * individual ballot is ever exposed to compute it.
     */
    private static Map<String, Object> entryRow(int season, int week, String kind, int rosterId, Long managerId,
                                                 String manager, int rank, Double score, String note,
                                                 Integer bestRank, Integer worstRank, Double stdev,
                                                 Integer ballotCount, Integer selfRankBias) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("season", season);
        m.put("week", week);
        m.put("kind", kind);
        m.put("rosterId", rosterId);
        m.put("managerId", managerId);
        m.put("manager", manager);
        m.put("rank", rank);
        m.put("score", score);
        m.put("note", note);
        m.put("bestRank", bestRank);
        m.put("worstRank", worstRank);
        m.put("stdev", stdev);
        m.put("ballotCount", ballotCount);
        m.put("selfRankBias", selfRankBias);
        return m;
    }

    /**
     * Whether this caller may save a ranking for this league -- the header
     * names a {@code league_member} row with {@code is_commissioner}, or
     * equals the configured app owner. Shared between {@code GET /ballot}'s
     * {@code canCommission} (display) and {@code POST /power/commissioner}'s
     * own gate (enforcement), so the two can never disagree about who is
     * allowed to save.
     */
    private boolean canCommission(long leagueId, String sleeperUserId) {
        if (sleeperUserId == null || sleeperUserId.isBlank()) return false;
        if (ownerProperties.configured() && sleeperUserId.equals(ownerProperties.sleeperUserId())) return true;
        Long managerId = managers.idsBySleeperUserId().get(sleeperUserId);
        return managerId != null && leagueMembers.isCommissioner(leagueId, managerId);
    }

    /**
     * Everything the ballot board needs to render itself: whether this
     * caller can submit or commission, the roster/member list (works before
     * any history ingest -- {@link MemberRankingService#members}), and this
     * caller's own already-submitted ballot for the requested week, if any.
     * {@code week} defaults to the current NFL week -- ballots only ever
     * exist for "now", never a past or future one (no carry-forward, no
     * backdating).
     */
    @GetMapping("/leagues/{sleeperId}/ballot")
    public ResponseEntity<?> ballot(@PathVariable String sleeperId,
                                    @RequestParam(required = false) Integer week,
                                    @RequestHeader(value = "X-Sleeper-User", required = false) String sleeperUserId) {
        Optional<LeagueRepository.LeagueRow> league = visibleLeague(sleeperId, sleeperUserId);
        if (league.isEmpty()) return ResponseEntity.notFound().build();
        LeagueRepository.LeagueRow row = league.get();

        PowerRankingService.NflState state = power.nflState();
        int effectiveWeek = week != null ? week : state.week();

        boolean anonymous = sleeperUserId == null || sleeperUserId.isBlank();
        Long callerManagerId = anonymous ? null : managers.idsBySleeperUserId().get(sleeperUserId);
        boolean isMember = callerManagerId != null && leagueMembers.isMember(row.id(), callerManagerId);
        boolean canSubmit = !anonymous && isMember && row.sport() == Sport.NFL && effectiveWeek == state.week();

        Map<String, Object> response = new LinkedHashMap<>();
        response.put("season", row.season());
        response.put("week", effectiveWeek);
        response.put("canSubmit", canSubmit);
        response.put("canCommission", canCommission(row.id(), sleeperUserId));
        response.put("commissionerKnown", leagueMembers.anyCommissioner(row.id()));

        List<MemberRankingService.BallotMember> members = memberRankings.members(row.id(), sleeperId, sleeperUserId);
        response.put("memberCount", members.size());
        response.put("ballotCount", ballots.forWeek(row.id(), effectiveWeek).size());
        response.put("members", members.stream().map(LeagueHistoryController::ballotMemberRow).toList());

        Map<String, Object> mine = null;
        if (callerManagerId != null) {
            Optional<RankingBallotRepository.Ballot> myBallot = ballots.find(row.id(), effectiveWeek, callerManagerId);
            if (myBallot.isPresent()) {
                List<Integer> orderedRosterIds = myBallot.get().entries().stream()
                        .sorted(Comparator.comparingInt(RankingBallotRepository.BallotEntry::rank))
                        .map(RankingBallotRepository.BallotEntry::rosterId)
                        .toList();
                mine = new LinkedHashMap<>();
                mine.put("rosterIds", orderedRosterIds);
                mine.put("submittedAt", myBallot.get().submittedAt().toString());
            }
        }
        response.put("mine", mine);

        return ResponseEntity.ok(response);
    }

    private static Map<String, Object> ballotMemberRow(MemberRankingService.BallotMember m) {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("rosterId", m.rosterId());
        row.put("managerId", m.managerId());
        row.put("manager", m.manager());
        row.put("teamName", m.teamName());
        row.put("isMe", m.isMe());
        return row;
    }

    public record BallotSubmission(Integer week, List<Integer> rosterIds) {}

    /**
     * Submits (or replaces) the caller's own ballot for the current week.
     * {@code manager_id} always comes from the header, never the body -- a
     * body naming someone else's manager is impossible by construction. The
     * {@code season} in the response is read from {@code league.season}
     * server-side; nothing in the body is trusted for it (V9's migration
     * comment, plan-review finding 4).
     *
     * <p><b>Anonymous is refused outright</b> -- the one deliberate exception
     * to this controller's usual "no header means allowed through" rule
     * ({@link LeagueMembership#canSee}), and it needs its own argument
     * because it is NOT the same shape as this app's only other attributed
     * write, {@code OwnerSlot.mayActAsSlot}, which DOES allow anonymous. The
     * difference is recoverability: a pick names an unambiguous seat the
     * request itself points at ({@code pickNo -> slot}), so an anonymous
     * caller there is merely unattributed but the write still lands
     * somewhere real. A ballot has no owner at all without the header --
     * there is no slot to fall back to, nothing to attribute it to later --
     * so anonymous here is unattributable, not merely unclaimed.
     * {@code APP_OWNER_SLEEPER_USER_ID} is deliberately NOT a fallback for
     * this reason: if it were, a header-less curl would silently submit
     * Allan's own ballot, which is strictly worse than refusing it.
     */
    @PostMapping("/leagues/{sleeperId}/ballot")
    public ResponseEntity<?> submitBallot(@PathVariable String sleeperId,
                                          @RequestBody(required = false) BallotSubmission body,
                                          @RequestHeader(value = "X-Sleeper-User", required = false) String sleeperUserId) {
        if (sleeperUserId == null || sleeperUserId.isBlank()) {
            return ResponseEntity.status(401).body(Map.of("message",
                    "X-Sleeper-User is required to submit a ballot -- a ballot has no owner without it"));
        }

        Optional<LeagueRepository.LeagueRow> league = leagues.bySleeperId(sleeperId);
        if (league.isEmpty()) return ResponseEntity.notFound().build();
        LeagueRepository.LeagueRow row = league.get();

        // Mode 2 is NFL-only by construction (PowerRankingService.nflState()
        // hardcodes sleeper.state("nfl")) -- without this check, a basketball
        // league reached by typing the URL would stamp a ballot with an NFL
        // week number. claude/power-rankings-ballots.md "Not building".
        if (row.sport() != Sport.NFL) {
            return ResponseEntity.badRequest().body(Map.of("message", "league member ballots are NFL-only for now"));
        }

        Long managerId = managers.idsBySleeperUserId().get(sleeperUserId);
        if (managerId == null || !leagueMembers.isMember(row.id(), managerId)) {
            return ResponseEntity.status(403).body(Map.of("message", "you are not a member of this league"));
        }

        if (body == null || body.rosterIds() == null || body.rosterIds().isEmpty()) {
            return ResponseEntity.badRequest().body(Map.of("message", "rosterIds is required"));
        }
        if (body.week() == null) {
            return ResponseEntity.badRequest().body(Map.of("message", "week is required"));
        }

        // Current week only -- backdating a ballot after seeing how the
        // games went is exactly the dishonesty this project designs out
        // everywhere else (same argument the commissioner gate below makes).
        PowerRankingService.NflState state = power.nflState();
        if (body.week() != state.week()) {
            return ResponseEntity.badRequest().body(Map.of("message",
                    "ballots can only be submitted for the current week (" + state.week() + ")"));
        }

        // Server-side coverage check: rosterIds must be EXACTLY this
        // league's roster-id set -- no missing roster, no extra, no
        // duplicate. The schema cannot enforce this (no FK on roster_id, by
        // design), so this is the only check that does.
        Set<Integer> validRosterIds = RosterOwnerMapper.rosterIds(sleeper.rosters(sleeperId));
        Set<Integer> submitted = new HashSet<>(body.rosterIds());
        if (submitted.size() != body.rosterIds().size() || !submitted.equals(validRosterIds)) {
            return ResponseEntity.badRequest().body(Map.of("message",
                    "rosterIds must contain exactly this league's roster ids, no duplicates, no missing, no extras"));
        }

        ballots.upsert(row.id(), body.week(), managerId, body.rosterIds());
        return ResponseEntity.ok(Map.of("saved", true, "season", row.season(), "week", body.week()));
    }

    /**
     * (Re)computes the current week's REALIZED snapshot and stores it -- safe
     * to re-run; only the current week's snapshot is meant to move
     * (claude/league-suite.md's storage sketch). Also seeds week 0, this
     * league's one-time preseason baseline, the first time anyone computes at
     * all -- see {@link PowerRankingService#computeWeek0IfMissing} for why
     * that's write-once rather than refreshed on every call like {@code week}
     * itself is.
     */
    @PostMapping("/leagues/{sleeperId}/power/compute")
    public ResponseEntity<?> compute(@PathVariable String sleeperId, @RequestParam int season,
                                     @RequestParam int week,
                                     @RequestHeader(value = "X-Sleeper-User", required = false) String sleeperUserId) {
        Optional<LeagueRepository.LeagueRow> league = visibleLeague(sleeperId, sleeperUserId);
        if (league.isEmpty()) return ResponseEntity.notFound().build();

        var week0 = power.computeWeek0IfMissing(league.get().id(), sleeperId, season);
        var realized = power.computeRealized(league.get().id(), season, week);

        // LinkedHashMap, not Map.of: the reason below is legitimately absent on
        // the happy path, and Map.of throws on a null value.
        Map<String, Object> response = new LinkedHashMap<>();
        response.put("week0", week0.length);
        response.put("realized", realized.length);
        // A zero that does not say why reads as a broken feature. It is almost
        // always "this week has not been scored/ingested yet", which is a thing
        // the caller can act on -- so say so instead of leaving them to guess
        // whether the snapshot failed to persist. Week 0 has no equivalent gap
        // message: a zero there just means it was already set (write-once), not
        // that anything is missing.
        if (realized.length == 0) {
            response.put("realizedSkipped", power.realizedGap(league.get().id(), week));
        }
        return ResponseEntity.ok(response);
    }

    public record CommissionerRanking(Integer season, Integer week, List<Integer> rosterIds) {}

    /**
     * Allan's own ordering for one week -- {@code rosterIds[0]} is 1st, and
     * so on.
     *
     * <p><b>claude/plan-review-power-rankings-ballots.md finding 11.2 and
     * 11.3 -- both deliberate regressions from today's behaviour, on
     * purpose.</b> This endpoint used to accept ANY caller, including
     * anonymous ({@code visibleLeague}'s {@code canSee} returns true for a
     * blank header) and ANY week. After the ballots feature both are gated:
     * only a Sleeper commissioner (or the configured app owner) may save,
     * and only for the current week -- the same "no backdating after seeing
     * how the games went" argument {@code POST /ballot} makes for twelve
     * signed opinions applies verbatim to one. <b>Fail closed</b>: an empty
     * {@code league_member} set (a league never re-ingested since V9) means
     * only the app owner can commission, and the response says why rather
     * than silently doing nothing.
     */
    @PostMapping("/leagues/{sleeperId}/power/commissioner")
    public ResponseEntity<?> commissioner(@PathVariable String sleeperId,
                                          @RequestBody CommissionerRanking body,
                                          @RequestHeader(value = "X-Sleeper-User", required = false) String sleeperUserId) {
        Optional<LeagueRepository.LeagueRow> league = visibleLeague(sleeperId, sleeperUserId);
        if (league.isEmpty()) return ResponseEntity.notFound().build();
        LeagueRepository.LeagueRow row = league.get();

        if (body == null || body.rosterIds() == null || body.rosterIds().isEmpty()) {
            return ResponseEntity.badRequest().body(Map.of("message", "rosterIds is required"));
        }
        if (body.season() == null || body.week() == null) {
            return ResponseEntity.badRequest().body(Map.of("message", "season and week are required"));
        }

        if (!canCommission(row.id(), sleeperUserId)) {
            boolean commissionerKnown = leagueMembers.anyCommissioner(row.id());
            String message = commissionerKnown
                    ? "only this league's Sleeper commissioner may save the power rankings"
                    : "no commissioner detected for this league -- re-run league ingest";
            Map<String, Object> response = new LinkedHashMap<>();
            response.put("message", message);
            response.put("commissionerKnown", commissionerKnown);
            return ResponseEntity.status(403).body(response);
        }

        PowerRankingService.NflState state = power.nflState();
        if (!body.week().equals(state.week())) {
            return ResponseEntity.badRequest().body(Map.of("message",
                    "the commissioner ranking can only be saved for the current week (" + state.week() + ")"));
        }

        var entries = power.saveCommissionerRanking(row.id(), sleeperId, body.season(), body.week(), body.rosterIds());
        return ResponseEntity.ok(Map.of("saved", entries.length));
    }

    private static double round2(double d) {
        return Math.round(d * 100.0) / 100.0;
    }
}
