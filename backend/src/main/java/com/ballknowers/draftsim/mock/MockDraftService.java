package com.ballknowers.draftsim.mock;

import com.ballknowers.draftsim.config.OwnerProperties;
import com.ballknowers.draftsim.domain.BoardEntry;
import com.ballknowers.draftsim.domain.DraftSlot;
import com.ballknowers.draftsim.domain.LeagueSettings;
import com.ballknowers.draftsim.domain.Sport;
import com.ballknowers.draftsim.engine.DraftContext;
import com.ballknowers.draftsim.engine.DraftContextFactory;
import com.ballknowers.draftsim.engine.LeagueShape;
import com.ballknowers.draftsim.engine.MockDraftEngine;
import com.ballknowers.draftsim.engine.OwnerSlot;
import com.ballknowers.draftsim.engine.SeatSpec;
import com.ballknowers.draftsim.engine.SimulationResult;
import com.ballknowers.draftsim.ingest.BoardService;
import com.ballknowers.draftsim.profile.ProfileService;
import com.ballknowers.draftsim.store.DraftRepository;
import com.ballknowers.draftsim.store.JsonUtil;
import com.ballknowers.draftsim.store.LeagueMembership;
import com.ballknowers.draftsim.store.LeagueRepository;
import com.ballknowers.draftsim.store.ManagerRepository;
import com.ballknowers.draftsim.store.MockDraftRepository;
import com.ballknowers.draftsim.store.PlayerRepository;
import com.fasterxml.jackson.core.type.TypeReference;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.*;

/**
 * The interactive mock draft room (claude/next-features-roadmap.md §4, Phase 3).
 *
 * Every mutating call ({@link #createSession} and {@link #submitPick}) is
 * transactional end-to-end: lock the session row, decide/persist whatever
 * follows, update {@code current_pick_no}, commit. There is deliberately no
 * separate "advance bots" endpoint and no SSE/polling -- unlike a live Sleeper
 * draft, nothing external moves a mock session between requests, so every
 * mutating call simply returns the fully-advanced state in one response.
 */
@Service
public class MockDraftService {

    private final MockDraftRepository mockDrafts;
    private final DraftContextFactory contexts;
    private final MockDraftEngine engine;
    private final BoardService boards;
    private final ProfileService profiles;
    private final PlayerRepository players;
    private final ManagerRepository managers;
    private final DraftRepository drafts;
    private final LeagueRepository leagues;
    private final OwnerProperties owner;
    private final LeagueMembership membership;

    public MockDraftService(MockDraftRepository mockDrafts, DraftContextFactory contexts,
                            MockDraftEngine engine, BoardService boards, ProfileService profiles,
                            PlayerRepository players, ManagerRepository managers,
                            DraftRepository drafts, LeagueRepository leagues, OwnerProperties owner,
                            LeagueMembership membership) {
        this.mockDrafts = mockDrafts;
        this.contexts = contexts;
        this.engine = engine;
        this.boards = boards;
        this.profiles = profiles;
        this.players = players;
        this.managers = managers;
        this.drafts = drafts;
        this.leagues = leagues;
        this.owner = owner;
        this.membership = membership;
    }

    /**
     * A football mock with no league behind it -- the plain {@code /mock/new}
     * default, and what every caller meant before the room learned basketball.
     *
     * @param sleeperUserId the {@code X-Sleeper-User} identity that owns the
     *                      resulting session (V8), or null when the caller
     *                      sent no header -- then the session is unowned and
     *                      visible to everyone, as all sessions were before.
     */
    public MockSessionState createSession(int teams, int userSlot, Map<Integer, Long> managerSeats,
                                          String sleeperUserId) {
        return createSession(Sport.NFL, teams, userSlot, managerSeats, sleeperUserId, null);
    }

