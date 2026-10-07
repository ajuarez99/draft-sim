package com.ballknowers.draftsim.api;

import com.ballknowers.draftsim.config.OwnerProperties;
import com.ballknowers.draftsim.domain.Sport;
import com.ballknowers.draftsim.engine.LeagueRecordService;
import com.ballknowers.draftsim.engine.ManagerCareerService;
import com.ballknowers.draftsim.engine.MemberRankingService;
import com.ballknowers.draftsim.engine.ScoredWeeks;
import com.ballknowers.draftsim.engine.PlayoffOddsService;
import com.ballknowers.draftsim.engine.PowerRankingService;
import com.ballknowers.draftsim.engine.TransactionAnalysisService;
import com.ballknowers.draftsim.ingest.RosterOwnerMapper;
import com.ballknowers.draftsim.ingest.SleeperClient;
import com.ballknowers.draftsim.profile.ManagerProfile;
import com.ballknowers.draftsim.profile.ProfileService;
import com.ballknowers.draftsim.store.LeagueMemberRepository;
import com.ballknowers.draftsim.store.LeagueMembership;
import com.ballknowers.draftsim.store.LeagueRepository;
import com.ballknowers.draftsim.store.WeekBound;
import com.ballknowers.draftsim.store.ManagerRepository;
import com.ballknowers.draftsim.store.RankingBallotRepository;
import com.ballknowers.draftsim.store.RosterSeasonRepository;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.*;

import com.ballknowers.draftsim.api.dto.ManagerHistoryResponses.CareerResponse;
import com.ballknowers.draftsim.api.dto.ManagerHistoryResponses.DraftHistoryEntry;
import com.ballknowers.draftsim.api.dto.ManagerHistoryResponses.ManagerHistoryResponse;
import com.ballknowers.draftsim.api.dto.PowerRankingResponses.BackfillResponse;
import com.ballknowers.draftsim.api.dto.PowerRankingResponses.Backfilled;
import com.ballknowers.draftsim.api.dto.PowerRankingResponses.BallotMemberRow;
import com.ballknowers.draftsim.api.dto.PowerRankingResponses.BallotResponse;
import com.ballknowers.draftsim.api.dto.PowerRankingResponses.BallotSaved;
import com.ballknowers.draftsim.api.dto.PowerRankingResponses.CommissionerSaved;
import com.ballknowers.draftsim.api.dto.PowerRankingResponses.ComputeResponse;
import com.ballknowers.draftsim.api.dto.PowerRankingResponses.MyBallot;
import com.ballknowers.draftsim.api.dto.PowerRankingResponses.PlayoffOddsSummaryRow;
import com.ballknowers.draftsim.api.dto.PowerRankingResponses.PowerEntry;
import com.ballknowers.draftsim.api.dto.PowerRankingResponses.PowerRankingsResponse;
import com.ballknowers.draftsim.api.dto.PowerRankingResponses.Skipped;
import com.ballknowers.draftsim.api.dto.PowerRankingResponses.SportStateRow;
import com.ballknowers.draftsim.api.dto.RecordBookResponses.RecordBookResponse;
import com.ballknowers.draftsim.api.dto.StandingsResponses.HistoryStandingRow;
import com.ballknowers.draftsim.api.dto.StandingsResponses.StandingBase;

