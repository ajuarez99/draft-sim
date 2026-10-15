package com.ballknowers.draftsim.ingest.espn;

import com.ballknowers.draftsim.domain.Platform;
import com.ballknowers.draftsim.domain.Sport;
import com.ballknowers.draftsim.security.CredentialCipher;
import com.ballknowers.draftsim.store.DraftRepository;
import com.ballknowers.draftsim.store.JsonUtil;
import com.ballknowers.draftsim.store.LeagueCredentialRepository;
import com.ballknowers.draftsim.store.LeagueMatchupRepository;
import com.ballknowers.draftsim.store.LeagueMemberRepository;
import com.ballknowers.draftsim.store.LeagueMembership;
import com.ballknowers.draftsim.store.LeagueRefreshRepository;
import com.ballknowers.draftsim.store.LeagueRepository;
import com.ballknowers.draftsim.store.LeagueTransactionRepository;
import com.ballknowers.draftsim.store.LeagueWeekFetchRepository;
import com.ballknowers.draftsim.store.ManagerLinkRepository;
import com.ballknowers.draftsim.store.ManagerRepository;
import com.ballknowers.draftsim.store.PlatformPlayerLinkRepository;
import com.ballknowers.draftsim.store.RosterSeasonRepository;
import com.ballknowers.draftsim.store.RosterWeekPointsRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.ReentrantLock;

/**
 * Adds an ESPN league (spec 035 T028/T029): fetch every season, translate, resolve players, then write
 * everything the Sleeper ingest writes, in ONE database transaction.
 *
 * <p>Two phases (research R16):
 * <ol>
 *   <li><b>No writes.</b> Fetch and translate the current season, then walk {@code status.previousSeasons}
 *       newest first. A shape failure anywhere aborts with nothing written (FR-005); a season ESPN will
 *       not serve (404, 401, HTTP error) is recorded as missing and the walk continues.</li>
 *   <li><b>One transaction</b>, fixed order: league rows, managers, members, rosters, weeks, draft and
 *       picks, player links, manager link, credential (F15). Everything is an upsert, so a re-add
 *       changes nothing (FR-006).</li>
 * </ol>
 *
 * <p>Credential rule (F2): a STORED credential is used only when the caller can already see an existing
 * stored season of that league. Anyone else is read as public and gets {@code ESPN_PRIVATE_NO_ACCESS}.
 */
@Service
public class EspnLeagueIngestService {

    private static final Logger log = LoggerFactory.getLogger(EspnLeagueIngestService.class);

    /** Bound on how far back the history walk goes. */
    static final int MAX_HISTORY_SEASONS = 15;

    /** ESPN serves {@code seasons/{y}/segments/0/leagues/{id}} from this season on; earlier is leagueHistory (R11, unverified). */
    private static final int FIRST_MODERN_ESPN_SEASON = 2018;

    private static final Set<String> VIEWS = Set.of("mSettings", "mTeam", "mRoster", "mMatchup", "mDraftDetail");

    public record SeasonRow(int season, String leagueKey, String status) {}

    public record MissingSeason(int season, String reason) {}

    public record TeamChoice(int rosterId, String name, String managerName, boolean claimedByYou, boolean claimedByOther) {}

    /**
     * @param written          false when no claim was supplied: the team list comes back and nothing was written (F3)
     * @param credentialStored the only credential-related field ever returned
     */
    public record AddResult(String leagueKey, String platform, String sport, String name, List<SeasonRow> seasons,
                            List<MissingSeason> missingSeasons, List<TeamChoice> teams,
                            EspnPlayerResolver.Counts players, Map<String, String> unavailable,
                            boolean credentialStored, boolean written,
                            List<PlatformPlayerLinkRepository.UnmatchedPlayer> unmatched) {}