    /**
     * @param sport         the sport this mock drafts in, always explicit --
     *                      never defaulted to football, which is the entire
     *                      point of the home screen's two-step modal
     *                      (design_handoff_multisport_mock_drafts). Must agree
     *                      with {@code sourceSleeperLeagueId}'s own sport when
     *                      one is given.
     * @param sourceSleeperLeagueId the real league whose settings the home
     *                      screen's "use settings from" step borrowed, or null
     *                      for a mock started with no league in mind.
     *                      <p>Replaces V12's {@code sourceLeagueName}, which was
     *                      a display string the caller passed alongside a team
     *                      count it had already read off the same league. That
     *                      was survivable while every mock was football and the
     *                      roster template was a constant; basketball needs the
     *                      roster, the round count and the reversal round off
     *                      that league too, and deriving five things from an id
     *                      beats trusting five fields to arrive consistent.
     *                      The stored column is unchanged -- the name is looked
     *                      up here and snapshotted, as before.
     */
    @Transactional
    public MockSessionState createSession(Sport sport, int teams, int userSlot, Map<Integer, Long> managerSeats,
                                          String sleeperUserId, String sourceSleeperLeagueId) {
        if (sport == null) throw new IllegalArgumentException("sport is required");
        if (!LeagueShape.SUPPORTED_TEAM_COUNTS.contains(teams)) {
            throw new IllegalArgumentException(
                    "teams must be one of " + LeagueShape.SUPPORTED_TEAM_COUNTS.stream().sorted().toList()
                            + ", got " + teams);
        }
        if (userSlot < 1 || userSlot > teams) {
            throw new IllegalArgumentException("userSlot must be between 1 and " + teams + ", got " + userSlot);
        }
        // Only checks that aren't already owned elsewhere: SeatSpec's own compact
        // constructor rejects slot < 1, and validate() (a few lines down, via
        // buildContext) rejects slot > teams and a slot claimed twice -- including
        // colliding with userSlot, since that seat is already in the same list.
        // Re-deriving either of those here risks drifting out of sync with the
        // one place that actually owns the rule.
        Map<Long, String> managerNames = managers.names();
        for (Map.Entry<Integer, Long> e : managerSeats.entrySet()) {
            int slot = e.getKey();
            Long managerId = e.getValue();
            // A primitive `long` unboxing NPE below would surface as an
            // unhandled 500 instead of this clean 400 -- validate before
            // SeatSpec.manager ever sees it.
            if (managerId == null) {
                throw new IllegalArgumentException("managerSeats slot " + slot + " needs a managerId, got null");
            }
            if (!managerNames.containsKey(managerId)) {
                throw new IllegalArgumentException("managerSeats slot " + slot + " has unknown managerId " + managerId);
            }
        }

        // "Use settings from <league>" means all of them, not just the team
        // count: the roster template, the round count and -- the one that
        // silently breaks a basketball mock otherwise -- the reversal round.
        LeagueShape shape = LeagueShape.standard(sport, teams);
        String sourceLeagueName = null;
        if (sourceSleeperLeagueId != null && !sourceSleeperLeagueId.isBlank()) {
            LeagueRepository.LeagueRow league = leagues.bySleeperId(sourceSleeperLeagueId)
                    .orElseThrow(() -> new IllegalArgumentException(
                            "league " + sourceSleeperLeagueId + " not ingested"));
            // Same answer as "not ingested" for a league that isn't the
            // caller's, matching createSessionFromDraft's own membership check.
            if (!membership.canSee(sleeperUserId, league.id())) {
                throw new IllegalArgumentException("league " + sourceSleeperLeagueId + " not ingested");
            }
            // The sport arrives explicitly AND is implied by the league. If they
            // disagree, something upstream picked one of them wrongly -- and a
            // silent winner here is exactly the "mock is always football" bug
            // this feature exists to kill, just with the other sport on top.
            if (league.sport() != sport) {
                throw new IllegalArgumentException("league " + sourceSleeperLeagueId + " is "
                        + league.sport().code() + ", but the requested sport is " + sport.code());
            }
            sourceLeagueName = league.name();
            // rounds and reversal_round live on the draft, not the league. A
            // league ingested without its draft keeps this sport's defaults
            // rather than failing -- the roster and the name are still worth
            // cloning on their own.
            int rounds = LeagueShape.standardRounds(sport);
            int reversalRound = 0;
            Optional<DraftRepository.DraftRow> sourceDraft = drafts.forLeague(league.id());
            if (sourceDraft.isPresent()) {
                rounds = sourceDraft.get().rounds();
                reversalRound = sourceDraft.get().reversalRound();
            }
            List<String> roster = league.rosterPositions().isEmpty()
                    ? LeagueShape.standardRoster(sport)
                    : league.rosterPositions();
            // A reversal past this mock's own round count can't fire and is
            // rejected by LeagueShape; clamp to plain snake rather than refusing
            // to start a mock over a setting the user never chose here.
            if (reversalRound > rounds) reversalRound = 0;
            shape = new LeagueShape(sport, teams, rounds, roster, league.ppr(), reversalRound);
        }

        List<SeatSpec> seats = new ArrayList<>();
        seats.add(SeatSpec.user(userSlot, null));
        managerSeats.forEach((slot, managerId) -> seats.add(SeatSpec.manager(slot, managerId)));

        // Validates board depth / seat shape the same way POST /api/sims does --
        // including every managerSeats slot's range and any collision with
        // userSlot -- built here, before any row is written, so a bad request
        // never leaves an orphaned session behind.
        DraftContext ctx = buildContext(shape, seats, Map.of());

        long rngSeed = System.nanoTime();
        long id = mockDrafts.createSession(shape.sport(), teams, shape.rounds(), shape.rosterPositions(),
                shape.pointsPerReception(), JsonUtil.write(seats), userSlot, rngSeed,
                null, null, sleeperUserId, sourceLeagueName, shape.reversalRound());

        advanceAndPersist(id, ctx, seats, rngSeed);
        return buildState(id, ctx);
    }

