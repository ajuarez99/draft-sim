package com.ballknowers.draftsim.ingest;

import com.ballknowers.draftsim.store.LeagueBracketRepository;
import com.ballknowers.draftsim.store.LeagueLosersBracketRepository;
import com.ballknowers.draftsim.store.LeagueRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

/** specs/034 T004: what a bracket fetch does and, as important, what it must not do. */
@ExtendWith(MockitoExtension.class)
class BracketIngestServiceTest {

    @Mock private SleeperClient sleeper;
    @Mock private LeagueBracketRepository brackets;
    @Mock private LeagueLosersBracketRepository losersBrackets;
    @Mock private LeagueRepository leagues;

    private BracketIngestService service() {
        return new BracketIngestService(sleeper, brackets, losersBrackets, leagues);
    }

    private static Map<String, Object> row(Object m, Object r, Object t1, Object t2, Object w, Object l, Object p) {
        Map<String, Object> x = new HashMap<>();
        x.put("m", m); x.put("r", r); x.put("t1", t1); x.put("t2", t2); x.put("w", w); x.put("l", l); x.put("p", p);
        return x;
    }

    @Test
    void mapsEveryFieldAndKeepsNullSlots() {
        when(brackets.isComplete(5L)).thenReturn(false);
        when(sleeper.winnersBracket("S5")).thenReturn(List.of(
                row(1, 1, 8, 9, 9, 8, null),
                row(3, 2, 10, null, null, null, null),
                row(6, 3, 1, 3, 1, 3, 1)));

        service().ingestSeason(5L, "S5", false);

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<LeagueBracketRepository.Match>> cap = ArgumentCaptor.forClass(List.class);
        verify(brackets).replaceForLeague(eq(5L), cap.capture());
        assertEquals(new LeagueBracketRepository.Match(5L, 3, 2, 10, null, null, null, null), cap.getValue().get(1));
        assertEquals(1, cap.getValue().get(2).placement());
    }

    @Test
    void aNullBodyIsNoDataNotAnNpeAndNeverWrites() {
        when(brackets.isComplete(5L)).thenReturn(false);
        when(sleeper.winnersBracket("S5")).thenReturn(null);
        assertDoesNotThrow(() -> service().ingestSeason(5L, "S5", false));
        verify(brackets, never()).replaceForLeague(anyLong(), anyList());
    }

    @Test
    void aCompleteStoredBracketIsSkippedWithoutACallUnlessForced() {
        when(brackets.isComplete(5L)).thenReturn(true);
        when(losersBrackets.isComplete(5L)).thenReturn(true);
        when(brackets.count(5L)).thenReturn(7);

        var r = service().ingestSeason(5L, "S5", false);

        assertTrue(r.skipped());
        assertEquals(7, r.matches());
        verifyNoInteractions(sleeper);

        when(sleeper.winnersBracket("S5")).thenReturn(List.of(row(1, 1, 8, 9, 9, 8, null)));
        assertFalse(service().ingestSeason(5L, "S5", true).skipped());
        verify(sleeper).winnersBracket("S5");
        verify(sleeper).losersBracket("S5");
    }

    @Test
    void ingestIfMissingFetchesWhenEmptyOrIncompleteAndNeverWhenComplete() {
        // empty
        when(brackets.isComplete(5L)).thenReturn(false);
        when(sleeper.winnersBracket("S5")).thenReturn(List.of());
        service().ingestIfMissing(5L, "S5");
        verify(sleeper, times(1)).winnersBracket("S5");

        // B1: rows exist but the final has no winner yet -> exactly one more call
        when(brackets.isComplete(7L)).thenReturn(false);
        when(brackets.count(7L)).thenReturn(7);
        when(sleeper.winnersBracket("S7")).thenReturn(List.of(row(6, 3, 1, 3, 1, 3, 1)));
        service().ingestIfMissing(7L, "S7");
        verify(sleeper, times(1)).winnersBracket("S7");
        verify(brackets).replaceForLeague(eq(7L), anyList());

        // complete -> none
        when(brackets.isComplete(6L)).thenReturn(true);
        service().ingestIfMissing(6L, "S6");
        verify(sleeper, never()).winnersBracket("S6");
    }

    /** B7: a league with no playoffs is never asked. */
    @Test
    void ingestIfMissingSkipsALeagueWithoutPlayoffs() {
        when(brackets.isComplete(8L)).thenReturn(false);
        when(leagues.playoffFormat(8L)).thenReturn(java.util.Optional.of(
                new LeagueRepository.PlayoffFormat(0, 0, 0, false, false, 0)));
        service().ingestIfMissing(8L, "S8");
        verifyNoInteractions(sleeper);
    }

    @Test
    void aFetchFailurePropagates() {
        when(brackets.isComplete(5L)).thenReturn(false);
        when(sleeper.winnersBracket("S5")).thenThrow(new IllegalStateException("down"));
        assertThrows(IllegalStateException.class, () -> service().ingestSeason(5L, "S5", false));
    }

    /** Spec 047 T007: the losers bracket is mapped field-for-field into its own table, never the winners one. */
    @Test
    void losersBracketIsFetchedAndStoredInItsOwnTable() {
        when(brackets.isComplete(5L)).thenReturn(true);       // winners already stored and frozen
        when(losersBrackets.isComplete(5L)).thenReturn(false); // a season stored before V40
        when(sleeper.losersBracket("S5")).thenReturn(List.of(
                row(1, 1, 6, 7, 7, 6, null),
                row(4, 3, 2, 5, 2, 5, 1)));

        service().ingestSeason(5L, "S5", false);

        verify(sleeper, never()).winnersBracket("S5");
        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<LeagueLosersBracketRepository.Match>> cap = ArgumentCaptor.forClass(List.class);
        verify(losersBrackets).replaceForLeague(eq(5L), cap.capture());
        assertEquals(new LeagueLosersBracketRepository.Match(5L, 4, 3, 2, 5, 2, 5, 1), cap.getValue().get(1));
        verify(brackets, never()).replaceForLeague(anyLong(), anyList());
    }

    @Test
    void anEmptyOrNullLosersAnswerNeverDeletes() {
        when(brackets.isComplete(5L)).thenReturn(true);
        when(losersBrackets.isComplete(5L)).thenReturn(false);
        when(sleeper.losersBracket("S5")).thenReturn(List.of());
        service().ingestSeason(5L, "S5", false);
        when(sleeper.losersBracket("S5")).thenReturn(null);
        service().ingestSeason(5L, "S5", false);
        verify(losersBrackets, never()).replaceForLeague(anyLong(), anyList());
    }

    @Test
    void ingestIfMissingFetchesOnlyTheMissingBracket() {
        when(brackets.isComplete(5L)).thenReturn(true);
        when(losersBrackets.isComplete(5L)).thenReturn(false);
        when(sleeper.losersBracket("S5")).thenReturn(List.of(row(4, 3, 2, 5, 2, 5, 1)));
        service().ingestIfMissing(5L, "S5");
        verify(sleeper, never()).winnersBracket("S5");
        verify(sleeper).losersBracket("S5");

        when(brackets.isComplete(6L)).thenReturn(true);
        when(losersBrackets.isComplete(6L)).thenReturn(true);
        service().ingestIfMissing(6L, "S6");
        verify(sleeper, never()).losersBracket("S6");
    }
}
