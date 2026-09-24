package com.ballknowers.draftsim.ingest;

import com.ballknowers.draftsim.domain.Player;
import com.ballknowers.draftsim.domain.Position;
import com.ballknowers.draftsim.domain.Sport;
import com.ballknowers.draftsim.sport.SportRules;
import com.ballknowers.draftsim.sport.SportRulesRegistry;
import com.ballknowers.draftsim.store.BoardRepository;
import com.ballknowers.draftsim.store.PlayerRepository;
import com.ballknowers.draftsim.store.StatusCaptureRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.Instant;
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
    private final StatusCaptureRepository statusCaptures;
    private final SportRulesRegistry rulesRegistry;

    public PlayerIngestService(SleeperClient sleeper, PlayerRepository players, BoardRepository boards,
                               StatusCaptureRepository statusCaptures, SportRulesRegistry rulesRegistry) {
        this.sleeper = sleeper;
        this.players = players;
        this.boards = boards;
        this.statusCaptures = statusCaptures;
        this.rulesRegistry = rulesRegistry;
    }

    /**
     * @param suspensionCaptured whether the /state/{sport} call and the
     *                           status_capture/player_suspension writes
     *                           (specs/008-season-superlatives T052, research
     *                           R11) succeeded. False on any failure of that
     *                           call -- it must never fail the player ingest
     *                           itself, which is the whole reason this is a
     *                           flag on the result rather than a thrown
     *                           exception. Also false, deliberately, when
     *                           {@code /state/{sport}}'s own {@code
     *                           season_type} is not {@code "regular"}
     *                           (coordinator follow-up 2026-09-23, item 5;
     *                           measured live against {@code GET
     *                           /v1/state/nba} during preseason: {@code
     *                           {"week":1,"leg":0,"season_type":"pre",
     *                           "season":"2026",...}} -- {@code week} alone
     *                           reports 1 even though nothing has started,
     *                           which would otherwise record a capture
     *                           claiming tracking began a real week 1). The
     *                           captured week itself, when it IS recorded, is
     *                           {@code leg} -- Sleeper's own name for the
     *                           fantasy scoring period -- not {@code week}
     *                           (measured the same day against {@code GET
     *                           /v1/state/nfl}: {@code {"week":3,"leg":3,
     *                           "season_type":"regular",...}}; they agree for
     *                           football today, but nothing here may assume
     *                           they always will).
     * @param suspendedCount     0 when suspensionCaptured is false.
     */
    public record Result(int playersWritten, int ranked, boolean suspensionCaptured, int suspendedCount) {}

    public Result ingest(Sport sport) {
        Map<String, Map<String, Object>> raw = sleeper.allPlayers(sport.code());
        log.info("sleeper returned {} raw player records", raw.size());

        List<Player> toWrite = new ArrayList<>();
        Map<String, Integer> searchRanks = new HashMap<>();

        for (Map.Entry<String, Map<String, Object>> e : raw.entrySet()) {
            Map<String, Object> p = e.getValue();
            List<Position> positions = fantasyPositions(p, sport);
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

        boolean suspensionCaptured = false;
        int suspendedCount = 0;
        try {
            Map<String, Object> state = sleeper.state(sport.code());
            String seasonType = str(state.get("season_type"));
            if (!"regular".equals(seasonType)) {
                // Measured live 2026-09-23 (coordinator follow-up, item 5):
                // GET /v1/state/nba during preseason returns {"week":1,"leg":0,
                // "season_type":"pre",...} -- "week" alone reports 1 even
                // though nothing has actually started, so a capture recorded
                // from it would falsely claim tracking began week 1 of a
                // season with zero real weeks observed. NFL today (measured
                // the same day): {"week":3,"leg":3,"season_type":"regular"}
                // -- unaffected by this guard.
                log.info("suspension capture skipped for {}: season_type is '{}', not 'regular'", sport, seasonType);
            } else {
                int season = Integer.parseInt(String.valueOf(state.get("season")));
                // "leg", not "week": leg is Sleeper's own name for the fantasy
                // scoring period, which is what status_capture's own "week"
                // column means everywhere else in this feature. They agree for
                // NFL today, but nothing here may assume they always will.
                int week = ((Number) state.getOrDefault("leg", 0)).intValue();
                SportRules rules = rulesRegistry.get(sport);
                List<String> suspended = toWrite.stream()
                        .filter(rules::isSuspended)
                        .map(Player::sleeperId)
                        .toList();
                statusCaptures.recordCapture(sport, season, week, Instant.now());
                statusCaptures.recordSuspended(sport, season, week, suspended);
                suspensionCaptured = true;
                suspendedCount = suspended.size();
                log.info("captured suspension tags for {} season {} week {}: {} suspended", sport, season, week, suspendedCount);
            }
        } catch (Exception e) {
            log.warn("suspension capture failed for {} -- player ingest still succeeded: {}", sport, e.toString());
        }

        return new Result(toWrite.size(), ranked, suspensionCaptured, suspendedCount);
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
    private static List<Position> fantasyPositions(Map<String, Object> p, Sport sport) {
        Object fp = p.get("fantasy_positions");
        List<String> raw = (fp instanceof List<?> l)
                ? (List<String>) l
                : (p.get("position") == null ? List.of() : List.of(p.get("position").toString()));
        // Sport-aware: the nba payload carries ~30 entries whose fantasy_positions
        // is exactly ["DEF"] (verified 2026-09-08) -- an archive artifact, not a
        // real basketball position. Position.fromSleeper(String, Sport) drops
        // those for nba rather than mapping them onto football's DEF.
        return raw.stream().map(pos -> Position.fromSleeper(pos, sport)).flatMap(Optional::stream).distinct().toList();
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