    /**
     * Forks a real, {@code drafting}-status Sleeper draft into a mock session
     * seeded with exactly what has actually happened so far -- the bridge
     * between live tracking and the mock room (claude/next-features-roadmap.md's
     * Phase 3/4 bridge). Bots continuing past the fork point use the same real
     * fitted manager profiles the live draft's own resim does, not neutral
     * ones, which is the entire point of forking rather than starting fresh.
     *
     * @param mySlotOverride explicit slot from the caller, or null to fall back
     *                       to {@link OwnerSlot#resolve} the same way the live
     *                       page's seats() call already does.
     * @param sleeperUserId  {@code X-Sleeper-User} identity, or null -- passed
     *                       through to {@link OwnerSlot#resolve} so a visiting
     *                       user's own seat is the fallback rather than always
     *                       the configured owner's.
     */
    @Transactional
    public MockSessionState createSessionFromDraft(String sleeperDraftId, Integer mySlotOverride, String sleeperUserId) {
        DraftRepository.DraftRow draft = drafts.bySleeperId(sleeperDraftId)
                .orElseThrow(() -> new IllegalArgumentException("draft " + sleeperDraftId + " not ingested"));

        // Same answer as "not ingested" for a draft in a league that isn't the
        // caller's: forking copies that draft's picks into a session this caller
        // then owns and reads, so it is a read of the whole board by another name.
        if (!membership.canSee(sleeperUserId, draft.leagueId())) {
            throw new IllegalArgumentException("draft " + sleeperDraftId + " not ingested");
        }

        if (!"drafting".equals(draft.status())) {
            throw new IllegalArgumentException("draft " + sleeperDraftId + " is "
                    + (draft.status() == null ? "not tracked" : draft.status())
                    + ", not drafting -- only a live, in-progress draft can be forked into a mock");
        }

        LeagueRepository.LeagueRow league = leagues.byId(draft.leagueId())
                .orElseThrow(() -> new IllegalStateException("league missing for draft " + sleeperDraftId));
        // The league's own totalRosters, not draft.teams(), is what
        // DraftContextFactory/the engine actually treat as the team count
        // (LeagueRepository.toSettings, mirroring SimulationService.simulate()) --
        // everything below (validation, session persistence, DraftSlot math) uses
        // settings.teams() so nothing can disagree with the DraftContext it's paired with.
        //
        // The real draft's own reversal round, not plain snake.
        //
        // This used to pass the 2-arg overload (reversalRound = 0) on purpose:
        // V14 had not happened, so MockDraftRepository's session row had nowhere
        // to keep the value, and honoring it for this fork's seed picks while
        // every later pick rebuilt a column-less plain-snake shape would have
        // made one session's round-3 order disagree with itself mid-draft.
        // Self-consistently wrong beat inconsistently right. The column exists
        // now (claude/nba-mock-drafts.md), submitPick reads it back, and so the
        // honest value is also the consistent one.
        //
        // draft.reversalRound() is already override-aware -- DraftRepository
        // .bySleeperId coalesces reversal_round_override over Sleeper's value --
        // so a user correction to the setting is what gets forked.
        LeagueSettings settings = LeagueRepository.toSettings(league, draft.rounds(), draft.reversalRound());

        if (!LeagueShape.SUPPORTED_TEAM_COUNTS.contains(settings.teams())) {
            throw new IllegalArgumentException("league has " + settings.teams() + " teams, but only "
                    + LeagueShape.SUPPORTED_TEAM_COUNTS.stream().sorted().toList()
                    + " can be forked into a mock");
        }

        Integer mySlot = mySlotOverride != null
                ? mySlotOverride
                : OwnerSlot.resolve(draft, managers, owner, sleeperUserId);
        if (mySlot == null) {
            throw new IllegalArgumentException(
                    "could not determine which seat is yours -- pass ?mySlot=<slot>");
        }
        if (mySlot < 1 || mySlot > settings.teams()) {
            throw new IllegalArgumentException(
                    "mySlot must be between 1 and " + settings.teams() + ", got " + mySlot);
        }

        List<SeatSpec> seats = SeatSpec.fromDraftOrder(draft.slotToManager(), mySlot);

        Map<Integer, Long> completed = new HashMap<>();
        for (DraftRepository.PickRow p : drafts.picks(draft.id())) {
            if (p.playerId() != null) completed.put(p.pickNo(), p.playerId());
        }

        List<BoardEntry> board = boards.currentBoard(settings.sport());
        ProfileService.Fit fit = profiles.fit(settings.sport());
        DraftContext ctx = contexts.build(settings, seats, fit.profiles(), fit.priors(), board, completed);

        long rngSeed = System.nanoTime();
        // The true first undecided pick, not max(completed)+1 -- those differ
        // whenever a lower pick number is still missing (an autopick Sleeper
        // hasn't attributed to a player yet: PickMapper/LiveDraftPoller can
        // write a null player_id, which the loop above already filters out of
        // `completed`). advanceAndPersist below will engine-decide that pick
        // regardless, so the banner this feeds must not claim it was real.
        int forkedAtPickNo = 1;
        while (forkedAtPickNo <= ctx.totalPicks() && completed.containsKey(forkedAtPickNo)) forkedAtPickNo++;
        long id = mockDrafts.createSession(settings.sport(), settings.teams(), settings.rounds(),
                settings.rosterPositions(), settings.pointsPerReception(), JsonUtil.write(seats), mySlot, rngSeed,
                draft.id(), forkedAtPickNo, sleeperUserId, league.name(), settings.reversalRound());

        List<MockDraftRepository.PickRow> seedRows = new ArrayList<>();
        for (Map.Entry<Integer, Long> e : completed.entrySet()) {
            int pickNo = e.getKey();
            // The forked draft's own reversal round, so this re-derivation
            // agrees with the real draft_pick.draft_slot Sleeper reported
            // (PickMapper reads that column directly rather than computing it,
            // so it is the one to trust if this ever disagreed).
            int slot = DraftSlot.slot(pickNo, settings.teams(), settings.reversalRound());
            int round = DraftSlot.round(pickNo, settings.teams());
            SeatSpec seat = seatAt(seats, slot);
            seedRows.add(new MockDraftRepository.PickRow(
                    id, pickNo, round, slot, seat.type().name(), seat.managerId(), e.getValue(), "LIVE"));
        }
        mockDrafts.insertPicks(id, seedRows);

        advanceAndPersist(id, ctx, seats, rngSeed);
        return buildState(id, ctx);
    }

