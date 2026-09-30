package com.ballknowers.draftsim.profile;

import com.ballknowers.draftsim.config.PriorProperties;
import com.ballknowers.draftsim.config.ShrinkageProperties;
import com.ballknowers.draftsim.domain.Player;
import com.ballknowers.draftsim.domain.Sport;
import com.ballknowers.draftsim.profile.ProfileService.ScoredPick;
import com.ballknowers.draftsim.store.DraftRepository;
import com.ballknowers.draftsim.store.ManagerProfileRepository;
import com.ballknowers.draftsim.store.ManagerRepository;
import com.ballknowers.draftsim.store.PlayerRepository;
import org.junit.jupiter.api.Test;

import java.util.*;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * claude/audit-2026-09-28/11-reach-bias-baseline.md. The relative figure is
 * display-only and measures a manager against the other managers in the same
 * draft, so a draft's own baseline offset cancels.
 */
class RelativeReachTest {

    private static final double EPS = 1e-9;

    private static DraftRepository.CompletedPick pick(long draftId, int pickNo, long manager, double reach) {
        // adpAtTime = pickNo + reach, so adpAtTime - pickNo == reach exactly.
        return new DraftRepository.CompletedPick(draftId, pickNo, (pickNo + 1) / 2, 1, manager,
                1000L + pickNo, pickNo + reach, 2, 6);
    }

    private ProfileService.Fit fit(List<DraftRepository.CompletedPick> picks) {
        DraftRepository drafts = mock(DraftRepository.class);
        PlayerRepository players = mock(PlayerRepository.class);
        ManagerRepository managers = mock(ManagerRepository.class);
        ManagerProfileRepository profiles = mock(ManagerProfileRepository.class);
        when(drafts.allCompletedPicks(Sport.NFL)).thenReturn(picks);
        when(players.findAll(Sport.NFL)).thenReturn(List.<Player>of());
        when(managers.names()).thenReturn(Map.of());
        when(managers.avatarIds()).thenReturn(Map.of());
        when(profiles.manualBySport(Sport.NFL)).thenReturn(Map.of());
        return new ProfileService(drafts, players, managers, profiles,
                new ShrinkageProperties(4.0), new PriorProperties(1.0, Map.of("nfl", 4)))
                .fit(Sport.NFL);
    }

    /** Preference ordering (bug class 1): earlier than the room reads earlier, whatever the room's own offset. */
    @Test
    void earlierThanTheRoomIsPositiveAndTheRoomsOwnOffsetCancels() {
        List<DraftRepository.CompletedPick> picks = new ArrayList<>();
        // Draft 1 room offset +10: manager 1 at +20, manager 2 at 0.
        // Draft 2 room offset +40: manager 3 at +50, manager 4 at +30.
        for (int i = 1; i <= 6; i++) {
            picks.add(pick(1, 2 * i - 1, 1, 20));
            picks.add(pick(1, 2 * i, 2, 0));
            picks.add(pick(2, 2 * i - 1, 3, 50));
            picks.add(pick(2, 2 * i, 4, 30));
        }
        ProfileService.Fit f = fit(picks);

        assertEquals(10.0, f.relativeReachBias().get(1L), EPS);
        assertEquals(-10.0, f.relativeReachBias().get(2L), EPS);
        assertEquals(10.0, f.relativeReachBias().get(3L), EPS);
        assertEquals(-10.0, f.relativeReachBias().get(4L), EPS);
        // The absolute figure still ranks manager 4 well above manager 1: the artifact removed.
        assertTrue(f.empiricalReachBias().get(4L) > f.empiricalReachBias().get(1L) + 5);
        assertTrue(f.relativeReachBias().get(4L) < f.relativeReachBias().get(1L));
    }

    @Test
    void aManagerPickingExactlyAtTheRoomAverageIsZero() {
        List<DraftRepository.CompletedPick> picks = new ArrayList<>();
        for (int i = 1; i <= 4; i++) {
            picks.add(pick(1, 3 * i - 2, 1, 5));
            picks.add(pick(1, 3 * i - 1, 2, 5));
            picks.add(pick(1, 3 * i, 3, 5));
        }
        ProfileService.Fit f = fit(picks);
        for (long m = 1; m <= 3; m++) assertEquals(0.0, f.relativeReachBias().get(m), EPS);
    }

    @Test
    void theEngineProfileIsStillTheShrunkAbsoluteFigure() {
        List<DraftRepository.CompletedPick> picks = new ArrayList<>();
        for (int i = 1; i <= 6; i++) {
            picks.add(pick(1, 2 * i - 1, 1, 20));
            picks.add(pick(1, 2 * i, 2, 0));
        }
        ProfileService.Fit f = fit(picks);
        // leagueMean 10, one draft observed, k=4 -> w = 1/5.
        assertEquals(new ShrinkageProperties(4.0).shrink(20, 10, 1), f.profiles().get(1L).reachBias(), EPS);
        assertEquals(new ShrinkageProperties(4.0).shrink(0, 10, 1), f.profiles().get(2L).reachBias(), EPS);
        assertEquals(20.0, f.empiricalReachBias().get(1L), EPS);
    }

    @Test
    void standardErrorIsSampleSdOverRootN() {
        Map<Long, Double> rel = new HashMap<>();
        Map<Long, Double> se = new HashMap<>();
        // m1 [4, 8], m2 [0, 0], m3 [3]. Room mean = 15/5 = 3.
        ProfileService.computeRelativeReach(List.of(
                new ScoredPick(1, 1, 4), new ScoredPick(1, 1, 8),
                new ScoredPick(2, 1, 0), new ScoredPick(2, 1, 0),
                new ScoredPick(3, 1, 3)), rel, se);
        // m1 residuals [1, 5]: mean 3, sample SD sqrt(8), SE = sqrt(8)/sqrt(2) = 2.
        assertEquals(3.0, rel.get(1L), EPS);
        assertEquals(2.0, se.get(1L), EPS);
        // m2 residuals [-3, -3]: SE 0.
        assertEquals(-3.0, rel.get(2L), EPS);
        assertEquals(0.0, se.get(2L), EPS);
        // m3 has one pick: a number, but no standard error.
        assertEquals(0.0, rel.get(3L), EPS);
        assertFalse(se.containsKey(3L));
    }

    @Test
    void aManagerWithNoScoreablePicksHasNeitherFigure() {
        List<DraftRepository.CompletedPick> picks = new ArrayList<>();
        picks.add(pick(1, 1, 1, 5));
        picks.add(new DraftRepository.CompletedPick(1, 2, 1, 1, 2L, 1002L, null, 2, 6));
        ProfileService.Fit f = fit(picks);
        assertTrue(f.relativeReachBias().containsKey(1L));
        assertFalse(f.relativeReachBias().containsKey(2L));
        assertFalse(f.relativeReachStdErr().containsKey(2L));
    }
}