    private final EspnClient client;
    private final LeagueRepository leagues;
    private final ManagerRepository managers;
    private final LeagueMemberRepository leagueMembers;
    private final RosterSeasonRepository rosterSeasons;
    private final RosterWeekPointsRepository weekPoints;
    private final LeagueMatchupRepository matchups;
    private final LeagueWeekFetchRepository weekFetches;
    private final LeagueTransactionRepository transactions;
    private final DraftRepository drafts;
    private final ManagerLinkRepository links;
    private final LeagueCredentialRepository credentials;
    private final PlatformPlayerLinkRepository playerLinks;
    private final LeagueRefreshRepository refreshes;
    private final LeagueMembership membership;
    private final EspnPlayerResolver resolver;
    private final CredentialCipher cipher;
    private final Clock clock;
    private final TransactionTemplate tx;

    /** One add/refresh per ESPN league at a time (F15). Keyed league-wide, so add and refresh share it. */
    private final ConcurrentHashMap<String, ReentrantLock> locks = new ConcurrentHashMap<>();

    public EspnLeagueIngestService(EspnClient client, LeagueRepository leagues, ManagerRepository managers,
                                   LeagueMemberRepository leagueMembers, RosterSeasonRepository rosterSeasons,
                                   RosterWeekPointsRepository weekPoints, LeagueMatchupRepository matchups,
                                   LeagueWeekFetchRepository weekFetches, LeagueTransactionRepository transactions, DraftRepository drafts,
                                   ManagerLinkRepository links, LeagueCredentialRepository credentials,
                                   PlatformPlayerLinkRepository playerLinks, LeagueRefreshRepository refreshes,
                                   LeagueMembership membership, EspnPlayerResolver resolver, CredentialCipher cipher,
                                   Clock clock, PlatformTransactionManager txManager) {
        this.client = client;
        this.leagues = leagues;
        this.managers = managers;
        this.leagueMembers = leagueMembers;
        this.rosterSeasons = rosterSeasons;
        this.weekPoints = weekPoints;
        this.matchups = matchups;
        this.weekFetches = weekFetches;
        this.transactions = transactions;
        this.drafts = drafts;
        this.links = links;
        this.credentials = credentials;
        this.playerLinks = playerLinks;
        this.refreshes = refreshes;
        this.membership = membership;
        this.resolver = resolver;
        this.cipher = cipher;
        this.clock = clock;
        this.tx = new TransactionTemplate(txManager);
    }

    /**
     * What a transactions read produced for one season. {@code finalByWeek} holds only the weeks fetched this
     * time (true = the week is over, so it is never fetched again). {@code failure} is an ESPN error code,
     * never a message; once set, no later week or season is fetched in this run.
     */
    private record TxFetch(List<EspnSeason.Tx> txs, Map<Integer, Boolean> finalByWeek, String failure) {
        static TxFetch none() {
            return new TxFetch(List.of(), Map.of(), null);
        }
    }

    /** A translated season with the players resolved for it, and its roster moves. */
    private record Loaded(EspnSeason season, EspnPlayerResolver.Resolved players, TxFetch tx) {}

    /**
     * @param credential        cookies from the request, or null
     * @param claimRosterId     the team the caller says is theirs; null returns the team list and writes nothing
     * @param todayForDefault   resolves a link with no season
     */
    public AddResult add(EspnLeagueRef ref, EspnCredential credential, String callerSleeperUserId,
                         Integer claimRosterId) {
        if (credential != null && !cipher.isConfigured()) {
            // Before any ESPN call, so cookies are never sent anywhere we could not have stored them.
            throw new EspnAddFailure("CREDENTIALS_NOT_CONFIGURED", 503);
        }
        String lockKey = "espn-" + ref.game() + "-" + ref.leagueId();
        // Never `synchronized` around network calls: on Java 21 a virtual thread blocked on a monitor pins
        // its carrier (SeasonBoxCache says the same). A ReentrantLock parks the virtual thread instead.
        ReentrantLock lock = locks.computeIfAbsent(lockKey, k -> new ReentrantLock());
        lock.lock();
        try {
            return addLocked(ref, credential, callerSleeperUserId, claimRosterId);
        } finally {
            lock.unlock();
        }
    }