    /**
     * This caller's mock sessions, newest first. Backs the picker screen's
     * "Mock drafts" list. A null {@code sleeperUserId} sees everything, as it
     * did before sessions had owners at all (V8).
     */
    public List<MockDraftRepository.SessionSummary> listSessions(String sleeperUserId) {
        return mockDrafts.allSessionsFor(sleeperUserId);
    }

    /**
     * Empty both when the session doesn't exist and when it belongs to someone
     * else -- the caller turns either into a 404. Deliberately not a 403: a
     * distinct "forbidden" would confirm that session id exists, and there is
     * nothing a visitor can do with that answer except learn how many mocks
     * other people are running.
     */
    public Optional<MockSessionState> get(long id, String sleeperUserId) {
        if (!mayUse(id, sleeperUserId)) return Optional.empty();
        return mockDrafts.find(id).isEmpty() ? Optional.empty() : Optional.of(buildState(id, null));
    }

    /**
     * Whether this caller may read or pick into this session.
     *
     * Three cases, and the middle one is the reason this exists: an owned
     * session is only its owner's, an unowned session (V8's closed set of
     * rows created before the column) is anyone's, and a caller with no
     * identity header at all keeps the pre-V8 behavior so nothing that never
     * signs in breaks.
     *
     * This is scoping, not security: {@code X-Sleeper-User} is an unverified
     * claim, so anyone who knows a Sleeper id can present it. It stops two
     * ordinary users from colliding -- which was a real, silent way to
     * overwrite someone's in-progress draft -- and does not stop an attacker.
     * Real auth is the answer to that; see DEPLOY.md's "Multiple people".
     */
    private boolean mayUse(long id, String sleeperUserId) {
        if (sleeperUserId == null || sleeperUserId.isBlank()) return true;
        Optional<Optional<String>> owner = mockDrafts.ownerOf(id);
        if (owner.isEmpty()) return true;               // no such session; let the caller 404 it normally
        return owner.get().map(sleeperUserId::equals).orElse(true);
    }

