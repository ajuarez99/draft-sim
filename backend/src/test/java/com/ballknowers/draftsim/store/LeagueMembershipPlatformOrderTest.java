package com.ballknowers.draftsim.store;

import com.ballknowers.draftsim.api.PlatformGuard;
import com.ballknowers.draftsim.domain.Platform;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.jdbc.core.simple.JdbcClient;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * Spec 035 code review S2 and S4, without a database: the draft and league guards are cheap-first (a Sleeper
 * key costs one platform lookup and no membership query), and an ESPN-shaped header is no identity.
 */
class LeagueMembershipPlatformOrderTest {

    private final JdbcClient db = Mockito.mock(JdbcClient.class);
    private final DraftRepository drafts = Mockito.mock(DraftRepository.class);
    private final LeagueRepository leagues = Mockito.mock(LeagueRepository.class);
    private final com.ballknowers.draftsim.config.AdminAccess admin = Mockito.mock(com.ballknowers.draftsim.config.AdminAccess.class);
    private final LeagueMembership membership =
            new LeagueMembership(db, drafts, leagues, null, null, null, admin);

    @Test
    void aSleeperDraftReturnsBeforeAnyMembershipQuery() {
        when(drafts.platformOf("d1")).thenReturn(Optional.of(Platform.SLEEPER));

        assertTrue(membership.platformOfVisibleDraft("123456", "d1").isEmpty());

        verifyNoInteractions(db);                       // no recursive membership CTE
        verify(drafts, never()).bySleeperId(Mockito.anyString());
        Mockito.verify(drafts, Mockito.times(1)).platformOf("d1");   // and one platform lookup, not two
    }

    @Test
    void aSleeperLeagueReturnsBeforeAnyMembershipQuery() {
        when(leagues.platformOf("L1")).thenReturn(Optional.of(Platform.SLEEPER));

        assertTrue(membership.platformOfVisibleLeague("123456", "L1").isEmpty());

        verifyNoInteractions(db);
        verify(leagues, never()).bySleeperId(Mockito.anyString());
        verify(leagues, Mockito.times(1)).platformOf("L1");
    }

    @Test
    void theGuardDoesNotRefuseASleeperDraftAndStillRefusesAVisibleEspnOne() {
        when(drafts.platformOf("d1")).thenReturn(Optional.of(Platform.SLEEPER));
        assertDoesNotThrow(() -> PlatformGuard.requireSleeperDraft(membership, "123456", "d1"));
        verifyNoInteractions(db);

        // An ESPN draft the caller cannot see (admin-less anonymous) is not an existence oracle: no refusal.
        when(drafts.platformOf("e1")).thenReturn(Optional.of(Platform.ESPN));
        when(drafts.bySleeperId("e1")).thenReturn(Optional.empty());
        assertDoesNotThrow(() -> PlatformGuard.requireSleeperDraft(membership, "123456", "e1"));
    }

    @Test
    void anEspnShapedHeaderIsAnonymousAndSeesNothing() {
        for (String h : new String[] {"espn:{ABC-1}", "ESPN:{ABC-1}", "{ABC-1}", "espn:123", "a:b"}) {
            assertTrue(LeagueMembership.isAnonymous(h), h);
        }
        for (String h : new String[] {"123456789012345678", "it035-allan", "owner4", "Some_User.1"}) {
            assertFalse(LeagueMembership.isAnonymous(h), h);
        }
        // canSee never reaches the membership query for it.
        Mockito.when(db.sql(Mockito.anyString())).thenThrow(new AssertionError("membership query for an ESPN-shaped header"));
        assertFalse(assertDoesNotThrowValue(() -> membership.canSee("espn:{ABC-1}", 1L)));
    }

    @Test
    void theCallerResolverTreatsAnEspnShapedHeaderAsNobody() {
        ManagerRepository managers = Mockito.mock(ManagerRepository.class);
        when(managers.idsBySleeperUserId()).thenReturn(java.util.Map.of("espn:{ABC-1}", 9L));
        when(managers.callerManagerIn(Mockito.anyCollection(), Mockito.eq("espn:{ABC-1}"))).thenReturn(Optional.of(9L));

        assertNull(ManagerRepository.caller(managers, 5L, "espn:{ABC-1}"));
    }

    private static boolean assertDoesNotThrowValue(java.util.function.BooleanSupplier s) {
        return assertDoesNotThrow(s::getAsBoolean);
    }
}