    /**
     * Refresh-on-visit for a stored ESPN league (spec 035 T046/T047): the CURRENT season only, with the stored
     * credential (the plan's F2 rule: a refresh of an existing league is what a stored credential is for).
     * Finished seasons are never re-read. Shares {@link #add}'s lock, so an add and a refresh of one league
     * never interleave (F15).
     *
     * <p>Never throws. A failure stores its CODE ({@code ESPN_ACCESS_DENIED}, {@code ESPN_SHAPE_CHANGED},
     * ...) and nothing else in {@code league_refresh.last_failure}: no message text, so no cookie or request
     * detail can reach the row. The stored data and {@code league_credential.last_worked_at} are untouched.
     *
     * @return true when the league and its moves were read and stored
     */
    public boolean refreshCurrent(LeagueRepository.LeagueRow league) {
        String lockKey = "espn-" + EspnLeagueRef.gameOf(league.sport()) + "-" + league.platformLeagueId();
        ReentrantLock lock = locks.computeIfAbsent(lockKey, k -> new ReentrantLock());
        lock.lock();
        try {
            return refreshLocked(league);
        } finally {
            lock.unlock();
        }
    }

    /** The fixed code stored for a refresh that failed for a reason that is not ESPN's. */
    public static final String REFRESH_ERROR = "ESPN_REFRESH_ERROR";

    private boolean refreshLocked(LeagueRepository.LeagueRow league) {
        Sport sport = league.sport();
        try {
            EspnLeagueRef ref = EspnLeagueRef.of(league.platformLeagueId(), sport, league.season());
            EspnCredential cred = cipher.isConfigured()
                    ? credentials.forIngest(sport.code(), ref.leagueId()).orElse(null) : null;
            Map<String, Object> payload = client.league(ref.game(), ref.leagueId(), ref.espnSeason(), VIEWS, cred);
            EspnSeason season = EspnTranslator.translate(payload, ref);
            String[] txFailure = new String[1];
            Loaded loaded = load(sport, season, fetchTransactions(ref, season, cred, txFailure));
            tx.executeWithoutResult(status -> {
                writeSeason(loaded, new LinkedHashMap<>(), false);
                if (txFailure[0] != null) refreshes.recordFailure(league.id(), clock.instant(), txFailure[0]);
                else if (cred != null) credentials.markWorked(sport.code(), ref.leagueId(), clock.instant());
            });
            return txFailure[0] == null;
        } catch (EspnException e) {
            refreshes.recordFailure(league.id(), clock.instant(), e.code());
            log.warn("espn refresh of {} failed ({})", league.sleeperId(), e.code());
            return false;
        } catch (RuntimeException e) {
            // The class name only: a driver or JSON message could carry request detail.
            refreshes.recordFailure(league.id(), clock.instant(), REFRESH_ERROR);
            log.warn("espn refresh of {} failed ({})", league.sleeperId(), e.getClass().getSimpleName());
            return false;
        }
    }

    /** What the contract says to default the season to when a link has none. */
    public int defaultSeason(Sport sport) {
        return EspnSeasons.currentAppSeason(sport, LocalDate.now(clock));
    }

