package com.ballknowers.draftsim.api;

import com.ballknowers.draftsim.domain.BoardEntry;
import com.ballknowers.draftsim.domain.Player;
import com.ballknowers.draftsim.domain.Position;
import com.ballknowers.draftsim.domain.Sport;
import com.ballknowers.draftsim.engine.SimulationResult;
import com.ballknowers.draftsim.ingest.BoardService;
import com.ballknowers.draftsim.mock.MockDraftService;
import com.ballknowers.draftsim.mock.MockSessionState;
import com.ballknowers.draftsim.store.MockDraftRepository;
import com.ballknowers.draftsim.store.PlayerRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.server.ResponseStatusException;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Real-Postgres coverage for {@code /api/targets} (spec 024 US3): the V30 table, the replace-the-whole-list
 * write, the advisory lock under concurrent saves, and the fork copy. Set up like
 * {@link LeagueControllerSeatsOwnerConfiguredIT}: the real Spring context against the local-dev Postgres on
 * 5433, SKIPPED (not failed) when that is unreachable -- so a green run must have its skip count read.
 *
 * <p>Calls the controller bean directly, so status codes appear as returned entities or thrown exceptions
 * (ErrorHandler is not in the path); the HTTP-level status codes are in {@code AccessControlMvcIT}.
 */
@SpringBootTest
class TargetControllerIT {

    private static final String JDBC_URL = "jdbc:postgresql://localhost:5433/draftsim";
    private static final String USER = "draftsim";
    private static final String PASSWORD = "draftsim";

    static final String LEAGUE = "it-target-league";
    static final String DRAFT = "it-target-draft";
    static final String ME = "it-target-me";
    static final String OTHER = "it-target-other";       // a league member too
    static final String STRANGER = "it-target-stranger"; // a manager in no league
    static final String OFFBOARD = "it-target-offboard";

    @BeforeAll
    static void requiresLocalPostgres() {
        boolean reachable;
        try (Connection c = DriverManager.getConnection(JDBC_URL, USER, PASSWORD)) {
            reachable = true;
        } catch (SQLException e) {
            reachable = false;
        }
        Assumptions.assumeTrue(reachable,
                "no local Postgres reachable at " + JDBC_URL + " -- skipping TargetController integration test");
    }

    @Autowired private TargetController controller;
    @Autowired private MockDraftService mocks;
    @Autowired private MockDraftRepository mockDrafts;
    @Autowired private PlayerRepository players;
    @Autowired private BoardService boards;
    @Autowired private JdbcTemplate jdbc;

    private long leagueId;
    private long meManager;
    private long otherManager;
    private List<String> board;   // sleeper ids on the current NFL board, best ADP first

    @BeforeEach
    void setUp() {
        tearDown();
        List<BoardEntry> current = boards.currentBoard(Sport.NFL);
        Assumptions.assumeTrue(current.size() >= 60, "no built NFL board in the local database");
        board = current.stream().map(e -> e.player().sleeperId()).limit(60).toList();

        meManager = jdbc.queryForObject(
                "insert into manager (sleeper_user_id, display_name) values (?, ?) returning id", Long.class, ME, "IT Me");
        otherManager = jdbc.queryForObject(
                "insert into manager (sleeper_user_id, display_name) values (?, ?) returning id", Long.class, OTHER, "IT Other");
        jdbc.update("insert into manager (sleeper_user_id, display_name) values (?, ?)", STRANGER, "IT Stranger");
        leagueId = jdbc.queryForObject(
                "insert into league (sport, season, sleeper_id, name, total_rosters) values ('nfl', 2026, ?, 'IT Targets', 12) returning id",
                Long.class, LEAGUE);
        jdbc.update("""
                insert into draft (league_id, sleeper_draft_id, season, rounds, teams, draft_type, status, slot_to_manager)
                values (?, ?, 2026, 15, 12, 'snake', 'drafting', ?::jsonb)
                """, leagueId, DRAFT, "{\"1\": " + meManager + ", \"2\": " + otherManager + "}");
        players.upsertAll(Sport.NFL, List.of(new Player(0L, Sport.NFL, OFFBOARD, "IT Offboard",
                List.of(Position.WR), null, "Active", null, null, null)));
    }

    @AfterEach
    void tearDown() {
        jdbc.update("delete from draft_target where owner_sleeper_user_id like 'it-target-%'");
        jdbc.update("delete from mock_draft_session where owner_sleeper_user_id like 'it-target-%'");
        jdbc.update("delete from league where sleeper_id = ?", LEAGUE);
        jdbc.update("delete from manager where sleeper_user_id like 'it-target-%'");
        jdbc.update("delete from player where sport = 'nfl' and sleeper_id = ?", OFFBOARD);
    }

