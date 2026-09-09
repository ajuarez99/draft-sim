package com.ballknowers.draftsim.api;

import com.ballknowers.draftsim.engine.SimulationRequest;
import com.ballknowers.draftsim.engine.SimulationService;
import com.ballknowers.draftsim.store.DraftRepository;
import com.ballknowers.draftsim.store.LeagueMembership;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * /api/sims was the one draft-addressed route the identity work
 * (claude/user-identity-and-onboarding.md) did not scope.
 *
 * That mattered because a simulation is not a private calculation: it is
 * addressed by a draft id and it answers with that draft's board, its seat map
 * and every seat's fitted manager profile -- the same data
 * GET /api/drafts/{id}/seats and /board return, reached by a different verb.
 * Scoping those two while leaving this open meant the boundary was decoration:
 * a visitor could read a stranger's league by POSTing its draft id here instead
 * of GETting it there.
 *
 * <p>Scoping, not security -- {@code X-Sleeper-User} is an unverified claim
 * (see {@link LeagueMembership}'s own header). What these tests pin is that the
 * rule is applied at all, and applied on both endpoints.
 */
@ExtendWith(MockitoExtension.class)
class SimulationControllerScopingTest {

    @Mock private SimulationService sims;
    @Mock private LeagueMembership membership;

    private static final SimulationRequest REQUEST =
            new SimulationRequest("someone-elses-draft", 3, 100, null, Map.of(), null, null);

    private SimulationController controller() {
        return new SimulationController(sims, membership);
    }

    private static DraftRepository.DraftRow row() {
        return new DraftRepository.DraftRow(1L, 10L, "someone-elses-draft", 2026, 15, 14,
                "complete", Map.of());
    }

    @Test
    void aDraftInSomeoneElsesLeagueIsRefusedBeforeAnythingIsSimulated() {
        when(membership.visibleDraft("visitor", "someone-elses-draft")).thenReturn(Optional.empty());

        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                () -> controller().run(REQUEST, "visitor"));

        // Deliberately the same message SimulationService throws for a draft that
        // genuinely isn't ingested. A distinct "forbidden" would confirm the draft
        // exists, which is most of what enumerating draft ids wanted.
        assertTrue(e.getMessage().contains("not ingested"), e.getMessage());
        verify(sims, never()).simulate(any(), any());
    }

    @Test
    void theStreamingEndpointIsScopedToo() {
        when(membership.visibleDraft("visitor", "someone-elses-draft")).thenReturn(Optional.empty());

        // The check has to run before the emitter is created, not inside the
        // worker thread: otherwise the refusal arrives as an `error` event on a
        // stream that opened with 200, which no caller treats as a denial.
        assertThrows(IllegalArgumentException.class, () -> controller().stream(REQUEST, "visitor"));
        verify(sims, never()).simulate(any(), any());
    }

    @Test
    void aMemberOfTheLeagueStillGetsTheirSimulation() {
        when(membership.visibleDraft("member", "someone-elses-draft")).thenReturn(Optional.of(row()));

        controller().run(REQUEST, "member");

        verify(sims).simulate(REQUEST, null);
    }

    /**
     * No header at all is the pre-identity contract every endpoint kept: the
     * curl-driven draft-night escape hatches and the APP_OWNER_SLEEPER_USER_ID
     * fallback both depend on it, and LeagueMembership.canSee answers true for an
     * anonymous caller by design. This test exists so that stays a decision
     * rather than an accident.
     */
    @Test
    void aCallerWithNoIdentityHeaderIsStillAllowedThrough() {
        when(membership.visibleDraft(null, "someone-elses-draft")).thenReturn(Optional.of(row()));

        controller().run(REQUEST, null);

        verify(sims).simulate(REQUEST, null);
    }
}