    private AddResult addLocked(EspnLeagueRef ref, EspnCredential requestCredential, String caller, Integer claimRosterId) {
        Sport sport = ref.sport();
        List<LeagueRepository.LeagueRow> existing = leagues.espnSeasons(sport, ref.leagueId());

        // F2: which credential, if any, may be used for this read.
        EspnCredential cred = requestCredential;
        boolean usedStored = false;
        if (cred == null && !existing.isEmpty() && cipher.isConfigured()
                && existing.stream().anyMatch(l -> membership.canSee(caller, l.id()))) {
            cred = credentials.forIngest(sport.code(), ref.leagueId()).orElse(null);
            usedStored = cred != null;
        }

        // ---- Phase 1: no writes ------------------------------------------------------------------
        List<Loaded> loaded = new ArrayList<>();
        List<MissingSeason> missing = new ArrayList<>();
        Map<String, Object> currentPayload;
        try {
            currentPayload = client.league(ref.game(), ref.leagueId(), ref.espnSeason(), VIEWS, cred);
        } catch (EspnAccessDenied e) {
            throw new EspnAddFailure(cred != null ? "ESPN_CREDENTIAL_REJECTED" : "ESPN_PRIVATE_NO_ACCESS", 403);
        }
        EspnSeason current = EspnTranslator.translate(currentPayload, ref);
        String[] txFailure = new String[1];   // the first transactions error code of this run, if any
        loaded.add(load(sport, current, fetchTransactions(ref, current, cred, txFailure)));

        Set<Integer> previous = previousSeasons(currentPayload);
        int walked = 0;
        for (int espnSeason : previous.stream().sorted(java.util.Comparator.reverseOrder()).toList()) {
            if (walked++ >= MAX_HISTORY_SEASONS) break;
            int app = EspnSeasons.toApp(sport, espnSeason);
            if (app >= ref.season()) continue;
            EspnLeagueRef past = ref.withSeason(app);
            try {
                Map<String, Object> payload = espnSeason >= FIRST_MODERN_ESPN_SEASON
                        ? client.league(past.game(), past.leagueId(), espnSeason, VIEWS, cred)
                        : client.leagueHistory(past.game(), past.leagueId(), espnSeason, VIEWS, cred);
                EspnSeason pastSeason = EspnTranslator.translate(payload, past);
                loaded.add(load(sport, pastSeason, fetchTransactions(past, pastSeason, cred, txFailure)));
            } catch (EspnNotFound | EspnAccessDenied | EspnHttpError e) {
                missing.add(new MissingSeason(app, e.code()));
            }
            // EspnShapeChanged / EspnUnsupported propagate: nothing has been written yet (FR-005).
        }

        EspnSeason head = loaded.getFirst().season();
        EspnPlayerResolver.Resolved headPlayers = loaded.getFirst().players();
        Map<String, String> unavailable = unavailable(head);
        if (txFailure[0] != null) unavailable.put("transactions", txFailure[0]);
        List<SeasonRow> seasons = new ArrayList<>();
        for (Loaded l : loaded) seasons.add(new SeasonRow(l.season().appSeason(), l.season().key(), l.season().status()));

        if (claimRosterId == null) {
            return new AddResult(head.key(), "ESPN", sport.code(), head.name(), seasons, missing,
                    teamChoices(head, caller), headPlayers.counts(), unavailable,
                    // N5: whether a league has stored cookies is told only to someone who can already see it.
                    existing.stream().anyMatch(l -> membership.canSee(caller, l.id()))
                            && credentials.exists(sport.code(), ref.leagueId()),
                    false, unmatchedOf(headPlayers));
        }
        EspnSeason.Team claimed = head.teams().stream().filter(t -> t.id() == claimRosterId).findFirst()
                .orElseThrow(() -> new EspnAddFailure("TEAM_NOT_FOUND", 404));
        if (caller == null || caller.isBlank()) throw new EspnAddFailure("SIGN_IN_REQUIRED", 401);

        // ---- Phase 2: one transaction -------------------------------------------------------------
        EspnCredential toStore = requestCredential;
        final boolean markWorked = usedStored;
        boolean storedNow = Boolean.TRUE.equals(tx.execute(status -> {
            Map<Integer, Long> leagueIdBySeason = new LinkedHashMap<>();
            Map<String, Long> managerByMember = new LinkedHashMap<>();
            List<Loaded> oldestFirst = new ArrayList<>(loaded);
            java.util.Collections.reverse(oldestFirst);
            for (Loaded l : oldestFirst) {
                long leagueId = writeSeason(l, managerByMember, l.season().appSeason() < head.appSeason());
                leagueIdBySeason.put(l.season().appSeason(), leagueId);
            }
            long headLeagueId = leagueIdBySeason.get(head.appSeason());

            Long callerManager = managers.idsBySleeperUserId().get(caller);
            // N6: a neutral display name, never the raw header string.
            if (callerManager == null) callerManager = managers.upsert(caller, "Ball Knowers user", null);
            // S7: link to an owner of the chosen team that is not already someone else's; 409 only when every
            // owner (primary and co-owners) is linked to another person.
            List<Long> owners = new ArrayList<>();
            owners.add(managerByMember.get(claimed.ownerMemberId()));
            for (String co : claimed.coOwners()) owners.add(managerByMember.get(co));
            Long espnManager = links.claimable(callerManager, owners).orElseThrow(ManagerLinkRepository.AlreadyClaimed::new);
            links.unlinkInLeague(callerManager, headLeagueId);
            links.link(callerManager, espnManager, headLeagueId);

            // The moves could not be read: the league is stored, but the refresh state says so, by code only.
            if (txFailure[0] != null) refreshes.recordFailure(headLeagueId, clock.instant(), txFailure[0]);
            if (markWorked && txFailure[0] == null) credentials.markWorked(sport.code(), ref.leagueId(), clock.instant());
            boolean stored = false;
            if (toStore != null) stored = credentials.put(sport.code(), ref.leagueId(), callerManager, toStore);
            return stored || credentials.exists(sport.code(), ref.leagueId());
        }));
        log.info("espn add: league {} ({} season(s), {} missing)", head.key(), seasons.size(), missing.size());
        return new AddResult(head.key(), "ESPN", sport.code(), head.name(), seasons, missing,
                teamChoices(head, caller), headPlayers.counts(), unavailable, storedNow, true, unmatchedOf(headPlayers));
    }

