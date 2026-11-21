package com.ballknowers.draftsim.api;

import com.ballknowers.draftsim.historical.HistoricalDailyService;
import com.ballknowers.draftsim.historical.HistoricalDailyService.YesterdayDaily;
import com.ballknowers.draftsim.historical.HistoricalGameService;
import com.ballknowers.draftsim.historical.HistoricalSport;
import com.ballknowers.draftsim.historical.HistoricalSports;
import com.ballknowers.draftsim.historical.PublicGame;
import com.ballknowers.draftsim.historical.SeasonList;
import com.ballknowers.draftsim.historical.SharePreview;
import com.ballknowers.draftsim.historical.SharePreviewBusyException;
import com.ballknowers.draftsim.historical.SharePreviewRenderException;
import com.ballknowers.draftsim.historical.SharePreviewService;
import com.ballknowers.draftsim.historical.SeasonPage;
import com.ballknowers.draftsim.historical.SeasonSummaryService;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Locale;
import java.util.Optional;

/**
 * The Redraft's public, read-only surface (spec 074, contracts/public-api.md): the season list, one season's summary,
 * yesterday's game of the day and a finished game by id.
 *
 * <p><b>No identity header is read here</b>, and none of these returns an owner, a Sleeper id or a guest id. They live
 * under {@code /api/history/**} on purpose: that prefix is already on the league lock's public list
 * ({@code LeagueLockInterceptor.PUBLIC_PATHS}), so they answer without a session in production, where the lock is on.
 * A prefix outside that list would pass every local test (the lock is off) and answer 401 in production (PR-01).
 * {@code LeagueLockMappingsIT} calls each one with the lock ON.
 */
@RestController
@RequestMapping("/api/history/public")
public class RedraftPublicController {

    private static final org.slf4j.Logger log = org.slf4j.LoggerFactory.getLogger(RedraftPublicController.class);

    private final HistoricalSports sports;
    private final SeasonSummaryService summaries;
    private final HistoricalGameService games;
    private final HistoricalDailyService daily;
    private final SharePreviewService previews;

    public RedraftPublicController(HistoricalSports sports, SeasonSummaryService summaries,
                                   HistoricalGameService games, HistoricalDailyService daily,
                                   SharePreviewService previews) {
        this.previews = previews;
        this.sports = sports;
        this.summaries = summaries;
        this.games = games;
        this.daily = daily;
    }

    /** Season data changes only when an import ends: an hour is plenty, and the build reads it once. */
    private static final String SEASONS = "public, max-age=3600";   // written out: contracts/public-api.md quotes it

    /** {@code sport} is required, with no default: missing or unknown is a 400. */
    @GetMapping("/seasons")
    public ResponseEntity<SeasonList> seasons(@RequestParam(value = "sport", required = false) String sportCode) {
        HistoricalSport sport = sports.get(sportCode);   // IllegalArgumentException -> 400
        return ResponseEntity.ok().header(HttpHeaders.CACHE_CONTROL, SEASONS).body(summaries.list(sport));
    }

    /** 404 for an unknown sport and for a slug that is not an offered season: the same answer for both. */
    @GetMapping("/seasons/{sport}/{slug}")
    public ResponseEntity<SeasonPage> season(@PathVariable String sport, @PathVariable String slug) {
        HistoricalSport s = knownSport(sport);
        if (s == null) return ResponseEntity.notFound().build();
        Optional<SeasonPage> page = summaries.page(s, slug);
        return page.map(p -> ResponseEntity.ok().header(HttpHeaders.CACHE_CONTROL, SEASONS).body(p))
                .orElseGet(() -> ResponseEntity.notFound().build());
    }

    /** One row per sport. Read-only: it never deals a puzzle. */
    @GetMapping("/daily/yesterday")
    public ResponseEntity<List<YesterdayDaily>> yesterday() {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(daily.yesterday());
    }

    /** 404 unless the game is COMPLETE: a drafting, abandoned and missing game are indistinguishable. */
    @GetMapping("/games/{id}")
    public ResponseEntity<PublicGame> game(@PathVariable long id) {
        return games.publicGame(id).map(g -> ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(g))
                .orElseGet(() -> ResponseEntity.notFound().build());
    }

    /**
     * Spec 079: the words of a shared link's preview card (title, description, alt text, what the picture draws).
     * Built only from what the public game view keeps, so it holds no player. 404 as {@code /games/{id}}.
     */
    @GetMapping("/games/{id}/preview")
    public ResponseEntity<SharePreview> preview(@PathVariable long id) {
        // SEAM(079 -> daily-number/078): pass the game's dailyNumber once HistoricalGameState carries it on main
        return previews.preview(id, null)
                .map(p -> ResponseEntity.ok().header(HttpHeaders.CACHE_CONTROL, "public, max-age=300").body(p))
                .orElseGet(() -> ResponseEntity.notFound().build());
    }

    /** Spec 079: the 1200x630 card. 404 as above; 503 when every render slot stays busy or the render fails. */
    @GetMapping(value = "/games/{id}/preview.png", produces = MediaType.IMAGE_PNG_VALUE)
    public ResponseEntity<byte[]> previewPng(@PathVariable long id) {
        try {
            return previews.png(id)
                    .map(b -> ResponseEntity.ok().header(HttpHeaders.CACHE_CONTROL, "public, max-age=86400")
                            .contentType(MediaType.IMAGE_PNG).body(b))
                    .orElseGet(() -> ResponseEntity.notFound().build());
        } catch (SharePreviewBusyException | SharePreviewRenderException e) {
            if (!(e instanceof SharePreviewBusyException)) log.warn("share preview render failed for game {}: {}", id, e.toString());
            return ResponseEntity.status(503).header(HttpHeaders.RETRY_AFTER, "5")
                    .header(HttpHeaders.CACHE_CONTROL, "no-store").build();
        }
    }

    private HistoricalSport knownSport(String code) {
        try {
            return sports.get(code == null ? null : code.toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            return null;
        }
    }
}
