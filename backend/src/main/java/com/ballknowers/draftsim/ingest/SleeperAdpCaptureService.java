package com.ballknowers.draftsim.ingest;

import com.ballknowers.draftsim.domain.Sport;
import com.ballknowers.draftsim.engine.AdpSeason;
import com.ballknowers.draftsim.store.LeagueRepository;
import com.ballknowers.draftsim.store.BoardRepository;
import com.ballknowers.draftsim.store.PlayerRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalInt;

/**
 * Spec 046 R1 (amended F11): Sleeper's dynasty and redraft ADP, captured into {@code adp_snapshot}.
 *
 * <p>This is its OWN pass over the <b>raw</b> season payload, run before and independently of
 * {@link SeasonProjectionIngestService}'s per-line validation. Two reasons, both measured on nba/2026:
 * 835 rows carry {@code adp_dynasty < 999} but only 529 carry a stat line, so the ADP-only "stubs" that
 * ingest throws away are exactly the deep rookies dynasty drafters care about; and its {@code gp != 1.0}
 * rejection returns mid-loop, which must not lose the ADP rows.
 *
 * <p>999 means undrafted. It is never written (absence = unranked), as {@code adp_snapshot.adp} is not
 * null. The write is guarded like the projections: a capture that would hold fewer than
 * {@link SeasonProjectionIngestService#MIN_KEPT_SHARE_OF_STORED} of the stored capture's rows is
 * refused and the stored one stays (a truncated payload must not look like a real shrink).
 *
 * <p>Basketball only: football's season endpoint isn't called (research R1, not verified for NFL).
 */
@Service
public class SleeperAdpCaptureService {

    private static final Logger log = LoggerFactory.getLogger(SleeperAdpCaptureService.class);

    /** Sleeper's "undrafted" sentinel: any ADP at or past it means unranked. */
    static final double UNDRAFTED = 999;

    private final SleeperProjectionClient client;
    private final PlayerRepository players;
    private final BoardRepository boards;
    private final LeagueRepository leagues;

    public SleeperAdpCaptureService(SleeperProjectionClient client, PlayerRepository players, BoardRepository boards,
                                    LeagueRepository leagues) {
        this.client = client;
        this.players = players;
        this.boards = boards;
        this.leagues = leagues;
    }

    /**
     * @param dynastyRows    rows written to {@code sleeper_dynasty} (0 when refused or none)
     * @param redraftRows    rows written to {@code sleeper_redraft}
     * @param unmapped       payload rows with an ADP whose Sleeper id no local player has (counted, never written)
     * @param rejectedReason null when both sources were handled; otherwise why one or both kept the stored capture
     */
    public record Result(int dynastyRows, int redraftRows, int unmapped, String rejectedReason) {
        static Result rejected(String why) {
            return new Result(0, 0, 0, why);
        }
    }

    /** Fetches the season payload itself: the daily ADP_CAPTURE step and the admin route, when the projection ingest did not. */
    public Result fetchAndCapture(Sport sport, int season) {
        return fetchAndCapture(sport, season, false);
    }

    /** @param force bypasses the shrink guard (spec 046 C11); it never bypasses the season rule (C3). */
    public Result fetchAndCapture(Sport sport, int season, boolean force) {
        requireNba(sport);
        Result wrongSeason = refuseUnlessCurrent(sport, season);
        if (wrongSeason != null) return wrongSeason;
        List<Map<String, Object>> raw = client.season(sport.code(), season);
        if (raw == null) {
            log.warn("sleeper adp: {} {} returned no body, keeping the stored captures", sport.code(), season);
            return Result.rejected("Sleeper returned no body");
        }
        return capture(sport, season, raw, force);
    }

    /**
     * Captures from an already-fetched raw payload of {@code season} (what {@link SeasonProjectionIngestService#refresh}
     * hands over). Spec 046 C3: the payload carries no season of its own and the capture is stored as today's, so
     * a payload for any season but the compare page's (see {@link AdpSeason}) writes nothing.
     */
    public Result capture(Sport sport, int season, List<Map<String, Object>> raw) {
        return capture(sport, season, raw, false);
    }