    /** The refused players of one season's resolution, named, for the add result (FR-007). Capped so a bad run stays small. */
    private static List<PlatformPlayerLinkRepository.UnmatchedPlayer> unmatchedOf(EspnPlayerResolver.Resolved r) {
        List<PlatformPlayerLinkRepository.UnmatchedPlayer> out = new ArrayList<>();
        for (PlatformPlayerLinkRepository.Link l : r.toWrite()) {
            if (l.playerId() != null) continue;
            out.add(new PlatformPlayerLinkRepository.UnmatchedPlayer(l.externalName(), l.externalPosition(),
                    l.externalTeam(), l.outcome()));
            if (out.size() >= 200) break;
        }
        return out;
    }

    private Loaded load(Sport sport, EspnSeason season, TxFetch tx) {
        List<EspnSeason.Entry> candidates = new ArrayList<>();
        for (EspnSeason.Team t : season.teams()) candidates.addAll(t.entries());
        // Players on no roster (draft picks, and anyone a roster move names) get their name from the public catalogue.
        Set<String> known = new LinkedHashSet<>();
        for (EspnSeason.Entry e : candidates) known.add(e.playerId());
        Set<String> unnamed = new LinkedHashSet<>();
        for (EspnSeason.DraftPick p : season.draft().picks()) if (!known.contains(p.playerId())) unnamed.add(p.playerId());
        for (EspnSeason.Tx t : tx.txs()) {
            for (String pid : t.adds().keySet()) if (!known.contains(pid)) unnamed.add(pid);
            for (String pid : t.drops().keySet()) if (!known.contains(pid)) unnamed.add(pid);
        }
        if (!unnamed.isEmpty()) {
            Map<String, EspnSeason.Entry> catalogue = new LinkedHashMap<>();
            try {
                for (EspnSeason.Entry c : EspnTranslator.catalogue(sport,
                        client.players(EspnLeagueRef.gameOf(sport), EspnSeasons.toEspn(sport, season.appSeason())))) {
                    catalogue.put(c.playerId(), c);
                }
            } catch (EspnException e) {
                log.warn("espn catalogue unavailable ({}); unnamed players stay unmatched", e.code());
            }
            for (String pid : unnamed) {
                if (!known.add(pid)) continue;
                EspnSeason.Entry c = catalogue.get(pid);
                candidates.add(c != null ? c : new EspnSeason.Entry(pid, -1, "ESPN player " + pid, null, null));
            }
        }
        return new Loaded(season, resolver.resolve(sport, candidates), tx);
    }

