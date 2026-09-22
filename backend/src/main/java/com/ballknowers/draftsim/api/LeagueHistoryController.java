package com.ballknowers.draftsim.api;

import com.ballknowers.draftsim.config.OwnerProperties;
import com.ballknowers.draftsim.domain.Sport;
import com.ballknowers.draftsim.engine.LeagueRecordService;
import com.ballknowers.draftsim.engine.ManagerCareerService;
import com.ballknowers.draftsim.engine.MemberRankingService;
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
    private final PlayoffOddsService playoffOdds;
    private final LeagueRecordService records;
    private final ManagerCareerService careers;

    public LeagueHistoryController(LeagueRepository leagues, RosterSeasonRepository rosterSeasons,
                                   PowerRankingService power, ProfileService profiles,
                                   LeagueMembership membership, SleeperClient sleeper, ManagerRepository managers,
                                   LeagueMemberRepository leagueMembers, RankingBallotRepository ballots,
                                   MemberRankingService memberRankings, OwnerProperties ownerProperties,
                                   PlayoffOddsService playoffOdds, LeagueRecordService records,
                                   ManagerCareerService careers) {
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
            List<Map<String, Object>> standings = rosterSeasons.forLeague(league.id()).stream()
                    .map(r -> withFinalRank(standingRow(r, league.complete()), r.rosterId(), ranks))
                    .toList();
            Map<String, Object> season = new LinkedHashMap<>();
            season.put("season", league.season());
            season.put("leagueId", league.id());
            season.put("sleeperLeagueId", league.sleeperId());
            season.put("name", league.name());
            season.put("standings", standings);
            seasons.add(season);
        }

        // Records span the CHAIN, not seasons[0]. league.id is a league-season
        // here, so a single id answers "this season" -- the question the page
        // could already answer (specs/002-league-history-record-book, FR-001).
        List<Long> chainIds = chain.stream().map(LeagueRepository.LeagueRow::id).toList();

        // LinkedHashMap, not Map.of: marginsUnavailableReason is legitimately
        // null once pairings exist, and Map.of throws on a null value. Same
        // reason the power compute endpoint below builds its response this way.
        Map<String, Object> response = new LinkedHashMap<>();
        response.put("sleeperLeagueId", sleeperId);
        response.put("records", recordBook(records.forChain(chainIds)));
        response.put("seasons", seasons);
        return ResponseEntity.ok(response);
    }

    /**
     * The record book as the client reads it. Always emitted, even when every
     * list is empty -- an absent key would make "this league has no records"
     * indistinguishable from "this endpoint is older than the record book"
     * (contracts/league-history-api.md).
     */
    /**
     * The rank cell on a standings row. rankStatus is present on EVERY row;
     * finalRank is non-null exactly when the status is RANKED. The three other
     * statuses each carry a different reason there is no rank, which is the
     * point -- a bare null on the wire would leave the page nothing to say
     * (FR-005).
     */
    private static Map<String, Object> withFinalRank(Map<String, Object> row, int rosterId,
                                                     PowerRankingService.SeasonRanks ranks) {
        Integer rank = ranks.byRoster().get(rosterId);
        // A roster missing from an otherwise-present snapshot has no rank of its
        // own, whatever the season's status is.
        row.put("rankStatus", (rank == null && ranks.status() == PowerRankingService.RankStatus.RANKED
                ? PowerRankingService.RankStatus.UNAVAILABLE : ranks.status()).name());
        row.put("finalRank", rank);
        row.put("finalRankWeek", rank == null ? null : ranks.week());
        return row;
    }

    private static Map<String, Object> recordBook(LeagueRecordService.RecordBook b) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("limit", b.limit());
        m.put("highestWeeks", b.highestWeeks().stream().map(LeagueHistoryController::weeklyScoreRow).toList());
        m.put("lowestWeeks", b.lowestWeeks().stream().map(LeagueHistoryController::weeklyScoreRow).toList());
        m.put("closestMatchups", b.closestMatchups().stream().map(LeagueHistoryController::marginRow).toList());
        m.put("biggestBlowouts", b.biggestBlowouts().stream().map(LeagueHistoryController::marginRow).toList());
        m.put("marginsUnavailableReason", b.marginsUnavailableReason());
        // T063 (specs/006-deeper-history-both-sports, US5): always present,
        // even empty -- an absent key here would be the same ambiguity the
        // comment above already names for the four original lists, and this
        // endpoint has shipped once already without these three.
        m.put("pointsLeaders", b.pointsLeaders().stream().map(LeagueHistoryController::pointsLeaderRow).toList());
        m.put("winStreaks", b.winStreaks().stream().map(LeagueHistoryController::streakRow).toList());
        m.put("lossStreaks", b.lossStreaks().stream().map(LeagueHistoryController::streakRow).toList());
        return m;
    }

    private static Map<String, Object> pointsLeaderRow(LeagueRecordService.PointsLeaderRecord r) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("rosterId", r.rosterId());
        m.put("managerId", r.managerId());
        m.put("manager", r.manager());
        m.put("avatarId", r.avatarId());
        // BigDecimal, same reason weeklyScoreRow's points is -- the wire
        // carries the stored number, formatting is the client's call.
        m.put("points", r.points());
        m.put("spanSeasons", r.spanSeasons());
        return m;
    }

    private static Map<String, Object> streakRow(LeagueRecordService.StreakRecord r) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("rosterId", r.rosterId());
        m.put("managerId", r.managerId());
        m.put("manager", r.manager());
        m.put("avatarId", r.avatarId());
        m.put("length", r.length());
        m.put("spanSeasons", r.spanSeasons());
        m.put("startWeek", r.startWeek());
        m.put("endWeek", r.endWeek());
        // Carried per-entry, not once for the list, so the page can state the
        // rule beside the figure itself rather than leave it to the reader to
        // assume (research R7, US5.2).
        m.put("withinSeasonOnly", r.withinSeasonOnly());
        return m;
    }

    private static Map<String, Object> weeklyScoreRow(LeagueRecordService.WeeklyScoreRecord r) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("season", r.season());
        m.put("week", r.week());
        m.put("rosterId", r.rosterId());
        m.put("managerId", r.managerId());
        m.put("manager", r.manager());
        m.put("avatarId", r.avatarId());
        // BigDecimal, so Jackson writes 205.04 rather than a pre-formatted
        // string -- formatting is the client's call, not the wire's.
        m.put("points", r.points());
        return m;
    }

    private static Map<String, Object> marginRow(LeagueRecordService.MarginRecord r) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("season", r.season());
        m.put("week", r.week());
        m.put("margin", r.margin());
        m.put("winner", marginSide(r.winner()));
        m.put("loser", marginSide(r.loser()));
        return m;
    }

    private static Map<String, Object> marginSide(LeagueRecordService.Side s) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("rosterId", s.rosterId());
        m.put("managerId", s.managerId());
        m.put("manager", s.manager());
        m.put("avatarId", s.avatarId());
        m.put("points", s.points());
        return m;
    }

    /**
     * @param seasonComplete whether the season this row belongs to has
     *                       actually finished -- resolved by the CALLER, not
     *                       read off {@code r.complete()} directly, because
     *                       that field is null from history()'s per-league
     *                       call ({@code forLeague()} never joins
     *                       {@code league.status}; the caller already holds
     *                       this season's own {@code LeagueRow} and passes
     *                       its {@code complete()}). {@code managerHistory()}
     *                       has no per-row {@code LeagueRow} in scope, so it
     *                       passes {@code r.complete()} itself, populated by
     *                       {@code forManager()}'s join through the same
     *                       {@code LeagueRow.complete()} rule (T014).
     *                       specs/006-deeper-history-both-sports T026.
     */
    private static Map<String, Object> standingRow(RosterSeasonRepository.StandingRow r, boolean seasonComplete) {
        Map<String, Object> m = new LinkedHashMap<>();
        // T040 (specs/006-deeper-history-both-sports): contracts/manager-profile-api.md's
        // careers[].seasons[] shape names leagueId explicitly, alongside
        // sleeperLeagueId -- the internal id is what a rank/chain lookup
        // joins on, the Sleeper id is what a re-ingest or a deep link uses.
        // Added here rather than only for the careers[] path since
        // standingRow() is the one row-builder shared with history()'s league
        // standings too -- an extra field is harmless there.
        m.put("leagueId", r.leagueId());
        m.put("rosterId", r.rosterId());
        m.put("managerId", r.managerId());
        m.put("manager", r.managerName());
        m.put("avatarId", r.avatarId());
        m.put("wins", r.wins());
        m.put("losses", r.losses());
        m.put("ties", r.ties());
        m.put("pointsFor", r.pointsFor());
        m.put("pointsAgainst", r.pointsAgainst());
        // Gated on seasonComplete, not derived from finalPlacement alone, so a
        // final_placement=1 stored before the T023 status gate existed (or a
        // row whose league has not been re-ingested since V21) can never
        // resurface here as a trophy -- the exact defect baseline.md T003
        // measured: popsharky and gregmullen, both "champion" of a 2026
        // season one week old.
        m.put("champion", r.finalPlacement() != null && r.finalPlacement() == 1 && seasonComplete);
        // Null from history()'s per-league call (the page already knows all
        // five of these); populated from managerHistory(), which spans
        // several leagues/seasons/sports and has no other way to tell its
        // rows apart, link back to one, or say which sport it was
        // (specs/006-deeper-history-both-sports US1).
        m.put("season", r.season());
        m.put("sleeperLeagueId", r.sleeperLeagueId());
        m.put("sport", r.sport());
        m.put("leagueName", r.leagueName());
        m.put("complete", r.complete());
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
        record.put("avatarId", seasons.get(0).avatarId());
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

        // T040 (specs/006-deeper-history-both-sports, US3): careers[] is now
        // the ONLY season list in this response -- it shipped alongside the
        // flat seasons[] for one release, and T076 removed that field once
        // ManagerHistory.tsx had moved over. At no point does a caller see a
        // career total spanning two sports (careers[] is one entry per sport,
        // same rule draftHistory above already follows).
        record.put("careers", careers.forManager(managerId).stream()
                .map(LeagueHistoryController::careerRow)
                .toList());

        return ResponseEntity.ok(record);
    }

    private static Map<String, Object> careerRow(ManagerCareerService.CareerProfile c) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("sport", c.sport());
        m.put("seasonsCounted", c.seasonsCounted());
        m.put("seasons", c.seasons().stream().map(LeagueHistoryController::careerSeasonRow).toList());
        m.put("wins", c.wins());
        m.put("losses", c.losses());
        m.put("ties", c.ties());
        m.put("winRate", c.winRate());
        m.put("pointsFor", c.pointsFor());
        m.put("pointsAgainst", c.pointsAgainst());
        m.put("pointsPerSeason", c.pointsPerSeason());
        m.put("averageEfficiency", c.averageEfficiency());
        m.put("weeksCounted", c.weeksCounted());
        m.put("weeksExcluded", c.weeksExcluded());
        m.put("winsAboveExpected", c.winsAboveExpected());
        m.put("titles", c.titles());
        m.put("unavailable", c.unavailable().stream().map(LeagueHistoryController::unavailableRow).toList());
        m.put("ranks", c.ranks().stream().map(LeagueHistoryController::rankRow).toList());
        // T073/US6: waivers rides alongside unavailable/ranks on the same
        // per-sport career block, never a top-level total -- one entry per
        // sport, same as every other figure on this object.
        m.put("waivers", waiverTendencyRow(c.waivers()));
        return m;
    }

    private static Map<String, Object> waiverTendencyRow(TransactionAnalysisService.WaiverTendency w) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("movesPerSeason", w.movesPerSeason());
        m.put("seasonsCounted", w.seasonsCounted());
        m.put("faab", w.faab() == null ? null : faabTendencyRow(w.faab()));
        m.put("faabExcludedSeasons", w.faabExcludedSeasons().stream()
                .map(LeagueHistoryController::excludedSeasonRow).toList());
        return m;
    }

    private static Map<String, Object> faabTendencyRow(TransactionAnalysisService.FaabTendency f) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("typicalBidPct", f.typicalBidPct());
        m.put("largestBidPct", f.largestBidPct());
        m.put("spentPerSeasonPct", f.spentPerSeasonPct());
        m.put("claimsPerSeason", f.claimsPerSeason());
        m.put("bidSuccessRate", f.bidSuccessRate());
        return m;
    }

    private static Map<String, Object> excludedSeasonRow(TransactionAnalysisService.ExcludedSeason e) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("season", e.season());
        m.put("leagueName", e.leagueName());
        m.put("reason", e.reason());
        return m;
    }

    /**
     * A careers[].seasons[] row is standingRow()'s own shape PLUS `counted` --
     * the one field standingRow() cannot supply on its own, since counted is
     * a fact about roster_week_points, not roster_season (T035, computed once
     * in {@link ManagerCareerService}, not re-derived here).
     */
    private static Map<String, Object> careerSeasonRow(ManagerCareerService.SeasonEntry se) {
        Map<String, Object> row = standingRow(se.row(), Boolean.TRUE.equals(se.row().complete()));
        row.put("counted", se.counted());
        return row;
    }

    private static Map<String, Object> unavailableRow(ManagerCareerService.Unavailable u) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("figure", u.figure());
        m.put("reason", u.reason());
        return m;
    }

    private static Map<String, Object> rankRow(ManagerCareerService.Rank r) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("figure", r.figure());
        m.put("position", r.position());
        m.put("population", r.population());
        m.put("leagueName", r.leagueName());
        m.put("sleeperLeagueId", r.sleeperLeagueId());
        return m;
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
        Optional<LeagueRepository.LeagueRow> league = visibleLeague(sleeperId, sleeperUserId);
        if (league.isEmpty()) return ResponseEntity.notFound().build();
        LeagueRepository.LeagueRow row = league.get();

        PowerRankingService.SportState state = power.sportState(row.sport());
        var snapshots = power.snapshots(row.id());

        List<Map<String, Object>> entries = new ArrayList<>(
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

        Map<String, Object> sportState = new LinkedHashMap<>();
        sportState.put("week", state.week());
        sportState.put("season", state.season());
        sportState.put("seasonStartDate", state.seasonStartDate());
        sportState.put("started", state.started());

        Map<String, Object> response = new LinkedHashMap<>();
        response.put("sleeperLeagueId", sleeperId);
        response.put("sportState", sportState);
        response.put("entries", entries);
        // Null when this league has no odds at all -- the page then says nothing
        // about a simulation instead of describing one that never ran.
        playoffOdds.summary(row.id(), row.season()).ifPresentOrElse(
                s -> {
                    Map<String, Object> odds = new LinkedHashMap<>();
                    odds.put("week", s.week());
                    odds.put("iterations", s.iterations());
                    odds.put("model", s.model());
                    odds.put("weeksOfScoring", s.weeksOfScoring());
                    response.put("playoffOdds", odds);
                },
                () -> response.put("playoffOdds", null));
        return ResponseEntity.ok(response);
    }

    private static void attachPlayoffOdds(List<Map<String, Object>> entries,
                                          Map<Integer, Map<Integer, Double>> byWeek) {
        if (byWeek.isEmpty()) return;
        for (Map<String, Object> entry : entries) {
            Map<Integer, Double> week = byWeek.get((Integer) entry.get("week"));
            if (week == null) continue;
            Double pct = week.get((Integer) entry.get("rosterId"));
            if (pct != null) entry.put("makesPlayoffsPct", pct);
        }
    }

    private static Map<String, Object> snapshotRow(com.ballknowers.draftsim.store.PowerRankingRepository.SnapshotRow r) {
        return entryRow(r.season(), r.week(), r.kind(), r.rosterId(), r.managerId(), r.managerName(), r.avatarId(),
                r.rank(), r.score(), r.note(), null, null, null, null, null);
    }

    private static Map<String, Object> memberRow(int season, int week, MemberRankingService.Entry e,
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
    private static Map<String, Object> entryRow(int season, int week, String kind, int rosterId, Long managerId,
                                                 String manager, String avatarId, int rank, Double score, String note,
                                                 Integer bestRank, Integer worstRank, Double stdev,
                                                 Integer ballotCount, Integer selfRankBias) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("season", season);
        m.put("week", week);
        m.put("kind", kind);
        m.put("rosterId", rosterId);
        m.put("managerId", managerId);
        m.put("manager", manager);
        m.put("avatarId", avatarId);
        m.put("rank", rank);
        m.put("score", score);
        m.put("note", note);
        m.put("bestRank", bestRank);
        m.put("worstRank", worstRank);
        m.put("stdev", stdev);
        m.put("ballotCount", ballotCount);
        m.put("selfRankBias", selfRankBias);
        // Always present, null until a stored odds snapshot fills it in
        // (attachPlayoffOdds). Same discipline as bestRank/stdev above: the
        // client's type is one shape, not one shape per kind.
        m.put("makesPlayoffsPct", null);
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
     * {@code week} defaults to the current week for this league's own sport
     * -- ballots only ever exist for "now", never a past or future one (no
     * carry-forward, no backdating). "Now" is legitimately week 0 for a sport
     * in its offseason; see {@link #weekLabel}.
     */
    @GetMapping("/leagues/{sleeperId}/ballot")
    public ResponseEntity<?> ballot(@PathVariable String sleeperId,
                                    @RequestParam(required = false) Integer week,
                                    @RequestHeader(value = "X-Sleeper-User", required = false) String sleeperUserId) {
        Optional<LeagueRepository.LeagueRow> league = visibleLeague(sleeperId, sleeperUserId);
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
        row.put("avatarId", m.avatarId());
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
        if (visibleLeague(sleeperId, sleeperUserId).isEmpty()) return ResponseEntity.notFound().build();
        List<LeagueRepository.LeagueRow> chain = leagues.chainBySleeperId(sleeperId);
        if (chain.isEmpty()) return ResponseEntity.notFound().build();

        if (season != null && chain.stream().noneMatch(r -> r.season() == season)) {
            return ResponseEntity.badRequest().body(Map.of(
                    "error", "season " + season + " is not in this league's chain"));
        }

        List<PowerRankingService.BackfilledSeason> results = power.backfillFinalRanks(chain, season);

        List<Map<String, Object>> backfilled = new ArrayList<>();
        List<Map<String, Object>> skipped = new ArrayList<>();
        for (PowerRankingService.BackfilledSeason r : results) {
            if (r.reason() == null) {
                Map<String, Object> m = new LinkedHashMap<>();
                m.put("season", r.season());
                m.put("week", r.week());
                m.put("entries", r.entries());
                backfilled.add(m);
            } else {
                Map<String, Object> m = new LinkedHashMap<>();
                m.put("season", r.season());
                m.put("reason", r.reason());
                skipped.add(m);
            }
        }

        // An empty "backfilled" always arrives with a populated "skipped". A
        // zero that does not say why reads as a broken feature -- the same
        // argument /power/compute below already makes about its own counts.
        Map<String, Object> response = new LinkedHashMap<>();
        response.put("backfilled", backfilled);
        response.put("skipped", skipped);
        return ResponseEntity.ok(response);
    }

    @PostMapping("/leagues/{sleeperId}/power/compute")
    public ResponseEntity<?> compute(@PathVariable String sleeperId, @RequestParam int season,
                                     @RequestParam int week,
                                     @RequestHeader(value = "X-Sleeper-User", required = false) String sleeperUserId) {
        Optional<LeagueRepository.LeagueRow> league = visibleLeague(sleeperId, sleeperUserId);
        if (league.isEmpty()) return ResponseEntity.notFound().build();

        var week0 = power.computeWeek0IfMissing(league.get().id(), sleeperId, season);
        var realized = power.computeRealized(league.get().id(), season, week);
        // Same trigger as the box-score snapshot, deliberately: odds are never
        // computed on a page load (claude/playoff-odds.md).
        var odds = playoffOdds.compute(league.get().id(), season, week);

        // LinkedHashMap, not Map.of: the reason below is legitimately absent on
        // the happy path, and Map.of throws on a null value.
        Map<String, Object> response = new LinkedHashMap<>();
        response.put("week0", week0.entries().length);
        response.put("realized", realized.length);
        response.put("playoffOdds", odds.size());
        // A zero that does not say why reads as a broken feature. It is almost
        // always "this week has not been scored/ingested yet", which is a thing
        // the caller can act on -- so say so instead of leaving them to guess
        // whether the snapshot failed to persist. A zero at week 0 usually
        // means it was already set (write-once), which is not a gap -- but it
        // can also mean the league has not drafted, and that one IS reported.
        if (realized.length == 0) {
            response.put("realizedSkipped", power.realizedGap(league.get().id(), week));
        }
        // Week 0 has one gap worth reporting and only one: a league that has
        // not drafted yet. "Already set" is the ordinary case and carries no
        // reason, so this key is absent then rather than present and empty.
        if (week0.skipped() != null) {
            response.put("week0Skipped", week0.skipped());
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

        PowerRankingService.SportState state = power.sportState(row.sport());
        if (!body.week().equals(state.week())) {
            return ResponseEntity.badRequest().body(Map.of("message",
                    "the commissioner ranking can only be saved for " + weekLabel(state.week()) + " -- no backdating"));
        }

        var entries = power.saveCommissionerRanking(row.id(), sleeperId, body.season(), body.week(), body.rosterIds());
        return ResponseEntity.ok(Map.of("saved", entries.length));
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

    private static double round2(double d) {
        return Math.round(d * 100.0) / 100.0;
    }
}
