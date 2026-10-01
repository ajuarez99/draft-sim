package com.ballknowers.draftsim.refresh;

import com.ballknowers.draftsim.domain.Sport;
import com.ballknowers.draftsim.engine.LeagueSeasonResolver;
import com.ballknowers.draftsim.ingest.LeagueHistoryIngestService;
import com.ballknowers.draftsim.ingest.PlayerGameIngestService;
import com.ballknowers.draftsim.store.LeagueRefreshRepository;
import com.ballknowers.draftsim.store.SportWeekStatsRepository;
import com.ballknowers.draftsim.store.LeagueRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * Refreshes a league on visit (specs/009-auto-data-refresh, US1).
 *
 * <p><b>The unit of refresh is the chain</b> (research R2, amended after
 * analysis): {@code ingestChain} already walks every season, so one refresh
 * runs per chain, keyed by the chain's newest league id, with the
 * {@code loaded_complete} seasons passed as its {@code skip} set. Per-game stats
 * are then refreshed per non-skipped season, through their own
 * {@code sport:season} single-flight inside {@link PlayerGameIngestService}.
 *
 * <p>{@link #trigger} never waits: it decides, submits at most one chain
 * refresh, and returns the state. "Running" is not stored -- it is the chain key
 * being in flight (research R4/R5), so a restart cannot leave a season stuck.
 * With {@code refresh.on-visit.enabled=false} {@code trigger} behaves like
 * {@link #status}.
 */
@Service
public class LeagueRefreshService {

    private static final Logger log = LoggerFactory.getLogger(LeagueRefreshService.class);

    public enum State { FRESH, RUNNING, FAILED, COMPLETE }

    public record SeasonStatus(String leagueSleeperId, int season, State state) {}

    /**
     * @param leagueSleeperId, season the league-season the page shows (after the
     *                         resolver walks back), not necessarily the URL's
     * @param state            RUNNING whenever the chain is in flight; otherwise the shown season's state
     * @param lastSuccessAt    the newest success across the chain's seasons; nullable
     * @param lastFailureAt    the newest failure across the chain, only if newer than
     *                         {@code lastSuccessAt}; nullable
     * @param seasons          every chain season, newest first
     */
    public record Status(State state, String leagueSleeperId, int season, Instant lastSuccessAt,
                         Instant lastFailureAt, List<SeasonStatus> seasons) {}

    private final LeagueRepository leagues;
    private final LeagueRefreshRepository refreshes;
    private final LeagueSeasonResolver resolver;
    private final LeagueHistoryIngestService history;
    private final PlayerGameIngestService playerGames;
    private final RefreshProperties properties;
    private final SportWeekStatsRepository sportWeeks;
    private final TrendingRefresh trendingRefresh;

    /** Keyed {@code chain:<newest league sleeper id>}. Separate from the per-sport-season flight it waits on. */
    private final SingleFlight chainFlight = new SingleFlight();

    public LeagueRefreshService(LeagueRepository leagues, LeagueRefreshRepository refreshes,
                                LeagueSeasonResolver resolver, LeagueHistoryIngestService history,
                                PlayerGameIngestService playerGames, RefreshProperties properties,
                                SportWeekStatsRepository sportWeeks, TrendingRefresh trendingRefresh) {
        this.trendingRefresh = trendingRefresh;
        this.leagues = leagues;
        this.refreshes = refreshes;
        this.resolver = resolver;
        this.history = history;
        this.playerGames = playerGames;
        this.properties = properties;
        this.sportWeeks = sportWeeks;
    }

    /** Empty when no such league is stored. Starts a refresh if any chain season is stale. */
    public Optional<Status> trigger(String sleeperLeagueId) {
        List<LeagueRepository.LeagueRow> chain = leagues.chainBySleeperId(sleeperLeagueId);
        if (chain.isEmpty()) return Optional.empty();

        if (properties.onVisitEnabled()) {
            Instant now = Instant.now();
            boolean anyStart = false;
            Set<String> loadedComplete = new HashSet<>();
            for (LeagueRepository.LeagueRow season : chain) {
                LeagueRefreshRepository.Row state = refreshes.find(season.id()).orElse(null);
                RefreshDecision.Decision decision = RefreshDecision.decide(state, season.status(), now);
                if (decision == RefreshDecision.Decision.START) anyStart = true;
                if (decision == RefreshDecision.Decision.SKIP_COMPLETE) loadedComplete.add(season.sleeperId());
            }
            if (anyStart) {
                String newest = chain.getFirst().sleeperId();
                Sport sport = chain.getFirst().sport();
                // A second trigger while this key is in flight gets the same future.
                String key = chainKey(chain);
                boolean alreadyRunning = chainFlight.isRunning(key);
                var flight = chainFlight.run(key, () -> refreshChain(sport, newest, Set.copyOf(loadedComplete)));
                // Log-only: an Error, or an exception inside refreshChain's own catch (say
                // recordFailure during a DB outage), would otherwise vanish with the future.
                if (!alreadyRunning) {
                    flight.whenComplete((r, t) -> {
                        if (t != null) log.error("refresh: chain {} run ended abnormally", newest, t);
                    });
                }
            }
        }
        return status(sleeperLeagueId);
    }

    /** The same view as {@link #trigger}, with nothing started. */
    public Optional<Status> status(String sleeperLeagueId) {
        List<LeagueRepository.LeagueRow> chain = leagues.chainBySleeperId(sleeperLeagueId);
        if (chain.isEmpty()) return Optional.empty();

        boolean chainRunning = chainFlight.isRunning(chainKey(chain));
        List<SeasonStatus> seasons = new ArrayList<>();
        Instant newestSuccess = null;
        Instant newestFailure = null;
        for (LeagueRepository.LeagueRow season : chain) {
            LeagueRefreshRepository.Row row = refreshes.find(season.id()).orElse(null);
            seasons.add(new SeasonStatus(season.sleeperId(), season.season(), stateOf(row, chainRunning)));
            if (row != null) {
                newestSuccess = newer(newestSuccess, row.lastSuccessAt());
                newestFailure = newer(newestFailure, row.lastFailureAt());
            }
        }

        LeagueRepository.LeagueRow shown = resolver.resolve(sleeperLeagueId)
                .map(LeagueSeasonResolver.Resolved::league)
                .orElse(chain.getFirst());
        LeagueRefreshRepository.Row shownRow = refreshes.find(shown.id()).orElse(null);
        // Top level is about the CHAIN's refresh, which is what the rail watches: RUNNING
        // whenever the chain is in flight, even when the shown season is itself complete
        // (NBA offseason: the resolver shows 2025 while 2026 refreshes) -- review fix
        // 2026-09-28. Otherwise the shown season's own state.
        State topState = chainRunning ? State.RUNNING : stateOf(shownRow, false);
        // A refresh writes every non-skipped season, so the newest success across the
        // chain is the "updated" moment; a shown-only timestamp never moves while a
        // newer season refreshes. A failure is reported only if newer than that success,
        // so lastFailureAt and FAILED never disagree with lastSuccessAt.
        Instant failure = newestFailure != null && (newestSuccess == null || newestFailure.isAfter(newestSuccess))
                ? newestFailure : null;
        return Optional.of(new Status(topState, shown.sleeperId(), shown.season(),
                newestSuccess, failure, seasons));
    }

    private static Instant newer(Instant a, Instant b) {
        if (a == null) return b;
        if (b == null) return a;
        return b.isAfter(a) ? b : a;
    }

    private static State stateOf(LeagueRefreshRepository.Row row, boolean chainRunning) {
        if (row != null && row.loadedComplete()) return State.COMPLETE;   // never fetched again, so never RUNNING
        if (chainRunning) return State.RUNNING;
        if (RefreshDecision.failed(row)) return State.FAILED;
        return State.FRESH;
    }

    /**
     * The single-flight key for a lineage: its <b>oldest</b> season, not its newest.
     *
     * <p>Fixed in live verification (2026-09-28). {@code chainBySleeperId} only walks
     * <i>backwards</i> from the id it's given, so a page for the 2025 season sees
     * [2025, 2024] while a page for 2026 sees [2026, 2025, 2024]. Keyed by the newest
     * season, those two pages built different keys: a status poll from the 2025 page
     * answered FRESH while a chain started from 2026 was still running, so the rail
     * stopped polling and refetched stale data. The oldest season is the same from
     * every starting point.
     */
    private static String chainKey(List<LeagueRepository.LeagueRow> chainNewestFirst) {
        return "chain:" + chainNewestFirst.getLast().sleeperId();
    }

    /**
     * The chain refresh. Never throws: a failure is recorded against every
     * non-skipped season and stored data is left as the walk left it (FR-006).
     *
     * @return the number of seasons refreshed successfully, for logs and tests
     */
    private int refreshChain(Sport sport, String newestLeagueSleeperId, Set<String> skip) {
        Instant startedAt = Instant.now();
        List<LeagueRepository.LeagueRow> targets = List.of();
        try {
            history.ingestChain(sport, newestLeagueSleeperId, skip);

            // Re-read the chain: the walk may have stored seasons that were not
            // in the database when this refresh was decided.
            targets = new ArrayList<>();
            for (LeagueRepository.LeagueRow season : leagues.chainBySleeperId(newestLeagueSleeperId)) {
                if (!skip.contains(season.sleeperId())) targets.add(season);
            }

            int weeksFailed = 0;
            for (LeagueRepository.LeagueRow season : targets) {
                weeksFailed += playerGames.refreshSportSeason(sport, season.season(), startedAt).weeksFailed();
            }
            // Sport-wide and best-effort (spec 014): never throws, and its outcome is
            // deliberately not read, so it can change neither weeksFailed nor success.
            // Before the throw below so it runs even when per-game fetching failed.
            trendingRefresh.refreshIfStale(sport, startedAt);
            // A per-game week that failed to fetch left its data behind; that is a
            // failed refresh, not a complete one, or loaded_complete could freeze a gap.
            if (weeksFailed > 0) {
                throw new IllegalStateException(weeksFailed + " per-game week fetch(es) failed");
            }

            Instant finishedAt = Instant.now();
            for (LeagueRepository.LeagueRow season : targets) {
                // The status this walk stored, read back from the row it just upserted.
                String statusSeen = leagues.bySleeperId(season.sleeperId()).map(LeagueRepository.LeagueRow::status).orElse(null);
                List<SportWeekStatsRepository.Row> weeks = sportWeeks.forSeason(sport, season.season());
                boolean perGameAllFinal = !weeks.isEmpty() && weeks.stream().allMatch(SportWeekStatsRepository.Row::fin);
                refreshes.recordSuccess(season.id(), finishedAt,
                        RefreshDecision.completeAfter(statusSeen, true, perGameAllFinal));
            }
            log.info("refresh: chain {} -- {} season(s) refreshed, {} skipped as complete",
                    newestLeagueSleeperId, targets.size(), skip.size());
            return targets.size();
        } catch (Exception e) {
            log.warn("refresh: chain {} failed", newestLeagueSleeperId, e);
            String reason = reasonOf(e);
            Instant failedAt = Instant.now();
            if (targets.isEmpty()) {
                // Failed before the chain was re-read: fall back to what is stored now.
                for (LeagueRepository.LeagueRow season : leagues.chainBySleeperId(newestLeagueSleeperId)) {
                    if (!skip.contains(season.sleeperId())) refreshes.recordFailure(season.id(), failedAt, reason);
                }
            } else {
                for (LeagueRepository.LeagueRow season : targets) {
                    refreshes.recordFailure(season.id(), failedAt, reason);
                }
            }
            return 0;
        }
    }

    /** Short, for the row and for logs; the full exception is logged separately. */
    private static String reasonOf(Exception e) {
        String message = e.getMessage() == null ? "" : e.getMessage();
        String reason = e.getClass().getSimpleName() + (message.isEmpty() ? "" : ": " + message);
        return reason.length() <= 200 ? reason : reason.substring(0, 200);
    }
}