    private static TargetController.DraftTargets body(ResponseEntity<?> r) {
        assertEquals(200, r.getStatusCode().value());
        return (TargetController.DraftTargets) r.getBody();
    }

    private TargetController.DraftTargets saveDraft(String user, List<String> ids) {
        return body(controller.put(new TargetController.PutRequest(DRAFT, null, ids), user));
    }

    private TargetController.DraftTargets readDraft(String user) {
        return body(controller.get(DRAFT, null, user));
    }

    private static List<String> sleeperIds(TargetController.DraftTargets t) {
        return t.players().stream().map(SimulationResult.PlayerRef::sleeperId).toList();
    }

    @Test
    void roundTripKeepsRankOrderAndPutEmptyClears() {
        List<String> ids = List.of(board.get(3), board.get(1), board.get(2));

        TargetController.DraftTargets saved = saveDraft(ME, ids);
        assertEquals(ids, sleeperIds(saved), "PUT returns the saved list in rank order");
        assertEquals(ids, sleeperIds(readDraft(ME)), "and GET returns the same");
        assertTrue(readDraft(ME).missing().isEmpty());

        // Reordering is a full replace: the new order wins and nothing from the old list lingers.
        List<String> reordered = List.of(board.get(2), board.get(5));
        assertEquals(reordered, sleeperIds(saveDraft(ME, reordered)));
        assertEquals(2, jdbc.queryForObject(
                "select count(*) from draft_target where owner_sleeper_user_id = ? and sleeper_draft_id = ?",
                Integer.class, ME, DRAFT));

        assertTrue(saveDraft(ME, List.of()).players().isEmpty());
        assertTrue(readDraft(ME).players().isEmpty());
        assertEquals(0, jdbc.queryForObject(
                "select count(*) from draft_target where owner_sleeper_user_id = ?", Integer.class, ME));
    }

    @Test
    void aTargetOffTheBoardComesBackInMissingWithItsNameAndNoAdp() {
        TargetController.DraftTargets saved = saveDraft(ME, List.of(board.get(0), OFFBOARD, board.get(1)));

        assertEquals(List.of(board.get(0), board.get(1)), sleeperIds(saved));
        assertEquals(1, saved.missing().size());
        assertEquals(OFFBOARD, saved.missing().get(0).sleeperId());
        assertEquals("IT Offboard", saved.missing().get(0).name());
        assertEquals(saved.missing(), readDraft(ME).missing());
    }

    @Test
    void listsArePrivateToTheirOwner() {
        saveDraft(ME, List.of(board.get(0)));
        saveDraft(OTHER, List.of(board.get(9), board.get(8)));

        assertEquals(List.of(board.get(0)), sleeperIds(readDraft(ME)));
        assertEquals(List.of(board.get(9), board.get(8)), sleeperIds(readDraft(OTHER)));
    }

    @Test
    void badRequestsAre400() {
        assertThrows(IllegalArgumentException.class, () -> saveDraft(ME, List.of(board.get(0), board.get(0))),
                "a duplicate");
        assertThrows(IllegalArgumentException.class, () -> saveDraft(ME, List.of(board.get(0), "no-such-sleeper-id")),
                "an unknown sleeper id");

        // ARBITRARY cap (50). Real, distinct ids, so only the cap can be what refuses it.
        IllegalArgumentException cap = assertThrows(IllegalArgumentException.class,
                () -> saveDraft(ME, board.subList(0, 51)));
        assertTrue(cap.getMessage().contains("at most 50"), cap.getMessage());
        assertEquals(50, saveDraft(ME, board.subList(0, 50)).players().size(), "exactly 50 is allowed");

        assertThrows(IllegalArgumentException.class,
                () -> controller.put(new TargetController.PutRequest(null, null, List.of()), ME), "neither scope");
        assertThrows(IllegalArgumentException.class,
                () -> controller.put(new TargetController.PutRequest(DRAFT, 1L, List.of()), ME), "both scopes");
        assertThrows(IllegalArgumentException.class, () -> controller.get(null, null, ME), "neither scope on GET");
        assertThrows(IllegalArgumentException.class, () -> controller.get(DRAFT, 1L, ME), "both scopes on GET");
        assertThrows(IllegalArgumentException.class,
                () -> controller.put(new TargetController.PutRequest(DRAFT, null, null), ME), "no list at all");

        // A refused save changes nothing.
        assertEquals(50, readDraft(ME).players().size());
    }

