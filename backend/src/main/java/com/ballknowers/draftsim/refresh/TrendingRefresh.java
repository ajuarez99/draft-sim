package com.ballknowers.draftsim.refresh;

import com.ballknowers.draftsim.domain.Sport;
import com.ballknowers.draftsim.ingest.SleeperClient;
import com.ballknowers.draftsim.store.SportTrendingRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * The sport-wide, best-effort trending fetch (specs/014-home-player-spotlight, research R5).
 *
 * <p>Runs as a step of the league refresh and of the daily route, gated on the stored
 * {@code fetched_at} being older than {@link RefreshProperties#STALE_AFTER}. It <b>never
 * throws</b>: trending is decoration on a page whose scores matter more, so any failure is
 * recorded and returned as {@link Outcome#FAILED}, and the caller's own success is untouched.
 * A per-sport {@link SingleFlight} lets concurrent league refreshes in one sport share a fetch.
 */
@Service
public class TrendingRefresh {

    private static final Logger log = LoggerFactory.getLogger(TrendingRefresh.class);

    /** Hand-set, arbitrary: the window the counts cover (research R5). */
    static final int LOOKBACK_HOURS = 24;
    /** Hand-set, arbitrary: how many entries to store; the page shows fewer. */
    static final int LIMIT = 25;

    public enum Outcome { DONE, SKIPPED_FRESH, FAILED }

    private final SleeperClient sleeper;
    private final SportTrendingRepository repository;
    /** Separate from every other flight; keyed {@code trending:<sport>}. */
    private final SingleFlight flight = new SingleFlight();

    public TrendingRefresh(SleeperClient sleeper, SportTrendingRepository repository) {
        this.sleeper = sleeper;
        this.repository = repository;
    }

    public Outcome refreshIfStale(Sport sport, Instant now) {
        try {
            var snapshot = repository.read(sport.code());
            if (snapshot.isPresent() && snapshot.get().fetchedAt() != null
                    && snapshot.get().fetchedAt().toInstant()
                            .isAfter(now.minus(RefreshProperties.STALE_AFTER))) {
                return Outcome.SKIPPED_FRESH;
            }
            return flight.run("trending:" + sport.code(), () -> fetch(sport, now)).join();
        } catch (Exception e) {
            // Reading the state, or the flight itself, failed: still best-effort.
            log.warn("trending: {} refresh failed before fetching: {}", sport.code(), e.toString());
            return Outcome.FAILED;
        }
    }

    private Outcome fetch(Sport sport, Instant now) {
        try {
            List<Map<String, Object>> raw = sleeper.trendingAdds(sport.code(), LOOKBACK_HOURS, LIMIT);
            if (raw == null) throw new IllegalStateException("trending response had no body");
            Map<String, Object> state = sleeper.state(sport.code());

            List<SportTrendingRepository.Entry> entries = new ArrayList<>();
            for (Map<String, Object> row : raw) {
                Object id = row.get("player_id");
                Object count = row.get("count");
                if (id == null || !(count instanceof Number n)) continue;
                entries.add(new SportTrendingRepository.Entry(id.toString(), n.intValue()));
            }
            repository.replace(sport.code(), LOOKBACK_HOURS, leagueSeason(state), seasonStart(state), entries,
                    OffsetDateTime.ofInstant(now, ZoneOffset.UTC));
            return Outcome.DONE;
        } catch (Exception e) {
            log.warn("trending: {} fetch failed: {}", sport.code(), e.toString());
            try {
                repository.recordFailure(sport.code(), OffsetDateTime.ofInstant(now, ZoneOffset.UTC), reasonOf(e));
            } catch (Exception recordFailed) {
                log.warn("trending: {} could not record the failure: {}", sport.code(), recordFailed.toString());
            }
            return Outcome.FAILED;
        }
    }

    /** Sleeper may send the season as a string; null when absent or unparseable, never a guess. */
    private static Integer leagueSeason(Map<String, Object> state) {
        Object v = state == null ? null : state.get("league_season");
        if (v instanceof Number n) return n.intValue();
        if (v == null) return null;
        try {
            return Integer.valueOf(v.toString().trim());
        } catch (NumberFormatException e) {
            return null;
        }
    }

    /** Null when absent or unparseable: an unknown start date is not replaced by a guessed one. */
    private static LocalDate seasonStart(Map<String, Object> state) {
        Object v = state == null ? null : state.get("season_start_date");
        if (v == null) return null;
        try {
            return LocalDate.parse(v.toString().trim());
        } catch (RuntimeException e) {
            return null;
        }
    }

    private static String reasonOf(Exception e) {
        String message = e.getMessage() == null ? "" : e.getMessage();
        String reason = e.getClass().getSimpleName() + (message.isEmpty() ? "" : ": " + message);
        return reason.length() <= 200 ? reason : reason.substring(0, 200);
    }
}
