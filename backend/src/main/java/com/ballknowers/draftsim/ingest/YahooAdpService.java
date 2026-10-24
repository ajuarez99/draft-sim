package com.ballknowers.draftsim.ingest;

import com.ballknowers.draftsim.domain.Sport;
import com.ballknowers.draftsim.store.BoardRepository;
import com.ballknowers.draftsim.store.PlayerRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Spec 046 R2: matches Yahoo's draft analysis onto local players and stores it as the {@code yahoo}
 * capture (scoring recorded as {@code redraft}, which is what Yahoo's public leagues play).
 *
 * <p>Players {@link PlayerMatcher} can't match are NOT dropped: they go to {@code adp_unmatched} in the same
 * transaction as the day's capture and reach the compare page as rows with no player id (F5). A miss on the
 * wrong side of the board is the most common silent corruption in this repo's ADP ingests.
 *
 * <p>Best-effort by design: any client failure keeps the previous capture and returns a
 * {@code rejectedReason}; it never throws, so a Yahoo wobble can't fail ingest for anything else.
 */
@Service
public class YahooAdpService {

    private static final Logger log = LoggerFactory.getLogger(YahooAdpService.class);

    private final YahooAdpClient client;
    private final PlayerRepository players;
    private final BoardRepository boards;

    public YahooAdpService(YahooAdpClient client, PlayerRepository players, BoardRepository boards) {
        this.client = client;
        this.players = players;
        this.boards = boards;
    }

    /**
     * @param fetched        Yahoo players read (those with a numeric average pick)
     * @param rows           matched rows written to {@code adp_snapshot}
     * @param matched        distinct local players matched
     * @param unmatched      players stored in {@code adp_unmatched} instead
     * @param rejectedReason null when written; otherwise why the previous capture was kept
     */
    public record Result(int fetched, int rows, int matched, int unmatched, String rejectedReason) {
        static Result rejected(int fetched, String why) {
            return new Result(fetched, 0, 0, 0, why);
        }
    }

    public Result ingest(Sport sport) {
        return ingest(sport, false);
    }

    /** @param force bypasses the shrink guard (spec 046 C11); it never bypasses the range checks (C5). */
    public Result ingest(Sport sport, boolean force) {
        if (sport != Sport.NBA) {
            throw new IllegalArgumentException("Yahoo ADP is basketball-only: " + sport.code()
                    + " has no Yahoo public-league feed wired up.");
        }
        List<YahooAdpClient.Player> fetched;
        try {
            fetched = client.fetchAll();
        } catch (Exception e) {
            log.warn("yahoo adp: fetch failed, keeping the previous capture: {}", e.toString());
            return Result.rejected(0, "Yahoo fetch failed: " + e.getMessage());
        }
        if (fetched.isEmpty()) {
            log.warn("yahoo adp: no ranked players, keeping the previous capture");
            return Result.rejected(0, "Yahoo returned no player with a numeric average pick");
        }

        // Spec 046 C5: a unit change upstream (percents, a 0-based pick) must reject the fetch, not be stored as data.
        for (YahooAdpClient.Player p : fetched) {
            if (!(p.averagePick() > 0)
                    || (p.percentDrafted() != null && (p.percentDrafted() < 0 || p.percentDrafted() > 1))) {
                log.warn("yahoo adp: {} has average_pick {} / percent_drafted {}, rejecting the whole fetch",
                        p.name(), p.averagePick(), p.percentDrafted());
                return Result.rejected(fetched.size(), "Yahoo sent an out-of-range value (" + p.name()
                        + ": average pick " + p.averagePick() + ", percent drafted " + p.percentDrafted()
                        + "); expected pick > 0 and percent drafted in [0, 1]; kept the stored capture");
            }
        }

        // Spec 046 C4: the strict matcher refuses a name two players share instead of taking the first one.
        PlayerMatcher matcher = PlayerMatcher.buildStrict(sport, players.findAll(sport), Map.of());
        Map<Long, BoardRepository.CaptureRow> rows = new LinkedHashMap<>();
        List<BoardRepository.UnmatchedRow> unmatched = new ArrayList<>();
        for (YahooAdpClient.Player p : fetched) {
            PlayerMatcher.Outcome o = match(matcher, p);
            Long id = o.kind() == PlayerMatcher.Outcome.Kind.MATCHED ? o.playerId() : null;
            // Pages arrive best average pick first, so the first entry for a player is the better one. The
            // second is NOT dropped: it goes to adp_unmatched, so ranked counts add up (spec 046 C4).
            if (id != null && !rows.containsKey(id)) {
                rows.put(id, new BoardRepository.CaptureRow(id, p.averagePick(), p.averageCost(), p.percentDrafted()));
                continue;
            }
            log.info("yahoo adp: {} ({}, {}) -> {}", p.name(), p.position(), p.team(),
                    id != null ? "DUPLICATE of an already matched player" : o.kind());
            unmatched.add(new BoardRepository.UnmatchedRow(p.name() == null ? "Unknown player" : p.name(),
                    p.team(), p.position(), p.averagePick(), p.averageCost(), p.percentDrafted()));
        }

        LocalDate today = LocalDate.now(ZoneOffset.UTC);
        Optional<LocalDate> stored = boards.latestCapture(sport, BoardRepository.SOURCE_YAHOO);
        int storedRows = stored.map(d -> boards.captureSize(sport, BoardRepository.SOURCE_YAHOO, d)).orElse(0);
        if (!force && storedRows > 0 && rows.size() < SeasonProjectionIngestService.MIN_KEPT_SHARE_OF_STORED * storedRows) {
            log.warn("yahoo adp: would shrink the capture from {} to {} rows, keeping the stored one", storedRows, rows.size());
            return Result.rejected(fetched.size(), "fetch matched " + rows.size() + " rows against " + storedRows
                    + " stored; kept the stored capture");
        }

        // Spec 046 C5: "never throws" holds for the write too -- a DB failure keeps the previous capture (the
        // save is one transaction) and comes back as a reason instead of a 500.
        try {
            boards.saveCapture(sport, BoardRepository.SOURCE_YAHOO, today, "redraft", List.copyOf(rows.values()), unmatched);
        } catch (Exception e) {
            log.warn("yahoo adp: saving the capture failed, the previous one stays: {}", e.toString());
            return Result.rejected(fetched.size(), "saving the Yahoo capture failed: " + e.getMessage());
        }
        log.info("yahoo adp: {} ranked, {} matched, {} unmatched", fetched.size(), rows.size(), unmatched.size());
        if (!unmatched.isEmpty()) {
            log.warn("yahoo adp unmatched (best ADP first): {}",
                    unmatched.stream().limit(25).map(u -> u.adp() + " " + u.name() + " " + u.positions()).toList());
        }
        return new Result(fetched.size(), rows.size(), rows.size(), unmatched.size(), null);
    }

    /**
     * Spec 046 C4. The name alone first, through the ambiguity rule with the team as a tie-break: a unique
     * name, or a shared name one team narrows to one player, is matched. Only a name still shared after that
     * is split by Yahoo's position list ("PG,SG" -- each tried, the matcher keys on one position); if no
     * position singles one player out it stays AMBIGUOUS and goes to adp_unmatched, never to the first id.
     */
    private static PlayerMatcher.Outcome match(PlayerMatcher matcher, YahooAdpClient.Player p) {
        PlayerMatcher.Outcome byName = matcher.matchStrictWithTeam(p.name(), null, p.team());
        if (byName.kind() != PlayerMatcher.Outcome.Kind.AMBIGUOUS) return byName;
        String pos = p.position();
        if (pos != null && !pos.isBlank()) {
            for (String one : pos.split(",")) {
                PlayerMatcher.Outcome o = matcher.matchStrictWithTeam(p.name(), one.trim(), p.team());
                if (o.kind() == PlayerMatcher.Outcome.Kind.MATCHED) return o;
            }
        }
        return byName;
    }
}