    /**
     * One transactions read per matchup period not already stored as final (spec 035 T039, F12). A period
     * is final once it is over, so a refresh asks only about the current one. NFL: the week's scoring
     * period. NBA: the matchup period's LAST day (unverified: the right unit is unmeasured, T053).
     *
     * <p>The transactions shape is the least certain one in this client, so a failure here is isolated:
     * the first error code is recorded in {@code failure[0]}, the rest of the run skips the moves, and the
     * league still stores. Not final means it is asked again next time.
     */
    private TxFetch fetchTransactions(EspnLeagueRef ref, EspnSeason s, EspnCredential cred, String[] failure) {
        if (failure[0] != null) return new TxFetch(List.of(), Map.of(), failure[0]);
        if (!(s.complete() || "in_season".equals(s.status()))) return TxFetch.none();
        Set<Integer> done = leagues.bySleeperId(s.key())
                .map(l -> weekFetches.finalWeeks(l.id(), LeagueWeekFetchRepository.TRANSACTIONS)).orElse(Set.of());
        boolean faab = s.settings().get("waiver_type") instanceof Number n && n.intValue() == 2;
        List<EspnSeason.Tx> txs = new ArrayList<>();
        Map<Integer, Boolean> finalByWeek = new LinkedHashMap<>();
        for (EspnSeason.Period p : s.periods()) {
            if (!s.complete() && p.matchupPeriodId() > s.currentMatchupPeriod()) continue;
            if (done.contains(p.week())) continue;
            Integer scoringPeriod = s.txScoringPeriodByMatchupPeriod().get(p.matchupPeriodId());
            if (scoringPeriod == null) continue;
            try {
                Map<String, Object> payload = client.transactions(ref.game(), ref.leagueId(),
                        EspnSeasons.toEspn(s.sport(), s.appSeason()), scoringPeriod, cred);
                EspnSeason.TxResult r = EspnTranslator.transactions(payload, p.week(), faab);
                txs.addAll(r.transactions());
                if (!r.skippedTypes().isEmpty()) {
                    log.info("espn transactions: {} week {} skipped {} move(s) of type(s) {}", s.key(), p.week(), r.skipped(), r.skippedTypes());
                }
                finalByWeek.put(p.week(), s.complete() || p.matchupPeriodId() < s.currentMatchupPeriod());
            } catch (EspnException e) {
                failure[0] = e.code();
                log.warn("espn transactions unavailable for {} ({}); the league is stored without them", s.key(), e.code());
                break;
            }
        }
        return new TxFetch(txs, finalByWeek, failure[0]);
    }

    @SuppressWarnings("unchecked")
    private static Set<Integer> previousSeasons(Map<String, Object> payload) {
        Set<Integer> out = new LinkedHashSet<>();
        Object status = payload.get("status");
        if (status instanceof Map<?, ?> s && s.get("previousSeasons") instanceof List<?> l) {
            for (Object o : l) if (o instanceof Number n) out.add(n.intValue());
        }
        return out;
    }

    private Map<String, String> unavailable(EspnSeason season) {
        Map<String, String> m = new LinkedHashMap<>();
        m.put("draftRoom", "PLATFORM_NOT_SUPPORTED");   // the LeagueAvailability key (N3)
        if (!season.scoringUntranslated().isEmpty()) m.put("stats", "SCORING_UNTRANSLATED");
        return m;
    }

    private List<TeamChoice> teamChoices(EspnSeason season, String caller) {
        Map<String, Long> byEspn = managers.idsBySleeperUserId();
        Long me = caller == null || caller.isBlank() ? null : byEspn.get(caller);
        Map<String, String> displayById = new LinkedHashMap<>();
        for (EspnSeason.Member m : season.members()) displayById.put(m.espnId(), m.displayName());
        List<TeamChoice> out = new ArrayList<>();
        for (EspnSeason.Team t : season.teams()) {
            List<String> ownerIds = new ArrayList<>();
            ownerIds.add(t.ownerMemberId());
            ownerIds.addAll(t.coOwners());
            boolean byYou = false;
            boolean allHeld = true;
            for (String o : ownerIds) {
                Long espnManager = byEspn.get("espn:" + o);
                Optional<Long> holder = espnManager == null ? Optional.empty() : links.holderOf(espnManager);
                if (holder.isPresent() && holder.get().equals(me)) byYou = true;
                if (holder.isEmpty()) allHeld = false;
            }
            // Claimable by someone else only when EVERY owner is already linked to another person (S7).
            out.add(new TeamChoice(t.id(), t.teamName(), displayById.get(t.ownerMemberId()), byYou, !byYou && allHeld));
        }
        return out;
    }

    // ---- writes (inside the transaction) ----------------------------------------------------------

