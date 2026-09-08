package com.ballknowers.draftsim.ingest;

import com.ballknowers.draftsim.domain.Player;
import com.ballknowers.draftsim.domain.Position;
import com.ballknowers.draftsim.domain.Sport;
import com.ballknowers.draftsim.store.BoardRepository;
import com.ballknowers.draftsim.store.PlayerRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * multi-sport-and-rebrand.md Phase 5: PlayerIngestService.fantasyPositions()
 * gained basketball cases via the sport-aware Position.fromSleeper(String,
 * Sport) overload. This pins the one case that mattered most: Sleeper's nba
 * player dump carries ~30 entries whose fantasy_positions is exactly ["DEF"]
 * (verified 2026-09-08), and those must be dropped as not fantasy-relevant --
 * NOT mapped onto football's Position.DEF, which is what a sport-blind
 * fromSleeper would have done.
 */
@ExtendWith(MockitoExtension.class)
class PlayerIngestServiceTest {

    @Mock private SleeperClient sleeper;
    @Mock private PlayerRepository players;
    @Mock private BoardRepository boards;

    @SuppressWarnings("unchecked")
    private List<Player> capturedPlayers() {
        ArgumentCaptor<List<Player>> captor = ArgumentCaptor.forClass(List.class);
        verify(players).upsertAll(org.mockito.ArgumentMatchers.eq(Sport.NBA), captor.capture());
        return captor.getValue();
    }

    @Test
    void nbaDefOnlyEntriesAreDroppedRatherThanMappedOntoFootballsDef() {
        Map<String, Map<String, Object>> raw = Map.of(
                "1658", nbaRecordWithoutRank("Nikola Jokic", List.of("C"), 1),
                "junk1", nbaRecordWithoutRank("Not Fantasy Relevant", List.of("DEF"), 5000));
        when(sleeper.allPlayers("nba")).thenReturn(raw);
        when(players.idsBySleeperId(Sport.NBA)).thenReturn(Map.of("1658", 100L));

        PlayerIngestService service = new PlayerIngestService(sleeper, players, boards);
        PlayerIngestService.Result result = service.ingest(Sport.NBA);

        assertEquals(1, result.playersWritten(), "the DEF-only nba entry must not be written at all");

        List<Player> written = capturedPlayers();
        assertEquals(1, written.size());
        assertEquals("Nikola Jokic", written.get(0).name());
        assertEquals(List.of(Position.C), written.get(0).positions());
        assertTrue(written.stream().noneMatch(p -> p.positions().contains(Position.DEF)),
                "no nba player should ever carry football's DEF position");
    }

    @Test
    void nbaMultiPositionPlayersKeepEveryEligiblePosition() {
        Map<String, Map<String, Object>> raw = Map.of(
                "1970", nbaRecordWithoutRank("Luka Doncic", List.of("PG", "SG"), 2));
        when(sleeper.allPlayers("nba")).thenReturn(raw);
        when(players.idsBySleeperId(Sport.NBA)).thenReturn(Map.of("1970", 200L));

        PlayerIngestService service = new PlayerIngestService(sleeper, players, boards);
        service.ingest(Sport.NBA);

        List<Player> written = capturedPlayers();
        assertEquals(1, written.size());
        assertEquals(List.of(Position.PG, Position.SG), written.get(0).positions());
    }

    private static Map<String, Object> nbaRecordWithoutRank(String fullName, List<String> fantasyPositions,
                                                            int searchRank) {
        return Map.of("full_name", fullName, "fantasy_positions", fantasyPositions, "search_rank", searchRank);
    }
}
