package com.ballknowers.draftsim.api;

import com.ballknowers.draftsim.domain.Sport;
import com.ballknowers.draftsim.engine.LockInService;
import com.ballknowers.draftsim.store.LeagueMembership;
import com.ballknowers.draftsim.store.LeagueRepository;
import com.ballknowers.draftsim.store.LeagueRepository.LeagueRow;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** Code review S1 (2026-10-22): a {@code ?player=} request must not default to the league-wide live block. */
class LockInControllerLiveDefaultTest {

    private final LockInService service = mock(LockInService.class);
    private final LeagueRepository leagues = mock(LeagueRepository.class);
    private final LeagueMembership membership = mock(LeagueMembership.class);
    private final LockInController controller = new LockInController(service, leagues, membership);
    private final LeagueRow league = new LeagueRow(1L, Sport.NBA, "s1", "L", 2026, 2, List.of(), 0.0, null, "in_season");

    private void arrange() {
        when(leagues.bySleeperId("s1")).thenReturn(Optional.of(league));
        when(membership.canSee(any(), anyLong())).thenReturn(true);
    }

    @Test
    void aPlayerRequestDefaultsToNoLiveBlock() {
        arrange();
        controller.lockIns("s1", "p1", null, null);
        verify(service).report(league, "p1", false);
    }

    @Test
    void aPlayerRequestStillGetsLiveWhenAskedForExplicitly() {
        arrange();
        controller.lockIns("s1", "p1", true, null);
        verify(service).report(league, "p1", true);
    }

    @Test
    void aLeagueRequestDefaultsToLive() {
        arrange();
        controller.lockIns("s1", null, null, null);
        verify(service).report(league, null, true);
    }
}
