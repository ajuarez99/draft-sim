package com.ballknowers.draftsim.mock;

import com.ballknowers.draftsim.domain.BoardEntry;
import com.ballknowers.draftsim.domain.DraftSlot;
import com.ballknowers.draftsim.domain.Sport;
import com.ballknowers.draftsim.engine.DraftContext;
import com.ballknowers.draftsim.engine.DraftContextFactory;
import com.ballknowers.draftsim.engine.LeagueShape;
import com.ballknowers.draftsim.engine.MockDraftEngine;
import com.ballknowers.draftsim.engine.SeatSpec;
import com.ballknowers.draftsim.engine.SimulationResult;
import com.ballknowers.draftsim.ingest.BoardService;
import com.ballknowers.draftsim.profile.ProfileService;
import com.ballknowers.draftsim.store.JsonUtil;
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

    public MockDraftService(MockDraftRepository mockDrafts, DraftContextFactory contexts,
                            MockDraftEngine engine, BoardService boards, ProfileService profiles,
                            PlayerRepository players, ManagerRepository managers) {
        this.mockDrafts = mockDrafts;
        this.contexts = contexts;
        this.engine = engine;
        this.boards = boards;
        this.profiles = profiles;
        this.players = players;
        this.managers = managers;
    }

    @Transactional
    public MockSessionState createSession(int teams, int userSlot) {
        if (!LeagueShape.SUPPORTED_TEAM_COUNTS.contains(teams)) {
            throw new IllegalArgumentException(
                    "teams must be one of " + LeagueShape.SUPPORTED_TEAM_COUNTS.stream().sorted().toList()
                            + ", got " + teams);
        }
        if (userSlot < 1 || userSlot > teams) {
            throw new IllegalArgumentException("userSlot must be between 1 and " + teams + ", got " + userSlot);
        }

        LeagueShape shape = LeagueShape.standard(teams);
        List<SeatSpec> seats = List.of(SeatSpec.user(userSlot, null));

        // Validates board depth / seat shape the same way POST /api/sims does --
        // built here, before any row is written, so a bad request never leaves
        // an orphaned session behind.
        DraftContext ctx = buildContext(shape, seats, Map.of());

        long rngSeed = System.nanoTime();
        long id = mockDrafts.createSession(teams, shape.rounds(), shape.rosterPositions(),
                shape.pointsPerReception(), JsonUtil.write(seats), userSlot, rngSeed);

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
                isUsersTurn);
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
