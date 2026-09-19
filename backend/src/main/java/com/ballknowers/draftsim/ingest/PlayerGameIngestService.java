package com.ballknowers.draftsim.ingest;

import com.ballknowers.draftsim.domain.Sport;
import com.ballknowers.draftsim.store.LeagueRepository;
import com.ballknowers.draftsim.store.PlayerGameRepository;
import com.ballknowers.draftsim.store.RosterWeekPointsRepository;
import com.ballknowers.draftsim.store.JsonUtil;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.util.*;

/**
 * Per-game stat lines for the players a league actually rostered
 * (specs/005-daily-weekly-top-players, US1).
 *
 * <p><b>Deliberately not part of any ingest chain.</b> This costs one upstream
 * call per player -- measured 331 players for the reference league's 2025
 * season, 280 for 2024 -- and serves one page for one sport. Feature 004 made
 * the opposite call for transactions and wired them into
 * {@link LeagueHistoryIngestService}, correctly: those are week-level data on
 * the same cadence as the weekly points that walk already fetches. These are
 * not. Folding them in would make every routine league ingest hundreds of calls
 * slower for data most leagues never read.
 *
 * <p>The player set is league-scoped only in how it is <i>chosen</i>. The rows
 * written are not: a game belongs to a sport and a season, and two leagues
 * share it.
 */
@Service
public class PlayerGameIngestService {

    private static final Logger log = LoggerFactory.getLogger(PlayerGameIngestService.class);

    private final SleeperPlayerStatsClient stats;
    private final LeagueRepository leagues;
    private final RosterWeekPointsRepository weekPoints;
    private final PlayerGameRepository games;

    public PlayerGameIngestService(SleeperPlayerStatsClient stats, LeagueRepository leagues,
                                   RosterWeekPointsRepository weekPoints, PlayerGameRepository games) {
        this.stats = stats;
        this.leagues = leagues;
        this.weekPoints = weekPoints;
        this.games = games;
    }

    /**
     * Counted and returned, not merely logged. A backfill nobody can see the
     * result of is how feature 004 ended up with five leagues holding zero
     * transactions while the suite stayed green.
     */
    public record Result(int playersWalked, int gamesStored, int playersFailed) {}

    public Result ingest(String sleeperLeagueId, Integer requestedSeason) {
        Optional<LeagueRepository.LeagueRow> found = leagues.bySleeperId(sleeperLeagueId);
        if (found.isEmpty()) return new Result(0, 0, 0);
        LeagueRepository.LeagueRow league = found.get();
        int season = requestedSeason == null ? league.season() : requestedSeason;
        Sport sport = league.sport();

        Set<String> playerIds = rosteredPlayers(league.id(), season);
        int stored = 0, failed = 0;

        for (String playerId : playerIds) {
            try {
                Map<String, List<Map<String, Object>>> byWeek =
                        stats.seasonByWeek(sport.code(), playerId, season);
                if (byWeek == null) continue;
                for (List<Map<String, Object>> entries : byWeek.values()) {
                    if (entries == null) continue;
                    for (Map<String, Object> entry : entries) {
                        PlayerGameRepository.Row row = toRow(sport, season, playerId, entry);
                        if (row == null) continue;
                        games.upsert(row);
                        stored++;
                    }
                }
            } catch (RuntimeException e) {
                // One player's failure must not cost the other 330. Counted so
                // the caller sees it; a silently partial backfill is worse than
                // a loud one.
                failed++;
                log.warn("player-games: {} season {} player {} failed: {}",
                        sleeperLeagueId, season, playerId, e.toString());
            }
        }

        log.info("player-games: league {} season {} -- {} players walked, {} games stored, {} failed",
                sleeperLeagueId, season, playerIds.size(), stored, failed);
        return new Result(playerIds.size(), stored, failed);
    }

    /**
     * Every player who appears in the league's stored weekly points for the
     * season -- which is exactly the set whose games anyone could ask about,
     * and no wider.
     */
    private Set<String> rosteredPlayers(long leagueId, int season) {
        Set<String> ids = new LinkedHashSet<>();
        for (RosterWeekPointsRepository.WeekBreakdown w : weekPoints.breakdownsFor(leagueId, season)) {
            if (w.playersPointsJson() == null || w.playersPointsJson().isBlank()) continue;
            ids.addAll(JsonUtil.readMap(w.playersPointsJson()).keySet());
        }
        return ids;
    }

    /**
     * One upstream entry to a row, or null if it is not a usable game.
     *
     * <p>The week comes from the entry's own {@code week} field and never from
     * {@code date}. That is what puts a postponed game in the week it was
     * played rather than the week it was scheduled, without this service
     * knowing anything about calendars (research R6).
     */
    static PlayerGameRepository.Row toRow(Sport sport, int season, String playerId,
                                          Map<String, Object> entry) {
        Object gameId = entry.get("game_id");
        Object date = entry.get("date");
        Object week = entry.get("week");
        // All three are required: a game with no id cannot be deduplicated, one
        // with no date cannot be a "night", and one with no week cannot be
        // placed. Dropping it is honest; inventing any of them is not.
        if (gameId == null || date == null || !(week instanceof Number w)) return null;

        Object statsObj = entry.get("stats");
        if (!(statsObj instanceof Map<?, ?> m) || m.isEmpty()) return null;

        LocalDate gameDate;
        try {
            gameDate = LocalDate.parse(String.valueOf(date));
        } catch (RuntimeException e) {
            return null;
        }

        Object opponent = entry.get("opponent");
        Object away = entry.get("is_away_team");

        return new PlayerGameRepository.Row(
                sport, season, w.intValue(), playerId, String.valueOf(gameId), gameDate,
                opponent == null ? null : String.valueOf(opponent),
                away instanceof Boolean b ? b : null,
                JsonUtil.write(m));
    }
}