    /**
     * @param olderSeason a season older than the one being added or refreshed: read once and, when ESPN says
     *                    it is over, frozen. The CURRENT season freezes only on the read after the one that
     *                    first said it was over (spec: "complete and a refresh after that succeeds").
     */
    private long writeSeason(Loaded l, Map<String, Long> managerByMember, boolean olderSeason) {
        EspnSeason s = l.season();
        EspnPlayerResolver.Resolved rp = l.players();
        Instant now = clock.instant();
        String priorStatus = leagues.bySleeperId(s.key()).map(LeagueRepository.LeagueRow::status).orElse(null);

        long leagueId = leagues.upsert(s.sport(), s.appSeason(), s.key(), s.previousKey(), s.name(), s.totalRosters(),
                JsonUtil.write(s.settings()), JsonUtil.write(s.scoring()), s.rosterPositions(), s.status(),
                Platform.ESPN, s.platformLeagueId(), s.scoringUntranslated(), s.unmodelledSlots());

        for (EspnSeason.Member m : s.members()) {
            managerByMember.put(m.espnId(), managers.upsert("espn:" + m.espnId(), m.displayName(), null, Platform.ESPN));
        }
        Map<String, Boolean> commissioner = new LinkedHashMap<>();
        for (EspnSeason.Member m : s.members()) commissioner.put(m.espnId(), m.commissioner());
        for (EspnSeason.Team t : s.teams()) {
            Long owner = requireManager(managerByMember, t.ownerMemberId());
            leagueMembers.upsert(leagueId, owner, commissioner.getOrDefault(t.ownerMemberId(), false), t.teamName());
            for (String co : t.coOwners()) {
                leagueMembers.upsert(leagueId, requireManager(managerByMember, co),
                        commissioner.getOrDefault(co, false), t.teamName());
            }
        }

        int ir = EspnTranslator.irSlot(s.sport());
        List<RosterSeasonRepository.Upsert> rosters = new ArrayList<>();
        OffsetDateTime fetched = OffsetDateTime.ofInstant(now, ZoneOffset.UTC);
        for (EspnSeason.Team t : s.teams()) {
            List<String> players = new ArrayList<>();
            List<String> reserve = new ArrayList<>();
            for (EspnSeason.Entry e : t.entries()) {
                String key = rp.keyByExternalId().get(e.playerId());
                (e.lineupSlotId() == ir ? reserve : players).add(key);
            }
            rosters.add(new RosterSeasonRepository.Upsert(leagueId, requireManager(managerByMember, t.ownerMemberId()),
                    t.id(), t.wins(), t.losses(), t.ties(), t.pointsFor(), t.pointsAgainst(),
                    null /* points_possible: ESPN has no equivalent (F7) */, t.finalPlacement(), players, fetched, reserve));
        }
        rosterSeasons.upsertAll(rosters);

        for (EspnSeason.Period p : s.periods()) {
            boolean allDecided = true;
            for (EspnSeason.Matchup m : p.matchups()) {
                matchups.upsert(new LeagueMatchupRepository.Row(leagueId, s.appSeason(), p.week(), m.home().teamId(),
                        m.away() == null ? null : m.id()));
                if (m.away() != null) {
                    matchups.upsert(new LeagueMatchupRepository.Row(leagueId, s.appSeason(), p.week(), m.away().teamId(), m.id()));
                }
                if (m.decided()) {
                    // players_points / starters need per-player data the R9 shape does not carry:
                    // unverified: confirm against real league (T053).
                    weekPoints.upsert(new RosterWeekPointsRepository.Row(leagueId, s.appSeason(), p.week(),
                            m.home().teamId(), m.home().points(), "{}"));
                    if (m.away() != null) {
                        weekPoints.upsert(new RosterWeekPointsRepository.Row(leagueId, s.appSeason(), p.week(),
                                m.away().teamId(), m.away().points(), "{}"));
                    }
                } else {
                    allDecided = false;
                }
            }
            // Points are written only for decided pairings; the week is final only when all of them are.
            if (allDecided && !p.matchups().isEmpty()) {
                weekFetches.record(leagueId, LeagueWeekFetchRepository.POINTS, p.week(), now, true);
            }
        }

        writeDraft(leagueId, s, rp, managerByMember);
        for (var link : rp.toWrite()) playerLinks.upsert(link, now);
        writeTransactions(leagueId, l, managerByMember, now);
        boolean priorComplete = priorStatus != null && LeagueRepository.LeagueRow.isComplete(priorStatus);
        refreshes.recordSuccess(leagueId, now, s.complete() && l.tx().failure() == null && (olderSeason || priorComplete));
        return leagueId;
    }

