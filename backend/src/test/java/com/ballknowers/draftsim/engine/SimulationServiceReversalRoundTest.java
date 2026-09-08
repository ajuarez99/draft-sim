package com.ballknowers.draftsim.engine;

import com.ballknowers.draftsim.config.BoardProperties;
import com.ballknowers.draftsim.config.ScoringProperties;
import com.ballknowers.draftsim.domain.*;
import com.ballknowers.draftsim.ingest.BoardService;
import com.ballknowers.draftsim.profile.PositionalPriors;
import com.ballknowers.draftsim.profile.ProfileService;
import com.ballknowers.draftsim.sport.FootballRules;
import com.ballknowers.draftsim.sport.SportRulesRegistry;
import com.ballknowers.draftsim.store.DraftRepository;
import com.ballknowers.draftsim.store.LeagueRepository;
import com.ballknowers.draftsim.store.PlayerRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.*;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.when;

/**
 * multi-sport-and-rebrand.md Phase 5 gave {@code DraftSlot} a correct
 * {@code isForward(round, reversalRound)} and made {@code LeagueIngestService}
 * persist {@code draft.reversal_round}, but left nothing threading that
 * persisted value into a running simulation -- every hot-path caller still
 * called the 2-/3-arg {@code DraftSlot} overloads that hardcode
 * {@code reversalRound = 0}. {@link com.ballknowers.draftsim.domain.DraftSlotReversalRoundTest}
 * already pins the maths; this pins the plumbing.
 *
 * Deliberately does NOT call {@link DraftSlot} anywhere in this file. It goes
 * through the real path a live request takes -- {@link SimulationService}
 * reads a {@link DraftRepository.DraftRow} with a nonzero
 * {@code reversalRound}, threads it through {@link LeagueRepository#toSettings}
 * into {@link LeagueSettings}, into a real {@link DraftContext}, through a
 * real (unmocked) {@link MonteCarloRunner} and {@link DraftSimulator} -- and
 * asserts on the resulting board's own {@code slot} field. A revert of the
 * wiring at ANY of those hops (SimulationService dropping
 * {@code draft.reversalRound()}, {@code toSettings} ignoring its 3rd argument,
 * {@code LeagueSettings} losing the field, or {@code DraftSimulator}/
 * {@code MonteCarloRunner} going back to the plain {@code DraftSlot} overloads)
 * makes this fail, even though the maths in {@code DraftSlot} itself stays
 * perfectly correct -- which is exactly the gap a test that only calls
 * {@code DraftSlot} directly cannot catch.
 */
@ExtendWith(MockitoExtension.class)
class SimulationServiceReversalRoundTest {

    @Mock private BoardService boards;
    @Mock private ProfileService profiles;
    @Mock private DraftRepository drafts;
    @Mock private LeagueRepository leagues;
    @Mock private PlayerRepository players;

    private static final int TEAMS = 4;
    private static final int ROUNDS = 4;
    private static final int REVERSAL_ROUND = 3;

    private static final List<String> SLOTS = List.of(
            "QB", "RB", "RB", "WR", "WR", "TE", "FLEX", "FLEX", "K", "DEF",
            "BN", "BN", "BN", "BN", "BN");

    private static final ScoringProperties.SportScoring CFG = new ScoringProperties.SportScoring(
            new ScoringProperties.Weights(1.0, 0.35, 0.5, 0.25),
            12.0, 3.0, 60.0, 0.15, 6, 0.85,
            Map.of("K", 3, "DEF", 4), 1.0, 30);

    private static List<BoardEntry> board(int n) {
        Position[] cycle = {
                Position.RB, Position.WR, Position.WR, Position.RB, Position.TE,
                Position.WR, Position.RB, Position.QB, Position.WR, Position.RB};
        List<BoardEntry> out = new ArrayList<>(n);
        for (int i = 0; i < n; i++) {
            out.add(new BoardEntry(new Player(i + 1L, Sport.NFL, "s" + i, "Player " + i,
                    List.of(cycle[i % cycle.length]), "FA", "Active", null, null, null), i + 1.0, i / 6 + 1));
        }
        return out;
    }

    /**
     * Real, unmocked engine stack -- MonteCarloRunner and DraftSimulator both
     * need to actually run for this test to mean anything.
     */
    private SimulationService realService() {
        return new SimulationService(
                boards, profiles, drafts, leagues, players,
                new BoardProperties(0.5, Map.of(), 14, 30),
                new MonteCarloRunner(),
                new DraftContextFactory(
                        new SportRulesRegistry(List.of(new FootballRules(new ScoringProperties(CFG, null)))),
                        new ScoringProperties(CFG, null)));
    }

