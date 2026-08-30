package com.ballknowers.draftsim.ingest;

import com.ballknowers.draftsim.domain.Sport;
import com.ballknowers.draftsim.store.DraftRepository;
import com.ballknowers.draftsim.store.ManagerRepository;
import com.ballknowers.draftsim.store.PlayerRepository;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The best available substitute for "verify against a real live draft" per
 * claude/live-poller-plan.md: pulls an already-complete real draft (Ball Knowers 2026,
 * draft 1346366555776126976) via a real SleeperClient call, replays it through
 * PickMapper.toPickRow + upsertPicks -- the same pipeline LiveDraftPoller.pollOnce uses --
 * and asserts the result matches what LeagueIngestService.ingestDraft's replacePicks path
 * already produced for the same draft after a real /api/ingest/all-shaped run. Proves the
 * two independent pipelines (batch ingest, live poller) map picks identically for a real
 * draft, without needing a drafting-status draft to exist.
 *
 * Requires both a reachable local Postgres and real internet access to api.sleeper.app;
 * skips cleanly (not fails) when either is unavailable.
 */
@SpringBootTest
class DifferentialReplayIT {

    private static final String COMPLETE_DRAFT_ID = "1346366555776126976"; // Ball Knowers 2026

    private static final String JDBC_URL = "jdbc:postgresql://localhost:5433/draftsim";
    private static final String USER = "draftsim";
    private static final String PASSWORD = "draftsim";

    @BeforeAll
    static void requiresPostgresAndInternet() {
        boolean pgReachable;
        try (Connection c = DriverManager.getConnection(JDBC_URL, USER, PASSWORD)) {
            pgReachable = true;
        } catch (SQLException e) {
            pgReachable = false;
        }
        Assumptions.assumeTrue(pgReachable,
                "no local Postgres reachable at " + JDBC_URL + " -- skipping differential replay test");

        boolean sleeperReachable;
        try {
            HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
            HttpRequest req = HttpRequest.newBuilder(URI.create("https://api.sleeper.app/v1/draft/" + COMPLETE_DRAFT_ID))
                    .timeout(Duration.ofSeconds(10)).GET().build();
            HttpResponse<Void> resp = http.send(req, HttpResponse.BodyHandlers.discarding());
            sleeperReachable = resp.statusCode() == 200;
        } catch (Exception e) {
            sleeperReachable = false;
        }
        Assumptions.assumeTrue(sleeperReachable,
                "no real internet access to api.sleeper.app -- skipping differential replay test");
    }

    @Autowired private SleeperClient sleeper;
    @Autowired private PlayerIngestService playerIngest;
    @Autowired private LeagueIngestService leagueIngest;
    @Autowired private DraftRepository drafts;
    @Autowired private ManagerRepository managers;
    @Autowired private PlayerRepository players;

    @Test
    void pickMapperPipelineMatchesReplacePicksPathForARealCompleteDraft() {
        Map<String, Object> draftMeta = sleeper.draft(COMPLETE_DRAFT_ID);
        assertEquals("complete", draftMeta.get("status"), "expected a complete draft to diff against");
        String sleeperLeagueId = String.valueOf(draftMeta.get("league_id"));

        // The real /api/ingest/all pipeline (minus adp/board, irrelevant to pick mapping):
        // populates player, manager, league, draft and draft_pick (via replacePicks -> PickMapper).
        playerIngest.ingest(Sport.NFL);
        leagueIngest.ingestChain(Sport.NFL, sleeperLeagueId);

        DraftRepository.DraftRow draftRow = drafts.bySleeperId(COMPLETE_DRAFT_ID).orElseThrow(
                () -> new AssertionError("draft not ingested: " + COMPLETE_DRAFT_ID));
        List<DraftRepository.PickRow> viaReplacePicks = drafts.picks(draftRow.id());
        assertFalse(viaReplacePicks.isEmpty(), "ingestChain should have produced picks for this draft");

        // Independently replay the same draft through the poller's own pipeline:
        // ManagerRepository.idsBySleeperUserId + slot conversion + PickMapper + upsertPicks.
        Map<String, Long> managerByUserId = managers.idsBySleeperUserId();
        Map<String, Long> playerIdsBySleeperId = players.idsBySleeperId(Sport.NFL);
        Map<Integer, Long> slotLookup = new HashMap<>();
        draftRow.slotToManager().forEach((slotStr, managerIdObj) ->
                slotLookup.put(Integer.parseInt(slotStr), ((Number) managerIdObj).longValue()));

        List<Map<String, Object>> rawPicks = sleeper.draftPicks(COMPLETE_DRAFT_ID);
        List<DraftRepository.PickRow> viaPickMapper = new ArrayList<>(rawPicks.size());
        for (Map<String, Object> p : rawPicks) {
            viaPickMapper.add(PickMapper.toPickRow(draftRow.id(), p, managerByUserId, slotLookup, playerIdsBySleeperId));
        }
        drafts.upsertPicks(draftRow.id(), viaPickMapper);

        List<DraftRepository.PickRow> afterUpsert = drafts.picks(draftRow.id());
        assertEquals(viaReplacePicks.size(), afterUpsert.size(), "pick count must match between the two pipelines");

        Map<Integer, DraftRepository.PickRow> expectedByPickNo = viaReplacePicks.stream()
                .collect(Collectors.toMap(DraftRepository.PickRow::pickNo, r -> r));
        for (DraftRepository.PickRow actual : afterUpsert) {
            DraftRepository.PickRow expected = expectedByPickNo.get(actual.pickNo());
            assertNotNull(expected, "pick_no " + actual.pickNo() + " present in the poller path but not replacePicks");
            assertEquals(expected.round(), actual.round(), "round mismatch for pick_no " + actual.pickNo());
            assertEquals(expected.draftSlot(), actual.draftSlot(), "draft_slot mismatch for pick_no " + actual.pickNo());
            assertEquals(expected.managerId(), actual.managerId(), "manager_id mismatch for pick_no " + actual.pickNo());
            assertEquals(expected.playerId(), actual.playerId(), "player_id mismatch for pick_no " + actual.pickNo());
        }
    }
}