    @Transactional
    public Optional<MockSessionState> submitPick(long id, String sleeperPlayerId, String sleeperUserId) {
        if (!mayUse(id, sleeperUserId)) return Optional.empty();
        Optional<MockDraftRepository.SessionRow> locked = mockDrafts.lockForUpdate(id);
        if (locked.isEmpty()) return Optional.empty();
        MockDraftRepository.SessionRow row = locked.get();

        if (!"IN_PROGRESS".equals(row.status())) {
            throw new IllegalStateException("mock session " + id + " is already complete");
        }

        List<SeatSpec> seats = readSeats(row.seatsJson());
        // The session's own reversal round (V14), not plain snake. This is the
        // call that decides WHOSE turn pick N is, so a session that reverses in
        // round 3 and a DraftSlot call that doesn't would hand the pick to the
        // wrong seat -- and then reject the user's own pick as "not your turn"
        // for the rest of the draft.
        int onTheClockSlot = DraftSlot.slot(row.currentPickNo(), row.teams(), row.reversalRound());
        SeatSpec seat = seatAt(seats, onTheClockSlot);
        if (seat.type() != SeatSpec.Type.USER) {
            throw new IllegalStateException("pick " + row.currentPickNo() + " is not the user's turn");
        }
        if (sleeperPlayerId == null || sleeperPlayerId.isBlank()) {
            throw new IllegalArgumentException("sleeperPlayerId is required");
        }

        // The session's own sport. Sleeper player ids are unique per sport, not
        // globally, so looking a basketball pick up in the football index finds
        // nothing -- every NBA pick would 400 as "unknown sleeperPlayerId".
        Long playerId = players.idsBySleeperId(row.sport()).get(sleeperPlayerId);
        if (playerId == null) {
            throw new IllegalArgumentException("unknown sleeperPlayerId: " + sleeperPlayerId);
        }

        Map<Integer, Long> completed = new HashMap<>();
        for (MockDraftRepository.PickRow p : mockDrafts.picks(id)) {
            completed.put(p.pickNo(), p.playerId());
            if (p.playerId() == playerId) {
                throw new IllegalArgumentException("player already drafted in this session");
            }
        }

        int round = DraftSlot.round(row.currentPickNo(), row.teams());
        mockDrafts.insertPicks(id, List.of(new MockDraftRepository.PickRow(
                id, row.currentPickNo(), round, onTheClockSlot, "USER", seat.managerId(), playerId, "USER")));
        completed.put(row.currentPickNo(), playerId);

        LeagueShape shape = new LeagueShape(row.sport(), row.teams(), row.rounds(), row.rosterPositions(),
                row.pointsPerReception(), row.reversalRound());
        DraftContext ctx = buildContext(shape, seats, completed);
        advanceAndPersist(id, ctx, seats, row.rngSeed());
        return Optional.of(buildState(id, ctx));
    }

