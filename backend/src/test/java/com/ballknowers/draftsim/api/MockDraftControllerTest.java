package com.ballknowers.draftsim.api;

import com.ballknowers.draftsim.domain.Sport;
import com.ballknowers.draftsim.engine.SeatSpec;
import com.ballknowers.draftsim.mock.MockDraftService;
import com.ballknowers.draftsim.mock.MockSessionState;
import com.ballknowers.draftsim.store.MockDraftRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.ResponseEntity;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * Request/response wiring for the mock draft room's controller
 * (claude/next-features-roadmap.md §4, Phase 3) -- MockDraftService itself is
 * mocked, same convention as calling a controller bean directly used elsewhere
 * in this package (see LeagueControllerSeatsOwnerConfiguredIT).
 */
@ExtendWith(MockitoExtension.class)
class MockDraftControllerTest {

    @Mock private MockDraftService mocks;
    @Mock private com.ballknowers.draftsim.store.LeagueMembership membership;

    /**
     * Creating a mock now needs an identity (claude/audit-2026-09-28/01): a header-less
     * create used to mint an unowned session that every caller could read and pick
     * into. These tests are about how the controller delegates, so they present a
     * signed-in caller; the refusal has its own tests at the bottom.
     */
    private static final String USER = "tester";

    private MockSessionState sampleState(long id) {
        return new MockSessionState(id, Sport.NFL, "IN_PROGRESS", 8, 15, List.of("QB", "BN"), 1, List.of(1),
                List.of(new MockSessionState.SeatView(1, SeatSpec.Type.USER, null, "You", null)),
                List.of(), List.of(), 1, 1, true, null, null, 0, null);
    }

    @Test
    void createDelegatesTeamsAndUserSlotToTheService() {
        MockDraftController controller = new MockDraftController(mocks, membership);
        when(mocks.createSession(Sport.NFL, 8, 3, Map.of(), USER, null)).thenReturn(sampleState(1));

        MockSessionState result = controller.create(new MockDraftController.CreateRequest(null, 8, 3, Map.of(), null), USER);

        assertEquals(1, result.id());
    }

    @Test
    void createDelegatesManagerSeatsToTheService() {
        MockDraftController controller = new MockDraftController(mocks, membership);
        when(mocks.createSession(Sport.NFL, 8, 3, Map.of(5, 42L), USER, null)).thenReturn(sampleState(1));

        MockSessionState result = controller.create(new MockDraftController.CreateRequest(Sport.NFL, 8, 3, Map.of(5, 42L), null), USER);

        assertEquals(1, result.id());
    }

    @Test
    void createDelegatesSourceLeagueIdToTheService() {
        MockDraftController controller = new MockDraftController(mocks, membership);
        when(mocks.createSession(Sport.NFL, 8, 3, Map.of(), USER, "123456")).thenReturn(sampleState(1));

        MockSessionState result = controller.create(
                new MockDraftController.CreateRequest(Sport.NFL, 8, 3, Map.of(), "123456"), USER);

        assertEquals(1, result.id());
    }

    @Test
    void createPassesTheRequestedSportThroughRatherThanAssumingFootball() {
        MockDraftController controller = new MockDraftController(mocks, membership);
        when(mocks.createSession(Sport.NBA, 12, 3, Map.of(), USER, "nba-league")).thenReturn(sampleState(7));

        MockSessionState result = controller.create(
                new MockDraftController.CreateRequest(Sport.NBA, 12, 3, Map.of(), "nba-league"), USER);

        assertEquals(7, result.id());
    }

    @Test
    void createRequestTreatsNullManagerSeatsAsEmpty() {
        assertEquals(Map.of(), new MockDraftController.CreateRequest(null, 8, 3, null, null).managerSeats());
    }

    /**
     * Only an omitted sport falls back to football -- a pre-multi-sport client
     * that never sent the field. The home screen's modal always states it.
     */
    @Test
    void createRequestTreatsAnOmittedSportAsFootball() {
        assertEquals(Sport.NFL, new MockDraftController.CreateRequest(null, 8, 3, null, null).sport());
    }

    @Test
    void createFromDraftDelegatesDraftIdAndOptionalMySlotToTheService() {
        MockDraftController controller = new MockDraftController(mocks, membership);
        when(mocks.createSessionFromDraft("sleeper-draft-1", 3, USER)).thenReturn(sampleState(1));

        MockSessionState result = controller.createFromDraft("sleeper-draft-1", 3, USER);

        assertEquals(1, result.id());
    }

