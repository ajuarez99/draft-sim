package com.ballknowers.draftsim.engine;

import com.ballknowers.draftsim.config.ScoringProperties;
import com.ballknowers.draftsim.domain.*;
import com.ballknowers.draftsim.profile.ManagerProfile;
import com.ballknowers.draftsim.profile.PositionalPriors;
import com.ballknowers.draftsim.sport.FootballRules;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/**
 * MockDraftEngine.advanceUntilUserOrEnd is the mock room's own orchestration
 * (claude/next-features-roadmap.md §4, Phase 3) -- "run() has no concept of
 * stop early for a human," so this is genuinely new logic, even though its
 * inner decision is 100% the same PickDecider DraftSimulator.run() uses.
 */
class MockDraftEngineTest {

    private static final ScoringProperties.Sport CFG = new ScoringProperties.Sport(
            new ScoringProperties.Weights(1.0, 0.35, 0.5, 0.25),
            12.0, 3.0, 60.0, 0.15, 6, 0.85,
            Map.of("K", 3, "DEF", 4), 1.0, 30);

    private final MockDraftEngine engine = new MockDraftEngine();

    private static List<BoardEntry> board(int n) {
        Position[] cycle = {Position.RB, Position.WR, Position.WR, Position.RB, Position.TE, Position.QB};
        List<BoardEntry> out = new ArrayList<>(n);
        for (int i = 0; i < n; i++) {
            out.add(new BoardEntry(new Player(i + 1L, Sport.NFL, "s" + i, "Player " + i,
                    List.of(cycle[i % cycle.length]), "FA", "Active", null, null, null), i + 1.0, i / 6 + 1));
        }
        return out;
    }

    private static DraftContext ctx(int teams, int rounds, Map<Integer, Long> completed) {
        LeagueSettings settings = new LeagueSettings(teams, rounds,
                List.of("QB", "RB", "RB", "WR", "WR", "TE", "FLEX", "FLEX", "K", "DEF", "BN", "BN"), 1.0);
        Map<Integer, ManagerProfile> profiles = new HashMap<>();
        for (int s = 1; s <= teams; s++) profiles.put(s, ManagerProfile.neutral(s, "seat " + s));
        return new DraftContext(board(400), settings, profiles, PositionalPriors.uniform(),
                new FootballRules(new ScoringProperties(CFG)), CFG,
                completed.keySet().stream().sorted().toList(), completed);
    }

    @Test
    void stopsExactlyAtTheNextUserSeatAndDecidesEveryBotPickBeforeIt() {
        int teams = 8;
        List<SeatSpec> seats = List.of(SeatSpec.user(5, null));   // slots 1-4 are bots, ahead of the user

        MockDraftEngine.AdvanceResult r = engine.advanceUntilUserOrEnd(
                ctx(teams, 15, Map.of()), seats, 1L);

        assertEquals(5, r.nextPickNo(), "must stop at pick 5, the user's first turn");
        assertFalse(r.complete());
        assertEquals(4, r.newPicks().size(), "picks 1-4 (bots) must all be decided");
        for (MockDraftEngine.Decision d : r.newPicks()) {
            assertTrue(d.pickNo() < 5, "no pick at or after the user's turn should be decided");
            assertEquals(SeatSpec.Type.BOT, d.seatType());
        }
        Set<Integer> decided = new HashSet<>();
        for (var d : r.newPicks()) decided.add(d.pickNo());
        assertEquals(Set.of(1, 2, 3, 4), decided);
    }

    @Test
    void userHoldingPickOneStopsImmediatelyWithNothingDecided() {
        MockDraftEngine.AdvanceResult r = engine.advanceUntilUserOrEnd(
                ctx(8, 15, Map.of()), List.of(SeatSpec.user(1, null)), 1L);

        assertEquals(1, r.nextPickNo());
        assertFalse(r.complete());
        assertTrue(r.newPicks().isEmpty(), "the user's own first pick must not be auto-decided");
    }

    @Test
    void aDraftWithNoUserSeatRunsToCompletion() {
        int teams = 8, rounds = 15;
        MockDraftEngine.AdvanceResult r = engine.advanceUntilUserOrEnd(
                ctx(teams, rounds, Map.of()), List.of(), 1L);   // every seat falls back to BOT

        assertTrue(r.complete());
        assertEquals(teams * rounds, r.newPicks().size());
        assertTrue(r.nextPickNo() > teams * rounds);

        Set<Long> seen = new HashSet<>();
        for (var d : r.newPicks()) {
            assertTrue(seen.add(d.player().player().id()), "player " + d.player().player().id() + " drafted twice");
        }
    }

    @Test
    void alreadyCompletedPicksAreNeverRedecided() {
        int teams = 8;
        // Pick 2 was already decided by an earlier advance/submitPick call.
        Map<Integer, Long> completed = Map.of(2, 50L);
        List<SeatSpec> seats = List.of(SeatSpec.user(5, null));

        MockDraftEngine.AdvanceResult r = engine.advanceUntilUserOrEnd(
                ctx(teams, 15, completed), seats, 1L);

        Set<Integer> decidedPickNos = new HashSet<>();
        for (var d : r.newPicks()) {
            decidedPickNos.add(d.pickNo());
            assertNotEquals(50L, d.player().player().id(), "player 50 is already drafted and must not be redrafted");
        }
        assertFalse(decidedPickNos.contains(2), "pick 2 was already completed and must not be redecided");
        assertEquals(Set.of(1, 3, 4), decidedPickNos);
    }

    /**
     * Simulates a full 8-team mock draft by repeatedly calling advance and,
     * every time it stops on the user's turn, feeding back the best remaining
     * player as "the user's pick" -- exactly the loop MockDraftService runs
     * across separate HTTP requests, just without the DB round-trip. Confirms
     * the engine can be driven start-to-finish with no duplicate or skipped pick.
     */
    @Test
    void aFullMockDraftDrivenPickByPickCompletesWithNoDuplicates() {
        int teams = 8, rounds = 15, userSlot = 3;
        List<SeatSpec> seats = List.of(SeatSpec.user(userSlot, null));
        Map<Integer, Long> completed = new HashMap<>();
        List<BoardEntry> fullBoard = board(400);
        Set<Long> drafted = new HashSet<>();

        MockDraftEngine.AdvanceResult r;
        do {
            DraftContext ctx = ctx(teams, rounds, completed);
            r = engine.advanceUntilUserOrEnd(ctx, seats, 7L);
            for (var d : r.newPicks()) {
                completed.put(d.pickNo(), d.player().player().id());
                assertTrue(drafted.add(d.player().player().id()), "duplicate draft of player " + d.player().player().id());
            }
            if (!r.complete()) {
                // "the user's pick": best remaining player by board position.
                BoardEntry best = fullBoard.stream()
                        .filter(e -> !drafted.contains(e.player().id()))
                        .findFirst().orElseThrow();
                completed.put(r.nextPickNo(), best.player().id());
                assertTrue(drafted.add(best.player().id()));
            }
        } while (!r.complete());

        assertEquals(teams * rounds, completed.size(), "every pick must be filled exactly once");
        assertEquals(teams * rounds, drafted.size());
    }
}
