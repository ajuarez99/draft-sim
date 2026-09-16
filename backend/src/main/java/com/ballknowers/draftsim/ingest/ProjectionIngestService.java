package com.ballknowers.draftsim.ingest;

import com.ballknowers.draftsim.store.PlayerProjectionRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Fills {@code player_projection} for a window of weeks
 * (claude/league-analysis.md Phase 1).
 *
 * A full rest-of-season refresh is ~13 calls, ~27 MB and ~4 s, so this is
 * never run from a GET the page makes on mount -- it sits behind an explicit
 * ingest endpoint, the same discipline the power-ranking and playoff-odds
 * computes already follow.
 */
@Service
public class ProjectionIngestService {

    private static final Logger log = LoggerFactory.getLogger(ProjectionIngestService.class);

    /**
     * How old a week's projections may be before a refresh refetches them.
     *
     * Six hours is a judgement, not a measurement: projections move on injury
     * news, which lands through the day rather than on a schedule. It is the
     * one number here that should be expected to change once somebody watches
     * a real week move.
     */
    private static final Duration STALE_AFTER = Duration.ofHours(6);

    private final SleeperProjectionClient client;
    private final PlayerProjectionRepository projections;

    public ProjectionIngestService(SleeperProjectionClient client, PlayerProjectionRepository projections) {
        this.client = client;
        this.projections = projections;
    }

    public record Result(int weeksFetched, int weeksFresh, int rowsStored) {}

    /**
     * Ensures every week in {@code [fromWeek, toWeek]} is present and not
     * stale. Weeks already fresh are skipped without a call -- that is the
     * whole point of storing these rather than fetching them per page view.
     */
    public Result refresh(String sport, int season, int fromWeek, int toWeek, boolean force) {
        int fetched = 0, fresh = 0, stored = 0;
        Instant now = Instant.now();

        for (int week = fromWeek; week <= toWeek; week++) {
            if (!force && isFresh(sport, season, week, now)) {
                fresh++;
                continue;
            }
            List<Map<String, Object>> raw = client.week(sport, season, week);
            if (raw == null) {
                log.warn("projections: {} {} week {} returned no body", sport, season, week);
                continue;
            }
            fetched++;
            List<PlayerProjectionRepository.Row> rows = toRows(sport, season, week, raw);
            projections.upsertAll(rows);
            stored += rows.size();
        }

        log.info("projections: {} {} weeks {}-{} -- {} fetched, {} already fresh, {} rows stored",
                sport, season, fromWeek, toWeek, fetched, fresh, stored);
        return new Result(fetched, fresh, stored);
    }

    private boolean isFresh(String sport, int season, int week, Instant now) {
        Optional<Instant> at = projections.fetchedAt(sport, season, week);
        return at.isPresent() && at.get().isAfter(now.minus(STALE_AFTER));
    }

    /**
     * Keeps only the rows carrying a real points projection. Roughly six of
     * every seven rows Sleeper returns are stubs holding an ADP and nothing
     * else (measured: 475 of 3,305 were real for nfl/2026 week 2), and storing
     * those would put a row that means "no projection" and a row that means
     * "projected zero" in the same table looking identical.
     */
    @SuppressWarnings("unchecked")
    private static List<PlayerProjectionRepository.Row> toRows(String sport, int season, int week,
                                                               List<Map<String, Object>> raw) {
        List<PlayerProjectionRepository.Row> rows = new ArrayList<>();
        for (Map<String, Object> r : raw) {
            Object playerId = r.get("player_id");
            if (playerId == null) continue;
            Object statsRaw = r.get("stats");
            if (!(statsRaw instanceof Map<?, ?> stats)) continue;

            Double ppr = asDouble(stats.get("pts_ppr"));
            Double half = asDouble(stats.get("pts_half_ppr"));
            Double std = asDouble(stats.get("pts_std"));
            if (ppr == null && half == null && std == null) continue;

            rows.add(new PlayerProjectionRepository.Row(
                    sport, season, week, String.valueOf(playerId), ppr, half, std,
                    r.get("company") == null ? null : String.valueOf(r.get("company"))));
        }
        return rows;
    }

    private static Double asDouble(Object o) {
        return o instanceof Number n ? n.doubleValue() : null;
    }
}