    /**
     * Runs {@link MockDraftEngine#advanceUntilUserOrEnd} against {@code ctx}
     * (already built from whatever is completed so far) and persists whatever
     * it decides -- shared by session creation (which may auto-advance bots
     * before the user's very first turn) and {@link #submitPick} (advancing
     * past the user's pick just recorded).
     */
    private void advanceAndPersist(long id, DraftContext ctx, List<SeatSpec> seats, long rngSeed) {
        MockDraftEngine.AdvanceResult adv = engine.advanceUntilUserOrEnd(ctx, seats, rngSeed);

        List<MockDraftRepository.PickRow> newRows = adv.newPicks().stream()
                .map(d -> new MockDraftRepository.PickRow(id, d.pickNo(), d.round(), d.slot(),
                        d.seatType().name(), d.managerId(), d.player().player().id(), "BOT"))
                .toList();
        mockDrafts.insertPicks(id, newRows);
        mockDrafts.advanceCurrentPick(id, adv.nextPickNo(), adv.complete() ? "COMPLETE" : "IN_PROGRESS");
    }

    /**
     * {@code shape} is a bare {@link LeagueShape} here (createSession's own
     * from-scratch session, or submitPick's re-derivation of one), never backed
     * by a real league row -- unlike {@link #createSessionFromDraft}, which has
     * one. The shape now carries its own sport, so both paths agree on which
     * board and which fitted profiles a session drafts against.
     */
    private DraftContext buildContext(LeagueShape shape, List<SeatSpec> seats, Map<Integer, Long> completed) {
        List<BoardEntry> board = boards.currentBoard(shape.sport());
        // Replaces the "NFL only" refusal this method's hardcoded sport used to
        // make redundant. An empty board is the one way a sport can be
        // structurally unable to hold a draft -- the session would start, and
        // then every pick would have nothing to choose from. Fail here, before
        // any row is written, rather than confusingly on the first pick.
        if (board.isEmpty()) {
            throw new IllegalArgumentException("no " + shape.sport().code()
                    + " board has been built yet -- ingest players and rebuild the board first");
        }
        ProfileService.Fit fit = profiles.fit(shape.sport());
        return contexts.build(shape, seats, fit.profiles(), fit.priors(), board, completed);
    }

