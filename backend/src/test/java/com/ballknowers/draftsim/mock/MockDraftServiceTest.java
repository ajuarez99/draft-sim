package com.ballknowers.draftsim.mock;

import com.ballknowers.draftsim.config.ScoringProperties;
import com.ballknowers.draftsim.domain.*;
import com.ballknowers.draftsim.engine.DraftContextFactory;
import com.ballknowers.draftsim.engine.MockDraftEngine;
import com.ballknowers.draftsim.ingest.BoardService;
import com.ballknowers.draftsim.profile.PositionalPriors;
import com.ballknowers.draftsim.profile.ProfileService;
import com.ballknowers.draftsim.sport.FootballRules;
import com.ballknowers.draftsim.store.ManagerRepository;
import com.ballknowers.draftsim.store.MockDraftRepository;
import com.ballknowers.draftsim.store.PlayerRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.util.*;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.lenient;

/**
 * MockDraftService is the interactive mock draft room's own orchestration
 * (claude/next-features-roadmap.md §4, Phase 3). BoardService/ProfileService/
 * PlayerRepository/ManagerRepository are mocked the same way SimulationServiceTest
 * mocks them; MockDraftRepository is faked in-memory (below) rather than mocked
 * call-by-call, since the service's own correctness here is about a stateful
 * sequence of reads/writes across several calls, not any one of them in isolation.
 */
@ExtendWith(MockitoExtension.class)
class MockDraftServiceTest {

    private static final ScoringProperties.Sport CFG = new ScoringProperties.Sport(
            new ScoringProperties.Weights(1.0, 0.35, 0.5, 0.25),
            12.0, 3.0, 60.0, 0.15, 6, 0.85,
            Map.of("K", 3, "DEF", 4), 1.0, 30);

    @Mock private BoardService boards;
    @Mock private ProfileService profiles;
    @Mock private PlayerRepository players;
    @Mock private ManagerRepository managers;

    private FakeMockDraftRepository repo;
    private MockDraftService service;

    private static List<BoardEntry> board(int n) {
        Position[] cycle = {Position.RB, Position.WR, Position.WR, Position.RB, Position.TE, Position.QB};
        List<BoardEntry> out = new ArrayList<>(n);
        for (int i = 0; i < n; i++) {
            out.add(new BoardEntry(new Player(i + 1L, Sport.NFL, "s" + i, "Player " + i,
                    List.of(cycle[i % cycle.length]), "FA", "Active", null, null, null), i + 1.0, i / 6 + 1));
        }
        return out;
    }

    @BeforeEach
    void setUp() {
        List<BoardEntry> synthetic = board(400);
        lenient().when(boards.currentBoard(Sport.NFL)).thenReturn(synthetic);
        lenient().when(profiles.fit(Sport.NFL))
                .thenReturn(new ProfileService.Fit(Map.of(), PositionalPriors.uniform(), 0));
        Map<String, Long> ids = new HashMap<>();
        for (BoardEntry e : synthetic) ids.put(e.player().sleeperId(), e.player().id());
        lenient().when(players.idsBySleeperId(Sport.NFL)).thenReturn(ids);
        lenient().when(managers.names()).thenReturn(Map.of());

        repo = new FakeMockDraftRepository();
        DraftContextFactory contexts =
                new DraftContextFactory(new FootballRules(new ScoringProperties(CFG)), new ScoringProperties(CFG));
        service = new MockDraftService(repo, contexts, new MockDraftEngine(), boards, profiles, players, managers);
    }

    @Test
    void createSessionAutoAdvancesBotsBeforeTheUsersFirstTurn() {
        MockSessionState state = service.createSession(8, 5);

        assertEquals("IN_PROGRESS", state.status());
        assertEquals(4, state.picks().size(), "slots 1-4 must be auto-advanced before slot 5's turn");
        assertEquals(5, state.currentPickNo());
        assertEquals(5, state.onTheClockSlot());
        assertTrue(state.isUsersTurn());
        for (var p : state.picks()) assertEquals("BOT", p.source());
    }

    @Test
    void createSessionRejectsAnUnsupportedTeamSize() {
        assertThrows(IllegalArgumentException.class, () -> service.createSession(9, 1));
    }

    @Test
    void createSessionRejectsAnOutOfRangeUserSlot() {
        assertThrows(IllegalArgumentException.class, () -> service.createSession(8, 0));
        assertThrows(IllegalArgumentException.class, () -> service.createSession(8, 9));
    }

    @Test
    void submitPickRecordsTheUsersChoiceAndAdvancesToTheNextUserTurn() {
        MockSessionState created = service.createSession(8, 1);   // user picks first, no bots ahead
        assertTrue(created.isUsersTurn());
        String firstAvailable = created.available().get(0).sleeperId();

        MockSessionState after = service.submitPick(created.id(), firstAvailable).orElseThrow();

        // Pick 1 (the user's) plus bots for slots 2-8 must all be in by the time
        // it's the user's turn again (pick 16 of a snake 8-team draft: round 2
        // reverses, so slot 1 is last in round 2, not first again until pick 17).
        assertTrue(after.picks().stream().anyMatch(p -> p.pickNo() == 1 && "USER".equals(p.source())));
        assertEquals(firstAvailable, after.picks().stream()
                .filter(p -> p.pickNo() == 1).findFirst().orElseThrow().player().sleeperId());
        assertTrue(after.currentPickNo() > 1, "bots after the user's pick must have been advanced");
    }