    @Test
    void aPersistedReversalRoundActuallyReversesRoundThreeAndFourOfARealSimulation() {
        SimulationService service = realService();

        // reversal_round = 3 on a real, persisted draft row -- exactly the
        // shape LeagueIngestService.ingestDraft writes for the 2026 Ball
        // Knowers NBA draft (Sport.NFL here only because FootballRules is the
        // simplest real SportRules to run a live simulation against; the
        // reversal-round plumbing itself is sport-agnostic).
        DraftRepository.DraftRow draft = new DraftRepository.DraftRow(
                1L, 10L, "sleeper-draft-reversal", 2026, ROUNDS, TEAMS, "pre_draft", Map.of(), REVERSAL_ROUND);
        LeagueRepository.LeagueRow league = new LeagueRepository.LeagueRow(
                10L, Sport.NFL, "sleeper-league-reversal", "Reversal League", 2026, TEAMS, SLOTS, 1.0, null);

        when(drafts.bySleeperId("sleeper-draft-reversal")).thenReturn(Optional.of(draft));
        when(leagues.all()).thenReturn(List.of(league));
        when(boards.currentBoard(Sport.NFL)).thenReturn(board(200));
        when(profiles.fit(Sport.NFL))
                .thenReturn(new ProfileService.Fit(Map.of(), PositionalPriors.uniform(Sport.NFL), 0, Map.of()));
        when(drafts.picks(1L)).thenReturn(List.of());

        SimulationRequest req = new SimulationRequest(
                "sleeper-draft-reversal", 1, 30, 1.0, null, null, 7L);

        SimulationResult result = service.simulate(req, null);

        Map<Integer, Integer> slotByPick = new HashMap<>();
        for (SimulationResult.PredictedPick p : result.board()) slotByPick.put(p.pickNo(), p.slot());

        // Fixture is DraftSlotReversalRoundTest's own, hand-traced reading of
        // reversalRound=3 at 4 teams -- reproduced here as literal expected
        // values, NOT by calling DraftSlot, so a dropped parameter anywhere
        // upstream cannot hide behind DraftSlot still being correct.
        //
        // Round 1 (picks 1-4) and round 2 (picks 5-8): unchanged from plain
        // snake, both < reversalRound.
        assertEquals(1, slotByPick.get(1), "round 1 pick 1");
        assertEquals(4, slotByPick.get(4), "round 1 pick 4");
        assertEquals(4, slotByPick.get(5), "round 2 pick 1 (reverse)");
        assertEquals(1, slotByPick.get(8), "round 2 pick 4 (reverse)");

        // Round 3 (picks 9-12): plain snake would run forward (1,2,3,4);
        // reversalRound=3 flips it to reverse (4,3,2,1).
        assertEquals(4, slotByPick.get(9), "round 3 pick 1 should be flipped to reverse");
        assertEquals(3, slotByPick.get(10), "round 3 pick 2 should be flipped to reverse");
        assertEquals(2, slotByPick.get(11), "round 3 pick 3 should be flipped to reverse");
        assertEquals(1, slotByPick.get(12), "round 3 pick 4 should be flipped to reverse");

        // Round 4 (picks 13-16): plain snake would run reverse (4,3,2,1);
        // reversalRound=3 flips it to forward (1,2,3,4).
        assertEquals(1, slotByPick.get(13), "round 4 pick 1 should be flipped to forward");
        assertEquals(2, slotByPick.get(14), "round 4 pick 2 should be flipped to forward");
        assertEquals(3, slotByPick.get(15), "round 4 pick 3 should be flipped to forward");
        assertEquals(4, slotByPick.get(16), "round 4 pick 4 should be flipped to forward");
    }

    /**
     * Companion to the test above: a draft with the ordinary, ingested-before-
     * Phase-5 {@code reversal_round = 0} must keep simulating as plain snake
     * end to end -- the same real MonteCarloRunner/DraftSimulator stack, same
     * shape, just without the flip. This is the guard against the opposite
     * mistake (accidentally reversing every draft, not just ones that ask for it).
     */
    @Test
    void aZeroReversalRoundStaysPlainSnakeEndToEnd() {
        SimulationService service = realService();

        DraftRepository.DraftRow draft = new DraftRepository.DraftRow(
                1L, 10L, "sleeper-draft-plain", 2026, ROUNDS, TEAMS, "pre_draft", Map.of(), 0);
        LeagueRepository.LeagueRow league = new LeagueRepository.LeagueRow(
                10L, Sport.NFL, "sleeper-league-plain", "Plain League", 2026, TEAMS, SLOTS, 1.0, null);

        when(drafts.bySleeperId("sleeper-draft-plain")).thenReturn(Optional.of(draft));
        when(leagues.all()).thenReturn(List.of(league));
        when(boards.currentBoard(Sport.NFL)).thenReturn(board(200));
        when(profiles.fit(Sport.NFL))
                .thenReturn(new ProfileService.Fit(Map.of(), PositionalPriors.uniform(Sport.NFL), 0, Map.of()));
        when(drafts.picks(1L)).thenReturn(List.of());

        SimulationRequest req = new SimulationRequest(
                "sleeper-draft-plain", 1, 30, 1.0, null, null, 7L);

        SimulationResult result = service.simulate(req, null);

        Map<Integer, Integer> slotByPick = new HashMap<>();
        for (SimulationResult.PredictedPick p : result.board()) slotByPick.put(p.pickNo(), p.slot());

        // Round 3 forward (1,2,3,4), round 4 reverse (4,3,2,1) -- plain snake,
        // no flip anywhere.
        assertEquals(1, slotByPick.get(9), "round 3 pick 1, plain snake forward");
        assertEquals(4, slotByPick.get(12), "round 3 pick 4, plain snake forward");
        assertEquals(4, slotByPick.get(13), "round 4 pick 1, plain snake reverse");
        assertEquals(1, slotByPick.get(16), "round 4 pick 4, plain snake reverse");
    }
}
