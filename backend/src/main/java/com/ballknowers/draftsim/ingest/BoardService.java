package com.ballknowers.draftsim.ingest;

import com.ballknowers.draftsim.config.BoardProperties;
import com.ballknowers.draftsim.domain.*;
import com.ballknowers.draftsim.store.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.*;

/**
 * Builds the board the engine actually values players against.
 *
 * This is the weakest link in the whole model and it is worth being blunt about
 * it: there is no true 14-team PPR ADP feed here. The board is
 *
 *   (1 - w) * Sleeper search_rank, dense-ranked to a pick number
 * + w       * observed pick order from completed drafts, rescaled to 14 teams
 *
 * Neither input is an ADP. search_rank is popularity and ignores league size and
 * scoring. Observed order is a handful of drafts, so it carries the specific
 * quirks of those rooms. The blend weight is a free parameter with no data
 * behind it. Replace the whole thing the moment a real ADP source is available;
 * everything downstream reads BoardEntry and will not notice.
 */
@Service
public class BoardService {

    private static final Logger log = LoggerFactory.getLogger(BoardService.class);

    private final BoardProperties cfg;
    private final BoardRepository boards;
    private final PlayerRepository players;
    private final DraftRepository drafts;
    private final JdbcClient db;
    private final JdbcTemplate jdbc;

    public BoardService(BoardProperties cfg, BoardRepository boards, PlayerRepository players,
                        DraftRepository drafts, JdbcClient db, JdbcTemplate jdbc) {
        this.cfg = cfg;
        this.boards = boards;
        this.players = players;
        this.drafts = drafts;
        this.db = db;
        this.jdbc = jdbc;
    }

    public record Result(int entries, int fromBothSources, int backfilledPicks) {}

    public Result rebuild(Sport sport) {
        LocalDate today = LocalDate.now();

        LocalDate srDate = boards.latestCapture(sport, BoardRepository.SOURCE_SEARCH_RANK)
                .orElseThrow(() -> new IllegalStateException(
                        "no search_rank snapshot — ingest players first"));
        Map<Long, Double> searchRank = new LinkedHashMap<>();
        boards.load(sport, BoardRepository.SOURCE_SEARCH_RANK, srDate)
                .forEach(r -> searchRank.put(r.playerId(), r.adp()));

        Map<Long, List<Double>> observed = observedPickNumbers();

        double w = cfg.observedWeight();
        Set<Long> universe = new LinkedHashSet<>(searchRank.keySet());
        universe.addAll(observed.keySet());

        int both = 0;
        List<Map.Entry<Long, Double>> blended = new ArrayList<>(universe.size());
        for (Long playerId : universe) {
            Double sr = searchRank.get(playerId);
            List<Double> obs = observed.get(playerId);
            Double obsMean = (obs == null || obs.isEmpty())
                    ? null
                    : obs.stream().mapToDouble(Double::doubleValue).average().orElseThrow();

            double value;
            if (sr != null && obsMean != null) {
                value = (1 - w) * sr + w * obsMean;
                both++;
            } else if (sr != null) {
                value = sr;
            } else {
                value = obsMean;
            }
            blended.add(Map.entry(playerId, value));
        }

        // Re-rank so the board is a clean 1..N pick ordering. The blended
        // magnitudes are estimates of a pick number; their ordering is the part
        // worth keeping.
        blended.sort(Map.Entry.comparingByValue());

        Map<Long, Position> positionById = new HashMap<>();
        for (Player p : players.findAll(sport)) {
            positionById.put(p.id(), p.primary());
        }
        Map<Position, Integer> posCounter = new EnumMap<>(Position.class);

        List<BoardRepository.Row> rows = new ArrayList<>(blended.size());
        for (int i = 0; i < blended.size(); i++) {
            long playerId = blended.get(i).getKey();
            Position pos = positionById.get(playerId);
            Integer posRank = null;
            if (pos != null) {
                posRank = posCounter.merge(pos, 1, Integer::sum);
            }
            rows.add(new BoardRepository.Row(playerId, i + 1.0, posRank));
        }
        boards.save(sport, BoardRepository.SOURCE_BLEND, today, rows);
        log.info("blended board: {} entries, {} present in both sources, observedWeight={}",
                rows.size(), both, w);

        int backfilled = backfillAdpAtTime(sport);
        return new Result(rows.size(), both, backfilled);
    }