    /**
     * @param ctx pass the {@link DraftContext} the caller already built for
     *            this same request (createSession/submitPick both have one on
     *            hand from {@link #advanceAndPersist}) so this doesn't call
     *            {@code boards.currentBoard()} a second/third time -- that's a
     *            full player-table scan plus a board load, not a cheap
     *            in-memory read. {@code null} for a bare {@link #get}, which
     *            has no ctx of its own to reuse.
     */
    private MockSessionState buildState(long id, DraftContext ctx) {
        MockDraftRepository.SessionRow row = mockDrafts.find(id)
                .orElseThrow(() -> new IllegalStateException("mock session " + id + " vanished mid-request"));
        List<SeatSpec> seats = readSeats(row.seatsJson());
        List<MockDraftRepository.PickRow> pickRows = mockDrafts.picks(id);

        // The session's own sport on the bare-GET path too. This is the fourth
        // and least obvious of the hardcoded-football sites: it only fires when
        // ctx is null (a plain GET /api/mocks/{id}, i.e. every page load of the
        // room), and its symptom is an NBA mock whose player picker lists
        // football players -- while create and pick, which pass a real ctx,
        // behave perfectly.
        List<BoardEntry> board = ctx != null ? ctx.board() : boards.currentBoard(row.sport());
        Map<Long, BoardEntry> byId;
        if (ctx != null) {
            byId = ctx.byId();
        } else {
            byId = new HashMap<>();
            for (BoardEntry e : board) byId.put(e.player().id(), e);
        }

        Map<Long, String> managerNames = managers.names();
        Map<Long, String> managerAvatars = managers.avatarIds();
        List<MockSessionState.SeatView> seatViews = new ArrayList<>();
        for (int slot = 1; slot <= row.teams(); slot++) {
            SeatSpec seat = seatAt(seats, slot);
            String name = switch (seat.type()) {
                case USER -> "You";
                case MANAGER -> managerNames.getOrDefault(seat.managerId(), "?");
                case BOT -> "Bot " + slot;
            };
            String avatarId = seat.type() == SeatSpec.Type.MANAGER ? managerAvatars.get(seat.managerId()) : null;
            seatViews.add(new MockSessionState.SeatView(slot, seat.type(), seat.managerId(), name, avatarId));
        }

        Set<Long> pickedIds = new HashSet<>();
        List<MockSessionState.PickView> pickViews = new ArrayList<>();
        for (MockDraftRepository.PickRow p : pickRows) {
            pickedIds.add(p.playerId());
            BoardEntry e = byId.get(p.playerId());
            pickViews.add(new MockSessionState.PickView(p.pickNo(), p.round(), p.draftSlot(),
                    SeatSpec.Type.valueOf(p.seatType()), p.source(),
                    e == null ? null : SimulationResult.PlayerRef.from(e)));
        }

        List<SimulationResult.PlayerRef> available = board.stream()
                .filter(e -> !pickedIds.contains(e.player().id()))
                .map(SimulationResult.PlayerRef::from)
                .toList();

        boolean complete = "COMPLETE".equals(row.status());
        // The session's own reversal round in both calls. These two feed the
        // room's "it's your turn" state and the "your picks" highlight on the
        // board, so a plain-snake answer here would contradict the seat
        // submitPick actually accepts a pick from.
        Integer onTheClockSlot = complete
                ? null : DraftSlot.slot(row.currentPickNo(), row.teams(), row.reversalRound());
        boolean isUsersTurn = !complete && onTheClockSlot != null && onTheClockSlot == row.userSlot();
        List<Integer> myPicks = Arrays.stream(
                        DraftSlot.picksForSlot(row.userSlot(), row.teams(), row.rounds(), row.reversalRound()))
                .boxed().toList();

        return new MockSessionState(row.id(), row.sport(), row.status(), row.teams(), row.rounds(),
                row.rosterPositions(), row.userSlot(), myPicks, seatViews, pickViews, available,
                row.currentPickNo(), onTheClockSlot, isUsersTurn, row.sourceDraftId(), row.forkedAtPickNo(),
                row.reversalRound());
    }

    /** A slot missing from `seats` is a BOT -- same convention DraftContextFactory.build() uses. */
    private static SeatSpec seatAt(List<SeatSpec> seats, int slot) {
        for (SeatSpec s : seats) if (s.slot() == slot) return s;
        return SeatSpec.bot(slot);
    }

    private static List<SeatSpec> readSeats(String seatsJson) {
        return JsonUtil.read(seatsJson, new TypeReference<>() {});
    }
}
