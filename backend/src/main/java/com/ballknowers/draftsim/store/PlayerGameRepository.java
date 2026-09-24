package com.ballknowers.draftsim.store;

import com.ballknowers.draftsim.domain.Sport;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.Set;

/**
 * One row per player per game (specs/005-daily-weekly-top-players, US1).
 *
 * <p>Not league-scoped, and stores the raw stat line rather than a points
 * figure: two leagues in a sport and season share every game and differ only in
 * how they score it. See {@code V20__player_game.sql} for the argument, which is
 * V15's argument about projections in a second place.
 *
 * <p>So nothing here knows about a league. Turning a row into points is
 * {@code GameScoringService}'s job, given the asking league's scoring.
 */
@Repository
public class PlayerGameRepository {

    private final JdbcClient db;

    public PlayerGameRepository(JdbcClient db) {
        this.db = db;
    }

    /**
     * One player's one game.
     *
     * @param week the fantasy week from the upstream entry's own field -- never
     *             derived from {@code gameDate}, which is what makes a postponed
     *             game land in the week it was played
     * @param opponent nullable; a payload without it still stores the game
     * @param isAway nullable, for the same reason
     */
    public record Row(Sport sport, int season, int week, String sleeperPlayerId, String gameId,
                      LocalDate gameDate, String opponent, Boolean isAway, String statsJson) {}

    /**
     * Idempotent on {@code (sleeper_player_id, game_id)}.
     *
     * <p>Updates rather than doing nothing on conflict, because a settled game is
     * not frozen: stat corrections land after the fact, measured at ~25-50 points
     * of drift across a season's roster totals. A backfill re-run is how a
     * correction reaches this table, so it has to overwrite.
     */
    public void upsert(Row r) {
        db.sql("""
                insert into player_game (sport, season, week, sleeper_player_id, game_id, game_date,
                                         opponent, is_away, stats, fetched_at)
                values (?, ?, ?, ?, ?, ?, ?, ?, ?::jsonb, now())
                on conflict (sleeper_player_id, game_id) do update set
                    sport = excluded.sport,
                    season = excluded.season,
                    week = excluded.week,
                    game_date = excluded.game_date,
                    opponent = excluded.opponent,
                    is_away = excluded.is_away,
                    stats = excluded.stats,
                    fetched_at = now()
                """)
                .params(r.sport().code(), r.season(), r.week(), r.sleeperPlayerId(), r.gameId(),
                        java.sql.Date.valueOf(r.gameDate()), r.opponent(), r.isAway(), r.statsJson())
                .update();
    }

    /**
     * Removes a specific game, keyed by (sleeper_player_id, game_id)
     * (coordinator follow-up 2026-09-23, item 2): called when a later ingest
     * run sees the SAME game as an ENTRY_WITHOUT_PLAY (a stat correction, or a
     * game that briefly showed a box score before being corrected to a DNP).
     * Without this, both a player_game row and an absence row would exist for
     * the one game. A no-op if no such row exists.
     */
    public void deleteByGame(Sport sport, int season, String sleeperPlayerId, String gameId) {
        db.sql("delete from player_game where sport = ? and season = ? and sleeper_player_id = ? and game_id = ?")
                .params(sport.code(), season, sleeperPlayerId, gameId)
                .update();
    }

    /** Every game in one fantasy week. The read path both ranking sections use. */
    public List<Row> forWeek(Sport sport, int season, int week) {
        return db.sql("""
                select sport, season, week, sleeper_player_id, game_id, game_date, opponent,
                       is_away, stats::text as stats
                from player_game
                where sport = ? and season = ? and week = ?
                """)
                .params(sport.code(), season, week)
                .query((rs, n) -> new Row(
                        Sport.fromCode(rs.getString("sport")),
                        rs.getInt("season"),
                        rs.getInt("week"),
                        rs.getString("sleeper_player_id"),
                        rs.getString("game_id"),
                        rs.getDate("game_date").toLocalDate(),
                        rs.getString("opponent"),
                        (Boolean) rs.getObject("is_away"),
                        rs.getString("stats")))
                .list();
    }

    /**
     * Every game played by a specific set of players in one sport and season
     * (specs/008-season-superlatives, research R10): the input to a player's
     * mean points per game played, over however many weeks are stored.
     */
    public List<Row> forPlayers(Sport sport, int season, Collection<String> playerIds) {
        if (playerIds == null || playerIds.isEmpty()) return List.of();
        String placeholders = String.join(", ", Collections.nCopies(playerIds.size(), "?"));
        String sql = """
                select sport, season, week, sleeper_player_id, game_id, game_date, opponent,
                       is_away, stats::text as stats
                from player_game
                where sport = ? and season = ? and sleeper_player_id in (%s)
                """.formatted(placeholders);
        List<Object> params = new ArrayList<>();
        params.add(sport.code());
        params.add(season);
        params.addAll(playerIds);
        return db.sql(sql)
                .params(params)
                .query((rs, n) -> new Row(
                        Sport.fromCode(rs.getString("sport")),
                        rs.getInt("season"),
                        rs.getInt("week"),
                        rs.getString("sleeper_player_id"),
                        rs.getString("game_id"),
                        rs.getDate("game_date").toLocalDate(),
                        rs.getString("opponent"),
                        (Boolean) rs.getObject("is_away"),
                        rs.getString("stats")))
                .list();
    }

    /**
     * Which players already have games stored for a season.
     *
     * <p>Reported rather than used as a skip gate. The backfill refetches every
     * player deliberately -- a player present here may still be missing their
     * most recent games, and R6's whole point is that "has rows" and "has all its
     * rows" are different questions. Skipping on presence is the bug this repo
     * has already shipped once, on roster_week_points.
     */
    public Set<String> playersWithGames(Sport sport, int season) {
        // .set() (not .stream()): JdbcClient's stream() holds the connection open
        // until the Stream itself is closed, and this call site never closes it --
        // a live connection leak, one Hikari connection per call, that hung the
        // whole pool after ~10 page loads (specs/008-season-superlatives, found in
        // live verification 2026-09-23). This was dead code on main (spec 005)
        // until SeasonSuperlativesService started calling it every page load.
        // Regression coverage: PlayerGameRepositoryConnectionLeakIT.
        return db.sql("select distinct sleeper_player_id from player_game where sport = ? and season = ?")
                .params(sport.code(), season)
                .query(String.class)
                .set();
    }
}