    public Result capture(Sport sport, int season, List<Map<String, Object>> raw, boolean force) {
        requireNba(sport);
        Result wrongSeason = refuseUnlessCurrent(sport, season);
        if (wrongSeason != null) return wrongSeason;
        LocalDate today = LocalDate.now(ZoneOffset.UTC);
        Map<String, Long> ids = players.idsBySleeperId(sport);

        Map<Long, BoardRepository.CaptureRow> dynasty = new LinkedHashMap<>();
        Map<Long, BoardRepository.CaptureRow> redraft = new LinkedHashMap<>();
        int unmapped = 0;
        for (Map<String, Object> r : raw) {
            Object playerId = r.get("player_id");
            if (playerId == null || !(r.get("stats") instanceof Map<?, ?> stats)) continue;
            Double dyn = ranked(stats.get("adp_dynasty"));
            Double red = ranked(stats.get("adp_std"));
            if (dyn == null && red == null) continue;
            Long id = ids.get(String.valueOf(playerId));
            if (id == null) {
                unmapped++;
                continue;
            }
            if (dyn != null) dynasty.putIfAbsent(id, new BoardRepository.CaptureRow(id, dyn, null, null));
            if (red != null) redraft.putIfAbsent(id, new BoardRepository.CaptureRow(id, red, null, null));
        }
        if (unmapped > 0) {
            log.warn("sleeper adp: {} ranked payload rows have no local player (counted, not written)", unmapped);
        }

        StringBuilder why = new StringBuilder();
        int d = write(sport, BoardRepository.SOURCE_SLEEPER_DYNASTY, "dynasty", today, dynasty.values(), why, force);
        int rd = write(sport, BoardRepository.SOURCE_SLEEPER_REDRAFT, "redraft", today, redraft.values(), why, force);
        log.info("sleeper adp: {} -- dynasty {} rows, redraft {} rows, {} unmapped", sport.code(), d, rd, unmapped);
        return new Result(d, rd, unmapped, why.isEmpty() ? null : why.toString());
    }

    private int write(Sport sport, String source, String scoring, LocalDate today,
                      java.util.Collection<BoardRepository.CaptureRow> rows, StringBuilder why, boolean force) {
        if (rows.isEmpty()) return 0;   // nothing ranked: never write an empty capture over a real one
        Optional<LocalDate> stored = boards.latestCapture(sport, source);
        int storedRows = stored.map(date -> boards.captureSize(sport, source, date)).orElse(0);
        if (!force && storedRows > 0 && rows.size() < SeasonProjectionIngestService.MIN_KEPT_SHARE_OF_STORED * storedRows) {
            log.warn("sleeper adp: {} would shrink from {} to {} rows, keeping the stored capture", source, storedRows, rows.size());
            if (!why.isEmpty()) why.append("; ");
            why.append(source).append(" had ").append(rows.size()).append(" rows against ").append(storedRows)
                    .append(" stored; kept the stored capture");
            return 0;
        }
        boards.saveCapture(sport, source, today, scoring, List.copyOf(rows), List.of());
        return rows.size();
    }

    /** Spec 046 C3: null when {@code season} is the one the compare page means; otherwise a rejected Result, nothing written. */
    private Result refuseUnlessCurrent(Sport sport, int season) {
        OptionalInt current = AdpSeason.current(leagues, sport);
        if (current.isPresent() && current.getAsInt() == season) return null;
        String why = current.isEmpty()
                ? "no " + sport.code() + " league exists, so no season is the ADP compare page's"
                : "season " + season + " is not the ADP compare page's season (" + current.getAsInt()
                        + "); a capture is stored as today's, so it would overwrite that season's";
        log.warn("sleeper adp: {} {} refused: {}", sport.code(), season, why);
        return Result.rejected(why);
    }

    /** A usable ADP: a number in (0, 999). Anything else (missing, 999 sentinel, junk) is unranked. */
    static Double ranked(Object v) {
        if (!(v instanceof Number n)) return null;
        double x = n.doubleValue();
        return Double.isFinite(x) && x > 0 && x < UNDRAFTED ? x : null;
    }

    private static void requireNba(Sport sport) {
        if (sport != Sport.NBA) {
            throw new IllegalArgumentException("sleeper adp capture isn't wired up for " + sport.code()
                    + ": it reads the season payload, which football doesn't use.");
        }
    }
}