    /**
     * By design, advanceUntilUserOrEnd always stops exactly at the single USER
     * seat's own next turn (or completion) -- so with a well-formed session
     * there is no legitimate sequence of calls that reaches submitPick with
     * current_pick_no pointing at a non-USER seat. The check exists as a
     * defensive guard against that invariant ever being violated (a future
     * bug, a corrupted row), so this test exercises it by corrupting the fake
     * repository's state directly rather than through the service's own API --
     * the only way to reach the branch at all.
     */
    @Test
    void submitPickRejectsWhenCurrentPickNoIsNotActuallyTheUsersTurn() {
        MockSessionState created = service.createSession(8, 5);   // slots 1-4 are bots first
        repo.advanceCurrentPick(created.id(), 6, "IN_PROGRESS");  // force onto slot 6, a bot seat

        String someone = created.available().get(0).sleeperId();
        assertThrows(IllegalStateException.class, () -> service.submitPick(created.id(), someone));
    }

    @Test
    void submitPickRejectsAnUnknownPlayer() {
        MockSessionState created = service.createSession(8, 1);
        assertThrows(IllegalArgumentException.class,
                () -> service.submitPick(created.id(), "no-such-sleeper-id"));
    }

    @Test
    void submitPickRejectsAPlayerAlreadyDraftedInThisSession() {
        MockSessionState created = service.createSession(8, 1);
        String player = created.available().get(0).sleeperId();
        MockSessionState afterFirst = service.submitPick(created.id(), player).orElseThrow();

        // Slot 1 (an 8-team snake) comes back around at pick 16 -- still the
        // same lone USER seat, so it is genuinely the user's turn again.
        assertTrue(afterFirst.isUsersTurn(), "slot 1's next pick comes back around to the user");
        assertThrows(IllegalArgumentException.class, () -> service.submitPick(created.id(), player));
    }

    @Test
    void submitPickReturnsEmptyForAnUnknownSession() {
        assertTrue(service.submitPick(999L, "whatever").isEmpty());
    }

    @Test
    void getReturnsEmptyForAnUnknownSession() {
        assertTrue(service.get(999L).isEmpty());
    }

    @Test
    void aFullMockDraftCompletesWithNoDuplicatePlayersAcrossRepeatedSubmitPickCalls() {
        MockSessionState state = service.createSession(8, 3);
        Set<String> drafted = new HashSet<>();
        for (var p : state.picks()) assertTrue(drafted.add(p.player().sleeperId()));

        int iterations = 0;
        while (!"COMPLETE".equals(state.status())) {
            assertTrue(state.isUsersTurn(), "the loop only ever feeds a pick when it's genuinely the user's turn");
            int before = drafted.size();
            String pick = state.available().get(0).sleeperId();
            state = service.submitPick(state.id(), pick).orElseThrow();

            drafted.clear();
            for (var p : state.picks()) {
                assertTrue(drafted.add(p.player().sleeperId()), "player " + p.player().sleeperId() + " drafted twice");
            }
            assertTrue(drafted.size() > before, "each call must add at least the user's own pick");
            assertTrue(++iterations < 20, "loop did not terminate within a plausible number of user turns");
        }

        assertEquals(8 * 15, state.picks().size());
        assertEquals(8 * 15, drafted.size());
    }

    /** In-memory stand-in for the real JdbcClient-backed repository. */
    private static final class FakeMockDraftRepository extends MockDraftRepository {
        private final AtomicLong nextId = new AtomicLong(1);
        private final Map<Long, SessionRow> sessions = new HashMap<>();
        private final Map<Long, List<PickRow>> picksBySession = new HashMap<>();

        FakeMockDraftRepository() {
            super(null, null);
        }

        @Override
        public long createSession(int teams, int rounds, List<String> rosterPositions, double ppr,
                                  String seatsJson, int userSlot, long rngSeed) {
            long id = nextId.getAndIncrement();
            sessions.put(id, new SessionRow(id, "IN_PROGRESS", teams, rounds, rosterPositions, ppr,
                    seatsJson, userSlot, rngSeed, 1));
            picksBySession.put(id, new ArrayList<>());
            return id;
        }

        @Override
        public Optional<SessionRow> find(long id) {
            return Optional.ofNullable(sessions.get(id));
        }

        @Override
        public Optional<SessionRow> lockForUpdate(long id) {
            return find(id);
        }

        @Override
        public void advanceCurrentPick(long id, int currentPickNo, String status) {
            SessionRow r = sessions.get(id);
            sessions.put(id, new SessionRow(r.id(), status, r.teams(), r.rounds(), r.rosterPositions(),
                    r.pointsPerReception(), r.seatsJson(), r.userSlot(), r.rngSeed(), currentPickNo));
        }

        @Override
        public void insertPicks(long sessionId, List<PickRow> picks) {
            picksBySession.computeIfAbsent(sessionId, k -> new ArrayList<>()).addAll(picks);
        }

        @Override
        public List<PickRow> picks(long sessionId) {
            return List.copyOf(picksBySession.getOrDefault(sessionId, List.of()));
        }

        @Override
        public List<SessionSummary> allSessions() {
            return sessions.values().stream()
                    .map(r -> new SessionSummary(r.id(), r.status(), r.teams(), r.rounds(), r.userSlot(),
                            r.currentPickNo(), Instant.now()))
                    .toList();
        }
    }
}