    @Test
    void identityRules() {
        // Anonymous read: an empty list, not an error. Anonymous write: 401.
        assertTrue(body(controller.get(DRAFT, null, null)).players().isEmpty());
        assertTrue(body(controller.get(DRAFT, null, "  ")).players().isEmpty());
        ResponseStatusException e = assertThrows(ResponseStatusException.class,
                () -> controller.put(new TargetController.PutRequest(DRAFT, null, List.of()), null));
        assertEquals(401, e.getStatusCode().value());
        try (var admin = com.ballknowers.draftsim.TestAdmin.asAdmin()) {
            assertEquals(401, assertThrows(ResponseStatusException.class,
                    () -> controller.put(new TargetController.PutRequest(DRAFT, null, List.of()), null))
                    .getStatusCode().value(), "the admin token does not stand in for an owner");
        }

        // A signed-in non-member, and an unknown draft, are 404 on both verbs.
        assertEquals(404, controller.get(DRAFT, null, STRANGER).getStatusCode().value());
        assertEquals(404, controller.put(new TargetController.PutRequest(DRAFT, null, List.of()), STRANGER)
                .getStatusCode().value());
        assertEquals(404, controller.get("no-such-draft", null, ME).getStatusCode().value());
    }

    @Test
    void aMockOfSomeoneElseIs404AndYourOwnWorks() {
        long mockId = mockDrafts.createSession(Sport.NFL, 8, 15, List.of("QB", "BN"), 1.0, "[]", 1, 1L,
                null, null, ME, null, 0, null);

        TargetController.DraftTargets saved = body(controller.put(
                new TargetController.PutRequest(null, mockId, List.of(board.get(4), board.get(0))), ME));
        assertEquals(List.of(board.get(4), board.get(0)), sleeperIds(saved));
        assertEquals(saved.players(), body(controller.get(null, mockId, ME)).players());

        assertEquals(404, controller.get(null, mockId, OTHER).getStatusCode().value());
        assertEquals(404, controller.put(new TargetController.PutRequest(null, mockId, List.of()), OTHER)
                .getStatusCode().value());
        assertEquals(404, controller.get(null, 987_654_321L, ME).getStatusCode().value());
        // The refused write left the owner's list alone.
        assertEquals(2, body(controller.get(null, mockId, ME)).players().size());
    }

    @Test
    void forkingAMockCopiesTheCallersTargetsOnly() {
        List<String> mine = List.of(board.get(7), board.get(3), board.get(5));
        saveDraft(ME, mine);
        saveDraft(OTHER, List.of(board.get(20)));

        MockSessionState fork = mocks.createSessionFromDraft(DRAFT, 1, ME);

        assertEquals(mine, sleeperIds(body(controller.get(null, fork.id(), ME))), "ME's targets, in rank order");
        assertEquals(0, jdbc.queryForObject(
                "select count(*) from draft_target where mock_session_id = ? and owner_sleeper_user_id <> ?",
                Integer.class, fork.id(), ME), "nobody else's targets were copied");
        // The source list is untouched, and a later edit to the mock's list does not leak back.
        saveMock(fork.id(), ME, List.of(board.get(0)));
        assertEquals(mine, sleeperIds(readDraft(ME)));
    }

    private TargetController.DraftTargets saveMock(long mockId, String user, List<String> ids) {
        return body(controller.put(new TargetController.PutRequest(null, mockId, ids), user));
    }

    /**
     * Without the advisory lock two simultaneous replaces both delete, then both insert, and the second
     * trips the partial unique index (a 500). With it they serialize: both succeed and the survivor is
     * exactly one of the two lists -- never a mix (research A8).
     */
    @Test
    void twoConcurrentPutsBothSucceedAndTheFinalListIsExactlyOneOfThem() throws Exception {
        List<String> a = List.of(board.get(0), board.get(1), board.get(2), board.get(3));
        List<String> b = List.of(board.get(3), board.get(10), board.get(11));
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            for (int round = 0; round < 15; round++) {
                CountDownLatch go = new CountDownLatch(1);
                Callable<Integer> putA = () -> {
                    go.await();
                    return controller.put(new TargetController.PutRequest(DRAFT, null, a), ME).getStatusCode().value();
                };
                Callable<Integer> putB = () -> {
                    go.await();
                    return controller.put(new TargetController.PutRequest(DRAFT, null, b), ME).getStatusCode().value();
                };
                Future<Integer> fa = pool.submit(putA);
                Future<Integer> fb = pool.submit(putB);
                go.countDown();
                assertEquals(200, fa.get());
                assertEquals(200, fb.get());

                List<String> finalList = sleeperIds(readDraft(ME));
                assertTrue(finalList.equals(a) || finalList.equals(b),
                        "round " + round + ": final list must be exactly one of the two, got " + finalList);
                List<Integer> ranks = new ArrayList<>(jdbc.queryForList(
                        "select rank from draft_target where owner_sleeper_user_id = ? and sleeper_draft_id = ? order by rank",
                        Integer.class, ME, DRAFT));
                assertEquals(java.util.stream.IntStream.range(0, finalList.size()).boxed().toList(), ranks,
                        "ranks are 0..n-1 with no leftovers from the other writer");
            }
        } finally {
            pool.shutdownNow();
        }
    }
}
