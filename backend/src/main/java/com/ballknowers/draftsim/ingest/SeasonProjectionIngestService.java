package com.ballknowers.draftsim.ingest;

import com.ballknowers.draftsim.domain.Sport;
import com.ballknowers.draftsim.store.JsonUtil;
import com.ballknowers.draftsim.store.PlayerSeasonProjectionRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Fills {@code player_season_projection} with one per-game stat line per player
 * (specs/030-post-draft-season-view research R1, data-model E1). Basketball only: football's
 * projections are weekly and live in {@code player_projection} ({@link ProjectionIngestService}).
 *
 * <p>One call, ~1.5 MB, ~0.4 s, so it is safe on the daily refresh. It is skipped when the stored
 * set is younger than {@link ProjectionIngestService#STALE_AFTER} (the same hand-set six hours,
 * not a second copy of the number) unless forced.
 *
 * <p>Two validations, both from the measured payload:
 * <ul>
 *   <li>Only rows whose stats hold {@code pts} are kept (529 of 2,120 for nba/2026). The rest are
 *       stubs carrying an ADP and nothing else, and a stub and a projected zero must not look alike.</li>
 *   <li>If any kept row carries a {@code gp} that is not 1.0, the <b>whole fetch is rejected</b> and
 *       nothing is stored. Every measured row was a per-game average with {@code gp = 1.0}; anything
 *       else means the basis changed underneath us, and scoring season totals as if they were one
 *       game would be a silent, enormous error.</li>
 * </ul>
 */
@Service
public class SeasonProjectionIngestService {

    private static final Logger log = LoggerFactory.getLogger(SeasonProjectionIngestService.class);

    /** The only basis this ingest accepts, recorded on every row. */
    static final String BASIS_PER_GAME = "PER_GAME";

    /**
     * A fetch that would leave fewer than this share of the currently stored rows is refused and the
     * stored set is kept (a truncated payload must not prune every missing player). Hand-set and
     * ARBITRARY, not fitted: 0.5 only says "losing more than half the projections in one fetch is
     * more likely a broken payload than a real change".
     */
    static final double MIN_KEPT_SHARE_OF_STORED = 0.5;

    private final SleeperProjectionClient client;
    private final PlayerSeasonProjectionRepository projections;

    public SeasonProjectionIngestService(SleeperProjectionClient client,
                                         PlayerSeasonProjectionRepository projections) {
        this.client = client;
        this.projections = projections;
    }

    /**
     * @param fetched   a call to Sleeper was made
     * @param fresh     the stored set was young enough that no call was made
     * @param rowsStored rows written; 0 when the fetch was rejected or empty
     * @param rejectedReason null when fine; otherwise why nothing was stored (gp basis, no body, no rows
     *                       with points, or a shrink beyond {@link #MIN_KEPT_SHARE_OF_STORED}). Amended after code review (R4).
     */
    public record Result(boolean fetched, boolean fresh, int rowsStored, int rowsDeleted, int skippedNoStats,
                         String source, Instant sourceModifiedAt, String rejectedReason) {}

    public Result refresh(Sport sport, int season, boolean force) {
        if (sport != Sport.NBA) {
            throw new IllegalArgumentException("season projections aren't wired up for " + sport.code()
                    + " leagues: football projections are weekly and are stored separately.");
        }
        Optional<PlayerSeasonProjectionRepository.Provenance> stored = projections.provenance(sport.code(), season);
        if (!force && stored.isPresent() && stored.get().fetchedAt() != null
                && stored.get().fetchedAt().isAfter(Instant.now().minus(ProjectionIngestService.STALE_AFTER))) {
            return new Result(false, true, 0, 0, 0, stored.get().source(), stored.get().sourceModifiedAt(), null);
        }

        List<Map<String, Object>> raw = client.season(sport.code(), season);
        if (raw == null) {
            log.warn("season projections: {} {} returned no body, keeping the stored rows", sport.code(), season);
            return new Result(true, false, 0, 0, 0, null, null, "Sleeper returned no body");
        }

        List<PlayerSeasonProjectionRepository.Row> rows = new ArrayList<>();
        int skipped = 0;
        String source = null;
        Instant modified = null;
        for (Map<String, Object> r : raw) {
            Object playerId = r.get("player_id");
            Object statsRaw = r.get("stats");
            if (playerId == null || !(statsRaw instanceof Map<?, ?> stats) || !(stats.get("pts") instanceof Number)) {
                skipped++;
                continue;
            }
            Object gp = stats.get("gp");
            if (gp instanceof Number n && n.doubleValue() != 1.0) {
                log.warn("season projections: {} {} rejected -- player {} has gp={} (expected 1.0, per-game); "
                        + "the basis may have changed, nothing stored", sport.code(), season, playerId, gp);
                return new Result(true, false, 0, 0, skipped, null, null,
                        "player " + playerId + " has gp=" + gp + " (expected 1.0, per-game); the basis may have changed");
            }

            Map<String, Object> line = new LinkedHashMap<>();
            for (Map.Entry<?, ?> e : stats.entrySet()) {
                String key = String.valueOf(e.getKey());
                if (key.startsWith("adp_")) continue;
                line.put(key, e.getValue());
            }
            String company = r.get("company") == null ? null : String.valueOf(r.get("company"));
            Instant rowModified = r.get("last_modified") instanceof Number m ? Instant.ofEpochMilli(m.longValue()) : null;
            if (source == null) source = company;
            if (rowModified != null && (modified == null || rowModified.isAfter(modified))) modified = rowModified;
            rows.add(new PlayerSeasonProjectionRepository.Row(String.valueOf(playerId), JsonUtil.write(line),
                    BASIS_PER_GAME, company, rowModified));
        }

        if (rows.isEmpty()) {
            // An empty set is a no-op, never a wipe: replaceAll would delete every stored line.
            log.warn("season projections: {} {} had no row with a points projection, keeping the stored rows",
                    sport.code(), season);
            return new Result(true, false, 0, 0, skipped, source, modified, "no row had a points projection");
        }

        if (stored.isPresent() && stored.get().rows() > 0
                && rows.size() < MIN_KEPT_SHARE_OF_STORED * stored.get().rows()) {
            log.warn("season projections: {} {} would shrink the stored set from {} to {} rows, keeping the stored rows",
                    sport.code(), season, stored.get().rows(), rows.size());
            return new Result(true, false, 0, 0, skipped, source, modified,
                    "fetch had " + rows.size() + " rows against " + stored.get().rows() + " stored; kept the stored set");
        }

        PlayerSeasonProjectionRepository.ReplaceResult res = projections.replaceAll(sport.code(), season, rows);
        log.info("season projections: {} {} -- {} stored, {} dropped, {} without a stat line",
                sport.code(), season, res.rowsStored(), res.rowsDeleted(), skipped);
        return new Result(true, false, res.rowsStored(), res.rowsDeleted(), skipped, source, modified, null);
    }
}
