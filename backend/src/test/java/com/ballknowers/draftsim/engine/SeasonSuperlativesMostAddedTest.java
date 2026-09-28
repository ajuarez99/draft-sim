package com.ballknowers.draftsim.engine;

import com.ballknowers.draftsim.engine.SeasonSuperlativesService.Superlative;
import com.ballknowers.draftsim.store.LeagueTransactionRepository;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * JABARI_SMITH_JR's result states (specs/008-season-superlatives US7), run
 * against in-memory rows -- no Postgres. The case this file exists for is spec
 * clarification 18: a top of one add names nobody, because early in a season
 * that's a tie between every player who was picked up at all.
 */
class SeasonSuperlativesMostAddedTest {

    private static int seq = 0;

    private static LeagueTransactionRepository.Row faAdd(int week, String playerId, int rosterId) {
        return new LeagueTransactionRepository.Row(1L, 2026, week, "tx" + (seq++), "FREE_AGENT", "complete",
                rosterId, null, "{\"" + playerId + "\": " + rosterId + "}", "{}", null, null);
    }

    private static Superlative award(List<LeagueTransactionRepository.Row> rows) {
        return SeasonSuperlativesService.mostAddedSuperlative("L", rows, 2, Map.of(), Map.of(), Map.of());
    }

    @Test
    void aTopOfOneAddNamesNobodyEvenWithManyPlayersAdded() {
        List<LeagueTransactionRepository.Row> rows = new ArrayList<>();
        for (int i = 0; i < 25; i++) rows.add(faAdd(1 + i % 2, "p" + i, 1 + i % 12));

        Superlative s = award(rows);

        assertTrue(s.available());
        assertEquals("nobody's been picked up twice yet", s.emptyReason());
        assertTrue(s.playerHolders().isEmpty(), "25 one-add players are not 25 winners");
        assertTrue(s.holders().isEmpty());
        assertTrue(s.detail().isEmpty());
        assertNull(s.value());
    }

    @Test
    void aTopOfTwoAddsIsNamed() {
        Superlative s = award(List.of(faAdd(1, "p1", 3), faAdd(2, "p1", 5), faAdd(1, "p2", 4)));

        assertNull(s.emptyReason());
        assertEquals(1, s.playerHolders().size());
        assertEquals("p1", s.playerHolders().get(0).playerId());
        assertEquals(2.0, s.value());
        assertEquals(2, s.detail().size());
    }

    @Test
    void noCountedAddsKeepsItsOwnReason() {
        Superlative s = award(List.of(faAdd(9, "p1", 3)));  // outside weeks 1..2

        assertEquals("nobody's been picked up yet", s.emptyReason());
    }
}