    private void writeTransactions(long leagueId, Loaded l, Map<String, Long> managerByMember, Instant now) {
        EspnSeason s = l.season();
        Map<Integer, Long> managerByTeam = new LinkedHashMap<>();
        for (EspnSeason.Team t : s.teams()) managerByTeam.put(t.id(), requireManager(managerByMember, t.ownerMemberId()));
        for (EspnSeason.Tx t : l.tx().txs()) {
            transactions.upsert(new LeagueTransactionRepository.Row(leagueId, s.appSeason(), t.week(),
                    "espn:" + t.externalId(), t.type(), t.status(), t.rosterId(),
                    t.rosterId() == null ? null : managerByTeam.get(t.rosterId()),
                    JsonUtil.write(keyed(t.adds(), l.players())), JsonUtil.write(keyed(t.drops(), l.players())),
                    t.faabBid(), t.createdAt(),
                    // V34 trade assets (spec 033): null = unknown, never [] = "none moved". ESPN's traded
                    // picks/FAAB aren't mapped, so the trade page says assets aren't loaded rather than
                    // asserting a player-only trade. Only the Sleeper ingest backfills these (guarded).
                    null, null));
        }
        l.tx().finalByWeek().forEach((week, fin) ->
                weekFetches.record(leagueId, LeagueWeekFetchRepository.TRANSACTIONS, week, now, fin));
    }

    /** ESPN player id to stored key (Sleeper id, or {@code espn:<id>}), keeping the team id as the value. */
    private static Map<String, Object> keyed(Map<String, Integer> byEspnPlayer, EspnPlayerResolver.Resolved rp) {
        Map<String, Object> out = new LinkedHashMap<>();
        byEspnPlayer.forEach((pid, team) -> out.put(rp.keyByExternalId().getOrDefault(pid, "espn:" + pid), team));
        return out;
    }

    private void writeDraft(long leagueId, EspnSeason s, EspnPlayerResolver.Resolved rp, Map<String, Long> managerByMember) {
        EspnSeason.Draft d = s.draft();
        Map<Integer, Long> managerByTeam = new LinkedHashMap<>();
        for (EspnSeason.Team t : s.teams()) managerByTeam.put(t.id(), requireManager(managerByMember, t.ownerMemberId()));
        Map<String, Object> slotToManager = new LinkedHashMap<>();
        Map<Integer, Integer> slotOfTeam = new LinkedHashMap<>();
        for (int i = 0; i < d.pickOrder().size(); i++) {
            int team = d.pickOrder().get(i);
            slotOfTeam.put(team, i + 1);
            Long m = managerByTeam.get(team);
            if (m != null) slotToManager.put(String.valueOf(i + 1), m);
        }
        if (!"linear".equals(d.type()) && !List.of("snake", "auction").contains(d.type())) {
            log.info("espn draft type stored as linear");
        }
        long draftId = drafts.upsertEspn(leagueId, s.key() + "-draft", s.appSeason(), d.rounds(), d.teams(), d.type(),
                d.status(), JsonUtil.write(slotToManager));
        List<DraftRepository.PickRow> rows = new ArrayList<>();
        for (EspnSeason.DraftPick p : d.picks()) {
            int slot = slotOfTeam.getOrDefault(p.teamId(), ((p.overall() - 1) % Math.max(1, d.teams())) + 1);
            rows.add(new DraftRepository.PickRow(draftId, p.overall(), p.round(), slot, managerByTeam.get(p.teamId()),
                    rp.playerIdByExternalId().get(p.playerId()), null));
        }
        drafts.replacePicks(draftId, rows);
    }

    private static Long requireManager(Map<String, Long> managerByMember, String memberId) {
        Long id = managerByMember.get(memberId);
        if (id == null) throw new EspnShapeChanged("teams[].owners");   // an owner who is not in members[]
        return id;
    }
}
