package com.ballknowers.draftsim.ingest;

import com.ballknowers.draftsim.domain.Player;
import com.ballknowers.draftsim.domain.Position;
import com.ballknowers.draftsim.domain.Sport;
import com.ballknowers.draftsim.store.BoardRepository;
import com.ballknowers.draftsim.store.PlayerRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.util.*;

/**
 * Pulls the Sleeper players dump (~5MB, meant to be fetched once a day) and
 * writes two things: the player table, and a board snapshot derived from
 * Sleeper's own search_rank.
 *
 * search_rank is a popularity/consensus ordering, not a true ADP: it is not
 * league-size aware and not scoring-format aware. It is used here because it
 * is exactly what the Sleeper draft UI ranks by, so it is what the other 13
 * seats will literally be looking at.
 */
@Service
public class PlayerIngestService {

    private static final Logger log = LoggerFactory.getLogger(PlayerIngestService.class);
    private static final int SEARCH_RANK_UNRANKED = 9_999_999;

    private final SleeperClient sleeper;
    private final PlayerRepository players;
    private final BoardRepository boards;

    public PlayerIngestService(SleeperClient sleeper, PlayerRepository players, BoardRepository boards) {
        this.sleeper = sleeper;
        this.players = players;
        this.boards = boards;
    }

    public record Result(int playersWritten, int ranked) {}

    public Result ingest(Sport sport) {
        Map<String, Map<String, Object>> raw = sleeper.allPlayers(sport.code());
        log.info("sleeper returned {} raw player records", raw.size());

        List<Player> toWrite = new ArrayList<>();
        Map<String, Integer> searchRanks = new HashMap<>();

        for (Map.Entry<String, Map<String, Object>> e : raw.entrySet()) {
            Map<String, Object> p = e.getValue();
            List<Position> positions = fantasyPositions(p);
            if (positions.isEmpty()) continue;   // not fantasy relevant in this format

            String name = name(p);
            if (name.isBlank()) continue;

            toWrite.add(new Player(
                    0L, sport, e.getKey(), name, positions,
                    str(p.get("team")), str(p.get("status")), str(p.get("injury_status")),
                    intOrNull(p.get("age")), intOrNull(p.get("years_exp"))));

            Integer sr = intOrNull(p.get("search_rank"));
            if (sr != null && sr < SEARCH_RANK_UNRANKED) {
                searchRanks.put(e.getKey(), sr);
            }
        }

        players.upsertAll(sport, toWrite);
        log.info("wrote {} fantasy-relevant players", toWrite.size());

        int ranked = writeSearchRankBoard(sport, searchRanks);
        return new Result(toWrite.size(), ranked);
    }

    /**
     * search_rank is sparse and its absolute values are not pick numbers, so it
     * is dense-ranked over fantasy-relevant players and the rank is used as a
     * pseudo-pick-number. That equates "the Nth most searched player" with "the
     * Nth pick", which is an assumption, not a measurement.
     */
    private int writeSearchRankBoard(Sport sport, Map<String, Integer> searchRanks) {
        Map<String, Long> ids = players.idsBySleeperId(sport);

        List<Map.Entry<String, Integer>> ordered = searchRanks.entrySet().stream()
                .filter(e -> ids.containsKey(e.getKey()))
                .sorted(Map.Entry.comparingByValue())
                .toList();

        List<BoardRepository.Row> rows = new ArrayList<>(ordered.size());
        for (int i = 0; i < ordered.size(); i++) {
            rows.add(new BoardRepository.Row(ids.get(ordered.get(i).getKey()), i + 1.0, null));
        }
        boards.save(sport, BoardRepository.SOURCE_SEARCH_RANK, LocalDate.now(), rows);
        log.info("wrote {} search_rank board rows", rows.size());
        return rows.size();
    }

    @SuppressWarnings("unchecked")
    private static List<Position> fantasyPositions(Map<String, Object> p) {
        Object fp = p.get("fantasy_positions");
        List<String> raw = (fp instanceof List<?> l)
                ? (List<String>) l
                : (p.get("position") == null ? List.of() : List.of(p.get("position").toString()));
        return raw.stream().map(Position::fromSleeper).flatMap(Optional::stream).distinct().toList();
    }

    private static String name(Map<String, Object> p) {
        Object full = p.get("full_name");
        if (full != null && !full.toString().isBlank()) return full.toString();
        String f = str(p.get("first_name"));
        String l = str(p.get("last_name"));
        return ((f == null ? "" : f) + " " + (l == null ? "" : l)).trim();
    }

    private static String str(Object o) {
        return o == null ? null : o.toString();
    }

    private static Integer intOrNull(Object o) {
        if (o == null) return null;
        if (o instanceof Number n) return n.intValue();
        try {
            return Integer.parseInt(o.toString());
        } catch (NumberFormatException e) {
            return null;
        }
    }
}