    /** Pick order from the configured completed drafts, rescaled to referenceTeams. */
    private Map<Long, List<Double>> observedPickNumbers() {
        Map<Long, List<Double>> out = new HashMap<>();
        for (String sleeperDraftId : cfg.observedDrafts()) {
            Optional<DraftRepository.DraftRow> maybe = drafts.bySleeperId(sleeperDraftId);
            if (maybe.isEmpty()) {
                log.warn("observed draft {} not ingested — skipping", sleeperDraftId);
                continue;
            }
            DraftRepository.DraftRow d = maybe.get();
            double scale = (double) cfg.referenceTeams() / d.teams();
            for (DraftRepository.PickRow p : drafts.picks(d.id())) {
                if (p.playerId() == null) continue;
                out.computeIfAbsent(p.playerId(), k -> new ArrayList<>()).add(p.pickNo() * scale);
            }
            log.info("observed draft {} ({} teams) contributed {} picks, scaled x{}",
                    sleeperDraftId, d.teams(), drafts.picks(d.id()).size(), String.format("%.3f", scale));
        }
        return out;
    }

    /**
     * Denormalize the board position onto each historical pick, so reach is
     * measured against the board that existed then.
     *
     * Only picks from drafts close enough in time to a board snapshot get a
     * value. Everything older stays null and is excluded from profile fitting.
     * Scoring a 2025 pick against a 2026 board would produce a reach number that
     * is mostly a year of player movement, not a manager's behavior.
     */
    private int backfillAdpAtTime(Sport sport) {
        return jdbc.update("""
                update draft_pick dp
                set adp_at_time = s.adp
                from draft d
                join adp_snapshot s
                  on s.player_id = dp.player_id
                 and s.sport = ?
                 and s.source = ?
                 and s.captured_on between (d.start_time::date - make_interval(days => ?))
                                       and (d.start_time::date + make_interval(days => ?))
                where d.id = dp.draft_id
                  and dp.player_id is not null
                  and d.start_time is not null
                """,
                sport.code(), BoardRepository.SOURCE_BLEND,
                cfg.maxBoardLagDays(), cfg.maxBoardLagDays());
    }

    /** The current board, ready for the engine. */
    public List<BoardEntry> currentBoard(Sport sport) {
        LocalDate date = boards.latestCapture(sport, BoardRepository.SOURCE_BLEND)
                .orElseThrow(() -> new IllegalStateException("no blended board — run /api/ingest/board"));
        Map<Long, Player> byId = new HashMap<>();
        players.findAll(sport).forEach(p -> byId.put(p.id(), p));

        List<BoardEntry> out = new ArrayList<>();
        for (BoardRepository.Row r : boards.load(sport, BoardRepository.SOURCE_BLEND, date)) {
            Player p = byId.get(r.playerId());
            if (p == null || p.positions().isEmpty()) continue;
            out.add(new BoardEntry(p, r.adp(), r.positionalRank() == null ? 999 : r.positionalRank()));
        }
        out.sort(Comparator.comparingDouble(BoardEntry::adp));
        return out;
    }

    public Optional<LocalDate> currentBoardDate(Sport sport) {
        return boards.latestCapture(sport, BoardRepository.SOURCE_BLEND);
    }

    /** How much of the board is backed by observed draft order rather than search_rank alone. */
    public int picksWithAdpAtTime() {
        return db.sql("select count(*) from draft_pick where adp_at_time is not null")
                .query(Integer.class).single();
    }

    static LocalDate toDate(java.time.Instant i) {
        return i == null ? null : i.atZone(ZoneOffset.UTC).toLocalDate();
    }
}