    @Test
    void createFromDraftPassesNullMySlotWhenOmitted() {
        MockDraftController controller = new MockDraftController(mocks, membership);
        when(mocks.createSessionFromDraft("sleeper-draft-1", null, USER)).thenReturn(sampleState(2));

        MockSessionState result = controller.createFromDraft("sleeper-draft-1", null, USER);

        assertEquals(2, result.id());
    }

    @Test
    void createFromDraftPassesTheSleeperUserHeaderThrough() {
        MockDraftController controller = new MockDraftController(mocks, membership);
        when(mocks.createSessionFromDraft("sleeper-draft-1", null, "1122386008709910528")).thenReturn(sampleState(3));

        MockSessionState result = controller.createFromDraft("sleeper-draft-1", null, "1122386008709910528");

        assertEquals(3, result.id());
    }

    @Test
    void createRejectsAMissingBody() {
        MockDraftController controller = new MockDraftController(mocks, membership);
        assertThrows(IllegalArgumentException.class, () -> controller.create(null, USER));
    }

    @Test
    void getReturns200WithTheStateWhenTheSessionExists() {
        MockDraftController controller = new MockDraftController(mocks, membership);
        when(mocks.get(5L, null)).thenReturn(Optional.of(sampleState(5)));

        ResponseEntity<MockSessionState> response = controller.get(5L, null);

        assertEquals(200, response.getStatusCode().value());
        assertEquals(5, response.getBody().id());
    }

    @Test
    void getReturns404WhenTheSessionDoesNotExist() {
        MockDraftController controller = new MockDraftController(mocks, membership);
        when(mocks.get(404L, null)).thenReturn(Optional.empty());

        assertEquals(404, controller.get(404L, null).getStatusCode().value());
    }

    @Test
    void pickRejectsAMissingSleeperPlayerIdWithoutCallingTheService() {
        MockDraftController controller = new MockDraftController(mocks, membership);

        ResponseEntity<?> response = controller.pick(1L, new MockDraftController.PickRequest(""), null);

        assertEquals(400, response.getStatusCode().value());
        assertEquals(Map.of("error", "sleeperPlayerId is required"), response.getBody());
    }

    @Test
    void pickReturns404WhenTheSessionDoesNotExist() {
        MockDraftController controller = new MockDraftController(mocks, membership);
        when(mocks.submitPick(1L, "abc", null)).thenReturn(Optional.empty());

        ResponseEntity<?> response = controller.pick(1L, new MockDraftController.PickRequest("abc"), null);

        assertEquals(404, response.getStatusCode().value());
    }

    @Test
    void pickReturns200WithTheAdvancedStateOnSuccess() {
        MockDraftController controller = new MockDraftController(mocks, membership);
        when(mocks.submitPick(1L, "abc", null)).thenReturn(Optional.of(sampleState(1)));

        ResponseEntity<?> response = controller.pick(1L, new MockDraftController.PickRequest("abc"), null);

        assertEquals(200, response.getStatusCode().value());
        assertEquals(1L, ((MockSessionState) response.getBody()).id());
    }

    @Test
    void listDelegatesToTheService() {
        MockDraftController controller = new MockDraftController(mocks, membership);
        var summary = new MockDraftRepository.SessionSummary(1, Sport.NFL, "IN_PROGRESS", 8, 15, 1, 1, null, null, null);
        when(mocks.listSessions(null)).thenReturn(List.of(summary));

        assertEquals(List.of(summary), controller.list(null));
    }
    @Test
    void createWithoutAnIdentityIsRefusedAndNeverReachesTheService() {
        MockDraftController controller = new MockDraftController(mocks, membership);
        var body = new MockDraftController.CreateRequest(Sport.NFL, 8, 3, Map.of(), null);

        for (String blank : new String[] {null, "", "   "}) {
            var e = assertThrows(org.springframework.web.server.ResponseStatusException.class,
                    () -> controller.create(body, blank));
            assertEquals(401, e.getStatusCode().value());
        }
        assertThrows(org.springframework.web.server.ResponseStatusException.class,
                () -> controller.createFromDraft("sleeper-draft-1", null, null));
        verifyNoInteractions(mocks);
    }

    @Test
    void anAdminTokenLetsAnOperatorCreateWithoutAnIdentity() {
        MockDraftController controller = new MockDraftController(mocks, membership);
        when(membership.isAdminRequest()).thenReturn(true);
        when(mocks.createSession(Sport.NFL, 8, 3, Map.of(), null, null)).thenReturn(sampleState(9));

        assertEquals(9, controller.create(new MockDraftController.CreateRequest(Sport.NFL, 8, 3, Map.of(), null), null).id());
    }
}
