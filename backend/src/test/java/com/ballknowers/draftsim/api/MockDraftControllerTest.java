package com.ballknowers.draftsim.api;

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

    private MockSessionState sampleState(long id) {
        return new MockSessionState(id, "IN_PROGRESS", 8, 15, List.of("QB", "BN"), 1, List.of(1),
                List.of(new MockSessionState.SeatView(1, SeatSpec.Type.USER, null, "You")),
                List.of(), List.of(), 1, 1, true, null, null);
    }

    @Test
    void createDelegatesTeamsAndUserSlotToTheService() {
        MockDraftController controller = new MockDraftController(mocks);
        when(mocks.createSession(8, 3, Map.of())).thenReturn(sampleState(1));

        MockSessionState result = controller.create(new MockDraftController.CreateRequest(8, 3, Map.of()));

        assertEquals(1, result.id());
    }

    @Test
    void createDelegatesManagerSeatsToTheService() {
        MockDraftController controller = new MockDraftController(mocks);
        when(mocks.createSession(8, 3, Map.of(5, 42L))).thenReturn(sampleState(1));

        MockSessionState result = controller.create(new MockDraftController.CreateRequest(8, 3, Map.of(5, 42L)));

        assertEquals(1, result.id());
    }

    @Test
    void createRequestTreatsNullManagerSeatsAsEmpty() {
        assertEquals(Map.of(), new MockDraftController.CreateRequest(8, 3, null).managerSeats());
    }

    @Test
    void createFromDraftDelegatesDraftIdAndOptionalMySlotToTheService() {
        MockDraftController controller = new MockDraftController(mocks);
        when(mocks.createSessionFromDraft("sleeper-draft-1", 3)).thenReturn(sampleState(1));

        MockSessionState result = controller.createFromDraft("sleeper-draft-1", 3);

        assertEquals(1, result.id());
    }

    @Test
    void createFromDraftPassesNullMySlotWhenOmitted() {
        MockDraftController controller = new MockDraftController(mocks);
        when(mocks.createSessionFromDraft("sleeper-draft-1", null)).thenReturn(sampleState(2));

        MockSessionState result = controller.createFromDraft("sleeper-draft-1", null);

        assertEquals(2, result.id());
    }

    @Test
    void createRejectsAMissingBody() {
        MockDraftController controller = new MockDraftController(mocks);
        assertThrows(IllegalArgumentException.class, () -> controller.create(null));
    }

    @Test
    void getReturns200WithTheStateWhenTheSessionExists() {
        MockDraftController controller = new MockDraftController(mocks);
        when(mocks.get(5L)).thenReturn(Optional.of(sampleState(5)));

        ResponseEntity<MockSessionState> response = controller.get(5L);

        assertEquals(200, response.getStatusCode().value());
        assertEquals(5, response.getBody().id());
    }

    @Test
    void getReturns404WhenTheSessionDoesNotExist() {
        MockDraftController controller = new MockDraftController(mocks);
        when(mocks.get(404L)).thenReturn(Optional.empty());

        assertEquals(404, controller.get(404L).getStatusCode().value());
    }

    @Test
    void pickRejectsAMissingSleeperPlayerIdWithoutCallingTheService() {
        MockDraftController controller = new MockDraftController(mocks);

        ResponseEntity<?> response = controller.pick(1L, new MockDraftController.PickRequest(""));

        assertEquals(400, response.getStatusCode().value());
        assertEquals(Map.of("error", "sleeperPlayerId is required"), response.getBody());
    }

    @Test
    void pickReturns404WhenTheSessionDoesNotExist() {
        MockDraftController controller = new MockDraftController(mocks);
        when(mocks.submitPick(1L, "abc")).thenReturn(Optional.empty());

        ResponseEntity<?> response = controller.pick(1L, new MockDraftController.PickRequest("abc"));

        assertEquals(404, response.getStatusCode().value());
    }

    @Test
    void pickReturns200WithTheAdvancedStateOnSuccess() {
        MockDraftController controller = new MockDraftController(mocks);
        when(mocks.submitPick(1L, "abc")).thenReturn(Optional.of(sampleState(1)));

        ResponseEntity<?> response = controller.pick(1L, new MockDraftController.PickRequest("abc"));

        assertEquals(200, response.getStatusCode().value());
        assertEquals(1L, ((MockSessionState) response.getBody()).id());
    }

    @Test
    void listDelegatesToTheService() {
        MockDraftController controller = new MockDraftController(mocks);
        var summary = new MockDraftRepository.SessionSummary(1, "IN_PROGRESS", 8, 15, 1, 1, null);
        when(mocks.listSessions()).thenReturn(List.of(summary));

        assertEquals(List.of(summary), controller.list());
    }
}
