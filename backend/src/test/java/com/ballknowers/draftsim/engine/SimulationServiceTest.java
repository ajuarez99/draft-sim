package com.ballknowers.draftsim.engine;

import com.ballknowers.draftsim.config.BoardProperties;
import com.ballknowers.draftsim.config.ScoringProperties;
import com.ballknowers.draftsim.domain.*;
import com.ballknowers.draftsim.ingest.BoardService;
import com.ballknowers.draftsim.profile.PositionalPriors;
import com.ballknowers.draftsim.profile.ProfileService;
import com.ballknowers.draftsim.sport.SportRules;
import com.ballknowers.draftsim.store.DraftRepository;
import com.ballknowers.draftsim.store.LeagueRepository;
import com.ballknowers.draftsim.store.PlayerRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/**
 * resolveStartState is private, so it is only reachable through simulate().
 * Everything downstream of it — MonteCarloRunner chief among them — is mocked
 * away, so this test is really only about the one thing it must pin down: a
 * startState entry whose sleeperId does not resolve to a known player is
 * dropped silently rather than corrupting the request or throwing.
 * claude/reactive-resimulation.md's frontend defensive check (DraftView's
 * choosePick, which validates the whole locked prefix after a resim) depends
 * on this staying fail-soft.
 */
@ExtendWith(MockitoExtension.class)
class SimulationServiceTest {

    @Mock private BoardService boards;
    @Mock private ProfileService profiles;
    @Mock private DraftRepository drafts;
    @Mock private LeagueRepository leagues;
    @Mock private PlayerRepository players;
    @Mock private SportRules rules;
    @Mock private MonteCarloRunner runner;

    private static final ScoringProperties.Sport CFG = new ScoringProperties.Sport(
            new ScoringProperties.Weights(1.0, 0.35, 0.5, 0.25),
            12.0, 3.0, 60.0, 0.15, 6, 0.85,
            Map.of("K", 3, "DEF", 4), 1.0, 30);

    @Test
    void resolveStartStateDropsAnUnresolvableSleeperIdWithoutThrowing() {
        SimulationService service = new SimulationService(
                boards, profiles, drafts, leagues, players,
                new BoardProperties(0.5, List.of(), 14, 30), runner,
                new DraftContextFactory(rules, new ScoringProperties(CFG)));

        DraftRepository.DraftRow draft = new DraftRepository.DraftRow(
                1L, 10L, "sleeper-draft-xyz", 2026, 1, 2, "pre_draft", Map.of());
        LeagueRepository.LeagueRow league = new LeagueRepository.LeagueRow(
                10L, "sleeper-league", "Test League", 2026, 2, List.of("QB", "BN"), 0.5);
        // Two entries for a 2-team, 1-round draft: DraftContextFactory rejects a
        // board shorter than the draft it is asked to run, since the last picks
        // would otherwise be chosen from an empty pool.
        List<BoardEntry> board = List.of(
                new BoardEntry(new Player(1L, Sport.NFL, "board-player", "Board Player",
                        List.of(Position.WR), "FA", "Active", null, null, null), 1.0, 1),
                new BoardEntry(new Player(2L, Sport.NFL, "board-player-2", "Board Player Two",
                        List.of(Position.RB), "FA", "Active", null, null, null), 2.0, 1));

        when(drafts.bySleeperId("sleeper-draft-xyz")).thenReturn(Optional.of(draft));
        when(leagues.all()).thenReturn(List.of(league));
        when(boards.currentBoard(Sport.NFL)).thenReturn(board);
        when(profiles.fit(Sport.NFL))
                .thenReturn(new ProfileService.Fit(Map.of(), PositionalPriors.uniform(), 0, Map.of()));
        when(players.idsBySleeperId(Sport.NFL)).thenReturn(Map.of("resolvable-id", 501L));

        // pick 1 resolves; pick 2's sleeperId is not a known player at all.
        Map<Integer, String> startState = Map.of(1, "resolvable-id", 2, "unresolvable-id");
        SimulationRequest req = new SimulationRequest(
                "sleeper-draft-xyz", 1, 100, null, startState, null);

        assertDoesNotThrow(() -> service.simulate(req, null));

        ArgumentCaptor<DraftContext> ctxCaptor = ArgumentCaptor.forClass(DraftContext.class);
        verify(runner).run(ctxCaptor.capture(), eq(1), eq(100), anyDouble(), anyLong(), any(), any());

        DraftContext ctx = ctxCaptor.getValue();
        assertEquals(Map.of(1, 501L), ctx.completedPicks(),
                "pick 2's unresolvable sleeperId should be dropped, not present and not thrown");
        assertEquals(List.of(1), ctx.completedPickNumbers());
    }
}