import static com.ballknowers.draftsim.util.Rounding.round2;

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
    private final PlayoffOddsService playoffOdds;
    private final LeagueRecordService records;
    private final ManagerCareerService careers;
    private final ScoredWeeks scoredWeeks;

    public LeagueHistoryController(LeagueRepository leagues, RosterSeasonRepository rosterSeasons,
                                   PowerRankingService power, ProfileService profiles,
                                   LeagueMembership membership, SleeperClient sleeper, ManagerRepository managers,
                                   LeagueMemberRepository leagueMembers, RankingBallotRepository ballots,
                                   MemberRankingService memberRankings, OwnerProperties ownerProperties,
                                   PlayoffOddsService playoffOdds, LeagueRecordService records,
                                   ManagerCareerService careers, ScoredWeeks scoredWeeks) {
        this.scoredWeeks = scoredWeeks;
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
        this.playoffOdds = playoffOdds;
        this.ownerProperties = ownerProperties;
        this.records = records;
        this.careers = careers;
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
        Optional<LeagueRepository.LeagueRow> visible = membership.visibleLeague(sleeperId, sleeperUserId);
        if (visible.isEmpty()) return ResponseEntity.notFound().build();

        List<LeagueRepository.LeagueRow> chain = leagues.chainBySleeperId(sleeperId);
        if (chain.isEmpty()) return ResponseEntity.notFound().build();

        // Spec 013 T031: caller -> manager id, never a username match. Null when
        // signed out or the user has no manager row, so isMe is false for everyone.
        boolean callerAnonymous = LeagueMembership.isAnonymous(sleeperUserId);
        Long callerManagerId = callerAnonymous ? null : managers.idsBySleeperUserId().get(sleeperUserId);

        List<HistorySeason> seasons = new ArrayList<>();
        for (int i = 0; i < chain.size(); i++) {
            LeagueRepository.LeagueRow league = chain.get(i);
            // T028 (specs/006-deeper-history-both-sports research R2):
            // this used to pass `i == 0` -- true only for the newest season in
            // the chain -- as a proxy for "this season is still being played."
            // That proxy is silently wrong the moment the newest season
            // actually finishes: index 0 never changes, but the season it
            // names eventually completes, and the rank would keep reporting
            // IN_PROGRESS forever after a real champion exists. `league.complete()`
            // is the stored fact itself (Sleeper's own top-level `status`, V21),
            // not a position in a list that happens to correlate with it today.
            PowerRankingService.SeasonRanks ranks = power.finalRankForSeason(league.id(), !league.complete());
            // T026: champion is derived from `league.complete()`, which this
            // loop already has in scope for the single season it is building --
            // NOT from StandingRow.complete(), which forLeague() never
            // populates (that field only carries a value from forManager(),
            // see the StandingRow javadoc). Passing false here for a
            // still-unfinished season is what actually clears a stale trophy
            // from this response; reading the always-null r.complete() instead
            // would have made every league-scoped season silently lose its
            // champion, finished or not.
            // Team name first, username second on every league page: this
            // season's own team name (it changes year to year), falling back to
            // the username the row already carries.
            Map<Long, String> teamNames = teamNamesByManager(league.id());
            List<HistoryStandingRow> standings = rosterSeasons.forLeague(league.id()).stream()
                    .map(r -> {
                        // The rank cell. rankStatus is on EVERY row, and finalRank is non-null exactly
                        // when the status is RANKED. The other three statuses each carry a different
                        // reason there is no rank, which is the point: a bare null on the wire would
                        // leave the page nothing to say (FR-005). A roster missing from an
                        // otherwise-present snapshot has no rank of its own, whatever the season's
                        // status is.
                        Integer rank = ranks.byRoster().get(r.rosterId());
                        String rankStatus = (rank == null && ranks.status() == PowerRankingService.RankStatus.RANKED
                                ? PowerRankingService.RankStatus.UNAVAILABLE : ranks.status()).name();
                        return new HistoryStandingRow(
                                StandingBase.of(r, league.complete()),
                                rankStatus, rank, rank == null ? null : ranks.week(),
                                // teamName is legitimately null for an unowned roster.
                                r.managerId() == null ? null : teamNames.getOrDefault(r.managerId(), r.managerName()),
                                // Spec 013 T031: this row's manager is the caller's manager.
                                callerManagerId != null && callerManagerId.equals(r.managerId()));
                    })
                    .toList();
            seasons.add(new HistorySeason(league.season(), league.id(), league.sleeperId(), league.name(), standings));
        }

        // Records span the CHAIN, not seasons[0]. league.id is a league-season
        // here, so a single id answers "this season" -- the question the page
        // could already answer (specs/002-league-history-record-book, FR-001).
        List<Long> chainIds = chain.stream().map(LeagueRepository.LeagueRow::id).toList();

        return ResponseEntity.ok(new HistoryResponse(sleeperId,
                // Spec 013 T031: gates the History page's Compute control. Same rule as ballot().
                membership.canCommission(visible.get().id(), sleeperUserId),
                RecordBookResponse.of(records.forChain(chainIds, WeekBound.ALL_WEEKS)),
                seasons));
    }

    /** {@code GET /leagues/{id}/history}. The record book spans the chain; seasons are newest first. */
    public record HistoryResponse(String sleeperLeagueId, boolean canCommission, RecordBookResponse records,
                                  List<HistorySeason> seasons) {}

    public record HistorySeason(int season, long leagueId, String sleeperLeagueId, String name,
                                List<HistoryStandingRow> standings) {}

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

        // The flat seasons[] that used to sit here is GONE (T076) -- step 3 of
        // the migration in specs/006-deeper-history-both-sports/contracts/manager-profile-api.md,
        // now that ManagerHistory.tsx reads careers[].seasons[] instead.
        // careers[] partitions exactly the same rows by sport (every
        // roster-season of this manager lands in exactly one CareerProfile),
        // so nothing is dropped from the response -- only the second, flat
        // copy that let a caller total two sports together without noticing.

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
        List<DraftHistoryEntry> draftHistory = new ArrayList<>();
        for (Sport sport : Sport.values()) {
            ProfileService.Fit fit = profiles.fit(sport);
            ManagerProfile fitted = fit.profiles().get(managerId);
            if (fitted == null || fitted.draftsObserved() == 0) continue;
            // Room-relative reach and its standard error (audit 11); null when
            // there are no scoreable picks / fewer than 2.
            Double rel = fit.relativeReachBias().get(managerId);
            Double se = fit.relativeReachStdErr().get(managerId);
            draftHistory.add(new DraftHistoryEntry(sport, round2(fitted.reachBias()),
                    rel == null ? null : round2(rel), se == null ? null : round2(se),
                    fitted.positionalTilt(), fitted.draftsObserved(),
                    // Carried so the client can tell "drafts the board" from "no reach
                    // signal exists" -- 0 here means reachBias is the league mean
                    // wearing this manager's name, which is every basketball manager,
                    // permanently (multi-sport-and-rebrand.md, "Basketball has no
                    // reach signal").
                    fitted.picksScored(), fitted.provenance().name()));
        }

        // T040 (specs/006-deeper-history-both-sports, US3): careers[] is now
        // the ONLY season list in this response -- it shipped alongside the
        // flat seasons[] for one release, and T076 removed that field once
        // ManagerHistory.tsx had moved over. At no point does a caller see a
        // career total spanning two sports (careers[] is one entry per sport,
        // same rule draftHistory above already follows).
        List<CareerResponse> careerList = careers.forManager(managerId).stream().map(CareerResponse::of).toList();

        return ResponseEntity.ok(new ManagerHistoryResponse(managerId, seasons.get(0).managerName(),
                seasons.get(0).avatarId(), draftHistory, careerList));
    }

    /**
     * sportState + every stored power-ranking snapshot for the league, one
     * payload for the client-side toggle -- plus, since
     * claude/power-rankings-ballots.md, one synthetic "MEMBER" snapshot per
     * week that actually has at least one submitted ballot. MEMBER entries
     * are computed on read every call, never written to {@code power_ranking}
     * (V9's migration comment explains why: a ballot's inputs are already
     * frozen the moment the week ends, so a stored average would be a second
     * copy of the truth that can disagree with the first).
     *
     * <p>Every sport, since claude/nba-power-rankings.md. This used to skip
     * MEMBER entries for anything but football, purely because
     * {@code nflState()} hardcoded {@code "nfl"}; with the state per-sport
     * that reason is gone, and leaving the skip in would have collected
     * basketball's ballots and then never rendered them.
     */
    @GetMapping("/leagues/{sleeperId}/power")
    public ResponseEntity<?> powerRankings(@PathVariable String sleeperId,
                                           @RequestHeader(value = "X-Sleeper-User", required = false) String sleeperUserId) {
        Optional<LeagueRepository.LeagueRow> league = membership.visibleLeague(sleeperId, sleeperUserId);
        if (league.isEmpty()) return ResponseEntity.notFound().build();
        LeagueRepository.LeagueRow row = league.get();

        PowerRankingService.SportState state = power.sportState(row.sport());
        // A computed (COMPUTED_REALIZED) ranking for a week past the latest FINAL one was written
        // from a partial or unplayed week; ignored on read, kept in the table. Week 0 (the
        // preseason baseline) and the human rankings (COMMISSIONER, MEMBER), which are opinions
        // about the open week, are never filtered.
        int latestFinal = scoredWeeks.of(row.id()).latestFinal();
        var snapshots = power.snapshots(row.id()).stream()
                .filter(s -> !("COMPUTED_REALIZED".equals(s.kind()) && s.week() > 0 && s.week() > latestFinal))
                .toList();

        List<PowerEntry> entries = new ArrayList<>(
                snapshots.stream().map(LeagueHistoryController::snapshotRow).toList());

        Map<Long, String> managerNames = managers.names();
        Map<Long, String> managerAvatars = managers.avatarIds();
        for (int week : memberRankings.weeksWithBallots(row.id())) {
            memberRankings.forWeek(row.id(), sleeperId, week).ifPresent(wr ->
                    wr.entries().forEach(e -> entries.add(memberRow(row.season(), week, e, managerNames, managerAvatars))));
        }

        // Playoff odds ride on the entries rather than a parallel structure:
        // the client reads makesPlayoffsPct off the entry it is already
        // rendering. A week with no stored snapshot keeps the null it was born
        // with, and the client renders "--" for it -- the odds shown against a
        // week are the odds AS OF that week, never last week's borrowed.
        attachPlayoffOdds(entries, playoffOdds.madePctByWeek(row.id(), row.season()));

        // Every entry (computed and MEMBER alike) names its team, so the page can
        // put the team first and the username second without a second lookup.
        Map<Long, String> teamNames = teamNamesByManager(row.id());
        entries.replaceAll(e -> e.withTeamName(
                e.managerId() == null ? null : teamNames.getOrDefault(e.managerId(), e.manager())));

        return ResponseEntity.ok(new PowerRankingsResponse(sleeperId,
                new SportStateRow(state.week(), state.season(), state.seasonStartDate(), state.started()),
                entries,
                // Null when this league has no odds at all -- the page then says nothing
                // about a simulation instead of describing one that never ran.
                playoffOdds.summary(row.id(), row.season())
                        .map(o -> new PlayoffOddsSummaryRow(o.week(), o.iterations(), o.model(), o.weeksOfScoring()))
                        .orElse(null)));
    }

    private static void attachPlayoffOdds(List<PowerEntry> entries, Map<Integer, Map<Integer, Double>> byWeek) {
        if (byWeek.isEmpty()) return;
        entries.replaceAll(e -> {
            Map<Integer, Double> week = byWeek.get(e.week());
            Double pct = week == null ? null : week.get(e.rosterId());
            return pct == null ? e : e.withMakesPlayoffsPct(pct);
        });
    }

    /** Sleeper's per-league team name by manager; the ingest already falls back to the display name and drops "TBD". */
    private Map<Long, String> teamNamesByManager(long leagueId) {
        Map<Long, String> out = new HashMap<>();
        for (com.ballknowers.draftsim.store.LeagueMemberRepository.MemberRow m : leagueMembers.forLeague(leagueId)) {
            if (m.teamName() != null && !m.teamName().isBlank()) out.put(m.managerId(), m.teamName());
        }
        return out;
    }

    private static PowerEntry snapshotRow(com.ballknowers.draftsim.store.PowerRankingRepository.SnapshotRow r) {
        return entryRow(r.season(), r.week(), r.kind(), r.rosterId(), r.managerId(), r.managerName(), r.avatarId(),
                r.rank(), r.score(), r.note(), null, null, null, null, null);
    }

    private static PowerEntry memberRow(int season, int week, MemberRankingService.Entry e,
                                                  Map<Long, String> managerNames, Map<Long, String> managerAvatars) {
        String manager = e.managerId() == null ? null : managerNames.get(e.managerId());
        String avatarId = e.managerId() == null ? null : managerAvatars.get(e.managerId());
        return entryRow(season, week, "MEMBER", e.rosterId(), e.managerId(), manager, avatarId, e.rank(), e.avgRank(),
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
    private static PowerEntry entryRow(int season, int week, String kind, int rosterId, Long managerId,
                                       String manager, String avatarId, int rank, Double score, String note,
                                       Integer bestRank, Integer worstRank, Double stdev,
                                       Integer ballotCount, Integer selfRankBias) {
        // makesPlayoffsPct is always present, null until a stored odds snapshot fills it
        // in (attachPlayoffOdds); teamName is filled in last. Same discipline as
        // bestRank/stdev: the client's type is one shape, not one shape per kind.
        return new PowerEntry(season, week, kind, rosterId, managerId, manager, avatarId, rank, score, note,
                bestRank, worstRank, stdev, ballotCount, selfRankBias, null, null);
    }

    /**
     * Everything the ballot board needs to render itself: whether this
     * caller can submit or commission, the roster/member list (works before
     * any history ingest -- {@link MemberRankingService#members}), and this
     * caller's own already-submitted ballot for the requested week, if any.
     * {@code week} defaults to the current week for this league's own sport
     * -- ballots only ever exist for "now", never a past or future one (no
     * carry-forward, no backdating). "Now" is legitimately week 0 for a sport
     * in its offseason; see {@link #weekLabel}.
     */
    @GetMapping("/leagues/{sleeperId}/ballot")
    public ResponseEntity<?> ballot(@PathVariable String sleeperId,
                                    @RequestParam(required = false) Integer week,
                                    @RequestHeader(value = "X-Sleeper-User", required = false) String sleeperUserId) {
        Optional<LeagueRepository.LeagueRow> league = membership.visibleLeague(sleeperId, sleeperUserId);
        if (league.isEmpty()) return ResponseEntity.notFound().build();
        LeagueRepository.LeagueRow row = league.get();

        PowerRankingService.SportState state = power.sportState(row.sport());
        // Null check, never truthiness: week 0 is a real, submittable week for
        // a sport in its offseason, and `week != null` is the only test that
        // tells "the caller asked for week 0" from "the caller asked for
        // nothing".
        int effectiveWeek = week != null ? week : state.week();

        boolean anonymous = sleeperUserId == null || sleeperUserId.isBlank();
        Long callerManagerId = anonymous ? null : managers.idsBySleeperUserId().get(sleeperUserId);
        boolean isMember = callerManagerId != null && leagueMembers.isMember(row.id(), callerManagerId);
        boolean canSubmit = !anonymous && isMember && effectiveWeek == state.week();

        boolean canCommission = membership.canCommission(row.id(), sleeperUserId);
        boolean commissionerKnown = leagueMembers.anyCommissioner(row.id());
        List<MemberRankingService.BallotMember> members = memberRankings.members(row.id(), sleeperId, sleeperUserId);
        int ballotCount = ballots.forWeek(row.id(), effectiveWeek).size();

        MyBallot mine = null;
        if (callerManagerId != null) {
            Optional<RankingBallotRepository.Ballot> myBallot = ballots.find(row.id(), effectiveWeek, callerManagerId);
            if (myBallot.isPresent()) {
                List<Integer> orderedRosterIds = myBallot.get().entries().stream()
                        .sorted(Comparator.comparingInt(RankingBallotRepository.BallotEntry::rank))
                        .map(RankingBallotRepository.BallotEntry::rosterId)
                        .toList();
                mine = new MyBallot(orderedRosterIds, myBallot.get().submittedAt().toString());
            }
        }

        return ResponseEntity.ok(new BallotResponse(row.season(), effectiveWeek, canSubmit, canCommission,
                commissionerKnown, members.size(), ballotCount,
                members.stream().map(m -> new BallotMemberRow(m.rosterId(), m.managerId(), m.manager(),
                        m.avatarId(), m.teamName(), m.isMe())).toList(),
                mine));
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
        PowerRankingService.SportState state = power.sportState(row.sport());
        if (body.week() != state.week()) {
            return ResponseEntity.badRequest().body(Map.of("message",
                    "ballots can only be submitted for " + weekLabel(state.week()) + " -- no backdating"));
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
        return ResponseEntity.ok(new BallotSaved(true, row.season(), body.week()));
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
    /**
     * Computes the missing end-of-season snapshot for completed seasons in this
     * league's chain. specs/002-league-history-record-book FR-006.
     *
     * <p>Sibling of {@code /power/compute}, which only ever targets the season
     * the caller's league id names -- which is why three of four played seasons
     * in this database had no power rankings at all: nobody could have run it
     * against a predecessor season without knowing that season's own Sleeper id.
     *
     * <p>Makes no Sleeper call. The backing compute reads roster_week_points,
     * which the history ingest has already filled.
     */
    @PostMapping("/leagues/{sleeperId}/power/backfill")
    public ResponseEntity<?> backfillFinalRanks(@PathVariable String sleeperId,
                                                @RequestParam(required = false) Integer season,
                                                @RequestHeader(value = "X-Sleeper-User", required = false) String sleeperUserId) {
        Optional<LeagueRepository.LeagueRow> visible = membership.visibleLeague(sleeperId, sleeperUserId);
        if (visible.isEmpty()) return ResponseEntity.notFound().build();
        // History shows this button only when canCommission, but until 2026-10-02 the route
        // checked membership alone (spec 013 open follow-up). Same gate as power/compute.
        if (!membership.canCommission(visible.get().id(), sleeperUserId)) {
            return ResponseEntity.status(403).body(Map.of("message",
                    "only this league's Sleeper commissioner may compute past seasons' final ranks"));
        }
        List<LeagueRepository.LeagueRow> chain = leagues.chainBySleeperId(sleeperId);
        if (chain.isEmpty()) return ResponseEntity.notFound().build();

        if (season != null && chain.stream().noneMatch(r -> r.season() == season)) {
            return ResponseEntity.badRequest().body(Map.of(
                    "error", "season " + season + " is not in this league's chain"));
        }

        List<PowerRankingService.BackfilledSeason> results = power.backfillFinalRanks(chain, season);

        List<Backfilled> backfilled = new ArrayList<>();
        List<Skipped> skipped = new ArrayList<>();
        for (PowerRankingService.BackfilledSeason r : results) {
            if (r.reason() == null) backfilled.add(new Backfilled(r.season(), r.week(), r.entries()));
            else skipped.add(new Skipped(r.season(), r.reason()));
        }

        // An empty "backfilled" always arrives with a populated "skipped". A
        // zero that does not say why reads as a broken feature -- the same
        // argument /power/compute below already makes about its own counts.
        return ResponseEntity.ok(new BackfillResponse(backfilled, skipped));
    }

    @PostMapping("/leagues/{sleeperId}/power/compute")
    public ResponseEntity<?> compute(@PathVariable String sleeperId, @RequestParam int season,
                                     @RequestParam int week,
                                     @RequestHeader(value = "X-Sleeper-User", required = false) String sleeperUserId) {
        Optional<LeagueRepository.LeagueRow> league = membership.visibleLeague(sleeperId, sleeperUserId);
        if (league.isEmpty()) return ResponseEntity.notFound().build();

        // The UI labels this "Recompute (commissioner)" and shows it only when
        // ballot.canCommission, but the route used to check membership alone
        // (claude/audit-2026-09-28/10). Same gate as the other commissioner writes,
        // an honour system since 2026-10-05 (claude/audit-2026-09-28/04, amended). An operator curling this passes the commissioner's X-Sleeper-User.
        if (!membership.canCommission(league.get().id(), sleeperUserId)) {
            return ResponseEntity.status(403).body(Map.of("message",
                    "only this league's Sleeper commissioner may recompute the power rankings"));
        }

        // The week-0 preseason baseline does not depend on the requested week or on finality.
        var week0 = power.computeWeek0IfMissing(league.get().id(), sleeperId, season);

        // The server, not the caller, keeps a partial week out of a snapshot: a request for an
        // in-progress week (the power rankings button sends the open week) writes no realized
        // ranking for it, and odds run through the latest FINAL week instead. Finality is
        // ScoredWeeks' (spec 009 WeekFinality; no fetch rows or loaded_complete = all final).
        var scored = scoredWeeks.of(league.get().id());
        boolean weekFinal = scored.isFinal(week);
        int oddsThrough = weekFinal ? week : Math.min(week, scored.latestFinal());

        var realized = weekFinal
                ? power.computeRealized(league.get().id(), season, week)
                : new com.ballknowers.draftsim.store.PowerRankingRepository.Entry[0];
        // Same trigger as the box-score snapshot, deliberately: odds are never
        // computed on a page load (claude/playoff-odds.md).
        var odds = oddsThrough >= 1
                ? playoffOdds.compute(league.get().id(), season, oddsThrough)
                : java.util.List.<com.ballknowers.draftsim.store.PlayoffOddsRepository.Entry>of();

        // A zero that does not say why reads as a broken feature. It is almost
        // always "this week has not been scored/ingested yet", which is a thing
        // the caller can act on -- so say so instead of leaving them to guess
        // whether the snapshot failed to persist. Three wire states; see ComputeResponse.
        Optional<String> realizedSkipped = null;
        if (!weekFinal) {
            realizedSkipped = Optional.of("week " + week + " is not final yet (scores can still change), so no ranking was saved for it"
                    + (oddsThrough >= 1 ? "; odds ran through week " + oddsThrough : ""));
        } else if (realized.length == 0) {
            realizedSkipped = Optional.ofNullable(power.realizedGap(league.get().id(), week));
        }
        return ResponseEntity.ok(new ComputeResponse(week0.entries().length, realized.length, odds.size(),
                // The week the odds were ACTUALLY computed through, which is the requested week only
                // when that week is final. Absent when no week is final yet, with the reason beside it.
                oddsThrough >= 1 ? oddsThrough : null,
                oddsThrough >= 1 ? null : "no week of this season is final yet, so there is nothing to forecast from",
                realizedSkipped,
                // Week 0 has one gap worth reporting and only one: a league that has
                // not drafted yet. "Already set" is the ordinary case and carries no
                // reason, so this key is absent then rather than present and empty.
                week0.skipped()));
    }

    public record CommissionerRanking(Integer season, Integer week, List<Integer> rosterIds) {}

    /**
     * Allan's own ordering for one week -- {@code rosterIds[0]} is 1st, and
     * so on.
     *
     * <p><b>claude/plan-review-power-rankings-ballots.md finding 11.2 and
     * 11.3 -- both deliberate regressions from today's behaviour, on
     * purpose.</b> This endpoint used to accept ANY caller, including
     * anonymous ({@code visibleLeague}'s {@code canSee} used to return true for a
     * blank header; it no longer does) and ANY week. After the ballots feature both are gated:
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
        Optional<LeagueRepository.LeagueRow> league = membership.visibleLeague(sleeperId, sleeperUserId);
        if (league.isEmpty()) return ResponseEntity.notFound().build();
        LeagueRepository.LeagueRow row = league.get();

        if (body == null || body.rosterIds() == null || body.rosterIds().isEmpty()) {
            return ResponseEntity.badRequest().body(Map.of("message", "rosterIds is required"));
        }
        if (body.season() == null || body.week() == null) {
            return ResponseEntity.badRequest().body(Map.of("message", "season and week are required"));
        }

        // An honour system since 2026-10-05 (claude/audit-2026-09-28/04, amended): the commissioner identity below is a
        // header anyone can copy from Sleeper's public league-users list. Commissioner
        // rankings are for fun, so this stops honest mistakes and nothing more.
        if (!membership.canCommission(row.id(), sleeperUserId)) {
            boolean commissionerKnown = leagueMembers.anyCommissioner(row.id());
            String message = commissionerKnown
                    ? "only this league's Sleeper commissioner may save the power rankings"
                    : "No commissioner is recorded for this league yet.";
            Map<String, Object> response = new LinkedHashMap<>();
            response.put("message", message);
            response.put("commissionerKnown", commissionerKnown);
            return ResponseEntity.status(403).body(response);
        }

        PowerRankingService.SportState state = power.sportState(row.sport());
        if (!body.week().equals(state.week())) {
            return ResponseEntity.badRequest().body(Map.of("message",
                    "the commissioner ranking can only be saved for " + weekLabel(state.week()) + " -- no backdating"));
        }

        var entries = power.saveCommissionerRanking(row.id(), sleeperId, body.season(), body.week(), body.rosterIds());
        return ResponseEntity.ok(new CommissionerSaved(entries.length));
    }

    /**
     * {@code "week 7"}, or {@code "the preseason"} for week 0.
     *
     * <p>{@code /state/{sport}} reports {@code week: 0} for a sport between
     * seasons -- measured 2026-09-15, {@code /state/nba} answered
     * {@code week: 0} while {@code /state/nfl} answered 2 -- and that week is
     * a perfectly good one to hold an opinion in: a commissioner ordering and
     * a member ballot are stated opinions, so neither needs a played game to
     * be honest. What it is NOT is a thing anyone calls "week 0", so no
     * message this controller returns should.
     */
    private static String weekLabel(int week) {
        return week == 0 ? "the preseason" : "week " + week;
    }
}
