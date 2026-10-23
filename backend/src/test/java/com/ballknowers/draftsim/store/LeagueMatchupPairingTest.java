package com.ballknowers.draftsim.store;

import com.ballknowers.draftsim.store.LeagueMatchupRepository.Pair;
import com.ballknowers.draftsim.store.LeagueMatchupRepository.ScoredRow;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Spec 045 A12 / T003: the live week pairs by the same rule as {@code pairedWithScores}'s self-join
 * ({@code a.roster_id < b.roster_id}, shared {@code matchup_id}, null skipped, ordered by the lower roster).
 */
class LeagueMatchupPairingTest {

    private static ScoredRow r(int roster, Integer matchup, double pts) {
        return new ScoredRow(roster, matchup, pts);
    }

    @Test
    void lowerRosterIdIsHomeAndOrderFollowsIt() {
        List<Pair> pairs = LeagueMatchupRepository.pair(List.of(
                r(5, 1, 192.5), r(3, 2, 150), r(1, 1, 201.5), r(2, 2, 160)));
        assertThat(pairs).extracting(p -> p.a().rosterId() + "-" + p.b().rosterId())
                .containsExactly("1-5", "2-3");
        assertThat(pairs.getFirst().a().points()).isEqualTo(201.5);
    }

    @Test
    void aNullMatchupIdPairsWithNothing_notEvenWithAnotherNull() {
        List<Pair> pairs = LeagueMatchupRepository.pair(List.of(r(1, null, 10), r(2, null, 20), r(3, 7, 30)));
        assertThat(pairs).isEmpty();
    }

    @Test
    void aThreeRosterMatchupYieldsAllThreePairs_asTheSqlSelfJoinDoes() {
        List<Pair> pairs = LeagueMatchupRepository.pair(List.of(r(4, 1, 1), r(2, 1, 2), r(9, 1, 3), r(1, null, 0)));
        assertThat(pairs).extracting(p -> p.a().rosterId() + "-" + p.b().rosterId())
                .containsExactly("2-4", "2-9", "4-9");
    }

    @Test
    void anOddRosterOutIsLeftOut() {
        List<Pair> pairs = LeagueMatchupRepository.pair(List.of(r(1, 1, 1), r(2, 1, 2), r(3, 2, 3)));
        assertThat(pairs).hasSize(1);
    }
}
