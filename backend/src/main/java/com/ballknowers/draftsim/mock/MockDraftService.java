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

    public MockDraftService(MockDraftRepository mockDrafts, DraftContextFactory contexts,
                            MockDraftEngine engine, BoardService boards, ProfileService profiles,
                            PlayerRepository players, ManagerRepository managers,
                            DraftRepository drafts, LeagueRepository leagues, OwnerProperties owner) {
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
    }

    @Transactional
    public MockSessionState createSession(int teams, int userSlot, Map<Integer, Long> managerSeats) {
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

        LeagueShape shape = LeagueShape.standard(teams);
        List<SeatSpec> seats = new ArrayList<>();
        seats.add(SeatSpec.user(userSlot, null));
        managerSeats.forEach((slot, managerId) -> seats.add(SeatSpec.manager(slot, managerId)));

        // Validates board depth / seat shape the same way POST /api/sims does --
        // including every managerSeats slot's range and any collision with
        // userSlot -- built here, before any row is written, so a bad request
        // never leaves an orphaned session behind.
        DraftContext ctx = buildContext(shape, seats, Map.of());

        long rngSeed = System.nanoTime();
        long id = mockDrafts.createSession(teams, shape.rounds(), shape.rosterPositions(),
                shape.pointsPerReception(), JsonUtil.write(seats), userSlot, rngSeed);

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
     */
    @Transactional
    public MockSessionState createSessionFromDraft(String sleeperDraftId, Integer mySlotOverride) {
        DraftRepository.DraftRow draft = drafts.bySleeperId(sleeperDraftId)
                .orElseThrow(() -> new IllegalArgumentException("draft " + sleeperDraftId + " not ingested"));

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
        LeagueSettings settings = LeagueRepository.toSettings(league, draft.rounds());

        if (!LeagueShape.SUPPORTED_TEAM_COUNTS.contains(settings.teams())) {
            throw new IllegalArgumentException("league has " + settings.teams() + " teams, but only "
                    + LeagueShape.SUPPORTED_TEAM_COUNTS.stream().sorted().toList()
                    + " can be forked into a mock");
        }

        Integer mySlot = mySlotOverride != null ? mySlotOverride : OwnerSlot.resolve(draft, managers, owner);
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

        List<BoardEntry> board = boards.currentBoard(Sport.NFL);
        ProfileService.Fit fit = profiles.fit(Sport.NFL);
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
        long id = mockDrafts.createSession(settings.teams(), settings.rounds(), settings.rosterPositions(),
                settings.pointsPerReception(), JsonUtil.write(seats), mySlot, rngSeed, draft.id(), forkedAtPickNo);

        List<MockDraftRepository.PickRow> seedRows = new ArrayList<>();
        for (Map.Entry<Integer, Long> e : completed.entrySet()) {
            int pickNo = e.getKey();
            int slot = DraftSlot.slot(pickNo, settings.teams());
            int round = DraftSlot.round(pickNo, settings.teams());
            SeatSpec seat = seatAt(seats, slot);
            seedRows.add(new MockDraftRepository.PickRow(
                    id, pickNo, round, slot, seat.type().name(), seat.managerId(), e.getValue(), "LIVE"));
        }
        mockDrafts.insertPicks(id, seedRows);

        advanceAndPersist(id, ctx, seats, rngSeed);
        return buildState(id, ctx);
    }

    /** Every mock session, newest first. Backs the picker screen's "Mock drafts" list. */
    public List<MockDraftRepository.SessionSummary> listSessions() {
        return mockDrafts.allSessions();
    }

    public Optional<MockSessionState> get(long id) {
        return mockDrafts.find(id).isEmpty() ? Optional.empty() : Optional.of(buildState(id, null));
    }

    @Transactional
    public Optional<MockSessionState> submitPick(long id, String sleeperPlayerId) {
        Optional<MockDraftRepository.SessionRow> locked = mockDrafts.lockForUpdate(id);
        if (locked.isEmpty()) return Optional.empty();
        MockDraftRepository.SessionRow row = locked.get();

        if (!"IN_PROGRESS".equals(row.status())) {
            throw new IllegalStateException("mock session " + id + " is already complete");
        }

        List<SeatSpec> seats = readSeats(row.seatsJson());
        int onTheClockSlot = DraftSlot.slot(row.currentPickNo(), row.teams());
        SeatSpec seat = seatAt(seats, onTheClockSlot);
        if (seat.type() != SeatSpec.Type.USER) {
            throw new IllegalStateException("pick " + row.currentPickNo() + " is not the user's turn");
        }
        if (sleeperPlayerId == null || sleeperPlayerId.isBlank()) {
            throw new IllegalArgumentException("sleeperPlayerId is required");
        }

        Long playerId = players.idsBySleeperId(Sport.NFL).get(sleeperPlayerId);
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

        LeagueShape shape = new LeagueShape(row.teams(), row.rounds(), row.rosterPositions(), row.pointsPerReception());
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

    private DraftContext buildContext(LeagueShape shape, List<SeatSpec> seats, Map<Integer, Long> completed) {
        List<BoardEntry> board = boards.currentBoard(Sport.NFL);
        ProfileService.Fit fit = profiles.fit(Sport.NFL);
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

        List<BoardEntry> board = ctx != null ? ctx.board() : boards.currentBoard(Sport.NFL);
        Map<Long, BoardEntry> byId;
        if (ctx != null) {
            byId = ctx.byId();
        } else {
            byId = new HashMap<>();
            for (BoardEntry e : board) byId.put(e.player().id(), e);
        }

        Map<Long, String> managerNames = managers.names();
        List<MockSessionState.SeatView> seatViews = new ArrayList<>();
        for (int slot = 1; slot <= row.teams(); slot++) {
            SeatSpec seat = seatAt(seats, slot);
            String name = switch (seat.type()) {
                case USER -> "You";
                case MANAGER -> managerNames.getOrDefault(seat.managerId(), "?");
                case BOT -> "Bot " + slot;
            };
            seatViews.add(new MockSessionState.SeatView(slot, seat.type(), seat.managerId(), name));
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
        Integer onTheClockSlot = complete ? null : DraftSlot.slot(row.currentPickNo(), row.teams());
        boolean isUsersTurn = !complete && onTheClockSlot != null && onTheClockSlot == row.userSlot();
        List<Integer> myPicks = Arrays.stream(DraftSlot.picksForSlot(row.userSlot(), row.teams(), row.rounds()))
                .boxed().toList();

        return new MockSessionState(row.id(), row.status(), row.teams(), row.rounds(), row.rosterPositions(),
                row.userSlot(), myPicks, seatViews, pickViews, available, row.currentPickNo(), onTheClockSlot,
                isUsersTurn, row.sourceDraftId(), row.forkedAtPickNo());
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
