package com.ballknowers.draftsim.mock;

import com.ballknowers.draftsim.config.OwnerProperties;
import com.ballknowers.draftsim.config.ScoringProperties;
import com.ballknowers.draftsim.domain.*;
import com.ballknowers.draftsim.engine.DraftContextFactory;
import com.ballknowers.draftsim.engine.LeagueShape;
import com.ballknowers.draftsim.engine.MockDraftEngine;
import com.ballknowers.draftsim.engine.SeatSpec;
import com.ballknowers.draftsim.ingest.BoardService;
import com.ballknowers.draftsim.profile.PositionalPriors;
import com.ballknowers.draftsim.profile.ProfileService;
import com.ballknowers.draftsim.sport.FootballRules;
import com.ballknowers.draftsim.store.DraftRepository;
import com.ballknowers.draftsim.store.LeagueRepository;
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
import static org.mockito.Mockito.when;

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
    @Mock private DraftRepository drafts;
    @Mock private LeagueRepository leagues;

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
                .thenReturn(new ProfileService.Fit(Map.of(), PositionalPriors.uniform(), 0, Map.of()));
        Map<String, Long> ids = new HashMap<>();
        for (BoardEntry e : synthetic) ids.put(e.player().sleeperId(), e.player().id());
        lenient().when(players.idsBySleeperId(Sport.NFL)).thenReturn(ids);
        lenient().when(managers.names()).thenReturn(Map.of());

        repo = new FakeMockDraftRepository();
        DraftContextFactory contexts =
                new DraftContextFactory(new FootballRules(new ScoringProperties(CFG)), new ScoringProperties(CFG));
        service = new MockDraftService(repo, contexts, new MockDraftEngine(), boards, profiles, players, managers,
                drafts, leagues, new OwnerProperties(null));
    }

    @Test
    void createSessionAutoAdvancesBotsBeforeTheUsersFirstTurn() {
        MockSessionState state = service.createSession(8, 5, Map.of());

        assertEquals("IN_PROGRESS", state.status());
        assertEquals(4, state.picks().size(), "slots 1-4 must be auto-advanced before slot 5's turn");
        assertEquals(5, state.currentPickNo());
        assertEquals(5, state.onTheClockSlot());
        assertTrue(state.isUsersTurn());
        for (var p : state.picks()) assertEquals("BOT", p.source());
    }

    @Test
    void createSessionRejectsAnUnsupportedTeamSize() {
        assertThrows(IllegalArgumentException.class, () -> service.createSession(9, 1, Map.of()));
    }

    @Test
    void createSessionRejectsAnOutOfRangeUserSlot() {
        assertThrows(IllegalArgumentException.class, () -> service.createSession(8, 0, Map.of()));
        assertThrows(IllegalArgumentException.class, () -> service.createSession(8, 9, Map.of()));
    }

    @Test
    void createSessionRejectsAManagerSeatAtTheUsersOwnSlot() {
        assertThrows(IllegalArgumentException.class, () -> service.createSession(8, 5, Map.of(5, 42L)));
    }

    @Test
    void createSessionRejectsAnOutOfRangeManagerSeatSlot() {
        assertThrows(IllegalArgumentException.class, () -> service.createSession(8, 1, Map.of(9, 42L)));
        assertThrows(IllegalArgumentException.class, () -> service.createSession(8, 1, Map.of(0, 42L)));
    }

    @Test
    void createSessionRejectsANullManagerIdInsteadOfNpeing() {
        Map<Integer, Long> withNull = new HashMap<>();
        withNull.put(2, null);
        assertThrows(IllegalArgumentException.class, () -> service.createSession(8, 1, withNull));
    }

    @Test
    void createSessionRejectsAnUnknownManagerId() {
        lenient().when(managers.names()).thenReturn(Map.of());
        assertThrows(IllegalArgumentException.class, () -> service.createSession(8, 1, Map.of(2, 999L)));
    }

    @Test
    void createSessionSeatsARealManagerInsteadOfABot() {
        lenient().when(managers.names()).thenReturn(Map.of(42L, "Dave"));

        MockSessionState state = service.createSession(8, 5, Map.of(2, 42L));

        MockSessionState.SeatView seat2 = state.seats().stream()
                .filter(s -> s.slot() == 2).findFirst().orElseThrow();
        assertEquals(SeatSpec.Type.MANAGER, seat2.type());
        assertEquals(42L, seat2.managerId());
        assertEquals("Dave", seat2.manager());

        // The rest of the auto-advanced field is still unmodelled bots.
        MockSessionState.SeatView seat1 = state.seats().stream()
                .filter(s -> s.slot() == 1).findFirst().orElseThrow();
        assertEquals(SeatSpec.Type.BOT, seat1.type());
    }

    @Test
    void submitPickRecordsTheUsersChoiceAndAdvancesToTheNextUserTurn() {
        MockSessionState created = service.createSession(8, 1, Map.of());   // user picks first, no bots ahead
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
        MockSessionState created = service.createSession(8, 5, Map.of());   // slots 1-4 are bots first
        repo.advanceCurrentPick(created.id(), 6, "IN_PROGRESS");  // force onto slot 6, a bot seat

        String someone = created.available().get(0).sleeperId();
        assertThrows(IllegalStateException.class, () -> service.submitPick(created.id(), someone));
    }

    @Test
    void submitPickRejectsAnUnknownPlayer() {
        MockSessionState created = service.createSession(8, 1, Map.of());
        assertThrows(IllegalArgumentException.class,
                () -> service.submitPick(created.id(), "no-such-sleeper-id"));
    }

    @Test
    void submitPickRejectsAPlayerAlreadyDraftedInThisSession() {
        MockSessionState created = service.createSession(8, 1, Map.of());
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
        MockSessionState state = service.createSession(8, 3, Map.of());
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

    @Test
    void createSessionFromDraftSeedsLivePicksAndContinuesPastThem() {
        long managerA = 501L, managerB = 502L;
        Map<String, Object> slotToManager = Map.of("1", managerA, "2", managerB);
        DraftRepository.DraftRow draft = new DraftRepository.DraftRow(
                77L, 9L, "sleeper-draft-fork", 2026, 15, 8, "drafting", slotToManager);
        LeagueRepository.LeagueRow league = new LeagueRepository.LeagueRow(
                9L, "sleeper-league-fork", "Fork League", 2026, 8, LeagueShape.STANDARD_ROSTER, 1.0);
        when(drafts.bySleeperId("sleeper-draft-fork")).thenReturn(Optional.of(draft));
        when(leagues.byId(9L)).thenReturn(Optional.of(league));
        // board(400)'s first entry has player id 1 -- already drafted by slot 1 (managerA).
        when(drafts.picks(77L)).thenReturn(List.of(
                new DraftRepository.PickRow(77L, 1, 1, 1, managerA, 1L, 1.0)));

        MockSessionState state = service.createSessionFromDraft("sleeper-draft-fork", 2);

        assertEquals(77L, state.sourceDraftId());
        assertEquals(2, state.forkedAtPickNo());
        assertEquals(1, state.picks().size(), "only the one seeded pick -- pick 2 is the user's own turn");
        var pick1 = state.picks().get(0);
        assertEquals(1, pick1.pickNo());
        assertEquals("LIVE", pick1.source());
        assertEquals("MANAGER", pick1.seatType().name());
        assertEquals(1L, pick1.player().id());
        assertTrue(state.isUsersTurn(), "slot 2 (mySlot) is next and has no seeded pick of its own");
        assertEquals(2, state.currentPickNo());
        assertEquals(2, state.onTheClockSlot());
    }

    @Test
    void forkedAtPickNoIsTheFirstTrulyUndecidedPickNotMaxPlusOne() {
        // Pick 2 is an unresolved autopick (player_id still null -- a real
        // PickMapper/LiveDraftPoller path), so it's missing from `completed`
        // even though pick 3 (a higher number) is landed. forkedAtPickNo must
        // report 2, not 4 (max(completed)+1), since pick 2 is what actually
        // gets engine-decided as speculative during this same fork call.
        Map<String, Object> slotToManager = Map.of("1", 501L, "2", 502L, "3", 503L);
        DraftRepository.DraftRow draft = new DraftRepository.DraftRow(
                77L, 9L, "sleeper-draft-fork-gap", 2026, 15, 8, "drafting", slotToManager);
        LeagueRepository.LeagueRow league = new LeagueRepository.LeagueRow(
                9L, "sleeper-league-fork-gap", "Fork League", 2026, 8, LeagueShape.STANDARD_ROSTER, 1.0);
        when(drafts.bySleeperId("sleeper-draft-fork-gap")).thenReturn(Optional.of(draft));
        when(leagues.byId(9L)).thenReturn(Optional.of(league));
        when(drafts.picks(77L)).thenReturn(List.of(
                new DraftRepository.PickRow(77L, 1, 1, 1, 501L, 1L, 1.0),
                new DraftRepository.PickRow(77L, 3, 1, 3, 503L, 3L, 3.0)));   // pick 2's player_id is null: unresolved

        // mySlot=5 (unmapped, so a bare USER seat) rather than 2 -- if the user's
        // own seat sat at slot 2, the engine would stop there for input rather
        // than deciding it, which would defeat the point of this test.
        MockSessionState state = service.createSessionFromDraft("sleeper-draft-fork-gap", 5);

        assertEquals(2, state.forkedAtPickNo(), "pick 2 is the first genuinely undecided pick, not pick 4");
        var pick2 = state.picks().stream().filter(p -> p.pickNo() == 2).findFirst().orElseThrow();
        assertEquals("BOT", pick2.source(), "pick 2 had to be engine-decided since Sleeper hadn't resolved it");
    }

    @Test
    void createSessionFromDraftRejectsANonDraftingStatus() {
        DraftRepository.DraftRow draft = new DraftRepository.DraftRow(
                1L, 9L, "sleeper-draft-not-live", 2026, 15, 8, "pre_draft", Map.of());
        when(drafts.bySleeperId("sleeper-draft-not-live")).thenReturn(Optional.of(draft));

        assertThrows(IllegalArgumentException.class,
                () -> service.createSessionFromDraft("sleeper-draft-not-live", 1));
    }

    @Test
    void createSessionFromDraftRejectsAnUnsupportedTeamCount() {
        DraftRepository.DraftRow draft = new DraftRepository.DraftRow(
                1L, 9L, "sleeper-draft-odd-size", 2026, 15, 9, "drafting", Map.of());
        LeagueRepository.LeagueRow league = new LeagueRepository.LeagueRow(
                9L, "sleeper-league-odd-size", "League", 2026, 9, LeagueShape.STANDARD_ROSTER, 1.0);
        when(drafts.bySleeperId("sleeper-draft-odd-size")).thenReturn(Optional.of(draft));
        when(leagues.byId(9L)).thenReturn(Optional.of(league));

        assertThrows(IllegalArgumentException.class,
                () -> service.createSessionFromDraft("sleeper-draft-odd-size", 1));
    }

    @Test
    void createSessionFromDraftThrowsWhenNoMySlotCanBeResolved() {
        DraftRepository.DraftRow draft = new DraftRepository.DraftRow(
                1L, 9L, "sleeper-draft-no-slot", 2026, 15, 8, "drafting", Map.of());
        LeagueRepository.LeagueRow league = new LeagueRepository.LeagueRow(
                9L, "sleeper-league-no-slot", "League", 2026, 8, LeagueShape.STANDARD_ROSTER, 1.0);
        when(drafts.bySleeperId("sleeper-draft-no-slot")).thenReturn(Optional.of(draft));
        when(leagues.byId(9L)).thenReturn(Optional.of(league));
        // owner is unconfigured (OwnerProperties(null) from setUp), and no override is passed.

        assertThrows(IllegalArgumentException.class,
                () -> service.createSessionFromDraft("sleeper-draft-no-slot", null));
    }

    @Test
    void createSessionFromDraftRejectsAnOutOfRangeMySlotOverride() {
        DraftRepository.DraftRow draft = new DraftRepository.DraftRow(
                1L, 9L, "sleeper-draft-bad-slot", 2026, 15, 8, "drafting", Map.of());
        LeagueRepository.LeagueRow league = new LeagueRepository.LeagueRow(
                9L, "sleeper-league-bad-slot", "League", 2026, 8, LeagueShape.STANDARD_ROSTER, 1.0);
        when(drafts.bySleeperId("sleeper-draft-bad-slot")).thenReturn(Optional.of(draft));
        when(leagues.byId(9L)).thenReturn(Optional.of(league));

        assertThrows(IllegalArgumentException.class,
                () -> service.createSessionFromDraft("sleeper-draft-bad-slot", 9));
    }

    /** In-memory stand-in for the real JdbcClient-backed repository. */
    private static final class FakeMockDraftRepository extends MockDraftRepository {
        private final AtomicLong nextId = new AtomicLong(1);
        private final Map<Long, SessionRow> sessions = new HashMap<>();
        private final Map<Long, List<PickRow>> picksBySession = new HashMap<>();

        FakeMockDraftRepository() {
            super(null, null);
        }

        // Only the 9-arg overload is overridden here: MockDraftRepository's own
        // 7-arg createSession is a delegate to it (`this.createSession(..., null,
        // null)`), so overriding just this one covers both callers via ordinary
        // virtual dispatch -- no need for a second override.
        @Override
        public long createSession(int teams, int rounds, List<String> rosterPositions, double ppr,
                                  String seatsJson, int userSlot, long rngSeed,
                                  Long sourceDraftId, Integer forkedAtPickNo) {
            long id = nextId.getAndIncrement();
            sessions.put(id, new SessionRow(id, "IN_PROGRESS", teams, rounds, rosterPositions, ppr,
                    seatsJson, userSlot, rngSeed, 1, sourceDraftId, forkedAtPickNo));
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
                    r.pointsPerReception(), r.seatsJson(), r.userSlot(), r.rngSeed(), currentPickNo,
                    r.sourceDraftId(), r.forkedAtPickNo()));
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
