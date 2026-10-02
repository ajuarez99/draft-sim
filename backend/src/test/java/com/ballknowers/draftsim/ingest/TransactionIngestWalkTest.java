package com.ballknowers.draftsim.ingest;

import com.ballknowers.draftsim.domain.Sport;
import com.ballknowers.draftsim.store.LeagueRepository;
import com.ballknowers.draftsim.store.LeagueTransactionRepository;
import com.ballknowers.draftsim.store.LeagueWeekFetchRepository;
import com.ballknowers.draftsim.store.ManagerRepository;
import com.ballknowers.draftsim.store.RosterSeasonRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

/**
 * Which weeks the transaction walk fetches, and how it records them (2026-10-02).
 * In NFL {@code last_scored_leg} is the last completed week; the week being played
 * ({@code leg}) already has free-agent moves on Sleeper, so the walk must reach it.
 */
@ExtendWith(MockitoExtension.class)
class TransactionIngestWalkTest {

    private static final String ID = "L-tx";

    @Mock private SleeperClient sleeper;
    @Mock private LeagueRepository leagues;
    @Mock private LeagueTransactionRepository transactions;
    @Mock private ManagerRepository managers;
    @Mock private RosterSeasonRepository rosterSeasons;
    @Mock private LeagueWeekFetchRepository weekFetches;

    private TransactionIngestService service(int lastScoredLeg, int leg, Set<Integer> finalWeeks) {
        when(leagues.bySleeperId(ID)).thenReturn(Optional.of(new LeagueRepository.LeagueRow(
                7L, Sport.NFL, ID, "IT tx", 2026, 12, List.of(), 1.0, null, "in_season")));
        when(sleeper.league(ID)).thenReturn(Map.of("settings", Map.of("last_scored_leg", lastScoredLeg, "leg", leg)));
        when(weekFetches.finalWeeks(7L, LeagueWeekFetchRepository.TRANSACTIONS)).thenReturn(finalWeeks);
        when(sleeper.transactions(eq(ID), anyInt())).thenReturn(List.of());
        return new TransactionIngestService(sleeper, leagues, transactions, managers, rosterSeasons, weekFetches);
    }

    @Test
    void theWeekBeingPlayedIsFetchedAndNeverRecordedFinal() {
        // Measured shape: (Foot) Ball Knowers 2026 on 2026-10-02, leg 4 / last_scored_leg 3.
        service(3, 4, Set.of(1, 2)).ingest(ID);

        verify(sleeper, never()).transactions(ID, 1);
        verify(sleeper, never()).transactions(ID, 2);
        verify(sleeper).transactions(ID, 3);
        verify(sleeper).transactions(ID, 4);
        verify(sleeper, never()).transactions(ID, 5);
        verify(weekFetches).record(eq(7L), eq(LeagueWeekFetchRepository.TRANSACTIONS), eq(3), any(), eq(true));
        verify(weekFetches).record(eq(7L), eq(LeagueWeekFetchRepository.TRANSACTIONS), eq(4), any(), eq(false));
    }

    @Test
    void aCompleteSeasonWalksToItsLastWeekAndNoFurther() {
        // Complete seasons read leg == last_scored_leg (NFL 2025: 17/17).
        service(17, 17, Set.of()).ingest(ID);

        verify(sleeper).transactions(ID, 17);
        verify(sleeper, never()).transactions(ID, 18);
    }

    @Test
    void aMissingLegFallsBackToLastScoredLeg() {
        service(3, 0, Set.of()).ingest(ID);

        verify(sleeper).transactions(ID, 3);
        verify(sleeper, never()).transactions(ID, 4);
    }
}
